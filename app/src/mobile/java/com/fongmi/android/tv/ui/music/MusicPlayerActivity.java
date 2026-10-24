package com.fongmi.android.tv.ui.music;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.LyricVersion;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicPlayerRebuildBinding;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicLyricCache;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.RichLyrics;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicLyricVersionDialog;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.dialog.MusicToolsSheetDialog;
import com.fongmi.android.tv.utils.CoverColor;
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

import jp.wasabeef.glide.transformations.internal.FastBlur;

/**
 * 音乐播放页（批 1 重建，ui.music 新包）：骨架＝氛围背景层（糊底／流体双路径）＋主题容器
 * （PlayerPageSwipeLayout 页级竖滑切歌层）＋爱心粒子层；主题分发按 MusicPlayerSettings
 * 主题 id，本批只实现 classic，其余 id 暂落 classic 渲染（批 3 实现）。
 * 播放只走 MusicController（未绑定积压重放机制在 Controller 内，服务没就绪不直调、不崩）。
 */
public class MusicPlayerActivity extends BaseActivity implements MusicPlayerHost {

    public static final String THEME_CLASSIC = MusicPlayerSettings.THEME_CLASSIC;
    public static final String THEME_APPLE = MusicPlayerSettings.THEME_APPLE_MUSIC;
    public static final String THEME_IMMERSIVE = MusicPlayerSettings.THEME_COVER_IMMERSIVE;
    public static final String THEME_CD = MusicPlayerSettings.THEME_LUMINOUS_CD;
    public static final String THEME_SHELF = MusicPlayerSettings.THEME_RECORD_SHELF;

    private ActivityMusicPlayerRebuildBinding binding;
    private View themeRoot;
    private String themeId = THEME_CLASSIC;

    private ImageView coverView;
    private ImageView coverExitView;
    private int coverExitGen;
    private TextView titleView;
    private TextView artistView;
    private TextView albumView;
    private ImageView favHeartView;
    private ImageView playTopView;
    private CoverLyricView lyricView;
    private MusicScrubberView scrubberView;
    private TextView positionTopView;
    private TextView durationTopView;
    private TextView qualityValueView;
    private TextView lyricChipTextView;

    private boolean destroyed;
    private boolean playing;
    private boolean favorited;
    private boolean favoriteBusy;
    private MusicTrack lastBoundTrack;

    /** 后台播放分家（批 0 点名迁移，旧页 :517-533 同口径）：onStop 因音乐后台关暂停时置位，回页只续同一首。 */
    private boolean bgPausedMusic;
    private String bgPausedTrackKey;

    private int ambientColor = CoverColor.FALLBACK_COLOR;
    private int dominantColor = CoverColor.FALLBACK_COLOR;
    private ValueAnimator ambientAnimator;
    private Bitmap bgSoftBitmap;
    private String bgSoftUrl;
    private String bgAppliedUrl;
    private int lastSkipDirection = 1;

    private final List<LyricsLine> lyricMains = new ArrayList<>();
    private final List<String> lyricTrans = new ArrayList<>();
    private final List<String> lyricRomas = new ArrayList<>();
    private final List<LyricVersion> currentLyricVersions = new ArrayList<>();
    private String currentLyricKey;
    private String currentLyricFormat;
    private boolean lyricEmpty;
    private boolean lyricLoading;

