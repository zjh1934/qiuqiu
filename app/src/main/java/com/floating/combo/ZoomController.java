package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.MagnificationController;
import android.os.Build;

/**
 * 放大控制。用 API 24 起就有的 setScale / setCenter。
 * （MagnificationConfig 是 API 33 才有，AIDE 的 android.jar 里没有，会报找不到符号）
 *
 * ── 中心点怎么算 ──
 * 系统放大的映射（内容坐标 C → 屏幕位置 P，放大中心 K，倍率 s）：
 *      P = S/2 + s × (C − K)
 *
 * 要的是：手指移到哪，就看到屏幕上那个位置的放大内容 —— 手指是【不动点】：
 *      fx = S/2 + s × (fx − K)
 *      K  = fx − (fx − S/2) / s
 *
 * 推论：在不动点处 内容坐标 = 屏幕坐标 = fx。
 * 所以球的窗口位置直接就是 fx − 球宽/2，不用换算。
 *
 * 【别写成 K = K0 − ΔF/s】
 * 那是"手指抓着内容不动"，画面会相对手指静止，看着像不会左右移动。
 */
public class ZoomController {

    private final AccessibilityService svc;
    private final Prefs prefs;

    private int screenW = 0, screenH = 0;
    private float curScale = 1f;
    private float curCx = 0, curCy = 0;
    private boolean zoomOn = false;

    public ZoomController(AccessibilityService svc, Prefs prefs) {
        this.svc = svc;
        this.prefs = prefs;
    }

    public void setScreen(int w, int h) {
        screenW = w;
        screenH = h;
        if (curCx <= 0 || curCy <= 0) {
            curCx = w / 2f;
            curCy = h / 2f;
        }
    }

    public boolean isOn() {
        return zoomOn;
    }

    public float scale() {
        return curScale;
    }

    /** 以手指为不动点起放大 */
    public void zoomInAt(float fx, float fy) {
        curScale = prefs.scale();
        zoomOn = true;
        panTo(fx, fy);
    }

    public void zoomOut() {
        curScale = 1f;
        zoomOn = false;
        reset(false);
    }

    /** 拖动中调用：手指是不动点，每帧独立算，不累加，不会漂 */
    public void panTo(float fx, float fy) {
        if (!zoomOn || curScale <= 1.01f || screenW <= 0) {
            return;
        }
        curCx = fx - (fx - screenW / 2f) / curScale;
        curCy = fy - (fy - screenH / 2f) / curScale;
        clampCenter();
        commit(false);
    }

    /** 中心点不许跑出可视内容区，否则会露出屏幕外空白 */
    private void clampCenter() {
        if (curScale <= 1.01f || screenW <= 0) {
            return;
        }
        float hw = screenW / (2f * curScale);
        float hh = screenH / (2f * curScale);
        curCx = Math.max(hw, Math.min(screenW - hw, curCx));
        curCy = Math.max(hh, Math.min(screenH - hh, curCy));
    }

    /** 屏幕坐标 → 内容坐标 */
    public float screenToContentX(float px) {
        if (!zoomOn || curScale <= 1.01f) {
            return px;
        }
        return curCx + (px - screenW / 2f) / curScale;
    }

    public float screenToContentY(float py) {
        if (!zoomOn || curScale <= 1.01f) {
            return py;
        }
        return curCy + (py - screenH / 2f) / curScale;
    }

    private MagnificationController mag() {
        try {
            return svc.getMagnificationController();
        } catch (Exception e) {
            return null;
        }
    }

    private void commit(boolean animate) {
        MagnificationController c = mag();
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            c.setScale(curScale, animate);
            c.setCenter(curCx, curCy, animate);
        } catch (Exception ignored) {
        }
    }

    private void reset(boolean animate) {
        MagnificationController c = mag();
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        try {
            c.reset(animate);
        } catch (Exception ignored) {
            // 有些 ROM 的 reset 会抛异常，退回手动置 1 倍
            try {
                c.setScale(1f, animate);
                c.setCenter(screenW / 2f, screenH / 2f, animate);
            } catch (Exception ignored2) {
            }
        }
    }
}
