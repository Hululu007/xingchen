package com.fongmi.android.tv.ui.activity;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.KaraokeLyricsView;
import com.fongmi.android.tv.ui.dialog.MusicFxDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐播放页：全主题共用骨架（封面糊底背景＋氛围色罩＋主题容器＋爱心粒子层），
 * 主题布局按 MusicStore.getPlayerTheme() 装进容器：classic / apple_music /
 * cover_immersive / luminous_cd / record_shelf。播放只走 MusicController 接口。
 */
public class MusicPlayerActivity extends BaseActivity {

    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_APPLE = "apple_music";
    public static final String THEME_IMMERSIVE = "cover_immersive";
    public static final String THEME_CD = "luminous_cd";
    public static final String THEME_SHELF = "record_shelf";

    private ActivityMusicPlayerBinding binding;

    // ===== 当前主题视图引用（applyTheme 时按 id 找回，缺失的主题留 null） =====
    private View themeRoot;
    private String themeId = THEME_CLASSIC;
    private ImageView coverView;
    private ImageView coverBackView;
    private TextView titleView;
    private TextView artistView;
    private TextView metaView;
    private SeekBar seekView;
    private SeekBar volumeView;
    private TextView positionView;
    private TextView durationView;
    private ImageView playView;
    private ImageView prevView;
    private ImageView nextView;
    private ImageView modeView;
    private ImageView queueView;
    private ImageView favBtnView;
    private KaraokeLyricsView lyricView;
    private TextView qualityValueView;
    private TextView lyricLineView;
    private View cdRoot;
    private View cdDisc;
    private TextView cdLyricView;
    private TextView cdLyricTransView;
    private RecyclerView shelfList;
    private ShelfAdapter shelfAdapter;

