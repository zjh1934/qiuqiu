package com.floating.combo;

/**
 * 纯字符串比较工具，无 Android 依赖，方便单元测试。
 *
 * 用于续读时判断某个文字节点是否就是"命中段"本身（避免把同一段读两遍）。
 * 只比「相等」，绝不比「包含」——否则只要某段正文恰好是命中段的子串
 * （比如命中「你好世界」、续读节点是「世界」），就会被误判为重复而跳过，丢内容。
 */
public final class TextCompare {
    private TextCompare() {
    }

    public static boolean sameLine(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equals(b.trim());
    }
}
