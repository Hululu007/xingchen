package com.fongmi.android.tv.service;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 独立音乐播放服务（音频专用）。
 * 只借 PlaybackService 的 MediaSession＋通知栏机制思路，服务独立起、播放器实例独立，
 * 与视频/听书播放互不串。队列、模式、断点、定时都归自己管。
 */
public class MusicPlaybackService extends MediaSessionService {

    public static final String LOCAL_BIND_ACTION = BuildConfig.APPLICATION_ID.concat(".MUSIC_LOCAL_BIND");

    /**
     * 占位前台通知 id：与 media3 默认媒体通知同 id（DefaultMediaNotification 的 1001），
     * 占位先顶上、真媒体通知出来时原地替换，不会双份。
     */
    private static final int FG_NOTIFICATION_ID = 1001;

    /**
     * 取链总预算（对差清单 D2）：源/桥卡死时最多等 20 秒，超时按播放失败收口，
     * 不许把 preparing 永久顶死——断网点任意歌，20 秒内必须出声或明确跳歌/报错。
     */
    private static final long PREPARE_TIMEOUT_MS = 20000;

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
    /** 定时截止时刻（elapsedRealtime 基准，供面板读剩余 m:ss，GZ B07）；未设定时为 0 */
    private long sleepDeadlineElapsed;
    private boolean preparing;
    private int prepareSeq;
    /** 本次「播到尾」事件是否已被消费（防 ENDED 回调与心跳看门狗重复切歌）；新轨 READY 时复位 */
    private boolean endedConsumed;
    private volatile MusicAudioInfo audioInfo;

    /** 失败熔断（对差清单 D3，只在主线程读写）：每首歌连续失败计数＋拉黑集＋全局连续失败数 */
    private final Map<String, Integer> failCounts = new HashMap<>();
    private final Set<String> failedBlacklist = new HashSet<>();
    private int consecutiveFails;

    /** 播放器里有没有真装过媒体项（D5 收口判断用：装过且停播走的是媒体通知自己的生命周期） */
    private boolean hasMediaItem;

