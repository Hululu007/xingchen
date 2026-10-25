package com.fongmi.android.tv.ui.music;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 经典主题（瓜子默认主题，重做第二步；规格 gz-full-parity-1025 §2＋§1 经典行＋§15.3/15.4）。
 * 列序定死：封面（满幅方形、圆角 0）→SongMeta→内嵌歌词（weight 余高）→6dp→进度块
 * （双筹＋Scrubber）→底 45dp。无上下首运输行——切歌靠页级竖滑（根拦截竖向成立后
 * 内容列整体跟手，锚点 clamp(屏高×0.42,240,400)dp、触发 36dp、甩速 600dp/s、
 * dominance 1.5；Scrubber 区由其 requestDisallowIntercept 自保）。
 * 封面点按无动作（瓜子 onTap=null 定死）；双击＝收藏＋爱心爆发；长按＝开工具面板（回调）。
 * 切歌转场 CoverTransitionSpec.Classic：400ms 竖向、入滑移 0.5/缩放 0.94、
 * 出滑移 0.5/缩放 0.96/alpha→0，封面区双层交替承接，先清后载不挂旧脸。
 */
public class ClassicThemeView extends FrameLayout implements PlayerThemeFace {

    // §1 经典竖滑参数
    private static final float DOMINANCE = 1.5f;
    private static final float TRIGGER_DP = 36f;
    private static final float FLING_DP_S = 600f;
    // §2 切歌转场
    private static final long TRANSITION_MS = 400L;
    private static final float SHIFT_RATIO = 0.5f;

    private final LinearLayout column;
    private final FrameLayout coverArea;
    private ImageView coverFront;
    private ImageView coverBack;
    private final FrameLayout fxLayer;
    private final TextView titleView;
    private final TextView artistView;
    private final TextView albumView;
    private final ImageView favHeart;
    private final ImageView playKey;
    private final TextView qualityChip;
    private final TextView lyricChip;
    private final TextView timeCurrentView;
    private final TextView timeTotalView;
    private final PlayerScrubberView scrubber;
    private final ClassicLyricView lyricView;
    private final LinearLayout hintTop;
    private final LinearLayout hintBottom;
    private final TextView hintTopTitle;
    private final TextView hintBottomTitle;

    private PlayerThemeFace.Callback callback;
    private boolean playing;
    private boolean favorite;
    private long durationMs;
    private List<LyricsLine> mainLines;
    private boolean showTranslation = true;

    private String currentCoverUrl;
    private boolean coverAnimating;
    private String pendingUrl;
    private String pendingTitle;
    private int pendingDir = CoverStackView.DIR_NEXT;

    // 竖滑状态（坐标由 dispatchTouchEvent 旁观记录，事件只喂一次）
    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private boolean swipeClaimed;

    private int playPadPx = -1;
    private ValueAnimator playPadAnim;

    public ClassicThemeView(Context context) {
        this(context, null);
    }

