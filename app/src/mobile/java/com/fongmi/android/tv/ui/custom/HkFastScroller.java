package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 海阔内容页右侧快速滚动条（对照 8.83 RecyclerFastScroller 行为）：
 * 列表可滚时在右侧显示细滑块，滚动/触摸时出现、静置后自动隐藏，
 * 按住拖动按比例跳转列表位置。
 */
public class HkFastScroller extends View {

    private RecyclerView recyclerView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideTask = () -> setShown(false);

    private float barTop;
    private float barHeight;
    private boolean shown;
    private boolean dragging;
    private float lastY;

    private int colorNormal = 0x66000000;
    private int colorPressed = 0xFFD4A017;

    public HkFastScroller(Context context) {
        super(context);
        init();
    }

    public HkFastScroller(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HkFastScroller(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        paint.setColor(colorNormal);
    }

    public void setColors(int normal, int pressed) {
        colorNormal = normal;
        colorPressed = pressed;
        paint.setColor(dragging ? colorPressed : colorNormal);
        invalidate();
    }

    /** 绑定 RecyclerView：跟随滚动更新滑块位置。 */
    public void attach(RecyclerView rv) {
        recyclerView = rv;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                updateBar();
            }
        });
        post(this::updateBar);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateBar() {
        if (recyclerView == null || getHeight() <= 0) {
            setShown(false);
            return;
        }
        int range = recyclerView.computeVerticalScrollRange();
        int extent = recyclerView.computeVerticalScrollExtent();
        int offset = recyclerView.computeVerticalScrollOffset();
        if (range <= extent) {
            setShown(false);
            return;
        }
        float h = getHeight();
        barHeight = Math.max(dp(48), h * extent / (float) range);
        barTop = (h - barHeight) * offset / (float) (range - extent);
        setShown(true);
        scheduleHide();
    }

    private void setShown(boolean show) {
        if (shown == show) {
            if (show) scheduleHide();
            return;
        }
        shown = show;
        if (!show) handler.removeCallbacks(hideTask);
        invalidate();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideTask);
        if (!dragging) handler.postDelayed(hideTask, 1200);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!shown || recyclerView == null) return;
        float right = getWidth() - dp(4);
        float left = right - dp(4);
        barRect.set(left, barTop, right, barTop + barHeight);
        canvas.drawRoundRect(barRect, dp(2), dp(2), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (recyclerView == null) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastY = event.getY();
                dragging = true;
                handler.removeCallbacks(hideTask);
                paint.setColor(colorPressed);
                setShown(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - lastY;
                lastY = event.getY();
                int range = recyclerView.computeVerticalScrollRange();
                int extent = recyclerView.computeVerticalScrollExtent();
                float track = getHeight() - barHeight;
                if (range > extent && track > 0) {
                    recyclerView.scrollBy(0, Math.round(dy / track * (range - extent)));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                paint.setColor(colorNormal);
                invalidate();
                scheduleHide();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(hideTask);
        super.onDetachedFromWindow();
    }
}
