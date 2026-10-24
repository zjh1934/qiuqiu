package com.floating.combo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * 悬浮球手势控制器。
 *
 * 三种手势，靠「时间」和「位移」两条线分开：
 *   手指一动（超 slop）  → 挪球
 *   按住不动超过 holdMs → 放大（手指到哪，球到哪；画面松手就还原，球留在手底下）
 *   按住不动，holdMs 内松手 → 轻点（交给上层，用于朗读）
 *
 * ── 为什么之前"一点都不跟手" ──
 * 系统全屏放大时，悬浮窗会被一起放大：球变大 s 倍、位置以屏幕中心为锚点外扩 s 倍。
 * 手指挪 1mm，球视觉上挪 3mm —— 那就是不跟手的确切感受。
 *
 * ── 怎么解决 ──
 * 窗口尺寸保持设定值（触摸区稳定），绘制时按 1/s 缩小抵消系统放大；
 * 位置用放大公式反推，让球的视觉中心精确落在手指下。
 */
public class BallController implements View.OnTouchListener {

    public interface Callback {
        /** 按住够久了，要放大。给的是手指位置 */
        void onZoomStart(float fx, float fy);

        /** 放大中，手指挪到 (fx,fy) */
        void onZoomMove(float fx, float fy);

        /** 松手，结束放大 */
        void onZoomEnd();

        /** 轻点（按住时间短、几乎没移动） */
        void onTap();

        /** 球位置变了，存起来 */
        void onMoved(int x, int y);
    }

    private static final int ST_IDLE = 0;
    /** 按下后还没判定：不知道要拖、要放大、还是要轻点 */
    private static final int ST_PENDING = 1;
    private static final int ST_DRAG_BALL = 2;
    private static final int ST_ZOOMING = 3;
    private static final long IDLE_MS = 2500;

    private final Context ctx;
    private final WindowManager wm;
    private final Prefs prefs;
    private final ZoomController zoom;
    private final Callback cb;
    private final Handler h = new Handler();

    private BallView view = null;
    private WindowManager.LayoutParams lp = null;

    private int state = ST_IDLE;
    private float offsetX, offsetY;
    private float downRawX, downRawY;
    private float lastFx, lastFy;
    private float dragOffsetCx, dragOffsetCy;
    private boolean faded = false;

    private int screenW = 0, screenH = 0;
    private ValueAnimator settleAnim = null;

    public BallController(Context c, Prefs p, ZoomController z, Callback cb) {
        // 关键：必须用【服务自己的 Context】。
        // TYPE_ACCESSIBILITY_OVERLAY 会校验窗口所有者是不是无障碍服务，
        // 用 applicationContext 建的 WindowManager 在多数 ROM 上加不进去，
        // 表现就是"点了开关球不出来"。
        ctx = c;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs = p;
        zoom = z;
        this.cb = cb;
        measure();
    }

    /**
     * 屏幕尺寸变了（旋转 / 分屏 / 折叠屏展开）之后必须重算，
     * 否则 clamp 还按旧尺寸算，球可能停在屏幕外，用户再也点不到。
     */
    public void remeasure() {
        measure();
        if (view == null || lp == null) {
            return;
        }
        lp.x = clampX(lp.x, lp.width);
        lp.y = clampY(lp.y, lp.height);
        commit();
        if (cb != null) {
            cb.onMoved(lp.x, lp.y);
        }
    }

