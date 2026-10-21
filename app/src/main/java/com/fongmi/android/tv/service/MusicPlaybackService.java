package com.fongmi.android.tv.service;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicBitDepthProbe;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.ParametricEq;
import com.fongmi.android.tv.music.ParametricEqProcessor;
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
import java.util.concurrent.Future;

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
     * 失败停机通知 id：与媒体通知 1001、通用 Notify 9527 都不同，
     * 失败停机时单独发一条、点开回播放器；恢复播放时按此 id 撤掉。
     */
    private static final int FAIL_NOTIFICATION_ID = 1002;
    /** 失败停机通知渠道（IMPORTANCE_DEFAULT：后台用户能看见提醒） */
    private static final String FAIL_NOTIFICATION_CHANNEL = "music_stopped";
    /** 定时结束通知 id：与媒体通知 1001、失败通知 1002 都不同 */
    private static final int SLEEP_NOTIFICATION_ID = 1003;
    /** 定时结束通知渠道（IMPORTANCE_DEFAULT：后台用户能看见提醒） */
    private static final String SLEEP_NOTIFICATION_CHANNEL = "music_sleep_ended";

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
    public static final int SLEEP_10 = 6;
    public static final int SLEEP_20 = 7;
    public static final int SLEEP_40 = 8;
    public static final int SLEEP_CUSTOM = 9;
    /** 自定义分钟编码档基数：面板经 MusicController.setSleep(int) 传档时，档值＝基数＋分钟（1–999），
     * 服务侧按该分钟数计时；MusicController 只透传 int，不用改它。直接调服务时也可用 setSleepCustom(分钟)。 */
    public static final int SLEEP_CUSTOM_MINUTES_BASE = 1000;

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

        /**
         * W-E 配合：可信的“已落位”信号——prepare 成功装载并即将出声时发，与受理宣告区分开。
         * 页内不要再靠 onTrackChanged 计次区分受理／落位：受理宣告（用户路径）与落位宣告
         * 是同一事件、无法仅凭它区分；落位以本事件为准。自动推进路径受理时不宣告，
         * 落位时本事件与 onTrackChanged 各发一次，计次口径下仍只有一次 onTrackChanged。
         * 默认空实现，老监听不受影响。
         */
        default void onTrackReady(MusicTrack track) {
        }

        /**
         * W-E 配合：可信的“已失败”信号——任一取链／播放失败收口时发（手动停在失败态、
         * 自动推进停机、自动跳歌越过失败歌时都发，失败歌以本事件为准，不许再从
         * 回滚宣告／null 宣告反推失败）。reasonRes 为失败原因串资源 id。
         * 默认空实现，老监听不受影响。
         */
        default void onTrackFailed(MusicTrack track, int reasonRes) {
        }

        default void onSleepChanged(int sleep) {
        }

        /** 落雪密钥问题（B4）：脚本明确报了 401／403，UI 侧弹密钥提示；其它失败不走这里 */
        default void onKeyIssue(String platform, String detail) {
        }
    }

    private final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();
    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MusicQueue queue = new MusicQueue();

    private MediaSession session;
    private ExoPlayer player;
    /** B6：参数均衡器处理链（建播放器时串入，面板改配置下一 buffer 起生效） */
    private ParametricEqProcessor parametricEq;
    private int sleep = SLEEP_OFF;
    private Runnable sleepTask;
    /** 定时截止时刻（elapsedRealtime 基准，供面板读剩余 m:ss，GZ B07）；未设定时为 0 */
    private long sleepDeadlineElapsed;
    private boolean preparing;
    private int prepareSeq;
    /**
     * 本次 prepare 是不是自然播完自动推进（onTrackEnded／advancePastFailed 链）：
     * 是＝失败时不许回滚到已播完的旧歌、也不许静默停住，必须走 onPlayFailed→自动跳歌往后找；
     * 否＝用户主动点歌／切歌，失败回滚语义保留（界面指回播放器里真装着的那首）。
     * READY 起播、stopPlayback、任何用户侧入队／切歌都清掉。
     */
    private boolean autoAdvancing;
    /** 本次「播到尾」事件是否已被消费（防 ENDED 回调与心跳看门狗重复切歌）；新轨 READY 时复位 */
    private boolean endedConsumed;
    private volatile MusicAudioInfo audioInfo;

    /** 失败熔断（对差清单 D3，只在主线程读写）：每首歌连续失败计数＋拉黑集＋全局连续失败数；
     * P2：键一律用 MusicQueue.trackKey（platform|id，无 id 时 platform|title|artist），
     * 不许只按 id——FreeJS 原始 id 跨插件撞车、无 id 歌共享空串键会连坐好歌 */
    private final Map<String, Integer> failCounts = new HashMap<>();
    /** 预加载下一首单槽缓存（瓜子后台播放／预加载下一首）：只存下一首的直链＋取链时档位；
     * 命中只省取链，不省起播缓冲；档位对不上（网络切了档）就作废重取，不拿错档的链凑合播；
     * 槽位键同样用 trackKey（同 P2 口径），不许只按 id 认歌 */
    private String preloadKey;
    private String preloadUrl;
    private int preloadQuality = -1;
    private final Set<String> failedBlacklist = new HashSet<>();
    private int consecutiveFails;
    /** 最近一次失败的原因文案（onPlayFailed 收口发通知用，四个调用点先填好） */
    private int lastFailReasonRes;
    /** S9：定时“本首结束”绑定的歌（trackKey，setSleep 时记当前歌）；换歌／该歌失败即失效，
     * 不许跟到下一首去。null＝设定时无当前歌，沿用旧口径（到本首尾即停）。 */
    private String sleepTargetKey;
    /** 随机历史回退标记（R6）：本次起播是按历史栈回退的，离场那首不许再入栈，否则上一首会原地打转 */
    private boolean startFromHistory;
    /** S10：顶前台失败标记（只提示一次；用户回应用内再按播放时 startForegroundNow 会重试并清掉） */
    private boolean foregroundFailed;
    /** 在途取链任务（P3／看门狗／停播时可取消，不许只放弃等待让僵尸线程占池） */
    private Future<?> prepareFuture;
    /** S2：锁屏／系统上一首回开头阈值——media3 默认口径 3 秒，未抠到瓜子值（见交卷清单）：
     * 播过 3 秒按上一首先回本首开头，不过 3 秒才换上一首 */
    private static final long PREV_TO_START_THRESHOLD_MS = 3000;

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
        // B6：参数均衡器处理链先建好、带上存量配置，再串进播放器
        parametricEq = new ParametricEqProcessor();
        parametricEq.setEnabled(MusicStore.isParametricEnabled());
        parametricEq.setBands(MusicStore.getParametricBands());
        // 本机 Media3 分支的 ExoPlayer.Builder 没有 setAudioProcessors，均衡链改走 AudioSink 注入：
        // DefaultRenderersFactory.createRenderers 调 buildAudioSink 取 AudioSink，子类覆写它即可把处理链串进音频渲染器（默认实现只设两个开关，本覆写原样照搬再加处理链）。
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessors(new AudioProcessor[]{parametricEq})
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setRenderersFactory(renderersFactory)
                .build();
        player.addListener(playerListener);
        // 播放模式只由 MusicQueue 一处决策（onTrackEnded 按 queue.nextIndex 走）：
        // 播放器自身不许带循环/随机状态，免得两套口径打架、队列模式形同虚设。
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        session = new MediaSession.Builder(this, wrapPlayer(player)).setId("xc_music").build();
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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 瓜子划掉后台停止播放：开＝从后台划掉 App 即停播（默认关＝现行行为，划掉后照常播）；
        // 先 pause 再 stopSelf，onDestroy 已有 stopForeground(true)＋放播放器，不挂空通知
        if (MusicStore.isStopOnTaskRemoved()) {
            try {
                if (player != null) player.pause();
            } catch (Throwable ignored) {
            }
            stopSelf();
        }
    }

    /** 立刻把服务顶到前台（占位通知）；真媒体通知由 media3 provider 随后同 id 替换 */
    private void startForegroundNow() {
        startForegroundWithTrack(null);
    }

    /**
     * S3：占位通知带歌名版——被杀重启断点摆好后，占位不再是无歌名的“音乐播放”空条：
     * 有断点歌时标题＝歌名、副标题＝歌手，点开回播放页；无歌时沿用旧通用占位（随后由
     * restoreBreakpoint 撤掉，不常驻）。不自动播的口径不变（见 restoreBreakpoint）。
     */
    private void startForegroundWithTrack(MusicTrack track) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notify.DEFAULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentIntent(buildPlayerIntent())
                    .setOngoing(true)
                    .setSilent(true);
            if (track != null) {
                builder.setContentTitle(track.displayTitle());
                String artist = track.displayArtist();
                if (artist != null && !artist.isEmpty()) builder.setContentText(artist);
                else builder.setContentText(getString(R.string.app_name));
            } else {
                builder.setContentTitle(getString(R.string.app_name));
                builder.setContentText("音乐播放");
            }
            Notification notification = builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FG_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(FG_NOTIFICATION_ID, notification);
            }
            foregroundFailed = false;
        } catch (Throwable t) {
            // S10：顶前台失败不许只记调试日志——没前台的服务切后台会被系统/厂商省电收掉，
            // 歌就「自己停」了。只提示一次（恢复播放／回应用内再按播放会重试并清标记），
            // 不循环重试（后台起前台被系统拒时重试也无用，只会刷屏）。
            SpiderDebug.log("music", "startForeground failed %s", t.getMessage());
            if (!foregroundFailed) {
                foregroundFailed = true;
                Notify.show(R.string.music_foreground_failed);
            }
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

    /**
     * W-E 配合：当前曲是否已落位（播放器里真装着当前序号这首）。取链期 current 已指
     * 目标歌但 loadedTrack 仍是旧歌时返回 false；停播／失败态 loadedTrack 已清时 false；
     * 暂停态已装载时 true（落位≠在播）。供控制器 pushAll 补发 onTrackReady 用。
     */
    public boolean isCurrentLoaded() {
        return hasMediaItem && loadedTrack != null && loadedTrack == queue.current();
    }

    /**
     * W-E 配合（R2 暂停态恢复）：“只换队不起播”——换队并把目标歌加载到断点位置后暂停，
     * 不出声。不要用 playFrom 假装恢复（它会起播）。空队直接返回，不动播放器。
     * 与 playQueue 同口径清本队运行态、写来源／route；定时“本首结束”绑定随换队失效。
     */
    public void loadQueuePaused(List<MusicTrack> tracks, int index, long position,
                                String source, String route, java.util.Map<String, String> routeParams) {
        if (tracks == null || tracks.isEmpty()) return;
        autoAdvancing = false;
        startFromHistory = false;
        resetQueueRunState();
        if (sleep == SLEEP_AFTER_CURRENT) setSleep(SLEEP_OFF);
        queue.setTracks(tracks, index, source, route, routeParams);
        listeners.forEach(ServiceListener::onQueueChanged);
        prepareTrack(queue.getIndex(), Math.max(0, position), false);
    }

    /** 播队列 */
    public void playQueue(List<MusicTrack> tracks, int index) {
        playQueue(tracks, index, null, null, null);
    }

    /** 播队列并写入队列级来源／route（B7：入队时写，面板不再反查） */
    public void playQueue(List<MusicTrack> tracks, int index,
                          String source, String route, java.util.Map<String, String> routeParams) {
        autoAdvancing = false;
        startFromHistory = false;
        resetQueueRunState();
        if (sleep == SLEEP_AFTER_CURRENT) setSleep(SLEEP_OFF);
        queue.setTracks(tracks, index, source, route, routeParams);
        listeners.forEach(ServiceListener::onQueueChanged);
        prepareTrack(queue.getIndex(), 0, true);
    }

    /**
     * R4 服务钩子：新队列＋模式一次定（随心听／全局随机入口用）：模式与起队在同一调用里定，
     * 不许 setMode 与 playQueue 分两步排队——两步之间若插进一次切歌，模式会串到旧队上。
     * 口径：模式仍是全局偏好（存盘，见 setMode）；换队本身不改模式，只清本队运行态
     * （随机历史／预加载槽／失败计数与黑名单都不许带到新队）。页面侧是否用本钩子由页面工定，
     * 服务侧不擅自给普通 playQueue 改模式。
     */
    public void playQueueWithMode(List<MusicTrack> tracks, int index, int mode,
                                  String source, String route, java.util.Map<String, String> routeParams) {
        setMode(mode);
        playQueue(tracks, index, source, route, routeParams);
    }

    /** 换队清本队运行态（R4）：旧队的失败记录／预加载链／随机历史都不带到新队 */
    private void resetQueueRunState() {
        failCounts.clear();
        failedBlacklist.clear();
        consecutiveFails = 0;
        preloadKey = null;
        preloadUrl = null;
        preloadQuality = -1;
    }

    /** 播队列并从指定位置起（断点续播用） */
    public void playQueueFrom(List<MusicTrack> tracks, int index, long position) {
        playQueueFrom(tracks, index, position, null, null, null);
    }

    /** 播队列并从断点位置起，同时写入队列级来源／route（B7） */
    public void playQueueFrom(List<MusicTrack> tracks, int index, long position,
                              String source, String route, java.util.Map<String, String> routeParams) {
        autoAdvancing = false;
        startFromHistory = false;
        resetQueueRunState();
        if (sleep == SLEEP_AFTER_CURRENT) setSleep(SLEEP_OFF);
        queue.setTracks(tracks, index, source, route, routeParams);
        listeners.forEach(ServiceListener::onQueueChanged);
        prepareTrack(queue.getIndex(), Math.max(0, position), true);
    }

    /** 播指定序号 */
    public void playAt(int index) {
        autoAdvancing = false;
        startFromHistory = false;
        // 真换歌才清绑定：点回本首重播时定时仍跟本首，不清
        if (index != queue.getIndex()) clearSleepTarget();
        if (queue.moveTo(index)) prepareTrack(index, 0, true);
    }

    public void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    public void play() {
        if (player == null) return;
        if (queue.current() == null) return;
        // 没准备过（断点恢复态）则先准备，进度走断点（R12 失败态的 0 进度由
        // stopAtFailed 显式存盘，不在这里改口径，否则断点续播会被误写成 0）
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
        autoAdvancing = false;
        startFromHistory = false;
        int n = queue.nextIndex();
        if (n < 0) return;
        // 只有真换歌才清“本首结束”绑定：队尾无下一首、单曲循环回开头都不换歌，定时留着
        if (n != queue.getIndex()) clearSleepTarget();
        // S2：下一首就是本首（单曲循环／单首队列）不许整首重取链——本地 seek 到 0 即可；
        // 但切歌＝要听新歌：暂停态按下一首必须出声（prepareTrack(n, 0, true) 语义），
        // seek 保持暂停只适用于进度条拖动，不适用于切歌
        if (n == queue.getIndex() && hasMediaItem && player != null
                && player.getPlaybackState() != Player.STATE_IDLE) {
            seekTo(0);
            if (!isPlaying()) play();
            return;
        }
        // Q3：切歌先 moveTo 再取链——序号不等取链成功才落盘，否则取链期连点只进一步、
        // 第二次切歌仍按旧序号算；失败时由 onPlayFailed／回滚按 loadedTrack 指回
        if (queue.moveTo(n)) prepareTrack(n, 0, true);
    }

    public void prev() {
        autoAdvancing = false;
        // R6：随机模式上一首先走历史栈（弹栈找回序号），栈空才回落旧随机口径；
        // 历史回退是真换歌，清“本首结束”绑定
        if (queue.getMode() == MusicQueue.MODE_SHUFFLE) {
            int h = queue.popShuffleHistoryIndex();
            if (h >= 0) {
                clearSleepTarget();
                startFromHistory = true;
                if (queue.moveTo(h)) prepareTrack(h, 0, true);
                else startFromHistory = false;
                return;
            }
            startFromHistory = false;
        } else {
            startFromHistory = false;
        }
        // S2：锁屏／系统上一首与 App 内同一口径——播过阈值先回本首开头，不换歌
        if (hasMediaItem && player != null && position() > PREV_TO_START_THRESHOLD_MS) {
            seekTo(0);
            return;
        }
        int p = queue.prevIndex();
        if (p < 0) return;
        if (p != queue.getIndex()) clearSleepTarget();
        // S2：上一首就是本首（单曲循环）同样本地 seek 到 0，不重取链；
        // 切歌＝要听新歌：暂停态按上一首必须出声（同下一首口径），seek 保持暂停
        // 只适用于进度条拖动。播过阈值先回本首开头那支（上文）不在此口径内，不动。
        if (p == queue.getIndex() && hasMediaItem && player != null
                && player.getPlaybackState() != Player.STATE_IDLE) {
            seekTo(0);
            if (!isPlaying()) play();
            return;
        }
        if (queue.moveTo(p)) prepareTrack(p, 0, true);
    }

    /** S9：手动切歌／换队即清“本首结束”绑定——定时只跟设定时那首，不跟到新歌 */
    private void clearSleepTarget() {
        if (sleep == SLEEP_AFTER_CURRENT) setSleep(SLEEP_OFF);
        else sleepTargetKey = null;
    }

    /**
     * 灵动岛／系统媒体卡缺下一首根因：音乐服务一次只给 media3 装一首歌（setMediaItem 单首），
     * 切歌走星辰自己的 MusicQueue，不走 media3 播放列表，media3 时间线里永远只有一首、没有下一首窗口，
     * 系统就画不出下一首钮（上一首能画是因单首内 seekToPrevious 可回到本首开头）。
     * 修法照视频 PlaybackService.wrap() 同款：ForwardingPlayer 显式加 SEEK_TO_NEXT／PREVIOUS
     * （含 MEDIA_ITEM 版）命令，并把 seekToNext／seekToPrevious 接到星辰自己的队列上
     * （next()＝queue.nextIndex，prev()＝queue.prevIndex）；不能只加命令不接线——media3 自带的
     * seekToNext 在单首时间线里无动作。命令无条件加（同视频口径）：顺序模式到尾时 next() 本就是
     * no-op，与 App 内下一首钮行为一致；上一首到头时 prevIndex 回本首＝重播本首，与 media3
     * 默认 seekToPrevious 回开头行为一致。服务内其余调用仍走原始 player（监听、装歌、播停不变），
     * 只有会话（系统 UI／通知栏／灵动岛）看到包过的播放器；player 只在 onCreate 建一次，包层不会 stale。
     */
    private ForwardingPlayer wrapPlayer(Player base) {
        return new ForwardingPlayer(base) {
            @Override
            public void play() {
                // S1：空闲态（失败停机／被杀重启断点恢复后，播放器里无媒体项）时系统面按播放
                // 不许空转——走服务 play()，它会先 prepareTrack(断点位置) 再播；服务 play()
                // 调的是原始 player，不会递归回包层。非空闲态沿用 media3 默认播停。
                if (getPlaybackState() == Player.STATE_IDLE) {
                    MusicPlaybackService.this.play();
                } else {
                    super.play();
                }
            }

            @Override
            public void seekToPrevious() {
                // S2：media3 原语义——seekToPrevious 按进度决定：播过阈值回本首开头，
                // 不过阈值才换上一首；与 seekToPreviousMediaItem（恒换歌）区分开，
                // 不许两者压成同一行为
                if (hasMediaItem && position() > PREV_TO_START_THRESHOLD_MS) {
                    seekTo(0);
                } else {
                    prev();
                }
            }

            @Override
            public void seekToPreviousMediaItem() {
                prev();
            }

            @Override
            public void seekToNext() {
                next();
            }

            @Override
            public void seekToNextMediaItem() {
                next();
            }

            @NonNull
            @Override
            public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon()
                        .add(COMMAND_SEEK_TO_PREVIOUS)
                        .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(COMMAND_SEEK_TO_NEXT)
                        .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build();
            }
        };
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
        MusicTrack removed = queue.get(i);
        queue.removeAt(i);
        listeners.forEach(ServiceListener::onQueueChanged);
        // Q6：删空／删到无当前歌时清断点——否则 onDestroy 的 saveBreakpoint 遇空队列直接
        // return，旧断点永远清不掉，重启后已删空的队列复活
        if (queue.isEmpty() || queue.current() == null) {
            MusicStore.clearBreakpoint();
            stopPlayback();
            return;
        }
        if (wasCurrent) {
            // S9：删掉的就是定时绑定的那首，定时失效（不跟到顶上来的下一首）
            if (removed != null && sleepTargetKey != null
                    && sleepTargetKey.equals(MusicQueue.trackKey(removed))) {
                setSleep(SLEEP_OFF);
            } else {
                startFromHistory = false;
            }
            prepareTrack(queue.getIndex(), 0, true);
        }
    }

    /** 队列移除契约（另一工调用口径）：按 key 删，方法名定死为 removeByKey */
    public void removeByKey(String key) {
        if (key == null) return;
        int idx = -1;
        List<MusicTrack> tracks = queue.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (key.equals(MusicQueue.trackKey(tracks.get(i)))) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) removeAt(idx);
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
        // 清队后不留前台空通知：stopPlayback 只停播放器，前台占位仍在时由这里撤掉
        demoteForeground();
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
        autoAdvancing = false;
        startFromHistory = false;
        // Q6/P1：停播即顶掉在途取链——序号＋取消任务双保险，不许清队／停机后取链回来
        // 又把已清的歌装上、或停机后取链中的歌“复活”；看门狗与 App.post 回调都以序号为准作废
        prepareSeq++;
        preparing = false;
        cancelPrepareFuture();
        preloadKey = null;
        preloadUrl = null;
        cancelSleep();
        // S9 附带：stopPlayback 只调 cancelSleep 会留下 stale 的 sleep 档位（任务已无、档位还在），
        // 下首播完 onTrackEnded 会误按旧档位停——停播即把档位与绑定一并清掉并通知
        if (sleep != SLEEP_OFF) {
            sleep = SLEEP_OFF;
            sleepTargetKey = null;
            listeners.forEach(l -> l.onSleepChanged(SLEEP_OFF));
        } else {
            sleepTargetKey = null;
        }
        hasMediaItem = false;
        loadedTrack = null;
        // P6 服务侧：停播清实测音质并通知——播放页 bindTrack(null) 提前返回刷不到音质格时，
        // 至少监听侧拿到的 audioInfo 已是空，不留上一首的实测值
        if (audioInfo != null) {
            audioInfo = null;
            listeners.forEach(ServiceListener::onAudioInfoChanged);
        }
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        listeners.forEach(l -> l.onTrackChanged(null));
        listeners.forEach(l -> l.onPlayingChanged(false));
    }

    /** R12：手动切歌失败停在失败态（不静默弹回旧歌、不报“无曲目”）：队列仍指失败歌、
     * 播放器停住、界面显示失败歌＋未播态，用户按播放即按 R12 口径从 0 重取（见 play()）。
     * 自动推进失败不走这里（保留原 stopPlayback 口径）。失败歌已不在当前序号时不硬指回，
     * 退回原 stopPlayback，避免把已换的队指乱。 */
    private void stopAtFailed(MusicTrack failed) {
        if (failed == null || failed != queue.current()) {
            stopPlayback();
            return;
        }
        autoAdvancing = false;
        startFromHistory = false;
        prepareSeq++;
        preparing = false;
        cancelPrepareFuture();
        preloadKey = null;
        preloadUrl = null;
        cancelSleep();
        if (sleep != SLEEP_OFF) {
            sleep = SLEEP_OFF;
            sleepTargetKey = null;
            listeners.forEach(l -> l.onSleepChanged(SLEEP_OFF));
        } else {
            sleepTargetKey = null;
        }
        hasMediaItem = false;
        loadedTrack = null;
        if (audioInfo != null) {
            audioInfo = null;
            listeners.forEach(ServiceListener::onAudioInfoChanged);
        }
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        // 失败态断点：显式存失败歌＋0 进度（不用 saveBreakpoint() 读当前 position，
        // 那是停之前旧歌的位置），重启后队列仍是它、按播放可重试，不复活旧歌
        MusicStore.saveBreakpoint(queue.getTracks(), queue.getIndex(), 0,
                queue.getSource(), queue.getRoute(), queue.getRouteParams());
        listeners.forEach(l -> l.onTrackChanged(failed));
        listeners.forEach(l -> l.onPlayingChanged(false));
    }

    private void cancelPrepareFuture() {
        Future<?> f = prepareFuture;
        prepareFuture = null;
        if (f != null) {
            try {
                f.cancel(true);
            } catch (Throwable ignored) {
            }
        }
    }

    // ================= 定时关闭 =================

    public int getSleep() {
        return sleep;
    }

    public void setSleep(int mode) {
        cancelSleep();
        // S9：设“本首结束”时绑当前歌；其它档／关闭不绑歌
        sleepTargetKey = mode == SLEEP_AFTER_CURRENT ? MusicQueue.trackKey(queue.current()) : null;
        sleep = mode;
        if (mode == SLEEP_10) armSleep(10 * 60 * 1000L);
        else if (mode == SLEEP_15) armSleep(15 * 60 * 1000L);
        else if (mode == SLEEP_20) armSleep(20 * 60 * 1000L);
        else if (mode == SLEEP_30) armSleep(30 * 60 * 1000L);
        else if (mode == SLEEP_40) armSleep(40 * 60 * 1000L);
        else if (mode == SLEEP_60) armSleep(60 * 60 * 1000L);
        else if (mode == SLEEP_90) armSleep(90 * 60 * 1000L);
        else if (mode >= SLEEP_CUSTOM_MINUTES_BASE) {
            int minutes = mode - SLEEP_CUSTOM_MINUTES_BASE;
            if (minutes >= 1 && minutes <= 999) armSleep(minutes * 60 * 1000L);
            else sleep = SLEEP_OFF;
        }
        listeners.forEach(l -> l.onSleepChanged(sleep));
    }

    /** 自定义分钟定时（直接调服务用）：分钟数 1–999，计时链就是 armSleep，任意分钟都支持，不做假档 */
    public void setSleepCustom(int minutes) {
        if (minutes < 1 || minutes > 999) return;
        setSleep(SLEEP_CUSTOM_MINUTES_BASE + minutes);
    }

    /** 当前自定义分钟数（仅编码档有效，其余返回 -1） */
    public int getSleepCustomMinutes() {
        if (sleep >= SLEEP_CUSTOM_MINUTES_BASE) return sleep - SLEEP_CUSTOM_MINUTES_BASE;
        return -1;
    }

    public long getSleepRemainingMs() {
        if (sleepDeadlineElapsed <= 0) return -1;
        return Math.max(0, sleepDeadlineElapsed - android.os.SystemClock.elapsedRealtime());
    }

    private void armSleep(long delayMs) {
        sleepDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + delayMs;
        sleepTask = () -> {
            SpiderDebug.log("music", "sleep timer fired delayMs=%d mode=%s", delayMs, sleepName(sleep));
            sleepDeadlineElapsed = 0;
            sleep = SLEEP_OFF;
            listeners.forEach(l -> l.onSleepChanged(SLEEP_OFF));
            pause();
            notifySleepEnded();
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
                return "本首结束";
            case SLEEP_10:
                return "10 分钟";
            case SLEEP_15:
                return "15 分钟";
            case SLEEP_20:
                return "20 分钟";
            case SLEEP_30:
                return "30 分钟";
            case SLEEP_40:
                return "40 分钟";
            case SLEEP_60:
                return "60 分钟";
            case SLEEP_90:
                return "90 分钟";
            case SLEEP_CUSTOM:
                return "自定义";
            default:
                if (s >= SLEEP_CUSTOM_MINUTES_BASE && s - SLEEP_CUSTOM_MINUTES_BASE >= 1
                        && s - SLEEP_CUSTOM_MINUTES_BASE <= 999) {
                    return (s - SLEEP_CUSTOM_MINUTES_BASE) + " 分钟";
                }
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

    // ================= 参数均衡器（B6：Media3 处理链，与系统 audiofx 并存，不改称） =================

    public void setParametricEnabled(boolean on) {
        MusicStore.setParametricEnabled(on);
        if (parametricEq != null) parametricEq.setEnabled(on);
    }

    public void setParametricBands(List<ParametricEq.Band> bands) {
        MusicStore.setParametricBands(bands);
        if (parametricEq != null) parametricEq.setBands(bands);
    }

    public boolean isParametricEnabled() {
        return parametricEq != null ? parametricEq.isEnabled() : MusicStore.isParametricEnabled();
    }

    public List<ParametricEq.Band> parametricBands() {
        return parametricEq != null ? parametricEq.copyBands() : MusicStore.getParametricBands();
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

    /** 降档重试的时间余量：首档尝试不设限（原路径），重试前已耗时超这个数就停手。
     * 收紧口径：死链不许吃满 15 秒重试窗＋20 秒看门狗才换歌——首档失败已超 8 秒就不再逐档
     * 试下去，失败收口直接换歌／停住；首档失败得快（几秒内）时仍可逐档重试，不丢降档收益。
     * 弱网冷插件首档本身更长是源侧上限（Free 15 秒／落雪 20 秒总预算），见交卷清单。 */
    private static final long FALLBACK_RETRY_AFTER_MS = 8000;

    /**
     * 同曲降档取链（在熔断计数之前）：所选档只是请求上限，源拿不出这一档不许整首卡死——
     * 在同一首歌里从所选档往下逐档试（母带→FLAC→320k→128k），拿到链就照常播、显示跟实测走。
     * 降档成功不算失败；降到最低档还没有才返回 null，交回原失败路径（熔断/跳歌）。
     * 已被新请求接管（seq 过期）就停手，旧结果反正会被作废，别白耗源请求。
     */
    private String playUrlWithFallback(MusicTrack track, int quality, int seq) {
        long start = System.currentTimeMillis();
        boolean keyIssueFired = false;
        for (int q = quality; q >= MusicStore.QUALITY_LOW; q--) {
            if (q != quality) {
                if (seq != prepareSeq) return null;
                if (System.currentTimeMillis() - start > FALLBACK_RETRY_AFTER_MS) {
                    SpiderDebug.log("music", "playUrl fallback stop (time) id=%s", track.id);
                    break;
                }
            }
            if (Thread.currentThread().isInterrupted()) return null;
            String url;
            try {
                url = MusicRepository.playUrl(track, q);
            } catch (LxKeyIssue e) {
                // B4：密钥问题不降档重试（换档也一样 401／403），只发一次事件给 UI 弹提示，走正常失败路径
                if (!keyIssueFired) {
                    keyIssueFired = true;
                    listeners.forEach(l -> l.onKeyIssue(e.getPlatform(), e.getMessage()));
                }
                return null;
            }
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

    /** 播放音质生效值：移动网络下“移动网络音质”固定档优先（-1＝跟随播放音质）；
     * 本地/已下载/设备歌曲不走网络，固定档不影响它们 */
    private int effectivePlayQuality(MusicTrack track) {
        int q = MusicStore.getQuality();
        if (track != null && !MusicDownloadStore.isLocal(track)
                && !com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                && isCellularNetwork()) {
            int mq = MusicStore.getMobileQuality();
            if (mq >= 0) q = mq;
        }
        return q;
    }

    /** 取出预加载的链（一次性：命中即清槽；档位必须与本次生效档一致，网络切了档就作废重取；
     * 键用 trackKey，不许只按 id 认歌） */
    private String takePreload(MusicTrack track, int quality) {
        if (track == null || !MusicQueue.trackKey(track).equals(preloadKey)) return null;
        String url = preloadUrl;
        preloadKey = null;
        preloadUrl = null;
        if (url == null || url.isEmpty() || preloadQuality != quality) return null;
        return url;
    }

    /**
     * 预加载下一首（瓜子后台播放／预加载下一首）：当前歌起播后，把下一首的链先取好存单槽缓存，
     * 交接时命中就跳过取链、静默期只剩起播缓冲。只做确定性模式（顺序／列表循环）：
     * 单曲循环（下一首＝自己）不用预取；随机（下一首每次现抽，提前取的未必是真下一首）不预取，
     * 免得白耗流量占池子。流量开关关着、已拉黑、取链中途换歌时都不取／结果作废。
     */
    private void maybePreloadNext() {
        if (!MusicStore.isPreloadNext()) return;
        MusicTrack cur = queue.current();
        if (cur == null) return;
        if (MusicStore.getMode() == MusicQueue.MODE_SHUFFLE) return;
        int ni = queue.nextIndex();
        if (ni < 0) return;
        MusicTrack next = queue.get(ni);
        if (next == null || MusicQueue.trackKey(next).equals(MusicQueue.trackKey(cur))) return;
        if (MusicQueue.trackKey(next).equals(preloadKey)) return; // 已取好或正在取
        if (failedBlacklist.contains(MusicQueue.trackKey(next))) return;
        if (!MusicStore.isMobilePlay() && !MusicDownloadStore.isLocal(next)
                && !com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(next.platform)
                && isCellularNetwork()) return;
        preloadKey = MusicQueue.trackKey(next);
        preloadUrl = null;
        preloadQuality = effectivePlayQuality(next);
        final String key = preloadKey;
        final MusicTrack snapshot = next;
        final int q = preloadQuality;
        // 预加载走音乐专用池（P3）：不占共享 5 线程池，不许预加载僵尸任务拖垮正式取链
        Task.executeMusic(() -> {
            String url = null;
            try {
                url = MusicRepository.playUrl(snapshot, q);
            } catch (Throwable ignored) {
            }
            final String fUrl = url;
            App.post(() -> {
                if (key.equals(preloadKey) && fUrl != null && !fUrl.isEmpty()) preloadUrl = fUrl;
            });
        });
    }

    private void prepareTrack(int index, long startPosition, boolean autoplay) {
        MusicTrack track = queue.get(index);
        if (track == null || player == null) return;
        // 正在取链时又点了新歌：不丢弃新请求，用序号让旧结果作废、新请求接管，
        // 否则旧链回来发现队列已换只会静默返回，点了跟没点一样。
        preparing = true;
        final int seq = ++prepareSeq;
        // 看门狗：取链卡死（源挂死/桥死等）时不许无限期顶着 preparing，超时按失败收口；
        // P3：到点同时取消在途取链任务（interrupt），不只放弃等待——否则僵尸任务继续占池
        main.postDelayed(() -> {
            if (seq != prepareSeq || !preparing) return;
            preparing = false;
            prepareSeq++; // 让还在路上的旧结果作废
            cancelPrepareFuture();
            SpiderDebug.log("music", "prepare timeout id=%s", track.id);
            lastFailReasonRes = autoAdvancing ? R.string.music_fail_next_timeout : R.string.music_fail_timeout;
            onPlayFailed(track);
        }, PREPARE_TIMEOUT_MS);
        // 标题与声音同步（下一曲／自动推进）：自动推进时受理即换标题会让 App 内标题先切、
        // 声音后到（上一首还在响、标题已是下一首），且通知栏仍是旧歌——自动路径受理时
        // 不发 onTrackChanged，只在真正起播（声音到）时发，标题与声音同到。用户主动点歌
        // 保留 D4 受理即通知（点了先有反应），不改。
        if (!autoAdvancing) listeners.forEach(l -> l.onTrackChanged(track));
        int quality = effectivePlayQuality(track);
        String cached = takePreload(track, quality);
        // L1 服务侧：本地／设备歌文件已删时不走取链＋播放器报错链——localPath 有值且文件
        // 不在就直接按失败收口（快失败、不占 20 秒看门狗）；列表幽灵行本身由列表页工清，
        // 服务侧只保证点它不卡、不播错歌。content URI 歌（localPath 为空）无法不打开就验，
        // 仍走原播放器报错收口，见交卷清单。
        boolean ghostLocal = track.localPath != null && !track.localPath.isEmpty()
                && !new File(track.localPath).isFile()
                && (MusicDownloadStore.isLocal(track)
                || com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform));
        prepareFuture = Task.submitMusic(() -> {
            // 移动网络播放开关：关了、当前是流量、且是需要联网的歌（本地/已下载/设备歌曲不受影响）
            boolean blocked = !MusicStore.isMobilePlay() && !MusicDownloadStore.isLocal(track)
                    && !com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                    && isCellularNetwork();
            String url = null;
            if (!ghostLocal && !blocked) url = cached != null ? cached : playUrlWithFallback(track, quality, seq);
            final boolean fBlocked = blocked;
            final String fUrl = url;
            App.post(() -> {
                if (seq != prepareSeq) return; // 已被新请求/看门狗接管，旧结果作废
                preparing = false;
                prepareFuture = null;
                if (player == null) return;
                // Q3③：取链期目标歌被删／移位——不许静默丢单：目标已不在该序号时不播它。
                // 自动推进：当前序号顶上来的就是后继歌，直接起它（不把删掉的歌算失败）；
                // 手动：能回滚到播放器里真装着的旧歌就回滚＋提示，否则只通知当前歌、不播已删的歌。
                if (queue.get(index) != track) {
                    if (autoAdvancing) {
                        int cur = queue.getIndex();
                        if (cur >= 0 && queue.get(cur) != null) {
                            prepareTrack(cur, 0, true);
                        }
                        return;
                    }
                    if (rollbackToLoaded(track)) return;
                    listeners.forEach(l -> l.onTrackChanged(queue.current()));
                    return;
                }
                if (fBlocked) {
                    Notify.show(R.string.music_mobile_play_blocked);
                    if (autoAdvancing) {
                        // 推进中被流量开关拦住：不许静默停住——先拉黑本首再走失败收口，
                        // 自动跳歌会越过它往后找（不拉黑会原地反复试同一首直至熔断，别的歌永远轮不到）。
                        failedBlacklist.add(MusicQueue.trackKey(track));
                        lastFailReasonRes = R.string.music_mobile_play_blocked;
                        onPlayFailed(track);
                        return;
                    }
                    // R12 手动拦歌：停在被拦歌的失败态（见 onPlayFailed），不回滚、不静默
                    lastFailReasonRes = R.string.music_mobile_play_blocked;
                    onPlayFailed(track);
                    return;
                }
                if (fUrl == null || fUrl.isEmpty()) {
                    SpiderDebug.log("music", "playUrl empty id=%s", track.id);
                    lastFailReasonRes = autoAdvancing ? R.string.music_fail_next_url : R.string.music_fail_url;
                    onPlayFailed(track);
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
                // R6：起播成功才记随机历史——离场的那首（loadedTrack）入栈；历史回退起播不入栈
                if (queue.getMode() == MusicQueue.MODE_SHUFFLE && !startFromHistory
                        && loadedTrack != null && loadedTrack != track) {
                    queue.pushShuffleHistory(loadedTrack);
                }
                startFromHistory = false;
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
                // W-E 落位信号：装载成功即发，页内以本事件为准，不靠计次
                listeners.forEach(l -> l.onTrackReady(track));
                maybePreloadNext(); // 本首起播后，下一首的链先取好（预加载下一首，默认开）
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
     * 失败计数记在 failed 这首上（不是 queue.current()）：自动推进时 current 还是已播完的旧歌，
     * 记错会把好歌拉黑、把坏歌漏掉；播放器报错时调用方传 queue.current()，与旧行为一致。
     */
    private void onPlayFailed(MusicTrack failed) {
        startFromHistory = false;
        // P1：失败计数记在 failed 这首上（不是 queue.current()），键用 trackKey；
        // S9：失败的就是定时绑定的那首时定时失效（它不会再“播完”，不许跟到下一首）
        String key = failed == null ? null : MusicQueue.trackKey(failed);
        if (key != null) {
            int c = failCounts.getOrDefault(key, 0) + 1;
            failCounts.put(key, c);
            if (c >= 2) failedBlacklist.add(key);
        }
        if (sleepTargetKey != null && sleepTargetKey.equals(key)) setSleep(SLEEP_OFF);
        consecutiveFails++;
        // W-E 失败信号：收口前先发——手动停失败态／自动停机／自动跳歌越过时都发，
        // 页内以本事件为准，不许再从回滚宣告／null 宣告反推；自动跳歌成功后随后
        // 还会有下一首的 onTrackReady，失败与落位是两首歌的两件事，不冲突
        if (failed != null) {
            int reason = lastFailReasonRes != 0 ? lastFailReasonRes : R.string.music_fail_unknown;
            listeners.forEach(l -> l.onTrackFailed(failed, reason));
        }
        if (MusicStore.isAutoSkipOnError() && advancePastFailed()) return;
        Notify.show(R.string.music_play_failed_stopped);
        if (autoAdvancing) {
            // 自动推进保留原口径：停机＋撤前台（stopPlayback 报“无曲目”沿用旧行为，不擅自改）
            stopPlayback();
        } else {
            // R12：手动切歌失败停在失败态＋可重试——队列仍指失败歌、按播放从 0 重取，
            // 不静默弹回旧歌（旧 rollbackToLoaded 口径只留给取链期删／移目标的 Q3③情形）
            stopAtFailed(failed);
        }
        notifyPlayFailedStopped();
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
            if (nt != null && failedBlacklist.contains(MusicQueue.trackKey(nt))) {
                queue.moveTo(n); // 拉黑的先越过，继续往后找
                continue;
            }
            autoAdvancing = true;
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

    /**
     * 失败停机通知（用户原话：不在播放器里就看不见失败）：
     * 失败收口停机时在通知栏留一条看得见的通知——标题写停了、正文写原因，
     * 点通知回播放器；恢复播放（STATE_READY）时撤掉，不留过期通知。
     * 只在 onPlayFailed 走到停机这条线才发：顺序播到尾（onTrackEnded 的 pause）
     * 与用户清队/删空（直接 stopPlayback）都不经过这里，不会误刷。
     */
    private void notifyPlayFailedStopped() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            NotificationManagerCompat mgr = NotificationManagerCompat.from(this);
            mgr.createNotificationChannel(new NotificationChannelCompat.Builder(FAIL_NOTIFICATION_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(getString(R.string.music_play_stopped_channel)).build());
            int reasonRes = lastFailReasonRes != 0 ? lastFailReasonRes : R.string.music_fail_unknown;
            Notification notification = new NotificationCompat.Builder(this, FAIL_NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.music_play_stopped_title))
                    .setContentText(getString(reasonRes))
                    .setContentIntent(buildPlayerIntent())
                    .setAutoCancel(true)
                    .build();
            mgr.notify(FAIL_NOTIFICATION_ID, notification);
        } catch (Throwable ignored) {
        }
    }

    /** 恢复播放时撤掉失败停机通知（STATE_READY 处调用，id 不存在时是空操作） */
    private void cancelPlayFailedNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(FAIL_NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 定时结束通知（睡眠时长定时到点停了，用户不在播放器里也得一眼知道是定时不是故障）：
     * 标题写定时结束、正文写音乐已停止，不编原因；点通知回播放器；恢复播放（STATE_READY）时撤掉。
     * 只在时长定时触发体里发：本首结束定时（onTrackEnded 的 pause）不经过这里，不会误刷。
     */
    private void notifySleepEnded() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
            NotificationManagerCompat mgr = NotificationManagerCompat.from(this);
            mgr.createNotificationChannel(new NotificationChannelCompat.Builder(SLEEP_NOTIFICATION_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(getString(R.string.music_sleep_channel)).build());
            Notification notification = new NotificationCompat.Builder(this, SLEEP_NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.music_sleep_title))
                    .setContentText(getString(R.string.music_play_stopped_title))
                    .setContentIntent(buildPlayerIntent())
                    .setAutoCancel(true)
                    .build();
            mgr.notify(SLEEP_NOTIFICATION_ID, notification);
        } catch (Throwable ignored) {
        }
    }

    /** 恢复播放时撤掉定时结束通知（STATE_READY 处调用，id 不存在时是空操作） */
    private void cancelSleepNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(SLEEP_NOTIFICATION_ID);
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
                autoAdvancing = false;
                // 真起播了：失败计数全清零（D3 的熔断只管连续失败）
                failCounts.clear();
                failedBlacklist.clear();
                consecutiveFails = 0;
                // 恢复播放：撤掉失败停机通知，不留过期通知
                cancelPlayFailedNotification();
                // 恢复播放：撤掉定时结束通知，不留过期通知
                cancelSleepNotification();
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
                // P1：取链期播放器里还装着旧歌，旧歌此时报错不许算到新歌头上——
                // 取链在途且当前序号已指新歌时，报错属于旧歌，直接忽略（新歌取链回来
                // setMediaItem＋prepare 会恢复播放器）；不置 preparing、不顶序号、不进失败计数。
                // 只有取链不在途、或当前就是装着的那首时，报错才属于当前歌。
                if (preparing && loadedTrack != null && queue.current() != loadedTrack) return;
                preparing = false;
                lastFailReasonRes = R.string.music_fail_play_error;
                onPlayFailed(loadedTrack != null ? loadedTrack : queue.current());
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
            // S9：只跟绑定那首——当前歌就是绑定歌才停；已换歌（手动切歌已在 next/prev 清过，
            // 这里是失败跳歌／其它路径换歌的兜底）时定时失效、不跟到新歌尾，继续正常推进
            if (sleepTargetKey == null || sleepTargetKey.equals(MusicQueue.trackKey(queue.current()))) {
                setSleep(SLEEP_OFF);
                pause();
                return;
            }
            setSleep(SLEEP_OFF);
        }
        int n = queue.nextIndex();
        SpiderDebug.log("music", "trackEnded mode=%d next=%d", queue.getMode(), n);
        if (n >= 0) {
            autoAdvancing = true;
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
            MusicStore.saveBreakpoint(queue.getTracks(), queue.getIndex(), position(),
                    queue.getSource(), queue.getRoute(), queue.getRouteParams());
        } catch (Throwable ignored) {
        }
    }

    private void restoreBreakpoint() {
        // 断点队列是 Gson 读盘（对差清单 D9）：别在 onCreate 主线程同步读，挪后台；
        // 回来时队列还空着才摆上——用户已经先点了歌就以用户的为准，不许覆盖。
        Task.execute(() -> {
            try {
                List<MusicTrack> q = MusicStore.loadQueue();
                if (q.isEmpty()) {
                    // S3：无断点队列时占位通知不许常驻——撤前台并停掉本服务（无歌可播时
                    // START_STICKY 重启循环无意义；之后绑定（BIND_AUTO_CREATE）会按需重建）
                    App.post(() -> {
                        if (!queue.isEmpty()) return;
                        demoteForeground();
                        stopSelf();
                    });
                    return;
                }
                // Q10 校验：存盘时记的断点歌 key 与队列该序号的歌对不上（旧版分次写盘撕裂
                // 的残留、或队列被改过），按 key 在队里找回断点歌，找不到才落到 0，不许
                // 按错序号播另一首
                int savedIdx = MusicStore.loadIndex();
                String savedKey = MusicStore.loadIndexTrackKey();
                int idx = Math.max(0, Math.min(savedIdx, q.size() - 1));
                if (savedKey != null && !savedKey.isEmpty()) {
                    boolean match = idx >= 0 && idx < q.size()
                            && savedKey.equals(MusicQueue.trackKey(q.get(idx)));
                    if (!match) {
                        int found = -1;
                        for (int i = 0; i < q.size(); i++) {
                            if (savedKey.equals(MusicQueue.trackKey(q.get(i)))) {
                                found = i;
                                break;
                            }
                        }
                        idx = found >= 0 ? found : 0;
                    }
                }
                final int fIdx = idx;
                App.post(() -> {
                    if (!queue.isEmpty()) return;
                    queue.setTracks(q, fIdx, MusicStore.loadQueueSource(), MusicStore.loadQueueRoute(),
                            MusicStore.loadQueueRouteParams());
                    // 只摆好、不自动播：用户点播放才从断点起（被杀重启自动出声是产品口径，
                    // 瓜子该行为未抠到，不擅自改；S3 修的是无歌占位常驻，不是改自动播）。
                    // 有断点时占位通知换成带歌名版，系统面按播放（S1 包层）可直接续播。
                    startForegroundWithTrack(queue.current());
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
