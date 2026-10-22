#!/usr/bin/env python3
# 镜像 com.floating.combo.TextLayout 的逻辑，以及 TextGrabber.charStartIn 的偏移换算。
#
# 项目环境里没有 JDK / Android SDK，JUnit 跑不起来。TextLayout 是特地写成
# 无 Android 依赖的纯逻辑，就是为了能在这里 1:1 镜像验证 —— 两边结果必须一致。
#
# 重点覆盖两个修复：
#   1) 阅读顺序：多列布局不能只按 (top,left) 排，要先纵向分带成行。
#   2) charStart 偏移：字符位置是按【原文】排的，而收录的文本是 trim 过的，
#      有前导空白时两者差一个偏移量，不减掉就会切错位置。

MAX_CHAR_REQ = 20000


# ---------- Java 语义 ----------

def _is_ws(ch):
    """Java Character.isWhitespace —— 注意不含不间断空格（U+00A0 等）。"""
    if ch in ('\u00a0', '\u2007', '\u202f'):
        return False
    if ch in ('\u001c', '\u001d', '\u001e', '\u001f'):
        return True
    return ch.isspace()


def leading_whitespace(s):
    if s is None:
        return 0
    i = 0
    while i < len(s) and _is_ws(s[i]):
        i += 1
    return i


def trimmed_length(s):
    if s is None:
        return 0
    a, b = 0, len(s)
    while a < b and _is_ws(s[a]):
        a += 1
    while b > a and _is_ws(s[b - 1]):
        b -= 1
    return b - a


def clean(s):
    if s is None:
        return None
    t = s.strip()
    return None if len(t) == 0 else t


def scan_back(text, offset, puncts):
    if text is None or puncts is None:
        return 0
    if offset > len(text):
        offset = len(text)
    for i in range(offset - 1, -1, -1):
        if puncts.find(text[i]) < 0:
            continue
        s = i + 1
        while s < len(text) and _is_ws(text[s]):
            s += 1
        if s >= len(text):
            return 0
        return s
    return 0


def sentence_start(text, offset):
    if text is None:
        return 0
    if offset <= 0:
        return 0
    if offset > len(text):
        offset = len(text)
    s = scan_back(text, offset, "。！？；\n.!?")
    if s > 0:
        return s
    s = scan_back(text, offset, "，、：,:")
    if s > 0:
        return s
    return offset


# ---------- 阅读顺序 ----------

class Node(object):
    __slots__ = ('text', 'left', 'top', 'right', 'bottom')

    def __init__(self, text, left, top, right, bottom):
        self.text = text
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def __repr__(self):
        return "Node(%r,%d,%d,%d,%d)" % (
            self.text, self.left, self.top, self.right, self.bottom)


def same_row(a, b):
    top = max(a.top, b.top)
    bottom = min(a.bottom, b.bottom)
    overlap = bottom - top
    if overlap <= 0:
        return False
    min_h = min(a.bottom - a.top, b.bottom - b.top)
    if min_h <= 0:
        return False
    return overlap * 2 >= min_h


def _band_hits(band, n):
    top = max(band['top'], n.top)
    bottom = min(band['bottom'], n.bottom)
    overlap = bottom - top
    if overlap <= 0:
        return False
    min_h = min(band['bottom'] - band['top'], n.bottom - n.top)
    if min_h <= 0:
        return False
    return overlap * 2 >= min_h


def sort_reading_order(lst):
    if lst is None or len(lst) < 2:
        return lst
    lst.sort(key=lambda n: (n.top, n.left))

    bands = []
    for n in lst:
        if bands and _band_hits(bands[-1], n):
            b = bands[-1]
            b['items'].append(n)
            if n.top < b['top']:
                b['top'] = n.top
            if n.bottom > b['bottom']:
                b['bottom'] = n.bottom
        else:
            bands.append({'items': [n], 'top': n.top, 'bottom': n.bottom})

    out = []
    for b in bands:
        b['items'].sort(key=lambda n: n.left)
        out.extend(b['items'])
    return out


# ---------- 字符级定位的偏移换算 ----------

