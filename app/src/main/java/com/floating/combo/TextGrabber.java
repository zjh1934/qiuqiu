package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 抓屏幕文字。三层叠加：
 *
 *   第 1 层 定位到段 —— 找包含点击点、面积最小、能读到文字的节点。
 *             不挑叶子、不挑父级，父节点矩形算不准也继续往下找，
 *             所以能稳稳落在你点的那一段上。
 *   第 2 层 段内精确到句 —— 拿到这段每个字的位置，算你点的是第几个字，
 *             往前找到最近的句读，从那句开始。拿不到就整段开始。
 *   第 3 层 续读 —— 把这段底边以下的内容接上，读完一屏。
 *
 * 只在【未放大】时调用 —— 放大状态下不抓字，避免坐标体系混淆。
 */
public class TextGrabber {

    private final AccessibilityService svc;

    public TextGrabber(AccessibilityService svc) {
        this.svc = svc;
    }

    /** 从点击处开始，往后续读 */
    public String grabFrom(int x, int y) {
        AccessibilityNodeInfo root;
        try {
            root = svc.getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (root == null) {
            return null;
        }

        Hit hit = new Hit();
        List<TextNode> nodes = new ArrayList<TextNode>();
        try {
            findDeepestHit(root, x, y, hit);
            hit.charStart = charStartIn(hit.node, x, y);
            collectTextNodes(root, nodes);
        } catch (Exception ignored) {
        } finally {
            if (hit.node != null) {
                try {
                    hit.node.recycle();
                } catch (Exception ignored) {
                }
            }
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
        }

        Collections.sort(nodes, new Comparator<TextNode>() {
            @Override
            public int compare(TextNode a, TextNode b) {
                if (a.top != b.top) {
                    return a.top - b.top;
                }
                return a.left - b.left;
            }
        });

        StringBuilder sb = new StringBuilder();

        // 先放命中的那一段
        if (hit.text != null) {
            String head = hit.text;
            if (hit.charStart > 0 && hit.charStart < head.length()) {
                head = head.substring(hit.charStart);
            }
            head = head.trim();
            if (head.length() > 0) {
                sb.append(head);
            }
        }

        // 再把这段底边以下的内容接上
        int floor = hit.hasHit ? hit.bottom : y;
        for (int i = 0; i < nodes.size(); i++) {
            TextNode n = nodes.get(i);
            if (n.top < floor) {
                continue;
            }
            String t = n.text;
            if (t == null) {
                continue;
            }
            t = t.trim();
            if (t.length() == 0) {
                continue;
            }
            if (hit.text != null && sameText(t, hit.text)) {
                continue;   // 命中段可能被重复收进来了
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(t);
        }

        String out = sb.toString().trim();
        if (out.length() > 0) {
            return out;
        }
        return grabAt(x, y);
    }

    /** 兜底：直接读命中的那一块，不切句 */
    public String grabAt(int x, int y) {
        AccessibilityNodeInfo root;
        try {
            root = svc.getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        Best best = new Best();
        try {
            findDeepest(root, x, y, best);
        } catch (Exception ignored) {
        } finally {
            try {
                root.recycle();
            } catch (Exception ignored) {
            }
        }
        return best.text;
    }

    private boolean sameText(String a, String b) {
        // 只比相等（委托给无依赖的纯函数，便于测试）；
        // 不再用 indexOf 判包含，否则子串段会被误当重复跳过、吞掉正文。
        return TextCompare.sameLine(a, b);
    }

    /** 字符级定位：应该从第几个字开始读。拿不到返回 0 */
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
            String text = cs.toString();
            int len = text.length();
            if (len == 0) {
                return 0;
            }

            String key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY;
            // 整段 → 2000字 → 500字，逐级退让
            int[] tries = new int[]{len, Math.min(len, 2000), Math.min(len, 500)};
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
                if (offset > text.length()) {
                    offset = text.length();
                }
                return sentenceStart(text, offset);
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
            if (raw instanceof RectF[]) {
                return (RectF[]) raw;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

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

    /** 往回找最近的断点，从它后面开始读。一个标点都没有就从点中的字开始 */
    private int sentenceStart(String text, int offset) {
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

    private int scanBack(String text, int offset, String puncts) {
        for (int i = offset - 1; i >= 0; i--) {
            if (puncts.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            int s = i + 1;
            while (s < text.length() && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            if (s >= text.length()) {
                return 0;
            }
            return s;
        }
        return 0;
    }

    private static class Hit {
        AccessibilityNodeInfo node = null;
        String text = null;
        int area = Integer.MAX_VALUE;
        int charStart = 0;
        int bottom = 0;
        boolean hasHit = false;
    }

    /** 定位到段：不要求叶子，父节点（整段）也能命中 */
    private void findDeepestHit(AccessibilityNodeInfo node, int x, int y, Hit hit) {
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
                if (!hit.hasHit || area < hit.area) {
                    if (hit.node != null) {
                        try {
                            hit.node.recycle();
                        } catch (Exception ignored) {
                        }
                    }
                    hit.area = area;
                    hit.text = t;
                    hit.bottom = r.bottom;
                    hit.hasHit = true;
                    try {
                        hit.node = AccessibilityNodeInfo.obtain(node);
                    } catch (Exception e) {
                        hit.node = null;
                    }
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
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            if (c != null) {
                findDeepestHit(c, x, y, hit);
                try {
                    c.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void collectTextNodes(AccessibilityNodeInfo node, List<TextNode> out) {
        if (node == null) {
            return;
        }
        String t = nodeText(node);
        boolean childHasText = false;
        int count;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
                if (c != null && hasTextInSubtree(c)) {
                    childHasText = true;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        c.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (t != null && !childHasText) {
            Rect r = new Rect();
            try {
                node.getBoundsInScreen(r);
            } catch (Exception e) {
                return;
            }
            out.add(new TextNode(t, r));
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
            } catch (Exception e) {
                c = null;
            }
            if (c != null) {
                collectTextNodes(c, out);
                try {
                    c.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean hasTextInSubtree(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        if (nodeText(node) != null) {
            return true;
        }
        int count;
        try {
            count = node.getChildCount();
        } catch (Exception e) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = null;
            try {
                c = node.getChild(i);
                if (c != null && hasTextInSubtree(c)) {
                    return true;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        c.recycle();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static class TextNode {
        String text;
        int top;
        int left;

        TextNode(String t, Rect r) {
            text = t;
            top = r.top;
            left = r.left;
        }
    }

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

    private static class Best {
        String text = null;
        int area = Integer.MAX_VALUE;
    }
}
