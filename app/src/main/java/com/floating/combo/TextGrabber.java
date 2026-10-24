package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 抓屏幕文字。
 *
 *   1) 取窗口 —— getRootInActiveWindow() 只在窗口内容变化时才有数据，经常拿 null，
 *      也拿不到弹窗 / 输入法 / 分屏的另一半。改走 getWindows() 按层级从上往下扫，
 *      哪个窗口里点得到字就用哪个，拿不到再退回 getRootInActiveWindow()。
 *   2) 收节点 —— 一趟后序遍历收完（旧实现每个节点把子树递归两遍，O(n²) + 双倍 IPC），
 *      顺手记下点中的那一段；带可见性过滤（不读滚出屏的）和节点数上限（防病态树卡死主线程）。
 *   3) 排顺序 —— 先按 top 纵向分带成行，行内按 left 排。只按 (top,left) 会把多列布局读乱。
 *   4) 精确到句 —— 拿这段每个字的位置，算点中的是第几个字，回退到最近的句读。
 *   5) 续读 —— 把阅读顺序里排在这段之后的都接上，读完一屏。
 *
 * 只在【未放大】时调用 —— 放大状态下不抓字，避免坐标体系混淆。
 */
public class TextGrabber {

    /** 一趟最多走多少个节点。超长列表之类的病态树会把主线程拖死（同行方案经验值 10000） */
    private static final int MAX_WALK = 10000;
    /** 一屏最多收多少段，防止一次朗读太长 */
    private static final int MAX_SEG = 400;
    /**
     * 递归深度上限。正常界面撑死几十层，这里给到 500 很宽裕；
     * 卡住是为了防病态树（几万层的链表式布局）把栈打爆 ——
     * 触顶时这一趟收不到东西，grabFrom 会退回 grabAt 兜底。
     */
    private static final int MAX_DEPTH = 500;
    /**
     * 一次最多取多少个字符位置。
     * 官方上限 EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH = 20000，
     * 超了请求直接失败 —— 旧实现把整段长度原样传进去，长文章必然拿不到位置。
     */
    private static final int MAX_CHAR_REQ = 20000;

    private final AccessibilityService svc;

    public TextGrabber(AccessibilityService svc) {
        this.svc = svc;
    }

    /** 从点击处开始，往后续读 */
    public String grabFrom(int x, int y) {
        String out = grabFromWindows(x, y);
        if (out != null && out.length() > 0) {
            return out;
        }
        return grabAt(x, y);
    }

