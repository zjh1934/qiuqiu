package com.floating.combo;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部设置的唯一出入口。所有键集中在这里，别到处散着读写。 */
public class Prefs {

    public static final int[] COLORS = new int[]{
            0xFF1565C0,  // 蓝
            0xFF2E7D32,  // 绿
            0xFFC62828,  // 红
            0xFF6A1B9A,  // 紫
            0xFFEF6C00,  // 橙
            0xFF37474F,  // 墨
            0xFFFAFAFA,  // 白
    };
    public static final String[] COLOR_NAMES = new String[]{
            "蓝", "绿", "红", "紫", "橙", "墨", "白"
    };

    private static final String P = "cfg";

    private static final String K_BALL_ON = "ball_on";
    private static final String K_SIZE = "size";
    private static final String K_COLOR = "color";
    private static final String K_ALPHA = "alpha";
    private static final String K_IDLE_ALPHA = "idle_alpha";
    private static final String K_SCALE = "scale";
    private static final String K_HOLD = "hold_ms";
    private static final String K_SNAP = "snap";
    private static final String K_COMP = "compensate";
    private static final String K_BALL_X = "ball_x";
    private static final String K_BALL_Y = "ball_y";
    private static final String K_RATE = "rate";
    /** 3.3 语速默认值迁移标记。只写一次，之后用户改回 1.0 也不再动 */
    private static final String K_RATE_MIG_33 = "rate_mig_33";
    /** 3.4 不透明度默认值迁移标记。两个 alpha 共用一个标记，要么都迁要么都不迁 */
    private static final String K_ALPHA_MIG_34 = "alpha_mig_34";
    private static final String K_PITCH = "pitch";
    private static final String K_HAPTIC = "haptic";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        migrateRate33();
        migrateAlpha34();
    }

    /**
     * 3.3：语速默认值从 1.0 提到 2.0。
     *
     * 光改 rate() 的默认值对老用户没用 —— 他们 sp 里存着 1.0，
     * 升级完还是 1.0，等于没改。所以这里做一次迁移。
     *
     * 迁谁：**只迁还停在旧默认值 1.0 的**。主动调过的人（1.5、0.8…）一律不动。
     *
     * 代价说清楚：恰好把语速主动调到 1.0 的老用户会被一并迁到 2.0。
     * 无法区分"没调过所以是 1.0"和"调过、正好调成 1.0" ——
     * sp 里只有一个 float，没有"是否调过"的标记。
     * 两害相权：让所有老用户都停在 1.0（等于没升级）更糟，
     * 而且被迁的人手动调回 1.0 即可，标记已写，不会二次迁移。
     */
    private void migrateRate33() {
        if (sp.getBoolean(K_RATE_MIG_33, false)) {
            return;
        }
        sp.edit().putBoolean(K_RATE_MIG_33, true).apply();
        if (!sp.contains(K_RATE)) {
            return;     // 从没存过 = 新装，直接走 rate() 的新默认 2.0
        }
        float v = sp.getFloat(K_RATE, 1.0f);
        if (v == 1.0f) {
            sp.edit().putFloat(K_RATE, 2.0f).apply();
        }
    }

    /**
     * 3.4：不透明度默认值 92% / 闲置 30% → 都是 100%。
     *
     * 跟语速同一个问题：光改默认值对老用户没用，sp 里存着旧值。
     * 同样是**只迁还停在旧默认值的**（92% / 30%），调过的人不动。
     *
     * 两个 alpha 共用一个标记：**要么都迁、要么都不迁**。
     * 不能拆开 —— 只迁一个会配出"闲置比正常还实"这种从没存在过的组合，
     * 那是我们自己造出来的怪状态，不是用户见过的任何一种。
     *
     * 代价同语速那条：恰好主动调到 92% / 30% 的人会被一起迁走。
     */
    private void migrateAlpha34() {
        if (sp.getBoolean(K_ALPHA_MIG_34, false)) {
            return;
        }
        sp.edit().putBoolean(K_ALPHA_MIG_34, true).apply();
        boolean hasA = sp.contains(K_ALPHA);
        boolean hasI = sp.contains(K_IDLE_ALPHA);
        if (!hasA && !hasI) {
            return;     // 从没存过 = 新装，走新默认 100%
        }
        boolean onDefaultA = !hasA || sp.getFloat(K_ALPHA, 0.92f) == 0.92f;
        boolean onDefaultI = !hasI || sp.getFloat(K_IDLE_ALPHA, 0.30f) == 0.30f;
        if (!onDefaultA || !onDefaultI) {
            return;     // 有一项被调过 → 尊重用户的配置，两个都不动
        }
        SharedPreferences.Editor ed = sp.edit();
        if (hasA) {
            ed.putFloat(K_ALPHA, 1.0f);
        }
        if (hasI) {
            ed.putFloat(K_IDLE_ALPHA, 1.0f);
        }
        ed.apply();
    }

    public boolean ballOn() {
        return sp.getBoolean(K_BALL_ON, true);
    }

    public void setBallOn(boolean v) {
        sp.edit().putBoolean(K_BALL_ON, v).apply();
    }

    public int sizeDp() {
        return sp.getInt(K_SIZE, 56);
    }

    public void setSizeDp(int v) {
        sp.edit().putInt(K_SIZE, v).apply();
    }

    public int colorIndex() {
        int i = sp.getInt(K_COLOR, 0);
        if (i < 0 || i >= COLORS.length) {
            i = 0;
        }
        return i;
    }

    public void setColorIndex(int v) {
        sp.edit().putInt(K_COLOR, v).apply();
    }

    public int color() {
        return COLORS[colorIndex()];
    }

    /**
     * 球的正常不透明度。3.4 起默认 100%（原来是 92%）。
     * 滑杆范围仍是 20% ~ 100%，调过的人不受影响（见 migrateAlpha34）。
     */
    public float alpha() {
        return sp.getFloat(K_ALPHA, 1.0f);
    }

    public void setAlpha(float v) {
        sp.edit().putFloat(K_ALPHA, v).apply();
    }

    /**
     * 闲置 2.5 秒后变多淡。3.4 起默认 100%（原来是 30%）。
     *
     * 默认 100% 意味着**闲置淡出默认不再有视觉变化** —— 球一直保持全实。
     * 这是 3.4 有意的取舍：先保证"永远看得清"，想要变淡的人自己往下拉。
     * 淡出的代码逻辑没删，拉到 100% 以下立刻生效。
     */
    public float idleAlpha() {
        return sp.getFloat(K_IDLE_ALPHA, 1.0f);
    }

    public void setIdleAlpha(float v) {
        sp.edit().putFloat(K_IDLE_ALPHA, v).apply();
    }

    /** 放大倍率，1.5 ~ 5.0 */
    public float scale() {
        // 夹到有效区间：早期版本可能存过 1.0，
        // 那样"按住球"会亮起放大外环、画面却纹丝不动，看着像坏了
        float v = sp.getFloat(K_SCALE, 2.5f);
        if (v < 1.5f) {
            return 1.5f;
        }
        if (v > 5.0f) {
            return 5.0f;
        }
        return v;
    }

    public void setScale(float v) {
        sp.edit().putFloat(K_SCALE, v).apply();
    }

    /**
     * 按住多久算"要放大"。
     * 这是「点一下朗读」和「按住放大」的分界线，手快的人要调长。
     */
    public int holdMs() {
        return sp.getInt(K_HOLD, 260);
    }

    public void setHoldMs(int v) {
        sp.edit().putInt(K_HOLD, v).apply();
    }

    /**
     * 松手后自动贴边。
     * 默认关：球是"按住放大"的手柄，用户经常把它放在屏幕中间顺手的位置；
     * 一松手就弹到边上，下次又要重新找。
     */
    public boolean snapEdge() {
        return sp.getBoolean(K_SNAP, false);
    }

    public void setSnapEdge(boolean v) {
        sp.edit().putBoolean(K_SNAP, v).apply();
    }

    /**
     * 放大时按 1/s 缩小球的绘制，抵消系统对悬浮窗的放大。
     * 关掉的话放大时球会跟着变大。
     */
    public boolean compensate() {
        return sp.getBoolean(K_COMP, true);
    }

    public void setCompensate(boolean v) {
        sp.edit().putBoolean(K_COMP, v).apply();
    }

    /**
     * 球存过位置没有。
     *
     * 不能拿 -1 当"没存过"的哨兵：贴左边时窗口 x 本来就是负数
     * （clampX 允许到 -size/2），存进去之后会被误判成"没设置过"，
     * 下次启动球就自己跑回右边 —— 用户明明把它挪到左边了。
     */
    public boolean hasBallPos() {
        return sp.contains(K_BALL_X) && sp.contains(K_BALL_Y);
    }

    public int ballX() {
        return sp.getInt(K_BALL_X, 0);
    }

    public int ballY() {
        return sp.getInt(K_BALL_Y, 0);
    }

    public void setBallPos(int x, int y) {
        sp.edit().putInt(K_BALL_X, x).putInt(K_BALL_Y, y).apply();
    }

    /**
     * 语速 0.5 ~ 3.0，3.3 起默认 2.0、上限 3.0（原来是 1.0 / 2.0）。
     *
     * 为什么仍然要 clamp：外部核查确认 framework 层只校验 >0，
     * 不做任何上限 clamp，setSpeechRate(3.0) 会返回 SUCCESS，
     * 但**引擎实际行为未定义** —— 可能正常、可能内部 clamp、可能失真吞字，
     * 而应用层无法从任何回调感知。所以至少保证传进来的数在我们自己的区间内。
     */
    public float rate() {
        float v = sp.getFloat(K_RATE, 2.0f);
        if (v < 0.5f) {
            return 0.5f;
        }
        if (v > 3.0f) {
            return 3.0f;
        }
        return v;
    }

    public void setRate(float v) {
        sp.edit().putFloat(K_RATE, v).apply();
    }

    /** 音调 0.5 ~ 2.0 */
    public float pitch() {
        return sp.getFloat(K_PITCH, 1.0f);
    }

    public void setPitch(float v) {
        sp.edit().putFloat(K_PITCH, v).apply();
    }

    /** 点按时震一下。低视力看不清球变不变色，靠这个确认点上了 */
    public boolean haptics() {
        return sp.getBoolean(K_HAPTIC, true);
    }

    public void setHaptics(boolean v) {
        sp.edit().putBoolean(K_HAPTIC, v).apply();
    }
}
