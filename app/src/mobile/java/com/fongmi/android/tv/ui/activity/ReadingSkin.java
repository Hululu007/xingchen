package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 阅读模块换皮共用：深色毛玻璃＋金 #F2B33D 点缀（与精修稿 v2 同语言）。
 * 只出样式（颜色/背景/圆角/边线），不碰行为。
 */
public final class ReadingSkin {

    private ReadingSkin() {
    }

    public static final int GOLD = 0xFFF2B33D;
    public static final int GOLD_DARK = 0xFFD89A2B;
    public static final int GOLD_DEEP = 0xFFE8A93B;
    public static final int TEXT = 0xFFF4F2EC;
    public static final int SUB = 0x99F4F2EC;
    public static final int FAINT = 0x66F4F2EC;
    /**
     * 暖棕黑基底（2026-10-18 他定调对稿）：
     * 取色依据——小说阅读器稿整套里最暖的页面底 #141318 压暗为顶色；
     * 封面占位暖棕渐变（#2C2118→#6B4A2E 系）取暖调；
     * 顶部暖色柔光取稿内金色 #F2B33D 降透明度。
     * 层次要看得出来，但不压字。
     */
    public static final int PAGE_BG_TOP = 0xFF171310;
    public static final int PAGE_BG_BOTTOM = 0xFF0D0B08;
    /** 兼容旧引用：页面主底色＝暖棕黑顶色。 */
    public static final int PAGE_BG = PAGE_BG_TOP;

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 页面底：暖棕黑纵向渐变＋顶部一道暖金柔光（对稿，层次不压字）。 */
    public static Drawable pageBackground(Context c) {
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(c, 380));
        glow.setGradientCenter(0.5f, 0f);
        glow.setColors(new int[]{0x2BF2B33D, 0x00000000});
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PAGE_BG_TOP, PAGE_BG_BOTTOM});
        return new LayerDrawable(new Drawable[]{base, glow});
    }

    /**
     * 窗口底对齐页面底：阅读页全挂 Theme.XingChen.HkPage，其 windowBackground
     * 是米白 #FFF4F6F8——状态栏接缝的根。调用方在 onCreate 里调一次，
     * 颜色只从这里取，各页不许各调各的。
     *
     * @param darkPage 深色页时状态栏/导航栏图标切浅色，浅色页切深色
     */
    public static void fitWindowBackground(android.app.Activity act, int color,
                                          boolean darkPage) {
        try {
            act.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(color));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.window.WindowInsetsController ic =
                        act.getWindow().getInsetsController();
                if (ic != null) {
                    int mask = android.window.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.window.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    ic.setSystemBarsAppearance(darkPage ? 0 : mask, mask);
                }
            } else {
                android.view.View decor = act.getWindow().getDecorView();
                int f = decor.getSystemUiVisibility();
                int bits = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                decor.setSystemUiVisibility(darkPage ? (f & ~bits) : (f | bits));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 毛玻璃卡：半透明白渐变＋边缘高光＋圆角。阴影走 view.setElevation。 */
    public static GradientDrawable glassCard(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1AFFFFFF, 0x0BFFFFFF});
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, 2), 0x29FFFFFF);
        return d;
    }

    /** 金胶囊钮底：金渐变。 */
    public static GradientDrawable goldCapsule(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF6C65E, 0xFFE8A93B});
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 金虚线框（添加卡）。 */
    public static GradientDrawable goldDashed(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x0DF2B33D);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, 2), 0x73F2B33D, dp(c, 10), dp(c, 7));
        return d;
    }

    /** 深色圆钮底（边线高光）。 */
    public static GradientDrawable darkCircle(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x12FFFFFF);
        d.setStroke(dp(c, 2), 0x33FFFFFF);
        return d;
    }

    /** 金圆钮底。 */
    public static GradientDrawable goldCircle(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF6C65E, 0xFFDE9A26});
        d.setShape(GradientDrawable.OVAL);
        return d;
    }

    /** 分段胶囊容器（暗底灰描边）。 */
    public static GradientDrawable segContainer(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x0FFFFFFF);
        d.setCornerRadius(dp(c, 999));
        d.setStroke(dp(c, 2), 0x29FFFFFF);
        return d;
    }

    /** 分段选中：金底（漫画稿）或金 tint＋金字（小说稿），调用方选。 */
    public static GradientDrawable segSelectedSolid(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFF6C65E, 0xFFE8A93B});
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    public static GradientDrawable segSelectedTint(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x38F2B33D);
        d.setCornerRadius(dp(c, 999));
        d.setStroke(dp(c, 2), 0x8CF2B33D);
        return d;
    }

    /** 金进度条（ProgressBar 用）：轨道半透明白，填充金渐变。 */
    public static LayerDrawable goldProgressBar(Context c) {
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x24FFFFFF);
        track.setCornerRadius(dp(c, 999));
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{GOLD_DARK, 0xFFF2C95D});
        fill.setCornerRadius(dp(c, 999));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START,
                ClipDrawable.HORIZONTAL);
        LayerDrawable d = new LayerDrawable(new Drawable[]{track, clip});
        d.setId(0, android.R.id.background);
        d.setId(1, android.R.id.progress);
        return d;
    }

    /** 金 SeekBar：轨道＋填充＋白圆点。 */
    public static void styleGoldSeekBar(Context c, android.widget.SeekBar bar) {
        bar.setProgressDrawable(goldProgressBar(c));
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        thumb.setSize(dp(c, 16), dp(c, 16));
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(c, 8));
    }

    /** 开关着色：开态金，关态灰（行为不动）。 */
    public static void styleSwitch(android.widget.Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{Color.WHITE, Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{GOLD, 0x29FFFFFF}));
    }

    /** 封面圆角裁剪。 */
    public static void roundClip(View v, Context c, int radiusDp) {
        final float r = dp(c, radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view,
                                   android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** 顶部栏吃状态栏 insets（防接缝）：保留原有上 padding 再叠加。 */
    public static void applyTopInset(View v, Context c, int baseDp) {
        final int base = dp(c, baseDp);
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int top = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()).top;
            view.setPadding(view.getPaddingLeft(), base + top,
                    view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
    }

    /** 底部栏吃导航栏 insets（防接缝）。 */
    public static void applyBottomInset(View v, Context c, int baseDp) {
        final int base = dp(c, baseDp);
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), base + bottom);
            return insets;
        });
    }

    /** 分区头：左白标题＋右灰动作字（点击走调用方原逻辑）。 */
    public static LinearLayout sectionHeader(Context c, String title,
                                             String action,
                                             View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        lp.topMargin = dp(c, 22);
        lp.bottomMargin = dp(c, 10);
        row.setLayoutParams(lp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(TEXT);
        t.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        t.setLayoutParams(tlp);
        row.addView(t);

        if (action != null) {
            TextView m = new TextView(c);
            m.setText(action);
            m.setTextSize(13);
            m.setTextColor(FAINT);
            m.setPadding(dp(c, 8), dp(c, 8), dp(c, 8), dp(c, 8));
            if (onAction != null) m.setOnClickListener(onAction);
            row.addView(m);
        }
        return row;
    }

    /** 底部灰小字说明（稿里各页底部的注）。 */
    public static TextView footnote(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(FAINT);
        t.setLineSpacing(dp(c, 4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        lp.topMargin = dp(c, 14);
        lp.bottomMargin = dp(c, 24);
        t.setLayoutParams(lp);
        return t;
    }

    /** 深色页面的返回字钮「‹ 返回」（灰字，点走 finish）。 */
    public static TextView backLabel(Context c, View.OnClickListener onBack) {
        TextView t = new TextView(c);
        t.setText("‹ 返回");
        t.setTextSize(15);
        t.setTextColor(SUB);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setOnClickListener(onBack);
        return t;
    }
}
