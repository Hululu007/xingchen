package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * 壁纸衬底：直接铺在壁纸上的文字，背后垫一层白 72% 的霜条，
 * 深浅两种壁纸下字都看得清；浅壁纸上近乎隐形，不破坏定稿观感。
 * （LabSkin 不许动，衬底逻辑收在这里。）
 */
public final class LabScrim {

    private LabScrim() {
    }

    /** 通栏霜条：白 72%，无圆角，顶栏/小字行直接用 */
    public static GradientDrawable bar(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xB8FFFFFF); // rgba(255,255,255,.72)，与卡面同料
        return d;
    }

    /** 给直接铺壁纸的 View 垫上霜条 */
    public static void under(View v) {
        v.setBackground(bar(v.getContext()));
    }
}
