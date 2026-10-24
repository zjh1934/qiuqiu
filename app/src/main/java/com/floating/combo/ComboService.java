package com.floating.combo;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * 唯一的服务：画球 + 抓字 + 控制放大。
 *
 * ── 核心原则：朗读和放大不交叉 ──
 *   放大期间就是放大，不存在选取态，也不朗读。
 *   一进放大，立刻撤掉覆盖层、停掉朗读。
 *   反过来，选取态/朗读中一旦按住要放大，先收干净再放大。
 *
 * 手势：
 *   轻点球     → 进入选取态（绿）→ 点屏幕上的字 → 从那句开始读
 *   朗读中点球 → 停止
 *   按住球     → 放大，手指挪画面跟着走，松手恢复
 *   拖动球     → 挪球
 */
public class ComboService extends AccessibilityService
        implements BallController.Callback {

    private static final int ST_IDLE = 0;
    private static final int ST_PICKING = 1;
    private static final int ST_SPEAKING = 2;

    private static ComboService instance = null;

    private Prefs prefs;
    private BallController ball;
    private ZoomController zoom;
    private TextGrabber grabber;

    private WindowManager wm;
    private View overlay = null;
    private WindowManager.LayoutParams overlayLp = null;
    private boolean overlayAdded = false;

    private final Handler h = new Handler(Looper.getMainLooper());
    private int state = ST_IDLE;
    private boolean zooming = false;

    /**
     * 选取态的「代号」。每次状态一变就 +1，用来作废还在排队的「延迟抓字」任务。
     *
     * 覆盖层抬手后会延迟 250ms 再抓字（等窗口恢复常态）。这 250ms 里用户完全
     * 可能又点了球：那一下要么是取消选取、要么是要放大。作废掉排队任务，
     * 否则就出现「点了取消它还在读」。
     */
    private int pickSeq = 0;

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * 球现在真的挂在屏幕上吗。
     * 界面上的"开启/关闭"按钮按它来显示，而不是按设置项 ——
     * 设置是"想不想开"，这里是"实际开没开"，两者经常不一致。
     */
    public static boolean ballShown() {
        return instance != null && instance.ball != null && instance.ball.isShown();
    }

    public static void refresh() {
        if (instance != null) {
            instance.doRefresh();
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.init(getApplicationContext());
        Log.i("svc", "无障碍服务已连接");
        initOnce();

        TtsManager.init(getApplicationContext());
        Haptics.init(getApplicationContext(), prefs.haptics());
        TtsManager.setSpeechListener(new TtsManager.SpeechListener() {
            @Override
            public void onDone() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state == ST_SPEAKING) {
                            toIdle();
                        }
                    }
                });
            }

            @Override
            public void onError() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (state == ST_SPEAKING) {
                            toIdle();
                        }
                    }
                });
            }
        });

        startFg();
        if (prefs.ballOn()) {
            Log.i("svc", "开关是开的，准备显示球");
            ball.show();
            if (ball.lastError() != null) {
                Log.e("svc", "球没出来：" + ball.lastError());
            } else if (ball.isShown()) {
                Log.i("svc", "球已显示");
            }
        } else {
            Log.i("svc", "开关是关的，不显示球");
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private void doRefresh() {
        if (prefs == null) {
            return;
        }
        Haptics.setEnabled(prefs.haptics());
        if (!prefs.ballOn()) {
            closeAll();
            return;
        }
        if (ball == null) {
            // 服务没真正连上时不能自己调 onServiceConnected()，
            // 那是系统回调。这里只做安全初始化。
            initOnce();
            if (ball == null) {
                return;
            }
        }
        ball.show();
        if (ball.lastError() != null) {
            // 加窗口失败，把原因吐出来，别让用户干瞪眼
            toast("球显示失败：" + ball.lastError());
            return;
        }
        ball.applyPrefs();
    }

    /**
     * 从 onServiceConnected 里拆出来，供重复调用。
     * 只在 ball 还没建时真正执行。
     */
    private void initOnce() {
        if (prefs == null) {
            prefs = new Prefs(this);
        }
        if (wm == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (zoom == null) {
            zoom = new ZoomController(this, prefs);
        }
        if (grabber == null) {
            grabber = new TextGrabber(this);
        }
        if (ball == null) {
            ball = new BallController(this, prefs, zoom, this);
            zoom.setScreen(ball.screenW(), ball.screenH());
        }
    }

    /** 给界面显示当前到底卡在哪一步 */
    public static String stateText() {
        if (instance == null) {
            return "服务未运行（去系统设置开启无障碍）";
        }
        ComboService s = instance;
        if (s.ball == null) {
            return "服务已运行，但球未初始化";
        }
        if (s.ball.lastError() != null) {
            return "球显示失败：" + s.ball.lastError();
        }
        if (!s.ball.isShown()) {
            return "球未显示" + (s.prefs != null && s.prefs.ballOn() ? "" : "（开关是关的）");
        }
        return "球已显示";
    }

    private void closeAll() {
        stopSpeakingInternal();
        pickSeq++;
        removeOverlay();
        if (zoom != null) {
            zoom.zoomOut();
        }
        zooming = false;
        state = ST_IDLE;
        if (ball != null) {
            ball.hide();
        }
    }

    // ---------- 手势回调 ----------

    /**
     * 按住够久了 —— 要放大。
     * 关键：先把朗读/选取全部收干净，放大期间不干别的。
     */
    @Override
    public void onZoomStart(float fx, float fy) {
        Log.state(stName(state), stName(ST_IDLE), "要放大，先清干净");
        stopSpeakingInternal();
        pickSeq++;      // 作废可能还在排队的抓字任务
        removeOverlay();
        state = ST_IDLE;
        zooming = true;
        Log.i("zoom", "放大开始 (" + (int) fx + "," + (int) fy + ") 倍率=" + prefs.scale());

        if (zoom != null) {
            zoom.zoomInAt(fx, fy);
        }
        if (ball != null) {
            ball.setState(BallView.ST_ACTIVE);
        }
    }

    @Override
    public void onZoomMove(float fx, float fy) {
        if (zoom != null) {
            zoom.panTo(fx, fy);
        }
    }

    @Override
    public void onZoomEnd() {
        Log.i("zoom", "放大结束，球退回原位");
        zooming = false;
        if (zoom != null) {
            zoom.zoomOut();
        }
        if (ball != null) {
            ball.resetLook();
        }
    }

    /**
     * 轻点球。三态循环：
     *   待命 → 选取态 →（点屏幕上的字）朗读中 →（点球）停止回待命
     *   选取态再点球 = 取消
     */
    @Override
    public void onTap() {
        if (zooming) {
            return;   // 放大中不响应轻点
        }
        // 点球本身就是在改状态，先作废排队中的抓字任务
        pickSeq++;
        if (state == ST_SPEAKING) {
            Log.i("tts", "朗读中轻点球 → 停止");
            stopSpeaking();
            toast("已停止");
            return;
        }
        if (state == ST_PICKING) {
            Log.i("pick", "选取态再点球 → 取消");
            Haptics.tap();
            removeOverlay();
            toIdle();
            return;
        }
        if (!TtsManager.isReady()) {
            String why = TtsManager.isFailed() ? "初始化失败" : "还在初始化";
            Log.w("tts", "想进选取态但引擎不可用：" + why
                    + " pkg=" + TtsManager.getCurrentPkg());
            if (TtsManager.isFailed()) {
                // 失败之后原来只能重启 App，这里给一次自愈的机会
                TtsManager.retryIfFailed();
                toast("语音引擎初始化失败，正在重新连接…");
            } else {
                toast("语音引擎还在初始化，等一两秒");
            }
            Haptics.error();
            return;
        }
        Log.state(stName(state), stName(ST_PICKING), "轻点球");
        state = ST_PICKING;
        if (ball != null) {
            ball.setState(BallView.ST_PICKING);
        }
        addOverlay();
        Haptics.ready();
    }

    @Override
    public void onMoved(int x, int y) {
        if (prefs != null) {
            prefs.setBallPos(x, y);
        }
    }

    // ---------- 朗读 ----------

    private void toIdle() {
        if (state != ST_IDLE) {
            Log.state(stName(state), stName(ST_IDLE), "toIdle");
        }
        h.removeCallbacks(speakWatchdog);
        state = ST_IDLE;
        if (ball != null) {
            ball.resetLook();
        }
    }

    /**
     * 朗读看门狗。
     *
     * 球的"两竖"= 朗读中。这个状态只有引擎回调 onDone 才会退出。
     * 但引擎是会失约的：被系统冻结、语音数据没装、第三方引擎吞掉回调……
     * 一旦不回调，球就永远停在"两竖"，用户点它也没用（点了还是在朗读态里打转）。
     * 所以给每次朗读按字数配一个超时，到点强制归位。
     *
     * 估计得宽一点：宁可晚几十秒，也不能把正在读的长文章截断。
     */
    private final Runnable speakWatchdog = new Runnable() {
        @Override
        public void run() {
            if (state != ST_SPEAKING) {
                return;
            }
            Log.w("tts", "朗读超时没有回调，强制回到待命");
            TtsManager.stop();
            toIdle();
        }
    };

    /** 按字数和语速估算"这句最多该读多久" */
    private int guardMs(int chars) {
        float rate = prefs.rate();
        if (rate < 0.1f) {
            rate = 1f;
        }
        long ms = (long) (chars * 400L / rate) + 8000L;
        if (ms > 10L * 60L * 1000L) {
            ms = 10L * 60L * 1000L;
        }
        return (int) ms;
    }

    /** 只读内部状态归位，不碰引擎 */
    private void stopSpeakingInternal() {
        TtsManager.stop();
        state = ST_IDLE;
    }

    /**
     * 停止朗读。
     *
     * 兜底重建（forceStop）只在"确实还在响"时才做，
     * 而且要等久一点 —— 重建引擎期间 isReady() 会是 false，
     * 用户这时点球会被告知"引擎还在初始化"，
     * 感觉就是"切换不丝滑"。所以能不重建就不重建。
     */
    private void stopSpeaking() {
        TtsManager.stop();
        toIdle();
        Haptics.tap();
        h.removeCallbacks(forceStopTask);
        h.postDelayed(forceStopTask, 1500);
    }

    /** 球当前该是什么外观，重建或提到上层后用来还原 */
    private int ballStateForCurrent() {
        switch (state) {
            case ST_PICKING:
                return BallView.ST_PICKING;
            case ST_SPEAKING:
                return BallView.ST_SPEAKING;
            default:
                return BallView.ST_NORMAL;
        }
    }

    private final Runnable forceStopTask = new Runnable() {
        @Override
        public void run() {
            TtsManager.forceStop();
        }
    };

    private void readAt(int x, int y) {
        if (grabber == null) {
            return;
        }
        if (!TtsManager.isReady()) {
            toast("语音引擎还没准备好");
            Haptics.error();
            toIdle();
            return;
        }
        // 上一次"停止"排队的兜底重建会在 1.5 秒后跑：如果这中间用户又点了一句，
        // 它会把刚开始的朗读直接掐掉（release + bind），听着就是"读两个字就没声了"。
        // 新的一次朗读开始，就把那个兜底撤掉。
        h.removeCallbacks(forceStopTask);
        // grabFrom 内部已经兜过 grabAt，这里不用再来一遍
        String text = grabber.grabFrom(x, y);
        if (text == null || text.trim().length() == 0) {
            Log.w("grab", "(" + x + "," + y + ") 什么都没抓到");
            toast("这里没读到文字");
            Haptics.error();
            toIdle();
            return;
        }
        Log.i("grab", "抓到 " + text.length() + " 字，开头：「"
                + (text.length() > 20 ? text.substring(0, 20) : text) + "」");
        boolean ok = TtsManager.speak(text, prefs.rate(), prefs.pitch());
        if (!ok) {
            toast("朗读失败");
            Haptics.error();
            toIdle();
            return;
        }
        Log.state(stName(state), stName(ST_SPEAKING), "开始朗读 " + text.length() + " 字");
        state = ST_SPEAKING;
        if (ball != null) {
            ball.setState(BallView.ST_SPEAKING);
        }
        Haptics.speak();
        // 引擎万一不回调，也要能从"两竖"里退出来
        h.removeCallbacks(speakWatchdog);
        h.postDelayed(speakWatchdog, guardMs(text.length()));
    }

    // ---------- 选取态覆盖层 ----------

    private void createOverlay() {
        if (overlay != null) {
            return;
        }
        overlay = new View(this);
        overlay.setBackgroundColor(Color.parseColor("#00000000"));

        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSPARENT);
        overlayLp.gravity = Gravity.TOP | Gravity.LEFT;
        overlayLp.x = 0;
        overlayLp.y = 0;

        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    final int x = (int) e.getRawX();
                    final int y = (int) e.getRawY();
                    removeOverlay();
                    final int seq = pickSeq;
                    // 等覆盖层撤掉、窗口恢复常态再抓，否则可能抓到自己的覆盖层
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // 这 250ms 里球可能又被点了（取消选取 / 要放大），
                            // 那这次屏幕点击就作废，别再读出来
                            if (seq != pickSeq || state != ST_PICKING) {
                                Log.i("pick", "点击已作废（期间状态变了），跳过抓字");
                                return;
                            }
                            readAt(x, y);
                        }
                    }, 250);
                }
                return true;
            }
        });
    }

    private void addOverlay() {
        if (overlayAdded || wm == null) {
            return;
        }
        createOverlay();
        try {
            wm.addView(overlay, overlayLp);
            overlayAdded = true;
            // 覆盖层是后加的，会盖住球。把球重新挂一次提到上层。
            // 注意：不能走 hide()+show()，那会重建 BallView，
            // 把刚设的"选取态绿色"冲掉 —— 用户点了球却看不出变化，
            // 就会以为"点了没反应"。
            if (ball != null) {
                ball.bringToFront();
                ball.reapplyState(ballStateForCurrent());
            }
            Log.i("pick", "覆盖层已加，球保持选取态在最上层");
        } catch (Exception e) {
            Log.e("pick", "覆盖层加不上", e);
            toIdle();
        }
    }

    private void removeOverlay() {
        if (overlayAdded && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlayAdded = false;
        }
    }

    // ---------- 保活通知 ----------

    private void startFg() {
        try {
            Intent ni = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, ni,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            ? PendingIntent.FLAG_IMMUTABLE : 0);
            Notification n;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String id = "combo_ball";
                NotificationChannel ch = new NotificationChannel(
                        id, "看得清", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
                n = new Notification.Builder(this, id)
                        .setContentTitle("看得清运行中")
                        .setContentText("点球选字朗读 · 按住放大")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentIntent(pi)
                        .build();
            } else {
                n = new Notification.Builder(this)
                        .setContentTitle("看得清运行中")
                        .setContentText("点球选字朗读 · 按住放大")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setContentIntent(pi)
                        .build();
            }
            startForeground(1, n);
        } catch (Exception ignored) {
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** 状态名，日志里用。静态方法不能加 @Override */
    private static String stName(int s) {
        switch (s) {
            case ST_IDLE:
                return "待命";
            case ST_PICKING:
                return "选取态";
            case ST_SPEAKING:
                return "朗读中";
            default:
                return String.valueOf(s);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        closeAll();
        TtsManager.release();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        closeAll();
        TtsManager.release();
        instance = null;
        super.onDestroy();
    }
}
