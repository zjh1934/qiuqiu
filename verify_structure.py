#!/usr/bin/env python3
"""
无 JDK / Android SDK 环境下的 Java 源码结构自检。

真机编译跑不了（环境里没有 gradle / SDK），所以用这套静态检查兜底，
专门抓「改坏了但肉眼看不出来」的几类问题：

  1. 括号不配对（字符串 / 注释里的括号不算）
  2. 跨文件符号解析 —— ProjectClass.member(...) 里 member 不存在。
     这是最容易犯的错：改方法名、改签名后，调用方忘了跟着改。
  3. 引用了没 import 的类
  4. import 了没用上的类（只警告）

解析是近似实现（不是完整的 Java 语法分析器），
宁可漏报也不误报 —— 报出来的必须都是真问题。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC_DIRS = [
    os.path.join(ROOT, "app", "src", "main", "java"),
    os.path.join(ROOT, "app", "src", "test", "java"),
]

# 这些不是方法调用
NOT_CALLS = {
    "if", "for", "while", "switch", "catch", "synchronized", "return",
    "new", "else", "do", "try", "finally", "throw", "throws", "case",
    "super", "this", "instanceof", "assert",
}

# 不在源码树里、但一定存在的类（只放真正自动生成的，别拿它掩盖漏写的 import）
EXTERNAL_OK = {
    "R",      # aapt 生成的资源类
}

# 从框架父类继承来的常量，源码里不会有声明
FRAMEWORK_INHERITED = {
    "WINDOW_SERVICE", "NOTIFICATION_SERVICE", "MODE_PRIVATE",
    "LAYOUT_INFLATER_SERVICE", "INPUT_METHOD_SERVICE", "POWER_SERVICE",
    "AUDIO_SERVICE", "KEYGUARD_SERVICE", "CLIPBOARD_SERVICE", "VIBRATOR_SERVICE", "RESULT_OK", "RESULT_CANCELED",
    # R 由 aapt2 在编译期生成，源码里本来就没有
    "R",
}

TYPE_KEYWORD = r"(?:class|interface|enum)"


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def strip_literals(src):
    """去掉注释、字符串、字符字面量，位置用空格补齐（保持下标对齐）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            out.append("  ")
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                out.append(" " if src[i] != "\n" else "\n")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
        elif c == '"':
            out.append(" ")
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i < n:
                        out.append(" ")
                        i += 1
                    continue
                out.append(" " if src[i] != "\n" else "\n")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
        elif c == "'":
            out.append(" ")
            i += 1
            while i < n and src[i] != "'":
                if src[i] == "\\":
                    out.append(" ")
                    i += 1
                    if i < n:
                        out.append(" ")
                        i += 1
                    continue
                out.append(" ")
                i += 1
            if i < n:
                out.append(" ")
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def match_block(s, i):
    """s[i] == '{'，返回配对的 '}' 下标；不配对返回 -1。"""
    depth = 0
    j = i
    n = len(s)
    while j < n:
        if s[j] == "{":
            depth += 1
        elif s[j] == "}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def find_classes(code):
    """
    找出所有类/接口/枚举声明，返回 [(name, declStart, braceOpen, braceClose)]。
    匿名类（new Runnable() {}）不含 class 关键字，不会被算进来。
    """
    out = []
    for m in re.finditer(r"\b%s\s+(\w+)" % TYPE_KEYWORD, code):
        j = code.find("{", m.end())
        if j < 0:
            continue
        end = match_block(code, j)
        if end < 0:
            continue
        out.append((m.group(1), m.start(), j, end))
    return out


def own_body(code, cls, classes):
    """把类自己的方法体挖出来，嵌套类的部分抹掉。"""
    _, s, b, e = cls
    chars = list(code)
    for other in classes:
        if other is cls:
            continue
        _, os_, ob, oe = other
        if ob > b and oe < e:
            for k in range(ob, oe + 1):
                chars[k] = " "
    return "".join(chars[b + 1:e])


