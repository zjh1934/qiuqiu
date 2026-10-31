package com.floating.combo;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
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
 *
 * ── 3.2 起的三档分配 ──
 *   长按放大  强震（长 + 满振幅）
 *   轻点      普通一下（短 + 弱振幅）
 *   取词全程  完全不震
 *
 * 为什么"强"不能只靠换常量：Android 官方明确说 HapticFeedbackConstants
 * 表达的是**事件类型**（长按 / 键盘敲击 / 时钟刻度），不是强度档位，
 * 实际效果由设备实现决定，官方不保证 LONG_PRESS 强于 KEYBOARD_TAP。
 * 所以强弱只能靠【回退通道上的时长 + 振幅】拉开，常量只用于系统通道那一路。
 */
public final class Haptics {

    /** 最大振幅。只有设备明确支持振幅控制时才用 */
    private static final int AMP = 255;
    /** 轻档振幅。跟满振幅拉开明显差距，别做成"只是短一点" */
    private static final int AMP_LIGHT = 110;

    private static Vibrator v = null;
    private static boolean enabled = true;
    /**
     * 球那个 View。系统自带的 UI 触感通道（performHapticFeedback）必须挂在
     * 一个真实存在的 View 上才能走 —— 悬浮球正好就是一个。
     */
    private static View view = null;

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

    /** 球显示/隐藏时把 View 挂上/摘掉 */
    public static void attach(View vw) {
        view = vw;
    }

    /**
     * 走系统自己的 UI 触感通道。
     *
     * 这是所有正常 App 做按键反馈的方式：由系统的"触感反馈"开关统一管，
     * 默认是开的。之前只用原始 Vibrator，在小米 + Android 16 上
     * 调用返回成功、日志也打印了，但系统根本没震 —— 实测就是被这一层拦掉。
     *
     * @param constant 事件类型常量。注意它只表达"什么事件"，不保证强度。
     */
    private static boolean viaView(int constant) {
        View vw = view;
        if (vw == null) {
            return false;
        }
        try {
            return vw.performHapticFeedback(constant);
        } catch (Throwable t) {
            return false;
        }
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

    /** 轻点：一下短震、弱振幅。取消 / 一般确认 / 界面按钮 */
    public static void tap() {
        buzz("tap", HapticFeedbackConstants.KEYBOARD_TAP,
                new long[]{0, 25}, AMP_LIGHT);
    }

    /**
     * 长按放大：强震 —— 整个 App 里最重要的一次触感。
     *
     * 对齐 iOS 的缩放控制器：按住，缩放生效的那一瞬间震一下，
     * 用户"感觉到震动"就知道画面已经放大了，接下来拖手指就行；
     * 松手缩放结束，不再震。
     *
     * 强在哪：系统通道给不了强度保证，所以靠回退通道上的
     * 80ms + 满振幅（对比轻点的 25ms + 弱振幅）拉开体感差距。
     */
    public static void zoom() {
        buzz("zoom", HapticFeedbackConstants.LONG_PRESS,
                new long[]{0, 80}, AMP);
    }

    /** 一记长震：这里没读到字 / 出错 */
    public static void error() {
        // 错误要的是"一记长震"，系统通道给不了这个长度，所以直接走原始接口
        buzzRaw("error", new long[]{0, 200}, AMP);
    }

    /*
     * ── 取词全程不震 ──
     * 3.2 起，"点球进选取态"和"开始朗读"这两处都不再震动。
     * 原来分别是 ready()（两下短震）和 speak()（一下稍长），已删除。
     *
     * 代价要说清楚：低视力用户本来靠震动确认"进入选取态了 / 开始读了"，
     * 现在只能靠球的颜色（选取态绿、朗读中橙）。
     * 但"读不到字"那记长震（error）保留 —— 那是失败报警不是状态提示，
     * 去掉的话点了个空白区域会完全不知道为什么没反应。
     */

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

    /** 无障碍用途：告诉系统这是辅助功能反馈，静音/勿扰模式不该把它屏蔽 */
    private static AudioAttributes attrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build();
    }

    /** 先试系统触感通道，没走成才退回原始 Vibrator（那时才用得上振幅） */
    private static void buzz(String name, int viewConstant, long[] timings, int amp) {
        if (viaView(viewConstant)) {
            lastError = "";
            Log.i("haptic", name + "（系统触感通道）");
            return;
        }
        buzzRaw(name, timings, amp);
    }

    private static void buzzRaw(String name, long[] timings, int amp) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    VibrationEffect fx;
                    if (timings.length <= 2) {
                        // 单次震动用 createOneShot：
                        // 之前一律用 createWaveform({0, n}, {0, amp})，
                        // 首段 0 时长在部分 ROM 上会整个不生效
                        fx = VibrationEffect.createOneShot(
                                timings[timings.length - 1],
                                amplitudeControl(vv) ? amp : VibrationEffect.DEFAULT_AMPLITUDE);
                    } else {
                        int n = timings.length;
                        int[] amps = new int[n];
                        amps[0] = 0;
                        for (int i = 1; i < n; i++) {
                            amps[i] = amplitudeControl(vv) ? amp
                                    : VibrationEffect.DEFAULT_AMPLITUDE;
                        }
                        fx = VibrationEffect.createWaveform(timings, amps, -1);
                    }
                    vv.vibrate(fx, attrs());
                    done = true;
                } catch (Throwable t) {
                    Log.w("haptic", name + " VibrationEffect 失败，退回老接口：" + t);
                }
            }
            if (!done) {
                // 最兼容的一条路：老 API，不碰振幅。
                // 刻意不先查 hasVibrator() —— 有些机器谎报"没有马达"，
                // 一查就把功能关了，而实际上震得动。
                vv.vibrate(timings, -1);
            }
            lastError = "";
            Log.i("haptic", name + " " + timings[timings.length - 1] + "ms");
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w("haptic", name + " 失败：" + t);
        }
    }
}
