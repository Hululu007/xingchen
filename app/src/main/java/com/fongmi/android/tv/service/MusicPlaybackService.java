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
import android.os.Bundle;
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
import androidx.media3.common.util.StuckPlayerException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaNotification;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.collect.ImmutableList;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.api.music.FreeJsBridge;
import com.fongmi.android.tv.api.music.LxKeyIssue;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.api.music.PlaylistImporter;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicBitDepthProbe;
import com.fongmi.android.tv.music.MusicController;
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
     * 占位前台通知 id：必须与本服务给 media3 provider 设的媒体通知 id 一致
     * （onCreate 里给媒体通知 provider 设的通知 id 传的就是本常量；
     * 1.4.43 起 provider 是自绘卡 MusicNotificationProvider，口径不变）——占位先顶上、真媒体通知出来时同 id 原地替换，不会双份。
     * 1.4.15 的教训是「占位 id 与 provider id 必须一致」，不是「必须等于 1001」：
     * 1001 是 media3 的默认 id，视频 PlaybackService 的 provider 没改 id、用的
     * 就是 1001，音乐若也用 1001，两个服务的媒体通知同 App 互相顶（D-18）。
     * 所以音乐整套改到独立的 1004：provider 与占位同用 1004、仍满足一致口径，
     * 又不与视频撞号。不许只改一边：占位与 provider 分家后，前台服务只有一个
     * 前台通知槽，provider 的媒体通知顶不进这个槽、cancel 又摘不掉在任的前台
     * 通知，真机实证只剩占位空条，通知栏媒体卡／灵动岛／控制中心音乐卡三处
     * 一起没（1.4.15 分家批的回归）。
     */
    private static final int FG_NOTIFICATION_ID = 1004;

    /**
     * 媒体通知已由 provider 发出过标记：provider 的媒体卡一上岗，占位起播路径
     * （startForegroundWithTrack）不许再拿素条盖它——每首起播都会调
     * startForegroundNow，顺序在播放器事件之后时就把媒体卡顶成素条且整首不再
     * 刷新（真机症状：通知栏没有播放器卡、灵动岛也不出）。provider 的回调里
     * 置真、demoteForeground 降前台时清假（降了前台通知已撤、下一首要重新顶）。
     */
    private static volatile boolean sMediaNotificationPosted;

    /**
     * 失败停机通知 id：与媒体通知 1004、通用 Notify 9527 都不同，
     * 失败停机时单独发一条、点开回播放器；恢复播放时按此 id 撤掉。
     */
    private static final int FAIL_NOTIFICATION_ID = 1002;
    /** 失败停机通知渠道（IMPORTANCE_DEFAULT：后台用户能看见提醒） */
    private static final String FAIL_NOTIFICATION_CHANNEL = "music_stopped";
    /** 定时结束通知 id：与媒体通知 1004、失败通知 1002 都不同 */
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
         * 出词提速：服务侧取词（与取链同刻启动的那份）已把当前歌的词备好并回写缓存时发。
         * 页面收到后若仍无词，直接读缓存上屏，不必等自己那份链收口。默认空实现。
         */
        default void onLyricReady(MusicTrack track) {
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
    private MusicBeatAnalyzer beatAnalyzer;
    private int sleep = SLEEP_OFF;
    private Runnable sleepTask;
    /** 定时截止时刻（elapsedRealtime 基准，供面板读剩余 m:ss，GZ B07）；未设定时为 0 */
    private long sleepDeadlineElapsed;
    /**
     * D3：分钟档“播完再停”等待态——到点时不暂停、只置位，曲终回调（onTrackEnded）里再停。
     * 与 SLEEP_AFTER_CURRENT 独立模式二选一：等待态只由分钟档／自定义档到点置位，独立模式不走这里；
     * setSleep／停播／曲终消费时清掉，手动切歌不自动清（等待的是“下一曲终”，不是原曲终，见 onTrackEnded）。
     */
    private boolean sleepWaitingForSongEnd;
    /** P-4b：ticker 节流存盘的上次存盘时刻（elapsedRealtime）与进度；每 30 秒或进度变化超阈值才存一次。 */
    private long lastTickerSaveElapsed;
    private long lastTickerSavePosition = -1;
    private boolean preparing;
    private int prepareSeq;
    /**
     * 本次 prepare 是不是自然播完自动推进（onTrackEnded／advancePastFailed 链）：
     * 是＝失败时不许回滚到已播完的旧歌、也不许静默停住，必须走 onPlayFailed→自动跳歌往后找；
     * 否＝用户主动点歌／切歌，失败回滚语义保留（界面指回播放器里真装着的那首）。
     * READY 起播、stopPlayback、任何用户侧入队／切歌都清掉。
     */
    private boolean autoAdvancing;
    /** M4：自动推进失败停机后不许把失败歌写成断点（只抑止 saveBreakpoint 写盘，
     * 不动队列游标、不回滚旧歌）。置位点只在 onPlayFailed 自动停机分支（stopPlayback() 之后，
     * stopPlayback 本身不许清它）；清位点只在新歌已确认起播处——prepareTrack 成功回调、
     * STATE_READY、play() 恢复出声三处。next/prev/playAt/playQueue 入口不单独清：
     * 它们都汇到 prepareTrack，入口清是“未确认就清”，且失败时 onPlayFailed 会重新置位。 */
    private boolean failedStopNoBreakpoint;
    /** 本次「播到尾」事件是否已被消费（防 ENDED 回调与心跳看门狗重复切歌）；新轨 READY 时复位 */
    private boolean endedConsumed;
    /** 越界看门狗计时：播放头首次越过标称时长的时刻（elapsedRealtime）；条件不成立的 tick 清零 */
    private long overrunSinceElapsed;
    private volatile MusicAudioInfo audioInfo;
    /**
     * 当前曲实际起播直链（setMediaItem 处记、停播／取链为空时清）：只供在线位深
     * Range 探头用，不作其它用途；绝不拿所选档、质量表、平台声明档位当位深。
     */
    private String currentPlayUrl;
    /**
     * 三-42：当前真起播那首的取链渠道（与 currentPlayUrl 同点记、同按 trackKey 认歌）：
     * playChannelKey＝起播歌的 trackKey，playChannelKind/Src＝仓库旁路记录里那次命中的
     * 渠道枚举与命中插件 srcUrl。只供信息页「取链渠道」行展示；换歌由下一次真起播覆盖，
     * 读端按 key 比对，切歌／失败停留态下不是这首的一律不显，不许串歌。
     */
    private String playChannelKey;
    private int playChannelKind = MusicRepository.PLAY_CHANNEL_NONE;
    private String playChannelSrc;
    /**
     * 在线位深 Range 探头按（歌键＋直链）只探一次：rangeProbeKey＝本次探头的键，
     * rangeProbeInfo＝进行中探头对应的 audioInfo 实例，rangeProbedBitDepth＝已测到
     * 的位深（>0）或 -1（未测到／失败，不重试刷屏）。
     */
    private String rangeProbeKey;
    private MusicAudioInfo rangeProbeInfo;
    private boolean rangeProbing;
    private int rangeProbedBitDepth = -1;

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
    /** 预取链落槽时刻（墙钟）：签名直链有时效，超 TTL 的预取链一律作废回源重取，
     * 不许拿陈旧链直播放（失败还会走跳歌、把好歌跳过去）。 */
    private long preloadTime;
    /** 预取链有效期：10 分钟（预取发生在本首起播后，正常在一首歌内就被消费）。 */
    private static final long PRELOAD_TTL_MS = 10 * 60 * 1000L;
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
        // 节拍分析器（瓜子 cg0 移植）：串在均衡之后，分析最终信号、透传不改声，
        // 帧发布到 MusicBeatState 供播放页流体背景消费；开关门由 MusicBeatState.requestEnabled 控。
        beatAnalyzer = new MusicBeatAnalyzer();
        // 本机 Media3 分支的 ExoPlayer.Builder 没有 setAudioProcessors，均衡链改走 AudioSink 注入：
        // DefaultRenderersFactory.createRenderers 调 buildAudioSink 取 AudioSink，子类覆写它即可把处理链串进音频渲染器（默认实现只设两个开关，本覆写原样照搬再加处理链）。
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setAudioProcessors(new AudioProcessor[]{parametricEq, beatAnalyzer})
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
        setMediaNotificationProvider(new DiagNotificationProvider(new MusicNotificationProvider(this, FG_NOTIFICATION_ID, buildPlayerIntent())));
        SpiderDebug.log("music", "notifDiag enabled=" + NotificationManagerCompat.from(this).areNotificationsEnabled());
        restoreBreakpoint();
        startTicker();
        SpiderDebug.log("music", "service onCreate");
    }

    private PendingIntent buildPlayerIntent() {
        try {
            Intent intent = new Intent(this, Class.forName("com.fongmi.android.tv.ui.music.MusicPlayerActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 自绘通知卡的三键命令：显式服务 Intent 直达内部播放控制
        if (intent != null) {
            String action = intent.getAction();
            if (MusicNotificationProvider.ACTION_TOGGLE.equals(action)) toggle();
            else if (MusicNotificationProvider.ACTION_NEXT.equals(action)) next();
            else if (MusicNotificationProvider.ACTION_PREV.equals(action)) prev();
        }
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
        if (sMediaNotificationPosted) {
            // 媒体卡已在岗：占位不许盖（见 sMediaNotificationPosted 注释）
            foregroundFailed = false;
            return;
        }
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
        SpiderDebug.log("music", "notifDiag onGetSession from=" + controllerInfo.getPackageName());
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
        saveBreakpoint(); // M4：failedStopNoBreakpoint 为 true 时内部直接返回，断点保持此前好歌
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
        // M4：play() 非 IDLE 分支已确认出声（IDLE 分支走 prepareTrack，成功回调里清），
        // 此后断点写盘恢复正常。失败停机后播放器是 IDLE，实际走上面分支，这里只是兜底。
        failedStopNoBreakpoint = false;
    }

    public void pause() {
        if (player != null) player.pause();
        saveBreakpoint();
    }

    public void next() {
        autoAdvancing = false;
        startFromHistory = false;
        // P-5b：顺序单首队手动按“下一首”＝重头播（瓜子 skipToNext 口径），不许无反应。
        // 已装载时本地 seek 到 0 并播放；未装载（失败停机／断点态）时按本首重取链起播，同样从头。
        if (queue.getMode() == MusicQueue.MODE_SEQUENTIAL && queue.size() == 1 && queue.current() != null) {
            if (hasMediaItem && player != null && player.getPlaybackState() != Player.STATE_IDLE) {
                seekTo(0);
                if (!isPlaying()) play();
            } else {
                prepareTrack(queue.getIndex(), 0, true);
            }
            return;
        }
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

    /**
     * D11：模式键循环顺序照瓜子 PlayerViewModel:4946——顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。
     * 数值口径不变（0 顺序／1 单曲／2 列表／3 随机），只改循环次序；工具面板段控是直接点选（setMode），不受影响。
     */
    public void cycleMode() {
        int m = queue.getMode();
        int next;
        switch (m) {
            case MusicQueue.MODE_SEQUENTIAL:
                next = MusicQueue.MODE_REPEAT_ALL;
                break;
            case MusicQueue.MODE_REPEAT_ALL:
                next = MusicQueue.MODE_REPEAT_ONE;
                break;
            case MusicQueue.MODE_REPEAT_ONE:
                next = MusicQueue.MODE_SHUFFLE;
                break;
            default:
                next = MusicQueue.MODE_SEQUENTIAL;
                break;
        }
        setMode(next);
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

    /**
     * 当前播放器的音频会话 id（只读口，X-15）：供播放页律动包络挂 Visualizer。
     * 无播放器/取不到时返回 0；不改任何播放行为。
     */
    public int getAudioSessionId() {
        try {
            return player == null ? 0 : player.getAudioSessionId();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 三-42（只读口）：当前真起播那首的取链渠道显示串，供信息页「取链渠道」行。
     * 按 trackKey 守卫：传入歌不是起播时记下的那首（切歌／失败停留态）返回 null，
     * 信息页显「—」。渠道无记录同样返回 null，不许拿平台名或日志冒充渠道。
     * 有命中插件时附插件显示名（按 srcUrl 反查站点名，查不到就显 srcUrl 本身）。
     */
    public String getPlayChannelLabel(MusicTrack track) {
        try {
            if (track == null || playChannelKey == null) return null;
            if (!playChannelKey.equals(MusicQueue.trackKey(track))) return null;
            String label;
            switch (playChannelKind) {
                case MusicRepository.PLAY_CHANNEL_ASSIGNED:
                    label = getString(R.string.music_channel_assigned);
                    break;
                case MusicRepository.PLAY_CHANNEL_LX:
                    label = getString(R.string.music_channel_lx);
                    break;
                case MusicRepository.PLAY_CHANNEL_FREEJS:
                    label = getString(R.string.music_channel_freejs);
                    break;
                case MusicRepository.PLAY_CHANNEL_DIRECT:
                    label = getString(R.string.music_channel_direct);
                    break;
                default:
                    return null;
            }
            if (playChannelSrc != null && !playChannelSrc.isEmpty()) {
                String name = null;
                try {
                    for (com.fongmi.android.tv.bean.music.MusicSite site
                            : MusicSourceManager.get().listSites()) {
                        if (site != null && playChannelSrc.equals(site.srcUrl)) {
                            name = site.name;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                label += " · " + (name != null && !name.isEmpty() ? name : playChannelSrc);
            }
            return label;
        } catch (Throwable t) {
            return null;
        }
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
        currentPlayUrl = null;
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
        currentPlayUrl = null;
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
            SpiderDebug.log("music", "sleep timer fired delayMs=%d mode=%s finishCurrent=%s",
                    delayMs, sleepName(sleep), MusicStore.isSleepFinishCurrent());
            sleepDeadlineElapsed = 0;
            // D3：分钟档＋播完再停开着时，到点不暂停、不清档位、不发结束通知、不 stopSelf，
            // 只记等待态：档位仍是分钟档、剩余已归 -1，面板据此显示“本首结束后停止”，曲终回调里再停。
            // 开关在面板侧存 MusicStore（控制器无新透传口，服务到点时现读，避免面板先选档后开开关时缓存过期）。
            if (MusicStore.isSleepFinishCurrent()) {
                sleepWaitingForSongEnd = true;
                listeners.forEach(l -> l.onSleepChanged(sleep));
                return;
            }
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
        sleepWaitingForSongEnd = false;
        if (sleepTask != null) {
            main.removeCallbacks(sleepTask);
            sleepTask = null;
        }
    }

    /** D3 等待态（分钟档到点后、曲终前）：供后波面板／调试直读，当前面板按“分钟档＋剩余 -1”推断同一状态。 */
    public boolean isSleepWaitingForSongEnd() {
        return sleepWaitingForSongEnd;
    }

    /**
     * 第二波 B4：关“播完本首再停”开关时若已在等待态，面板侧调本方法同步清等待态。
     * 语义：等待态只在分钟档／自定义档到点后才置位，到点时计时任务已经跑完、剩余已归 -1，
     * 所以只清等待态即定时彻底失效——曲终 onTrackEnded 不再消费等待态停播。
     * 未到点时等待态本就是 false，本方法无操作、计时链不动：到点时 armSleep 现读
     * 开关（已关）仍按到点即停口径执行，不会把未到点的定时也取消。
     * 手动切歌不调本方法：D3 deliberate 设计——等待的是“下一曲终”不是原曲终，
     * 切歌后等待态保留、下一曲终才停，本方法只给“关开关”这条路用。
     */
    public void clearSleepWaitingForSongEnd() {
        if (!sleepWaitingForSongEnd) return;
        sleepWaitingForSongEnd = false;
        listeners.forEach(l -> l.onSleepChanged(sleep));
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
        if (f.channelCount > 0) info.channelCount = f.channelCount;
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
     * 位深只对实查到的文件头填（FLAC/WAV 文件头）：本地文件读本地头，在线无损
     * 歌走 Range 探头向直链要头；拿不到就保持 -1、master 保持 false。查实 24 位
     * 以上才改 master。
     */
    private void probeBitDepth(final MusicAudioInfo info) {
        if (info == null || !info.lossless || info.bitDepth >= 0) return;
        MusicTrack track = queue.current();
        if (track == null) return;
        if (track.localPath != null && !track.localPath.isEmpty()) {
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
            return;
        }
        probeBitDepthOnline(info, track);
    }

    /**
     * 在线位深 Range 探头：只对在线无损歌、位深未知时跑。向当前曲实际起播直链
     * 要头 128 字节（Range: bytes=0-127），用本地同一套头解析算位深；服务器不
     * 支持 Range 返回整文件时只读 128 字节就断开，绝不把整文件下下来；其它状态
     * 码、异常、解析 -1 一律保持未知，不改显示、不重试刷屏。播放器构造一律不碰。
     */
    private void probeBitDepthOnline(final MusicAudioInfo info, MusicTrack track) {
        String url = currentPlayUrl;
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        String key = MusicQueue.trackKey(track) + "|" + url;
        if (key.equals(rangeProbeKey)) {
            if (!rangeProbing && rangeProbedBitDepth > 0) {
                info.bitDepth = rangeProbedBitDepth;
                info.master = info.lossless && rangeProbedBitDepth >= 24;
                listeners.forEach(ServiceListener::onAudioInfoChanged);
            } else if (rangeProbing) {
                rangeProbeInfo = info;
            }
            return;
        }
        rangeProbeKey = key;
        rangeProbeInfo = info;
        rangeProbing = true;
        rangeProbedBitDepth = -1;
        final String fKey = key;
        final String fUrl = url;
        Task.execute(() -> {
            int bd = fetchHeaderBitDepth(fUrl);
            App.post(() -> {
                if (!fKey.equals(rangeProbeKey)) return; // 已换歌，旧结果作废
                rangeProbing = false;
                if (bd > 0) rangeProbedBitDepth = bd;
                if (bd <= 0) return;
                if (audioInfo != rangeProbeInfo) return; // 实例已换，旧结果作废（同歌新实例由缓存分支补写）
                rangeProbeInfo.bitDepth = bd;
                rangeProbeInfo.master = rangeProbeInfo.lossless && bd >= 24;
                listeners.forEach(ServiceListener::onAudioInfoChanged);
            });
        });
    }

    /** 向直链要头 128 字节并解析位深；拿不到返回 -1（未知），调用方不改显示。 */
    private static int fetchHeaderBitDepth(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Range", "bytes=0-127");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 206 && code != 200) return -1;
            java.io.InputStream in = conn.getInputStream();
            if (in == null) return -1;
            byte[] head = new byte[128];
            int n = 0;
            int r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            return MusicBitDepthProbe.probeHeader(head, n);
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
     * 往下逐档都没有时（P-1b，用户已定：自动播源给出的最高档），再从最高档往下试到所选档
     * 上一档，拿到最高可用档就播，不提示无档；往下有可用档时仍按原降档顺序走，不一律最高档。
     * 降档成功不算失败；上下都没有才返回 null，交回原失败路径（熔断/跳歌）。
     * 已被新请求接管（seq 过期）就停手，旧结果反正会被作废，别白耗源请求。
     * 向上补档与向下同口径：同一 8 秒重试窗、同一 seq／中断检查、密钥问题同样直接回 null。
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
                url = MusicRepository.playUrl(track, q, !autoAdvancing);
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
        if (System.currentTimeMillis() - start > FALLBACK_RETRY_AFTER_MS) return null;
        for (int q = MusicStore.QUALITY_SUPER; q > quality; q--) {
            if (seq != prepareSeq) return null;
            if (System.currentTimeMillis() - start > FALLBACK_RETRY_AFTER_MS) {
                SpiderDebug.log("music", "playUrl fallback up stop (time) id=%s", track.id);
                break;
            }
            if (Thread.currentThread().isInterrupted()) return null;
            String url;
            try {
                url = MusicRepository.playUrl(track, q, !autoAdvancing);
            } catch (LxKeyIssue e) {
                if (!keyIssueFired) {
                    keyIssueFired = true;
                    listeners.forEach(l -> l.onKeyIssue(e.getPlatform(), e.getMessage()));
                }
                return null;
            }
            if (url != null && !url.isEmpty()) {
                SpiderDebug.log("music", "playUrl fallback up id=%s %s->%s", track.id,
                        MusicStore.qualityName(quality), MusicStore.qualityName(q));
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
        long at = preloadTime;
        preloadKey = null;
        preloadUrl = null;
        preloadTime = 0;
        if (url == null || url.isEmpty() || preloadQuality != quality) return null;
        // 陈旧签名链作废：返回 null 让 prepareTrack 走正常取链回源重取，不直播旧链
        if (System.currentTimeMillis() - at > PRELOAD_TTL_MS) return null;
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
        // 出词提速：下一首的词与链同批预取（同门控），切过去时缓存已有词、跟歌一起出。
        // 共享单飞保证与起播同刻/页面那份调用并成一条链，不重复跑。取词任务内含等链
        // 收口的阻塞等待，不许占音乐池（4 线程，与取链共用会互相饿死）——走大池。
        Task.largeExecutor().execute(() -> {
            try {
                MusicRepository.fetchLyricForPlayback(snapshot);
            } catch (Throwable ignored) {
            }
        });
        // 预加载走音乐专用池（P3）：不占共享 5 线程池，不许预加载僵尸任务拖垮正式取链
        Task.executeMusic(() -> {
            String url = null;
            try {
                url = MusicRepository.playUrl(snapshot, q);
            } catch (Throwable ignored) {
            }
            final String fUrl = url;
            App.post(() -> {
                if (key.equals(preloadKey) && fUrl != null && !fUrl.isEmpty()) {
                    preloadUrl = fUrl;
                    preloadTime = System.currentTimeMillis();
                }
            });
        });
    }

    /** 出词提速：起播同刻的服务侧取词。只备词＋回写缓存＋（仍是当前歌才）通知页面，
     * 不碰播放器、不碰队列；失败静默（页面自己那份链照常兜底）。 */
    private void startLyricFetch(MusicTrack track) {
        if (track == null) return;
        // 等链收口是阻塞等待，走大池不占音乐池（取链在音乐池，互不饿死）
        Task.largeExecutor().execute(() -> {
            MusicLyric lyric = null;
            try {
                lyric = MusicRepository.fetchLyricForPlayback(track);
            } catch (Throwable ignored) {
            }
            if (lyric == null || !lyric.hasLyric()) return;
            MusicTrack cur = queue.current();
            if (cur != null && MusicQueue.trackKey(cur).equals(MusicQueue.trackKey(track))) {
                listeners.forEach(l -> l.onLyricReady(track));
            }
        });
    }

    private void prepareTrack(int index, long startPosition, boolean autoplay) {
        MusicTrack track = queue.get(index);
        if (track == null || player == null) return;
        // P-3d＋第二波堵-4：新一次取链开始时清掉上次取链的死插件／死通道记录（本次取链内有效，
        // 换歌／重试都重新算，不许跨取链拉黑好插件；同一次取链内的降档循环不再重进已死指派插件，
        // 见 MusicRepository）。仓库层、Lx 源内、FreeJs 桥内三处一起清，不许只清仓库层
        // （三处清集均幂等，仓库层方法内也联动清两源内，重复清无害）。
        MusicRepository.clearDeadPlayUrlAttempt();
        LxMusicSource.clearDeadPlayUrlAttempt();
        FreeJsBridge.clearDeadPlayUrlAttempt();
        // 正在取链时又点了新歌：不丢弃新请求，用序号让旧结果作废、新请求接管，
        // 否则旧链回来发现队列已换只会静默返回，点了跟没点一样。
        preparing = true;
        final int seq = ++prepareSeq;
        // 手动切歌先停旧声（本 bug 实根）：取链完成前播放器不被触碰，旧歌会一直响到
        // 新链装上为止——队列／界面受理即切到新歌、声音还是旧歌，用户看着就是
        // 「多开了一个播放器、两套各放各的」（真机：播 A 时搜索点酷狗等别源的 B，
        // B 的页面与队列已成一套，响的还是 A）。手动受理即暂停旧歌：只暂停，
        // 不清 loadedTrack／hasMediaItem——Q3③ 取链期删目标的回滚与 W-E 落位判定
        // 照旧；成功回调 setMediaItem 后按 autoplay 正常出声，失败走原收口。
        // 自动推进（连播／失败跳歌，autoAdvancing 已置位）不暂停，保无缝衔接；
        // 同曲重备（音质重载／重点当前行）不暂停，避免无谓顿挫。
        if (!autoAdvancing && loadedTrack != null && loadedTrack != track && isPlaying()) {
            try {
                player.pause();
            } catch (Throwable ignored) {
            }
        }
        // 出词提速：取词与取链同刻启动、并行——不等播放器回调、不等播放页绑定。
        // 与页面那份取词走共享单飞并成一条链，结果回写缓存；备好时若仍是当前歌，
        // 经 onLyricReady 通知页面直接读缓存上屏（词跟歌一起到）。
        startLyricFetch(track);
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
                    currentPlayUrl = null;
                    lastFailReasonRes = autoAdvancing ? R.string.music_fail_next_url : R.string.music_fail_url;
                    onPlayFailed(track);
                    return;
                }
                // 音乐播放器入口同款拦截（视频那路 Exo 1004 的同源问题）：非 http(s)
                // 直链绝不交给 player.setMediaItem→prepare。放行口径与视频一致再加
                // 本地音乐：http(s) 在线链、content/file 本地歌（DeviceMusicSource
                // 给的就是 content:// 与 file://），其余协议（magnet/ed2k 等未转换
                // 链）走 onPlayFailed 可见失败＋自动跳歌，不进播放器抛 1004。
                String fScheme = android.net.Uri.parse(fUrl).getScheme();
                if (!"http".equalsIgnoreCase(fScheme) && !"https".equalsIgnoreCase(fScheme)
                        && !"content".equalsIgnoreCase(fScheme) && !"file".equalsIgnoreCase(fScheme)) {
                    SpiderDebug.log("music", "playUrl non-http scheme=%s id=%s", fScheme, track.id);
                    currentPlayUrl = null;
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
                currentPlayUrl = fUrl;
                // 三-42：与直链同点记渠道（按 trackKey 从仓库旁路记录读那次命中；
                // 预载命中的链也是预载取链时记的同键记录，读到的就是真渠道）
                playChannelKey = MusicQueue.trackKey(track);
                playChannelKind = MusicRepository.playChannelKindOf(playChannelKey);
                playChannelSrc = MusicRepository.playChannelSrcOf(playChannelKey);
                player.setMediaItem(item, startPosition);
                hasMediaItem = true;
                loadedTrack = track;
                player.prepare();
                ensureFx(); // 会话可能随音频汇重建而变，音效跟着重新挂
                player.setPlaybackSpeed(getSpeed());
                if (autoplay) player.play();
                else player.pause();
                // M4：新歌已装载起播（prepareTrack 成功回调），清失败停机断点抑制——
                // 下面 saveBreakpoint 存的就是新歌，此后断点写盘恢复正常。
                failedStopNoBreakpoint = false;
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
        // 自动跳歌链后台不断链：失败收口前先顶回前台，免得后台跳歌时回调被推迟到回前台才执行。
        if (autoAdvancing) startForegroundNow();
        if (MusicStore.isAutoSkipOnError() && advancePastFailed(failed)) return;
        // 第三波 G-B5：lx: 歌且无支撑落雪脚本时，提示改指缺脚本而非通用失败
        if (!lxNoSupportNotified(failed)) Notify.show(R.string.music_play_failed_stopped);
        if (autoAdvancing) {
            // 自动推进保留原口径：停机＋撤前台（stopPlayback 报“无曲目”沿用旧行为，不擅自改）
            // M4 新口径（停机后按播放重试失败歌）：队列游标仍指失败歌（见 advancePastFailed，
            // 成功路径 moveTo 时机不动、不回滚旧歌），同会话内按播放即从失败歌重取；
            // 但断点不许写成失败歌——置 failedStopNoBreakpoint，saveBreakpoint 直接返回，
            // 保留此前已存的断点（重启后回到上次好歌，不是失败歌；手动路径的 stopAtFailed
            // 显式存失败歌＋0 进度口径不动，两条线不混）。
            stopPlayback();
            failedStopNoBreakpoint = true;
        } else {
            // R12：手动切歌失败停在失败态＋可重试——队列仍指失败歌、按播放从 0 重取，
            // 不静默弹回旧歌（旧 rollbackToLoaded 口径只留给取链期删／移目标的 Q3③情形）
            stopAtFailed(failed);
        }
        notifyPlayFailedStopped(failed, hasNextAfterFailed(failed));
        demoteForeground();
    }

    /**
     * 自动跳歌：找下一首可试的（绕开已拉黑的），找到并起播返回 true。
     * 自动推进时以失败歌序号作起点：Q3 口径下成功前不 moveTo，取链失败时队列仍指
     * 已播完的旧歌，直接用 nextIndex() 会再算出同一首失败歌（第 1 次失败还没拉黑），
     * 白吃一次 20 秒看门狗——先把游标指到失败歌再往后找。随机模式下黑名单越过会把
     * 游标移开、之后随机仍可能抽回失败歌，遇上它同样只移游标不起播。
     * 手动路径不改：沿用旧口径（第 1 次失败重备一次、第 2 次拉黑后越过）。
     * 成功路径 moveTo 时机不动，只改失败路径的搜索起点。
     * M4 新口径（停机后按播放重试失败歌）：自动推进找遍全队都失败时，游标停在失败歌
     * （上面 moveTo(fi)／越过拉黑项的结果），不指回旧歌；onPlayFailed 自动停机分支
     * 只停播放器＋置 failedStopNoBreakpoint 抑止断点写盘，不改游标、不改成功路径
     * moveTo 时机、不碰 Q3 回滚与手动路径。同会话按播放即重试失败歌，重启后断点
     * 仍是此前好歌（见 onPlayFailed 自动分支注释）。
     */
    private boolean advancePastFailed(MusicTrack failed) {
        int size = queue.getTracks().size();
        if (size == 0 || consecutiveFails >= size) return false;
        String failedKey = failed == null ? null : MusicQueue.trackKey(failed);
        if (autoAdvancing && failed != null) {
            int fi = indexOfTrack(failed);
            if (fi >= 0) queue.moveTo(fi);
        }
        for (int i = 0; i < size; i++) {
            int n = queue.nextIndex();
            if (n < 0 || n == queue.getIndex()) return false; // 没下一首／下一首就是自己
            MusicTrack nt = queue.get(n);
            if (autoAdvancing && failedKey != null && nt != null
                    && (nt == failed || failedKey.equals(MusicQueue.trackKey(nt)))) {
                queue.moveTo(n); // 本次失败歌：只移游标、不起播，下一轮抽到的必不是它
                continue;
            }
            if (nt != null && failedBlacklist.contains(MusicQueue.trackKey(nt))) {
                queue.moveTo(n); // 拉黑的先越过，继续往后找
                continue;
            }
            autoAdvancing = true;
            // P-3b：自动跳歌成功（已找到下一首并开始取链）时发一次带歌名提示，不只停机时提示；
            // 失败歌名用 displayTitle，失败歌为空时不发（advancePastFailed 只在有失败歌时被调，空是兜底）。
            if (failed != null) {
                String title = failed.displayTitle();
                if (title != null && !title.isEmpty()) Notify.show("「" + title + "」无法播放，已自动跳过");
            }
            prepareTrack(n, 0, true);
            return true;
        }
        return false;
    }

    /** 队里找一首歌的序号：先按引用，引用找不着（队列被重建）再按 trackKey 回落，找不着返回 -1 */
    private int indexOfTrack(MusicTrack track) {
        if (track == null) return -1;
        List<MusicTrack> tracks = queue.getTracks();
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i) == track) return i;
        }
        String key = MusicQueue.trackKey(track);
        for (int i = 0; i < tracks.size(); i++) {
            if (key.equals(MusicQueue.trackKey(tracks.get(i)))) return i;
        }
        return -1;
    }

    /**
     * 失败歌后面是否还有一首不同的歌（只算存在性，不算能不能试：已拉黑／熔断的也算存在，
     * 用户手动按下一首仍能走到它）。按失败歌在队里的位置＋模式算，不动队列游标；
     * 失败歌已不在队里（取链期被删）时回落 queue.hasNext() 旧口径。
     */
    private boolean hasNextAfterFailed(MusicTrack failed) {
        if (failed == null) return queue.hasNext();
        int fi = indexOfTrack(failed);
        if (fi < 0) return queue.hasNext();
        int size = queue.getTracks().size();
        switch (queue.getMode()) {
            case MusicQueue.MODE_REPEAT_ONE:
                return false; // 下一首就是自己，没有不同的下一首
            case MusicQueue.MODE_SHUFFLE:
            case MusicQueue.MODE_REPEAT_ALL:
                return size > 1;
            default:
                return fi + 1 < size;
        }
    }

    /** 不起播收口（对差清单 D5）：停播后撤前台，别把通知空挂着 */
    private void demoteForeground() {
        sMediaNotificationPosted = false;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 失败停机通知（用户原话：不在播放器里就看不见失败）：
     * 失败收口停机时在通知栏留一条看得见的通知——标题写停了、正文写失败歌名＋原因＋
     * 下一首是否存在，点通知回播放器；恢复播放（STATE_READY）时撤掉，不留过期通知。
     * 只在 onPlayFailed 走到停机这条线才发：顺序播到尾（onTrackEnded 的 pause）
     * 与用户清队/删空（直接 stopPlayback）都不经过这里，不会误刷；定时停机走另一条
     * 定时通知（标题不同），后台看到本通知即知是失败停机、不是到尾也不是定时。
     */
    /** 第三波 A4/G-B5：失败歌的来源名（FreeJS＝插件名，落雪＝平台名，其余＝platform 原值）。 */
    private String failSourceName(MusicTrack failed) {
        if (failed == null) return "";
        try {
            if ("lx".equals(failed.platform) && failed.id != null) {
                String[] parts = failed.id.split(":", 3);
                if (parts.length >= 2) return LxPlatformSearch.platformName(parts[1]);
            }
            if (failed.rawJson != null && failed.rawJson.contains("\"_plugin\"")) {
                org.json.JSONObject wrap = new org.json.JSONObject(failed.rawJson);
                String plugin = wrap.optString("_plugin", "");
                if (!plugin.isEmpty()) {
                    for (com.fongmi.android.tv.bean.music.MusicSite site
                            : MusicSourceManager.get().listSites()) {
                        if (plugin.equals(site.srcUrl)) return site.name == null ? "" : site.name;
                    }
                    return plugin;
                }
            }
        } catch (Throwable ignored) {
        }
        return failed.platform == null ? "" : failed.platform;
    }

    /** 第三波 G-B5：lx: 歌且无支撑脚本时发缺脚本提示并返回 true（调用方不再发通用失败）。 */
    private boolean lxNoSupportNotified(MusicTrack failed) {
        if (failed == null || failed.id == null || !failed.id.startsWith("lx:")) return false;
        try {
            String[] parts = failed.id.split(":", 3);
            if (parts.length < 2) return false;
            if (!PlaylistImporter.hasLxSupport(parts[1])) {
                Notify.show("播不了：缺支持" + LxPlatformSearch.platformName(parts[1])
                        + "的落雪脚本，去插件管理装一个");
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void notifyPlayFailedStopped(MusicTrack failed, boolean hasNext) {
        try {
            int reasonRes = lastFailReasonRes != 0 ? lastFailReasonRes : R.string.music_fail_unknown;
            String text = getString(reasonRes);
            if (failed != null) {
                String title = failed.displayTitle();
                String src = failSourceName(failed);
                if (title != null && !title.isEmpty()) {
                    text = title + (src.isEmpty() ? "" : " · " + src) + " · " + text;
                }
            }
            text += hasNext ? "，还有下一首" : "，已无下一首";
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // X-32：无通知权限不许无声消失——落日志＋吐司兜底（Notify.show(String)
                // 走 ToastUtil、不需要通知权限；同文件 lxNoSupportNotified 已有先例）。
                // 文案与通知版正文同串。吐司在后台可能被系统抑制，真机为准。
                SpiderDebug.log("music", "notifyPlayFailedStopped no notification permission, toast fallback: %s", text);
                Notify.show(text);
                return;
            }
            NotificationManagerCompat mgr = NotificationManagerCompat.from(this);
            mgr.createNotificationChannel(new NotificationChannelCompat.Builder(FAIL_NOTIFICATION_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(getString(R.string.music_play_stopped_channel)).build());
            Notification notification = new NotificationCompat.Builder(this, FAIL_NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(R.string.music_play_stopped_title))
                    .setContentText(text)
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // X-32：无通知权限不许无声消失——落日志＋吐司兜底（Notify.show(String)
                // 走 ToastUtil、不需要通知权限）。文案＝通知版标题＋正文两串拼接。
                // 吐司在后台可能被系统抑制，真机为准。
                String text = getString(R.string.music_sleep_title) + "：" + getString(R.string.music_play_stopped_title);
                SpiderDebug.log("music", "notifySleepEnded no notification permission, toast fallback: %s", text);
                Notify.show(text);
                return;
            }
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
                // M4：STATE_READY＝真起播，清失败停机断点抑制（与 prepareTrack 成功回调双确认点）。
                failedStopNoBreakpoint = false;
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
                MusicTrack errTrack = loadedTrack != null ? loadedTrack : queue.current();
                SpiderDebug.log("music", "onPlayerError code=%d id=%s msg=%s cause=%s", error.errorCode, errTrack != null ? errTrack.id : "null", error.getMessage(), error.getCause());
                // P1：取链期播放器里还装着旧歌，旧歌此时报错不许算到新歌头上——
                // 取链在途且当前序号已指新歌时，报错属于旧歌，直接忽略（新歌取链回来
                // setMediaItem＋prepare 会恢复播放器）；不置 preparing、不顶序号、不进失败计数。
                // 只有取链不在途、或当前就是装着的那首时，报错才属于当前歌。
                if (preparing && loadedTrack != null && queue.current() != loadedTrack) return;
                // 卡尾错误按曲终走：STUCK_PLAYING_NOT_ENDING＝播放头已越过标称时长、
                // 停在 READY 结不掉（真机日志实证：整首播完后挂 60 秒才抛这个错），
                // 歌实际已经播完——不许记失败/拉黑/走失败收口，按自然曲终决策推进。
                // 走到这里说明 ticker 越界看门狗也没接住（主线程被卡等极端情形），是最后兜底。
                if (!endedConsumed && isStuckNotEnding(error)) {
                    endedConsumed = true;
                    preparing = false;
                    SpiderDebug.log("music", "trackEnded via stuck error mode=%d", queue.getMode());
                    onTrackEnded();
                    return;
                }
                preparing = false;
                lastFailReasonRes = R.string.music_fail_play_error;
                onPlayFailed(loadedTrack != null ? loadedTrack : queue.current());
            } catch (Throwable t) {
                // X-31：错误处理自己再抛错不许静默吞——落日志留痕再收口，
                // 否则失败收口断在哪一步无从查起。
                SpiderDebug.log("music", "onPlayerError handler failed %s", t);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            MusicBeatState.playing = isPlaying;
            if (!isPlaying) MusicBeatState.silence();
            listeners.forEach(l -> l.onPlayingChanged(isPlaying));
            // 双声兜底下沉服务侧：会话/通知/耳机起播绕开控制器入口时也停视频；
            // 与控制器钩同轮重复调用无副作用（pauseVideoForMusic 内有「视频没在播就返回」守卫）。
            if (isPlaying) MusicController.pauseVideoForMusic();
            if (!isPlaying) saveBreakpoint();
        }
    };

    /** error 的成因链里是不是 media3 的「越过标称时长结不掉」卡尾（StuckPlayerException type=3） */
    private static boolean isStuckNotEnding(PlaybackException error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof StuckPlayerException stuck
                    && stuck.stuckType == StuckPlayerException.STUCK_PLAYING_NOT_ENDING) return true;
        }
        return false;
    }

    private void onTrackEnded() {
        // 切歌入口先顶回前台：prepareTrack 成功回调已有此补法（:1598），切歌入口
        // 还没有——前台曾被降（失败收口 demoteForeground 等）后，后台播完切歌时服务
        // 一度是“裸奔”状态，主线程回调易被推迟到回前台才执行。
        startForegroundNow();
        // D3：分钟档等待态曲终——先消费等待态再清档位、暂停（与本首结束独立模式同口径的停法，
        // 不进下一首；不 stopSelf，播放页仍需服务在位，结束通知走分钟档原有 notifySleepEnded）。
        if (sleepWaitingForSongEnd) {
            sleepWaitingForSongEnd = false;
            setSleep(SLEEP_OFF);
            pause();
            notifySleepEnded();
            return;
        }
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
                        // P-4b：ticker 节流存盘——250ms 只发进度，不写盘；每 30 秒或进度变化超 10 秒
                        // 才存一次断点，播着歌时进程被杀也能从最近存盘点续播。失败停机抑制态不存（saveBreakpoint
                        // 内部会返回，见下：抑制态直接跳过，不刷新节流时钟，免得把存盘窗口拖后）。
                        if (!failedStopNoBreakpoint && queue.current() != null) {
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (lastTickerSavePosition < 0 || now - lastTickerSaveElapsed >= 30000
                                    || Math.abs(pos - lastTickerSavePosition) >= 10000) {
                                saveBreakpoint();
                                lastTickerSaveElapsed = now;
                                lastTickerSavePosition = pos;
                            }
                        }
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
                    // 卡尾兜底：ENDED 没到、但播放器想播却停在曲尾（厂商吞回调／尾字节挂住），
                    // 直接按曲终决策推进；用户手动暂停（playWhenReady=false）不触发。
                    if (player != null && !preparing && !endedConsumed
                            && player.getPlayWhenReady() && !player.isPlaying()
                            && player.getPlaybackState() != Player.STATE_IDLE
                            && player.getPlaybackState() != Player.STATE_ENDED
                            && player.getDuration() > 0
                            && player.getCurrentPosition() >= player.getDuration() - 500) {
                        endedConsumed = true;
                        SpiderDebug.log("music", "trackEnded via tail watchdog mode=%d", queue.getMode());
                        onTrackEnded();
                    }
                    // 越界兜底（与上面卡尾兜底互补的另一半）：播放头已越过标称时长却仍停在
                    // READY 结不掉——isPlaying() 仍为真，上面那条永远轮不到；media3 要等 60 秒
                    // 才抛 STUCK_PLAYING_NOT_ENDING（真机日志实证整首播完后先挂 60 秒再报错停住）。
                    // 持续越界满 1 秒就按曲终决策推进；正常曲终 ENDED 在一两 tick 内到达，走不到这里。
                    // 用户手动暂停（playWhenReady=false）不触发，与卡尾兜底同口径。
                    if (player != null && !preparing && !endedConsumed
                            && player.getPlayWhenReady()
                            && player.getPlaybackState() == Player.STATE_READY
                            && player.getDuration() > 0
                            && player.getCurrentPosition() >= player.getDuration()) {
                        long nowOverrun = android.os.SystemClock.elapsedRealtime();
                        if (overrunSinceElapsed == 0) overrunSinceElapsed = nowOverrun;
                        if (nowOverrun - overrunSinceElapsed >= 1000) {
                            endedConsumed = true;
                            overrunSinceElapsed = 0;
                            SpiderDebug.log("music", "trackEnded via overrun watchdog mode=%d", queue.getMode());
                            onTrackEnded();
                        }
                    } else {
                        overrunSinceElapsed = 0;
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
        // M4：自动推进失败停机后直接返回，不把失败歌写成断点，保留此前已存的断点；
        // onDestroy 调这里同样受本标志约束（不另设分支）。手动 stopAtFailed 的显式存盘
        // 不走这里，不受影响。
        if (failedStopNoBreakpoint) return;
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

    // 通知不显示取证（2026-10-25 定：随版取证，只打点不改行为）：包住默认 Provider，
    // 记三点——建通知被调与结果、通知变更回调、（配合 onCreate 的 enabled 与 onGetSession 的控制器来源）。
    private static final class DiagNotificationProvider implements MediaNotification.Provider {
        private final MediaNotification.Provider delegate;

        DiagNotificationProvider(MediaNotification.Provider delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaNotification createNotification(MediaSession mediaSession, ImmutableList<CommandButton> mediaButtonPreferences, MediaNotification.ActionFactory actionFactory, Callback onNotificationChangedCallback) {
            MediaNotification result = delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory, notification -> {
                sMediaNotificationPosted = true;
                SpiderDebug.log("music", "notifDiag changed id=" + notification.notificationId);
                onNotificationChangedCallback.onNotificationChanged(notification);
            });
            SpiderDebug.log("music", "notifDiag create -> " + (result == null ? "null" : "id=" + result.notificationId));
            return result;
        }

        @Override
        public boolean handleCustomCommand(MediaSession session, String action, Bundle extras) {
            return delegate.handleCustomCommand(session, action, extras);
        }

        @Override
        public NotificationChannelInfo getNotificationChannelInfo() {
            return delegate.getNotificationChannelInfo();
        }
    }
}
