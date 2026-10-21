package com.fongmi.android.tv.ui.activity;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.SeekBar;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicRandomBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 第 10 项：随心听沉浸页（GZ ShuffleListenScreenKt 结构；队列来源链同首页 shufflePlay：
 * 当前队列 → 最近播放 → 收藏 → 已下载，都为空才提示无歌）。
 * 本页不放自己的返回箭头，走系统返回。
 */
public class MusicRandomActivity extends BaseActivity {

    private ActivityMusicRandomBinding binding;
    private ObjectAnimator discAnim;
    private boolean discStarted;
    private boolean finding;
    private boolean seeking;

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> showTrack(track));
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> updatePlaying(playing));
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> {
                if (seeking) return;
                binding.seekBar.setMax((int) duration);
                binding.seekBar.setProgress((int) position);
                binding.tvCur.setText(fmt(position));
                binding.tvDur.setText(fmt(duration));
            });
        }
    };

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicRandomActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicRandomBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 黑胶尺寸：外圈＝屏宽×0.7、上限 320dp（GZ 绝对 dp 未抠到）；内圈封面 0.68f、中心黑点 0.053f、fallback 图标 0.24f
        int sw = getResources().getDisplayMetrics().widthPixels;
        int d = (int) Math.min(sw * 0.7f, dp(320));
        ViewGroup.LayoutParams lp = binding.discWrap.getLayoutParams();
        lp.width = d;
        lp.height = d;
        binding.discWrap.setLayoutParams(lp);
        setSize(binding.ivDiscCover, (int) (d * 0.68f));
        setSize(binding.discDot, (int) (d * 0.053f));
        setSize(binding.ivDiscFallback, (int) (d * 0.24f));
        binding.btnPrev.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        binding.btnNext.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        binding.ivDiscFallback.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        discAnim = ObjectAnimator.ofFloat(binding.discWrap, "rotation", 0f, 360f);
        discAnim.setDuration(20000);
        discAnim.setInterpolator(new LinearInterpolator());
        discAnim.setRepeatCount(ObjectAnimator.INFINITE);
        binding.btnPrev.setOnClickListener(v -> MusicController.get().prev());
        binding.btnNext.setOnClickListener(v -> MusicController.get().next());
        binding.btnPlayPause.setOnClickListener(v -> MusicController.get().toggle());
        // 不合口味＝切下一首（星辰无偏好学习引擎，学习效果不假装）
        binding.btnDislike.setOnClickListener(v -> MusicController.get().next());
        binding.btnFav.setOnClickListener(v -> toggleFav());
        binding.tvRediscover.setOnClickListener(v -> buildQueue());
        binding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) binding.tvCur.setText(fmt(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                MusicController.get().seekTo(seekBar.getProgress());
            }
        });
        MusicController.get().addListener(listener);
        // 进页即按随机链起播（同首页随心听卡旧行为，只是落点改成本页）
        buildQueue();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(listener);
        if (discAnim != null) discAnim.cancel();
        super.onDestroy();
    }

    /** 随机队列来源链：当前队列 → 最近播放 → 收藏 → 已下载；都为空才提示无歌 */
    private void buildQueue() {
        setFinding(true);
        List<MusicTrack> queue = null;
        try {
            queue = MusicController.get().queue();
        } catch (Throwable ignored) {
        }
        if (queue != null && !queue.isEmpty()) {
            startRandom(new ArrayList<>(queue));
            return;
        }
        List<MusicTrack> recent = new ArrayList<>();
        try {
            recent = MusicStore.loadRecent();
        } catch (Throwable ignored) {
        }
        if (!recent.isEmpty()) {
            startRandom(recent);
            return;
        }
        MusicLibrary.favorites(list -> {
            if (list != null && !list.isEmpty()) {
                startRandom(new ArrayList<>(list));
                return;
            }
            MusicDownloadStore.all(downloads -> {
                if (downloads != null && !downloads.isEmpty()) {
                    startRandom(new ArrayList<>(downloads));
                } else {
                    setFinding(false);
                    Notify.show(R.string.music_no_track);
                }
            });
        });
    }

    private void startRandom(List<MusicTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0, getString(R.string.home_shuffle), "home", null);
    }

    private void showTrack(MusicTrack track) {
        setFinding(false);
        if (track == null) {
            binding.tvShuffleTitle.setText("");
            binding.ivDiscFallback.setVisibility(View.VISIBLE);
            binding.ivDiscCover.setImageDrawable(null);
            updateState();
            return;
        }
        binding.tvShuffleTitle.setText(track.displayTitle());
        if (track.cover == null || track.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(binding.ivDiscCover).clear(binding.ivDiscCover);
            } catch (Throwable ignored) {
            }
            binding.ivDiscCover.setImageDrawable(null);
            binding.ivDiscFallback.setVisibility(View.VISIBLE);
        } else {
            binding.ivDiscFallback.setVisibility(View.GONE);
            ImgUtil.loadCircle(track.displayTitle(), track.cover, binding.ivDiscCover);
        }
        refreshFavButton();
        updateState();
        updatePlaying(MusicController.get().isPlaying());
    }

    private void updatePlaying(boolean playing) {
        binding.btnPlayPause.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        binding.btnPlayPause.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
        binding.btnPlayPause.setContentDescription(playing ? "暂停" : "播放");
        if (playing) {
            if (!discStarted) {
                discAnim.start();
                discStarted = true;
            } else {
                discAnim.resume();
            }
        } else {
            if (discStarted) discAnim.pause();
        }
        updateState();
    }

    /** 状态文案三态：选歌中／正在播放／等待聆听 */
    private void updateState() {
        String state;
        if (finding || MusicController.get().current() == null) state = "选歌中";
        else state = MusicController.get().isPlaying() ? "正在播放" : "等待聆听";
        binding.tvShuffleState.setText(state);
    }

    private void setFinding(boolean on) {
        finding = on;
        binding.tvShuffleFinding.setVisibility(on ? View.VISIBLE : View.GONE);
        binding.tvShuffleTitle.setVisibility(on ? View.GONE : View.VISIBLE);
        binding.tvShuffleSub.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) updateState();
    }

    private void refreshFavButton() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null || cur.id == null) {
            binding.btnFav.setText("收藏这首");
            binding.btnFav.setTextColor(0xFFFFFFFF);
            return;
        }
        MusicLibrary.isFavorite(cur.id, fav -> {
            binding.btnFav.setText(fav ? "已收藏这首" : "收藏这首");
            binding.btnFav.setTextColor(fav ? 0xFFFA243C : 0xFFFFFFFF);
        });
    }

    private void toggleFav() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null || cur.id == null) return;
        MusicLibrary.isFavorite(cur.id, fav -> MusicLibrary.setFavorite(cur, !fav, this::refreshFavButton));
    }

    private void setSize(View v, int size) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = size;
        lp.height = size;
        v.setLayoutParams(lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }
}
