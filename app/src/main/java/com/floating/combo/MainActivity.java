package com.floating.combo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面。三个 Tab：朗读 / 放大 / 球。
 *
 * 全部用 Java 写布局，不用 XML —— AIDE 上省事，也不会有资源引用问题。
 */
public class MainActivity extends Activity
        implements TtsManager.OnInitListener {

    private Prefs prefs;

    private Button btnOn;
    private Button bAcc;
    private TextView tvStatus;

    private LinearLayout tabBar;
    private final Button[] tabs = new Button[3];
    private LinearLayout body;
    private final LinearLayout[] pages = new LinearLayout[3];
    private int curTab = 0;
    /** 跨重建记住当前 Tab，rebuild()（finish+startActivity）后恢复，不踢回第一页 */
    private static int savedTab = 0;

    // 朗读页
    private Spinner spEngine, spVoice;
    private TextView tvRate, tvPitch, tvEngineInfo;
    private List<TtsManager.Engine> engines = new ArrayList<TtsManager.Engine>();
    private List<TtsManager.Voice> voices = new ArrayList<TtsManager.Voice>();
    /** 界面上当前显示的是哪个引擎 / 音色，用来识别"程序设置"引起的选择回调 */
    private String shownEngine = null;
    private String shownVoice = null;

    // 放大页
    private TextView tvScale, tvHold;

    // 球页
    private TextView tvSize, tvAlpha, tvIdle;
    private Button btnSnap, btnComp, btnHaptic;
    private int colorIndex = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        colorIndex = prefs.colorIndex();

        Log.init(getApplicationContext());
        Haptics.init(getApplicationContext(), prefs.haptics());
        Log.i("ui", "主界面打开");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Theme.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, dp(24));
        sv.addView(root);

        root.addView(brandHeader(), lp(0, 0, 0, dp(12)));
        root.addView(cardTop(), lp(0, 0, 0, dp(12)));
        root.addView(tabBar(), lp(0, 0, 0, dp(10)));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        pages[0] = pageRead();
        pages[1] = pageZoom();
        pages[2] = pageBall();
        for (int i = 0; i < pages.length; i++) {
            body.addView(pages[i]);
        }

        switchTab(savedTab);
        setContentView(sv);

        TtsManager.setListener(this);
        TtsManager.init(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        reloadEngines();
    }

    @Override
    protected void onDestroy() {
        TtsManager.setListener(null);
        super.onDestroy();
    }

    // ---------- 顶部卡 ----------

    private View cardTop() {
        LinearLayout c = card();

        btnOn = primaryBtn();
        btnOn.setTextSize(16);
        btnOn.setAllCaps(false);
        btnOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 无障碍没开的话，改开关毫无意义 —— 直接带去开
                if (!accOn()) {
                    toast("先去开启无障碍服务，球才有地方挂");
                    openAccSettings();
                    return;
                }
                boolean on = !prefs.ballOn();
                prefs.setBallOn(on);
                ComboService.refresh();
                // 服务可能刚被系统拉起来，稍等再刷一次状态
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                    }
                }, 400);
                refreshStatus();
            }
        });
        c.addView(btnOn, lp(0, 0, 0, dp(10)));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setLineSpacing(dp(2), 1f);
        c.addView(tvStatus);

        bAcc = flatBtn("去开启无障碍服务");
        bAcc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccSettings();
            }
        });
        c.addView(bAcc, lp(0, dp(10), 0, 0));

        return c;
    }

    private void openAccSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("打不开设置页，请手动去 设置→无障碍 里开启");
        }
    }

    private void refreshStatus() {
        boolean on = prefs.ballOn();
        Log.i("ui", "状态刷新 开关=" + on + " 无障碍=" + accOn()
                + " 服务=" + ComboService.isRunning());
        btnOn.setText(on ? "关闭悬浮球" : "开启悬浮球");

        boolean acc = accOn();
        StringBuilder sb = new StringBuilder();
        sb.append(acc ? "● 无障碍已开启" : "○ 无障碍未开启").append("\n");
        if (acc) {
            sb.append(ComboService.stateText());
        } else {
            sb.append("○ 球未显示（要先开无障碍）");
        }
        sb.append("\n● 点球选字朗读　● 按住球放大");
        tvStatus.setText(sb.toString());
        tvStatus.setTextColor(Theme.TEXT_2);

        // 开启/关闭按钮随状态换肤：关时是品牌渐变"开启"，开时是描边"关闭"
        if (on) {
            btnOn.setBackgroundDrawable(Theme.rounded(Theme.SURFACE_2, dp(12), Theme.DANGER, 1));
            btnOn.setTextColor(Theme.DANGER);
        } else {
            btnOn.setBackgroundDrawable(Theme.brandGradientPill(dp(12)));
            btnOn.setTextColor(Color.WHITE);
        }

        // 无障碍开着就把这个按钮收起来，别占地方
        bAcc.setVisibility(acc ? View.GONE : View.VISIBLE);
    }

    /** 粗略判断：本包名的服务有没有出现在已启用列表里 */
    private boolean accOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) {
                return false;
            }
            return s.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Tab ----------

    private View tabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundDrawable(Theme.rounded(Theme.SURFACE, dp(14), Theme.BORDER, 1));
        int pad = dp(5);
        tabBar.setPadding(pad, pad, pad, pad);

        String[] names = new String[]{"朗读", "放大", "球"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setBackgroundDrawable(Theme.rounded(Theme.TRANSPARENT, dp(10), 0, 0));
            LinearLayout.LayoutParams l =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            l.setMargins(dp(4), 0, dp(4), 0);
            b.setLayoutParams(l);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(idx);
                }
            });
            tabs[i] = b;
            tabBar.addView(b);
        }
        return tabBar;
    }

    private void switchTab(int idx) {
        curTab = idx;
        savedTab = idx;
        body.removeAllViews();
        body.addView(pages[idx]);
        for (int i = 0; i < tabs.length; i++) {
            boolean active = (i == idx);
            tabs[i].setTextColor(active ? Color.WHITE : Theme.TEXT_2);
            tabs[i].setTextSize(active ? 15 : 14);
            tabs[i].setBackgroundDrawable(active
                    ? Theme.brandGradientPill(dp(10))
                    : Theme.rounded(Theme.TRANSPARENT, dp(10), 0, 0));
        }
    }

    // ---------- 朗读页 ----------

    private LinearLayout pageRead() {
        LinearLayout c = card();

        tvEngineInfo = new TextView(this);
        tvEngineInfo.setTextSize(12);
        tvEngineInfo.setTextColor(Theme.TEXT_2);
        c.addView(tvEngineInfo, lp(0, 0, 0, dp(10)));

        spEngine = new Spinner(this);
        spEngine.setBackgroundDrawable(Theme.rounded(Theme.SURFACE_2, dp(10), Theme.BORDER, 1));
        c.addView(rowText("语音引擎", ""), lp(0, 0, 0, dp(4)));
        c.addView(spEngine, lp(0, 0, 0, dp(10)));

        spVoice = new Spinner(this);
        spVoice.setBackgroundDrawable(Theme.rounded(Theme.SURFACE_2, dp(10), Theme.BORDER, 1));
        c.addView(rowText("音色", ""), lp(0, 0, 0, dp(4)));
        c.addView(spVoice, lp(0, 0, 0, dp(10)));

        tvRate = new TextView(this);
        c.addView(sliderRow("语速", tvRate, 50, 200, (int) (prefs.rate() * 100),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setRate(v / 100f);
                    }
                }), lp(0, 0, 0, dp(4)));

        Button bRate = flatBtn("语速默认 1.00");
        bRate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setRate(1.0f);
                rebuild();
                toast("语速已恢复 1.00");
            }
        });
        c.addView(bRate, lp(0, 0, 0, dp(10)));

        tvPitch = new TextView(this);
        c.addView(sliderRow("音调", tvPitch, 50, 200, (int) (prefs.pitch() * 100),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setPitch(v / 100f);
                    }
                }), lp(0, 0, 0, dp(4)));

        Button bPitch = flatBtn("音调默认 1.00");
        bPitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setPitch(1.0f);
                rebuild();
                toast("音调已恢复 1.00");
            }
        });
        c.addView(bPitch, lp(0, 0, 0, dp(12)));

        Button bTry = flatBtn("试听一句");
        bTry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!TtsManager.isReady()) {
                    toast("引擎还没准备好，等一两秒");
                    return;
                }
                TtsManager.speak("这是一句试听，声音正常就可以用了", prefs.rate(), prefs.pitch());
            }
        });
        c.addView(bTry, lp(0, 0, 0, dp(10)));

        Button bStop = flatBtn("停止");
        bStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TtsManager.stop();
            }
        });
        c.addView(bStop, lp(0, 0, 0, 0));

        return c;
    }

    private void reloadEngines() {
        engines = TtsManager.enumerate(this);
        List<String> names = new ArrayList<String>();
        int sel = 0;
        String saved = TtsManager.getSavedEngine(this);
        for (int i = 0; i < engines.size(); i++) {
            TtsManager.Engine e = engines.get(i);
            names.add(e.name);
            if (saved != null && saved.length() > 0 && saved.equals(e.pkg)) {
                sel = i;
            }
        }
        if (names.isEmpty()) {
            names.add("（没检测到引擎）");
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spEngine.setAdapter(ad);
        spEngine.setSelection(Math.min(sel, names.size() - 1));
        // 界面上现在代表哪个引擎。setAdapter/setSelection 会触发一次回调，
        // 那不是"用户换了引擎" —— 不区分的话，每次打开 App 都会把引擎
        // 切成列表第一项并重连一次，正在进行的朗读会被当场打断。
        shownEngine = (engines.isEmpty())
                ? null : engines.get(Math.min(sel, engines.size() - 1)).pkg;
        spEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (engines == null || pos >= engines.size()) {
                    return;
                }
                String pkg = engines.get(pos).pkg;
                if (pkg.equals(shownEngine)) {
                    return;     // 程序设置引起的，不是用户改的
                }
                shownEngine = pkg;
                if (pkg.equals(TtsManager.getSavedEngine(MainActivity.this))) {
                    return;
                }
                TtsManager.saveEngine(MainActivity.this, pkg);
                TtsManager.rebind(getApplicationContext());
                toast("正在切换引擎…");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        reloadVoices();
    }

    private void reloadVoices() {
        voices = TtsManager.listVoicesZhFirst();
        List<String> labels = new ArrayList<String>();
        int sel = 0;
        String saved = TtsManager.getSavedVoice(this);
        for (int i = 0; i < voices.size(); i++) {
            TtsManager.Voice v = voices.get(i);
            labels.add(v.label);
            if (saved != null && saved.length() > 0 && saved.equals(v.name)) {
                sel = i;
            }
        }
        if (labels.isEmpty()) {
            labels.add("默认音色");
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        spVoice.setAdapter(ad);
        spVoice.setSelection(Math.min(sel, labels.size() - 1));
        // 同上：程序设置引起的回调不是"用户换了音色"
        shownVoice = (voices.isEmpty())
                ? null : voices.get(Math.min(sel, voices.size() - 1)).name;
        spVoice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (voices == null || pos >= voices.size()) {
                    return;
                }
                String name = voices.get(pos).name;
                if (name.equals(shownVoice)) {
                    return;     // 程序设置引起的
                }
                shownVoice = name;
                TtsManager.saveVoice(MainActivity.this, name);
                TtsManager.applyVoice(name);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public void onEngineReady(boolean ready, boolean cnOk, String pkg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                if (TtsManager.isFailed()) {
                    sb.append("引擎初始化失败，换一个试试");
                } else if (!TtsManager.isReady()) {
                    sb.append("引擎初始化中…");
                } else {
                    sb.append("引擎就绪");
                    sb.append(TtsManager.isCnOk() ? " · 中文可用" : " · 中文可能不支持");
                    if (voices.isEmpty()) {
                        reloadVoices();
                    }
                }
                tvEngineInfo.setText(sb.toString());
            }
        });
    }

    // ---------- 放大页 ----------

    private LinearLayout pageZoom() {
        LinearLayout c = card();

        TextView tip = new TextView(this);
        tip.setTextSize(12);
        tip.setTextColor(Theme.TEXT_2);
        tip.setText("按住球不动 → 放大，手指挪动 → 画面跟着走，松手 → 恢复。\n"
                + "放大期间不会朗读，也不会进入选取态。");
        tip.setLineSpacing(dp(2), 1f);
        c.addView(tip, lp(0, 0, 0, dp(12)));

        tvScale = new TextView(this);
        c.addView(sliderRow("放大倍率", tvScale, 15, 50, (int) (prefs.scale() * 10),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setScale(v / 10f);
                    }
                }), lp(0, 0, 0, dp(6)));

        tvHold = new TextView(this);
        c.addView(sliderRow("按住多久算放大", tvHold, 120, 600, prefs.holdMs(),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setHoldMs(v);
                    }
                }), lp(0, 0, 0, dp(12)));

        TextView tip2 = new TextView(this);
        tip2.setTextSize(12);
        tip2.setTextColor(Theme.TEXT_2);
        tip2.setText("上面这个越长，越不容易误触发放大；越短，放大越灵敏。\n"
                + "手快、总是不小心放大的，往长了调。");
        tip2.setLineSpacing(dp(2), 1f);
        c.addView(tip2, lp(0, 0, 0, dp(12)));

        btnComp = flatBtn("");
        updateCompText();
        btnComp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setCompensate(!prefs.compensate());
                updateCompText();
                ComboService.refresh();
            }
        });
        c.addView(btnComp, lp(0, 0, 0, dp(10)));

        Button bZoomReset = flatBtn("放大相关恢复默认");
        bZoomReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setScale(2.5f);
                prefs.setHoldMs(260);
                prefs.setCompensate(true);
                rebuild();
                toast("放大的设置已恢复默认");
            }
        });
        c.addView(bZoomReset, lp(0, 0, 0, 0));

        return c;
    }

    private void updateCompText() {
        btnComp.setText("放大时球不变大：" + (prefs.compensate() ? "开" : "关"));
    }

    // ---------- 球页 ----------

    private LinearLayout pageBall() {
        LinearLayout c = card();

        tvSize = new TextView(this);
        c.addView(sliderRow("球大小", tvSize, 36, 88, prefs.sizeDp(),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setSizeDp(v);
                        ComboService.refresh();
                    }
                }), lp(0, 0, 0, dp(6)));

        tvAlpha = new TextView(this);
        c.addView(sliderRow("不透明度", tvAlpha, 20, 100, (int) (prefs.alpha() * 100),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setAlpha(v / 100f);
                        ComboService.refresh();
                    }
                }), lp(0, 0, 0, dp(6)));

        tvIdle = new TextView(this);
        c.addView(sliderRow("闲置时", tvIdle, 5, 100, (int) (prefs.idleAlpha() * 100),
                new OnVal() {
                    @Override
                    public void on(int v) {
                        prefs.setIdleAlpha(v / 100f);
                    }
                }), lp(0, 0, 0, dp(12)));

        btnSnap = flatBtn("");
        updateSnapText();
        btnSnap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setSnapEdge(!prefs.snapEdge());
                updateSnapText();
            }
        });
        c.addView(btnSnap, lp(0, 0, 0, dp(10)));

        btnHaptic = flatBtn("");
        updateHapticText();
        btnHaptic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setHaptics(!prefs.haptics());
                Haptics.setEnabled(prefs.haptics());
                updateHapticText();
                if (prefs.haptics()) {
                    Haptics.ready();     // 开的时候震一下，让他知道震感是什么样
                }
                ComboService.refresh();
            }
        });
        c.addView(btnHaptic, lp(0, 0, 0, dp(10)));

        TextView t = new TextView(this);
        t.setText("球颜色");
        t.setTextSize(14);
        t.setTextColor(Theme.TEXT);
        c.addView(t, lp(0, 0, 0, dp(8)));

        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Prefs.COLORS.length; i++) {
            final int idx = i;
            View v = new View(this);
            int d = dp(34);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(d, d);
            l.rightMargin = dp(10);
            v.setLayoutParams(l);
            final GradientDrawable g = new GradientDrawable();
            g.setColor(Prefs.COLORS[i]);
            g.setCornerRadius(d / 2f);
            if (i == colorIndex) {
                g.setStroke(dp(3), Theme.ACCENT);
            } else {
                g.setStroke(1, Theme.BORDER);
            }
            v.setBackgroundDrawable(g);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View vv) {
                    colorIndex = idx;
                    prefs.setColorIndex(idx);
                    for (int j = 0; j < colors.getChildCount(); j++) {
                        View cv = colors.getChildAt(j);
                        GradientDrawable cg = (GradientDrawable) cv.getBackground();
                        if (j == idx) {
                            cg.setStroke(dp(3), Theme.ACCENT);
                        } else {
                            cg.setStroke(1, Theme.BORDER);
                        }
                    }
                    ComboService.refresh();
                }
            });
            colors.addView(v);
        }
        c.addView(colors);

        // 恢复默认
        Button bReset = flatBtn("球相关恢复默认");
        bReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setSizeDp(56);
                prefs.setAlpha(0.92f);
                prefs.setIdleAlpha(0.30f);
                prefs.setSnapEdge(true);
                prefs.setColorIndex(0);
                colorIndex = 0;
                rebuild();
                toast("球的设置已恢复默认");
            }
        });
        c.addView(bReset, lp(0, dp(12), 0, dp(10)));

        // 折叠的诊断入口挂在球页底部，平时不占地方
        c.addView(logSection(), lp(0, dp(12), 0, 0));

        return c;
    }

    /** 重画整个界面，把新设置反映出来 */
    private void rebuild() {
        Intent i = getIntent();
        finish();
        startActivity(i);
    }

    private void updateSnapText() {
        btnSnap.setText("松手后自动贴边：" + (prefs.snapEdge() ? "开" : "关"));
    }

    private void updateHapticText() {
        btnHaptic.setText("点按时震一下：" + (prefs.haptics() ? "开" : "关"));
    }

    // ---------- 诊断与日志（折叠，默认只露一个小入口） ----------

    private TextView tvLog;
    private LinearLayout logBody;
    private Button btnLogToggle;
    private boolean logOpen = false;
    private boolean logFull = false;

    /** 折叠入口：默认收起，点开才加载内容 */
    private LinearLayout logSection() {
        LinearLayout c = card();

        btnLogToggle = flatBtn("诊断与日志  ▸");
        btnLogToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logOpen = !logOpen;
                btnLogToggle.setText(logOpen ? "诊断与日志  ▾" : "诊断与日志  ▸");
                logBody.setVisibility(logOpen ? View.VISIBLE : View.GONE);
                if (logOpen) {
                    refreshLog();
                }
            }
        });
        c.addView(btnLogToggle, lp(0, 0, 0, 0));

        logBody = new LinearLayout(this);
        logBody.setOrientation(LinearLayout.VERTICAL);
        logBody.setVisibility(View.GONE);
        c.addView(logBody);

        // 按钮放最上面，不会被日志内容顶到看不见
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button bExport = flatBtn("导出日志");
        LinearLayout.LayoutParams wl =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wl.rightMargin = dp(6);
        bExport.setLayoutParams(wl);
        bExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = Log.export(MainActivity.this);
                if (p == null) {
                    toast("导出失败，可能还没产生日志");
                } else {
                    toast("已导出到：\n" + p);
                }
            }
        });
        row.addView(bExport);

        Button bRefresh = flatBtn("刷新");
        LinearLayout.LayoutParams wl2 =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        wl2.leftMargin = dp(6);
        bRefresh.setLayoutParams(wl2);
        bRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLog();
            }
        });
        row.addView(bRefresh);
        logBody.addView(row, lp(0, dp(10), 0, dp(8)));

        tvLog = new TextView(this);
        tvLog.setTextSize(11);
        tvLog.setTextColor(Theme.TEXT_2);
        tvLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvLog.setSingleLine(false);
        logBody.addView(tvLog, lp(0, 0, 0, dp(8)));

        // 「显示全部 / 只看最近」开关——默认只渲染 8 行，防止撑爆
        final Button bMore = flatBtn("显示全部");
        bMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFull = !logFull;
                bMore.setText(logFull ? "只看最近 8 条" : "显示全部");
                refreshLog();
            }
        });
        logBody.addView(bMore, lp(0, 0, 0, dp(8)));

        Button bClear = flatBtn("清空日志");
        bClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.clear();
                refreshLog();
                toast("已清空");
            }
        });
        logBody.addView(bClear, lp(0, 0, 0, 0));

        return c;
    }

    private void refreshLog() {
        if (tvLog == null) {
            return;
        }
        java.util.ArrayList<String> lines = Log.recent();
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) {
            sb.append("（暂无日志）");
        } else {
            int show = logFull ? Log.MAX_RENDER : 8;
            show = Math.min(show, lines.size());
            // 倒着渲染，最新的在最上面
            for (int k = 0; k < show; k++) {
                sb.append(lines.get(lines.size() - 1 - k));
                if (k < show - 1) {
                    sb.append("\n");
                }
            }
            if (show < lines.size()) {
                sb.append("\n… 共 ").append(lines.size()).append(" 条，仅显示最近 ")
                        .append(show).append(" 条");
            }
        }
        sb.append("\n\n只记手势、状态、错误，不记你读的内容。");
        tvLog.setText(sb.toString());
    }

    // ---------- 通用件 ----------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundDrawable(Theme.rounded(Theme.SURFACE, dp(16), Theme.BORDER, 1));
        int p = dp(16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private View rowText(String name, String val) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView a = new TextView(this);
        a.setText(name);
        a.setTextSize(14);
        a.setTextColor(Theme.TEXT);
        r.addView(a);
        return r;
    }

    private Button flatBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackgroundDrawable(Theme.rounded(Theme.SURFACE_2, dp(10), Theme.BORDER, 1));
        b.setTextColor(Theme.TEXT);
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        return b;
    }

    /** 顶部品牌横幅（纯装饰，不参与任何功能逻辑）。 */
    private View brandHeader() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundDrawable(Theme.brandGradientPill(dp(18)));
        c.setPadding(dp(18), dp(20), dp(18), dp(20));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("看得清");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(title);

        TextView badge = new TextView(this);
        badge.setText(" 2.1 ");
        badge.setTextSize(13);
        badge.setTextColor(Color.WHITE);
        badge.setBackgroundDrawable(Theme.rounded(0x33FFFFFF, dp(10), 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        badge.setLayoutParams(blp);
        row.addView(badge);

        c.addView(row);

        TextView sub = new TextView(this);
        sub.setText("点字即读 · 按住放大 · 拖动挪位");
        sub.setTextSize(13);
        sub.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        sub.setLayoutParams(slp);
        c.addView(sub);

        return c;
    }

    /** 主操作按钮：品牌渐变 + 白字，用于"开启/关闭悬浮球"这种关键动作。 */
    private Button primaryBtn() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setBackgroundDrawable(Theme.brandGradientPill(dp(12)));
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private interface OnVal {
        void on(int v);
    }

    /** 一行：标题 + 当前值 + 滑杆 */
    private LinearLayout sliderRow(String name, final TextView val,
                                   int min, int max, int cur, final OnVal cb) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(14);
        t.setTextColor(Theme.TEXT);
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        val.setTextSize(13);
        val.setTextColor(Theme.ACCENT);
        val.setGravity(Gravity.RIGHT);
        head.addView(val);
        r.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int p = Math.max(0, Math.min(max - min, cur - min));
        sb.setProgress(p);
        try {
            sb.getProgressDrawable().setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN);
            if (sb.getThumb() != null) {
                sb.getThumb().setColorFilter(Theme.ACCENT, PorterDuff.Mode.SRC_IN);
            }
        } catch (Exception ignored) {
        }
        val.setText(fmt(name, cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                int v = prog + min;
                val.setText(fmt(name, v));
                if (fromUser) {
                    cb.on(v);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        r.addView(sb);
        return r;
    }

    private String fmt(String name, int v) {
        if ("放大倍率".equals(name)) {
            return String.format("%.1fx", v / 10f);
        }
        if ("按住多久算放大".equals(name)) {
            return v + " 毫秒";
        }
        if ("球大小".equals(name)) {
            return v + " dp";
        }
        if ("语速".equals(name) || "音调".equals(name)) {
            return String.format("%.2f", v / 100f);
        }
        return v + "%";
    }

    private void toast(String m) {
        try {
            Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean isEmpty(CharSequence c) {
        return TextUtils.isEmpty(c);
    }
}
