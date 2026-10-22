package com.floating.combo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 纯排版逻辑：把"一段段带坐标的文字"排成人眼阅读的顺序。
 *
 * 不依赖 Android，方便单元测试 —— verify_textlayout.py 里镜像了同一套逻辑，
 * 两边跑出一样的结果才算过（项目里没有 JDK，跑不了 JUnit）。
 *
 * ── 为什么不能只按 (top, left) 排 ──
 *   多列布局（左列三行 + 右列三行）里，右列第二行的 top 可能比左列第二行小，
 *   按 top 排就会把右列内容插到左列中间，读出来是乱的。
 *   正确做法：先把「垂直重叠过半」的段归成一行，行内再按 left 排。
 */
public final class TextLayout {

    /** 一段文字 + 它在屏幕上的矩形 */
    public static final class Node {
        public final String text;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Node(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /** 一行（一个纵向分带）里装的所有段 */
    private static final class Band {
        final List<Node> items = new ArrayList<Node>();
        int top;
        int bottom;

        Band(Node n) {
            items.add(n);
            top = n.top;
            bottom = n.bottom;
        }

        void add(Node n) {
            items.add(n);
            if (n.top < top) {
                top = n.top;
            }
            if (n.bottom > bottom) {
                bottom = n.bottom;
            }
        }
    }

    private static final Comparator<Node> TOP_LEFT = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            if (a.top != b.top) {
                return a.top < b.top ? -1 : 1;
            }
            if (a.left != b.left) {
                return a.left < b.left ? -1 : 1;
            }
            return 0;
        }
    };

    private static final Comparator<Node> LEFT = new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            if (a.left != b.left) {
                return a.left < b.left ? -1 : 1;
            }
            return 0;
        }
    };

    private TextLayout() {
    }

    /** 垂直重叠达到较矮那一段的一半以上，就算同一行 */
    public static boolean sameRow(Node a, Node b) {
        int top = Math.max(a.top, b.top);
        int bottom = Math.min(a.bottom, b.bottom);
        int overlap = bottom - top;
        if (overlap <= 0) {
            return false;
        }
        int ha = a.bottom - a.top;
        int hb = b.bottom - b.top;
        int minH = Math.min(ha, hb);
        if (minH <= 0) {
            return false;
        }
        return overlap * 2 >= minH;
    }

    /**
     * 就地排成阅读顺序：先按 top 排，再纵向分带，行内按 left 排。
     *
     * 分带时只看最后一行 —— 已经按 top 排好序了，
     * 够不着最后一行就一定够不着更早的行（更早的行 top 更小、bottom 也更小）。
     */
    public static void sortReadingOrder(List<Node> list) {
        if (list == null || list.size() < 2) {
            return;
        }
        Collections.sort(list, TOP_LEFT);

        List<Band> bands = new ArrayList<Band>();
        for (int i = 0; i < list.size(); i++) {
            Node n = list.get(i);
            Band last = bands.isEmpty() ? null : bands.get(bands.size() - 1);
            if (last != null && bandHits(last, n)) {
                last.add(n);
            } else {
                bands.add(new Band(n));
            }
        }

        list.clear();
        for (int i = 0; i < bands.size(); i++) {
            Band b = bands.get(i);
            Collections.sort(b.items, LEFT);
            list.addAll(b.items);
        }
    }

    private static boolean bandHits(Band b, Node n) {
        int top = Math.max(b.top, n.top);
        int bottom = Math.min(b.bottom, n.bottom);
        int overlap = bottom - top;
        if (overlap <= 0) {
            return false;
        }
        int hb = b.bottom - b.top;
        int hn = n.bottom - n.top;
        int minH = Math.min(hb, hn);
        if (minH <= 0) {
            return false;
        }
        return overlap * 2 >= minH;
    }

    // ---------- 纯字符串：句子切分 ----------

    /** 前导空白的长度。nodeText() 会 trim，字符偏移得减掉它才对得上 */
    public static int leadingWhitespace(String s) {
        if (s == null) {
            return 0;
        }
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** trim 之后的长度 */
    public static int trimmedLength(String s) {
        if (s == null) {
            return 0;
        }
        int a = 0;
        int b = s.length();
        while (a < b && Character.isWhitespace(s.charAt(a))) {
            a++;
        }
        while (b > a && Character.isWhitespace(s.charAt(b - 1))) {
            b--;
        }
        return b - a;
    }

    /** 去掉两端空白；空或 null 返回 null */
    public static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() == 0 ? null : t;
    }

    /** 往回找最近的断点，从它后面开始读。一个标点都没有就从点中的字开始 */
    public static int sentenceStart(String text, int offset) {
        if (text == null) {
            return 0;
        }
        if (offset <= 0) {
            return 0;
        }
        if (offset > text.length()) {
            offset = text.length();
        }
        int s = scanBack(text, offset, "。！？；\n.!?");
        if (s > 0) {
            return s;
        }
        s = scanBack(text, offset, "，、：,:");
        if (s > 0) {
            return s;
        }
        return offset;
    }

    /** 从 offset-1 往前扫，找到 puncts 里任意一个标点，返回它后面第一个非空字符的下标 */
    public static int scanBack(String text, int offset, String puncts) {
        if (text == null || puncts == null) {
            return 0;
        }
        if (offset > text.length()) {
            offset = text.length();
        }
        for (int i = offset - 1; i >= 0; i--) {
            if (puncts.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int s = i + 1;
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            if (s >= text.length()) {
                return 0;   // 标点后面没字了，说明点在了最后一句，从头读
            }
            return s;
        }
        return 0;
    }
}
