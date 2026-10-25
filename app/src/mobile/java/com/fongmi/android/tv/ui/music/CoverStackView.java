package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 封面卡片主题的封面叠卡区（重做第一步，规格 gz-full-parity-1025 §4＋§1＋§15.1-8）：
 * 前卡（真封面）＋两张后叠占位卡。卡几何在 onLayout 定死：
 * 卡高＝min(区高×0.98, 区宽/0.65)、卡宽＝卡高×0.65（竖卡，宽/高 0.65；§4 宽式与源码
 * PlayerScreenKt.java:8877-8883 互证后的定式）；前卡圆角 22dp。后卡尺寸 ×0.93、
 * 圆角 22dp、偏移 (−12,−18)/(12,−14)dp、旋转 −4°/+4°、elevation 6dp、无封面色。
 * 手势（§1 封面卡行）：横滑切歌＝轴 dominance 1.2、锚点 clamp(屏宽×0.42,220,360)dp、
 * 触发 36dp、甩速 600dp/s；单击＝宿主切展开态；双击＝宿主收藏（回调用）。
 * 换卡 ThreeCard alternate 350ms：出场滑出全幅÷4＋淡出＋缩放→0.92；
 * 入场自全幅滑入＋淡入＋缩放 0.94→1。
 * 切歌不挂旧脸：换图先清前卡旧图再异步载入；手势切歌先出场、宿主换歌后入场新图。
 */
public class CoverStackView extends FrameLayout {

    public static final int DIR_NEXT = 1;
    public static final int DIR_PREV = -1;

    public interface Callback {
        /** 单击封面（宿主切展开/信息态）。 */
        void onTapCover();

        /** 双击封面（宿主收藏切换＋爱心爆发）；坐标为本区内坐标。 */
        void onDoubleTapCover(float x, float y);

        /** 横滑已过触发线松手（dir：DIR_NEXT/DIR_PREV）；宿主据此调控制器切歌。 */
        void onSwipeCommit(int dir);
    }

    private static final float ASPECT = 0.65f;
    private static final float HEIGHT_FILL = 0.98f;
    private static final float BACK_SCALE = 0.93f;
    private static final int RADIUS_DP = 22;
    private static final long SWAP_MS = 350L;
    private static final float DOMINANCE = 1.2f;
    private static final float TRIGGER_DP = 36f;
    private static final float FLING_DP_S = 600f;

    private final View backCardFar;
    private final View backCardNear;
    private final ImageView frontCard;
    private final GestureDetector detector;
    private Callback callback;

    private String currentUrl;
    private boolean laidOut;
    private boolean gesturesEnabled = true;

    // 手势状态
    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean tapSequence;

    // 换卡状态
    private boolean exiting;
    private String pendingUrl;
    private String pendingTitle;
    private int pendingDir = DIR_NEXT;

    public CoverStackView(Context context) {
        this(context, null);
    }