    // ===== 状态 =====
    private final List<LyricsLine> lyricMains = new ArrayList<>();
    private final List<String> lyricTrans = new ArrayList<>();
    private final List<String> lyricRomas = new ArrayList<>();
    private int lyricIndex = -1;
    private boolean seeking;
    private boolean favorited;
    private boolean playing;
    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int dominantColor = CoverColor.FALLBACK_COLOR;
    private ValueAnimator ambientAnimator;
    private ObjectAnimator cdAnimator;
    private boolean destroyed;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> setPlaying(playing));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }

        @Override
        public void onModeChanged(int mode) {
            runOnUiThread(() -> refreshModeIcon(mode));
        }

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> refreshQualityValue());
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicPlayerBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        applyTheme();
        bindTrack(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pageResumed("music");
        } catch (Throwable ignored) {
        }
        // 主题可能在别处被改（或工具面板回调漏达）：主题 id 变了就换装
        String theme = MusicStore.getPlayerTheme();
        if (theme != null && !theme.equals(themeId)) applyTheme();
        else {
            applyLyricStyle();
            if (lyricView != null) lyricView.setPlaying(playing);
        }
        applyKeepScreenOn();
        refreshVolume();
        updateRotations();
    }

    @Override
    protected void onPause() {
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this).pagePaused("music");
        } catch (Throwable ignored) {
        }
        if (cdAnimator != null && cdAnimator.isRunning()) cdAnimator.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        if (cdAnimator != null) cdAnimator.cancel();
        super.onDestroy();
    }

    private void applyKeepScreenOn() {
        try {
            if (MusicStore.isPlayerKeepScreenOn()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 主题装配 =================

    /** 按 MusicStore 主题 id 装布局（非法 id 回退 classic，与 MusicStore 口径一致再兜一层） */
    private void applyTheme() {
        String theme = MusicStore.getPlayerTheme();
        int layout;
        if (THEME_APPLE.equals(theme)) layout = R.layout.player_theme_apple_music;
        else if (THEME_IMMERSIVE.equals(theme)) layout = R.layout.player_theme_cover_immersive;
        else if (THEME_CD.equals(theme)) layout = R.layout.player_theme_luminous_cd;
        else if (THEME_SHELF.equals(theme)) layout = R.layout.player_theme_record_shelf;
        else {
            theme = THEME_CLASSIC;
            layout = R.layout.player_theme_classic;
        }
        themeId = theme;
        if (cdAnimator != null) {
            cdAnimator.cancel();
            cdAnimator = null;
        }
        binding.themeContainer.removeAllViews();
        themeRoot = LayoutInflater.from(this).inflate(layout, binding.themeContainer, false);
        binding.themeContainer.addView(themeRoot);
        grabThemeViews();
        wireThemeViews();
        applyLyricStyle();
        feedLyricView();
        // 换装后把当前歌曲的静态信息重刷一遍（封面走缓存，不重拉歌词）
        MusicTrack track = MusicController.get().current();
        bindTrackViews(track);
        setPlaying(playing);
        refreshModeIcon(MusicController.get().getMode());
        refreshQualityValue();
        refreshVolume();
    }

    private <T extends View> T find(int id) {
        return themeRoot == null ? null : themeRoot.findViewById(id);
    }

    private void grabThemeViews() {
        coverView = find(R.id.tpCover);
        coverBackView = find(R.id.tpCoverBack);
        titleView = find(R.id.tpTitle);
        artistView = find(R.id.tpArtist);
        metaView = find(R.id.tpMeta);
        seekView = find(R.id.tpSeek);
        volumeView = find(R.id.tpVolume);
        positionView = find(R.id.tpPosition);
        durationView = find(R.id.tpDuration);
        playView = find(R.id.tpPlay);
        prevView = find(R.id.tpPrev);
        nextView = find(R.id.tpNext);
        modeView = find(R.id.tpMode);
        queueView = find(R.id.tpQueue);
        favBtnView = find(R.id.tpBtnFav);
        lyricView = find(R.id.tpLyricView);
        qualityValueView = find(R.id.tpQualityValue);
        lyricLineView = find(R.id.tpLyricLine);
        cdRoot = find(R.id.tpCdRoot);
        cdDisc = find(R.id.tpCdDisc);
        cdLyricView = find(R.id.tpCdLyric);
        cdLyricTransView = find(R.id.tpCdLyricTrans);
        shelfList = find(R.id.tpShelfList);
    }

    private void wireThemeViews() {
        if (playView != null) playView.setOnClickListener(v -> MusicController.get().toggle());
        if (prevView != null) prevView.setOnClickListener(v -> MusicController.get().prev());
        if (nextView != null) nextView.setOnClickListener(v -> MusicController.get().next());
        if (modeView != null) modeView.setOnClickListener(v -> MusicController.get().cycleMode());
        if (queueView != null) queueView.setOnClickListener(v -> MusicQueueDialog.show(this));
        if (favBtnView != null) favBtnView.setOnClickListener(v -> toggleFavorite(v));

        // 封面圆角：经典沿用现有 28dp 裁剪口径，其余主题按各自版式
        if (coverView != null) {
            if (THEME_CD.equals(themeId)) setCircle(coverView);
            else setRounded(coverView, THEME_CLASSIC.equals(themeId) ? 28 : THEME_APPLE.equals(themeId) ? 20 : 24);
        }
        if (coverBackView != null) setRounded(coverBackView, 24);

        // 封面手势（G01–G03）：经典/Apple/封面卡片三套带大封面的主题共用
        View coverWrap = find(R.id.tpCoverWrap);
        if (coverWrap != null) attachCoverGestures(coverWrap);

        // 进度口径统一千分比（沿用已验口径：max=1000，拖完才 seek）
        if (seekView != null) {
            seekView.setMax(1000);
            seekView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    seeking = true;
                    swapSeekDrawable(seekBar, true);
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seeking = false;
                    swapSeekDrawable(seekBar, false);
                    long dur = MusicController.get().duration();
                    MusicController.get().seekTo(dur * seekBar.getProgress() / 1000);
                }
            });
        }
        wireVolume();

        // 经典主题专属：歌名行队列键/菜单键＋功能筹行
        View queueTitle = find(R.id.tpBtnQueueTitle);
        if (queueTitle != null) queueTitle.setOnClickListener(v -> MusicQueueDialog.show(this));
        View menu = find(R.id.tpBtnMenu);
        if (menu != null) menu.setOnClickListener(v -> showToolsSheet());
        View chipFav = find(R.id.tpChipFav);
        if (chipFav != null) chipFav.setOnClickListener(v -> toggleFavorite(v));
        View chipDownload = find(R.id.tpChipDownload);
        if (chipDownload != null) chipDownload.setOnClickListener(v -> downloadCurrent());
        View chipQuality = find(R.id.tpChipQuality);
        if (chipQuality != null) chipQuality.setOnClickListener(v -> showQualityDialog());
        View chipFx = find(R.id.tpChipFx);
        if (chipFx != null) chipFx.setOnClickListener(v -> MusicFxDialog.show(this));

        // 歌词视图接线：点行回调（经典 cover 模式点开全屏歌词页；唱片架点行 seek）
        if (lyricView != null) {
            if (THEME_CLASSIC.equals(themeId)) lyricView.setCoverMode(true);
            if (THEME_SHELF.equals(themeId)) {
                lyricView.setCoverMode(true);
                lyricView.setLightScheme(true);
            }
            lyricView.setOnLineClickListener((index, positionMs) -> {
                if (THEME_CLASSIC.equals(themeId)) openLyricsPage();
                else MusicController.get().seekTo(positionMs);
            });
        }

        // Apple 专属：歌词入口/音质入口＋运输键按压缩放（上/下一首 0.88、播放 0.9）
        View btnLyric = find(R.id.tpBtnLyric);
        if (btnLyric != null) btnLyric.setOnClickListener(v -> openLyricsPage());
        View btnQuality = find(R.id.tpBtnQuality);
        if (btnQuality != null) btnQuality.setOnClickListener(v -> showQualityDialog());
        if (THEME_APPLE.equals(themeId)) {
            attachPressScale(prevView, 0.88f);
            attachPressScale(nextView, 0.88f);
            attachPressScale(playView, 0.9f);
        }

        // 封面卡片：点当前歌词行开全屏歌词页
        if (lyricLineView != null) lyricLineView.setOnClickListener(v -> openLyricsPage());
        // 流光 CD：点歌词行开全屏歌词页
        if (cdLyricView != null) cdLyricView.setOnClickListener(v -> openLyricsPage());

        // 封面卡片：封面区高＝宽×0.65（COVER_IMMERSIVE_ASPECT，PlayerScreenKt:3919 用法）
        View immersiveWrap = find(R.id.tpCoverWrap);
        if (THEME_IMMERSIVE.equals(themeId) && immersiveWrap != null) {
            immersiveWrap.post(() -> {
                int w = immersiveWrap.getWidth();
                if (w <= 0) return;
                ViewGroup.LayoutParams lp = immersiveWrap.getLayoutParams();
                int h = Math.round(w * 0.65f);
                if (lp != null && lp.height != h) {
                    lp.height = h;
                    immersiveWrap.setLayoutParams(lp);
                }
            });
        }

        // 流光 CD：色板生效＋建旋转动画
        if (THEME_CD.equals(themeId)) {
            applyCdPalette();
            buildCdAnimator();
        }
        // 黑胶唱片架：横向队列陈列
        if (shelfList != null) {
            shelfList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            shelfAdapter = new ShelfAdapter();
            shelfList.setAdapter(shelfAdapter);
        }
    }

    private void setRounded(ImageView view, float radiusDp) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                int r = (int) (radiusDp * v.getResources().getDisplayMetrics().density);
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
    }

    private void setCircle(ImageView view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    /** 按压缩放（Apple 运输键口径）：按下缩、松开回，不吞点击 */
    private void attachPressScale(View view, float scale) {
        if (view == null) return;
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(260).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
            }
            return false;
        });
    }

    // ================= 封面手势（G01–G03） =================

    /**
     * 双击→收藏＋burstCover（大爆）；长按→工具面板；横滑（轴锁 1.5）→上一首/下一首。
     * 下滑退出不做（系统返回已覆盖）。
     */
    private void attachCoverGestures(View wrap) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // 单击封面开全屏歌词页：走 onSingleTapConfirmed（双击手势被识别时本回调不触发），
                // 与双击收藏天然互斥，不会误触（审计结论见 section-C 交互节）
                openLyricsPage();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                MusicTrack track = MusicController.get().current();
                if (track != null && !favorited) toggleFavorite(null);
                float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                binding.heartsOverlay.burstCover(p[0], p[1], true);
                wrap.animate().scaleX(1.045f).scaleY(1.045f).setDuration(200)
                        .withEndAction(() -> wrap.animate().scaleX(1f).scaleY(1f).setDuration(250).start())
                        .start();
                try {
                    wrap.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                showToolsSheet();
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                float threshold = 72 * getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    if (dx < 0) MusicController.get().next();
                    else MusicController.get().prev();
                    return true;
                }
                return false;
            }
        });
        wrap.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    /** 主题内坐标 → 爱心覆盖层坐标 */
    private float[] toOverlayPoint(View anchor, float x, float y) {
        int[] a = new int[2];
        int[] o = new int[2];
        anchor.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + x, a[1] - o[1] + y};
    }

    private float[] viewCenterInOverlay(View view) {
        int[] a = new int[2];
        int[] o = new int[2];
        view.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + view.getWidth() / 2f, a[1] - o[1] + view.getHeight() / 2f};
    }

    // ================= 歌曲绑定 =================

    private void bindTrack(MusicTrack track) {
        bindTrackViews(track);
        if (track == null) {
            // 无曲目：歌词视图等待态
            if (lyricView != null) lyricView.showWaiting();
            return;
        }
        loadLyric(track, false);
        refreshFavorite(track);
        refreshQualityValue();
        if (shelfAdapter != null) shelfAdapter.notifyDataSetChanged();
        scrollShelfToCurrent();
    }

    /** 只刷静态视图（歌名/歌手/封面/背景）；歌词与收藏由 bindTrack 另行刷新 */
    private void bindTrackViews(MusicTrack track) {
        if (titleView != null) titleView.setText(track == null ? getString(R.string.music_no_track) : track.displayTitle());
        if (artistView != null) artistView.setText(track == null ? "" : track.displayArtist());
        bindCovers(track);
        refreshMetaLine(track);
    }

    private void bindCovers(MusicTrack track) {
        String cover = track == null ? null : track.cover;
        String title = track == null ? "" : track.displayTitle();
        loadIntoCover(coverView, cover, title);
        loadIntoCover(coverBackView, cover, title);
        updateBackground(track);
        // 封面切换动画近似（GZ CoverTransitionSpec：经典 400ms、Apple 600ms；
        // View 动画近似其淡入＋enterScaleFrom 回正，确切 slide/scale 复合规格未逐项复刻，见 section-C）
        if (coverView != null && track != null) {
            long dur = THEME_APPLE.equals(themeId) ? 600 : 400;
            coverView.setAlpha(0.35f);
            coverView.setScaleX(0.96f);
            coverView.setScaleY(0.96f);
            coverView.animate().alpha(1f).scaleX(playing ? 1f : 0.9f).scaleY(playing ? 1f : 0.9f)
                    .setDuration(dur).start();
        }
    }

    private void loadIntoCover(ImageView view, String cover, String title) {
        if (view == null) return;
        if (cover == null || cover.isEmpty()) {
            // 没封面地址才上素图（素灰底＋歌名排字）；有地址的加载失败也保持透明，不变
            view.setImageDrawable(new CoverTextDrawable(title, false,
                    28 * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, view);
        }
    }

    /**
     * 背景层：封面按 64×64 小图请求（GZ BlurredCoverAmbient 口径），放大即糊；
     * 拿到图后取色刷氛围层与 CD 色板。无封面用 CoverColor 兜底色。
     */
    private void updateBackground(MusicTrack track) {
        String cover = track == null ? null : track.cover;
        if (cover == null || cover.isEmpty()) {
            binding.ivBgBlur.setImageDrawable(null);
            animateAmbient(CoverColor.ambientBackground(null));
            dominantColor = CoverColor.FALLBACK_COLOR;
            if (THEME_CD.equals(themeId)) applyCdPalette();
            return;
        }
        ImgUtil.load(this, cover, 64, 64, new CustomTarget<Drawable>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(cur.cover)) return;
                binding.ivBgBlur.setImageDrawable(resource);
                if (resource instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                if (THEME_CD.equals(themeId)) applyCdPalette();
                            });
                        });
                    }
                }
            }
        });
    }

    /** 氛围色 500ms 渐变过渡（GZ CoverTone 口径） */
    private void animateAmbient(int to) {
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, to);
        ambientAnimator.setDuration(500);
        ambientAnimator.addUpdateListener(a -> {
            ambientColor = (int) a.getAnimatedValue();
            binding.viewAmbient.setBackgroundColor(ambientColor);
        });
        ambientAnimator.start();
    }

    /** Apple 第三行：来源·实测音质；无实测整行隐藏（任务口径）。经典/其它主题走 sourceName 逻辑同旧版 */
    private void refreshMetaLine(MusicTrack track) {
        if (metaView == null) return;
        String real = realQualityText();
        if (track == null || real == null) {
            metaView.setVisibility(View.GONE);
            return;
        }
        String source = sourceName(track);
        metaView.setText(TextUtils.isEmpty(source) ? real : source + " · " + real);
        metaView.setVisibility(View.VISIBLE);
    }

    /** 来源标签：查插件名；查不到不显示，本地文件标识为已下载，py 源显示点播源名 */
    private String sourceName(MusicTrack track) {
        if (track == null || track.platform == null) return "";
        try {
            java.util.List<MusicSite> sites = MusicSourceManager.get().listSites();
            // FreeJS 多个插件共用一个 platform：先按 rawJson 的 _plugin 精确对归属，
            // 只按 platform 匹配永远命中排第一的插件（KW 的歌被标成 bilibili 的实证根因）
            String plugin = pluginOf(track);
            if (plugin != null) {
                for (MusicSite site : sites) {
                    if (site != null && plugin.equals(site.srcUrl)) return site.name == null ? "" : site.name;
                }
            }
            for (MusicSite site : sites) {
                if (site != null && track.platform.equals(site.platform)) return site.name == null ? "" : site.name;
            }
        } catch (Throwable ignored) {
        }
        if ("local".equals(track.platform)) return getString(R.string.music_downloaded);
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            return getString(R.string.music_device);
        }
        if (com.fongmi.android.tv.music.PyMusicSource.PLATFORM.equals(track.platform)) {
            return com.fongmi.android.tv.music.PyMusicSource.siteNameOf(track);
        }
        return "";
    }

    /** track.rawJson 里的 _plugin（FreeJS 曲目归属的插件 srcUrl），取不到返回 null */
    private static String pluginOf(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return null;
            return com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================= 歌词 =================

    private void loadLyric(MusicTrack track, boolean force) {
        // 切歌/开始取词先露加载态，取词未回不许先露空态（时序接线口径）；
        // 单行歌词数据（封面卡片/CD 当前行）照旧先清，避免旧歌词残留错位
        if (lyricView != null) lyricView.showLoading();
        lyricMains.clear();
        lyricTrans.clear();
        lyricRomas.clear();
        lyricIndex = -1;
        updateCurrentLyricTexts(MusicController.get().position());
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            try {
                MusicLyric rich = MusicRepository.lyricRich(track, force);
                if (rich != null) {
                    if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(rich.rawLrc);
                    }
                    List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                            ? null : LyricsParser.parseTimed(rich.translation);
                    List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                            ? null : LyricsParser.parseTimed(rich.romanization);
                    List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                    trans.clear();
                    romas.clear();
                    for (RichLyrics.Line line : aligned) {
                        trans.add(line.translation);
                        romas.add(line.romanization);
                    }
                }
                if (mains.isEmpty()) {
                    // rich 通道没拿到正文时回退旧纯文本链（口径与旧版 loadLyric 一致）
                    String lrc = MusicRepository.lyric(track, force);
                    if (lrc != null && !lrc.isEmpty()) mains = LyricsParser.parseWordLyric(lrc);
                }
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                applyLyricData(resultMains, resultTrans, resultRomas);
                // 取词确认为空才露空态
                if (resultMains.isEmpty() && lyricView != null) lyricView.showNoLyric();
                if (force) {
                    Notify.show(resultMains.isEmpty() ? R.string.music_lyric_not_found : R.string.music_lyric_found);
                }
            });
        });
    }

    /** 工具面板里的手动搜索歌词：强制跨插件找 */
    private void searchLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        Notify.show(R.string.music_lyric_searching);
        loadLyric(track, true);
    }

    private void applyLyricData(List<LyricsLine> mains, List<String> trans, List<String> romas) {
        lyricMains.clear();
        lyricMains.addAll(mains);
        lyricTrans.clear();
        lyricTrans.addAll(trans);
        lyricRomas.clear();
        lyricRomas.addAll(romas);
        lyricIndex = -1;
        feedLyricView();
        updateCurrentLyricTexts(MusicController.get().position());
    }

    private void feedLyricView() {
        if (lyricView == null) return;
        lyricView.setLines(new ArrayList<>(lyricMains), new ArrayList<>(lyricTrans), new ArrayList<>(lyricRomas));
        lyricView.setPlaying(playing);
        lyricView.updatePosition(MusicController.get().position());
    }

    /**
     * 歌词样式从 MusicStore 读并重应用（onResume 与工具面板 onLyricStyleChanged 时走）。
     * 组件现行 API 只覆盖主歌词（setPrimary）与译/音显隐（setShowSub）；
     * 字号/粗细/对齐/模糊/高级动效组件暂无 setter——读取点集中在此，TextView 型单行歌词
     * （封面卡片当前行、CD 歌词行）先吃对齐与粗细，其余待组件补 API 后在此一处接入。
     */
    private void applyLyricStyle() {
        int primary = 0;
        boolean showTrans = true;
        boolean showRoma = false;
        int align = 0;
        int weight = 0;
        float fontSize = 24f;
        int blur = 1;
        boolean advanced = true;
        try {
            primary = MusicStore.getLyricPrimary();
            showTrans = MusicStore.isShowTranslation();
            showRoma = MusicStore.isShowRomanization();
            align = MusicStore.getLyricAlign();
            weight = MusicStore.getLyricWeight();
            fontSize = MusicStore.getLyricFontSize();
            blur = MusicStore.getLyricBlur();
            advanced = MusicStore.isAdvancedFx();
        } catch (Throwable ignored) {
        }
        if (lyricView != null) {
            lyricView.setPrimary(primary);
            lyricView.setShowSub(showTrans, showRoma);
            // 本页歌词视图恒为封面模式（经典内嵌/唱片架卡片）：字号按 GZ 封面 22 对
            // 全屏 24 的差值口径减 2，全屏页用原值（见 MusicLyricsActivity）。
            lyricView.setMainTextSizeSp(Math.max(16f, fontSize - 2f));
            lyricView.setLyricAlign(align);
            lyricView.setLyricWeight(weight);
            lyricView.setBlurLevel(blur);
            lyricView.setAdvancedFx(advanced);
        }
        int gravity = align == 1 ? Gravity.CENTER : align == 2 ? Gravity.END : Gravity.START;
        Typeface tf = weight == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT_BOLD;
        if (lyricLineView != null) {
            lyricLineView.setGravity(gravity | Gravity.CENTER_VERTICAL);
            lyricLineView.setTypeface(tf);
        }
        if (cdLyricView != null) {
            cdLyricView.setGravity(gravity);
            cdLyricView.setTypeface(tf);
        }
    }

    /** 当前行文本刷新（封面卡片一行、流光 CD 一行＋译一行） */
    private void updateCurrentLyricTexts(long position) {
        int idx = lyricMains.isEmpty() ? -1 : LyricsParser.findLine(lyricMains, position);
        if (idx == lyricIndex) return;
        lyricIndex = idx;
        String main = "";
        String trans = "";
        if (idx >= 0 && idx < lyricMains.size()) {
            LyricsLine line = lyricMains.get(idx);
            main = line == null || line.getText() == null ? "" : line.getText();
            if (idx < lyricTrans.size() && lyricTrans.get(idx) != null) trans = lyricTrans.get(idx);
        }
        if (lyricLineView != null) lyricLineView.setText(main);
        if (cdLyricView != null) cdLyricView.setText(main);
        if (cdLyricTransView != null) {
            cdLyricTransView.setText(trans);
            cdLyricTransView.setVisibility(trans.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void openLyricsPage() {
        try {
            MusicLyricsActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    // ================= 进度/主控 =================

    private void onProgressUi(long position, long duration) {
        if (!seeking && duration > 0 && seekView != null) {
            seekView.setProgress((int) (position * 1000 / duration));
        }
        if (positionView != null) positionView.setText(fmt(position));
        if (durationView != null) durationView.setText(fmt(duration));
        if (lyricView != null) {
            lyricView.updatePosition(position);
            lyricView.setPlaying(playing);
        }
        updateCurrentLyricTexts(position);
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void setPlaying(boolean isPlaying) {
        playing = isPlaying;
        if (playView != null) {
            playView.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        }
        if (lyricView != null) lyricView.setPlaying(playing);
        // 经典封面暂停缩放 0.9（GZ 口径）
        if (coverView != null && (THEME_CLASSIC.equals(themeId) || THEME_APPLE.equals(themeId))) {
            float target = playing ? 1f : 0.9f;
            coverView.animate().scaleX(target).scaleY(target).setDuration(250).start();
        }
        updateRotations();
        if (shelfAdapter != null) shelfAdapter.notifyDataSetChanged();
    }

    private void refreshModeIcon(int mode) {
        if (modeView == null) return;
        int icon;
        switch (mode) {
            case MusicQueue.MODE_REPEAT_ONE:
                icon = R.drawable.ic_music_repeat_one;
                break;
            case MusicQueue.MODE_REPEAT_ALL:
                icon = R.drawable.ic_music_repeat;
                break;
            case MusicQueue.MODE_SHUFFLE:
                icon = R.drawable.ic_music_shuffle;
                break;
            default:
                icon = R.drawable.ic_music_sequential;
                break;
        }
        modeView.setImageResource(icon);
        int tint;
        if (THEME_CD.equals(themeId)) {
            tint = cdIsDark() ? 0xFFF2F2F7 : 0xFF282D36;
            boolean active = mode != MusicQueue.MODE_SEQUENTIAL;
            modeView.setImageTintList(ColorStateList.valueOf(active ? 0xFFFA243C : tint));
        } else {
            boolean active = mode != MusicQueue.MODE_SEQUENTIAL;
            modeView.setImageTintList(ColorStateList.valueOf(active ? 0xFFFA243C : 0xFF9AA7B8));
        }
        modeView.setContentDescription(MusicQueue.modeName(mode));
    }

    // ================= 流光 CD =================

    private boolean cdIsDark() {
        int surface = 0;
        try {
            surface = MusicStore.getCdSurface();
        } catch (Throwable ignored) {
        }
        if (surface == 1) return true;
        if (surface == 2) {
            int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        }
        return false;
    }

    /** CdPalette：浅 Pearl(base #F4F2EE/mist #E9EFF5/ink #282D36/muted #747D89)，深 Space(base #14161C/ink #F2F2F7/muted #8E96A3) */
    private void applyCdPalette() {
        if (cdRoot == null) return;
        boolean dark = cdIsDark();
        int base = dark ? 0xFF14161C : 0xFFF4F2EE;
        int mist = dark ? 0xFF20232C : 0xFFE9EFF5;
        int ink = dark ? 0xFFF2F2F7 : 0xFF282D36;
        int muted = dark ? 0xFF8E96A3 : 0xFF747D89;
        // 表面切换 500ms：根背景旧色→新色渐变（其余文字/盘面色即时跟随）
        try {
            android.graphics.drawable.Drawable old = cdRoot.getBackground();
            int from = old instanceof android.graphics.drawable.ColorDrawable
                    ? ((android.graphics.drawable.ColorDrawable) old).getColor() : base;
            ValueAnimator bgAnim = ValueAnimator.ofObject(new ArgbEvaluator(), from, base);
            bgAnim.setDuration(500);
            bgAnim.addUpdateListener(a -> cdRoot.setBackgroundColor((int) a.getAnimatedValue()));
            bgAnim.start();
        } catch (Throwable ignored) {
            cdRoot.setBackgroundColor(base);
        }
        if (titleView != null) titleView.setTextColor(ink);
        if (artistView != null) artistView.setTextColor(muted);
        if (cdLyricView != null) cdLyricView.setTextColor(ink);
        if (cdLyricTransView != null) cdLyricTransView.setTextColor(muted);
        if (positionView != null) positionView.setTextColor(muted);
        if (durationView != null) durationView.setTextColor(muted);
        if (seekView != null) {
            seekView.setProgressDrawable(getDrawable(dark
                    ? R.drawable.bg_player_seek_progress : R.drawable.bg_player_seek_progress_dark));
            seekView.setThumb(getDrawable(R.drawable.bg_player_seek_thumb));
            if (seekView.getThumb() != null) {
                seekView.getThumb().setTintList(ColorStateList.valueOf(ink));
            }
        }
        ColorStateList inkList = ColorStateList.valueOf(ink);
        if (prevView != null) prevView.setImageTintList(inkList);
        if (nextView != null) nextView.setImageTintList(inkList);
        if (playView != null) playView.setImageTintList(inkList);
        if (queueView != null) queueView.setImageTintList(inkList);
        // 盘面三段色：fresh 向主色混 0.22 / middle 0.12 / bottom 向 mist 侧混 0.08（CdPalette 梯度）
        if (cdDisc != null) {
            int fresh = blend(base, dominantColor, 0.22f);
            int middle = blend(base, dominantColor, 0.12f);
            int bottom = blend(mist, dominantColor, 0.08f);
            GradientDrawable disc = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{fresh, middle, bottom});
            disc.setShape(GradientDrawable.OVAL);
            disc.setStroke((int) (2 * getResources().getDisplayMetrics().density),
                    dark ? 0xFF3A3F4A : 0xFFD8D2C8);
            cdDisc.setBackground(disc);
        }
        refreshModeIcon(MusicController.get().getMode());
    }

    private static int blend(int from, int to, float ratio) {
        int r = (int) (android.graphics.Color.red(from) + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * ratio);
        int g = (int) (android.graphics.Color.green(from) + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * ratio);
        int b = (int) (android.graphics.Color.blue(from) + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * ratio);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** CD 自旋 16°/s：整圈 22500ms 线性无限；暂停冻结角度（animator.pause 保角） */
    private void buildCdAnimator() {
        if (cdDisc == null) return;
        cdAnimator = ObjectAnimator.ofFloat(cdDisc, View.ROTATION, 0f, 360f);
        cdAnimator.setDuration(22500);
        cdAnimator.setRepeatCount(ValueAnimator.INFINITE);
        cdAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        updateRotations();
    }

    private void updateRotations() {
        if (cdAnimator == null) return;
        if (playing && !destroyed) {
            if (cdAnimator.isPaused()) cdAnimator.resume();
            else if (!cdAnimator.isStarted()) cdAnimator.start();
        } else if (cdAnimator.isRunning()) {
            cdAnimator.pause();
        }
    }

    // ================= 黑胶唱片架 =================

    private void scrollShelfToCurrent() {
        if (shelfList == null) return;
        int idx = MusicController.get().index();
        if (idx >= 0) shelfList.post(() -> shelfList.scrollToPosition(idx));
    }

    /** 唱片架项：封面方卡（左）＋黑胶圆盘（右侧滑出）；当前项盘旋转 30°/s，其余静止；点按切歌 */
    private class ShelfAdapter extends RecyclerView.Adapter<ShelfAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float d = getResources().getDisplayMetrics().density;
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(new RecyclerView.LayoutParams((int) (196 * d), (int) (196 * d)));
            ImageView disc = new ImageView(parent.getContext());
            FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams((int) (150 * d), (int) (150 * d));
            discLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            disc.setLayoutParams(discLp);
            disc.setScaleType(ImageView.ScaleType.CENTER_CROP);
            setCircle(disc);
            root.addView(disc);
            ImageView cover = new ImageView(parent.getContext());
            FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams((int) (150 * d), (int) (150 * d));
            coverLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            cover.setLayoutParams(coverLp);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setElevation(6 * d);
            setRounded(cover, 12);
            root.addView(cover);
            return new Holder(root, cover, disc);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            List<MusicTrack> queue = MusicController.get().queue();
            if (position < 0 || position >= queue.size()) return;
            MusicTrack track = queue.get(position);
            String title = track.displayTitle();
            if (track.cover == null || track.cover.isEmpty()) {
                h.cover.setImageDrawable(new CoverTextDrawable(title, false,
                        12 * getResources().getDisplayMetrics().density));
                h.disc.setImageDrawable(new CoverTextDrawable(title, false,
                        75 * getResources().getDisplayMetrics().density));
            } else {
                ImgUtil.load(title, track.cover, h.cover);
                ImgUtil.load(title, track.cover, h.disc);
            }
            boolean current = position == MusicController.get().index();
            h.cover.setAlpha(current ? 1f : 0.75f);
            h.stopSpin();
            if (current && playing) h.startSpin();
            h.itemView.setOnClickListener(v -> MusicController.get().playAt(position));
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            h.stopSpin();
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            try {
                return MusicController.get().queue().size();
            } catch (Throwable ignored) {
                return 0;
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final ImageView disc;
            ObjectAnimator spin;

            Holder(View root, ImageView cover, ImageView disc) {
                super(root);
                this.cover = cover;
                this.disc = disc;
            }

            void startSpin() {
                stopSpin();
                spin = ObjectAnimator.ofFloat(disc, View.ROTATION, disc.getRotation(), disc.getRotation() + 360f);
                spin.setDuration(12000); // 30°/s
                spin.setRepeatCount(ValueAnimator.INFINITE);
                spin.setInterpolator(new android.view.animation.LinearInterpolator());
                spin.start();
            }

            void stopSpin() {
                if (spin != null) {
                    spin.cancel();
                    spin = null;
                }
            }
        }
    }

    // ================= 音量（Apple 主题，系统音量 STREAM_MUSIC） =================

    private AudioManager audioManager() {
        return (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    /** 进度拖动态换装：经典系 bg_player_seek_progress ↔ _active（0.42→0.78）；Apple seek6 ↔ _active（0.58→0.9）；CD 深色变体不换 */
    private void swapSeekDrawable(SeekBar bar, boolean active) {
        if (bar == null) return;
        try {
            if (THEME_APPLE.equals(themeId)) {
                bar.setProgressDrawable(getDrawable(active
                        ? R.drawable.bg_player_seek6_progress_active : R.drawable.bg_player_seek6_progress));
            } else if (!THEME_CD.equals(themeId)) {
                bar.setProgressDrawable(getDrawable(active
                        ? R.drawable.bg_player_seek_progress_active : R.drawable.bg_player_seek_progress));
            }
        } catch (Throwable ignored) {
        }
    }

    private void wireVolume() {
        if (volumeView == null) return;
        try {
            AudioManager am = audioManager();
            volumeView.setMax(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volumeView.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
            volumeView.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        try {
                            audioManager().setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void refreshVolume() {
        if (volumeView == null) return;
        try {
            volumeView.setProgress(audioManager().getStreamVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable ignored) {
        }
    }

    // ================= 功能：收藏/下载/音质 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            paintFavoriteIcons();
            return;
        }
        MusicLibrary.isFavorite(track.id, fav -> {
            favorited = fav;
            paintFavoriteIcons();
        });
    }

    /** 收藏态刷所有主题里出现的收藏图标（歌名行心、功能筹心、胶囊心、唱片架心共用 favBtnView/筹图标两处） */
    private void paintFavoriteIcons() {
        int icon = favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        int color = favorited ? 0xFFF2433D : 0xFFFFFFFF;
        if (favBtnView != null) {
            favBtnView.setImageResource(icon);
            favBtnView.setImageTintList(ColorStateList.valueOf(color));
        }
        ImageView chipIcon = find(R.id.tpChipFavIcon);
        if (chipIcon != null) {
            chipIcon.setImageResource(icon);
            chipIcon.setImageTintList(ColorStateList.valueOf(color));
        }
        // 经典歌名行的小爱心与工具筹共用 tpBtnFav 已在上面处理；歌名行收藏态另由 tpBtnFav 呈现
    }

    private void toggleFavorite(View fromView) {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favorited = !favorited;
            paintFavoriteIcons();
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
            if (favorited && fromView != null) {
                float[] p = viewCenterInOverlay(fromView);
                binding.heartsOverlay.burstFavorite(p[0], p[1]);
            }
        });
    }

    private void downloadCurrent() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)) {
            Notify.show(R.string.music_already_local);
            return;
        }
        if (MusicDownloadStore.isLocal(track)) {
            Notify.show(R.string.music_download_exists);
            return;
        }
        MusicDownloadStore.download(track);
    }

    private String realQualityText() {
        try {
            com.fongmi.android.tv.music.MusicAudioInfo info = MusicController.get().audioInfo();
            if (info != null) {
                String real = info.describe();
                if (real != null && !real.isEmpty()) return real;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 音质只显示服务实测（无损只显编码名、有损显编码＋真实码率）；取不到实测时
     * 经典筹只留「音质」标签不冒充、Apple 第三行整行隐藏——所选档永不进显示（铁律，沿用已验口径）。
     */
    private void refreshQualityValue() {
        String real = realQualityText();
        if (qualityValueView != null) {
            qualityValueView.setText(real == null ? getString(R.string.music_quality) : real);
        }
        refreshMetaLine(MusicController.get().current());
    }

    private void showQualityDialog() {
        List<String> opts = new ArrayList<>();
        int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
        for (int v : values) opts.add(MusicStore.qualityName(v));
        MusicOptionsDialog.show(this, getString(R.string.music_quality), opts, MusicController.get().getQuality(), idx -> {
            if (MusicController.get().getQuality() != values[idx]) {
                MusicController.get().setQuality(values[idx]);
                MusicController.get().reloadQuality();
                refreshQualityValue();
                Notify.show("已切换" + MusicStore.qualityName(values[idx]));
            }
        });
    }

    // ================= 工具面板 =================

    private void showToolsSheet() {
        try {
            MusicToolsSheetDialog.show(this, new MusicToolsSheetDialog.Listener() {
                @Override
                public void onThemeChanged() {
                    applyTheme();
                }

                @Override
                public void onLyricStyleChanged() {
                    applyLyricStyle();
                    feedLyricView();
                }

                @Override
                public void onSearchLyric() {
                    searchLyric();
                }

                @Override
                public void onAddToPlaylist() {
                    MusicTrack track = MusicController.get().current();
                    if (track != null) pickPlaylist(track);
                }

                @Override
                public void onDownload() {
                    downloadCurrent();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> {
                        if (name == null || name.trim().isEmpty()) return;
                        MusicLibrary.createPlaylist(name.trim(), p ->
                                MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                        Notify.show(added ? R.string.music_added : R.string.music_already_in)));
                    });
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        EditText et = new EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
