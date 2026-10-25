package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * 黑胶唱片架架面（照瓜子 RecordShelfPlayer 抠值）：三槽，f=clamp(约束宽×.66,180,300)dp、
 * 容器高 f−23、f2=min(约束×.5,f−36)、f3=f2×.95、当前槽宽 1.48×f3、架板条高 6dp；
 * 黑胶直径 .97×f3、内嵌封面 .22×f3、中心垫 .065×f3 #FFD4D0C5、中心点 .025×f3 #FF080808，
 * 播放时 30°/s 旋转。点当前盘＝收藏切换，点左右槽＝上/下一首。
 * 侧槽封面：宿主未下发邻曲封面 url，侧槽暂以素面＋标题呈现（对照表标注）。
 */
public class ShelfView extends View {

    public interface Listener {
        void onPrevSlot();

        void onNextSlot();

        void onDiscTap();
    }

    private static final int INK = 0xFF252522;
    private static final int ROOT = 0xFFF1F0ED;
    private static final int DISC = 0xFF17171A;
    private static final int PAD = 0xFFD4D0C5;
    private static final int DOT = 0xFF080808;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private Listener listener;
    private Bitmap coverBitmap;
    private String coverUrl;
    private String prevTitle = "";
    private String nextTitle = "";
    private float angle;
    private boolean playing;
    private long lastFrameNs;
    private boolean frameRunning;
    private float downX;
    private float downY;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameRunning) return;
            if (lastFrameNs > 0) {
                float dt = Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1e9f);
                if (playing) {
                    angle = (angle + 30f * dt) % 360f;
                    invalidate();
                }
            }
            lastFrameNs = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public ShelfView(Context context) {
        this(context, null);
    }

    public ShelfView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
    }

    private float dp(float v) {
        return v * density;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setHints(String prevTitle, String nextTitle) {
        this.prevTitle = prevTitle == null ? "" : prevTitle;
        this.nextTitle = nextTitle == null ? "" : nextTitle;
        invalidate();
    }

    public void setCover(String url) {
        if (url == null ? coverUrl == null : url.equals(coverUrl)) return;
        coverUrl = url;
        coverBitmap = null;
        if (url != null && !url.isEmpty()) {
            Glide.with(getContext()).asBitmap().load(url).override(512, 512)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable Transition<? super Bitmap> transition) {
                            if (url.equals(coverUrl)) {
                                coverBitmap = resource;
                                invalidate();
                            }
                        }

                        @Override
                        public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        }
                    });
        }
        invalidate();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        boolean should = playing && isAttachedToWindow();
        if (should && !frameRunning) {
            frameRunning = true;
            lastFrameNs = 0;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else if (!should) {
            frameRunning = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) setPlaying(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        frameRunning = false;
        super.onDetachedFromWindow();
    }

    private float f3For(float widthPx) {
        float wDp = widthPx / density;
        float f = Math.max(180f, Math.min(300f, wDp * 0.66f));
        float f2 = Math.min(wDp * 0.5f, f - 36f);
        return f2 * 0.95f;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float wDp = w / density;
        float f = Math.max(180f, Math.min(300f, wDp * 0.66f));
        setMeasuredDimension(w, Math.round(dp(f - 23f)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float f3 = dp(f3For(w));
        float curW = 1.48f * f3;
        float curH = f3;
        float sideW = f3 * 0.74f;
        float sideH = f3 * 0.86f;
        float boardY = h - dp(6);
        float curX = (w - curW) / 2f;
        float curY = boardY - curH;
        float sideY = boardY - sideH;

        paint.setColor((INK & 0x00FFFFFF) | (Math.round(255 * 0.16f) << 24));
        float boardW = curW * 1.1f;
        rect.set((w - boardW) / 2f, boardY, (w + boardW) / 2f, boardY + dp(6));
        canvas.drawRoundRect(rect, dp(3), dp(3), paint);

        drawSleeve(canvas, curX - curW * 0.52f, sideY, sideW, sideH, null, prevTitle);
        drawSleeve(canvas, curX + curW * 1.52f - sideW, sideY, sideW, sideH, null, nextTitle);

        float discD = 0.97f * f3;
        float discCx = w / 2f;
        float discCy = curY + discD * 0.30f;
        canvas.save();
        canvas.rotate(angle, discCx, discCy);
        paint.setColor(DISC);
        canvas.drawCircle(discCx, discCy, discD / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        for (int i = 1; i <= 3; i++) {
            paint.setColor(0x14FFFFFF);
            canvas.drawCircle(discCx, discCy, discD / 2f * (0.55f + i * 0.13f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        float inner = 0.22f * f3;
        if (coverBitmap != null) {
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(discCx, discCy, inner / 2f, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            rect.set(discCx - inner / 2f, discCy - inner / 2f, discCx + inner / 2f, discCy + inner / 2f);
            canvas.drawBitmap(coverBitmap, null, rect, paint);
            canvas.restore();
        }
        paint.setColor(PAD);
        canvas.drawCircle(discCx, discCy, 0.065f * f3 / 2f, paint);
        paint.setColor(DOT);
        canvas.drawCircle(discCx, discCy, 0.025f * f3 / 2f, paint);
        canvas.restore();

        drawSleeve(canvas, curX, curY, curW, curH, coverBitmap, "");
        paint.setColor((ROOT & 0x00FFFFFF) | (Math.round(255 * 0.0f) << 24));
    }

    private void drawSleeve(Canvas canvas, float x, float y, float w, float h,
                            @Nullable Bitmap bitmap, String label) {
        android.graphics.Path path = new android.graphics.Path();
        rect.set(x, y, x + w, y + h);
        path.addRoundRect(rect, dp(6), dp(6), android.graphics.Path.Direction.CW);
        canvas.save();
        canvas.clipPath(path);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, rect, paint);
        } else {
            paint.setColor(0xFFE3E0D8);
            canvas.drawRect(rect, paint);
            if (label != null && !label.isEmpty()) {
                paint.setColor(0xFF6E6C64);
                paint.setTextSize(dp(10));
                paint.setTextAlign(Paint.Align.CENTER);
                String shortLabel = label.length() > 10 ? label.substring(0, 10) : label;
                canvas.drawText(shortLabel, x + w / 2f, y + h / 2f, paint);
            }
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor((INK & 0x00FFFFFF) | (Math.round(255 * 0.10f) << 24));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) < dp(12) && Math.abs(dy) < dp(12) && listener != null) {
                    float w = getWidth();
                    if (event.getX() < w * 0.3f) listener.onPrevSlot();
                    else if (event.getX() > w * 0.7f) listener.onNextSlot();
                    else listener.onDiscTap();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
