#!/usr/bin/env python3
"""
提交前自检总入口。

环境里没有 JDK / Android SDK，gradle 跑不了，所以用这三套校验兜底：

  verify_structure.py —— 结构 + 跨文件符号解析（替代编译）
  verify_textlayout.py —— TextLayout 纯逻辑镜像（阅读顺序 / 断句）
  verify_grabber.py   —— TextGrabber 完整流程镜像（收集 / 排序 / 拼装 / 回收）

跑三遍，全绿才准提交。
"""

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SUITES = ["verify_structure.py", "verify_textlayout.py", "verify_grabber.py"]
PASSES = 3


def main():
    total_failed = 0
    for i in range(1, PASSES + 1):
        print("########## 第 %d 轮自检 ##########" % i)
        for s in SUITES:
            print("---- %s ----" % s)
            r = subprocess.call(
                [sys.executable, os.path.join(ROOT, s)], cwd=ROOT)
            if r != 0:
                total_failed += 1
                print("!! %s 未通过 (exit=%d)" % (s, r))
        print()

    if total_failed:
        raise SystemExit("自检未通过：共 %d 次失败" % total_failed)
    print("三轮自检全部通过 ✅")


if __name__ == "__main__":
    main()