    public CoverStackView(Context context, AttributeSet attrs) {
        super(context, attrs);
        backCardFar = buildBackCard();
        backCardNear = buildBackCard();
        frontCard = new ImageView(context);
        frontCard.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frontCard.setBackgroundColor(0x14FFFFFF);
        // 前卡 Z 序必须压过两张后卡（后卡 elevation 6dp），否则后卡会盖到前卡上
        frontCard.setElevation(ResUtil.dp2px(8));
        frontCard.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ResUtil.dp2px(RADIUS_DP));
            }
        });
        frontCard.setClipToOutline(true);
        addView(backCardFar);
        addView(backCardNear);
        addView(frontCard);
        detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (callback != null) callback.onTapCover();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                tapSequence = true;
                if (callback != null) callback.onDoubleTapCover(e.getX(), e.getY());
                return true;
            }
        });
    }

    private View buildBackCard() {
        View v = new View(getContext());
        v.setBackgroundResource(R.drawable.bg_cover_back_card);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ResUtil.dp2px(RADIUS_DP));
            }
        });
        v.setClipToOutline(true);
        v.setElevation(ResUtil.dp2px(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            v.setOutlineAmbientShadowColor(0x1A000000);
            v.setOutlineSpotShadowColor(0x29000000);
        }
        return v;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** 展开态关手势：叠卡不可见时不许再横滑切歌（宿主切态时调用）。 */
    public void setGesturesEnabled(boolean enabled) {
        gesturesEnabled = enabled;
        if (!enabled && dragging) {
            dragging = false;
            snapBack();
        }
    }

    public ImageView frontCard() {
        return frontCard;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) return;
        // 卡几何定死（C3）：高＝min(区高×0.98, 区宽/0.65)，宽＝高×0.65
        int cardH = (int) Math.min(h * HEIGHT_FILL, w / ASPECT);
        int cardW = (int) (cardH * ASPECT);
        int backW = (int) (cardW * BACK_SCALE);
        int backH = (int) (cardH * BACK_SCALE);
        int cx = w / 2;
        int cy = h / 2;
        layoutCentered(frontCard, cx, cy, cardW, cardH);
        layoutCentered(backCardNear, cx + ResUtil.dp2px(-12), cy + ResUtil.dp2px(-18), backW, backH);
        layoutCentered(backCardFar, cx + ResUtil.dp2px(12), cy + ResUtil.dp2px(-14), backW, backH);
        backCardNear.setRotation(-4f);
        backCardFar.setRotation(4f);
        laidOut = true;
    }

    private void layoutCentered(View v, int cx, int cy, int vw, int vh) {
        v.layout(cx - vw / 2, cy - vh / 2, cx - vw / 2 + vw, cy - vh / 2 + vh);
    }

    /**
     * 换封面（宿主切歌/首开调用）。animate=false 直接落图（先清后载、不挂旧脸）；
     * animate=true 走 alternate 换卡（dir 决定出入方向）。换卡进行中再来新图先暂存、出场完再入场。
     */
    public void setCover(String url, String title, boolean animate, int dir) {
        if (url != null && url.equals(currentUrl) && !exiting) return;
        if (exiting) {
            pendingUrl = url;
            pendingTitle = title;
            pendingDir = dir;
            return;
        }
        if (!animate || !laidOut || currentUrl == null) {
            applyCover(url, title);
            return;
        }
        beginSwap(url, title, dir);
    }

    private void applyCover(String url, String title) {
        currentUrl = url;
        frontCard.animate().cancel();
        frontCard.setTranslationX(0f);
        frontCard.setAlpha(1f);
        frontCard.setScaleX(1f);
        frontCard.setScaleY(1f);
        frontCard.setImageDrawable(null);
        if (url != null && !url.isEmpty()) ImgUtil.load(title == null ? "" : title, url, frontCard);
    }

    private void beginSwap(String url, String title, int dir) {
        exiting = true;
        pendingUrl = url;
        pendingTitle = title;
        pendingDir = dir;
        float exitTo = (dir == DIR_NEXT ? -1f : 1f) * (getWidth() / 4f);
        frontCard.animate()
                .translationX(exitTo)
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(SWAP_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        frontCard.animate().setListener(null);
                        exiting = false;
                        String u = pendingUrl;
                        String t = pendingTitle;
                        int d = pendingDir;
                        pendingUrl = null;
                        enterCover(u, t, d);
                    }
                })
                .start();
    }

    private void enterCover(String url, String title, int dir) {
        currentUrl = url;
        frontCard.setImageDrawable(null);
        if (url != null && !url.isEmpty()) ImgUtil.load(title == null ? "" : title, url, frontCard);
        float from = (dir == DIR_NEXT ? 1f : -1f) * getWidth();
        frontCard.setTranslationX(from);
        frontCard.setAlpha(0f);
        frontCard.setScaleX(0.94f);
        frontCard.setScaleY(0.94f);
        frontCard.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SWAP_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 双击封面脉冲（§1：缩放 1.045/200ms、回落 spring 0.9/300——回落以同参过冲插值近似）。 */
    public void pulse() {
        frontCard.animate()
                .scaleX(1.045f)
                .scaleY(1.045f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        frontCard.animate().setListener(null);
                        frontCard.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(300L)
                                .setInterpolator(new OvershootInterpolator(0.9f))
                                .start();
                    }
                })
                .start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!gesturesEnabled) return false;
        detector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                tapSequence = false;
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) tracker.addMovement(event);
                if (tapSequence || exiting) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging) {
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * DOMINANCE) dragging = true;
                }
                if (dragging) frontCard.setTranslationX(clampDrag(dx));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracker != null) tracker.addMovement(event);
                if (dragging && !exiting) {
                    float upDx = event.getX() - downX;
                    float velocityDpS = 0f;
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000);
                        velocityDpS = tracker.getXVelocity() / getResources().getDisplayMetrics().density;
                    }
                    boolean pass = Math.abs(upDx) > ResUtil.dp2px((int) TRIGGER_DP)
                            || Math.abs(velocityDpS) > FLING_DP_S;
                    dragging = false;
                    if (pass && event.getActionMasked() == MotionEvent.ACTION_UP) {
                        int dir = upDx < 0 || (Math.abs(upDx) <= ResUtil.dp2px((int) TRIGGER_DP) && velocityDpS < 0)
                                ? DIR_NEXT : DIR_PREV;
                        // 出场由 beginSwap 在宿主回填新图时执行；此处先把卡留在原位等待换歌事件，
                        // 宿主切歌回调走 setCover(animate) 即开始出场。未切成（已是首/尾）时回弹。
                        if (callback != null) callback.onSwipeCommit(dir);
                        snapBackIfNoSwap();
                    } else {
                        snapBack();
                    }
                }
                if (tracker != null) {
                    tracker.recycle();
                    tracker = null;
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float clampDrag(float dx) {
        float screenW = getResources().getDisplayMetrics().widthPixels;
        float anchor = Math.max(ResUtil.dp2px(220), Math.min(ResUtil.dp2px(360), screenW * 0.42f));
        return Math.max(-anchor, Math.min(anchor, dx));
    }

    private void snapBackIfNoSwap() {
        // 宿主切歌成功会随控制器回调回 setCover(animate) 置 exiting；给异步回调留 300ms 窗，
        // 窗内仍未进入换卡视为未切成（队列首/尾），回弹
        postDelayed(() -> {
            if (!exiting) snapBack();
        }, 300L);
    }

    private void snapBack() {
        frontCard.animate()
                .translationX(0f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }
}