    private void measure() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        } catch (Exception e) {
            screenW = 1080;
            screenH = 1920;
        }
    }

    public int screenW() {
        return screenW;
    }

    public int screenH() {
        return screenH;
    }

    public float centerX() {
        return lp == null ? screenW / 2f : lp.x + lp.width / 2f;
    }

    public float centerY() {
        return lp == null ? screenH / 2f : lp.y + lp.height / 2f;
    }

    // ---------- 显示 / 隐藏 ----------

    /** 上一次加窗口失败的原因，给界面显示用。成功则为 null */
    public String lastError() {
        return lastErr;
    }

    private String lastErr = null;

    public void show() {
        if (view != null) {
            applyPrefs();
            return;
        }
        measure();
        int size = dp(prefs.sizeDp());
        lastErr = null;

        view = new BallView(ctx);
        view.setBallColor(prefs.color());
        view.setBallAlpha(prefs.alpha());

        // 三种窗口类型，从最合适到最兜底，挨个试
        int[] types;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            types = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.TYPE_PHONE,
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            types = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_PHONE,
            };
        } else {
            types = new int[]{WindowManager.LayoutParams.TYPE_PHONE};
        }

        int sx = prefs.ballX();
        int sy = prefs.ballY();
        if (!prefs.hasBallPos()) {
            sx = screenW - size - dp(8);
            sy = screenH / 3;
        }

        boolean added = false;
        for (int i = 0; i < types.length; i++) {
            lp = new WindowManager.LayoutParams(
                    size, size, types[i],
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = clampX(sx, size);
            lp.y = clampY(sy, size);
            try {
                wm.addView(view, lp);
                added = true;
                Log.i("ball", "加窗口成功 type=" + types[i] + " size=" + size
                        + " at (" + lp.x + "," + lp.y + ")");
                break;
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName()
                        + ": " + String.valueOf(e.getMessage());
                Log.e("ball", "加窗口失败 type=" + types[i] + " → " + lastErr);
                lp = null;
            }
        }

        if (!added) {
            view = null;
            Log.e("ball", "三种窗口类型全失败，球出不来。最后错误：" + lastErr);
            return;
        }
        view.setOnTouchListener(this);
        scheduleIdle();
    }

    /** 球现在真的挂在窗口上吗 */
    public boolean isShown() {
        return view != null && view.getParent() != null;
    }

    /**
     * 把球提到最上层。
     * 覆盖层加进来会盖住球，原来靠 hide+show 解决，
     * 但那样球会消失一帧、状态也丢了。改成重新挂一次，不重建。
     */
    public void bringToFront() {
        if (view == null || lp == null || wm == null) {
            return;
        }
        if (view.getParent() == null) {
            return;
        }
        try {
            wm.removeView(view);
            wm.addView(view, lp);
        } catch (Exception e) {
            Log.e("ball", "球提到上层失败", e);
            // remove 已经生效、add 却失败：球就这样凭空消失了。
            // 重建一次，别让用户"点一下球，球没了"
            view = null;
            lp = null;
            show();
        }
    }

    /** 重新挂上状态（重建或提到上层后，颜色/图标要跟当前状态一致） */
    public void reapplyState(int s) {
        if (view != null) {
            view.setState(s);
        }
    }

    public void hide() {
        cancelAnim();
        h.removeCallbacks(idleTask);
        // 按住还没到判定时间就被隐藏（比如用户在界面里把开关关了），
        // holdTask 到点会去碰已经置空的 view —— 直接崩在服务主线程上
        h.removeCallbacks(holdTask);
        state = ST_IDLE;
        faded = false;
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
        }
        view = null;
        lp = null;
    }

    public void applyPrefs() {
        if (view == null || lp == null) {
            return;
        }
        int size = dp(prefs.sizeDp());
        view.setBallColor(prefs.color());
        view.setBallAlpha(faded ? prefs.idleAlpha() : prefs.alpha());
        lp.width = size;
        lp.height = size;
        lp.x = clampX(lp.x, size);
        lp.y = clampY(lp.y, size);
        commit();
    }

    public void setState(int s) {
        if (view != null) {
            view.setState(s);
        }
    }

    /** 强制恢复待命外观 */
    public void resetLook() {
        if (view != null) {
            view.setDrawScale(1f);
            view.setState(BallView.ST_NORMAL);
        }
    }

    // ---------- 手势 ----------

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        if (lp == null) {
            return false;
        }
        float fx = e.getRawX();
        float fy = e.getRawY();

        switch (e.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                cancelAnim();
                h.removeCallbacks(idleTask);
                if (faded) {
                    faded = false;
                    view.setBallAlpha(prefs.alpha());
                }
                downRawX = fx;
                downRawY = fy;
                lastFx = fx;
                lastFy = fy;
                offsetX = fx - lp.x;
                offsetY = fy - lp.y;
                state = ST_PENDING;
                h.removeCallbacks(holdTask);
                h.postDelayed(holdTask, prefs.holdMs());
                Log.gesture("按下 (" + (int) fx + "," + (int) fy + ")"
                        + " 等待 " + prefs.holdMs() + "ms 判定");
                return true;

            case MotionEvent.ACTION_MOVE:
                lastFx = fx;
                lastFy = fy;

                if (state == ST_PENDING && overSlop(fx - downRawX, fy - downRawY)) {
                    h.removeCallbacks(holdTask);
                    state = ST_DRAG_BALL;
                    Log.gesture("手指动了 → 判定为拖球");
                    if (zooming()) {
                        dragOffsetCx = zoom.screenToContentX(downRawX) - (lp.x + lp.width / 2f);
                        dragOffsetCy = zoom.screenToContentY(downRawY) - (lp.y + lp.height / 2f);
                    } else {
                        dragOffsetCx = offsetX - lp.width / 2f;
                        dragOffsetCy = offsetY - lp.height / 2f;
                    }
                }

                if (state == ST_DRAG_BALL) {
                    placeBallKeepOffset(fx, fy);
                } else if (state == ST_ZOOMING) {
                    if (cb != null) {
                        cb.onZoomMove(fx, fy);
                    }
                    placeBallAtFinger(fx, fy);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                h.removeCallbacks(holdTask);
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                int st = state;
                state = ST_IDLE;
                Log.gesture("抬手 原状态=" + stName(st)
                        + " 位移=" + (int) Math.sqrt((fx - downRawX) * (fx - downRawX)
                        + (fy - downRawY) * (fy - downRawY)) + "px"
                        + (cancel ? " (被系统取消)" : ""));

                if (st == ST_PENDING) {
                    if (!cancel && cb != null) {
                        cb.onTap();
                    }
                } else if (st == ST_ZOOMING) {
                    if (cb != null) {
                        cb.onZoomEnd();
                    }
                }

                view.setDrawScale(1f);

                // 只有真拖动过才重新落位。
                // 原来轻点也走这条：球停在中屏时，点一下要读字，球却自己弹到边上，
                // 下次更点不中。轻点不改位置。
                if (st == ST_DRAG_BALL) {
                    if (prefs.snapEdge()) {
                        animateToEdge((int) (fx - offsetX), (int) (fy - offsetY));
                    } else {
                        lp.x = clampX((int) (fx - offsetX), lp.width);
                        lp.y = clampY((int) (fy - offsetY), lp.height);
                        commit();
                        if (cb != null) {
                            cb.onMoved(lp.x, lp.y);
                        }
                    }
                } else if (st == ST_ZOOMING) {
                    // 放大松手：球留在手指最后的位置。
                    // 原来这里会退回放大前的位置，用户松手后球自己"跑掉"了 ——
                    // 而他刚才是用手指把球拖过来的，球留在手底下才符合直觉。
                    // 只做边界收拢，别让它停在屏幕外。
                    lp.x = clampX((int) (fx - lp.width / 2f), lp.width);
                    lp.y = clampY((int) (fy - lp.height / 2f), lp.height);
                    commit();
                    if (cb != null) {
                        cb.onMoved(lp.x, lp.y);
                    }
                }
                scheduleIdle();
                return true;
            }

            default:
                return false;
        }
    }

    // ---------- 位置核心 ----------

    private boolean zooming() {
        return zoom != null && zoom.isOn() && zoom.scale() > 1.01f;
    }

    private void placeBallAtFinger(float fx, float fy) {
        if (lp == null || view == null) {
            return;
        }
        lp.x = (int) (fx - lp.width / 2f);
        lp.y = (int) (fy - lp.height / 2f);
        if (zooming() && prefs.compensate()) {
            view.setDrawScale(1f / zoom.scale());
        } else {
            view.setDrawScale(1f);
        }
        commit();
    }

    private void placeBallKeepOffset(float fx, float fy) {
        if (lp == null || view == null) {
            return;
        }
        if (zooming() && prefs.compensate()) {
            float cx = zoom.screenToContentX(fx) - dragOffsetCx;
            float cy = zoom.screenToContentY(fy) - dragOffsetCy;
            lp.x = (int) (cx - lp.width / 2f);
            lp.y = (int) (cy - lp.height / 2f);
            view.setDrawScale(1f / zoom.scale());
        } else {
            lp.x = (int) (fx - offsetX);
            lp.y = (int) (fy - offsetY);
            view.setDrawScale(1f);
        }
        commit();
    }

    private void commit() {
        if (view == null || lp == null || view.getParent() == null) {
            return;
        }
        try {
            wm.updateViewLayout(view, lp);
        } catch (Exception ignored) {
        }
    }

    private void animateToEdge(int fromX, int fromY) {
        cancelAnim();
        final int from = fromX;
        int size = lp.width;
        int to = (from + size / 2 < screenW / 2)
                ? (int) (-size * 0.35f)
                : (int) (screenW - size * 0.65f);

        settleAnim = ValueAnimator.ofInt(from, to);
        settleAnim.setDuration(320);
        settleAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                if (lp == null) {
                    return;
                }
                lp.x = (Integer) a.getAnimatedValue();
                lp.y = clampY(lp.y, lp.height);
                commit();
            }
        });
        settleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cb != null && lp != null) {
                    cb.onMoved(lp.x, lp.y);
                }
            }
        });
        settleAnim.start();
    }

    private void cancelAnim() {
        if (settleAnim != null) {
            try {
                settleAnim.cancel();
            } catch (Exception ignored) {
            }
            settleAnim = null;
        }
    }

    // ---------- 闲置变淡 ----------

    private void scheduleIdle() {
        h.removeCallbacks(idleTask);
        h.postDelayed(idleTask, IDLE_MS);
    }

    /** 按住不动够久了 —— 判定为"要放大" */
    private final Runnable holdTask = new Runnable() {
        @Override
        public void run() {
            if (state != ST_PENDING || view == null || lp == null) {
                return;
            }
            state = ST_ZOOMING;
            Log.gesture("按住够久 → 判定为放大");
            if (cb != null) {
                // 先让上层把朗读停掉：放大就只干放大，不交叉
                cb.onZoomStart(lastFx, lastFy);
            }
            view.setState(BallView.ST_ACTIVE);
            placeBallAtFinger(lastFx, lastFy);
        }
    };

    private final Runnable idleTask = new Runnable() {
        @Override
        public void run() {
            if (view == null || state != ST_IDLE) {
                return;
            }
            faded = true;
            view.setBallAlpha(prefs.idleAlpha());
        }
    };

    private static String stName(int s) {
        switch (s) {
            case ST_IDLE:
                return "待命";
            case ST_PENDING:
                return "待判定";
            case ST_DRAG_BALL:
                return "拖球";
            case ST_ZOOMING:
                return "放大中";
            default:
                return String.valueOf(s);
        }
    }

    private boolean overSlop(float dx, float dy) {
        int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        return dx * dx + dy * dy > (float) slop * slop;
    }

    private int clampX(int x, int size) {
        int min = (int) (-size * 0.5f);
        int max = screenW - size / 2;
        return Math.max(min, Math.min(max, x));
    }

    private int clampY(int y, int size) {
        return Math.max(0, Math.min(screenH - size, y));
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
