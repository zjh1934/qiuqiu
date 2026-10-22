package com.floating.combo;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部设置的唯一出入口。所有键集中在这里，别到处散着读写。 */
public class Prefs {

    public static final int[] COLORS = new int[]{
            0xFF1565C0,  // 蓝
            0xFF2E7D32,  // 绿
            0xFFC62828,  // 红
            0xFF6A1B9A,  // 紫
            0xFFEF6C00,  // 橙
            0xFF37474F,  // 墨
            0xFFFAFAFA,  // 白
    };
    public static final String[] COLOR_NAMES = new String[]{
            "蓝", "绿", "红", "紫", "橙", "墨", "白"
    };

    private static final String P = "cfg";

    private static final String K_BALL_ON = "ball_on";
    private static final String K_SIZE = "size";
    private static final String K_COLOR = "color";
    private static final String K_ALPHA = "alpha";
    private static final String K_IDLE_ALPHA = "idle_alpha";
    private static final String K_SCALE = "scale";
    private static final String K_HOLD = "hold_ms";
    private static final String K_SNAP = "snap";
    private static final String K_COMP = "compensate";
    private static final String K_BALL_X = "ball_x";
    private static final String K_RATE = "rate";
    private static final String K_PITCH = "pitch";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public boolean ballOn() {
        return sp.getBoolean(K_BALL_ON, true);
    }

    public void setBallOn(boolean v) {
        sp.edit().putBoolean(K_BALL_ON, v).apply();
    }

    public int sizeDp() {
        return sp.getInt(K_SIZE, 56);
    }

    public void setSizeDp(int v) {
        sp.edit().putInt(K_SIZE, v).apply();
    }

    public int colorIndex() {
        int i = sp.getInt(K_COLOR, 0);
        if (i < 0 || i >= COLORS.length) {
            i = 0;
        }
        return i;
    }

    public void setColorIndex(int v) {
        sp.edit().putInt(K_COLOR, v).apply();
    }

    public int color() {
        return COLORS[colorIndex()];
    }

    public float alpha() {
        return sp.getFloat(K_ALPHA, 0.92f);
    }

    public void setAlpha(float v) {
        sp.edit().putFloat(K_ALPHA, v).apply();
    }

    public float idleAlpha() {
        return sp.getFloat(K_IDLE_ALPHA, 0.30f);
    }

    public void setIdleAlpha(float v) {
        sp.edit().putFloat(K_IDLE_ALPHA, v).apply();
    }

    /** 放大倍率，1.5 ~ 5.0 */
    public float scale() {
        return sp.getFloat(K_SCALE, 2.5f);
    }

    public void setScale(float v) {
        sp.edit().putFloat(K_SCALE, v).apply();
    }

    /**
     * 按住多久算"要放大"。
     * 这是「点一下朗读」和「按住放大」的分界线，手快的人要调长。
     */
    public int holdMs() {
        return sp.getInt(K_HOLD, 260);
    }

    public void setHoldMs(int v) {
        sp.edit().putInt(K_HOLD, v).apply();
    }

    public boolean snapEdge() {
        return sp.getBoolean(K_SNAP, true);
    }

    public void setSnapEdge(boolean v) {
        sp.edit().putBoolean(K_SNAP, v).apply();
    }

    /**
     * 放大时按 1/s 缩小球的绘制，抵消系统对悬浮窗的放大。
     * 关掉的话放大时球会跟着变大。
     */
    public boolean compensate() {
        return sp.getBoolean(K_COMP, true);
    }

    public void setCompensate(boolean v) {
        sp.edit().putBoolean(K_COMP, v).apply();
    }

    public int ballX() {
        return sp.getInt(K_BALL_X, -1);
    }

    public int ballY() {
        return sp.getInt("ball_y", -1);
    }

    public void setBallPos(int x, int y) {
        sp.edit().putInt(K_BALL_X, x).putInt("ball_y", y).apply();
    }

    /** 语速 0.5 ~ 2.0，1.0 = 正常 */
    public float rate() {
        return sp.getFloat(K_RATE, 1.0f);
    }

    public void setRate(float v) {
        sp.edit().putFloat(K_RATE, v).apply();
    }

    /** 音调 0.5 ~ 2.0 */
    public float pitch() {
        return sp.getFloat(K_PITCH, 1.0f);
    }

    public void setPitch(float v) {
        sp.edit().putFloat(K_PITCH, v).apply();
    }
}
