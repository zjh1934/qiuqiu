package com.floating.combo;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TTS 引擎管理。全进程只有一个 TextToSpeech 实例。
 * 切换引擎 = stop + shutdown + 重新绑定，保证彻底换掉。
 *
 * 语速音调这里直接设进去（系统 TTS 那套设置界面不是每个引擎都有）。
 */
public class TtsManager {

    public static class Engine {
        public String name;
        public String pkg;

        public Engine(String name, String pkg) {
            this.name = name;
            this.pkg = pkg;
        }
    }

    public static class Voice {
        public String name;
        public String label;

        public Voice(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    private static final String PREF = "cfg";
    private static final String KEY_ENGINE = "engine_pkg";
    private static final String KEY_VOICE = "engine_voice";
    /**
     * 一次交给引擎的最大字数。
     * 官方上限 TextToSpeech.getMaxSpeechInputLength() 是 4000，
     * 超了 speak() 直接返回 ERROR —— 一个字都不念。留 400 的余量。
     */
    private static final int MAX_CHUNK = 3600;

    private static TextToSpeech tts = null;
    /** 每次重建 +1，用来丢弃旧实例的回调 */
    private static int serial = 0;
    private static boolean ready = false;
    private static boolean failed = false;
    private static boolean cnOk = false;
    private static String currentPkg = "";
    private static Context appCtx = null;
    private static OnInitListener listener = null;
    private static SpeechListener speechListener = null;

    /**
     * 当前朗读会话号。speak() / stop() / release() 都会 +1。
     *
     * 长文本被切成几段排队念，引擎逐段回调；被 stop() 打断的旧会话也会回调。
     * 靠这个号把"不是这一次朗读"的回调全部丢掉 ——
     * 否则会出现"刚点了一句，读两个字球就跳回待命"。
     */
    private static int session = 0;
    /** 本次会话一共几段。回调里用它判断"是不是最后一段"，不靠计数器累加 */
    private static int chunkTotal = 0;
    /** 上次因初始化失败而重试的时间，避免点一下就重连一次 */
    private static long lastRetry = 0;

    public interface OnInitListener {
        void onEngineReady(boolean ready, boolean cnOk, String pkg);
    }

    /** 朗读结束 / 出错的回调 */
    public interface SpeechListener {
        void onDone();

        void onError();
    }

    public static void setListener(OnInitListener l) {
        listener = l;
    }

    public static void setSpeechListener(SpeechListener l) {
        speechListener = l;
    }

    private static void notifyListener() {
        if (listener != null) {
            try {
                listener.onEngineReady(ready, cnOk, currentPkg);
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isFailed() {
        return failed;
    }

    public static boolean isCnOk() {
        return cnOk;
    }

    public static String getCurrentPkg() {
        return currentPkg;
    }

    /**
     * 枚举引擎。两条路都走，结果合并：
     *   ① PackageManager 查 TTS_SERVICE（需要 manifest 里声明 <queries>）
     *   ② 建实例问 getEngines()（部分引擎要注册后才出现）
     */
    public static List<Engine> enumerate(Context ctx) {
        List<Engine> out = new ArrayList<Engine>();
        try {
            Intent intent = new Intent("android.intent.action.TTS_SERVICE");
            PackageManager pm = ctx.getPackageManager();
            int flag = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag = PackageManager.MATCH_ALL;
            }
            List<ResolveInfo> list = pm.queryIntentServices(intent, flag);
        int n = (list == null) ? 0 : list.size();
        Log.i("tts", "包查询 TTS_SERVICE 命中 " + n + " 个"
                + (n == 0 ? " ← 若只有 0 个，多半是 <queries> 没生效或没装第三方引擎" : ""));
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.serviceInfo == null) {
                        continue;
                    }
                    String pkg = ri.serviceInfo.packageName;
                    if (pkg == null || pkg.length() == 0 || containsPkg(out, pkg)) {
                        continue;
                    }
                    String label;
                    try {
                        label = String.valueOf(ri.loadLabel(pm));
                    } catch (Exception e) {
                        label = pkg;
                    }
                    out.add(new Engine(label, pkg));
                }
            }
        } catch (Exception ignored) {
        }

        // 兜底：实例查询
        try {
            TextToSpeech probe = new TextToSpeech(ctx.getApplicationContext(), null);
            List<TextToSpeech.EngineInfo> es = probe.getEngines();
            Log.i("tts", "getEngines() 命中 " + (es == null ? 0 : es.size()) + " 个");
            if (es != null) {
                for (TextToSpeech.EngineInfo e : es) {
                    if (e == null || e.name == null || containsPkg(out, e.name)) {
                        continue;
                    }
                    String label = e.label;
                    if (label == null || label.isEmpty()) {
                        label = e.name;
                    }
                    out.add(new Engine(label, e.name));
                }
            }
            probe.shutdown();
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean containsPkg(List<Engine> list, String pkg) {
        for (Engine e : list) {
            if (e.pkg != null && e.pkg.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列音色，中文排前面。
     *
     * 优先用系统的 getVoices()（API 21+），拿到的是真实音色名，
     * 比如 zh-CN-XiaoxiaoNeural —— MultiTTS 那类就是靠这个区分发音人的。
     * 拿不到就退回列语言。
     */
    public static List<Voice> listVoicesZhFirst() {
        List<Voice> out = new ArrayList<Voice>();
        if (tts == null || !ready) {
            return out;
        }
        List<Voice> zh = new ArrayList<Voice>();
        List<Voice> other = new ArrayList<Voice>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null && !vs.isEmpty()) {
                    for (android.speech.tts.Voice v : vs) {
                        if (v == null || v.getName() == null) {
                            continue;
                        }
                        Locale l = null;
                        try {
                            l = v.getLocale();
                        } catch (Exception ignored) {
                        }
                        String tag = v.getName();
                        String label = tag;
                        if (l != null) {
                            label = tag + "　" + l.getDisplayName(Locale.SIMPLIFIED_CHINESE);
                        }
                        Voice item = new Voice(tag, label);
                        if (l != null && "zh".equals(l.getLanguage())) {
                            zh.add(item);
                        } else {
                            other.add(item);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (zh.isEmpty() && other.isEmpty()) {
            try {
                java.util.Set<Locale> ls = tts.getAvailableLanguages();
                if (ls != null) {
                    for (Locale l : ls) {
                        Voice v = new Voice(l.toLanguageTag(),
                                l.getDisplayName(Locale.SIMPLIFIED_CHINESE));
                        if ("zh".equals(l.getLanguage())) {
                            zh.add(v);
                        } else {
                            other.add(v);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        out.addAll(zh);
        out.addAll(other);
        return out;
    }

    /** 按名字设音色。名字为空就只设中文语言 */
    public static boolean applyVoice(String name) {
        if (tts == null || !ready) {
            return false;
        }
        try {
            if (name != null && name.length() > 0
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null) {
                    for (android.speech.tts.Voice v : vs) {
                        if (v != null && name.equals(v.getName())) {
                            int r = tts.setVoice(v);
                            if (r == TextToSpeech.SUCCESS) {
                                return true;
                            }
                        }
                    }
                }
            }
            // 名字对不上就退回设语言
            Locale l = Locale.CHINA;
            if (name != null && name.length() > 0) {
                try {
                    l = Locale.forLanguageTag(name);
                } catch (Exception ignored) {
                    l = Locale.CHINA;
                }
            }
            tts.setLanguage(l);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 引擎刚就绪 / 刚重建时，把用户存的音色套回去 */
    public static void applySavedVoice() {
        if (appCtx == null) {
            return;
        }
        applyVoice(getSavedVoice(appCtx));
    }

    public static void saveEngine(Context ctx, String pkg) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_ENGINE, pkg).apply();
        } catch (Exception ignored) {
        }
    }

    /** 返回 "" 表示用系统默认 */
    public static String getSavedEngine(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_ENGINE, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static void saveVoice(Context ctx, String tag) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_VOICE, tag == null ? "" : tag).apply();
        } catch (Exception ignored) {
        }
    }

    public static String getSavedVoice(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_VOICE, "");
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        // 已经有实例就别重建。
        // 原来无条件 bind()，会 release 掉服务里那个实例 ——
        // 表现就是"听着听着打开看得清 App，声音当场就断了"。
        if (tts != null && !failed) {
            notifyListener();
            return;
        }
        bind();
    }

    public static void rebind(Context ctx) {
        if (appCtx == null && ctx != null) {
            appCtx = ctx.getApplicationContext();
        }
        release();
        bind();
    }

    /**
     * 初始化失败之后的重试。3 秒内不重复，免得用户连点把引擎反复拉起。
     * 没有这个入口，引擎一旦初始化失败就只能重启 App 才能恢复。
     */
    public static void retryIfFailed() {
        if (!failed || appCtx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRetry < 3000) {
            return;
        }
        lastRetry = now;
        Log.w("tts", "引擎初始化失败，重试一次");
        rebind(appCtx);
    }

    private static void bind() {
        if (appCtx == null) {
            return;
        }
        // 先释放旧实例，避免每次 init()/rebuild() 泄漏一个 TTS 引擎
        // （rebind() 知道先 release()，bind() 之前漏了）
        session++;      // 旧实例的在途回调一并作废
        chunkTotal = 0;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        ready = false;
        failed = false;
        final int mySerial = ++serial;
        TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (mySerial != serial) {
                    return;   // 旧实例回调，丢弃
                }
                if (status != TextToSpeech.SUCCESS) {
                    failed = true;
                    ready = false;
                    Log.e("tts", "引擎初始化失败 status=" + status
                            + " pkg=" + currentPkg);
                    notifyListener();
                    return;
                }
                cnOk = setup(tts);
                ready = true;
                failed = false;
                attachProgress();
                applySavedVoice();
                Log.i("tts", "引擎就绪 pkg=" + (currentPkg.length() == 0 ? "(系统默认)" : currentPkg)
                        + " 中文=" + cnOk + " 音色数=" + listVoicesZhFirst().size());
                notifyListener();
            }
        };
        String pkg = getSavedEngine(appCtx);
        if (pkg != null && pkg.length() > 0 && !installed(appCtx, pkg)) {
            // 存的引擎被卸载了。不清掉的话每次启动都必然初始化失败，
            // 而且用户完全不知道该去哪儿改 —— 直接退回系统默认
            Log.w("tts", "保存的引擎已不存在：" + pkg + "，改回系统默认引擎");
            saveEngine(appCtx, "");
            pkg = "";
        }
        try {
            if (pkg != null && pkg.length() > 0) {
                tts = new TextToSpeech(appCtx, l, pkg);
                currentPkg = pkg;
            } else {
                tts = new TextToSpeech(appCtx, l);
                currentPkg = "";
            }
        } catch (Exception e) {
            tts = null;
            failed = true;
            Log.e("tts", "new TextToSpeech 抛异常", e);
        }
    }

    /** 这个包还在不在 */
    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 挂朗读进度回调。引擎重建后必须重新挂，否则读完不回调、状态卡住 */
    private static void attachProgress() {
        if (tts == null) {
            return;
        }
        try {
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    onChunkEnd(id, false);
                }

                @Override
                public void onError(String id) {
                    onChunkEnd(id, true);
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 一段念完了。
     *
     * 只有"当前会话的最后一段"才算整次朗读结束：
     * 长文本会被切成几段排队，引擎一段一段回调；
     * 并且被 stop() 掉的旧会话也会回调 —— 靠会话号筛掉。
     *
     * 判断"最后一段"直接看 id 里的段号，不靠计数器累加 ——
     * 回调在引擎的线程上，累加计数会有竞态，错了就是状态永远卡在"朗读中"。
     */
    private static void onChunkEnd(String id, boolean error) {
        int s = sessionOf(id);
        if (s == -1) {
            // 引擎没回传我们给的那个 id（个别第三方引擎会这样）。
            // 这种回调没法判断属于哪一次，只能当成"当前这次结束了" ——
            // 丢弃的话球会永远停在"两竖"（朗读中），比偶尔早一帧收尾严重得多。
            Log.w("tts", "回调没有可识别的 id，按本次结束处理：" + id);
            notifyEnd(error);
            return;
        }
        if (s != session) {
            Log.i("tts", "丢弃旧会话的回调 id=" + id);
            return;
        }
        if (error) {
            // 出错就别等了：后面排队的片段很可能已经被引擎丢掉
            notifyEnd(true);
            return;
        }
        if (!isLastChunk(id)) {
            return;     // 后面还有片段，别急着收状态
        }
        notifyEnd(false);
    }

    private static void notifyEnd(boolean error) {
        if (speechListener == null) {
            return;
        }
        if (error) {
            speechListener.onError();
        } else {
            speechListener.onDone();
        }
    }

    /** id 形如 "3#0"，# 前面是会话号，后面是第几段 */
    private static boolean isLastChunk(String id) {
        int i = (id == null) ? -1 : id.indexOf('#');
        if (i < 0) {
            return true;    // 没有段号信息，当作只有一段
        }
        try {
            return Integer.parseInt(id.substring(i + 1)) >= chunkTotal - 1;
        } catch (Exception e) {
            return true;
        }
    }

    private static int sessionOf(String id) {
        if (id == null || id.length() == 0) {
            return -1;
        }
        int i = id.indexOf('#');
        String head = (i < 0) ? id : id.substring(0, i);
        try {
            return Integer.parseInt(head);
        } catch (Exception e) {
            return -1;
        }
    }

    public static void release() {
        serial++;
        session++;      // 在途回调全部作废
        chunkTotal = 0;
        ready = false;
        if (tts != null) {
            try {
                tts.stop();
            } catch (Exception ignored) {
            }
            try {
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        tts = null;
    }

    /** 只设语言。语速音调在 speak 时按 Prefs 设 */
    public static boolean setup(TextToSpeech t) {
        if (t == null) {
            return false;
        }
        try {
            int r = t.setLanguage(Locale.CHINA);
            boolean ok = !(r == TextToSpeech.LANG_MISSING_DATA
                    || r == TextToSpeech.LANG_NOT_SUPPORTED);
            if (!ok) {
                t.setLanguage(Locale.getDefault());
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 朗读。语速音调从 Prefs 取。
     *
     * 长文本必须切段：官方上限 getMaxSpeechInputLength() 是 4000 字，
     * 超了 speak() 直接返回 ERROR —— 引擎一个字都不念，界面上却已经进入"朗读中"，
     * 用户看到球变橙、听不到声音，点一下球又退回待命，像坏了一样。
     * 切段后按顺序排队（FLUSH + ADD），读完最后一段才算这次朗读结束。
     *
     * 返回值也如实反映引擎的应答，不再一律 true。
     */
    public static boolean speak(String text, float rate, float pitch) {
        if (tts == null || !ready || text == null) {
            return false;
        }
        try {
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            java.util.List<String> parts = split(text);
            if (parts.isEmpty()) {
                return false;
            }
            int mySession = ++session;
            chunkTotal = parts.size();
            Log.i("tts", "speak " + text.length() + " 字 → " + parts.size()
                    + " 段 rate=" + rate + " pitch=" + pitch);
            for (int i = 0; i < parts.size(); i++) {
                String id = mySession + "#" + i;
                int mode = (i == 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                int r;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Bundle b = new Bundle();
                    b.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                    r = tts.speak(parts.get(i), mode, b, id);
                } else {
                    r = tts.speak(parts.get(i), mode, null);
                }
                if (r != TextToSpeech.SUCCESS) {
                    Log.e("tts", "引擎拒收第 " + i + " 段，返回 " + r);
                    session++;      // 作废这一批已经排进去的
                    chunkTotal = 0;
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e("tts", "speak 抛异常", e);
            chunkTotal = 0;
            return false;
        }
    }

    /**
     * 把长文本切成不超过 MAX_CHUNK 的几段，尽量切在标点上。
     * 找不到标点就硬切 —— 硬切的听感差，但总比整段被拒收、一个字都不念强。
     */
    private static java.util.List<String> split(String text) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String t = text.trim();
        while (t.length() > 0) {
            if (t.length() <= MAX_CHUNK) {
                out.add(t);
                break;
            }
            int cut = -1;
            for (int i = MAX_CHUNK - 1; i >= MAX_CHUNK / 2; i--) {
                char c = t.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                        || c == '.' || c == '!' || c == '?' || c == ';'
                        || c == '，' || c == ',' || c == '、') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0) {
                cut = MAX_CHUNK;
            }
            out.add(t.substring(0, cut));
            t = t.substring(cut);
        }
        return out;
    }

    /**
     * 停止。
     *
     * 原来这里会用空串 speak 去"冲洗队列"，
     * 但 MultiTTS 那类引擎收到空文本会报错、甚至卡住状态机，
     * 表现为"停了一次之后再也点不动朗读"。
     * 直接 stop() 就够了，不要多此一举。
     */
    public static void stop() {
        // 会话号一变，在途的"读完了"回调就全被丢掉，
        // 不会在读下一句的时候被上一句的回调把状态清回待命
        session++;
        chunkTotal = 0;
        if (tts == null) {
            return;
        }
        try {
            tts.stop();
        } catch (Exception ignored) {
        }
    }

    /** 兜底：还在响就整个重建，强制闭嘴。重建后一定重新挂回调 */
    public static void forceStop() {
        if (tts == null) {
            return;
        }
        boolean still = false;
        try {
            still = tts.isSpeaking();
        } catch (Exception ignored) {
        }
        if (!still) {
            return;
        }
        String pkg = currentPkg;
        release();
        bind();
        currentPkg = pkg;
    }

    public static TextToSpeech get() {
        return tts;
    }
}
