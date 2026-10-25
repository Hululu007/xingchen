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
import com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.music.view.BurstHeartView;
import com.fongmi.android.tv.ui.music.view.ClassicScrubberView;
import com.fongmi.android.tv.ui.music.view.CoverAmbientView;
import com.fongmi.android.tv.ui.music.view.InPageLyricsView;
import com.fongmi.android.tv.ui.music.view.MiniProgressView;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 音乐播放页（从零重写，本批＝经典主题／默认主题，照瓜子全表第三节＋定案截图 113250）：
 * 外壳氛围（dominant 色 450ms＋糊封面）→封面（占满父级、竖滑切歌、双击收藏爆发、暂停缩放 0.9）→
 * 歌名行三键（菜单＝工具面板／队列／播停）→歌手♡专辑→页内歌词→进度条。
 * 其余主题渲染器随后续批次点亮（主题存档与面板入口本批已通）。
 */
public class MusicPlayerActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static final PathInterpolator BS4_E = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final PathInterpolator BS4_C = new PathInterpolator(0.3f, 0f, 1f, 1f);
    private static final PathInterpolator BS4_F = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView mAmbient;
    private FrameLayout mCoverBox;
    private ImageView mCover, mCoverOut;
    private BurstHeartView mBurst;
    private TextView mTitle, mArtist, mAlbum;
    private ImageView mBtnMenu, mBtnQueue, mBtnPlay, mBtnFav;
    private InPageLyricsView mLyrics;
    private ClassicScrubberView mScrubber;
    private MiniProgressView mMiniProgress;
    private boolean mScrubberExpanded;
    private long mLastScrubberTouchMs;

    private MusicTrack mTrack;
    private String mTrackKey;
    private Bitmap mCoverBitmap;
    private boolean mFavorite;
    private int mLastDir = 1;
    private boolean mFirstBind = true;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            MusicController ctl = MusicController.get();
            if (!mScrubber.isDragging()) mScrubber.setProgress(ctl.position(), ctl.duration());
            mMiniProgress.setProgress(ctl.position(), ctl.duration());
            // 迷你进度条闲置缩回：展开后 3 秒无操作且正在播放才缩（暂停保持展开）
            if (mScrubberExpanded && ctl.isPlaying()
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

        applyKeepScreenOn();
        wireActions();
        sizeCoverBox();

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

    /** 封面测量驱动：宽占满父级，高＝min(宽, 内容高−元信息−歌词最小区−进度条)，下限 180dp。 */
    private void sizeCoverBox() {
        final View root = findViewById(R.id.playerRoot);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int w = root.getWidth(), h = root.getHeight();
                if (w <= 0 || h <= 0) return;
                int coverH = (int) Math.min(w, h - dp(52 + 24 + 140 + 92));
                coverH = Math.max((int) dp(180), coverH);
                if (mCoverBox.getLayoutParams().height != coverH) {
                    mCoverBox.getLayoutParams().height = coverH;
                    mCoverBox.requestLayout();
                }
            }
        });
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
        mLyrics.setListener(timeMs -> MusicController.get().seekTo(timeMs));
        // 迷你进度条：点开完整进度栈；展开后交互刷新闲置计时（点栈任意处都算）
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
            public void onQualityChip() {
                if (mTrack == null) return;
                MusicQualityPanelDialog.show(MusicPlayerActivity.this, mTrack,
                        MusicController.get().getQuality(),
                        q -> {
                            MusicController.get().setQuality(q);
                            refreshChips();
                        });
            }

            @Override
            public void onLyricChip() {
                MusicStore.setShowTranslation(!MusicStore.isShowTranslation());
                mLyrics.reloadSettings();
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
                toggleFavorite();
                mBurst.burst(e.getX(), e.getY());
                return true;
            }
        });
        mCoverBox.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, totalDy, totalDx;
            private boolean axisLocked, deadZone;
            private boolean hapticDone;
            private VelocityTracker vt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                gd.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        totalDy = 0;
                        totalDx = 0;
                        axisLocked = false;
                        hapticDone = false;
                        deadZone = downY < dp(24); // 顶部状态栏死区：dy>0 不触发
                        vt = VelocityTracker.obtain();
                        vt.addMovement(e);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (vt != null) vt.addMovement(e);
                        totalDx = e.getRawX() - downX;
                        totalDy = e.getRawY() - downY;
                        if (!axisLocked && Math.abs(totalDy) > dp(8)) {
                            axisLocked = Math.abs(totalDy) > Math.abs(totalDx) * 1.5f;
                            if (!axisLocked) return true;
                        }
                        if (axisLocked && !deadZone) {
                            float clamped = Math.max(-mCoverBox.getHeight(), Math.min(mCoverBox.getHeight(), totalDy));
                            mCoverBox.setTranslationY(clamped);
                            if (!hapticDone && Math.abs(totalDy) > dp(36)) {
                                hapticDone = true;
                                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (vt != null) {
                            vt.addMovement(e);
                            vt.computeCurrentVelocity(1000);
                        }
                        float vy = vt != null ? vt.getYVelocity() : 0;
                        if (vt != null) vt.recycle();
                        vt = null;
                        if (axisLocked && !deadZone
                                && (Math.abs(totalDy) > dp(36) || Math.abs(vy) > dp(600))) {
                            commitSwipe(totalDy < 0 ? 1 : -1);
                        } else {
                            animateCoverBack();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void commitSwipe(int dir) {
        mLastDir = dir;
        final float target = dir > 0 ? -mCoverBox.getHeight() : mCoverBox.getHeight();
        mCoverBox.animate().translationY(target).setDuration(150).setInterpolator(BS4_F)
                .withEndAction(() -> {
                    mCoverBox.setTranslationY(0);
                    if (dir > 0) MusicController.get().next();
                    else MusicController.get().prev();
                }).start();
    }

    private void animateCoverBack() {
        mCoverBox.animate().translationY(0).setDuration(250)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
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

    /** 迷你进度条 ↔ 完整进度栈切换（星辰自有件口径：平时短条、点开全栈、闲置 3 秒缩回、暂停保持）。 */
    private void setScrubberExpanded(boolean expanded) {
        mScrubberExpanded = expanded;
        mLastScrubberTouchMs = System.currentTimeMillis();
        mScrubber.setVisibility(expanded ? View.VISIBLE : View.GONE);
        mMiniProgress.setVisibility(expanded ? View.GONE : View.VISIBLE);
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
        mFirstBind = false;

        // 歌名切换动画：出 fadeOut 150/bs4.c → 换字 → 入 fadeIn 200/bs4.e
        final String title = TextUtils.isEmpty(track.displayTitle()) ? "未在播放" : track.displayTitle();
        mTitle.animate().alpha(0f).setDuration(150).setInterpolator(BS4_C)
                .withEndAction(() -> {
                    mTitle.setText(title);
                    mTitle.animate().alpha(1f).setDuration(200).setInterpolator(BS4_E).start();
                }).start();
        mArtist.setText(track.displayArtist());
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
                    mCover.setScaleX(1f);
                    mCover.setScaleY(1f);
                    return;
                }
                // 切歌转场 400ms/bs4.e：入竖滑 0.5×尺寸＋缩放 0.94→1；出竖滑 0.5、缩放→0.96、alpha→0
                float h = mCoverBox.getHeight() > 0 ? mCoverBox.getHeight() : dp(320);
                mCoverOut.setImageBitmap(old);
                mCoverOut.setVisibility(View.VISIBLE);
                mCoverOut.setAlpha(1f);
                mCoverOut.setTranslationY(0);
                mCoverOut.setScaleX(1f);
                mCoverOut.setScaleY(1f);
                mCover.setImageBitmap(resource);
                mCover.setTranslationY(dir * 0.5f * h);
                mCover.setScaleX(0.94f);
                mCover.setScaleY(0.94f);
                mCover.setAlpha(1f);
                mCover.animate().translationY(0).scaleX(1f).scaleY(1f)
                        .setDuration(400).setInterpolator(BS4_E).start();
                mCoverOut.animate().translationY(-dir * 0.5f * h).scaleX(0.96f).scaleY(0.96f).alpha(0f)
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
        mBtnFav.setAlpha(mFavorite ? 0.45f : 0.30f);
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

    private void refreshChips() {
        String q = "";
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null && info.shortCode() != null && !info.shortCode().isEmpty()) {
                q = info.lossless ? info.codec : info.codec + " " + (info.bitrateKbps > 0 ? info.bitrateKbps + "kbps" : "");
                q = q.trim();
            }
        } catch (Throwable ignored) {
        }
        if (q.isEmpty()) q = MusicStore.qualityName(MusicController.get().getQuality());
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
        mBtnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        // 播停图标尺寸（瓜子：播放中 28dp／暂停 25dp，300ms 过渡）——以 padding 动画近似尺寸变化
        int pad = (int) dp(playing ? 6 : 7.5f);
        mBtnPlay.setPadding(pad, pad, pad, pad);
        float target = playing ? 1f : 0.9f;
        mCoverBox.animate().scaleX(target).scaleY(target).setDuration(300).start();
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