MODIFIERS = (r"(?:public|protected|private|static|final|abstract|synchronized"
             r"|native|strictfp|volatile|transient)\s+")

METHOD_RE = re.compile(
    r"(?:^|[;{}])\s*(?:@\w+\s*)*(?:%s)*[\w.$]+(?:<[^;=(){}]*>)?(?:\[\s*\])*\s+(\w+)\s*(?=\()"
    % MODIFIERS, re.M)

FIELD_RE = re.compile(
    r"(?:^|[;{}])\s*(?:@\w+\s*)*(?:%s)*[\w.$]+(?:<[^;=(){}]*>)?(?:\[\s*\])*\s+(\w+)\s*(?=[=;])"
    % MODIFIERS, re.M)


def members_of(body):
    names = set()
    for m in METHOD_RE.finditer(body):
        n = m.group(1)
        if n not in NOT_CALLS:
            names.add(n)
    for m in FIELD_RE.finditer(body):
        n = m.group(1)
        if n not in NOT_CALLS:
            names.add(n)
    for m in re.finditer(r"\b(?:class|interface|enum)\s+(\w+)", body):
        names.add(m.group(1))   # 嵌套类型也能用 Outer.Inner 引用
    return names


def build_index():
    files = []
    for d in SRC_DIRS:
        if not os.path.isdir(d):
            continue
        for root, _, names in os.walk(d):
            for n in names:
                if n.endswith(".java"):
                    files.append(os.path.join(root, n))
    files.sort()

    index = {}   # class name -> set(members)
    packages = {}  # class name -> package
    per_file = []
    for f in files:
        code = strip_literals(read(f))
        pkg = None
        m = re.search(r"package\s+([\w.]+)\s*;", code)
        if m:
            pkg = m.group(1)
        classes = find_classes(code)
        info = {
            "path": f,
            "code": code,
            "package": pkg,
            "classes": classes,
            "imports": re.findall(r"import\s+(static\s+)?([\w.]+)\s*;", code),
        }
        for c in classes:
            body = own_body(code, c, classes)
            index.setdefault(c[0], set()).update(members_of(body))
            packages[c[0]] = pkg
        per_file.append(info)
    return index, packages, per_file


def check_balance(info):
    """括号配对检查。返回错误列表。"""
    code = info["code"]
    pairs = {"}": "{", ")": "(", "]": "["}
    stack = []
    errs = []
    for i, c in enumerate(code):
        if c in "{([":
            stack.append((c, i))
        elif c in "})]":
            if not stack:
                errs.append("第 %d 个字符多了个 '%s'" % (i, c))
            elif stack[-1][0] != pairs[c]:
                errs.append("第 %d 个字符 '%s' 和前面的 '%s' 不配对"
                            % (i, c, stack[-1][0]))
                stack.pop()
            else:
                stack.pop()
    for c, i in stack:
        errs.append("第 %d 个字符的 '%s' 没有闭合" % (i, c))
    return errs


REF_RE = re.compile(r"(?<![\w.$])([A-Z]\w*)\s*\.\s*(\w+)")

# X.class / X.this 不是成员访问
REF_SKIP_MEMBERS = {"class", "this", "super"}


def check_symbols(index, packages, per_file):
    """ProjectClass.member 必须真实存在。"""
    errs = []
    for info in per_file:
        code = info["code"]
        own = set(c[0] for c in info["classes"])
        # 本文件里所有类自己的成员都可以直接用
        mine = set()
        for c in info["classes"]:
            mine |= index.get(c[0], set())

        for m in REF_RE.finditer(code):
            cls, member = m.group(1), m.group(2)
            if cls in EXTERNAL_OK or member in REF_SKIP_MEMBERS:
                continue
            if cls in own:
                # 同文件里的类：成员应该在本文件里找得到
                if member not in mine:
                    errs.append("%s: %s.%s 不存在（本文件内未定义）"
                                % (os.path.basename(info["path"]), cls, member))
                continue
            if cls in index:
                if member not in index[cls]:
                    errs.append("%s: %s.%s 不存在（%s 里没这个成员）"
                                % (os.path.basename(info["path"]), cls, member, cls))
    return errs


