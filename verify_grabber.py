#!/usr/bin/env python3
"""
镜像 TextGrabber 的完整抓字流程：roots → walk → sortReadingOrder → join。

TextGrabber 自己依赖 Android API，没法在这里跑；但它的算法是确定的，
所以用一棵合成的假节点树把整个流水线复现一遍，验证：

  - 收录的是"最靠下那批"文字节点，父子不重复贴
  - 滚出屏幕的节点不读；isVisibleToUser 撒谎的 ROM 有兜底
  - 多列布局按【行】读，不会列间乱序
  - 命中段按句读切割，续读不重不漏

上限常量直接从 TextGrabber.java 里正则解析出来，
避免 Java 改了、这里的镜像还停留在旧值。
"""

import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_textlayout import (  # noqa: E402
    Node, RectF, clean, nearest_char, sentence_start,
    leading_whitespace, trimmed_length, sort_reading_order,
)

# 镜像 walk 是递归的，深树测试需要抬高上限
sys.setrecursionlimit(20000)

ROOT = os.path.dirname(os.path.abspath(__file__))
GRABBER = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "floating", "combo",
    "TextGrabber.java")

_src = io.open(GRABBER, encoding="utf-8").read()


def _const(name, default):
    m = re.search(
        r"private\s+static\s+final\s+int\s+%s\s*=\s*(\d+)\s*;" % name, _src)
    return int(m.group(1)) if m else default


MAX_WALK = _const("MAX_WALK", 10000)
MAX_SEG = _const("MAX_SEG", 400)
MAX_DEPTH = _const("MAX_DEPTH", 200)


# ---------- 假节点 ----------

class FakeNode(object):
    def __init__(self, text=None, desc=None, bounds=(0, 0, 0, 0),
                 visible=True, children=None, char_rects=None):
        self._text = text
        self._desc = desc
        self.bounds = bounds
        self.visible = visible
        self.children = children if children is not None else []
        self.char_rects = char_rects
        self.recycled = False

    def getText(self):
        return self._text

    def getContentDescription(self):
        return self._desc

    def getBoundsInScreen(self, out):
        out.left, out.top, out.right, out.bottom = self.bounds
        return out

    def getChildCount(self):
        return len(self.children)

    def getChild(self, i):
        return self.children[i]

    def isVisibleToUser(self):
        return self.visible

    def recycle(self):
        self.recycled = True


class Rect(object):
    def __init__(self):
        self.left = self.top = self.right = self.bottom = 0

    def width(self):
        return self.right - self.left

    def height(self):
        return self.bottom - self.top

    def isEmpty(self):
        return self.left >= self.right or self.top >= self.bottom

    def contains(self, x, y):
        return self.left <= x <= self.right and self.top <= y <= self.bottom


def intersects(a, b):
    return (a.left < b.right and b.left < a.right
            and a.top < b.bottom and b.top < a.bottom)


# ---------- 镜像 TextGrabber ----------

class Walk(object):
    def __init__(self, out, screen, px, py):
        self.out = out
        self.screen = screen
        self.px = px
        self.py = py
        self.requireVisible = True
        self.visited = 0
        self.truncated = False
        self.pick = None


class Pick(object):
    def __init__(self, seg, area, char_start):
        self.seg = seg
        self.area = area
        self.charStart = char_start


def node_text(node):
    if node is None:
        return None
    try:
        t = node.getText()
        if t is not None:
            s = t.strip()
            if len(s) > 0:
                return s
        d = node.getContentDescription()
        if d is not None:
            s = d.strip()
            if len(s) > 0:
                return s
    except Exception:
        return None
    return None


def visible(node):
    try:
        return node.isVisibleToUser()
    except Exception:
        return True


def char_start_in(node, x, y, sdk=26):
    """镜像 TextGrabber.charStartIn（不含 Bundle/refresh，只算偏移）"""
    if node is None or sdk < 26:
        return 0
    try:
        cs = node.getText()
        if cs is None:
            return 0
        raw = cs if isinstance(cs, str) else str(cs)
        lead = leading_whitespace(raw)
        length = trimmed_length(raw)
        if length <= 0:
            return 0
        rects = node.char_rects
        if not rects:
            return 0
        offset = nearest_char(rects, x, y)
        if offset < 0:
            return 0
        if offset > len(raw):
            offset = len(raw)
        rel = sentence_start(raw, offset) - lead
        if rel < 0:
            rel = 0
        if rel > length:
            rel = length
        return rel
    except Exception:
        return 0


