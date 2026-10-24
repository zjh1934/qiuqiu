package com.floating.combo;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * 运行日志。写进 APP 私有目录 + 公共下载目录各一份。
 *
 * 用 APP 私有目录，是因为 Android 10+ 往公共目录随便写文件会被拦。
 * 界面上的「导出日志」会把文件复制到 Downloads，方便你捞出来。
 *
 * 只记代码内部事件（手势、状态、错误），不记你的任何内容。
 */
public class Log {

    private static final String TAG = "看得清";
    private static final String NAME = "qiuqiu.log";
    /** 文件超过 150KB 就砍掉前面一半，防止越用越撑 */
    private static final int MAX_BYTES = 150 * 1024;
    /** 界面上最多渲染多少行，防止 TextView 撑爆 */
    /** 和 recent 里保留的条数保持一致，否则"显示全部"名不副实 */
    public static final int MAX_RENDER = 60;
    private static final int MAX_LINES = 3000;

    private static File file = null;
    private static HandlerThread thread = null;
    private static Handler h = null;
    private static boolean inited = false;

    /** 内存里也留一份，界面可以直接显示最新若干条 */
    private static final ArrayList<String> recent = new ArrayList<String>();

    public static synchronized void init(Context ctx) {
        if (inited) {
            return;
        }
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            file = new File(dir, NAME);
        } catch (Exception e) {
            file = null;
        }
        try {
            thread = new HandlerThread("log");
            thread.start();
            h = new Handler(thread.getLooper());
        } catch (Exception e) {
            h = null;
        }
        inited = true;
        i("app", "日志开始 · " + deviceInfo());
    }

    private static String deviceInfo() {
        return "SDK=" + android.os.Build.VERSION.SDK_INT
                + " 设备=" + android.os.Build.MANUFACTURER
                + " " + android.os.Build.MODEL
                + " 版本=" + android.os.Build.VERSION.RELEASE;
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    /** 带异常的日志，堆栈也会记下来 */
    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + " | " + String.valueOf(t));
        if (t != null) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                t.printStackTrace(pw);
                pw.flush();
                String[] lines = sw.toString().split("\n");
                for (int i = 0; i < lines.length && i < 12; i++) {
                    write("E", tag, "  " + lines[i]);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 手势事件单独一个入口，方便看时序 */
    public static void gesture(String msg) {
        write("G", "gesture", msg);
    }

    /** 状态变化 */
    public static void state(String from, String to, String why) {
        write("S", "state", from + " → " + to + (why == null ? "" : "  (" + why + ")"));
    }

    private static synchronized void write(String level, String tag, String msg) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
                .format(new Date());
        final String line = time + " " + level + "/" + tag + ": " + msg;

        android.util.Log.d(TAG, line);

        synchronized (recent) {
            recent.add(line);
            while (recent.size() > 60) {
                recent.remove(0);
            }
        }

        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                append(line);
                trim();
            }
        });
    }

    private static synchronized void append(String line) {
        if (file == null) {
            return;
        }
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(line);
            w.write("\n");
            w.flush();
            w.close();
        } catch (Exception ignored) {
        }
    }

    /** 超过 MAX_LINES 就砍掉前面一半，别无限涨 */
    private static synchronized void trim() {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            if (file.length() < MAX_BYTES) {
                return;
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            long len = file.length();
            // 从后往前找，保留最后一半
            long keep = len / 2;
            raf.seek(keep);
            raf.readLine();   // 丢弃半行
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = raf.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            raf.close();
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(bos.toByteArray());
            fos.close();
        } catch (Exception ignored) {
        }
    }

    /** 最近若干条，界面直接显示 */
    public static synchronized ArrayList<String> recent() {
        synchronized (recent) {
            return new ArrayList<String>(recent);
        }
    }

    /** 清空 */
    public static void clear() {
        if (file != null) {
            try {
                FileOutputStream fos = new FileOutputStream(file, false);
                fos.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (recent) {
            recent.clear();
        }
        i("app", "日志已清空");
    }

    /**
     * 复制一份日志到「下载」目录，返回给用户看得懂的位置描述；失败返回 null。
     *
     * Android 10（Q）起作用域存储，直接写公共 Downloads 会被拦、抛异常，
     * 老代码被 catch 掉返回 null → 只能看到"导出失败"。
     * 改法：
     *   - API 29+ 用 MediaStore 写进 Downloads（作用域存储下的正路）
     *   - API < 29 仍可直写公共 Downloads
     *   - 任一路径失败，兜底写进 app 私有外部目录（必定可写），保证不空手而归
     */
    public static String export(Context ctx) {
        if (file == null || !file.exists()) {
            return null;
        }
        String stamp = new SimpleDateFormat("mmdd_HHmm", Locale.CHINA)
                .format(new Date());
        String name = "看得清日志_" + stamp + ".txt";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try {
                        OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                        FileInputStream in = new FileInputStream(file);
                        copy(in, os);
                        in.close();
                        os.close();
                        return Environment.DIRECTORY_DOWNLOADS + "/" + name;
                    } catch (Exception e) {
                        Log.e("log", "MediaStore 写入失败，转私有目录", e);
                    }
                }
            } else {
                File dl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dl != null) {
                    if (!dl.exists()) {
                        dl.mkdirs();
                    }
                    File out = new File(dl, name);
                    copy(new FileInputStream(file), new FileOutputStream(out));
                    return out.getAbsolutePath();
                }
            }
            // 兜底：写进 app 私有外部目录（必然可写，无需任何权限）
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            File out = new File(dir, name);
            copy(new FileInputStream(file), new FileOutputStream(out));
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.e("log", "导出失败", e);
            return null;
        }
    }

    /** 字节拷贝，关闭交给调用方 */
    private static void copy(java.io.InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /** 私有目录里那份的路径 */
    public static String path() {
        return file == null ? null : file.getAbsolutePath();
    }
}
