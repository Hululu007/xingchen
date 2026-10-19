package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * 壁纸衬底：页面正文里直接铺在壁纸上的文字，背后垫一层深色霜条，
 * 深浅两种壁纸下字都看得清。
 * 顶栏的那条带已撤（潇洒哥：标题直接落在壁纸上），这里只收正文垫字。
 */
public final class LabScrim {

    private LabScrim() {
    }

    /** 通栏霜条：黑 54%，无圆角，正文垫字行用 */
    public static GradientDrawable bar(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x8C000000); // rgba(0,0,0,.54)
        return d;
    }

    /** 给直接铺壁纸的 View 垫上霜条 */
    public static void under(View v) {
        v.setBackground(bar(v.getContext()));
    }
}
