package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.dialog.MusicLyricVersionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.music.view.BurstHeartView;
import com.fongmi.android.tv.ui.music.view.ClassicScrubberView;
import com.fongmi.android.tv.ui.music.view.CoverAmbientView;
import com.fongmi.android.tv.ui.music.view.InPageLyricsView;
import com.fongmi.android.tv.ui.music.view.MiniProgressView;
import com.fongmi.android.tv.ui.music.view.SpringAnimator;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 音乐播放页（从零重写，本批＝经典主题／默认主题，照瓜子源码抠值全表第三节＋对照表回补）：
 * 外壳氛围（dominant 色 450ms＋糊封面）→封面（权重盒占满、竖滑切歌、双击收藏爆发、暂停缩放 0.9）→
 * 歌名行三键（菜单＝工具面板／队列／播停）→歌手♡专辑（歌手/专辑可点跳搜索）→页内歌词→进度条。
 * 其余主题渲染器随后续批次点亮（主题存档与面板入口本批已通）。
 */
public class MusicPlayerActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static final PathInterpolator BS4_A = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator BS4_B = new PathInterpolator(0f, 0f, 0f, 1f);
    private static final PathInterpolator BS4_E = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator BS4_C = new PathInterpolator(0.3f, 0f, 1f, 1f);
    private static final PathInterpolator BS4_F = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    // 真弹簧（瓜子 spring 本体参数）：播停偏转 (0.55,600)、竖滑回中 (0.9,1400)、收藏回弹 (0.9,300)
    private final SpringAnimator mDeflectSpring = new SpringAnimator(0.55, 600);
    private final SpringAnimator mCoverBackSpring = new SpringAnimator(0.9, 1400);
    private final SpringAnimator mFavBackSpring = new SpringAnimator(0.9, 300);
    private android.animation.ValueAnimator mLyricDimAnim;
    private float mCoverScaleBase = 1f;

    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView mAmbient;
    private FrameLayout mCoverBox;
    private View mMeta;
    private ImageView mCover, mCoverOut;
    private BurstHeartView mBurst;
    private TextView mTitle, mArtist, mAlbum;
    private ImageView mBtnMenu, mBtnQueue, mBtnPlay, mBtnFav;
    private InPageLyricsView mLyrics;
    private ClassicScrubberView mScrubber;
    private MiniProgressView mMiniProgress;


    private MusicTrack mTrack;
    private String mTrackKey;
    private Bitmap mCoverBitmap;
    private boolean mFavorite;
    private int mFavTriggerCount;
    private Boolean mPlayingUi;
    private int mLastDir = 1;
    private boolean mFirstBind = true;
    private android.animation.ValueAnimator mTitleSizeAnim;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            MusicController ctl = MusicController.get();
            if (!mScrubber.isDragging()) mScrubber.setProgress(ctl.position(), ctl.duration());
            mMiniProgress.setProgress(ctl.position(), ctl.duration());
            // 瓜子口径：暂停→完整进度栈出来（音质/逐字筹在栈里）；播放→闲置 3 秒缩回迷你条
            if (!ctl.isPlaying() && !mScrubberExpanded) {
                setScrubberExpanded(true);
            } else if (mScrubberExpanded && ctl.isPlaying()
                    && System.currentTimeMillis() - mLastScrubberTouchMs > 3000) {
                setScrubberExpanded(false);
            }
            mLyrics.setPosition(ctl.position());
            mTickHandler.postDelayed(this, 250L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        setContentView(R.layout.activity_music_player);

        mAmbient = findViewById(R.id.playerAmbient);
        mCoverBox = findViewById(R.id.playerCoverBox);
        mMeta = findViewById(R.id.playerMeta);
        // 歌名列与封面零间隔、渲染下移 12dp（瓜子 offset，只移渲染不动布局）
        mMeta.setTranslationY(dp(12));
        mCover = findViewById(R.id.playerCover);
        mCoverOut = findViewById(R.id.playerCoverOut);
        mBurst = findViewById(R.id.playerBurst);
        mTitle = findViewById(R.id.playerTitle);
        mArtist = findViewById(R.id.playerArtist);
        mAlbum = findViewById(R.id.playerAlbum);
        mBtnMenu = findViewById(R.id.playerBtnMenu);
        mBtnQueue = findViewById(R.id.playerBtnQueue);
        mBtnPlay = findViewById(R.id.playerBtnPlay);
        mBtnFav = findViewById(R.id.playerBtnFav);
        mLyrics = findViewById(R.id.playerLyrics);
        mScrubber = findViewById(R.id.playerScrubber);
        mMiniProgress = findViewById(R.id.playerMiniProgress);

        // 歌名 800 字重＋行高 24sp、歌手行高 18sp（API 28+；低版本退回粗体与默认行高）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mTitle.setTypeface(android.graphics.Typeface.create(mTitle.getTypeface(), 800, false));
            mTitle.setLineHeight((int) dp(24));
            mArtist.setLineHeight((int) dp(18));
        }

        applyKeepScreenOn();
        wireActions();
        // 音质面板关掉时进度条恢复全宽（收缩画法的菜单态来源）
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewDestroyed(@NonNull androidx.fragment.app.FragmentManager fm,
                                                        @NonNull androidx.fragment.app.Fragment f) {
                        if (f instanceof MusicQualityPanelDialog || f instanceof MusicLyricVersionsDialog) mScrubber.setMenuOpen(false);
                    }
                }, false);

        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current, 1);
        updatePlayUi(MusicController.get().isPlaying());
        mTickHandler.post(mTick);
    }

    @Override
    protected void onDestroy() {
        mTickHandler.removeCallbacks(mTick);
        mDeflectSpring.cancel();
        mCoverBackSpring.cancel();
        mFavBackSpring.cancel();
        if (mLyricDimAnim != null) mLyricDimAnim.cancel();
        if (mTitleSizeAnim != null) mTitleSizeAnim.cancel();
        MusicController.get().removeListener(this);
        super.onDestroy();
    }

    private void applyKeepScreenOn() {
        if (MusicStore.isPlayerKeepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void wireActions() {
        mBtnPlay.setOnClickListener(v -> MusicController.get().toggle());
        mBtnQueue.setOnClickListener(v -> MusicQueueDialog.show(this));
        mBtnMenu.setOnClickListener(v -> MusicToolsSheet.show(this, new MusicToolsSheet.Callback() {
            @Override
            public void onSettingsChanged() {
                mLyrics.reloadSettings();
                applyKeepScreenOn();
            }

            @Override
            public void onOpenLyricPage() {
                openLyricPage();
            }
        }));
        mBtnFav.setOnClickListener(v -> toggleFavorite());
        // 爱心按压缩放 0.85（瓜子 SongMeta 同款）
        mBtnFav.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start();
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        // 歌手/专辑可点跳搜索（瓜子：search?q=歌手&tab=artist／q=专辑&tab=album，星辰搜索页无页签、直带词搜）
        mArtist.setOnClickListener(v -> {
            String first = firstArtist(mTrack == null ? null : mTrack.displayArtist());
            if (first != null) {
                com.fongmi.android.tv.ui.activity.MusicSearchActivity.startSearch(this, first);
            }
        });
        mAlbum.setOnClickListener(v -> {
            if (mTrack == null || TextUtils.isEmpty(mTrack.album)) return;
            String album = mTrack.album.trim();
            if (album.isEmpty() || "—".equals(album) || "未知专辑".equals(album)) return;
            com.fongmi.android.tv.ui.activity.MusicSearchActivity.startSearch(this, album);
        });
        mLyrics.setListener(timeMs -> MusicController.get().seekTo(timeMs));
        // 迷你条点开全栈；全栈上的交互刷新闲置计时
        mMiniProgress.setOnClickListener(v -> setScrubberExpanded(true));
        mScrubber.setOnTouchListener((v, e) -> {
            mLastScrubberTouchMs = System.currentTimeMillis();
            return false;
        });
        mScrubber.setListener(new ClassicScrubberView.Listener() {
            @Override
            public void onSeek(long ms) {
                MusicController.get().seekTo(ms);
            }

            @Override
            public void onScrubPreview(long ms) {
                // 瓜子 scrubPreviewMs 同路：拖动时页内歌词跟预览位置走，结束（-1）回播放位置
                mLyrics.setPreviewPosition(ms >= 0 ? ms : null);
            }

            @Override
            public void onQualityChip() {
                if (mTrack == null) return;
                mScrubber.setMenuOpen(true);
                MusicQualityPanelDialog.show(MusicPlayerActivity.this, mTrack,
                        MusicController.get().getQuality(),
                        q -> {
                            MusicController.get().setQuality(q);
                            refreshChips();
                        });
            }

            @Override
            public void onLyricChip() {
                // 瓜子口径：歌词筹点开＝歌词版本面板（翻译显隐在设置页开关，不占筹）
                if (mTrack == null) return;
                mScrubber.setMenuOpen(true);
                com.fongmi.android.tv.bean.music.MusicLyric cur =
                        com.fongmi.android.tv.music.MusicLyricCache.get(mTrack);
                MusicLyricVersionsDialog.show(MusicPlayerActivity.this, mTrack,
                        cur == null ? null : cur.rawLrc,
                        version -> {
                            com.fongmi.android.tv.music.MusicRepository.saveManualLyricChoice(mTrack, version);
                            com.fongmi.android.tv.music.MusicLyricCache.put(mTrack, version.getLyric());
                            applyLyric(mTrack, version.getLyric());
                        });
            }
        });
        installCoverGestures();
        installPageSwipe();
    }

    // ---------- 封面手势：竖滑切歌（轴锁定、36dp 触发、600dp/s 甩速、过阈震动）＋双击收藏 ----------
    private void installCoverGestures() {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // 瓜子封面双击：震动＋把「!当前收藏态」传给粒子（变已收藏 8 颗／取消 5 颗，种子＝触发计数）
                boolean target = !mFavorite;
                mCoverBox.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                mFavTriggerCount++;
                mBurst.burst(e.getX(), e.getY(), target, mFavTriggerCount);
                animateCoverFavPulse();
                toggleFavorite();
                return true;
            }
        });
        // 竖滑切歌已收口到页级 dispatchTouchEvent（全屏可滑，见 trackPageSwipe）；
        // 封面盒这里只留双击收藏，触摸全消费（不让点穿到下层）
        mCoverBox.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return true;
        });
    }

    // ---------- 全屏竖滑切歌（瓜子封面竖滑的页级收口：封面/歌词/空白处起滑都算） ----------
    // 点按（歌词点行快进、筹、按钮）与横向拖动（进度条）不接管；竖向轴锁定后补发 CANCEL 收走子视图事件流。
    private float mSwipeDownX, mSwipeDownY, mSwipeDy, mSwipeDx;
    private boolean mSwipeLocked, mSwipeDeadZone, mSwipeHaptic, mSwipeTaken, mSwipeInjecting;
    private VelocityTracker mSwipeVt;

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (!mSwipeInjecting && trackPageSwipe(e)) return true;
        return super.dispatchTouchEvent(e);
    }

    private boolean trackPageSwipe(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mCoverBackSpring.cancel();
                mCoverBox.animate().cancel();
                mSwipeDownX = e.getRawX();
                mSwipeDownY = e.getRawY();
                mSwipeDy = 0;
                mSwipeDx = 0;
                mSwipeLocked = false;
                mSwipeTaken = false;
                mSwipeHaptic = false;
                mSwipeDeadZone = mSwipeDownY < dp(24);
                if (mSwipeVt != null) mSwipeVt.recycle();
                mSwipeVt = VelocityTracker.obtain();
                mSwipeVt.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mSwipeVt != null) mSwipeVt.addMovement(e);
                mSwipeDx = e.getRawX() - mSwipeDownX;
                mSwipeDy = e.getRawY() - mSwipeDownY;
                if (!mSwipeLocked && Math.abs(mSwipeDy) > dp(8)) {
                    mSwipeLocked = Math.abs(mSwipeDy) > Math.abs(mSwipeDx) * 1.5f
                            && !(mSwipeDeadZone && mSwipeDy > 0);
                    if (mSwipeLocked) {
                        mSwipeTaken = true;
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        mSwipeInjecting = true;
                        try {
                            super.dispatchTouchEvent(cancel);
                        } finally {
                            mSwipeInjecting = false;
                        }
                        cancel.recycle();
                    }
                }
                if (mSwipeTaken) {
                    float anchor = anchorDistancePx();
                    float clamped = Math.max(-anchor, Math.min(anchor, mSwipeDy));
                    mCoverBox.setTranslationY(clamped);
                    if (!mSwipeHaptic && Math.abs(mSwipeDy) > dp(36)) {
                        mSwipeHaptic = true;
                        mCoverBox.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = mSwipeTaken;
                float vy = 0;
                if (mSwipeVt != null) {
                    mSwipeVt.addMovement(e);
                    mSwipeVt.computeCurrentVelocity(1000);
                    vy = mSwipeVt.getYVelocity();
                    mSwipeVt.recycle();
                    mSwipeVt = null;
                }
                mSwipeTaken = false;
                mSwipeLocked = false;
                if (was) {
                    if (Math.abs(mSwipeDy) > dp(36) || Math.abs(vy) > dp(600)) {
                        commitSwipe(mSwipeDy < 0 ? 1 : -1);
                    } else {
                        animateCoverBack();
                    }
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** 竖滑锚点距（瓜子口径）：clamp(屏高×0.42, 240dp, 400dp)；拖动钳制与提交目标都走它。 */
    private float anchorDistancePx() {
        float screenHDp = getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density;
        float d = Math.max(240f, Math.min(400f, screenHDp * 0.42f));
        return d * getResources().getDisplayMetrics().density;
    }

    private void commitSwipe(int dir) {
        mCoverBackSpring.cancel();
        mLastDir = dir;
        final float target = dir > 0 ? -anchorDistancePx() : anchorDistancePx();
        mCoverBox.animate().translationY(target).setDuration(150).setInterpolator(BS4_F)
                .withEndAction(() -> {
                    mCoverBox.setTranslationY(0);
                    if (dir > 0) MusicController.get().next();
                    else MusicController.get().prev();
                }).start();
    }

    private void animateCoverBack() {
        mCoverBox.animate().cancel();
        final float from = mCoverBox.getTranslationY();
        mCoverBackSpring.start(from, 0, new SpringAnimator.Listener() {
            @Override
            public void onUpdate(float value) {
                mCoverBox.setTranslationY(value);
            }

            @Override
            public void onEnd() {
            }
        });
    }

    /** 左划进全屏歌词页（歌词页在播放页右侧；他定）。歌词页本体随下一批落地，本批留接口。 */
    private void installPageSwipe() {
        final View content = findViewById(R.id.playerContent);
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getRawX() - e1.getRawX();
                float dy = e2.getRawY() - e1.getRawY();
                if (dx < -dp(60) && Math.abs(dx) > Math.abs(dy) * 1.5f && vx < -800) {
                    openLyricPage();
                    return true;
                }
                return false;
            }
        });
        content.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return false;
        });
    }

    private void openLyricPage() {
        try {
            Class<?> cls = Class.forName("com.fongmi.android.tv.ui.music.MusicLyricActivity");
            java.lang.reflect.Method start = cls.getMethod("start", Activity.class);
            start.invoke(null, this);
            overridePendingTransition(R.anim.music_slide_in_right, R.anim.music_slide_out_left);
        } catch (Throwable ignored) {
            // 歌词页未落地前左划无动作（不摆假入口、不崩）
        }
    }

    private boolean mScrubberExpanded;
    private long mLastScrubberTouchMs;
    private android.animation.ValueAnimator mStackAnim;

    /** 迷你进度条 ↔ 完整进度栈（瓜子口径 2026-10-26 他亲口：暂停进度条就出来、播放就变迷你条；
     *  另保留点开全栈、播放中闲置 3 秒缩回的自有口径）。
     *  切换不许生硬（他同日点名）：容器高度 250ms/bs4.a 形变＋两件交叉淡化，不再瞬间显隐。 */
    private void setScrubberExpanded(boolean expanded) {
        mScrubberExpanded = expanded;
        mLastScrubberTouchMs = System.currentTimeMillis();
        final View in = expanded ? mScrubber : mMiniProgress;
        final View out = expanded ? mMiniProgress : mScrubber;
        final android.view.ViewGroup box = (android.view.ViewGroup) mScrubber.getParent();
        if (mStackAnim != null) mStackAnim.cancel();
        in.animate().cancel();
        out.animate().cancel();
        int widthSpec = View.MeasureSpec.makeMeasureSpec(box.getWidth(), View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        in.measure(widthSpec, heightSpec);
        final int fromH = box.getHeight();
        final int toH = in.getMeasuredHeight();
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        out.setVisibility(View.VISIBLE);
        if (fromH > 0 && toH > 0 && fromH != toH) {
            mStackAnim = android.animation.ValueAnimator.ofInt(fromH, toH);
            mStackAnim.setDuration(250);
            mStackAnim.setInterpolator(BS4_A);
            mStackAnim.addUpdateListener(a -> {
                android.view.ViewGroup.LayoutParams lp = box.getLayoutParams();
                lp.height = (int) a.getAnimatedValue();
                box.setLayoutParams(lp);
            });
            mStackAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    android.view.ViewGroup.LayoutParams lp = box.getLayoutParams();
                    lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    box.setLayoutParams(lp);
                }
            });
            mStackAnim.start();
        }
        in.animate().alpha(1f).setDuration(250).setInterpolator(BS4_A).start();
        out.animate().alpha(0f).setDuration(200).setInterpolator(BS4_A)
                .withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setAlpha(1f);
                }).start();
    }

    // ---------- 数据绑定 ----------
    private static String trackKey(MusicTrack t) {
        return t == null ? "" : (t.id == null ? "" : t.id) + "|" + (t.title == null ? "" : t.title);
    }

    private void bindTrack(MusicTrack track, int dir) {
        mTrack = track;
        String key = trackKey(track);
        boolean same = key.equals(mTrackKey);
        mTrackKey = key;
        if (same && !mFirstBind) return;
        boolean wasFirst = mFirstBind;
        mFirstBind = false;

        // 歌名切换（瓜子 AnimatedSongText）：出 fadeOut 150/bs4.c → 换字 → 入 fadeIn 200/bs4.e
        // ＋尺寸动画 200/bs4.e（SizeTransform：文本块宽度由旧宽动画到新宽，单行标题高度恒定、
        // 尺寸变化只体现在宽度上，这里落为宽度剪裁动画）
        final String title = TextUtils.isEmpty(track.displayTitle()) ? "未在播放" : track.displayTitle();
        if (mTitleSizeAnim != null) {
            mTitleSizeAnim.cancel();
            mTitle.setClipBounds(null);
        }
        if (wasFirst) {
            mTitle.setText(title);
        } else {
            final int availW = mTitle.getWidth();
            final float padW = mTitle.getPaddingLeft() + mTitle.getPaddingRight();
            final int fromW = availW > 0
                    ? Math.min((int) Math.ceil(mTitle.getPaint().measureText(mTitle.getText().toString()) + padW), availW)
                    : (int) Math.ceil(mTitle.getPaint().measureText(mTitle.getText().toString()) + padW);
            mTitle.animate().alpha(0f).setDuration(150).setInterpolator(BS4_C)
                    .withEndAction(() -> {
                        mTitle.setText(title);
                        float textW = mTitle.getPaint().measureText(title) + padW;
                        final int newW = availW > 0 ? Math.min((int) Math.ceil(textW), availW) : (int) Math.ceil(textW);
                        final int height = mTitle.getHeight();
                        mTitle.animate().alpha(1f).setDuration(200).setInterpolator(BS4_E).start();
                        if (height > 0 && fromW != newW) {
                            mTitleSizeAnim = android.animation.ValueAnimator.ofInt(fromW, newW);
                            mTitleSizeAnim.setDuration(200);
                            mTitleSizeAnim.setInterpolator(BS4_E);
                            mTitleSizeAnim.addUpdateListener(a -> {
                                if (mTitle == null) return;
                                mTitle.setClipBounds(new android.graphics.Rect(
                                        0, 0, (int) a.getAnimatedValue(), height));
                            });
                            mTitleSizeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(android.animation.Animator animation) {
                                    if (mTitle != null) mTitle.setClipBounds(null);
                                }

                                @Override
                                public void onAnimationCancel(android.animation.Animator animation) {
                                    if (mTitle != null) mTitle.setClipBounds(null);
                                }
                            });
                            mTitleSizeAnim.start();
                        }
                    }).start();
        }
        mArtist.setText(track.displayArtist());
        // 歌手透明档：有点击回调且歌手可解析 → 白 0.45，否则 0.34（瓜子 SongMeta 两档）
        mArtist.setTextColor(firstArtist(track.displayArtist()) != null ? 0x73FFFFFF : 0x57FFFFFF);
        if (TextUtils.isEmpty(track.album)) {
            mAlbum.setVisibility(View.GONE);
        } else {
            mAlbum.setVisibility(View.VISIBLE);
            mAlbum.setText(track.album);
        }

        bindCover(track, dir);
        mAmbient.setCover(track.cover);
        refreshFavorite(track);
        refreshChips();
        mScrubber.resetForTrack();
        mScrubber.setProgress(0, track.duration > 0 ? track.duration : MusicController.get().duration());
        mLyrics.setLines(null);
        tryLoadLyric(track);
        updatePlayUi(MusicController.get().isPlaying());
    }

    private void bindCover(MusicTrack track, int dir) {
        String url = track.cover;
        if (TextUtils.isEmpty(url)) {
            mCoverOut.setVisibility(View.GONE);
            ImgUtil.load(track.displayTitle(), "", mCover);
            mCoverBitmap = null;
            return;
        }
        final String want = url;
        ImgUtil.load(url, 512, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (mTrack == null || !want.equals(mTrack.cover)) return;
                Bitmap old = mCoverBitmap;
                mCoverBitmap = resource;
                if (old == null || old == resource) {
                    mCover.setImageBitmap(resource);
                    mCover.setAlpha(1f);
                    mCover.setTranslationY(0);
                    mCover.setScaleX(mCoverScaleBase);
                    mCover.setScaleY(mCoverScaleBase);
                    return;
                }
                // 切歌转场 400ms/bs4.e：入竖滑 0.5×尺寸＋缩放 0.94→1；出竖滑 0.5、缩放→0.96、alpha→0
                // （缩放值与暂停缩放基数相乘——瓜子两层图形层叠加的等价）
                float base = mCoverScaleBase;
                float h = mCoverBox.getHeight() > 0 ? mCoverBox.getHeight() : dp(320);
                mCoverOut.setImageBitmap(old);
                mCoverOut.setVisibility(View.VISIBLE);
                mCoverOut.setAlpha(1f);
                mCoverOut.setTranslationY(0);
                mCoverOut.setScaleX(base);
                mCoverOut.setScaleY(base);
                mCover.setImageBitmap(resource);
                mCover.setTranslationY(dir * 0.5f * h);
                mCover.setScaleX(0.94f * base);
                mCover.setScaleY(0.94f * base);
                mCover.setAlpha(1f);
                mCover.animate().translationY(0).scaleX(base).scaleY(base)
                        .setDuration(400).setInterpolator(BS4_E).start();
                mCoverOut.animate().translationY(-dir * 0.5f * h).scaleX(0.96f * base).scaleY(0.96f * base).alpha(0f)
                        .setDuration(400).setInterpolator(BS4_E)
                        .withEndAction(() -> mCoverOut.setVisibility(View.GONE)).start();
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (mTrack != null && want.equals(mTrack.cover)) {
                    mCover.setImageDrawable(null);
                    mCoverBitmap = null;
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    private void refreshFavorite(MusicTrack track) {
        MusicLibrary.isFavorite(track, fav -> {
            if (mTrack != track) return;
            mFavorite = fav != null && fav;
            applyFavUi();
        });
    }

    private void applyFavUi() {
        mBtnFav.setImageResource(mFavorite
                ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
        // 爱心 tint 两档（已收藏 白 0.45／未收藏 0.30），300ms 动画（瓜子 songMetaFavTint）
        mBtnFav.animate().alpha(mFavorite ? 0.45f : 0.30f).setDuration(300).setInterpolator(BS4_A).start();
        mBtnFav.setColorFilter(0xFFFFFFFF);
    }

    private void toggleFavorite() {
        if (mTrack == null) return;
        boolean target = !mFavorite;
        MusicLibrary.setFavorite(mTrack, target, () -> {
            mFavorite = target;
            applyFavUi();
        });
    }

    /** 首位歌手（瓜子 qk5.e0 口径）：去空白后为空/—/未知歌手/未知艺术家 → null；否则取分隔后的第一位。 */
    private static String firstArtist(String artist) {
        if (artist == null) return null;
        String s = artist.trim();
        if (s.isEmpty() || "—".equals(s) || "未知歌手".equals(s) || "未知艺术家".equals(s)) return null;
        String norm = s.replaceAll("(?i)\\s+(feat\\.|ft\\.|featuring)\\s+", "、");
        String[] parts = norm.split("[、/,&]|\\s+&\\s+");
        for (String p : parts) {
            String t = p == null ? "" : p.trim();
            if (!t.isEmpty()) return t;
        }
        return s;
    }

    /** 封面收藏动效（瓜子 favScale）：升到 1.045（200ms/bs4.b）→spring(0.9,300) 回落，作用于封面整体（与暂停缩放基数相乘）。 */
    private void animateCoverFavPulse() {
        mCover.animate().cancel();
        mFavBackSpring.cancel();
        final float base = mCoverScaleBase;
        android.animation.ValueAnimator up = android.animation.ValueAnimator.ofFloat(mCover.getScaleX(), base * 1.045f);
        up.setDuration(200);
        up.setInterpolator(BS4_B);
        up.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            mCover.setScaleX(v);
            mCover.setScaleY(v);
        });
        up.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mFavBackSpring.start(base * 1.045f, base, new SpringAnimator.Listener() {
                    @Override
                    public void onUpdate(float value) {
                        mCover.setScaleX(value);
                        mCover.setScaleY(value);
                    }

                    @Override
                    public void onEnd() {
                    }
                });
            }
        });
        up.start();
    }

    private void refreshChips() {
        // 本地旗（瓜子 z50）：本地曲音质筹改标「本地」且不可点
        mScrubber.setLocalTrack(mTrack != null
                && com.fongmi.android.tv.music.MusicDownloadStore.isLocal(mTrack));
        // 音质筹只报真实音质（他的口径：取不到就不显示，永不拿所选档名垫场）
        String q = "";
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null && info.shortCode() != null && !info.shortCode().isEmpty()) {
                q = info.lossless ? info.codec : info.codec + " " + (info.bitrateKbps > 0 ? info.bitrateKbps + "kbps" : "");
                q = q.trim();
            }
        } catch (Throwable ignored) {
        }
        mScrubber.setChips(q, lyricChipLabel());
    }

    private String mLyricChip = "无词";

    private String lyricChipLabel() {
        return mLyricChip;
    }

    private void tryLoadLyric(MusicTrack track) {
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric == null || !lyric.hasLyric()) {
            mLyricChip = "无词";
            refreshChipsOnly();
            return;
        }
        applyLyric(track, lyric);
    }

    private void refreshChipsOnly() {
        // 只更新歌词筹（音质筹在 refreshChips 里一并算，这里复用它保持单一路径）
        refreshChips();
    }

    private void applyLyric(MusicTrack track, MusicLyric lyric) {
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> main =
                LyricsParser.parseWordLyric(lyric.rawLrc);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> trans = TextUtils.isEmpty(lyric.translation)
                ? null : LyricsParser.parseTimed(lyric.translation);
        List<com.fongmi.android.tv.player.lyrics.LyricsLine> roma = TextUtils.isEmpty(lyric.romanization)
                ? null : LyricsParser.parseTimed(lyric.romanization);
        List<RichLyrics.Line> lines = RichLyrics.align(main, trans, roma);
        if (mTrack != track) return;
        mLyrics.setLines(lines);
        boolean hasWords = false;
        if (main != null) {
            for (com.fongmi.android.tv.player.lyrics.LyricsLine l : main) {
                if (l != null && l.hasWords()) {
                    hasWords = true;
                    break;
                }
            }
        }
        if (!TextUtils.isEmpty(lyric.format)) mLyricChip = lyric.format;
        else if (hasWords) mLyricChip = "逐字";
        else if (main != null && !main.isEmpty() && main.get(0) != null && main.get(0).getTimeMs() > 0) mLyricChip = "逐行";
        else if (main != null && !main.isEmpty()) mLyricChip = "纯文本";
        else mLyricChip = "无词";
        refreshChips();
    }

    private void updatePlayUi(boolean playing) {
        boolean firstBind = mPlayingUi == null;
        mScrubber.setPlaying(playing);
        mBtnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        // 播停图标：播放中 28dp／暂停 25dp，300ms 尺寸过渡（以 padding 补间实现）；切换先偏转 ±15° 再弹簧回正
        int fromPad = mBtnPlay.getPaddingLeft();
        int toPad = Math.round(dp(playing ? 6 : 7.5f));
        if (fromPad != toPad) {
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(fromPad, toPad);
            va.setDuration(300);
            va.setInterpolator(BS4_A);
            va.addUpdateListener(a -> {
                int p = (int) a.getAnimatedValue();
                mBtnPlay.setPadding(p, p, p, p);
            });
            va.start();
        }
        if (mPlayingUi != null && mPlayingUi != playing) {
            // 播停偏转：先偏 ±15°，再 spring(0.55,600) 真弹簧回正
            mBtnPlay.animate().cancel();
            mBtnPlay.setRotation(playing ? 15f : -15f);
            mDeflectSpring.start(mBtnPlay.getRotation(), 0, new SpringAnimator.Listener() {
                @Override
                public void onUpdate(float value) {
                    mBtnPlay.setRotation(value);
                }

                @Override
                public void onEnd() {
                }
            });
        }
        mPlayingUi = playing;
        // 歌词帧时钟：播放中逐帧插值推进逐字填充（他的「逐字也有问题」定因之一，见 InPageLyricsView）
        mLyrics.setPlaying(playing);
        // 氛围漂移：播放中糊封面层缓慢流动、暂停冻结（他点名瓜子下半屏会动）
        if (mAmbient != null) mAmbient.setAmbientPlaying(playing);
        // 暂停缩放作用于封面本体（瓜子口径：缩封面，不缩盒——粒子层不跟着缩）
        mCoverScaleBase = playing ? 1f : 0.9f;
        mCover.animate().scaleX(mCoverScaleBase).scaleY(mCoverScaleBase).setDuration(300).setInterpolator(BS4_A).start();
        if (mCoverOut.getVisibility() == View.VISIBLE) {
            mCoverOut.animate().scaleX(mCoverScaleBase).scaleY(mCoverScaleBase).setDuration(300).setInterpolator(BS4_A).start();
        }
        // 页内歌词整盒变暗已撤（2026-10-26 订正）：lyricDimA 0.38 不是经典页播放态的常态——
        // 瓜子真机录屏帧实证播放中当前行是全白满亮（gz-ref-frames/gz-player-playing.jpg），
        // 整盒乘 0.38 后当前行只剩灰、后续行几乎看不见，正是他点名的「歌词跟瓜子不一样」。
        // 该值是宿主供值（PlayerThemeContext.lyricDimAlpha），生效条件未定死，经典页不落。
        if (mLyricDimAnim != null) mLyricDimAnim.cancel();
        mLyrics.setAlpha(1.0f);
    }

    // ---------- MusicController.Listener ----------
    @Override
    public void onTrackChanged(MusicTrack track) {
        if (track != null) bindTrack(track, mLastDir);
    }

    @Override
    public void onPlayingChanged(boolean playing) {
        updatePlayUi(playing);
    }

    @Override
    public void onLyricReady(MusicTrack track) {
        if (track != null && track == mTrack) tryLoadLyric(track);
    }

    @Override
    public void onAudioInfoChanged() {
        refreshChips();
    }

    @Override
    public void onTrackFailed(MusicTrack track, int reasonRes) {
        // 服务侧已按自动跳歌策略处理，页面只跟随 onTrackChanged
    }
}
