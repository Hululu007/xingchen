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
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

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
import com.fongmi.android.tv.ui.dialog.MusicQueueDialog;
import com.fongmi.android.tv.ui.music.view.CoverAmbientView;
import com.fongmi.android.tv.ui.music.view.FadingCoverView;
import com.fongmi.android.tv.ui.music.view.MusicLyricStageView;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 全屏歌词页（独立页，照瓜子全屏歌词面构成＋gz-player-values 第八节）：
 * 封面氛围拉满整屏垫底（与播放页同一 CoverAmbientView 外壳）＋原封面整屏垫底
 * 向下溶入氛围（与播放页同一 FadingCoverView、同一份 512 位图同源派生）→
 * 歌词大字沉浸区铺满整屏（MusicLyricStageView，行可穿标题之下）→歌名行（右三裸键：
 * 队列／歌词样式／播放暂停）与歌手♡专辑行为覆盖层→底部只留逐行/逐字双筹＋
 * 一条发丝进度线（不可拖；本页不带音质筹/轨道/时间行，音质入口归播放页）。
 * 与播放页共用同一播放状态与歌词数据：同走 MusicController 单例（同一队列/进度）、
 * 同读 MusicLyricCache、先行歌词监听与切歌回调同路，不另起播放链。
 * 进页方向＝播放页左划（歌词页在播放页右侧）；回页走系统返回（铁律：不放自带返回箭头）。
 */
public class MusicLyricActivity extends AppCompatActivity implements MusicController.Listener {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicLyricActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private final Handler mTickHandler = new Handler(Looper.getMainLooper());
    private CoverAmbientView mAmbient;
    private FadingCoverView mCover;
    private TextView mTitle, mArtist, mAlbum;
    private ImageView mBtnQueue, mBtnStyle, mBtnPlay, mBtnFav;
    private MusicLyricStageView mLyrics;
    private TextView mChipLine, mChipWord;
    private ProgressBar mProgress;

    private MusicTrack mTrack;
    private String mTrackKey;
    private boolean mFavorite;
    private Boolean mPlayingUi;
    private boolean mFirstBind = true;
    private String mLyricChip = "无词";

    private final MusicLibrary.FavoritesListener mFavListener = () -> {
        if (mTrack != null) refreshFavorite(mTrack);
    };

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            MusicController ctl = MusicController.get();
            long pos = ctl.position(), dur = ctl.duration();
            mProgress.setProgress(dur > 0 ? (int) Math.min(1000L, pos * 1000L / Math.max(1L, dur)) : 0);
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
        setContentView(R.layout.activity_music_lyric);

        mAmbient = findViewById(R.id.lyricAmbient);
        mAmbient.refreshSettings();
        mTitle = findViewById(R.id.lyricTitle);
        mArtist = findViewById(R.id.lyricArtist);
        mAlbum = findViewById(R.id.lyricAlbum);
        mBtnQueue = findViewById(R.id.lyricBtnQueue);
        mBtnStyle = findViewById(R.id.lyricBtnStyle);
        mBtnPlay = findViewById(R.id.lyricBtnPlay);
        mBtnFav = findViewById(R.id.lyricBtnFav);
        mCover = findViewById(R.id.lyricCover);
        mLyrics = findViewById(R.id.lyricStage);
        mChipLine = findViewById(R.id.lyricChipLine);
        mChipWord = findViewById(R.id.lyricChipWord);
        mProgress = findViewById(R.id.lyricProgress);