def declared_names(info, index):
    """本文件里声明过的名字（方法 / 字段 / 嵌套类型）"""
    names = set()
    for c in info["classes"]:
        names.add(c[0])
        names |= index.get(c[0], set())
    return names


def check_imports(per_file, packages, index):
    """用到的类名得 import 过；import 了没用的给个警告。"""
    errs = []
    warns = []
    java_lang = {
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Character",
        "Byte", "Short", "Object", "Math", "System", "Exception", "Throwable",
        "RuntimeException", "IllegalStateException", "IllegalArgumentException",
        "Runnable", "Thread", "StringBuilder", "CharSequence", "Number",
        "Override", "Deprecated", "SuppressWarnings", "Void",
    }
    for info in per_file:
        code = info["code"]
        # 真正写了 import 的（区分普通 import 和 static import：
        # static import 进来的是方法名，小写开头，不能按类名的规则查）
        imported = set()
        static_imported = set()
        for is_static, imp in info["imports"]:
            if is_static.strip():
                static_imported.add(imp.rsplit(".", 1)[-1])
            else:
                imported.add(imp.rsplit(".", 1)[-1])

        # 同包的类不用 import。要和 imported 分开存，
        # 否则"未使用的 import"会把所有同包类都算进来，全是噪音。
        samepkg = set()
        for name, pkg in packages.items():
            if pkg == info["package"]:
                samepkg.add(name)

        used = set()
        for m in re.finditer(r"(?<![\w.$])([A-Z]\w*)", code):
            used.add(m.group(1))

        # 本文件里声明过的（类 / 方法 / 字段 / 嵌套类型）不是外部引用
        used -= declared_names(info, index)

        missing = sorted(n for n in used
                         if n not in imported
                         and n not in samepkg
                         and n not in java_lang
                         and n not in FRAMEWORK_INHERITED)
        if missing:
            errs.append("%s: 用了但没 import → %s"
                        % (os.path.basename(info["path"]), ", ".join(missing)))

        unused = sorted(n for n in imported if n not in used)
        if unused:
            warns.append("%s: import 了没用 → %s"
                         % (os.path.basename(info["path"]), ", ".join(unused)))

        called = set(m.group(1) for m in re.finditer(r"(?<![\w.$])(\w+)\s*\(", code))
        unused_static = sorted(n for n in static_imported if n not in called)
        if unused_static:
            warns.append("%s: static import 了没用 → %s"
                         % (os.path.basename(info["path"]), ", ".join(unused_static)))
    return errs, warns


def run_once():
    index, packages, per_file = build_index()
    problems = []

    for info in per_file:
        for e in check_balance(info):
            problems.append("FAIL [括号] %s: %s" % (os.path.basename(info["path"]), e))

    errs = check_symbols(index, packages, per_file)
    for e in errs:
        problems.append("FAIL [符号] " + e)

    ierrs, iwarns = check_imports(per_file, packages, index)
    for e in ierrs:
        problems.append("FAIL [import] " + e)
    for w in iwarns:
        problems.append("WARN [import] " + w)

    print("-- 扫到 %d 个源文件，%d 个类 --" % (len(per_file), len(index)))
    if problems:
        for p in problems:
            print(p)
    else:
        print("结构与符号检查全部通过 ✅")
    # 只有 FAIL 算失败
    return sum(1 for p in problems if p.startswith("FAIL"))


def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
    if total:
        raise SystemExit("结构检查累计 %d 个问题" % total)
    print("结构检查两遍全部通过 ✅")


if __name__ == "__main__":
    main()
