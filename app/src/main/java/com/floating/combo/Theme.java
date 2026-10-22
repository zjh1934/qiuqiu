package com.floating.combo;

import android.graphics.drawable.GradientDrawable;

/**
 * 球球 2.0 视觉规范：集中管理配色、圆角、描边与品牌渐变。
 *
 * 纯展示层 —— 不引用任何核心逻辑（TTS / 抓字 / 日志 / 手势）。
 * 所有圆角/描边参数都用 px 传入（由调用方用 dp() 换算）。
 */
public final class Theme {

    // 背景与表面
    public static final int BG        = 0xFF0D1117; // 近黑、带一丝冷蓝
    public static final int SURFACE   = 0xFF161B22; // 卡片底
    public static final int SURFACE_2 = 0xFF1F2630; // 次级按钮 / 输入框底
    public static final int BORDER    = 0xFF2A313C; // 描边

    // 文字
    public static final int TEXT   = 0xFFE6EDF3; // 主文字
    public static final int TEXT_2 = 0xFF8B949E; // 次要文字
    public static final int TEXT_3 = 0xFF6E7681; // 更弱

    // 品牌强调（青→蓝渐变）
    public static final int ACCENT = 0xFF38BDF8; // 天蓝，用于数值 / 高亮
    public static final int TEAL   = 0xFF2DD4BF;
    public static final int BLUE   = 0xFF3B82F6;

    // 语义色
    public static final int DANGER     = 0xFFF87171;
    public static final int TRANSPARENT = 0x00000000;

    private Theme() {
    }

    /** 圆角矩形：填充 + 可选描边（strokePx<=0 时不描边）。 */
    public static GradientDrawable rounded(int color, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0f) {
            g.setStroke((int) (strokePx + 0.5f), strokeColor);
        }
        return g;
    }

    /** 品牌渐变药丸（青→蓝）。 */
    public static GradientDrawable brandGradientPill(float radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{TEAL, BLUE});
        g.setCornerRadius(radiusPx);
        return g;
    }
}
