package com.floating.combo;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 触觉反馈（对应 iOS 那套"轻点确认"的触感）。
 *
 * 为什么无障碍软件要有这个：
 *   球的三个状态（待命 / 选字 / 朗读）原来全靠颜色区分。
 *   低视力的人看不清颜色变化，手指又常常点偏 —— 到底点上了没有，全靠猜。
 *   震一下是最快、最不打扰的确认方式：不用看屏幕，也不用等语音。
 *
 * 四种节奏刻意拉开差别，手指能分辨：
 *   tap    一下轻点        —— 取消、一般确认
 *   ready  两下短震        —— 进入选字态，等你点字
 *   speak  一下稍长        —— 开始朗读
 *   error  一记长震        —— 这里没读到字
 *
 * 开关在「球」页，关了就是完全静音（不震），不影响朗读。
 */
public final class Haptics {

    private static Vibrator v = null;
    private static boolean enabled = true;

    private Haptics() {
    }

    public static void init(Context c, boolean on) {
        enabled = on;
        if (v != null) {
            return;
        }
        try {
            v = (Vibrator) c.getApplicationContext()
                    .getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) {
            v = null;
        }
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** 一下轻点：取消 / 一般确认 */
    public static void tap() {
        buzz(new long[]{0, 15}, -1);
    }

    /** 两下短震：进入选字态，该你点字了 */
    public static void ready() {
        buzz(new long[]{0, 22, 70, 22}, -1);
    }

    /** 一下稍长：开始朗读 */
    public static void speak() {
        buzz(new long[]{0, 40}, -1);
    }

    /** 一记长震：这里没读到字 / 出错 */
    public static void error() {
        buzz(new long[]{0, 120}, -1);
    }

    private static void buzz(long[] pattern, int repeat) {
        if (!enabled || v == null) {
            return;
        }
        try {
            if (!v.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat));
            } else {
                v.vibrate(pattern, repeat);
            }
        } catch (Exception ignored) {
            // 没马达、或被系统策略拦了：静默降级，不影响朗读
        }
    }
}