    /** 播放器里真装着的那首（setMediaItem 成功时记下）：新歌取链失败时回滚到它，
     * 别让界面把播不了的歌当成「当前」展示（封面/歌名换了、进度还是旧歌的实证根因） */
    private MusicTrack loadedTrack;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 必须第一件事就顶上前台通知：本服务是被 startForegroundService 拉起的，
        // 系统只给约 10 秒，没等到 startForeground 就判 ForegroundServiceDidNotStartInTime 崩。
        // media3 的真媒体通知要等播放器备好媒体项才发，而取链（QuickJS＋同步 HTTP）
        // 慢一点就赶不上（1.3.58 真机实证崩点），所以先用占位通知占住前台。
        startForegroundNow();
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
        // 播放模式只由 MusicQueue 一处决策（onTrackEnded 按 queue.nextIndex 走）：
        // 播放器自身不许带循环/随机状态，免得两套口径打架、队列模式形同虚设。
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        session = new MediaSession.Builder(this, player).setId("xc_music").build();
        session.setSessionActivity(buildPlayerIntent());
        // 必须显式登记会话：media3 的 MediaNotificationManager 只认 addSession 过的会话
        // （updateNotification 对未登记会话直接撤通知）。本 App 走本地 Binder 驱动、
        // 从没有外部 MediaController 连进来，onGetSession 永远等不到人触发登记，
        // 不加这行媒体通知永远发不出来（1.3.59 真机实证通知栏空白）。
        addSession(session);
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
        // onCreate 已顶过前台时这里是无害重入；服务被系统降级后再次被
        // startForegroundService 拉起时，靠这次把前台补回来，先于任何其它活。
        startForegroundNow();
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
    }

    /** 立刻把服务顶到前台（占位通知）；真媒体通知由 media3 provider 随后同 id 替换 */
    private void startForegroundNow() {
        try {
            Notification notification = new NotificationCompat.Builder(this, Notify.DEFAULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("音乐播放")
                    .setContentIntent(buildPlayerIntent())
                    .setOngoing(true)
                    .setSilent(true)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FG_NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            // 顶前台失败不许静默：没前台的服务切后台会被系统/厂商省电收掉，
            // 歌就「自己停」了（第十二件排查点 1）
            SpiderDebug.log("music", "startForeground failed %s", t.getMessage());
        }
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
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        saveBreakpoint();
        cancelSleep();
        releaseFx();
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
        // 续播同 prepareTrack：被降过前台就先补回前台再播
        startForegroundNow();
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
        SpiderDebug.log("music", "setMode %d", mode);
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
        hasMediaItem = false;
        loadedTrack = null;
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

    public long getSleepRemainingMs() {
        if (sleepDeadlineElapsed <= 0) return -1;
        return Math.max(0, sleepDeadlineElapsed - android.os.SystemClock.elapsedRealtime());
    }

    private void armSleep(long delayMs) {
        sleepDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + delayMs;
        sleepTask = () -> {
            sleepDeadlineElapsed = 0;
            sleep = SLEEP_OFF;
            listeners.forEach(l -> l.onSleepChanged(SLEEP_OFF));
            pause();
            stopSelf();
        };
        main.postDelayed(sleepTask, delayMs);
    }

    private void cancelSleep() {
        sleepDeadlineElapsed = 0;
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
     * 均衡器旧口：跳系统均衡器面板（AudioEffect 标准口）。
     * 播放页「更多 → 音效」已改走 App 内面板（下面 fx* 一套），这里保留给其它入口。
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
        if (info != null) fillLocalBitrate(info);
        String oldSig = audioInfo == null ? "" : audioInfo.signature();
        String newSig = info == null ? "" : info.signature();
        if (newSig.equals(oldSig)) return;
        audioInfo = info;
        listeners.forEach(ServiceListener::onAudioInfoChanged);
        if (info != null) probeBitDepth(info);
    }

    /**
     * 本地文件补算平均码率：FLAC 等本地文件的 Format 常常不带码率（真机实证
     * bitrateKbps=-1，音质格只剩采样率）。当前曲有本地路径、文件在、时长已知时，
     * 用文件字节数÷时长算平均码率——这是文件本身的真数据，不是所选档。
     * 在线流没有本地路径，直接跳过，拿不到就维持未知，不许编造。
     */
    private void fillLocalBitrate(MusicAudioInfo info) {
        if (info == null || info.bitrateKbps > 0) return;
        MusicTrack track = queue.current();
        if (track == null || track.localPath == null || track.localPath.isEmpty()) return;
        long durMs = track.duration > 0 ? track.duration
                : (player != null ? player.getDuration() : 0);
        if (durMs <= 0) return;
        try {
            File file = new File(track.localPath);
            if (!file.isFile()) return;
            long kbps = file.length() * 8L / durMs; // 比特/毫秒 数值上等于 kbps
            if (kbps > 0) info.bitrateKbps = (int) kbps;
        } catch (Throwable ignored) {
        }
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

    // ================= 音效（App 内面板：系统 audiofx 三件） =================

    private android.media.audiofx.Equalizer fxEqualizer;
    private android.media.audiofx.BassBoost fxBassBoost;
    private android.media.audiofx.Virtualizer fxVirtualizer;
    private int fxSession = -1;

    /** 音效面板要的全部状态：支持与否逐项给，不支持就是不支持，不许装有效 */
    public static class FxState {
        public boolean enabled;
        public boolean eqSupported;
        public boolean bassSupported;
        public boolean virtSupported;
        public java.util.List<String> presets = new java.util.ArrayList<>();
        public int preset = -1;
        public short[] levels = new short[0];
        public int[] centerFreqsHz = new int[0];
        public short minLevel;
        public short maxLevel;
        public int bass;
        public int virt;
    }

    /**
     * 按需建/重建三件 audiofx：挂当前 player 的 audioSessionId。
     * 会话变了（切歌/音频汇重建）就先释放旧的再重挂，并把存档设置重新应用。
     */
    private void ensureFx() {
        if (player == null) return;
        int sid;
        try {
            sid = player.getAudioSessionId();
        } catch (Throwable t) {
            return;
        }
        if (sid == fxSession && (fxEqualizer != null || fxBassBoost != null || fxVirtualizer != null)) return;
        releaseFx();
        fxSession = sid;
        try {
            fxEqualizer = new android.media.audiofx.Equalizer(0, sid);
        } catch (Throwable t) {
            fxEqualizer = null;
        }
        try {
            fxBassBoost = new android.media.audiofx.BassBoost(0, sid);
        } catch (Throwable t) {
            fxBassBoost = null;
        }
        try {
            fxVirtualizer = new android.media.audiofx.Virtualizer(0, sid);
        } catch (Throwable t) {
            fxVirtualizer = null;
        }
        applyFxFromStore();
    }

    private void releaseFx() {
        try {
            if (fxEqualizer != null) fxEqualizer.release();
        } catch (Throwable ignored) {
        }
        try {
            if (fxBassBoost != null) fxBassBoost.release();
        } catch (Throwable ignored) {
        }
        try {
            if (fxVirtualizer != null) fxVirtualizer.release();
        } catch (Throwable ignored) {
        }
        fxEqualizer = null;
        fxBassBoost = null;
        fxVirtualizer = null;
        fxSession = -1;
    }

    /** 把 MusicStore 里的音效存档灌进三件 audiofx（总开关关＝三件全 bypass） */
    private void applyFxFromStore() {
        boolean on = MusicStore.isFxEnabled();
        try {
            if (fxEqualizer != null) {
                fxEqualizer.setEnabled(on);
                if (on) {
                    int preset = MusicStore.getFxPreset();
                    int count = fxEqualizer.getNumberOfPresets();
                    if (preset >= 0 && preset < count) {
                        fxEqualizer.usePreset((short) preset);
                    } else {
                        short[] levels = MusicStore.getFxBands();
                        int bands = fxEqualizer.getNumberOfBands();
                        for (int i = 0; i < bands && i < levels.length; i++) {
                            try {
                                fxEqualizer.setBandLevel((short) i, levels[i]);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (fxBassBoost != null) {
                fxBassBoost.setEnabled(on);
                if (on) fxBassBoost.setStrength((short) MusicStore.getFxBass());
            }
        } catch (Throwable ignored) {
        }
        try {
            if (fxVirtualizer != null) {
                fxVirtualizer.setEnabled(on);
                if (on) fxVirtualizer.setStrength((short) MusicStore.getFxVirtualizer());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 面板打开时取全量状态 */
    public FxState fxState() {
        ensureFx();
        FxState s = new FxState();
        s.enabled = MusicStore.isFxEnabled();
        s.bass = MusicStore.getFxBass();
        s.virt = MusicStore.getFxVirtualizer();
        s.preset = MusicStore.getFxPreset();
        try {
            if (fxEqualizer != null) {
                s.eqSupported = true;
                int pc = fxEqualizer.getNumberOfPresets();
                for (short i = 0; i < pc; i++) s.presets.add(fxEqualizer.getPresetName(i));
                int bands = fxEqualizer.getNumberOfBands();
                short[] range = fxEqualizer.getBandLevelRange();
                s.minLevel = range[0];
                s.maxLevel = range[1];
                s.levels = new short[bands];
                s.centerFreqsHz = new int[bands];
                for (short i = 0; i < bands; i++) {
                    s.levels[i] = fxEqualizer.getBandLevel(i);
                    s.centerFreqsHz[i] = fxEqualizer.getCenterFreq(i) / 1000;
                }
            }
        } catch (Throwable t) {
            s.eqSupported = false;
        }
        s.bassSupported = fxBassBoost != null;
        s.virtSupported = fxVirtualizer != null;
        return s;
    }

    public void setFxEnabled(boolean on) {
        MusicStore.setFxEnabled(on);
        ensureFx();
        applyFxFromStore();
    }

    public void setFxPreset(int preset) {
        MusicStore.setFxPreset(preset);
        ensureFx();
        if (fxEqualizer == null) return;
        try {
            if (preset >= 0 && preset < fxEqualizer.getNumberOfPresets()) {
                fxEqualizer.setEnabled(MusicStore.isFxEnabled());
                fxEqualizer.usePreset((short) preset);
                // 预设落完后把实际电平回存，面板滑杆照这个显示
                int bands = fxEqualizer.getNumberOfBands();
                short[] levels = new short[bands];
                for (short i = 0; i < bands; i++) levels[i] = fxEqualizer.getBandLevel(i);
                MusicStore.setFxBands(levels);
            }
        } catch (Throwable ignored) {
        }
    }

    public void setFxBandLevel(int band, int millibel) {
        ensureFx();
        if (fxEqualizer == null) return;
        try {
            short[] range = fxEqualizer.getBandLevelRange();
            short level = (short) Math.max(range[0], Math.min(range[1], millibel));
            fxEqualizer.setEnabled(MusicStore.isFxEnabled());
            fxEqualizer.setBandLevel((short) band, level);
            // 手动拉杆＝离开预设，记自定义并存全段电平
            MusicStore.setFxPreset(-1);
            int bands = fxEqualizer.getNumberOfBands();
            short[] levels = new short[bands];
            for (short i = 0; i < bands; i++) levels[i] = fxEqualizer.getBandLevel(i);
            MusicStore.setFxBands(levels);
        } catch (Throwable ignored) {
        }
    }

    public void setFxBass(int strength) {
        MusicStore.setFxBass(strength);
        ensureFx();
        if (fxBassBoost == null) return;
        try {
            fxBassBoost.setEnabled(MusicStore.isFxEnabled());
            fxBassBoost.setStrength((short) MusicStore.getFxBass());
        } catch (Throwable ignored) {
        }
    }

    public void setFxVirtualizer(int strength) {
        MusicStore.setFxVirtualizer(strength);
        ensureFx();
        if (fxVirtualizer == null) return;
        try {
            fxVirtualizer.setEnabled(MusicStore.isFxEnabled());
            fxVirtualizer.setStrength((short) MusicStore.getFxVirtualizer());
        } catch (Throwable ignored) {
        }
    }

    // ================= 播放实现 =================

    /** 降档重试的时间余量：首档尝试不设限（原路径），重试前已耗时超这个数就停手，剩下的交给 20 秒看门狗收口 */
    private static final long FALLBACK_RETRY_AFTER_MS = 15000;

    /**
     * 同曲降档取链（在熔断计数之前）：所选档只是请求上限，源拿不出这一档不许整首卡死——
     * 在同一首歌里从所选档往下逐档试（母带→FLAC→320k→128k），拿到链就照常播、显示跟实测走。
     * 降档成功不算失败；降到最低档还没有才返回 null，交回原失败路径（熔断/跳歌）。
     * 已被新请求接管（seq 过期）就停手，旧结果反正会被作废，别白耗源请求。
     */
    private String playUrlWithFallback(MusicTrack track, int quality, int seq) {
        long start = System.currentTimeMillis();
        for (int q = quality; q >= MusicStore.QUALITY_LOW; q--) {
            if (q != quality) {
                if (seq != prepareSeq) return null;
                if (System.currentTimeMillis() - start > FALLBACK_RETRY_AFTER_MS) {
                    SpiderDebug.log("music", "playUrl fallback stop (time) id=%s", track.id);
                    break;
                }
            }
            String url = MusicRepository.playUrl(track, q);
            if (url != null && !url.isEmpty()) {
                if (q != quality) {
                    SpiderDebug.log("music", "playUrl fallback id=%s %s->%s", track.id,
                            MusicStore.qualityName(quality), MusicStore.qualityName(q));
                }
                return url;
            }
        }
        return null;
    }

    private void prepareTrack(int index, long startPosition, boolean autoplay) {
        MusicTrack track = queue.get(index);
        if (track == null || player == null) return;
        // 正在取链时又点了新歌：不丢弃新请求，用序号让旧结果作废、新请求接管，
        // 否则旧链回来发现队列已换只会静默返回，点了跟没点一样。
        preparing = true;
        final int seq = ++prepareSeq;
        // 看门狗：取链卡死（源挂死/桥死等）时不许无限期顶着 preparing，超时按失败收口
        main.postDelayed(() -> {
            if (seq != prepareSeq || !preparing) return;
            preparing = false;
            prepareSeq++; // 让还在路上的旧结果作废
            SpiderDebug.log("music", "prepare timeout id=%s", track.id);
            if (rollbackToLoaded(track)) return;
            listeners.forEach(l -> l.onTrackChanged(track));
            onPlayFailed();
        }, PREPARE_TIMEOUT_MS);
        // 受理即通知 UI 切标题/封面（对差清单 D4）：不等取链回来才有反应，
        // 取链再慢界面先动，不给「点了没反应」的观感。
        listeners.forEach(l -> l.onTrackChanged(track));
        int quality = MusicStore.getQuality();
        Task.execute(() -> {
            // 移动网络播放开关：关了、当前是流量、且是需要联网的歌（本地/已下载/设备歌曲不受影响）
            boolean blocked = !MusicStore.isMobilePlay() && !MusicDownloadStore.isLocal(track)
                    && !com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                    && isCellularNetwork();
            String url = null;
            if (!blocked) url = playUrlWithFallback(track, quality, seq);
            final boolean fBlocked = blocked;
            final String fUrl = url;
            App.post(() -> {
                if (seq != prepareSeq) return; // 已被新请求/看门狗接管，旧结果作废
                preparing = false;
                if (player == null || queue.get(index) != track) return;
                if (fBlocked) {
                    Notify.show(R.string.music_mobile_play_blocked);
                    // 不起播收口（对差清单 D5）：播放器里没东西在播时别把前台通知空挂着；
                    // 有旧歌在播则不动，那首的通知归它自己管。
                    if (!hasMediaItem && !player.isPlaying()) demoteForeground();
                    return;
                }
                if (fUrl == null || fUrl.isEmpty()) {
                    SpiderDebug.log("music", "playUrl empty id=%s", track.id);
                    if (rollbackToLoaded(track)) return;
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
                // 真起播必须在前台：失败收口 demoteForeground 降过前台后，
                // 再起播不补这一步，服务就是「裸奔」播放——一切后台/退桌面
                // 就被系统收掉，歌自己停（第十二件主根因）。已在前台时重复调无害。
                startForegroundNow();
                player.setMediaItem(item, startPosition);
                hasMediaItem = true;
                loadedTrack = track;
                player.prepare();
                ensureFx(); // 会话可能随音频汇重建而变，音效跟着重新挂
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
     * 状态一致性回滚：新歌取链失败时，若播放器里还装着上一首（还在播/暂停中），
     * 就把队列当前指回它、通知界面重绑它——界面呈现的「当前」必须永远是
     * 播放器里真装着的那首，不许封面/歌名是新歌、进度/时长是旧歌。
     * 上一首已不在队列里/根本没有上一首时返回 false，走原失败收口。
     */
    private boolean rollbackToLoaded(MusicTrack failed) {
        if (loadedTrack == null || loadedTrack == failed || !hasMediaItem || player == null) return false;
        int idx = -1;
        List<MusicTrack> tracks = queue.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i) == loadedTrack) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return false;
        queue.moveTo(idx);
        SpiderDebug.log("music", "play failed rollback id=%s -> loaded id=%s",
                failed == null ? "null" : String.valueOf(failed.id), String.valueOf(loadedTrack.id));
        listeners.forEach(l -> l.onTrackChanged(loadedTrack));
        listeners.forEach(l -> l.onPlayingChanged(player.isPlaying()));
        Notify.show(R.string.music_play_unavailable);
        return true;
    }

    /**
     * 取链失败/播放报错的统一收口，受「播放失败自动跳下一首」开关管：
     * 开=自动下一首，关=停住并提示。另外按对差清单 D3 熔断：
     * 下一首就是自己（单曲循环/单曲队列）不许原地重取链；同一首连续失败 2 次拉黑、
     * 自动跳歌绕开拉黑项；整个队列轮着失败一遍就停，别无限耗电耗流量。
     * 停下时一并撤前台（D5），别挂着空通知。
     */
    private void onPlayFailed() {
        MusicTrack cur = queue.current();
        String id = cur == null ? null : cur.id;
        if (id != null) {
            int c = failCounts.getOrDefault(id, 0) + 1;
            failCounts.put(id, c);
            if (c >= 2) failedBlacklist.add(id);
        }
        consecutiveFails++;
        if (MusicStore.isAutoSkipOnError() && advancePastFailed()) return;
        Notify.show(R.string.music_play_failed_stopped);
        stopPlayback();
        demoteForeground();
    }

    /** 自动跳歌：找下一首可试的（绕开已拉黑的），找到并起播返回 true */
    private boolean advancePastFailed() {
        int size = queue.getTracks().size();
        if (size == 0 || consecutiveFails >= size) return false;
        for (int i = 0; i < size; i++) {
            int n = queue.nextIndex();
            if (n < 0 || n == queue.getIndex()) return false; // 没下一首／下一首就是自己
            MusicTrack nt = queue.get(n);
            if (nt != null && nt.id != null && failedBlacklist.contains(nt.id)) {
                queue.moveTo(n); // 拉黑的先越过，继续往后找
                continue;
            }
            prepareTrack(n, 0, true);
            return true;
        }
        return false;
    }

    /** 不起播收口（对差清单 D5）：停播后撤前台，别把通知空挂着 */
    private void demoteForeground() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
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
            if (state == Player.STATE_ENDED) {
                if (!endedConsumed) {
                    endedConsumed = true;
                    onTrackEnded();
                }
            } else if (state == Player.STATE_READY) {
                endedConsumed = false;
                // 真起播了：失败计数全清零（D3 的熔断只管连续失败）
                failCounts.clear();
                failedBlacklist.clear();
                consecutiveFails = 0;
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
        SpiderDebug.log("music", "trackEnded mode=%d next=%d", queue.getMode(), n);
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
                    // 播完决策看门狗：万一 STATE_ENDED 回调在真机被吞（厂商/版本差异），
                    // 播放器会停在 ENDED、模式再对也不切歌。这里发现「停在 ENDED、
                    // 没在取链、本次结束还没消费」就按同一决策（onTrackEnded→queue.nextIndex）
                    // 补走一次；正常路径下 endedConsumed 已置位，永远轮不到它。
                    if (player != null && !preparing && !endedConsumed
                            && player.getPlaybackState() == Player.STATE_ENDED) {
                        endedConsumed = true;
                        SpiderDebug.log("music", "trackEnded via ticker watchdog mode=%d", queue.getMode());
                        onTrackEnded();
                    }
                } catch (Throwable ignored) {
                }
                // 进度 tick 250ms（GZ positionTicker 歌词态档位；UI 侧再以 200ms 补间/帧插值平滑，见播放页）
                main.postDelayed(this, 250);
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
        // 断点队列是 Gson 读盘（对差清单 D9）：别在 onCreate 主线程同步读，挪后台；
        // 回来时队列还空着才摆上——用户已经先点了歌就以用户的为准，不许覆盖。
        Task.execute(() -> {
            try {
                List<MusicTrack> q = MusicStore.loadQueue();
                if (q.isEmpty()) return;
                int idx = Math.max(0, Math.min(MusicStore.loadIndex(), q.size() - 1));
                App.post(() -> {
                    if (!queue.isEmpty()) return;
                    queue.setTracks(q, idx);
                    // 只摆好、不自动播：用户点播放才从断点起
                    listeners.forEach(ServiceListener::onQueueChanged);
                    listeners.forEach(l -> l.onTrackChanged(queue.current()));
                });
            } catch (Throwable ignored) {
            }
        });
    }

    public class LocalBinder extends Binder {
        public MusicPlaybackService getService() {
            return MusicPlaybackService.this;
        }
    }
}