def walk(node, w, depth=0):
    """镜像 TextGrabber.walk：一趟后序遍历，返回这个子树有没有交出文字"""
    if node is None:
        return False
    if depth > MAX_DEPTH:
        w.truncated = True
        return True
    if len(w.out) >= MAX_SEG:
        w.truncated = True
        return True
    if w.visited >= MAX_WALK:
        w.truncated = True
        return True
    w.visited += 1

    child_yielded = False
    try:
        count = node.getChildCount()
    except Exception:
        count = 0
    for i in range(count):
        c = None
        try:
            c = node.getChild(i)
        except Exception:
            c = None
        if c is None:
            continue
        if walk(c, w, depth + 1):
            child_yielded = True
        try:
            c.recycle()
        except Exception:
            pass

    t = node_text(node)
    if t is None:
        return child_yielded
    if child_yielded:
        return True

    r = Rect()
    try:
        node.getBoundsInScreen(r)
    except Exception:
        return False
    if r.isEmpty():
        return False
    if w.requireVisible and not visible(node):
        return False
    if not intersects(r, w.screen):
        return False

    seg = Node(t, r.left, r.top, r.right, r.bottom)
    w.out.append(seg)

    if r.contains(w.px, w.py):
        area = r.width() * r.height()
        if w.pick is None or area < w.pick.area:
            w.pick = Pick(seg, area, char_start_in(node, w.px, w.py))
    return True


class Sink(object):
    def __init__(self):
        self.parts = []
        self.prev = None

    def add_head(self, raw):
        t = clean(raw)
        if t is None:
            return
        self.parts.append(t)
        self.prev = t

    def add(self, raw):
        t = clean(raw)
        if t is None:
            return
        if self.prev is not None and self.prev == t:
            return
        self.parts.append(t)
        self.prev = t

    def text(self):
        return "\n".join(self.parts).strip()


def join(segs, pick):
    idx = -1
    for i, s in enumerate(segs):
        if s is pick.seg:
            idx = i
            break
    if idx < 0:
        return join_below(segs, pick.seg.top)
    sink = Sink()
    head = pick.seg.text
    if 0 < pick.charStart < len(head):
        head = head[pick.charStart:]
    sink.add_head(head)
    for i in range(idx + 1, len(segs)):
        sink.add(segs[i].text)
    return sink.text()


def join_below(segs, y):
    sink = Sink()
    for n in segs:
        if n.top < y:
            continue
        sink.add(n.text)
    return sink.text()


def grab_from_windows(roots, x, y, screen):
    """镜像 TextGrabber.grabFromWindows（含 finally 里的根回收）"""
    if not roots:
        return None
    try:
        fallback = None
        for root in roots:
            segs = []
            w = Walk(segs, screen, x, y)
            walk(root, w, 0)
            if not segs and w.requireVisible:
                w.requireVisible = False
                w.visited = 0
                walk(root, w, 0)
            if not segs:
                continue
            segs = sort_reading_order(segs)
            if fallback is None:
                fallback = segs
            if w.pick is not None:
                return join(segs, w.pick)
        if fallback is None:
            return None
        return join_below(fallback, y)
    finally:
        for r in roots:
            r.recycle()


def all_nodes(node, acc=None):
    if acc is None:
        acc = []
    if node is None:
        return acc
    acc.append(node)
    for c in node.children:
        all_nodes(c, acc)
    return acc


# ---------- 用例 ----------

SCREEN = Rect()
SCREEN.left, SCREEN.top, SCREEN.right, SCREEN.bottom = 0, 0, 1080, 1920


def n(text=None, desc=None, bounds=(0, 0, 0, 0), visible=True, children=None,
      char_rects=None):
    return FakeNode(text, desc, bounds, visible, children, char_rects)