    /**
     * 按窗口层级从上往下扫，第一个「点得到字」的窗口就是内容窗口。
     * 全屏扫完都没点到（比如点在图片上），就用最上面那个有内容的窗口，从点击高度往下接。
     */
    private String grabFromWindows(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        try {
            Rect screen = screenRect();
            List<TextLayout.Node> fallback = null;
            for (int r = 0; r < roots.size(); r++) {
                List<TextLayout.Node> segs = new ArrayList<TextLayout.Node>();
                Walk w = new Walk(segs, screen, x, y);
                walk(roots.get(r), w, 0);
                if (w.truncated) {
                    Log.w("grab", "节点太多，收够上限就停了 walk=" + w.visited
                            + " seg=" + segs.size());
                }
                if (segs.isEmpty() && w.requireVisible) {
                    // 有些 ROM 的 isVisibleToUser 会乱报 false，收不到就不过滤再走一遍
                    w.requireVisible = false;
                    w.visited = 0;
                    walk(roots.get(r), w, 0);
                }
                if (segs.isEmpty()) {
                    continue;
                }
                TextLayout.sortReadingOrder(segs);
                if (fallback == null) {
                    fallback = segs;
                }
                if (w.pick != null) {
                    return join(segs, w.pick);
                }
            }
            if (fallback == null) {
                return null;
            }
            return joinBelow(fallback, y);
        } finally {
            for (int r = 0; r < roots.size(); r++) {
                try {
                    roots.get(r).recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 兜底：直接读命中的那一块，不切句 */
    public String grabAt(int x, int y) {
        List<AccessibilityNodeInfo> roots = roots();
        if (roots.isEmpty()) {
            return null;
        }
        Best best = new Best();
        try {
            for (int r = 0; r < roots.size(); r++) {
                findDeepest(roots.get(r), x, y, best);
                if (best.text != null) {
                    break;      // 点到的窗口在最上面，命中就收工
                }
            }
        } finally {
            for (int r = 0; r < roots.size(); r++) {
                try {
                    roots.get(r).recycle();
                } catch (Exception ignored) {
                }
            }
        }
        return best.text;
    }

    // ---------- 窗口 ----------

    /**
     * 取所有可遍历的窗口根节点，按层级从高到低（上层优先）。
     *
     * getWindows() 需要 accessibilityFlags 里有 flagRetrieveInteractiveWindows
     * （配置里已经加了）。拿不到再退回 getRootInActiveWindow()。
     */
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();

        List<AccessibilityWindowInfo> wins = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                wins = svc.getWindows();
            } catch (Exception e) {
                wins = null;
            }
        }

        if (wins != null && wins.size() > 0) {
            // getWindows() 的返回顺序不保证是层级顺序，自己排
            Collections.sort(wins, new Comparator<AccessibilityWindowInfo>() {
                @Override
                public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                    return layerOf(b) - layerOf(a);
                }
            });
            for (int i = 0; i < wins.size(); i++) {
                AccessibilityWindowInfo w = wins.get(i);
                if (w == null || isChrome(w)) {
                    continue;   // 状态栏 / 导航栏 / 输入法 / 分屏条 / 我们自己的浮层
                }
                AccessibilityNodeInfo r = null;
                try {
                    r = w.getRoot();
                } catch (Exception e) {
                    r = null;
                }
                if (r != null) {
                    out.add(r);
                }
            }
            // 根的生命周期和窗口信息无关，但窗口信息本身也要还回去
            for (int i = 0; i < wins.size(); i++) {
                try {
                    if (wins.get(i) != null) {
                        wins.get(i).recycle();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (out.isEmpty()) {
            AccessibilityNodeInfo r = null;
            try {
                r = svc.getRootInActiveWindow();
            } catch (Exception e) {
                r = null;
            }
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static int layerOf(AccessibilityWindowInfo w) {
        try {
            return w.getLayer();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 系统装饰窗口 / 我们自己的浮层：一律不当内容窗口。
     *
     * 注意：Android 只有 TYPE_SYSTEM 一个类型同时代表状态栏和导航栏，
     * 没有 TYPE_STATUS_BAR / TYPE_NAVIGATION_BAR（这两个名字不存在，写了编译不过）。
     * TYPE_MAGNIFICATION_OVERLAY 是 API 33 才有的，compileSdk 30 引不到，
     * 它只是放大后的位图、没有文字节点，不排除也不影响抓字。
     */
    private static boolean isChrome(AccessibilityWindowInfo w) {
        try {
            int t = w.getType();
            return t == AccessibilityWindowInfo.TYPE_SYSTEM
                    || t == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    || t == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                    || t == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY;
        } catch (Exception e) {
            return false;
        }
    }

    private Rect screenRect() {
        Rect r = new Rect();
        try {
            android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            r.set(0, 0, dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            // 量不到就当无限大，等于不做屏幕过滤，别把内容筛没了
            r.set(-1000000, -1000000, 1000000, 1000000);
        }
        return r;
    }

    // ---------- 收集 ----------

    private static final class Walk {
        final List<TextLayout.Node> out;
        final Rect screen;
        final int px;
        final int py;
        boolean requireVisible = true;
        int visited = 0;
        boolean truncated = false;
        Pick pick = null;

        Walk(List<TextLayout.Node> out, Rect screen, int px, int py) {
            this.out = out;
            this.screen = screen;
            this.px = px;
            this.py = py;
        }
    }

    private static final class Pick {
        final TextLayout.Node seg;
        final int area;
        final int charStart;

        Pick(TextLayout.Node seg, int area, int charStart) {
            this.seg = seg;
            this.area = area;
            this.charStart = charStart;
        }
    }

    /**
     * 一趟后序遍历：返回这个子树有没有交出文字。
     *
     * 只在「有文字且子节点没交出文字」时才收录当前节点 ——
     * 这样拿到的是最靠下的那批文字节点，父节点不会和子节点重复贴一遍。
     *
     * 预算耗尽时返回 true（而不是 false）：让父节点知道"下面已经有内容了"，
     * 别把父节点整段再贴上来造成重复。
     */
    private boolean walk(AccessibilityNodeInfo node, Walk w, int depth) {
        if (node == null) {
            return false;
        }
        if (depth > MAX_DEPTH) {
            w.truncated = true;
            return true;
        }
        if (w.out.size() >= MAX_SEG) {
            w.truncated = true;
            return true;
        }
        if (w.visited >= MAX_WALK) {
            w.truncated = true;
            return true;
        }
        w.visited++;

        boolean childYielded = false;
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            if (c == null) {
                continue;
            }
            if (walk(c, w, depth + 1)) {
                childYielded = true;
            }
            try {
                c.recycle();
            } catch (Exception ignored) {
            }
        }

        String t = nodeText(node);
        if (t == null) {
            return childYielded;
        }
        if (childYielded) {
            return true;    // 文字在子节点里，当前节点这段就不重复了
        }

        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return false;
        }
        if (r.isEmpty()) {
            return false;   // 量不到位置，没法排序，宁可不收
        }
        if (w.requireVisible && !visible(node)) {
            return false;
        }
        if (!Rect.intersects(r, w.screen)) {
            return false;   // 整块都在屏幕外（滚出去的），不读
        }

        TextLayout.Node seg = new TextLayout.Node(t, r.left, r.top, r.right, r.bottom);
        w.out.add(seg);

        if (r.contains(w.px, w.py)) {
            int area = r.width() * r.height();
            if (w.pick == null || area < w.pick.area) {
                w.pick = new Pick(seg, area, charStartIn(node, w.px, w.py));
            }
        }
        return true;
    }

    private static boolean visible(AccessibilityNodeInfo node) {
        try {
            return node.isVisibleToUser();
        } catch (Exception e) {
            return true;    // 拿不到就当可见，别把内容筛没了
        }
    }

    // ---------- 拼装 ----------

    /** 点中了某一段：从这段的句读处开始，接上阅读顺序里后面的所有段 */
    private String join(List<TextLayout.Node> segs, Pick pick) {
        int idx = indexOf(segs, pick.seg);
        if (idx < 0) {
            return joinBelow(segs, pick.seg.top);
        }
        Sink s = new Sink();
        String head = pick.seg.text;
        if (pick.charStart > 0 && pick.charStart < head.length()) {
            head = head.substring(pick.charStart);
        }
        s.addHead(head);
        for (int i = idx + 1; i < segs.size(); i++) {
            s.add(segs.get(i).text);
        }
        return s.text();
    }

    /** 没点中任何一段：把点击高度以下的都接上 */
    private String joinBelow(List<TextLayout.Node> segs, int y) {
        Sink s = new Sink();
        for (int i = 0; i < segs.size(); i++) {
            TextLayout.Node n = segs.get(i);
            if (n.top < y) {
                continue;
            }
            s.add(n.text);
        }
        return s.text();
    }

    private static int indexOf(List<TextLayout.Node> segs, TextLayout.Node target) {
        for (int i = 0; i < segs.size(); i++) {
            if (segs.get(i) == target) {
                return i;
            }
        }
        return -1;
    }

    /** 拼字符串的容器，顺手干掉连续重复段 */
    private static final class Sink {
        final StringBuilder sb = new StringBuilder();
        String prev = null;

        void addHead(String raw) {
            String t = TextLayout.clean(raw);
            if (t == null) {
                return;
            }
            sb.append(t);
            prev = t;
        }

        void add(String raw) {
            String t = TextLayout.clean(raw);
            if (t == null) {
                return;
            }
            if (prev != null && TextCompare.sameLine(prev, t)) {
                return;     // 只去「连续且完全相等」，绝不比包含 —— 否则会吞掉正文
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(t);
            prev = t;
        }

        String text() {
            return sb.toString().trim();
        }
    }

    // ---------- 字符级定位 ----------

    /**
     * 应该从第几个字开始读。返回的下标是相对【trim 之后】的串。
     *
     * 注意：nodeText() 收录的是 trim 过的文本，而字符位置是按原文排的，
     * 有前导空白时两者下标差了一个偏移量 —— 不减掉就会切错位置（旧实现的 bug）。
     */
    private int charStartIn(AccessibilityNodeInfo node, int x, int y) {
        if (node == null || Build.VERSION.SDK_INT < 26) {
            return 0;
        }
        try {
            try {
                node.refresh();
            } catch (Exception ignored) {
            }
            CharSequence cs = null;
            try {
                cs = node.getText();
            } catch (Exception ignored) {
            }
            if (cs == null) {
                return 0;
            }
            String raw = cs.toString();
            int lead = TextLayout.leadingWhitespace(raw);
            int len = TextLayout.trimmedLength(raw);
            if (len <= 0) {
                return 0;
            }

            String key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
            // 整段（夹到 20000）→ 2000 字 → 500 字，逐级退让
            int[] tries = new int[]{
                    Math.min(raw.length(), MAX_CHAR_REQ),
                    Math.min(raw.length(), 2000),
                    Math.min(raw.length(), 500),
            };
            for (int t = 0; t < tries.length; t++) {
                int count = tries[t];
                if (count <= 0) {
                    continue;
                }
                RectF[] rects = requestRects(node, key, 0, count);
                if (rects == null) {
                    continue;
                }
                int offset = nearestChar(rects, x, y);
                if (offset < 0) {
                    continue;
                }
                if (offset > raw.length()) {
                    offset = raw.length();
                }
                int rel = TextLayout.sentenceStart(raw, offset) - lead;
                if (rel < 0) {
                    rel = 0;
                }
                if (rel > len) {
                    rel = len;
                }
                return rel;
            }
            return 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private RectF[] requestRects(AccessibilityNodeInfo node, String key, int start, int count) {
        try {
            Bundle args = new Bundle();
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start);
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, count);
            if (!node.refreshWithExtraData(key, args)) {
                return null;
            }
            Bundle extras = node.getExtras();
            if (extras == null) {
                return null;
            }
            Object raw = extras.get(key);
            if (raw instanceof RectF[] && ((RectF[]) raw).length > 0) {
                return (RectF[]) raw;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 起点固定是 0，所以下标就是第几个字 */
    private int nearestChar(RectF[] rects, float x, float y) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            RectF r = rects[i];
            if (r == null || r.isEmpty()) {
                continue;
            }
            float dx = 0f;
            float dy = 0f;
            if (x < r.left) {
                dx = r.left - x;
            } else if (x > r.right) {
                dx = x - r.right;
            }
            if (y < r.top) {
                dy = r.top - y;
            } else if (y > r.bottom) {
                dy = y - r.bottom;
            }
            double d = (double) dx * dx + (double) dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ---------- 兜底用的最深命中 ----------

    private void findDeepest(AccessibilityNodeInfo node, int x, int y, Best best) {
        if (node == null) {
            return;
        }
        Rect r = new Rect();
        try {
            node.getBoundsInScreen(r);
        } catch (Exception e) {
            return;
        }

        if (r.contains(x, y)) {
            String t = nodeText(node);
            if (t != null) {
                int area = r.width() * r.height();
                if (best.text == null || area < best.area) {
                    best.text = t;
                    best.area = area;
                }
            }
        }

        int count;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                continue;
            }
            if (child != null) {
                findDeepest(child, x, y, best);
                try {
                    child.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class Best {
        String text = null;
        int area = Integer.MAX_VALUE;
    }

    // ---------- 取文本 ----------

    /**
     * 节点的文字：正文优先，没有就退回内容描述（图标按钮靠它才有东西读）。
     * 返回的一律是 trim 过的。
     */
    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        try {
            CharSequence t = node.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
            CharSequence d = node.getContentDescription();
            if (d != null) {
                String s = d.toString().trim();
                if (s.length() > 0) {
                    return s;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