    public ClassicThemeView(Context context, AttributeSet attrs) {
        super(context, attrs);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addView(column);

        // —— 封面区（满幅方形、高＝宽 onMeasure 定死、圆角 0） ——
        coverArea = new SquareFrame(context);
        coverArea.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        column.addView(coverArea);
        coverBack = buildCoverLayer(context);
        coverBack.setVisibility(INVISIBLE);
        coverFront = buildCoverLayer(context);
        coverArea.addView(coverBack);
        coverArea.addView(coverFront);
        fxLayer = new FrameLayout(context) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return false;
            }
        };
        fxLayer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fxLayer.setClickable(false);
        coverArea.addView(fxLayer);
        wireCoverGestures();

        // —— SongMeta（横垫 28、offset y +12、底垫 8；§15.4-3 结构） ——
        LinearLayout meta = new LinearLayout(context);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setPadding(ResUtil.dp2px(28), 0, ResUtil.dp2px(28), ResUtil.dp2px(8));
        meta.setTranslationY(ResUtil.dp2px(12));
        meta.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(meta);

        LinearLayout metaRow = new LinearLayout(context);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        meta.addView(metaRow);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        textColLp.weight = 1f;
        textCol.setLayoutParams(textColLp);
        metaRow.addView(textCol);

        titleView = new TextView(context);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(28)));
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextSize(18f);
        titleView.setTextColor(0xF2FFFFFF);
        titleView.setLetterSpacing(-0.4f / 18f);
        titleView.setTypeface(CoverCardThemeView.typeface(800));
        textCol.addView(titleView);

        LinearLayout artistRow = new LinearLayout(context);
        artistRow.setOrientation(LinearLayout.HORIZONTAL);
        artistRow.setGravity(Gravity.CENTER_VERTICAL);
        artistRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(22)));
        textCol.addView(artistRow);

        artistView = new TextView(context);
        artistView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        artistView.setGravity(Gravity.CENTER_VERTICAL);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setTextSize(14f);
        // 本步歌手无去向 → 不可点色 白 0.34（§2：可点 0.45／不可点 0.34）
        artistView.setTextColor(0x57FFFFFF);
        artistView.setTypeface(CoverCardThemeView.typeface(500));
        artistRow.addView(artistView);

        favHeart = new ImageView(context);
        LinearLayout.LayoutParams heartLp = new LinearLayout.LayoutParams(
                ResUtil.dp2px(27), ResUtil.dp2px(27));
        favHeart.setLayoutParams(heartLp);
        favHeart.setImageResource(R.drawable.ic_music_heart);
        favHeart.setColorFilter(0xFFFFFFFF);
        favHeart.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        // 图标 13dp：27dp 盒四向垫 7dp
        favHeart.setPadding(ResUtil.dp2px(7), ResUtil.dp2px(7), ResUtil.dp2px(7), ResUtil.dp2px(7));
        favHeart.setClickable(true);
        favHeart.setOnClickListener(v -> {
            if (callback != null) callback.onFavoriteClick();
        });
        artistRow.addView(favHeart);

        albumView = new TextView(context);
        albumView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(20)));
        albumView.setGravity(Gravity.CENTER_VERTICAL);
        albumView.setSingleLine(true);
        albumView.setEllipsize(TextUtils.TruncateAt.END);
        albumView.setTextSize(14f);
        // 专辑无去向 → 不可点色 白 0.26（§2：可点 0.45／不可点 0.26）
        albumView.setTextColor(0x42FFFFFF);
        albumView.setTypeface(CoverCardThemeView.typeface(500));
        textCol.addView(albumView);

        // —— 右侧三键（盒 40dp、距标题列 8dp；菜单 21dp/队列 20dp/播暂停按态 28/25dp） ——
        LinearLayout keyRow = new LinearLayout(context);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams keyRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        keyRowLp.leftMargin = ResUtil.dp2px(8);
        keyRow.setLayoutParams(keyRowLp);
        metaRow.addView(keyRow);

        ImageView menuKey = buildMetaKey(context, R.drawable.ic_music_more, 9.5f);
        menuKey.setOnClickListener(v -> {
            if (callback != null) callback.onOpenTools();
        });
        keyRow.addView(menuKey);
        ImageView queueKey = buildMetaKey(context, R.drawable.ic_music_queue, 10f);
        queueKey.setOnClickListener(v -> {
            if (callback != null) callback.onQueue();
        });
        keyRow.addView(queueKey);
        playKey = buildMetaKey(context, R.drawable.ic_music_play, 6f);
        playKey.setOnClickListener(v -> {
            if (callback != null) callback.onTogglePlay();
        });
        keyRow.addView(playKey);

        // —— 内嵌歌词（weight 余高；Meta→歌词间距 0） ——
        FrameLayout lyricRegion = new FrameLayout(context);
        LinearLayout.LayoutParams lyricRegionLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        lyricRegionLp.weight = 1f;
        lyricRegion.setLayoutParams(lyricRegionLp);
        column.addView(lyricRegion);
        lyricView = new ClassicLyricView(context);
        lyricView.setPadding(ResUtil.dp2px(28), 0, ResUtil.dp2px(28), 0);
        FrameLayout.LayoutParams lyricLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lyricLp.gravity = Gravity.TOP;
        lyricView.setLayoutParams(lyricLp);
        lyricView.setSeekListener(timeMs -> {
            if (callback != null) callback.onSeekMs(timeMs);
        });
        lyricRegion.addView(lyricView);

        column.addView(spacer(6));

        // —— 进度块（横垫 28：双筹＋Scrubber 行） ——
        LinearLayout scrubBlock = new LinearLayout(context);
        scrubBlock.setOrientation(LinearLayout.VERTICAL);
        scrubBlock.setPadding(ResUtil.dp2px(28), 0, ResUtil.dp2px(28), 0);
        scrubBlock.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(scrubBlock);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        qualityChip = buildChip(context);
        qualityChip.setVisibility(GONE);
        qualityChip.setOnClickListener(v -> {
            if (callback != null) callback.onQualityClick();
        });
        lyricChip = buildChip(context);
        lyricChip.setText("译词");
        lyricChip.setVisibility(GONE);
        LinearLayout.LayoutParams chipLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipLp2.leftMargin = ResUtil.dp2px(10);
        lyricChip.setLayoutParams(chipLp2);
        lyricChip.setOnClickListener(v -> {
            showTranslation = !showTranslation;
            lyricChip.setAlpha(showTranslation ? 1f : 0.55f);
            if (callback != null) callback.onLyricChipToggle(showTranslation);
        });
        chips.addView(qualityChip);
        chips.addView(lyricChip);
        scrubBlock.addView(chips);

        LinearLayout seekRow = new LinearLayout(context);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(18));
        seekLp.topMargin = ResUtil.dp2px(12);
        seekRow.setLayoutParams(seekLp);
        timeCurrentView = buildTimeText(context, Gravity.START);
        scrubber = new PlayerScrubberView(context);
        LinearLayout.LayoutParams scrubLp = new LinearLayout.LayoutParams(0, ResUtil.dp2px(18));
        scrubLp.weight = 1f;
        scrubLp.leftMargin = ResUtil.dp2px(6);
        scrubLp.rightMargin = ResUtil.dp2px(6);
        scrubber.setLayoutParams(scrubLp);
        timeTotalView = buildTimeText(context, Gravity.END);
        seekRow.addView(timeCurrentView);
        seekRow.addView(scrubber);
        seekRow.addView(timeTotalView);
        scrubBlock.addView(seekRow);
        scrubber.setListener(new PlayerScrubberView.Listener() {
            @Override
            public void onDraggingChanged(boolean dragging) {
                float a = dragging ? 0.72f : 0f;
                timeCurrentView.animate().alpha(a).setDuration(150L).start();
                timeTotalView.animate().alpha(a).setDuration(150L).start();
            }

            @Override
            public void onSeek(float fraction) {
                if (callback != null && durationMs > 0) callback.onSeekMs((long) (fraction * durationMs));
            }
        });

        column.addView(spacer(45));

        // —— 竖滑方向提示（§15.3：顶置上一首、底置下一首；小字 11sp/0.72 上、大字 15sp/0.92 下） ——
        hintTop = buildHint(context);
        hintTopTitle = (TextView) hintTop.getChildAt(1);
        FrameLayout.LayoutParams hintTopLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintTopLp.gravity = Gravity.TOP;
        hintTopLp.topMargin = ResUtil.dp2px(16);
        hintTop.setLayoutParams(hintTopLp);
        hintTop.setAlpha(0f);
        addView(hintTop);
        hintBottom = buildHint(context);
        hintBottomTitle = (TextView) hintBottom.getChildAt(1);
        FrameLayout.LayoutParams hintBottomLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintBottomLp.gravity = Gravity.BOTTOM;
        hintBottomLp.bottomMargin = ResUtil.dp2px(16);
        hintBottom.setLayoutParams(hintBottomLp);
        hintBottom.setAlpha(0f);
        addView(hintBottom);
        ((TextView) hintTop.getChildAt(0)).setText("上一首");
        ((TextView) hintBottom.getChildAt(0)).setText("下一首");
    }

    private ImageView buildCoverLayer(Context context) {
        ImageView v = new ImageView(context);
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setBackgroundColor(0x14FFFFFF);
        return v;
    }

    private ImageView buildMetaKey(Context context, int icon, float padDp) {
        ImageView key = new ImageView(context);
        key.setLayoutParams(new LinearLayout.LayoutParams(ResUtil.dp2px(40), ResUtil.dp2px(40)));
        key.setImageResource(icon);
        // 图标向量自带 #9AA7B8 蓝灰 tint（列表页口径），播放页按瓜子纯白覆盖，否则在氛围上近隐形
        key.setColorFilter(0xFFFFFFFF);
        key.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        // 图标尺寸＝40dp 盒 − 2×垫（菜单 9.5dp 垫→21dp 图标、队列 10dp 垫→20dp、播暂停 6dp 垫→28dp）
        int pad = Math.round(ResUtil.getDisplayMetrics().density * padDp);
        key.setPadding(pad, pad, pad, pad);
        key.setClickable(true);
        key.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) CoverCardThemeView.bounce(v, 0.96f);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) CoverCardThemeView.bounce(v, 1f);
            return false;
        });
        return key;
    }

    private TextView buildChip(Context context) {
        TextView chip = new TextView(context);
        chip.setBackgroundResource(R.drawable.bg_player_glass_btn);
        chip.setPadding(ResUtil.dp2px(12), ResUtil.dp2px(5), ResUtil.dp2px(12), ResUtil.dp2px(5));
        chip.setTextSize(11f);
        chip.setTextColor(0xB8FFFFFF);
        chip.setTypeface(CoverCardThemeView.typeface(700));
        chip.setLetterSpacing(0.6f / 11f);
        chip.setClickable(true);
        chip.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) CoverCardThemeView.bounce(v, 0.96f);
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) CoverCardThemeView.bounce(v, 1f);
            return false;
        });
        return chip;
    }

    private TextView buildTimeText(Context context, int gravity) {
        TextView t = new TextView(context);
        t.setLayoutParams(new LinearLayout.LayoutParams(ResUtil.dp2px(40),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        t.setGravity(gravity);
        t.setTextSize(11f);
        t.setTextColor(Color.WHITE);
        t.setLetterSpacing(0.2f / 11f);
        t.setAlpha(0f);
        t.setText("0:00");
        return t;
    }

    private LinearLayout buildHint(Context context) {
        LinearLayout hint = new LinearLayout(context);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER);
        TextView label = new TextView(context);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        label.setGravity(Gravity.CENTER);
        label.setTextSize(11f);
        label.setTextColor(0xB8FFFFFF);
        hint.addView(label);
        TextView name = new TextView(context);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = ResUtil.dp2px(2);
        nameLp.leftMargin = ResUtil.dp2px(48);
        nameLp.rightMargin = ResUtil.dp2px(48);
        name.setLayoutParams(nameLp);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextSize(15f);
        name.setTextColor(0xEBFFFFFF);
        name.setTypeface(CoverCardThemeView.typeface(700));
        hint.addView(name);
        return hint;
    }

    private View spacer(int dp) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ResUtil.dp2px(dp)));
        return v;
    }

    /** 满幅方形容器：高＝宽在 onMeasure 定死（§2 封面方形、宿主定死高，T-4 同口径）。 */
    private static class SquareFrame extends FrameLayout {
        SquareFrame(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int sq = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            super.onMeasure(sq, sq);
        }
    }

    // ================= 封面手势（单击无动作/双击收藏/长按工具面板） =================

    private void wireCoverGestures() {
        final android.view.GestureDetector detector = new android.view.GestureDetector(getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        // §15.4-9 定死：瓜子经典封面单击 onTap=null、无动作，不自加
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        pulseCover();
                        burstHearts(e.getX(), e.getY(), !favorite);
                        if (callback != null) callback.onFavoriteClick();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        if (callback != null) callback.onOpenTools();
                    }
                });
        coverArea.setClickable(true);
        coverArea.setOnTouchListener((v, ev) -> detector.onTouchEvent(ev));
    }

    /** 双击封面脉冲（§1：缩放 1.045/200ms、回落 spring 0.9/300 以同参过冲插值近似，第一步同法）。 */
    private void pulseCover() {
        coverFront.animate()
                .scaleX(1.045f)
                .scaleY(1.045f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        coverFront.animate().setListener(null);
                        coverFront.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(300L)
                                .setInterpolator(new OvershootInterpolator(0.9f))
                                .start();
                    }
                })
                .start();
    }

    // ================= 页级竖滑切歌（§1 经典行） =================

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // 旁观记录（每个事件只喂一次）：根拦截判定与接管后处理共用
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                swipeClaimed = false;
                if (tracker != null) tracker.recycle();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(ev);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (tracker != null) tracker.addMovement(ev);
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE && !swipeClaimed) {
            float dx = ev.getX() - downX;
            float dy = ev.getY() - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * DOMINANCE) {
                swipeClaimed = true;
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!swipeClaimed) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = event.getY() - downY;
                column.setTranslationY(clampDrag(dy));
                float trigger = ResUtil.dp2px((int) TRIGGER_DP);
                hintTop.setAlpha(dy > 0 ? Math.min(1f, dy / trigger) : 0f);
                hintBottom.setAlpha(dy < 0 ? Math.min(1f, -dy / trigger) : 0f);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dy = event.getY() - downY;
                float velocityDpS = 0f;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000);
                    velocityDpS = tracker.getYVelocity() / getResources().getDisplayMetrics().density;
                }
                boolean pass = Math.abs(dy) > ResUtil.dp2px((int) TRIGGER_DP)
                        || Math.abs(velocityDpS) > FLING_DP_S;
                swipeClaimed = false;
                recycleTracker();
                if (pass && callback != null) {
                    int dir = dy < 0 || (Math.abs(dy) <= ResUtil.dp2px((int) TRIGGER_DP) && velocityDpS < 0)
                            ? CoverStackView.DIR_NEXT : CoverStackView.DIR_PREV;
                    if (dir == CoverStackView.DIR_NEXT) callback.onNext();
                    else callback.onPrev();
                    settleColumn(150L, new DecelerateInterpolator());
                } else {
                    settleColumn(300L, new OvershootInterpolator(0.9f));
                }
                fadeHints();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                swipeClaimed = false;
                recycleTracker();
                settleColumn(300L, new OvershootInterpolator(0.9f));
                fadeHints();
                return true;
            default:
                return true;
        }
    }

    private void recycleTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private float clampDrag(float dy) {
        float screenH = getResources().getDisplayMetrics().heightPixels;
        float density = getResources().getDisplayMetrics().density;
        float anchorDp = Math.max(240f, Math.min(400f, screenH / density * 0.42f));
        float anchor = anchorDp * density;
        return Math.max(-anchor, Math.min(anchor, dy));
    }

    /** 列位移归零：提交 150ms（§1 提交口径）；回弹 spring 0.9/1400 以过冲插值近似（物理不等效）。 */
    private void settleColumn(long durationMs, android.view.animation.Interpolator interpolator) {
        column.animate()
                .translationY(0f)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .start();
    }

    private void fadeHints() {
        hintTop.animate().alpha(0f).setDuration(150L).start();
        hintBottom.animate().alpha(0f).setDuration(150L).start();
    }

    // ================= 宿主写入面 =================

    @Override
    public void setCallback(PlayerThemeFace.Callback callback) {
        this.callback = callback;
    }

    @Override
    public void setTrack(String title, String artist, String album) {
        titleView.setText(title == null ? "" : title);
        artistView.setText(artist == null ? "" : artist);
        albumView.setText(album == null ? "" : album);
        albumView.setVisibility(TextUtils.isEmpty(album) ? GONE : VISIBLE);
    }

    @Override
    public void setInfoDetail(String detail) {
        // 经典无歌曲信息面（§8.3 信息卡只属封面卡展开态/Apple 信息页），空实现
    }

    @Override
    public void setSwipeHints(String prevTitle, String nextTitle) {
        hintTopTitle.setText(prevTitle == null ? "" : prevTitle);
        hintBottomTitle.setText(nextTitle == null ? "" : nextTitle);
    }

    @Override
    public void setCover(String url, String title, boolean animate, int dir) {
        if (url != null && url.equals(currentCoverUrl) && !coverAnimating) return;
        if (coverAnimating) {
            pendingUrl = url;
            pendingTitle = title;
            pendingDir = dir;
            return;
        }
        if (!animate || currentCoverUrl == null || coverArea.getHeight() <= 0) {
            applyCoverImmediate(url, title);
            return;
        }
        beginTransition(url, title, dir);
    }

    private void applyCoverImmediate(String url, String title) {
        currentCoverUrl = url;
        coverFront.animate().cancel();
        coverFront.setTranslationY(0f);
        coverFront.setAlpha(1f);
        coverFront.setScaleX(1f);
        coverFront.setScaleY(1f);
        coverFront.setVisibility(VISIBLE);
        coverFront.setImageDrawable(null);
        if (url != null && !url.isEmpty()) ImgUtil.load(title == null ? "" : title, url, coverFront);
        coverBack.setVisibility(INVISIBLE);
        coverBack.setImageDrawable(null);
    }

    /** 转场（§2 Classic 规格）：新层滑入 0.5 高＋缩放 0.94→1、旧层滑出 0.5 高＋缩放→0.96＋alpha→0，400ms。 */
    private void beginTransition(String url, String title, int dir) {
        final float h = coverArea.getHeight();
        final ImageView incoming = coverBack;
        final ImageView outgoing = coverFront;
        coverAnimating = true;
        currentCoverUrl = url;
        incoming.setImageDrawable(null);
        if (url != null && !url.isEmpty()) ImgUtil.load(title == null ? "" : title, url, incoming);
        float inFrom = (dir == CoverStackView.DIR_NEXT ? 1f : -1f) * h * SHIFT_RATIO;
        float outTo = (dir == CoverStackView.DIR_NEXT ? -1f : 1f) * h * SHIFT_RATIO;
        incoming.setTranslationY(inFrom);
        incoming.setScaleX(0.94f);
        incoming.setScaleY(0.94f);
        incoming.setAlpha(1f);
        incoming.setVisibility(VISIBLE);
        incoming.animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
        outgoing.animate()
                .translationY(outTo)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .alpha(0f)
                .setDuration(TRANSITION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        outgoing.animate().setListener(null);
                        coverBack = outgoing;
                        coverFront = incoming;
                        outgoing.setVisibility(INVISIBLE);
                        outgoing.setTranslationY(0f);
                        outgoing.setScaleX(1f);
                        outgoing.setScaleY(1f);
                        outgoing.setAlpha(1f);
                        outgoing.setImageDrawable(null);
                        coverAnimating = false;
                        if (pendingUrl != null || pendingTitle != null) {
                            String u = pendingUrl;
                            String t = pendingTitle;
                            int d = pendingDir;
                            pendingUrl = null;
                            pendingTitle = null;
                            setCover(u, t, true, d);
                        }
                    }
                })
                .start();
    }

    @Override
    public void setPlaying(boolean value) {
        boolean first = playPadPx < 0;
        playing = value;
        playKey.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        // 图标尺寸按态（§2/§15.1-10）：播放图标 28dp（垫 6dp）/暂停图标 25dp（垫 7.5dp），300ms tween
        int target = Math.round(ResUtil.getDisplayMetrics().density * (value ? 7.5f : 6f));
        if (first) {
            playPadPx = target;
            playKey.setPadding(target, target, target, target);
            return;
        }
        if (playPadPx == target) return;
        if (playPadAnim != null) playPadAnim.cancel();
        final int from = playPadPx;
        playPadAnim = ValueAnimator.ofInt(from, target);
        playPadAnim.setDuration(300L);
        playPadAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        playPadAnim.addUpdateListener(a -> {
            int p = (int) a.getAnimatedValue();
            playPadPx = p;
            playKey.setPadding(p, p, p, p);
        });
        playPadAnim.start();
    }

    @Override
    public boolean isPlaying() {
        return playing;
    }

    @Override
    public void setFavorite(boolean value) {
        favorite = value;
        favHeart.setImageResource(value ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
    }

    @Override
    public boolean isFavorite() {
        return favorite;
    }

    /** 音质筹：describe() 文案；null 时隐藏（测不到不显，他定案）。 */
    @Override
    public void setQualityText(String text) {
        if (TextUtils.isEmpty(text)) {
            qualityChip.setVisibility(GONE);
        } else {
            qualityChip.setVisibility(VISIBLE);
            qualityChip.setText(text);
        }
    }

    @Override
    public void setLyricChipState(boolean hasTranslation, boolean show) {
        showTranslation = show;
        lyricChip.setVisibility(hasTranslation ? VISIBLE : GONE);
        lyricChip.setAlpha(show ? 1f : 0.55f);
    }

    @Override
    public void setProgress(long positionMs, long totalMs) {
        durationMs = totalMs;
        if (totalMs > 0) scrubber.setFraction((float) positionMs / (float) totalMs);
        timeCurrentView.setText(CoverCardThemeView.formatTime(positionMs));
        timeTotalView.setText(CoverCardThemeView.formatTime(totalMs));
    }

    @Override
    public void setLyrics(List<RichLyrics.Line> lines, boolean loading) {
        mainLines = null;
        boolean hasTrans = false;
        if (lines != null) {
            mainLines = new ArrayList<>();
            for (RichLyrics.Line l : lines) {
                if (l == null) continue;
                mainLines.add(l.main);
                if (!TextUtils.isEmpty(l.translation)) hasTrans = true;
            }
        }
        showTranslation = MusicPlayerSettings.isShowTranslation();
        setLyricChipState(hasTrans, showTranslation);
        lyricView.setLines(mainLines, loading);
    }

    @Override
    public void advanceLyric(long positionMs) {
        lyricView.advance(positionMs);
    }

    // ================= 双击爱心爆发（§1 参数，与第一步同式） =================

    private void burstHearts(float x, float y, boolean favoriting) {
        if (fxLayer.getWidth() <= 0) return;
        Random random = new Random();
        RippleView ripple = new RippleView(getContext());
        ripple.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ripple.cx = x;
        ripple.cy = y;
        ripple.maxRadius = coverArea.getWidth() * 0.38f;
        fxLayer.addView(ripple);
        ValueAnimator rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(800L);
        rippleAnim.addUpdateListener(a -> {
            ripple.progress = (float) a.getAnimatedValue();
            ripple.invalidate();
        });
        rippleAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fxLayer.removeView(ripple);
            }
        });
        rippleAnim.start();
        int count = favoriting ? 8 : 5;
        List<HeartSpec> specs = new ArrayList<>();
        List<ImageView> hearts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            HeartSpec s = new HeartSpec();
            s.angleDeg = random.nextFloat() * 120f - 60f;
            s.distancePx = ResUtil.dp2px((int) (random.nextFloat() * 48f + 42f));
            s.sizePx = ResUtil.dp2px((int) (random.nextFloat() * 10f + 9f));
            s.rotation = random.nextFloat() * 50f - 25f;
            s.delayMs = (long) ((random.nextFloat() * 0.08f + i * 0.035f) * 1000f);
            specs.add(s);
            ImageView heart = new ImageView(getContext());
            heart.setImageResource(R.drawable.ic_music_heart_fill);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s.sizePx, s.sizePx);
            heart.setLayoutParams(lp);
            heart.setX(x - s.sizePx / 2f);
            heart.setY(y - s.sizePx / 2f);
            heart.setAlpha(0f);
            fxLayer.addView(heart);
            hearts.add(heart);
        }
        long total = 1100L;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(total);
        anim.addUpdateListener(a -> {
            long elapsed = (long) ((float) a.getAnimatedValue() * total);
            for (int i = 0; i < hearts.size(); i++) {
                HeartSpec s = specs.get(i);
                ImageView heart = hearts.get(i);
                float t = (elapsed - s.delayMs) / 700f;
                if (t < 0f) {
                    heart.setAlpha(0f);
                    continue;
                }
                t = Math.min(1f, t);
                double rad = Math.toRadians(s.angleDeg);
                float dx = (float) Math.sin(rad) * s.distancePx * t;
                float dy = (float) -Math.cos(rad) * s.distancePx * t;
                heart.setX(x - s.sizePx / 2f + dx);
                heart.setY(y - s.sizePx / 2f + dy);
                heart.setRotation(s.rotation * t);
                float alpha = t < 0.15f ? t / 0.15f : (t > 0.7f ? (1f - t) / 0.3f : 1f);
                heart.setAlpha(Math.max(0f, alpha));
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (ImageView h : hearts) fxLayer.removeView(h);
            }
        });
        anim.start();
    }

    private static class HeartSpec {
        float angleDeg;
        float distancePx;
        int sizePx;
        float rotation;
        long delayMs;
    }

    private static class RippleView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx;
        float cy;
        float maxRadius;
        float progress;

        RippleView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ResUtil.dp2px(2));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            if (progress <= 0f || maxRadius <= 0f) return;
            paint.setColor(ColorUtils.setAlphaComponent(Color.WHITE, Math.round((1f - progress) * 0.5f * 255f)));
            canvas.drawCircle(cx, cy, maxRadius * progress, paint);
        }
    }
}
