package com.fongmi.android.tv.ui.music.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 随心听页刻度尺（瓜子 ShuffleRecordCard 唱片下 18dp 高整宽刻度带）：
 * 长短刻度交替——每 5 根一长刻，其余短刻，顶部对齐向下画。
 * 刻度间距／长短高度的瓜子真值在其 Canvas 合成 lambda 里未抠到，
 * 这里按瓜子截图量级自定（间距 6dp、长 13dp、短 7dp、线宽 1dp），报告已标抠不到；
 * 颜色由页面按封面 accent 注入（瓜子此带以封面 tone 为绘制入参），无封面时用中灰。
 */
public class MusicRulerView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float density;

    public MusicRulerView(Context context) {
        super(context);
        init();
    }

    public MusicRulerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        paint.setColor(0x8C8E8E93);
        paint.setStrokeWidth(Math.max(1f, density));
    }

    /** 注入刻度色（封面 accent，已含透明度口径由调用方决定） */
    public void setToneColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) return;
        float spacing = 6f * density;
        float longH = 13f * density;
        float shortH = 7f * density;
        float x = spacing * 0.5f;
        int i = 0;
        while (x < w) {
            float h = (i % 5 == 0) ? longH : shortH;
            canvas.drawLine(x, 0f, x, h, paint);
            x += spacing;
            i++;
        }
    }
}
