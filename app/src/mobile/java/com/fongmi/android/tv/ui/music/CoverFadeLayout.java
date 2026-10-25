package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 全屏歌词页封面渐隐层（gz-full-parity-1025 §7.3，按他的实帧形态）：
 * 封面拉满居上 46%，下沿 84dp 内渐隐进取色面（宿主经 setToneColor 给封面取色）。
 * 注：84dp 渐隐是星辰「背景一体」修法的近似（瓜子无此构造，§14.2 已如实标注），本类只负责绘制。
 */
public class CoverFadeLayout extends FrameLayout {

    private static final float COVER_RATIO = 0.46f;
    private static final float FADE_DP = 84f;

    private final ImageView coverView;
    private final android.view.View fadeView;
    private int toneColor = 0xFF101418;

    public CoverFadeLayout(Context context) {
        super(context);
        coverView = buildCover(context);
        fadeView = new android.view.View(context);
        addView(coverView);
        addView(fadeView);
        applyFade();
    }

    public CoverFadeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        coverView = buildCover(context);
        fadeView = new android.view.View(context);
        addView(coverView);
        addView(fadeView);
        applyFade();
    }

    private ImageView buildCover(Context context) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return view;
    }

    public void setCover(String url, String title) {
        if (url == null || url.isEmpty()) {
            coverView.setImageDrawable(null);
            return;
        }
        ImgUtil.load(title == null ? "" : title, url, coverView);
    }

    public void setCoverDrawable(Drawable drawable) {
        coverView.setImageDrawable(drawable);
    }

    public void setToneColor(int color) {
        this.toneColor = color;
        applyFade();
    }

    private void applyFade() {
        int transparent = toneColor & 0x00FFFFFF;
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{transparent, toneColor});
        fadeView.setBackground(gradient);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int coverHeight = Math.round(h * COVER_RATIO);
        LayoutParams coverParams = new LayoutParams(LayoutParams.MATCH_PARENT, coverHeight);
        coverParams.gravity = Gravity.TOP;
        coverView.setLayoutParams(coverParams);
        int fadeHeight = Math.round(FADE_DP * getResources().getDisplayMetrics().density);
        LayoutParams fadeParams = new LayoutParams(LayoutParams.MATCH_PARENT, fadeHeight);
        fadeParams.gravity = Gravity.TOP;
        fadeParams.topMargin = Math.max(0, coverHeight - fadeHeight);
        fadeView.setLayoutParams(fadeParams);
    }
}