class RectF(object):
    __slots__ = ('left', 'top', 'right', 'bottom')

    def __init__(self, left, top, right, bottom):
        self.left, self.top, self.right, self.bottom = left, top, right, bottom

    def is_empty(self):
        return self.left >= self.right or self.top >= self.bottom


def nearest_char(rects, x, y):
    best = -1
    best_d = float('inf')
    for i, r in enumerate(rects):
        if r is None or r.is_empty():
            continue
        dx = 0.0
        dy = 0.0
        if x < r.left:
            dx = r.left - x
        elif x > r.right:
            dx = x - r.right
        if y < r.top:
            dy = r.top - y
        elif y > r.bottom:
            dy = y - r.bottom
        d = dx * dx + dy * dy
        if d < best_d:
            best_d = d
            best = i
    return best


def char_start_in(raw, rects_of, x, y, sdk=26):
    """
    镜像 TextGrabber.charStartIn：
    句读下标在【原文】上算，再减掉前导空白长度，换算到 trim 之后的串上。
    rects_of(count) -> RectF 列表 或 None
    """
    if raw is None or sdk < 26:
        return 0
    lead = leading_whitespace(raw)
    length = trimmed_length(raw)
    if length <= 0:
        return 0

    for count in (min(len(raw), MAX_CHAR_REQ), min(len(raw), 2000), min(len(raw), 500)):
        if count <= 0:
            continue
        rects = rects_of(count)
        if not rects:
            continue
        offset = nearest_char(rects, x, y)
        if offset < 0:
            continue
        if offset > len(raw):
            offset = len(raw)
        rel = sentence_start(raw, offset) - lead
        if rel < 0:
            rel = 0
        if rel > length:
            rel = length
        return rel
    return 0


def make_rects(text, x0=0, y0=0, cw=20, ch=40):
    """把一串字按等宽排成一行，返回每个字的矩形。"""
    out = []
    for i in range(len(text)):
        out.append(RectF(x0 + i * cw, y0, x0 + (i + 1) * cw, y0 + ch))
    return out


def center_of(i, cw=20, ch=40, x0=0, y0=0):
    return x0 + i * cw + cw / 2.0, y0 + ch / 2.0


# ---------- 用例 ----------

def _order(lst):
    return [n.text for n in sort_reading_order(list(lst))]


def case_single_column():
    """普通单列：从上到下。"""
    ns = [
        Node("第三行", 0, 80, 100, 120),
        Node("第一行", 0, 0, 100, 40),
        Node("第二行", 0, 40, 100, 80),
    ]
    got = _order(ns)
    return got == ["第一行", "第二行", "第三行"], got


def case_two_columns():
    """
    两列布局。这正是旧排序会读乱的场景：
    右列第二行的 top 比左列第三行小，只按 top 排会插进左列中间。
    """
    ns = [
        Node("右二", 110, 40, 210, 80),
        Node("左三", 0, 80, 100, 120),
        Node("右一", 110, 0, 210, 40),
        Node("左一", 0, 0, 100, 40),
        Node("左二", 0, 40, 100, 80),
    ]
    got = _order(ns)
    return got == ["左一", "右一", "左二", "右二", "左三"], got


def case_same_row_misaligned_top():
    """同一行但 top 差几像素：仍要算同一行，行内按 left 排。"""
    ns = [
        Node("B", 200, 3, 300, 43),
        Node("A", 0, 0, 100, 40),
    ]
    got = _order(ns)
    return got == ["A", "B"], got


def case_different_rows_small_gap():
    """两行只差一点：垂直重叠不到一半，必须算两行。"""
    ns = [
        Node("下一行", 0, 30, 100, 70),
        Node("上一行", 0, 0, 100, 40),
    ]
    got = _order(ns)
    return got == ["上一行", "下一行"], got


def case_empty_and_single():
    ns = [Node("孤零零", 0, 0, 10, 10)]
    got = _order(ns)
    ok1 = got == ["孤零零"]
    ok2 = sort_reading_order([]) == []
    return (ok1 and ok2), got


def case_same_row_api():
    a = Node("a", 0, 0, 100, 40)
    b = Node("b", 110, 5, 210, 45)
    c = Node("c", 0, 100, 100, 140)
    return (same_row(a, b) and not same_row(a, c)), (same_row(a, b), same_row(a, c))


