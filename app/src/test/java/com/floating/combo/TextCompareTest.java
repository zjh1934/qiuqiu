package com.floating.combo;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 续读去重比较的单元测试。
 * 关键回归：某段正文是命中段的子串时，绝不能当成"重复"跳过（否则丢内容）。
 */
public class TextCompareTest {

    @Test
    public void equalLinesAreSame() {
        assertTrue(TextCompare.sameLine("你好世界", "你好世界"));
        assertTrue(TextCompare.sameLine("  你好世界  ", "你好世界"));
    }

    @Test
    public void subStringMustNotBeTreatedAsDuplicate() {
        // 命中"你好世界"、续读节点是"世界" —— 必须当成不同内容，读出来
        assertFalse(TextCompare.sameLine("你好世界", "世界"));
        assertFalse(TextCompare.sameLine("世界", "你好世界"));
        assertFalse(TextCompare.sameLine("你好世界", "你好地球"));
        assertFalse(TextCompare.sameLine("一段长文本", "文本"));
    }

    @Test
    public void nullSafe() {
        assertFalse(TextCompare.sameLine(null, "x"));
        assertFalse(TextCompare.sameLine("x", null));
        assertFalse(TextCompare.sameLine(null, null));
    }
}
