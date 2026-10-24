package com.floating.combo;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 触觉反馈（对应 iOS 那套"轻点确认"的触感）。
 *
 * ── 为什么第一版"没落地" ──
 * 之前用 15~22ms 的纯时长震动，而且没设振幅。很多机器上这么短的触发
 * 根本感觉不到（马达起转本身就要十几毫秒），用户自然以为没做。
 * 现在：时长从 30ms 起、API 26+ 显式给最大振幅、每次触发都写日志
 * （导日志搜 "haptic" 就能确认到底有没有调用）。
 *
 * 四种节奏刻意拉开差别，手指能分辨：
 *   tap    一下        —— 取消 / 一般确认
 *   ready  两下        —— 进入选字态，等你点字
 *   speak  一下稍长    —— 开始朗读
 *   error  一记长震    —— 这里没读到字
 *
 * 开关在「球」页，关了就是完全不震，不影响朗读。
 */
public final class Haptics {

    /** 最大振幅。不给的话部分 ROM 按三分之一强度震，几乎摸不到 */
    private static final int AMP = 255;

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
        Log.i("haptic", "初始化 " + (v == null ? "拿不到震动马达" : "ok")
                + " 有马达=" + (v != null && v.hasVibrator())
                + " 可控振幅=" + hasAmplitude());
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /** 这台机器到底能不能震 */
    public static boolean available() {
        try {
            return v != null && v.hasVibrator();
        } catch (Exception e) {
            return false;
        }
    }

    /** 一下：取消 / 一般确认 */
    public static void tap() {
        buzz("tap", new long[]{0, 30}, -1);
    }

    /** 两下：进入选字态，该你点字了 */
    public static void ready() {
        buzz("ready", new long[]{0, 40, 90, 40}, -1);
    }

    /** 一下稍长：开始朗读 */
    public static void speak() {
        buzz("speak", new long[]{0, 70}, -1);
    }

    /**
     * 缩放启动的那一下 —— 这是整个 App 里最重要的一次触感。
     *
     * 对齐 iOS 的缩放控制器：按住控制器，缩放生效的那一瞬间震一下，
     * 用户"感觉到震动"就知道画面已经放大了，接下来拖手指就行；
     * 松手缩放结束，不再震。
     * （Apple 支持文档原话：按壓螢幕並於感覺到震動時放開）
     *
     * 用一次单发、干脆的冲击，不用连续震动 —— 按住期间持续震动
     * 既耗电又让人分不清"是我在动还是它在震"。
     */
    public static void zoom() {
        buzz("zoom", new long[]{0, 45}, -1);
    }

    /** 一记长震：这里没读到字 / 出错 */
    public static void error() {
        buzz("error", new long[]{0, 200}, -1);
    }

    private static boolean hasAmplitude() {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl();
        } catch (Exception e) {
            return false;
        }
    }

    private static void buzz(String name, long[] timings, int repeat) {
        if (!enabled) {
            Log.i("haptic", name + " 跳过（开关关着）");
            return;
        }
        if (v == null) {
            Log.w("haptic", name + " 跳过（没有 Vibrator 实例）");
            return;
        }
        try {
            if (!v.hasVibrator()) {
                Log.w("haptic", name + " 跳过（设备没有马达）");
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // timings 和 amplitudes 必须等长；第一段是等待时间，振幅必须为 0
                int n = timings.length;
                int[] amps = new int[n];
                amps[0] = 0;
                for (int i = 1; i < n; i++) {
                    amps[i] = hasAmplitude() ? AMP : VibrationEffect.DEFAULT_AMPLITUDE;
                }
                v.vibrate(VibrationEffect.createWaveform(timings, amps, repeat));
            } else {
                v.vibrate(timings, repeat);
            }
            Log.i("haptic", name + " " + timings[timings.length - 1] + "ms");
        } catch (Exception e) {
            // 没马达、或被系统策略拦了：静默降级，不影响朗读
            Log.w("haptic", name + " 失败：" + e);
        }
    }
}
