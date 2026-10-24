#!/usr/bin/env python3
"""
提交前自检总入口。

环境里没有 JDK / Android SDK，gradle 跑不了，所以用这几套校验兜底：

  verify_structure.py —— 结构 + 跨文件符号解析（替代编译）
  verify_textlayout.py —— TextLayout 纯逻辑镜像（阅读顺序 / 断句）
  verify_grabber.py   —— TextGrabber 完整流程镜像（收集 / 排序 / 拼装 / 回收）
  verify_speak.py     —— TtsManager 长文本切段（4000 字上限）

跑三遍，全绿才准提交。

注意：这些脚本只能挡住"逻辑改坏"，挡不住"API 用错"。
      TextGrabber 里曾经写过 AccessibilityWindowInfo.TYPE_STATUS_BAR，
      三个镜像脚本全绿，真编译一次才发现这个常量根本不存在。
      有 JDK + android.jar 的时候，务必真跑一次 javac（见 build_apk.sh）。
"""

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
SUITES = ["verify_structure.py", "verify_textlayout.py",
          "verify_grabber.py", "verify_speak.py"]
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
