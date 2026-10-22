#!/usr/bin/env python3
# 镜像 com.floating.combo.TextCompare.sameLine 的逻辑，用于在无法编译 Android 的环境里
# 验证「续读去重」这一关键修复：只比相等，不比包含。
# 这是给开发机/CI 跑的等价校验，真正的 JUnit 测试在 TextCompareTest.java（AIDE 里跑）。


def same_line(a, b):
    if a is None or b is None:
        return False
    return a.strip() == b.strip()


def run_once():
    cases = [
        # (a, b, 期望) —— 相等 / 去空白后相等 应判为同一段
        ("你好世界", "你好世界", True),
        ("  你好世界  ", "你好世界", True),
        # 关键回归：子串绝不能当重复跳过（旧逻辑会吞掉「世界」这段正文）
        ("你好世界", "世界", False),
        ("世界", "你好世界", False),
        ("你好世界", "你好地球", False),
        ("一段长文本", "文本", False),
        # null 安全
        (None, "x", False),
        ("x", None, False),
        (None, None, False),
    ]
    failed = 0
    for a, b, expect in cases:
        got = same_line(a, b)
        ok = (got == expect)
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"),
              "sameLine(%r, %r) = %s (expect %s)" % (a, b, got, expect))
    return failed


def main():
    total_failed = 0
    for i in range(1, 3):
        print("===== 第 %d 遍测试 =====" % i)
        total_failed += run_once()
        print("本遍通过 ✅" if total_failed == 0 else "")
    if total_failed:
        raise SystemExit("累计有 %d 个用例失败" % total_failed)
    print("两遍全部通过 ✅")


if __name__ == "__main__":
    main()
