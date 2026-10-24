#!/usr/bin/env python3
"""
镜像 TtsManager.split 的逻辑。

为什么单独钉这一块：
  Android 的 TextToSpeech 单次最多 4000 字（getMaxSpeechInputLength），
  超了 speak() 直接返回 ERROR —— 一个字都不念，但界面上已经进入"朗读中"。
  用户看到球变橙、听不到声音，点一下又退回待命，看起来就是"这软件坏了"。

  所以长文本必须切段。切段的两个硬要求：
    1. 每段不超过上限（否则还是被拒收）
    2. 拼回去必须和原文一模一样（不许吞字、不许重字）
  切在标点上只是听感优化，切不到就硬切 —— 宁可听着断一下，也不能整段不念。

真正的 Java 在 TtsManager.java，MAX_CHUNK 两边必须一致。
"""

MAX_CHUNK = 3600

PUNCT = set("。！？；\n.!?;，,、")


def split_text(text):
    out = []
    t = text.strip()
    while len(t) > 0:
        if len(t) <= MAX_CHUNK:
            out.append(t)
            break
        cut = -1
        for i in range(MAX_CHUNK - 1, MAX_CHUNK // 2 - 1, -1):
            if t[i] in PUNCT:
                cut = i + 1
                break
        if cut <= 0:
            cut = MAX_CHUNK
        out.append(t[:cut])
        t = t[cut:]
    return out


def run_once():
    cases = []

    def check(name, got, want):
        cases.append((name, got, want))

    def check_true(name, cond):
        cases.append((name, bool(cond), True))

    # 短文本原样返回
    check("短文本 → 1 段", split_text("你好，世界。"), ["你好，世界。"])
    check("刚好等于上限 → 1 段", split_text("字" * MAX_CHUNK), ["字" * MAX_CHUNK])
    check("空串 → 0 段", split_text(""), [])
    check("只有空白 → 0 段", split_text("   \n  "), [])
    check("首尾空白去掉", split_text("  正文  "), ["正文"])

    # 超长文本
    long_cn = ("这是一句话，用来测试切段。" * 500)          # 6500 字
    parts = split_text(long_cn)
    check_true("超长文本被切成多段", len(parts) > 1)
    check_true("每段都不超上限", all(len(p) <= MAX_CHUNK for p in parts))
    check("拼回去和原文一致（不吞不重）", "".join(parts), long_cn.strip())

    # 有标点时要切在标点上
    punct_text = ("甲" * (MAX_CHUNK - 10)) + "。" + ("乙" * 100)
    p2 = split_text(punct_text)
    check_true("优先切在句号后", p2[0].endswith("。"))
    check("切在标点后仍然拼得回原文", "".join(p2), punct_text)

    # 没标点就硬切
    no_punct = "字" * (MAX_CHUNK * 2 + 100)
    p3 = split_text(no_punct)
    check("没有标点时按上限硬切", [len(x) for x in p3], [MAX_CHUNK, MAX_CHUNK, 100])
    check("硬切也拼得回原文", "".join(p3), no_punct)

    # 换行也算断点（网页/聊天记录很多这种）
    nl_text = ("甲" * (MAX_CHUNK - 5)) + "\n" + ("乙" * 50)
    p4 = split_text(nl_text)
    check_true("换行也能当断点", p4[0].endswith("\n"))

    # 英文标点
    en_text = ("a" * (MAX_CHUNK - 5)) + ". " + ("b" * 50)
    p5 = split_text(en_text)
    check_true("英文句点也能当断点", p5[0].endswith("."))

    failed = 0
    for name, got_v, want in cases:
        ok = got_v == want
        failed += 0 if ok else 1
        if not ok:
            print("FAIL -", name, "\n    got =", repr(got_v)[:160],
                  "\n    want=", repr(want)[:160])
        else:
            print("PASS -", name)
    return failed


def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
        print()
    if total:
        raise SystemExit("切段逻辑累计 %d 个用例失败" % total)
    print("切段逻辑两遍全部通过 ✅")


if __name__ == "__main__":
    main()
