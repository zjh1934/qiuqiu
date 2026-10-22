package com.floating.combo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 球本体。四态：
 *   ST_NORMAL    待命 —— 用户选的颜色
 *   ST_PICKING   选取态 —— 绿，等你点屏幕上的字
 *   ST_SPEAKING  朗读中 —— 橙 + 暂停图标
 *   ST_ACTIVE    放大中 —— 套一圈外环
 *
 * 关键：drawScale。
 * 系统全屏放大时悬浮窗会被一起放大，所以放大 s 倍时按 1/s 画，
 * 放大回来正好是设定大小，不会变大。
 */
public class BallView extends View {

    public static final int ST_NORMAL = 0;
    public static final int ST_ACTIVE = 1;
    public static final int ST_PICKING = 2;
    public static final int ST_SPEAKING = 3;

    /** 各状态的底色，跟拾字验证过的保持一致 */
    private static final int C_PICKING = 0xFF43A047;
    private static final int C_SPEAKING = 0xFFFB8C00;

    private final Paint pShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pIcon = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int color = 0xFF1565C0;
    private float alpha = 0.92f;
    private int state = ST_NORMAL;
    private float drawScale = 1f;

    public BallView(Context c) {
        super(c);
        pEdge.setStyle(Paint.Style.STROKE);
        pRing.setStyle(Paint.Style.STROKE);
        pIcon.setColor(Color.WHITE);
        pIcon.setStyle(Paint.Style.FILL);
    }

    public void setBallColor(int c) {
        color = c;
        invalidate();
    }

    public void setBallAlpha(float a) {
        alpha = a;
        invalidate();
    }

    public void setDrawScale(float s) {
        if (s <= 0f) {
            s = 1f;
        }
        if (Math.abs(drawScale - s) > 0.001f) {
            drawScale = s;
            invalidate();
        }
    }

    public void setState(int s) {
        if (state != s) {
            state = s;
            invalidate();
        }
    }

    /** 当前实际用的底色（选取/朗读态会盖掉用户颜色） */
    private int baseColor() {
        if (state == ST_PICKING) {
            return C_PICKING;
        }
        if (state == ST_SPEAKING) {
            return C_SPEAKING;
        }
        return color;
    }

    private float effAlpha() {
        float a = alpha;
        if (state == ST_PICKING || state == ST_SPEAKING) {
            a = Math.max(a, 0.94f);
        } else if (state == ST_ACTIVE) {
            a *= 0.72f;
        }
        return Math.max(0.15f, Math.min(1f, a));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float cx = w / 2f;
        float cy = h / 2f;
        float outer = Math.min(w, h) / 2f;
        float r = outer * 0.80f * drawScale;
        if (r <= 1f) {
            return;
        }

        float a = effAlpha();
        int base = baseColor() & 0xFFFFFF;

        // 1. 外阴影
        float shR = r * 1.22f;
        RadialGradient g = new RadialGradient(
                cx, cy + r * 0.10f, shR,
                new int[]{Color.argb((int) (90 * a), 0, 0, 0), Color.argb(0, 0, 0, 0)},
                new float[]{0.55f, 1f}, Shader.TileMode.CLAMP);
        pShadow.setShader(g);
        c.drawCircle(cx, cy + r * 0.08f, shR, pShadow);

        // 2. 主体：上亮下暗
        int top = shift(base, 1.18f);
        int bot = shift(base, 0.78f);
        LinearGradient lg = new LinearGradient(
                cx, cy - r, cx, cy + r,
                new int[]{argb(a, top), argb(a, bot)},
                null, Shader.TileMode.CLAMP);
        pBody.setShader(lg);
        c.drawCircle(cx, cy, r, pBody);

        // 3. 顶部高光
        RadialGradient gg = new RadialGradient(
                cx - r * 0.28f, cy - r * 0.34f, r * 0.62f,
                new int[]{Color.argb((int) (110 * a), 255, 255, 255),
                        Color.argb(0, 255, 255, 255)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        pGloss.setShader(gg);
        c.drawCircle(cx - r * 0.28f, cy - r * 0.34f, r * 0.62f, pGloss);

        // 4. 描边
        int lum = lum(base);
        int ec = lum > 160 ? 60 : 255;
        pEdge.setColor(Color.argb((int) (140 * a), ec, ec, ec));
        pEdge.setStrokeWidth(Math.max(1f, r * 0.045f));
        c.drawCircle(cx, cy, r - pEdge.getStrokeWidth() / 2f, pEdge);

        // 5. 放大中：外环
        if (state == ST_ACTIVE) {
            pRing.setColor(argb(a * 0.9f, base));
            pRing.setStrokeWidth(Math.max(1.5f, r * 0.10f));
            c.drawCircle(cx, cy, r * 1.16f, pRing);
        }

        // 6. 状态图标：选取态画个靶心，朗读中画暂停
        pIcon.setColor(Color.argb((int) (230 * a), 255, 255, 255));
        if (state == ST_PICKING) {
            float ir = r * 0.30f;
            c.drawCircle(cx, cy, ir, pIcon);
            pIcon.setColor(argb(a, base));
            c.drawCircle(cx, cy, ir * 0.55f, pIcon);
        } else if (state == ST_SPEAKING) {
            float bw = r * 0.20f;
            float bh = r * 0.52f;
            c.drawRect(cx - bw * 1.3f, cy - bh / 2f, cx - bw * 0.3f, cy + bh / 2f, pIcon);
            c.drawRect(cx + bw * 0.3f, cy - bh / 2f, cx + bw * 1.3f, cy + bh / 2f, pIcon);
        }
    }

    private static int argb(float a, int rgb) {
        int v = (int) (255 * Math.max(0f, Math.min(1f, a)));
        return Color.argb(v, rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
    }

    private static int shift(int rgb, float f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int lum(int c) {
        return (int) (0.299 * ((c >> 16) & 0xFF)
                + 0.587 * ((c >> 8) & 0xFF)
                + 0.114 * (c & 0xFF));
    }
}