def case_two_columns_continue():
    """
    两列布局，点左一。
    旧排序按 (top,left)：左一 左二 左三 右一 右二 —— 列间乱序。
    新排序分带后：左一 右一 左二 右二 左三。
    """
    root = n(children=[
        n(children=[
            n("左一", bounds=(0, 0, 100, 40)),
            n("左二", bounds=(0, 40, 100, 80)),
            n("左三", bounds=(0, 80, 100, 120)),
        ]),
        n(children=[
            n("右一", bounds=(110, 0, 210, 40)),
            n("右二", bounds=(110, 40, 210, 80)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "左一\n右一\n左二\n右二\n左三", got


def case_parent_child_no_dup():
    """
    容器有 contentDescription，子节点是真正的正文。
    只应收子节点，不能把容器的描述再贴一遍。
    """
    root = n(children=[
        n(desc="卡片", bounds=(0, 0, 200, 200), children=[
            n("第一行", bounds=(0, 0, 200, 40)),
            n("第二行", bounds=(0, 40, 200, 80)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "第一行\n第二行", got


def case_offscreen_skipped():
    """滚出屏幕的节点整块在屏外，不该读出来。"""
    root = n(children=[
        n("屏内的", bounds=(0, 0, 100, 40)),
        n("滚出去了", bounds=(0, 2400, 100, 2440)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "屏内的", got


def case_visibility_lie_fallback():
    """ROM 的 isVisibleToUser 全报 false —— 兜底那趟要把内容捞回来。"""
    root = n(bounds=(0, 0, 1080, 1920), children=[
        n("文本甲", bounds=(0, 0, 200, 40), visible=False),
        n("文本乙", bounds=(0, 40, 200, 80), visible=False),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "文本甲\n文本乙", got


def case_consecutive_dedup():
    """连续且完全相等的两段只保留一段。"""
    root = n(children=[
        n("重复", bounds=(0, 0, 200, 40)),
        n("重复", bounds=(0, 40, 200, 80)),
        n("收尾", bounds=(0, 80, 200, 120)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "重复\n收尾", got


def case_substring_not_swallowed():
    """
    关键回归：后一段是命中段的子串时，绝不能当重复跳掉。
    （旧实现用过 indexOf 判包含，会把"世界"这种正文吞掉）
    """
    root = n(children=[
        n("你好世界", bounds=(0, 0, 200, 40)),
        n("世界", bounds=(0, 40, 200, 80)),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    return got == "你好世界\n世界", got


def case_sentence_cut():
    """点中"光"，从最近的句读（逗号）后开始：欢迎光临。"""
    raw = "你好世界，欢迎光临。"
    rects = []
    for i in range(len(raw)):
        rects.append(RectF(i * 20, 0, (i + 1) * 20, 40))
    root = n(children=[
        n(raw, bounds=(0, 0, len(raw) * 20, 40), char_rects=rects),
    ])
    x = 7 * 20 + 10          # "光"
    got = grab_from_windows([root], x, 20, SCREEN)
    return got == "欢迎光临。", got


def case_leading_ws_sentence_cut():
    """前导空白版本：偏移要减掉前导空白，不能切错。"""
    raw = "  你好世界，欢迎光临。"
    rects = []
    for i in range(len(raw)):
        rects.append(RectF(i * 20, 0, (i + 1) * 20, 40))
    root = n(children=[
        n(raw, bounds=(0, 0, len(raw) * 20, 40), char_rects=rects),
    ])
    x = 7 * 20 + 10          # "欢"（原文下标 7）
    got = grab_from_windows([root], x, 20, SCREEN)
    return got == "欢迎光临。", got


def case_no_hit_reads_below():
    """点在空白处（没命中任何段）：从点击高度往下接。"""
    root = n(children=[
        n("上面", bounds=(0, 0, 200, 40)),
        n("中间", bounds=(0, 100, 200, 140)),
        n("下面", bounds=(0, 200, 200, 240)),
    ])
    got = grab_from_windows([root], 50, 100, SCREEN)
    return got == "中间\n下面", got


def case_budget_does_not_explode():
    """超宽树（>10000 节点）要在上限处停住，不能卡死。"""
    leaf = n("叶", bounds=(0, 0, 10, 10))
    mids = [n(children=[FakeNode("叶", bounds=(0, 0, 10, 10))
                        for _ in range(60)]) for _ in range(200)]
    root = n(children=mids)
    got = grab_from_windows([root], 5, 5, SCREEN)
    return got is not None and len(got) > 0, (len(got) if got else None)


def _chain(depth, leaf_text):
    node = n(leaf_text, bounds=(0, 0, 10, 10))
    for _ in range(depth):
        node = n(children=[node])
    return node


def case_deep_tree_ok():
    """正常深度的深树要能正常收到内容，不能被深度上限误伤。"""
    got = grab_from_windows([_chain(300, "最底")], 5, 5, SCREEN)
    return got == "最底", got


def case_too_deep_guarded():
    """
    超深树（远超 MAX_DEPTH）这一趟会被上限挡住、收不到东西；
    但 grabFrom 会退回 grabAt，那边照样能把最深那块捞出来 —— 不会变成"什么都读不到"。
    """
    tree = _chain(4000, "最底")
    got = grab_from_windows([tree], 5, 5, SCREEN)
    ok_window_pass = (got is None)

    best = Best()
    find_deepest(tree, 5, 5, best)
    ok_fallback = (best.text == "最底")
    return (ok_window_pass and ok_fallback), (got, best.text)


class Best(object):
    def __init__(self):
        self.text = None
        self.area = 1 << 30


def find_deepest(node, x, y, best):
    """镜像 TextGrabber.findDeepest"""
    if node is None:
        return
    r = Rect()
    try:
        node.getBoundsInScreen(r)
    except Exception:
        return
    if r.contains(x, y):
        t = node_text(node)
        if t is not None:
            area = r.width() * r.height()
            if best.text is None or area < best.area:
                best.text = t
                best.area = area
    try:
        count = node.getChildCount()
    except Exception:
        return
    for i in range(count):
        try:
            child = node.getChild(i)
        except Exception:
            continue
        if child is not None:
            find_deepest(child, x, y, best)


def case_all_nodes_recycled():
    """
    AccessibilityNodeInfo 是有上限的系统资源（一般同时只能有几十个），
    漏 recycle 会让后续 getChild() 直接抛异常 —— 表现为"读一会儿就抓不到字了"。
    这里把整棵树收一遍，确认每个节点都被回收了。
    """
    root = n(children=[
        n(children=[
            n("甲", bounds=(0, 0, 100, 40)),
            n("乙", bounds=(0, 40, 100, 80)),
        ]),
        n(children=[
            n("丙", bounds=(0, 80, 100, 120)),
        ]),
    ])
    got = grab_from_windows([root], 50, 20, SCREEN)
    leaked = [x for x in all_nodes(root) if not x.recycled]
    ok = (got is not None) and not leaked
    return ok, (got, len(leaked))


CASES = [
    ("两列布局续读不乱序", case_two_columns_continue),
    ("父子节点不重复收录", case_parent_child_no_dup),
    ("滚出屏的节点不读", case_offscreen_skipped),
    ("可见性撒谎时的兜底", case_visibility_lie_fallback),
    ("连续重复段去重", case_consecutive_dedup),
    ("子串不能被当重复吞掉（关键回归）", case_substring_not_swallowed),
    ("命中段按句读切割", case_sentence_cut),
    ("前导空白下的句读切割（关键回归）", case_leading_ws_sentence_cut),
    ("未命中时从点击高度续读", case_no_hit_reads_below),
    ("超宽树在上限处停住", case_budget_does_not_explode),
    ("正常深树不被误伤", case_deep_tree_ok),
    ("超深树有兜底不会读空", case_too_deep_guarded),
    ("节点全部回收无泄漏", case_all_nodes_recycled),
]


def run_once():
    failed = 0
    for name, fn in CASES:
        ok, detail = fn()
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"), "-", name,
              "" if ok else ("→ %r" % (detail,)))
    return failed


def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
    if total:
        raise SystemExit("TextGrabber 累计 %d 个用例失败" % total)
    print("TextGrabber 两遍全部通过 ✅")


if __name__ == "__main__":
    main()
