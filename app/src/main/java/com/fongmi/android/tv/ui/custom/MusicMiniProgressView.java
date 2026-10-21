package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 音乐播放页 GZ 式迷你进度条：平时底下只有一根短条，点一下展开完整进度栈，闲置后缩回。
 * 与视频侧 PlayerOsdController 用的 MiniProgressView 不是同一个类（视频／音乐播放器各独立成套，不混用）。
 * 形态按 GZ 播放页实拍量测（非 GZ 代码值）：宽约 52dp、高约 3dp、水平居中；
 * 轨道约白 20%、填充约白 40-45%（白底 alpha 叠加在深棕糊底上的反推，见报告）。
 * 视图本身高 24dp、条在垂直居中画 3dp：24dp 是点按热区，视觉条仍是 3dp。
 */
public class MusicMiniProgressView extends View {

    private float progress;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public MusicMiniProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        trackPaint.setColor(0x33FFFFFF);
        fillPaint.setColor(0x73FFFFFF);
    }

    public void setProgress(float p) {
        float v = Math.max(0f, Math.min(1f, p));
        if (v != progress) {
            progress = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = 3f * getResources().getDisplayMetrics().density;
        float top = (getHeight() - h) / 2f;
        rect.set(0, top, getWidth(), top + h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint);
        float fw = getWidth() * progress;
        if (fw > 0) {
            rect.set(0, top, Math.max(fw, h), top + h);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint);
        }
    }
}
