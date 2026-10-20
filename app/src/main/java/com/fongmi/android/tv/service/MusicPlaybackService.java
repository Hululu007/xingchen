package com.fongmi.android.tv.service;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicBitDepthProbe;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 独立音乐播放服务（音频专用）。
 * 只借 PlaybackService 的 MediaSession＋通知栏机制思路，服务独立起、播放器实例独立，
 * 与视频/听书播放互不串。队列、模式、断点、定时都归自己管。
 */
public class MusicPlaybackService extends MediaSessionService {

    public static final String LOCAL_BIND_ACTION = BuildConfig.APPLICATION_ID.concat(".MUSIC_LOCAL_BIND");

    /** 定时关闭 */
    public static final int SLEEP_OFF = 0;
    public static final int SLEEP_AFTER_CURRENT = 1;
    public static final int SLEEP_15 = 2;
    public static final int SLEEP_30 = 3;
    public static final int SLEEP_60 = 4;
    public static final int SLEEP_90 = 5;

    private static volatile MusicPlaybackService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    @Nullable
    public static MusicPlaybackService get() {
        return instance;
    }

    public interface ServiceListener {
        default void onTrackChanged(MusicTrack track) {
        }

        default void onPlayingChanged(boolean playing) {
        }

        default void onProgress(long position, long duration) {
        }

        default void onQueueChanged() {
        }

        default void onModeChanged(int mode) {
        }

        /** 当前音频真实参数变化（音质格用它显示；null=取不到，回退所选档名） */
        default void onAudioInfoChanged() {
        }

        default void onSleepChanged(int sleep) {
        }
    }

    private final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MusicQueue queue = new MusicQueue();