        // 状态栏留白按系统嵌饰实给（不编数值）：只给标题行覆盖层，舞台与背景仍拉满整屏
        final View content = findViewById(R.id.lyricMeta);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        // 歌名 800 字重＋行高 24sp、歌手行高 18sp（与播放页页头同一口径；API 28+）
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            mTitle.setTypeface(android.graphics.Typeface.create(mTitle.getTypeface(), 800, false));
            mTitle.setLineHeight((int) dp(24));
            mArtist.setLineHeight((int) dp(18));
        }

        applyKeepScreenOn();
        wireActions();
        MusicController.get().bind(this);
        MusicController.get().addListener(this);
        MusicLibrary.addFavoritesListener(mFavListener);
        MusicTrack current = MusicController.get().current();
        if (current != null) bindTrack(current);
        updatePlayUi(MusicController.get().isPlaying());
        mTickHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        mTickHandler.removeCallbacks(mTick);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTickHandler.removeCallbacks(mTick);
        mTickHandler.post(mTick);
        // 与播放页同口径（转派接缝）：别处改选了歌词版本、回本页也要重读缓存，
        // 不靠切歌才刷新。
        if (mTrack != null) tryLoadLyric(mTrack);
    }

    @Override
    protected void onDestroy() {
        if (mEarlyTrack != null && mEarlyListener != null) {
            com.fongmi.android.tv.music.MusicRepository.clearEarlyLyricListener(mEarlyTrack, mEarlyListener);
        }
        mTickHandler.removeCallbacks(mTick);
        MusicLibrary.removeFavoritesListener(mFavListener);
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
        mBtnStyle.setOnClickListener(v -> MusicToolsSheet.showLyricStyleOnly(this, new MusicToolsSheet.Callback() {
            @Override
            public void onSettingsChanged() {
                mLyrics.reloadSettings();
                applyKeepScreenOn();
                if (mAmbient != null) mAmbient.refreshSettings();
            }

            @Override
            public void onOpenLyricPage() {
                // 已在全屏歌词页：样式面板里没有「全屏歌词」入口行（来源页才有），此回调不会被触发；
                // 万一被触发也无动作（不叠开第二个歌词页）
            }
        }));
        mBtnFav.setOnClickListener(v -> toggleFavorite());
        mBtnFav.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start();
            else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                    || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        // 歌手/专辑可点跳搜索（与播放页同路）
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
        // 点歌词行＝快进到那行（与播放页同口径）
        mLyrics.setListener(timeMs -> MusicController.get().seekTo(timeMs));
        // 底部双筹（逐行/逐字）都是歌词版本面板的入口（瓜子 §八底部构成，无音质筹）
        mChipLine.setOnClickListener(v -> showLyricVersions());
        mChipWord.setOnClickListener(v -> showLyricVersions());
    }

    /** 歌词筹：歌词版本面板（与播放页同口径，点选同走存档＋覆缓存＋换屏）。 */
    private void showLyricVersions() {
        if (mTrack == null) return;
        MusicLyric cur = MusicLyricCache.get(mTrack);
        MusicLyricVersionsDialog.show(this, mTrack, cur,
                version -> {
                    com.fongmi.android.tv.music.MusicRepository.saveManualLyricChoice(mTrack, version);
                    MusicLyricCache.put(mTrack, version.getLyric());
                    applyLyric(mTrack, version.getLyric());
                });
    }

    // ---------- 数据绑定（与播放页同源：切歌同步换名换封面、进度归零） ----------
    private static String trackKey(MusicTrack t) {
        return t == null ? "" : (t.id == null ? "" : t.id) + "|" + (t.title == null ? "" : t.title);
    }

    // 先行歌词监听（与播放页同一条：链消费到第一份可用词先上屏，不等整链收口）
    private MusicTrack mEarlyTrack;
    private java.util.function.Consumer<MusicLyric> mEarlyListener;

    private void registerEarlyLyric(final MusicTrack track) {
        if (mEarlyTrack != null && mEarlyListener != null
                && !com.fongmi.android.tv.music.MusicQueue.trackKey(mEarlyTrack)
                        .equals(com.fongmi.android.tv.music.MusicQueue.trackKey(track))) {
            com.fongmi.android.tv.music.MusicRepository.clearEarlyLyricListener(mEarlyTrack, mEarlyListener);
        }
        mEarlyTrack = track;
        mEarlyListener = lyric -> {
            if (lyric == null || !lyric.hasLyric()) return;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && mTrack == track) applyLyric(track, lyric);
            });
        };
        com.fongmi.android.tv.music.MusicRepository.setEarlyLyricListener(track, mEarlyListener);
    }

    private void bindTrack(MusicTrack track) {
        mTrack = track;
        registerEarlyLyric(track);
        String key = trackKey(track);
        boolean same = key.equals(mTrackKey);
        mTrackKey = key;
        if (same && !mFirstBind) return;
        mFirstBind = false;

        mTitle.setText(TextUtils.isEmpty(track.displayTitle()) ? "未在播放" : track.displayTitle());
        mArtist.setText(track.displayArtist());
        mArtist.setTextColor(firstArtist(track.displayArtist()) != null ? 0x73FFFFFF : 0x57FFFFFF);
        if (TextUtils.isEmpty(track.displayAlbum())) {
            mAlbum.setVisibility(View.GONE);
        } else {
            mAlbum.setVisibility(View.VISIBLE);
            mAlbum.setText(track.displayAlbum());
        }
        loadAmbientCover(track);
        refreshFavorite(track);
        updateLyricChips();
        mProgress.setProgress(0);
        mLyrics.setLines(null);
        tryLoadLyric(track);
        updatePlayUi(MusicController.get().isPlaying());
    }

    /**
     * 封面同源派生（与播放页同一套）：封面位图按播放页同尺寸（512）只取一份，
     * 同一份位图两处用——交氛围层当场派生糊底与取色（氛围由封面本体派生），
     * 同时喂整屏原封面层（FadingCoverView）向下溶入氛围；两边同图同裁切，
     * 不许各取各的。无封面地址走素垫图口径（与播放页 bindCover 同路）；
     * 有址加载失败两层都清成透明（占位令：失败不许变素图）。
     */
    private void loadAmbientCover(final MusicTrack track) {
        String url = track.cover;
        if (TextUtils.isEmpty(url)) {
            mAmbient.setCoverBitmap(null, url);
            ImgUtil.load(track.displayTitle(), "", mCover);
            return;
        }
        final String want = url;
        ImgUtil.load(url, 512, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (mTrack != track || !want.equals(mTrack.cover)) return;
                mAmbient.setCoverBitmap(resource, want);
                mCover.setImageBitmap(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                if (mTrack == track && want.equals(mTrack.cover)) {
                    mAmbient.setCoverBitmap(null, want);
                    mCover.setImageDrawable(null);
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
        mBtnFav.animate().alpha(mFavorite ? 0.45f : 0.30f).setDuration(300).start();
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

    /** 首位歌手（与播放页同一口径）：去空白后为空/—/未知歌手/未知艺术家 → null。 */
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

    /** 底部双筹当前态：与现词形态对应的那颗全亮，另一颗压暗（瓜子未抠到更多状态，不自创）。 */
    private void updateLyricChips() {
        if (mChipLine == null || mChipWord == null) return;
        boolean word = "逐字".equals(mLyricChip);
        boolean line = "逐行".equals(mLyricChip) || "纯文本".equals(mLyricChip);
        mChipWord.setAlpha(word ? 1f : 0.55f);
        mChipLine.setAlpha(line ? 1f : 0.55f);
    }

    private void tryLoadLyric(MusicTrack track) {
        MusicLyric lyric = MusicLyricCache.get(track);
        if (lyric == null || !lyric.hasLyric()) {
            mLyricChip = "无词";
            updateLyricChips();
            return;
        }
        applyLyric(track, lyric);
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
        // 筹面只留中文（与播放页同口径，2026-10-27 他亲令英文不上筹）：原始格式名
        // （AMLL/LRC/KRC 等）一律不上筹，AMLL 与带词时间的归逐字，其余按结构归档
        if ("AMLL".equals(lyric.format) || hasWords) mLyricChip = "逐字";
        else if (main != null && !main.isEmpty() && main.get(0) != null && main.get(0).getTimeMs() > 0) mLyricChip = "逐行";
        else if (main != null && !main.isEmpty()) mLyricChip = "纯文本";
        else mLyricChip = "无词";
        updateLyricChips();
    }

    private void updatePlayUi(boolean playing) {
        mBtnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        int toPad = Math.round(dp(playing ? 6 : 7.5f));
        mBtnPlay.setPadding(toPad, toPad, toPad, toPad);
        mPlayingUi = playing;
        mLyrics.setPlaying(playing);
        if (mAmbient != null) mAmbient.setAmbientPlaying(playing);
    }

    // ---------- MusicController.Listener ----------
    @Override
    public void onTrackChanged(MusicTrack track) {
        if (track != null) bindTrack(track);
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
    public void onCoverChanged(MusicTrack track) {
        // 同播放页：115 内嵌封面后到时只重派氛围底，不重走整页 bindTrack
        if (track != null && track == mTrack && mAmbient != null) loadAmbientCover(track);
    }

    @Override
    public void onAudioInfoChanged() {
        // 本页底部无音质筹（瓜子 §八构成），音质信息变化在本页无落点，不刷新任何控件
    }

    @Override
    public void onTrackFailed(MusicTrack track, int reasonRes) {
        // 服务侧已按自动跳歌策略处理，页面只跟随 onTrackChanged（与播放页同口径）
    }
}