    private static final android.view.animation.Interpolator INTERP_FOSI = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final android.view.animation.Interpolator INTERP_EMPH = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    /** 旧页 start() 原样语义：无 extras、无标志，普通 startActivity 进播放页。 */
    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicPlayerActivity.class));
    }

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> bindTrack(track));
        }

        @Override
        public void onTrackReady(MusicTrack track) {
            runOnUiThread(() -> paintFavoriteIcons());
        }

        @Override
        public void onPlayingChanged(boolean value) {
            runOnUiThread(() -> setPlaying(value));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> onProgressUi(position, duration));
        }

        @Override
        public void onAudioInfoChanged() {
            runOnUiThread(() -> refreshQualityValue());
        }

        @Override
        public void onKeyIssue(String platform, String detail) {
            runOnUiThread(() -> showKeyIssue(platform, detail, () -> {
                int idx = MusicController.get().index();
                if (idx >= 0) MusicController.get().playAt(idx);
            }));
        }
    };

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicPlayerRebuildBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicRepository.ensureDebugFake();
        // 冷进不崩：只经 MusicController 绑定，服务未就绪时 Controller 积压重放，本页不直调服务。
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        attachPageSwipe();
        applyTheme();
        bindTrack(MusicController.get().current());
        setPlaying(MusicController.get().isPlaying());
        refreshQualityValue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicController.get().removeListener(listener);
        MusicController.get().addListener(listener);
        setPlaying(MusicController.get().isPlaying());
        // 后台关暂停的回页续播（旧页原样迁移）：只有 onStop 亲手暂停的才续，且必须是同一首。
        if (bgPausedMusic) {
            bgPausedMusic = false;
            String key = bgPausedTrackKey;
            bgPausedTrackKey = null;
            try {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && key != null
                        && key.equals(trackKey(curBg))
                        && !MusicController.get().isPlaying()) {
                    MusicController.get().play();
                }
            } catch (Throwable ignored) {
            }
        }
        MusicTrack cur = MusicController.get().current();
        if (cur != lastBoundTrack && (cur == null || lastBoundTrack == null
                || !trackKey(cur).equals(trackKey(lastBoundTrack)))) {
            bindTrack(cur);
        } else if (cur != null && lyricEmpty && !lyricLoading) {
            loadLyric(cur, false);
        }
        String theme = MusicPlayerSettings.getTheme();
        if (!theme.equals(themeId)) applyTheme();
        else applyLyricStyle();
        applyKeepScreenOn();
    }

    @Override
    protected void onPause() {
        MusicController.get().removeListener(listener);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 后台播放分家（旧页 :517-533 原样迁移）：音乐只认音乐自己那份开关
        // （PlayerSetting.isMusicBackgroundOff，默认开＝常驻）；关时离开播放页暂停，
        // 页面没在关（isFinishing 为假）才记下是哪首，回页由 onResume 续同一首。
        bgPausedMusic = false;
        bgPausedTrackKey = null;
        try {
            if (PlayerSetting.isMusicBackgroundOff() && MusicController.get().isPlaying()) {
                MusicTrack curBg = MusicController.get().current();
                if (curBg != null && !isFinishing()) {
                    bgPausedTrackKey = trackKey(curBg);
                    bgPausedMusic = true;
                }
                MusicController.get().pause();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        if (ambientAnimator != null) ambientAnimator.cancel();
        cancelCoverScaleSpring();
        super.onDestroy();
    }

    private static String trackKey(MusicTrack track) {
        return track == null ? "" : String.valueOf(track.id) + "|" + String.valueOf(track.platform);
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

    /**
     * 主题分发：按 MusicPlayerSettings 主题 id 选渲染。本批只实现 classic；
     * 其余 id（cover_immersive/apple_music/luminous_cd/luminous_cd_dark/luminous_cd_auto/record_shelf）
     * 暂落 classic 渲染——批 3 实现（分支不发版，允许此过渡）。
     */
    private void applyTheme() {
        String theme = MusicPlayerSettings.getTheme();
        themeId = theme;
        binding.themeContainer.removeAllViews();
        // TODO 批 3 实现：cover_immersive/apple_music/luminous_cd(_dark/_auto)/record_shelf 各自渲染
        themeRoot = LayoutInflater.from(this).inflate(R.layout.music_player_theme_classic, binding.themeContainer, false);
        binding.themeContainer.addView(themeRoot);
        grabThemeViews();
        wireThemeViews();
        applyLyricStyle();
        feedLyricView();
        MusicTrack track = MusicController.get().current();
        bindTrackViews(track);
        setPlaying(playing);
        refreshQualityValue();
        applyFluidBg();
    }

    private void attachPageSwipe() {
        if (binding.themeContainer == null) return;
        binding.themeContainer.setTranslationY(0f);
        binding.themeContainer.setOnPageSwipeListener(next -> {
            lastSkipDirection = next ? 1 : -1;
            if (next) MusicController.get().next();
            else MusicController.get().prev();
        });
        if (binding.swipeHint != null) {
            binding.themeContainer.setSwipeHintView(binding.swipeHint,
                    getString(R.string.music_next), getString(R.string.music_prev));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id) {
        return themeRoot == null ? null : (T) themeRoot.findViewById(id);
    }

    private void grabThemeViews() {
        coverView = find(R.id.tpCover);
        coverExitView = find(R.id.tpCoverExit);
        titleView = find(R.id.tpTitle);
        artistView = find(R.id.tpArtist);
        albumView = find(R.id.tpAlbum);
        favHeartView = find(R.id.tpFavHeart);
        playTopView = find(R.id.tpPlayTop);
        lyricView = find(R.id.tpLyricView);
        scrubberView = find(R.id.tpScrubber);
        positionTopView = find(R.id.tpPositionTop);
        durationTopView = find(R.id.tpDurationTop);
        qualityValueView = find(R.id.tpQualityValue);
        lyricChipTextView = find(R.id.tpLyricChipText);
    }

    private void wireThemeViews() {
        // SongMeta 三键：菜单＝工具面板、队列＝MusicQueueDialog（已核：队列对话框不引用播放页，直接复用）、播放/暂停＝Controller.toggle()
        if (playTopView != null) playTopView.setOnClickListener(v -> MusicController.get().toggle());
        View menu = find(R.id.tpBtnMenu);
        if (menu != null) {
            menu.setOnClickListener(v -> showToolsSheet());
            attachPressScale(menu, 0.96f);
        }
        View queueTitle = find(R.id.tpBtnQueueTitle);
        if (queueTitle != null) {
            queueTitle.setOnClickListener(v -> MusicQueueDialog.show(this));
            attachPressScale(queueTitle, 0.96f);
        }
        if (playTopView != null) attachPressScale(playTopView, 0.96f);
        if (favHeartView != null) {
            favHeartView.setOnClickListener(v -> toggleFavorite());
            attachPressScale(favHeartView, 0.85f);
        }
        // 歌手／专辑可点：进歌手／专辑搜索（旧页同口径；无名时不可点、颜色走不可点档，见 bindTrackViews）
        if (artistView != null) artistView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String a = t == null ? null : t.displayArtist();
            if (a != null && !a.isEmpty()) {
                try {
                    com.fongmi.android.tv.ui.activity.MusicListActivity.startSearch(this, a);
                } catch (Throwable ignored) {
                }
            }
        });
        if (albumView != null) albumView.setOnClickListener(v -> {
            MusicTrack t = MusicController.get().current();
            String al = t == null ? null : t.album;
            if (al != null && !al.isEmpty()) {
                try {
                    com.fongmi.android.tv.ui.activity.MusicListActivity.startSearch(this, al);
                } catch (Throwable ignored) {
                }
            }
        });
        View coverWrap = find(R.id.tpCoverWrap);
        if (coverWrap != null) attachCoverGestures(coverWrap);
        if (scrubberView != null) {
            scrubberView.setOnScrubListener(new MusicScrubberView.OnScrubListener() {
                @Override
                public void onScrubbing(float fraction) {
                    setTopTimesAlpha(0.72f);
                    long dur = MusicController.get().duration();
                    if (positionTopView != null) positionTopView.setText(formatTime(Math.round(dur * fraction)));
                }

                @Override
                public void onScrubCommit(float fraction) {
                    setTopTimesAlpha(0f);
                    long dur = MusicController.get().duration();
                    if (dur > 0) MusicController.get().seekTo(Math.round(dur * fraction));
                }
            });
        }
        View chipQuality = find(R.id.tpChipQuality);
        if (chipQuality != null) {
            chipQuality.setOnClickListener(v -> showQualityDialog());
            attachPressScale(chipQuality, 0.96f);
        }
        View chipLyric = find(R.id.tpChipLyric);
        if (chipLyric != null) {
            chipLyric.setOnClickListener(v -> showToolsSheetLyric());
            attachPressScale(chipLyric, 0.96f);
        }
        if (lyricView != null) {
            // 点行＝seek 到该行行首（定案；行时间是原始时间轴，偏移只作用于显示侧当前行判定）
            lyricView.setOnLineClickListener((index, positionMs) ->
                    MusicController.get().seekTo(positionMs));
        }
    }

    /** 按压缩放（不吞点击）：按下缩、松开回。 */
    private void attachPressScale(View view, float scale) {
        if (view == null) return;
        view.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).setInterpolator(INTERP_FOSI).start();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(INTERP_FOSI).start();
            }
            return false;
        });
    }

    private void setTopTimesAlpha(float alpha) {
        if (positionTopView != null) {
            positionTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
        if (durationTopView != null) {
            durationTopView.animate().alpha(alpha).setDuration(150).setInterpolator(INTERP_FOSI).start();
        }
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ================= 播放态 =================

    private android.animation.ObjectAnimator playBreathAnimator;

    private void setPlaying(boolean value) {
        playing = value;
        if (playTopView == null) return;
        // 三键播放/暂停图标随状态切尺寸（§1：播放态 28dp／暂停态 25dp，300ms tween 用 padding 补间近似）：
        // 正在播＝显示暂停图标 25dp（padding 7.5dp）；已暂停＝显示播放图标 28dp（padding 6dp）。
        playTopView.setImageResource(value ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        int pad = Math.round((value ? 7.5f : 6f) * getResources().getDisplayMetrics().density);
        ValueAnimator padAnim = ValueAnimator.ofInt(playTopView.getPaddingLeft(), pad);
        padAnim.setDuration(300);
        padAnim.addUpdateListener(a -> {
            int p = (int) a.getAnimatedValue();
            playTopView.setPadding(p, p, p, p);
        });
        padAnim.start();
        // 暂停态呼吸（§1 playBreath 0.28→1.0、800ms）：仅暂停态跑，播放态停并复位
        if (playBreathAnimator != null) {
            playBreathAnimator.cancel();
            playBreathAnimator = null;
        }
        if (!value) {
            playTopView.setAlpha(0.28f);
            playBreathAnimator = android.animation.ObjectAnimator.ofFloat(playTopView, "alpha", 0.28f, 1.0f);
            playBreathAnimator.setDuration(800);
            playBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
            playBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
            playBreathAnimator.start();
        } else {
            playTopView.setAlpha(1f);
        }
    }

    private void onProgressUi(long position, long duration) {
        if (scrubberView != null && duration > 0) {
            scrubberView.setFraction(position / (float) duration);
            if (!scrubberView.isDragging() && positionTopView != null) {
                positionTopView.setText(formatTime(position));
            }
            if (durationTopView != null) durationTopView.setText(formatTime(duration));
        }
        if (lyricView != null) {
            lyricView.setPosition(position + MusicPlayerSettings.getLyricOffsetMs());
        }
    }

    // ================= 歌曲绑定 =================

    private void bindTrack(MusicTrack track) {
        lastBoundTrack = track;
        bindTrackViews(track);
        if (track == null) {
            if (lyricView != null) {
                lyricView.clearLines();
                lyricView.setEmptyText(getString(R.string.music_lyric_chip_none));
            }
            return;
        }
        loadLyric(track, false);
        refreshFavorite(track);
        refreshQualityValue();
    }

    private void bindTrackViews(MusicTrack track) {
        String newTitle = track == null ? getString(R.string.music_no_track) : track.displayTitle();
        String newArtist = track == null ? "" : track.displayArtist();
        if (track != null && com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(newArtist)) {
            newArtist = getString(R.string.music_unknown_artist);
        }
        if (titleView != null) titleView.setText(newTitle);
        if (artistView != null) {
            artistView.setText(newArtist);
            // 经典歌手色：可点（有歌手名）白 0.45／不可点 0.34（§1）
            artistView.setTextColor(newArtist == null || newArtist.isEmpty() ? 0x57FFFFFF : 0x73FFFFFF);
        }
        if (albumView != null) {
            String album = track == null ? null : track.album;
            if (album == null || album.isEmpty()) {
                // 专辑空则整行 GONE（§1；歌手行内的专辑段与分隔心一并藏，本布局专辑段即此视图，收藏心是独立收藏键不藏）
                albumView.setVisibility(View.GONE);
            } else {
                albumView.setText(album);
                albumView.setVisibility(View.VISIBLE);
                albumView.setTextColor(0x73FFFFFF);
            }
        }
        bindCovers(track);
    }

    /** 当前曲应显示的封面地址：自定义封面优先（本地相册选图），否则原封面（旧页同口径）。 */
    private String effectiveCover(MusicTrack track) {
        if (track == null) return null;
        String custom = MusicStore.getSongCover(MusicStore.songCoverKey(track));
        if (custom != null && !custom.isEmpty()) return custom;
        return track.cover;
    }

    private void loadIntoCover(ImageView view, String cover, String title) {
        if (view == null) return;
        if (cover == null || cover.isEmpty()) {
            // 没封面地址才上素图；有地址的加载失败也保持透明，不变素图（封面占位定案）
            view.setImageDrawable(new CoverTextDrawable(title, false,
                    28 * getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.load(title, cover, view);
        }
    }

    private void bindCovers(MusicTrack track) {
        String cover = effectiveCover(track);
        String title = track == null ? "" : track.displayTitle();
        // 出场层：切歌前把旧封面暂存（首绑旧层无图不建出场动画）——CoverTransitionSpec.Classic 出场侧
        boolean stagedExit = track != null && coverView != null && coverExitView != null && coverView.getDrawable() != null;
        if (stagedExit) {
            coverExitGen++;
            coverExitView.setImageDrawable(coverView.getDrawable());
            coverExitView.setVisibility(View.VISIBLE);
            coverExitView.setAlpha(1f);
            coverExitView.setScaleX(1f);
            coverExitView.setScaleY(1f);
            coverExitView.setTranslationY(0f);
        }
        loadIntoCover(coverView, cover, title);
        updateBackground(track);
        if (coverView != null && track != null) {
            // 入场（Classic：400ms、竖向滑移 0.5＋缩放 0.94→1，Emphasized 缓动近似瓜子分链口径，旧页同取法）
            int h = coverView.getHeight();
            float slide = (h > 0 ? h : 300 * getResources().getDisplayMetrics().density) * 0.5f;
            coverView.setAlpha(0f);
            coverView.setScaleX(0.94f);
            coverView.setScaleY(0.94f);
            coverView.setTranslationY(slide);
            coverView.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(400).setInterpolator(INTERP_EMPH).start();
            if (stagedExit) {
                final int gen = coverExitGen;
                coverExitView.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(-slide)
                        .setDuration(400).setInterpolator(INTERP_EMPH)
                        .withEndAction(() -> {
                            if (coverExitView != null && coverExitGen == gen) {
                                coverExitView.setVisibility(View.GONE);
                                coverExitView.setImageDrawable(null);
                            }
                        }).start();
            }
        }
    }

    // ================= 氛围背景（双路径） =================

    private void clearClassicRenderEffect() {
        if (binding == null || binding.ivBgBlur == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) binding.ivBgBlur.setRenderEffect(null);
    }

    /**
     * 背景双路径分流：流体开→FluidCoverAmbientView（只借底层机制，不改其内部）；
     * 流体关→BlurredCoverAmbient（§1：封面缩 64×64、模糊 24dp、四段罩、取色过渡 450ms）。
     * 切到无封面歌必须清掉旧模糊与旧色不留残留（D-12 行为承接）：本方法无封面分支先清图、
     * 清 RenderEffect、清罩、氛围色回兜底，再走流体分支时流体色也回兜底三色。
     */
    private void updateBackground(MusicTrack track) {
        String cover = effectiveCover(track);
        if (cover == null || cover.isEmpty()) {
            bgSoftBitmap = null;
            bgSoftUrl = null;
            bgAppliedUrl = null;
            if (binding.ivBgBlur != null) binding.ivBgBlur.setImageDrawable(null);
            clearClassicRenderEffect();
            if (binding.viewVeil != null) binding.viewVeil.setBackground(null);
            dominantColor = CoverColor.FALLBACK_COLOR;
            animateAmbient(CoverColor.ambientBackground(null));
            applyFluidBg();
            return;
        }
        ImgUtil.load(this, cover, 64, 64, new CustomTarget<Drawable>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                if (resource instanceof BitmapDrawable && ((BitmapDrawable) resource).getBitmap() != null) {
                    bgSoftBitmap = ((BitmapDrawable) resource).getBitmap();
                    bgSoftUrl = cover;
                }
                refreshBlurredAmbient();
                if (resource instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) resource).getBitmap();
                    if (bmp != null) {
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                MusicTrack c2 = MusicController.get().current();
                                if (c2 == null || !cover.equals(effectiveCover(c2))) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                applyFluidBg();
                            });
                        });
                    }
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (destroyed) return;
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cover.equals(effectiveCover(cur))) return;
                // 软底失败时从主封面现成图取色兜底（旧页同教训：不让背景永停兜底黑）
                if (coverView != null && coverView.getDrawable() instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) coverView.getDrawable()).getBitmap();
                    if (bmp != null) {
                        bgSoftBitmap = bmp;
                        bgSoftUrl = cover;
                        refreshBlurredAmbient();
                        Task.execute(() -> {
                            int ambient = CoverColor.ambientBackground(bmp);
                            int dominant = CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (destroyed) return;
                                dominantColor = dominant;
                                animateAmbient(ambient);
                                applyFluidBg();
                            });
                        });
                    }
                }
            }
        });
        refreshBlurredAmbient();
    }

    /** 糊底合成：64×64 小图拉满＋24dp 模糊＋四段取色罩，合成一张位图贴 ivBgBlur；流体开时不贴（流体层在上）。 */
    private void refreshBlurredAmbient() {
        if (binding == null || destroyed) return;
        if (MusicPlayerSettings.isFluidBg()) return;
        MusicTrack cur = MusicController.get().current();
        String cover = cur == null ? null : effectiveCover(cur);
        if (cover == null || bgSoftBitmap == null || !cover.equals(bgSoftUrl)) return;
        if (cover.equals(bgAppliedUrl) && binding.ivBgBlur.getDrawable() != null) return;
        View bgView = binding.ivBgBlur;
        bgView.post(() -> {
            if (binding == null || destroyed) return;
            int vw = bgView.getWidth();
            int vh = bgView.getHeight();
            if (vw <= 0 || vh <= 0) return;
            Bitmap src = bgSoftBitmap;
            String url = bgSoftUrl;
            int ambient = ambientColor;
            Task.execute(() -> {
                Bitmap out = buildBlurredAmbient(src, vw, vh, ambient);
                if (out == null) return;
                runOnUiThread(() -> {
                    if (binding == null || destroyed) return;
                    MusicTrack c = MusicController.get().current();
                    if (c == null || !url.equals(effectiveCover(c))) return;
                    if (MusicPlayerSettings.isFluidBg()) return;
                    binding.ivBgBlur.setImageDrawable(new BitmapDrawable(getResources(), out));
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        float r = 24f * getResources().getDisplayMetrics().density;
                        binding.ivBgBlur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
                    }
                    bgAppliedUrl = url;
                });
            });
        });
    }

    /**
     * 一层满幅糊底合成（后台线程）：底＝封面缩 64×64 拉满（API<31 位图层 FastBlur 25 回落；
     * API31+ 由视图层 RenderEffect 24dp 落地，位图层跳过），上盖四段氛围罩——
     * 停点 0/0.4/0.78/1、alpha 0.76/0.82/0.80/0.86、底色两端明度 +0.05/−0.04（§1）。
     */
    private static Bitmap buildBlurredAmbient(Bitmap src, int viewW, int viewH, int ambient) {
        if (src == null || src.isRecycled() || viewW <= 0 || viewH <= 0) return null;
        Bitmap work = src;
        boolean copied = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && src.getConfig() == Bitmap.Config.HARDWARE) {
            work = src.copy(Bitmap.Config.ARGB_8888, false);
            if (work == null) return null;
            copied = true;
        }
        try {
            Bitmap out = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            int smallH = Math.max(1, Math.round(64f * work.getHeight() / (float) work.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(work, 64, smallH, true);
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Bitmap blurred = FastBlur.blur(small, 25, true);
                if (blurred != null) small = blurred;
            }
            canvas.drawBitmap(small, null, new RectF(0, 0, viewW, viewH), paint);
            small.recycle();
            drawAmbientVeil(canvas, viewW, viewH, ambient);
            return out;
        } finally {
            if (copied) work.recycle();
        }
    }

    /** 四段氛围罩（§1）：停点 0/0.4/0.78/1；alpha 0.76/0.82/0.80/0.86；色＝取色经 toneAdjust，两端明度 +0.05/−0.04。 */
    private static void drawAmbientVeil(Canvas canvas, int viewW, int viewH, int ambient) {
        int base = CoverColor.toneAdjust(ambient);
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        float topV = Math.max(0.1f, Math.min(0.68f, hsv[2] + 0.05f));
        int topColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], topV});
        float bottomV = Math.max(0.1f, Math.min(0.68f, hsv[2] - 0.04f));
        int bottomColor = Color.HSVToColor(new float[]{hsv[0], hsv[1], bottomV});
        int[] alphas = {Math.round(0.76f * 255), Math.round(0.82f * 255), Math.round(0.80f * 255), Math.round(0.86f * 255)};
        int[] colors = {topColor, base, base, bottomColor};
        int[] argb = new int[4];
        for (int i = 0; i < 4; i++) {
            argb[i] = Color.argb(alphas[i], Color.red(colors[i]), Color.green(colors[i]), Color.blue(colors[i]));
        }
        Paint tint = new Paint();
        tint.setShader(new LinearGradient(0, 0, 0, viewH, argb, new float[]{0f, 0.4f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(new RectF(0, 0, viewW, viewH), tint);
    }

    /** 氛围取色过渡 450ms（§1）。 */
    private void animateAmbient(int to) {
        if (ambientAnimator != null) ambientAnimator.cancel();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, to);
        ambientAnimator.setDuration(450);
        ambientAnimator.setInterpolator(INTERP_FOSI);
        ambientAnimator.addUpdateListener(a -> {
            ambientColor = (int) a.getAnimatedValue();
            if (binding.fluidAmbient != null && binding.fluidAmbient.getVisibility() == View.VISIBLE) {
                binding.fluidAmbient.setColors(ambientColor, dominantColor, CoverColor.toneAdjust(ambientColor));
            }
        });
        ambientAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                // 合成时用的氛围色已旧：清合成标记按最终色重合成一次（不回调 animateAmbient，不成环）
                if (animation == ambientAnimator && !MusicPlayerSettings.isFluidBg()) {
                    bgAppliedUrl = null;
                    refreshBlurredAmbient();
                }
            }
        });
        ambientAnimator.start();
    }

    /**
     * 流体路径：开时流体层可见并喂取色三色、糊底合成停用；关时流体层隐藏、回糊底路径。
     * 速度：MusicPlayerSettings 已按 §0 存档（slow 0.6/normal 1.0/fast 2.5），但
     * FluidCoverAmbientView 没有速度设置 API（其漂移频率写死在 onDraw 内），本批只借不改其内部，
     * 速度倍率暂无处下发——差在此，待该视图开放 setSpeed 后在此接上。
     */
    private void applyFluidBg() {
        try {
            if (binding == null || binding.fluidAmbient == null) return;
            boolean on = MusicPlayerSettings.isFluidBg();
            binding.fluidAmbient.setVisibility(on ? View.VISIBLE : View.GONE);
            if (binding.viewVeil != null) binding.viewVeil.setVisibility(on ? View.GONE : View.VISIBLE);
            if (on) {
                binding.fluidAmbient.setColors(ambientColor, dominantColor, CoverColor.toneAdjust(ambientColor));
            } else {
                bgAppliedUrl = null;
                refreshBlurredAmbient();
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 封面手势 =================

    /**
     * 封面手势三态（§1 detectTapGestures 等价）：双击＝收藏切换＋爱心爆发＋封面缩放，
     * 长按＝开工具面板（与菜单键同一入口 showToolsSheet），单击在经典下无对照动作、不绑
     * （parity-diff 两文只抠到双击／长按的经典回调，点按回调未抠到）。
     * 切歌竖滑不在封面本体（页级 PlayerPageSwipeLayout，§1 同口径）。
     * X-10 行为承接：爱心爆发只在收藏状态真的变了才放——收藏切换走 MusicLibrary 异步回调，
     * 回调里比对切换前后状态，变了才 burst；缩放／震动是双击本身的反馈，不受此门影响。
     */
    private void attachCoverGestures(View wrap) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                MusicTrack track = MusicController.get().current();
                if (track == null) return true;
                float[] p = toOverlayPoint(wrap, e.getX(), e.getY());
                toggleFavorite(() -> {
                    // 收藏态已变（toggleFavorite 回调时 favorited 已翻）：收藏态 8 颗／取消态 5 颗（§1 爆发参数在 HeartsBurstView 内）
                    binding.heartsOverlay.burstCover(p[0], p[1], favorited);
                });
                cancelCoverScaleSpring();
                wrap.animate().scaleX(1.045f).scaleY(1.045f).setDuration(200)
                        .withEndAction(() -> startCoverScaleSpring(wrap))
                        .start();
                startRipple(wrap);
                try {
                    wrap.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                showToolsSheet();
            }
        });
        wrap.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
    }

    /** 涟漪 0→1、800ms（§1）：以封面缩放的轻微过冲近似呈现——封面本体无独立涟漪层，
     * 这里用一个贴在封面上的半透明白圆 View 自绘扩散，结束即移除。 */
    private void startRipple(View wrap) {
        if (!(wrap instanceof android.view.ViewGroup)) return;
        View ripple = new View(this);
        int size = Math.max(wrap.getWidth(), wrap.getHeight());
        if (size <= 0) return;
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        ripple.setLayoutParams(lp);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0x33FFFFFF);
        ripple.setBackground(drawable);
        ripple.setScaleX(0f);
        ripple.setScaleY(0f);
        ripple.setAlpha(1f);
        ((android.view.ViewGroup) wrap).addView(ripple);
        ripple.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(800)
                .withEndAction(() -> {
                    try {
                        ((android.view.ViewGroup) wrap).removeView(ripple);
                    } catch (Throwable ignored) {
                    }
                }).start();
    }

    private float[] toOverlayPoint(View anchor, float x, float y) {
        int[] a = new int[2];
        int[] o = new int[2];
        anchor.getLocationInWindow(a);
        binding.heartsOverlay.getLocationInWindow(o);
        return new float[]{a[0] - o[0] + x, a[1] - o[1] + y};
    }

    // ---- 双击封面缩放回落 spring（§1：阻尼 0.9／刚度 300，半隐式欧拉积分；树内无 androidx.dynamicanimation，见页级切歌回弹同注） ----
    private android.view.Choreographer.FrameCallback coverScaleSpringCallback;
    private float coverScaleSpringV;
    private long coverScaleSpringLastMs;

    private void cancelCoverScaleSpring() {
        if (coverScaleSpringCallback != null) {
            android.view.Choreographer.getInstance().removeFrameCallback(coverScaleSpringCallback);
            coverScaleSpringCallback = null;
        }
    }

    private void startCoverScaleSpring(View target) {
        cancelCoverScaleSpring();
        coverScaleSpringV = 0f;
        coverScaleSpringLastMs = android.os.SystemClock.uptimeMillis();
        coverScaleSpringCallback = new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                long nowMs = android.os.SystemClock.uptimeMillis();
                float dt = coverScaleSpringLastMs == 0 ? 0.016f
                        : Math.min(0.05f, (nowMs - coverScaleSpringLastMs) / 1000f);
                coverScaleSpringLastMs = nowMs;
                double k = 300d;
                double damp = 2d * 0.9d * Math.sqrt(k);
                float s = target.getScaleX();
                coverScaleSpringV += (float) ((k * (1f - s) - damp * coverScaleSpringV) * dt);
                s += coverScaleSpringV * dt;
                target.setScaleX(s);
                target.setScaleY(s);
                if (Math.abs(s - 1f) < 0.001f && Math.abs(coverScaleSpringV) < 0.01f) {
                    target.setScaleX(1f);
                    target.setScaleY(1f);
                    coverScaleSpringCallback = null;
                } else {
                    android.view.Choreographer.getInstance().postFrameCallback(this);
                }
            }
        };
        android.view.Choreographer.getInstance().postFrameCallback(coverScaleSpringCallback);
    }

    // ================= 收藏 =================

    private void refreshFavorite(MusicTrack track) {
        if (track == null || track.id == null) {
            favorited = false;
            paintFavoriteIcons();
            return;
        }
        MusicLibrary.isFavorite(track, fav -> {
            favorited = fav;
            paintFavoriteIcons();
        });
    }

    private void paintFavoriteIcons() {
        if (favHeartView == null) return;
        int icon = favorited ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart;
        favHeartView.setImageResource(icon);
        // 经典小爱心（旧页 K-4 口径）：已收藏实心白 0.45、未收藏描边白 0.30
        favHeartView.setImageTintList(ColorStateList.valueOf(favorited ? 0x73FFFFFF : 0x4DFFFFFF));
        favHeartView.setVisibility(View.VISIBLE);
    }

    private void toggleFavorite() {
        toggleFavorite(null);
    }

    /**
     * 收藏切换：未落位（isCurrentLoaded 为假）不接受（旧页必-2 守门）。
     * onChanged 只在状态真的变了才回调（X-10：双击爱心爆发挂在这里，无变化不放）。
     */
    private void toggleFavorite(Runnable onChanged) {
        MusicTrack track = MusicController.get().current();
        if (track == null || favoriteBusy || !MusicController.get().isCurrentLoaded()) return;
        favoriteBusy = true;
        boolean before = favorited;
        MusicLibrary.setFavorite(track, !favorited, () -> {
            favoriteBusy = false;
            favorited = !before;
            paintFavoriteIcons();
            Notify.show(favorited ? R.string.music_favorited : R.string.music_unfavorited);
            if (favorited != before && onChanged != null) onChanged.run();
        });
    }

    // ================= 音质筹 =================

    /**
     * 音质筹文案（任务 §9／施工图 §12 定案）：实测显示——无损只显编码名、有损显编码＋真实码率、
     * 采样率永不显、测不到整筹不显；数据走 MusicAudioInfo.describe()（地基保留件的现成口子）。
     * 注：旧页 realQualityText 后来改走 shortCode 短码，与本批任务口径冲突，本批按任务口径用 describe()。
     */
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

    private void refreshQualityValue() {
        String real = realQualityText();
        if (qualityValueView != null) {
            qualityValueView.setText(real == null ? "" : real);
            View chip = qualityValueView.getParent() instanceof View ? (View) qualityValueView.getParent() : null;
            if (chip != null && chip.getId() == R.id.tpChipQuality) {
                animateChipVisibility(chip, real != null);
            }
        }
    }

    /** 筹显隐透明动画 150ms（§1）。 */
    private void animateChipVisibility(View chip, boolean show) {
        if (show) {
            chip.setVisibility(View.VISIBLE);
            chip.setAlpha(0f);
            chip.animate().alpha(1f).setDuration(150).start();
        } else {
            chip.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> chip.setVisibility(View.GONE)).start();
        }
    }

    /** 音质筹点击＝音质面板（旧页同口径：点行切档＋重载＋刷新音质筹）。 */
    private void showQualityDialog() {
        if (isFinishing() || isDestroyed()) return;
        com.fongmi.android.tv.ui.dialog.MusicQualityPanelDialog.show(this, MusicController.get().current(),
                MusicController.get().getQuality(), quality -> {
                    if (MusicController.get().getQuality() != quality) {
                        MusicController.get().setQuality(quality);
                        MusicController.get().reloadQuality();
                        refreshQualityValue();
                        Notify.show("已切换" + MusicStore.qualityName(quality));
                    }
                });
    }

    /** 歌词筹文案（旧页 updateLyricChipLabel 同口径，GZ lyricChipLabel 五档）：AMLL 档＋逐字／逐行／纯文本／无词。 */
    private void updateLyricChipLabel(List<LyricsLine> mains) {
        if (lyricChipTextView == null) return;
        if ("AMLL".equals(currentLyricFormat)) {
            lyricChipTextView.setText(R.string.music_lyric_chip_amll);
            return;
        }
        int label;
        if (mains == null || mains.isEmpty()) label = R.string.music_lyric_chip_none;
        else {
            boolean word = false;
            boolean timed = false;
            for (LyricsLine line : mains) {
                if (line.hasWords()) word = true;
                if (line.getTimeMs() > 0) timed = true;
            }
            label = word ? R.string.music_lyric_chip_word : timed ? R.string.music_lyric_chip_line : R.string.music_lyric_chip_plain;
        }
        lyricChipTextView.setText(label);
    }

    // ================= 歌词（行级，逐字引擎归批 2） =================

    private void applyLyricStyle() {
        if (lyricView == null) return;
        lyricView.setFontSizeSp(MusicPlayerSettings.getCoverLyricFontSize());
        lyricView.setBlurCoefficient(MusicPlayerSettings.blurCoefficient());
        lyricView.setShowSub(MusicPlayerSettings.isShowTranslation() || MusicPlayerSettings.isShowRomanization());
    }

    /** 副行选词：翻译开取翻译、罗马音开取罗马音（都开时翻译优先，与旧页喂词口径一致的简化：副行只有一条槽）。 */
    private List<String> subLines() {
        if (MusicPlayerSettings.isShowTranslation()) return new ArrayList<>(lyricTrans);
        if (MusicPlayerSettings.isShowRomanization()) return new ArrayList<>(lyricRomas);
        return new ArrayList<>();
    }

    private void feedLyricView() {
        if (lyricView == null) return;
        lyricView.setLines(new ArrayList<>(lyricMains), subLines());
        lyricView.setPosition(MusicController.get().position() + MusicPlayerSettings.getLyricOffsetMs());
    }

    private void applyLyricData(List<LyricsLine> mains, List<String> trans, List<String> romas) {
        lyricMains.clear();
        lyricMains.addAll(mains);
        lyricTrans.clear();
        lyricTrans.addAll(trans);
        lyricRomas.clear();
        lyricRomas.addAll(romas);
        updateLyricChipLabel(mains);
        feedLyricView();
    }

    /**
     * 取词：走现成查找链（旧页同路）——已下载旁路词 → MusicLyricCache → MusicRepository.lyricRich
     * （内部走 player/lyrics/LyricsRepository）→ 纯文本回退 lyric()；解析 LyricsParser、翻译对齐 RichLyrics。
     * 切歌先清旧词防残留（旧页同时序口径的本批版）。
     */
    private void loadLyric(MusicTrack track, boolean force) {
        lyricLoading = true;
        lyricMains.clear();
        lyricTrans.clear();
        lyricRomas.clear();
        currentLyricVersions.clear();
        currentLyricFormat = null;
        currentLyricKey = null;
        if (!force && lyricView != null) lyricView.clearLines();
        updateLyricChipLabel(null);
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            String richFormat = null;
            String richKey = null;
            boolean richProvided = false;
            LxKeyIssue keyIssue = null;
            String sidecarLrc = MusicDownloadStore.readSidecarLyric(track);
            if (sidecarLrc != null && !sidecarLrc.trim().isEmpty()) {
                mains = LyricsParser.parseWordLyric(sidecarLrc);
                richProvided = !mains.isEmpty();
                if (richProvided) richKey = sidecarLrc.trim();
            }
            MusicLyric cached = force ? null : MusicLyricCache.get(track);
            try {
                MusicLyric rich = richProvided ? null : (cached != null ? cached : MusicRepository.lyricRich(track, force));
                if (rich != null) {
                    if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(rich.rawLrc);
                    }
                    List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.translation);
                    List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                            ? null : LyricsParser.parseWordLyric(rich.romanization);
                    List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                    trans.clear();
                    romas.clear();
                    for (RichLyrics.Line line : aligned) {
                        trans.add(line.translation);
                        romas.add(line.romanization);
                    }
                }
                richProvided = !mains.isEmpty();
                if (richProvided && rich != null) {
                    richFormat = rich.format;
                    richKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                    if (cached == null) MusicLyricCache.put(track, rich);
                }
                if (mains.isEmpty()) {
                    String lrc = MusicRepository.lyric(track, force);
                    if (lrc != null && !lrc.isEmpty()) {
                        mains = LyricsParser.parseWordLyric(lrc);
                        if (!mains.isEmpty()) MusicLyricCache.putPlain(track, lrc);
                    }
                }
                if (force && mains.isEmpty() && keyIssue == null) MusicLyricCache.remove(track);
            } catch (LxKeyIssue e) {
                keyIssue = e;
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            String resultFormat = richProvided ? richFormat : null;
            String resultKey = richProvided ? richKey : null;
            LxKeyIssue resultKeyIssue = keyIssue;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                currentLyricFormat = resultFormat;
                currentLyricKey = resultKey;
                applyLyricData(resultMains, resultTrans, resultRomas);
                lyricEmpty = resultMains.isEmpty();
                lyricLoading = false;
                if (resultMains.isEmpty() && lyricView != null) {
                    lyricView.setEmptyText(getString(R.string.music_lyric_chip_none));
                }
                if (force) {
                    Notify.show(resultMains.isEmpty() ? R.string.music_lyric_not_found : R.string.music_lyric_found);
                }
                if (resultKeyIssue != null) {
                    showKeyIssue(resultKeyIssue.getPlatform(), resultKeyIssue.getMessage(),
                            () -> loadLyric(track, true));
                }
                // 版本聚合（宿主接口 getLyricVersionCount/openLyricVersionDialog 的数据来源，旧页 B2 同路）
                Task.execute(() -> {
                    List<LyricVersion> versions;
                    try {
                        versions = MusicRepository.lyricRichAll(track, force);
                    } catch (Throwable ignored) {
                        versions = new ArrayList<>();
                    }
                    List<LyricVersion> resultVersions = versions;
                    runOnUiThread(() -> {
                        MusicTrack cur2 = MusicController.get().current();
                        if (cur2 == null || !cur2.equals(track)) return;
                        currentLyricVersions.clear();
                        currentLyricVersions.addAll(resultVersions);
                    });
                });
            });
        });
    }

    private void searchLyric() {
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        loadLyric(track, true);
    }

    private void showKeyIssue(String platform, String detail, Runnable onRetry) {
        if (isFinishing() || isDestroyed()) return;
        MusicTrack cur = MusicController.get().current();
        if (cur == null || !"lx".equals(cur.platform)) return;
        com.fongmi.android.tv.ui.dialog.LxKeyIssueDialog.show(this, platform, detail, onRetry);
    }

    // ================= MusicPlayerHost（工具面板宿主回调） =================

    @Override
    public void refreshCurrentCover() {
        if (binding == null || destroyed) return;
        bindCovers(MusicController.get().current());
    }

    @Override
    public int getLyricVersionCount() {
        return currentLyricVersions.size();
    }

    @Override
    public void openLyricVersionDialog() {
        MusicLyricVersionDialog.showWithSearch(this, MusicController.get().current(),
                new ArrayList<>(currentLyricVersions), currentLyricKey, this::applyLyricVersion,
                this::searchLyric);
    }

    /** 切版本：重走解析＋对齐＋喂词（与 loadLyric 同一口径），只换词数据不换曲目；点选即写盘（旧页 B2 同路）。 */
    private void applyLyricVersion(LyricVersion version) {
        if (version == null || version.getLyric() == null) return;
        MusicTrack track = MusicController.get().current();
        if (track == null) return;
        MusicRepository.saveManualLyricChoice(track, version);
        MusicLyric rich = version.getLyric();
        Task.execute(() -> {
            List<LyricsLine> mains = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<String> romas = new ArrayList<>();
            try {
                if (rich.rawLrc != null && !rich.rawLrc.isEmpty()) {
                    mains = LyricsParser.parseWordLyric(rich.rawLrc);
                }
                List<LyricsLine> transLines = rich.translation == null || rich.translation.isEmpty()
                        ? null : LyricsParser.parseWordLyric(rich.translation);
                List<LyricsLine> romaLines = rich.romanization == null || rich.romanization.isEmpty()
                        ? null : LyricsParser.parseWordLyric(rich.romanization);
                List<RichLyrics.Line> aligned = RichLyrics.align(mains, transLines, romaLines);
                for (RichLyrics.Line line : aligned) {
                    trans.add(line.translation);
                    romas.add(line.romanization);
                }
            } catch (Throwable ignored) {
            }
            List<LyricsLine> resultMains = mains;
            List<String> resultTrans = trans;
            List<String> resultRomas = romas;
            runOnUiThread(() -> {
                MusicTrack cur = MusicController.get().current();
                if (cur == null || !cur.equals(track)) return;
                currentLyricFormat = rich.format;
                currentLyricKey = rich.rawLrc == null ? null : rich.rawLrc.trim();
                applyLyricData(resultMains, resultTrans, resultRomas);
            });
        });
    }

    // ================= 工具面板 =================

    private void showToolsSheet() {
        showToolsSheet(false);
    }

    private void showToolsSheetLyric() {
        showToolsSheet(true);
    }

    private void showToolsSheet(boolean lyricPanel) {
        if (isFinishing() || isDestroyed()) return;
        try {
            MusicToolsSheetDialog.Listener listener = toolsSheetListener();
            if (lyricPanel) MusicToolsSheetDialog.showLyricPanel(this, listener);
            else MusicToolsSheetDialog.show(this, listener);
        } catch (Throwable ignored) {
        }
    }

    private MusicToolsSheetDialog.Listener toolsSheetListener() {
        return new MusicToolsSheetDialog.Listener() {
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

            @Override
            public void onAppearanceChanged() {
                applyFluidBg();
            }
        };
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            if (isFinishing() || isDestroyed()) return;
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
        if (isFinishing() || isDestroyed()) return;
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            Notify.show("正在查询大小…");
            final int checkedFinal = checked;
            MusicDownloadStore.probeQualitySizes(track, values, sizes -> {
                if (isFinishing() || isDestroyed()) return;
                List<String> opts = new ArrayList<>();
                for (int i = 0; i < values.length; i++) {
                    String name = MusicStore.qualityName(values[i]);
                    opts.add(sizes[i] > 0 ? name + " · " + com.fongmi.android.tv.music.MusicCache.formatSize(sizes[i]) : name);
                }
                MusicOptionsDialog.show(this, getString(R.string.music_set_download_quality), opts, checkedFinal,
                        idx -> MusicDownloadStore.downloadWithQuality(track, values[idx]));
            });
            return;
        }
        MusicDownloadStore.download(track);
    }
}
