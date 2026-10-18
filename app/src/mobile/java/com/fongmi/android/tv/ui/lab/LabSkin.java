package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/**
 * 实验室 UI 设计语言：定稿图 lab-home.png / lab-service.png。
 * 深色毛玻璃卡＋黄色 #F2B33D 只做点缀；跟默影视那套完全不一样。
 * 独立成套，不依赖其它模块的皮肤。
 */
public final class LabSkin {

    public static final int YELLOW = 0xFFF2B33D;
    public static final int TEXT_MAIN = 0xFFF2F2F5;
    public static final int TEXT_SUB = 0xFFB9B9C2;
    public static final int TEXT_DIM = 0xFF7A7A86;
    public static final int GREEN = 0xFF4ADE80;

    private LabSkin() {
    }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** 页面底：深黑渐变（定稿图底色） */
    public static GradientDrawable pageBackground(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B0B10, 0xFF101014, 0xFF0B0B10});
        d.setCornerRadius(0);
        return d;
    }

    /** 毛玻璃卡面：半透明深底＋细边线 */
    public static GradientDrawable cardBackground(Context c, boolean running) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x9E121218); // rgba(18,18,24,.62)
        d.setCornerRadius(dp(c, 18));
        d.setStroke(dp(c, 1), running ? 0x404ADE80 : 0x12FFFFFF);
        return d;
    }

    /** 状态徽：灰（未安装/已停止） */
    public static GradientDrawable tagGray(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x12FFFFFF);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 状态徽：绿（运行中） */
    public static GradientDrawable tagGreen(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x1F4ADE80);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 黄色主钮（只做点缀） */
    public static GradientDrawable buttonYellow(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(YELLOW);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 灰色次钮 */
    public static GradientDrawable buttonGray(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x17FFFFFF);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 小圆点（运行中） */
    public static GradientDrawable dot(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        int s = dp(c, 8);
        d.setSize(s, s);
        return d;
    }
}