def case_sentence_plain():
    """没有前导空白：点中"光"(下标7)，回退到逗号后的"欢"(下标5)。"""
    raw = "你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    ok = (rel == 5) and (raw.strip()[rel:] == "欢迎光临。")
    return ok, (rel, raw.strip()[rel:] if rel < len(raw.strip()) else None)


def case_sentence_leading_ws():
    """
    关键回归：有前导空白。
    原文 "  你好世界，欢迎光临。"（两个空格），收录的是 trim 过的 "你好世界，欢迎光临。"
    点中"欢"：原文下标 7，减掉前导空白 2 → trim 串上的 5。
    旧实现不减前导空白，charStart=7 → 切到 "光临。"，正文被吞掉一半。
    """
    raw = "  你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)          # 欢
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    ok = (rel == 5) and (raw.strip()[rel:] == "欢迎光临。")
    return ok, (rel, raw.strip()[rel:] if rel < len(raw.strip()) else None)


def case_sentence_no_punct():
    """一个标点都没有：从点中的字开始。"""
    raw = "没有标点的一段话"
    rects = make_rects(raw)
    x, y = center_of(5)
    rel = char_start_in(raw, lambda c: rects[:c], x, y)
    return rel == 5, rel


def case_sentence_fallback_to_500():
    """
    整段(3000)和 2000 字两档都拿不到位置，退到 500 字那一档也要能算出结果。
    文本："甲乙丙丁。" 重复 500 次 = 3000 字，句号落在下标 %5==4。
    """
    raw = "甲乙丙丁。" * 500
    full = make_rects(raw)

    def rects_of(count):
        if count > 500:
            return None
        return full[:count]

    x, y = center_of(100)        # 正好是某个"甲"
    rel = char_start_in(raw, rects_of, x, y)
    return rel == 100, rel


def case_char_req_capped():
    """超长文本第一次请求必须夹到 20000，不能把整段长度原样传进去。"""
    seen = []
    raw = "字" * 50000

    def rects_of(count):
        seen.append(count)
        return None   # 全部失败，最终返回 0

    rel = char_start_in(raw, rects_of, 0, 0)
    ok = rel == 0 and seen[0] == MAX_CHAR_REQ and all(c <= MAX_CHAR_REQ for c in seen)
    return ok, seen[:1]


def case_old_buggy_offset():
    """
    证明修复前的算法确实是错的（用作对照，确保这个回归测试有区分力）。
    旧：直接用原文下标切 trim 串。
    """
    raw = "  你好世界，欢迎光临。"
    rects = make_rects(raw)
    x, y = center_of(7)
    offset = nearest_char(rects, x, y)
    old_rel = sentence_start(raw, offset)          # 没减前导空白
    trimmed = raw.strip()
    old_head = trimmed[old_rel:] if old_rel < len(trimmed) else ""
    return old_head != "欢迎光临。", old_head


CASES = [
    ("单列从上到下", case_single_column),
    ("两列布局不乱序", case_two_columns),
    ("同一行 top 轻微错位", case_same_row_misaligned_top),
    ("相邻行不被合并", case_different_rows_small_gap),
    ("空列表与单元素", case_empty_and_single),
    ("sameRow 判定", case_same_row_api),
    ("无前导空白断句", case_sentence_plain),
    ("有前导空白断句（关键回归）", case_sentence_leading_ws),
    ("无标点时从点中的字开始", case_sentence_no_punct),
    ("请求长度逐级退让", case_sentence_fallback_to_500),
    ("超长文本请求夹到 20000", case_char_req_capped),
    ("对照：旧算法确实切错", case_old_buggy_offset),
]


def run_once():
    failed = 0
    for name, fn in CASES:
        ok, detail = fn()
        failed += 0 if ok else 1
        print(("PASS" if ok else "FAIL"), "-", name, "" if ok else ("→ %r" % (detail,)))
    return failed


def main():
    total = 0
    for i in range(1, 3):
        print("===== 第 %d 遍 =====" % i)
        total += run_once()
    if total:
        raise SystemExit("TextLayout 累计 %d 个用例失败" % total)
    print("TextLayout 两遍全部通过 ✅")


if __name__ == "__main__":
    main()