    private MediaSession session;
    private ExoPlayer player;
    private int sleep = SLEEP_OFF;
    private Runnable sleepTask;
    private boolean preparing;
    private volatile MusicAudioInfo audioInfo;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        queue.setMode(MusicStore.getMode());
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(playerListener);
        session = new MediaSession.Builder(this, player).build();
        session.setSessionActivity(buildPlayerIntent());
        setMediaNotificationProvider(new DefaultMediaNotificationProvider.Builder(this).build());
        restoreBreakpoint();
        startTicker();
        SpiderDebug.log("music", "service onCreate");
    }

    private PendingIntent buildPlayerIntent() {
        try {
            Intent intent = new Intent(this, Class.forName("com.fongmi.android.tv.ui.activity.MusicPlayerActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (LOCAL_BIND_ACTION.equals(intent != null ? intent.getAction() : null)) return binder;
        return super.onBind(intent);
    }

    @Override
    public void onDestroy() {
        saveBreakpoint();
        cancelSleep();
        main.removeCallbacksAndMessages(null);
        listeners.clear();
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.removeListener(playerListener);
            player.release();
            player = null;
        }
        instance = null;
        super.onDestroy();
        SpiderDebug.log("music", "service onDestroy");
    }

    // ================= 对外控制 =================

    public void addListener(ServiceListener l) {
        listeners.add(l);
    }

    public void removeListener(ServiceListener l) {
        listeners.remove(l);
    }

    public MusicQueue getQueue() {
        return queue;
    }

    public MusicTrack current() {
        return queue.current();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public long position() {
        try {
            return player == null ? 0 : Math.max(0, player.getCurrentPosition());
        } catch (Throwable t) {
            return 0;
        }
    }

    public long duration() {
        try {
            long d = player == null ? C.TIME_UNSET : player.getDuration();
            if (d == C.TIME_UNSET || d <= 0) {
                MusicTrack t = queue.current();
                return t == null ? 0 : t.duration;
            }
            return d;
        } catch (Throwable t) {
            return 0;
        }
    }

    public boolean hasQueue() {
        return !queue.isEmpty();
    }

    /** 播队列 */
    public void playQueue(List<MusicTrack> tracks, int index) {
        queue.setTracks(tracks, index);
        listeners.forEach(ServiceListener::onQueueChanged);
        prepareTrack(queue.getIndex(), 0, true);
    }

    /** 播队列并从指定位置起（断点续播用） */
    public void playQueueFrom(List<MusicTrack> tracks, int index, long position) {
        queue.setTracks(tracks, index);
        listeners.forEach(ServiceListener::onQueueChanged);
        prepareTrack(queue.getIndex(), Math.max(0, position), true);
    }

    /** 播指定序号 */
    public void playAt(int index) {
        if (queue.moveTo(index)) prepareTrack(index, 0, true);
    }

    public void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    public void play() {
        if (player == null) return;
        if (queue.current() == null) return;
        // 没准备过（断点恢复态）则先准备
        if (player.getPlaybackState() == Player.STATE_IDLE) {
            prepareTrack(queue.getIndex(), MusicStore.loadPosition(), true);
            return;
        }
        player.play();
    }

    public void pause() {
        if (player != null) player.pause();
        saveBreakpoint();
    }

    public void next() {
        int n = queue.nextIndex();
        if (n >= 0) prepareTrack(n, 0, true);
    }

    public void prev() {
        int p = queue.prevIndex();
        if (p >= 0) prepareTrack(p, 0, true);
    }

    public void seekTo(long ms) {
        if (player != null) player.seekTo(Math.max(0, ms));
    }

    public void setMode(int mode) {
        queue.setMode(mode);
        MusicStore.setMode(mode);
        listeners.forEach(l -> l.onModeChanged(mode));
    }

    public void cycleMode() {
        int m = queue.getMode();
        setMode(m >= MusicQueue.MODE_SHUFFLE ? MusicQueue.MODE_SEQUENTIAL : m + 1);
    }

    public void removeAt(int i) {
        boolean wasCurrent = i == queue.getIndex();
        queue.removeAt(i);
        listeners.forEach(ServiceListener::onQueueChanged);
        if (queue.isEmpty()) {
            stopPlayback();
        } else if (wasCurrent) {
            prepareTrack(queue.getIndex(), 0, true);
        }
    }

    public void move(int from, int to) {
        queue.move(from, to);
        listeners.forEach(ServiceListener::onQueueChanged);
    }

    public void clearQueue() {
        queue.clear();
        MusicStore.clearBreakpoint();
        listeners.forEach(ServiceListener::onQueueChanged);
        stopPlayback();
    }

    public void insertNext(MusicTrack track) {
        queue.insertNext(track);
        listeners.forEach(ServiceListener::onQueueChanged);
    }

    /** 加到播放队尾 */
    public void appendTrack(MusicTrack track) {
        queue.append(track);
        listeners.forEach(ServiceListener::onQueueChanged);
    }

    /** 倍速：1.0 正常，其余 0.5/0.75/1.25/1.5/2.0；持久化，下首歌照样用 */
    public void setSpeed(float speed) {
        if (speed < 0.25f) speed = 0.25f;
        if (speed > 4f) speed = 4f;
        MusicStore.setSpeed(speed);
        if (player != null) player.setPlaybackSpeed(speed);
    }

    public float getSpeed() {
        return MusicStore.getSpeed();
    }

    /** 音质切换后：当前歌按新音质重取直链，进度保留 */
    public void reloadCurrent() {
        int idx = queue.getIndex();
        if (idx < 0 || player == null) return;
        prepareTrack(idx, position(), isPlaying());
    }

    private void stopPlayback() {
        cancelSleep();
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        listeners.forEach(l -> l.onTrackChanged(null));
        listeners.forEach(l -> l.onPlayingChanged(false));
    }

    // ================= 定时关闭 =================

    public int getSleep() {
        return sleep;
    }

    public void setSleep(int mode) {
        cancelSleep();
        sleep = mode;
        if (mode == SLEEP_15) armSleep(15 * 60 * 1000L);
        else if (mode == SLEEP_30) armSleep(30 * 60 * 1000L);
        else if (mode == SLEEP_60) armSleep(60 * 60 * 1000L);
        else if (mode == SLEEP_90) armSleep(90 * 60 * 1000L);
        listeners.forEach(l -> l.onSleepChanged(mode));
    }

    private void armSleep(long delayMs) {
        sleepTask = () -> {
            sleep = SLEEP_OFF;
            listeners.forEach(l -> l.onSleepChanged(SLEEP_OFF));
            pause();
            stopSelf();
        };
        main.postDelayed(sleepTask, delayMs);
    }

    private void cancelSleep() {
        if (sleepTask != null) {
            main.removeCallbacks(sleepTask);
            sleepTask = null;
        }
    }

    public static String sleepName(int s) {
        switch (s) {
            case SLEEP_AFTER_CURRENT:
                return "播完这首";
            case SLEEP_15:
                return "15 分钟";
            case SLEEP_30:
                return "30 分钟";
            case SLEEP_60:
                return "60 分钟";
            case SLEEP_90:
                return "90 分钟";
            default:
                return "关闭";
        }
    }

    // ================= 均衡器：调系统均衡器面板 =================

    /**
     * 均衡器走系统均衡器面板跳转（AudioEffect 标准口）。
     * 不自己造 Equalizer：各机型原生均衡器支持程度不一，调系统面板最稳，
     * 作用域是当前音频会话，关 App 后系统侧保留用户调的曲线。
     */
    public boolean openEqualizer(Activity activity) {
        try {
            if (player == null || activity == null) return false;
            Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.getAudioSessionId());
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
            intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 真实音质参数 =================

    /** 当前音频的真实参数（编码/码率/采样率/位深）；还没选轨或取不到时为 null */
    public MusicAudioInfo audioInfo() {
        return audioInfo;
    }

    /** 从当前选中的音频轨 Format 采集真实参数；变了才通知＋（本地无损时）补查位深 */
    private void updateAudioInfo() {
        if (player == null) return;
        MusicAudioInfo info = null;
        try {
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (info != null) break;
                for (int i = 0; i < group.length; i++) {
                    if (!group.isTrackSelected(i)) continue;
                    Format f = group.getTrackFormat(i);
                    if (f != null && f.sampleMimeType != null && f.sampleMimeType.startsWith("audio/")) {
                        info = toAudioInfo(f);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            info = null;
        }
        String oldSig = audioInfo == null ? "" : audioInfo.signature();
        String newSig = info == null ? "" : info.signature();
        if (newSig.equals(oldSig)) return;
        audioInfo = info;
        listeners.forEach(ServiceListener::onAudioInfoChanged);
        if (info != null) probeBitDepth(info);
    }

    private MusicAudioInfo toAudioInfo(Format f) {
        MusicAudioInfo info = new MusicAudioInfo();
        info.codec = codecName(f.sampleMimeType);
        info.lossless = isLossless(f.sampleMimeType);
        int bps = f.averageBitrate > 0 ? f.averageBitrate : (f.bitrate > 0 ? f.bitrate : f.peakBitrate);
        if (bps > 0) info.bitrateKbps = (bps + 500) / 1000;
        if (f.sampleRate > 0) info.sampleRateHz = f.sampleRate;
        return info;
    }

    private static String codecName(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "audio/flac":
                return "FLAC";
            case "audio/mpeg":
            case "audio/mpeg-L1":
            case "audio/mpeg-L2":
                return "MP3";
            case "audio/mp4a-latm":
            case "audio/aac":
                return "AAC";
            case "audio/vorbis":
                return "OGG";
            case "audio/opus":
                return "Opus";
            case "audio/raw":
            case "audio/wav":
            case "audio/x-wav":
                return "WAV";
            case "audio/alac":
            case "audio/x-alac":
                return "ALAC";
            case "audio/ape":
            case "audio/x-ape":
                return "APE";
            case "audio/ac3":
                return "AC3";
            case "audio/eac3":
                return "E-AC3";
            case "audio/true-hd":
                return "TrueHD";
            case "audio/dts":
            case "audio/vnd.dts":
            case "audio/dts-hd":
                return "DTS";
            case "audio/mp4":
                return "MP4";
            default:
                return "";
        }
    }

    private static boolean isLossless(String mime) {
        if (mime == null) return false;
        switch (mime) {
            case "audio/flac":
            case "audio/raw":
            case "audio/wav":
            case "audio/x-wav":
            case "audio/alac":
            case "audio/x-alac":
            case "audio/ape":
            case "audio/x-ape":
            case "audio/true-hd":
                return true;
            default:
                return false;
        }
    }

    /**
     * 位深只对本地文件实查（FLAC/WAV 文件头）；在线流拿不到就不填——
     * bitDepth 保持 -1、master 保持 false。查实 24 位以上才改 master。
     */
    private void probeBitDepth(final MusicAudioInfo info) {
        if (info == null || !info.lossless || info.bitDepth >= 0) return;
        MusicTrack track = queue.current();
        if (track == null || track.localPath == null || track.localPath.isEmpty()) return;
        File file = new File(track.localPath);
        if (!file.isFile()) return;
        Task.execute(() -> {
            int bd = MusicBitDepthProbe.probe(file);
            if (bd <= 0) return;
            App.post(() -> {
                if (audioInfo != info) return; // 已经换歌，旧结果作废
                info.bitDepth = bd;
                info.master = info.lossless && bd >= 24;
                listeners.forEach(ServiceListener::onAudioInfoChanged);
            });
        });
    }

    // ================= 播放实现 =================

    private void prepareTrack(int index, long startPosition, boolean autoplay) {
        MusicTrack track = queue.get(index);
        if (track == null || player == null || preparing) return;
        preparing = true;
        int quality = MusicStore.getQuality();
        Task.execute(() -> {
            // 移动网络播放开关：关了、当前是流量、且是需要联网的歌（本地/已下载不受影响）
            boolean blocked = !MusicStore.isMobilePlay() && !MusicDownloadStore.isLocal(track) && isCellularNetwork();
            String url = null;
            if (!blocked) url = MusicRepository.playUrl(track, quality);
            final boolean fBlocked = blocked;
            final String fUrl = url;
            App.post(() -> {
                preparing = false;
                if (player == null || queue.get(index) != track) return;
                if (fBlocked) {
                    Notify.show(R.string.music_mobile_play_blocked);
                    return;
                }
                if (fUrl == null || fUrl.isEmpty()) {
                    SpiderDebug.log("music", "playUrl empty id=%s", track.id);
                    listeners.forEach(l -> l.onTrackChanged(track));
                    onPlayFailed();
                    return;
                }
                MediaItem item = new MediaItem.Builder()
                        .setMediaId(track.id == null ? "" : track.id)
                        .setUri(fUrl)
                        .setMediaMetadata(new MediaMetadata.Builder()
                                .setTitle(track.displayTitle())
                                .setArtist(track.displayArtist())
                                .setAlbumTitle(track.album)
                                .setArtworkUri(track.cover == null || track.cover.isEmpty() ? null : android.net.Uri.parse(track.cover))
                                .build())
                        .build();
                queue.moveTo(index);
                audioInfo = null; // 换歌先清旧参数，等新轨 Format 到齐再刷新
                listeners.forEach(ServiceListener::onAudioInfoChanged);
                player.setMediaItem(item, startPosition);
                player.prepare();
                player.setPlaybackSpeed(getSpeed());
                if (autoplay) player.play();
                else player.pause();
                saveBreakpoint();
                MusicStore.pushRecent(track);
                listeners.forEach(l -> l.onTrackChanged(track));
            });
        });
    }

    /**
     * 取链失败/播放报错的统一收口，受「播放失败自动跳下一首」开关管：
     * 开=自动下一首，关=停住并提示。
     */
    private void onPlayFailed() {
        if (MusicStore.isAutoSkipOnError()) {
            next();
        } else {
            Notify.show(R.string.music_play_failed_stopped);
            stopPlayback();
        }
    }

    /** 当前是不是在用移动数据（WiFi/无网都不算），判流量播放拦不拦用 */
    private boolean isCellularNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable t) {
            return false;
        }
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) onTrackEnded();
            else if (state == Player.STATE_READY) {
                saveBreakpoint();
                updateAudioInfo();
            }
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            updateAudioInfo();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            try {
                SpiderDebug.log("music", "onPlayerError %s", error.getMessage());
                preparing = false;
                onPlayFailed();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            listeners.forEach(l -> l.onPlayingChanged(isPlaying));
            if (!isPlaying) saveBreakpoint();
        }
    };

    private void onTrackEnded() {
        if (sleep == SLEEP_AFTER_CURRENT) {
            setSleep(SLEEP_OFF);
            pause();
            return;
        }
        int n = queue.nextIndex();
        if (n >= 0) {
            prepareTrack(n, 0, true);
        } else {
            // 顺序播到尾：停住
            pause();
            seekTo(0);
        }
    }

    private void startTicker() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (player != null && player.isPlaying()) {
                        long pos = position();
                        long dur = duration();
                        listeners.forEach(l -> l.onProgress(pos, dur));
                    }
                } catch (Throwable ignored) {
                }
                main.postDelayed(this, 500);
            }
        });
    }

    // ================= 断点续播 =================

    private void saveBreakpoint() {
        try {
            MusicTrack t = queue.current();
            if (t == null || queue.isEmpty()) return;
            MusicStore.saveBreakpoint(queue.getTracks(), queue.getIndex(), position());
        } catch (Throwable ignored) {
        }
    }

    private void restoreBreakpoint() {
        try {
            List<MusicTrack> q = MusicStore.loadQueue();
            if (q.isEmpty()) return;
            int idx = Math.max(0, Math.min(MusicStore.loadIndex(), q.size() - 1));
            queue.setTracks(q, idx);
            // 只摆好、不自动播：用户点播放才从断点起
            listeners.forEach(ServiceListener::onQueueChanged);
            listeners.forEach(l -> l.onTrackChanged(queue.current()));
        } catch (Throwable ignored) {
        }
    }

    public class LocalBinder extends Binder {
        public MusicPlaybackService getService() {
            return MusicPlaybackService.this;
        }
    }
}
