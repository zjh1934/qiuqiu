package com.floating.combo;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 触觉反馈。
 *
 * ── 为什么 2.7 之前一直"感觉不到" ──
 * 排查下来有三处会静默放行，每一处都足以让震动整个失效：
 *   1. 先检查 hasVibrator() 再震。有些机器谎报"没有马达"，
 *      这一句就把整个功能关掉了，日志还只写一句"设备没有马达"。
 *   2. 设备不支持振幅控制时，混着传 255 和 DEFAULT_AMPLITUDE(-1)，
 *      部分 ROM 的 createWaveform 直接抛异常，被 catch 吃掉。
 *   3. 震动调用写在 onZoomStart 最后，前面任何一步出问题就轮不到它。
 *
 * 现在的策略：
 *   - 不再用 hasVibrator() 当门槛，直接震，震不动再记日志
 *   - 只有"系统明确说支持振幅控制"才带振幅，否则走最兼容的老 API
 *   - 凡是失败都写进日志（搜 haptic），并且有"试一下"按钮可当场验证
 *
 * 节奏设计对齐 iOS 缩放控制器：按住 → 缩放生效那一下震一次，松手不震。
 */
public final class Haptics {

    /** 最大振幅。只有设备明确支持振幅控制时才用 */
    private static final int AMP = 255;

    private static Vibrator v = null;
    private static boolean enabled = true;

    /** 上一次震动的诊断信息，界面上"试一下"按钮会显示 */
    private static String lastError = "";

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
        } catch (Throwable t) {
            v = null;
        }
        Log.i("haptic", "初始化 " + (v == null ? "拿不到 Vibrator" : "ok")
                + " 系统说有马达=" + hasVibrator()
                + " 可控振幅=" + amplitudeControl(v));
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * 这台机器到底能不能震。
     * 注意：这个返回值**只用来显示**，不再用来决定震不震 ——
     * hasVibrator() 在部分机型上会谎报，拿它当门槛等于自废武功。
     */
    public static boolean available() {
        return v != null;
    }

    /** 给界面看的一行诊断，出问题直接照抄给开发者 */
    public static String diag() {
        if (v == null) {
            return "拿不到 Vibrator（系统没给）";
        }
        return (hasVibrator() ? "系统说有马达" : "系统说无马达（仍会尝试震动）")
                + " · 振幅可控：" + (amplitudeControl(v) ? "是" : "否");
    }

    /** 上一次为什么没震成 */
    public static String lastError() {
        return lastError;
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
     * 缩放启动的那一下 —— 整个 App 里最重要的一次触感。
     *
     * 对齐 iOS 的缩放控制器：按住，缩放生效的那一瞬间震一下，
     * 用户"感觉到震动"就知道画面已经放大了，接下来拖手指就行；
     * 松手缩放结束，不再震。
     */
    public static void zoom() {
        buzz("zoom", new long[]{0, 60}, -1);
    }

    /** 一记长震：这里没读到字 / 出错 */
    public static void error() {
        buzz("error", new long[]{0, 200}, -1);
    }

    /**
     * 不管开关是开是关，强制震一下 —— 界面上"试一下"按钮用。
     * 原来只能在"把开关打开"的那一刻试一次，开关本来就是开的话
     * 反而没法验证，出了问题完全无从判断。
     *
     * @return 失败原因，空字符串表示震成功了
     */
    public static String test() {
        boolean was = enabled;
        enabled = true;
        zoom();
        enabled = was;
        return lastError;
    }

    private static boolean hasVibrator() {
        try {
            return v != null && v.hasVibrator();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean amplitudeControl(Vibrator vv) {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && vv != null && vv.hasAmplitudeControl();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void buzz(String name, long[] timings, int repeat) {
        if (!enabled) {
            Log.i("haptic", name + " 跳过（开关关着）");
            return;
        }
        final Vibrator vv = v;
        if (vv == null) {
            lastError = "没有 Vibrator 实例";
            Log.w("haptic", name + " 跳过（没有 Vibrator 实例）");
            return;
        }
        try {
            boolean done = false;
            // 只有明确支持振幅控制才带振幅。
            // 不支持的机器上传 DEFAULT_AMPLITUDE 混 255，部分 ROM 会直接抛异常。
            if (amplitudeControl(vv)) {
                try {
                    int n = timings.length;
                    int[] amps = new int[n];
                    amps[0] = 0;
                    for (int i = 1; i < n; i++) {
                        amps[i] = AMP;
                    }
                    vv.vibrate(VibrationEffect.createWaveform(timings, amps, repeat));
                    done = true;
                } catch (Throwable t) {
                    Log.w("haptic", name + " 带振幅失败，退回普通震动：" + t);
                }
            }
            if (!done) {
                // 最兼容的一条路：老 API，不碰振幅。
                // 刻意不先查 hasVibrator() —— 有些机器谎报"没有马达"，
                // 一查就把功能关了，而实际上震得动。
                vv.vibrate(timings, repeat);
            }
            lastError = "";
            Log.i("haptic", name + " " + timings[timings.length - 1] + "ms"
                    + (amplitudeControl(vv) ? "（带振幅）" : "（普通）"));
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w("haptic", name + " 失败：" + t);
        }
    }
}
