package com.fongmi.android.tv.music;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 音乐门面：UI/语音只调这里。内部绑 MusicPlaybackService，未连接时操作排队等连上再执行。
 * 迷你条（mobile 侧 ListenMiniBar）也经由这里同步状态。
 */
public class MusicController {

    public interface Listener {
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

        default void onSleepChanged(int sleep) {
        }

        default void onAudioInfoChanged() {
        }

        /** 落雪密钥问题（B4）：脚本明确报了 401／403，UI 侧弹密钥提示；其它失败不走这里 */
        default void onKeyIssue(String platform, String detail) {
        }

        /** 服务落位信号（见 MusicPlaybackService.ServiceListener.onTrackReady），默认空实现 */
        default void onTrackReady(MusicTrack track) {
        }

        /** 服务侧取词备好信号（见 MusicPlaybackService.ServiceListener.onLyricReady），默认空实现 */
        default void onLyricReady(MusicTrack track) {
        }

        /** 当前曲封面被真图换上信号（见 MusicPlaybackService.ServiceListener.onCoverChanged），默认空实现 */
        default void onCoverChanged(MusicTrack track) {
        }

        /** 服务失败信号（见 MusicPlaybackService.ServiceListener.onTrackFailed），默认空实现 */
        default void onTrackFailed(MusicTrack track, int reasonRes) {
        }
    }

    private static volatile MusicController instance;

    public static MusicController get() {
        if (instance == null) {
            synchronized (MusicController.class) {
                if (instance == null) instance = new MusicController();
            }
        }
        return instance;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 视频暂停音乐的所有权旗（X-28 显式来源口径）：只有 pauseForVideo() 置位，
     * 其它 play/pause 公共入口一律清掉，PlayerManager 退出视频时凭此旗决定是否续播。 */
    private volatile boolean pausedByVideo;
    private final List<Runnable> pending = new ArrayList<>();

    private Context app;
    private MusicPlaybackService svc;
    private boolean bound;
    private int bindRef;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((MusicPlaybackService.LocalBinder) binder).getService();
            svc.addListener(serviceListener);
            List<Runnable> ops;
            synchronized (pending) {
                ops = new ArrayList<>(pending);
                pending.clear();
            }
            for (Runnable r : ops) runOnService(r);
            syncMiniBar();
            pushAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (svc != null) svc.removeListener(serviceListener);
            svc = null;
            // 必须复位 bound：旧实现只清 svc，ensureBound 永远被 bound=true 挡住空转，
            // runOnService 的 pending 永不执行、播放控制整体失效直到进程重启（扫描 M6）。
            bound = false;
        }
    };

    private final MusicPlaybackService.ServiceListener serviceListener = new MusicPlaybackService.ServiceListener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            App.post(() -> {
                listeners.forEach(l -> l.onTrackChanged(track));
                syncMiniBar();
            });
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            App.post(() -> {
                // X-28 后半兜底：系统会话／通知等绕开控制器入口的起播也汇到
                // 这里，音乐真出声就把视频停掉（入口钩子是同步主路，这里兜底）。
                if (playing) pauseVideoForMusic();
                listeners.forEach(l -> l.onPlayingChanged(playing));
                syncMiniBar();
                // 第三波 M2（选口径 a）：播放态变化时调策略 playingChanged，让暂停 10 秒
                // 自动收真生效。顺序在 syncMiniBar（register 会把策略 playing 置 true、
                // 取消计时）之后，否则暂停事件会被注册复位盖掉。onCloseSession 对音乐
                // 只是 pause（已暂停时为空操作）＋注销条（仅藏条、队列保留），不停止
                // 服务、不清队列，见 ListenMiniBar.onCloseSession，不会误停。
                try {
                    com.fongmi.android.tv.ui.minibar.ListenMiniBar bar =
                            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(app == null ? App.get() : app);
                    bar.musicPlayingChanged(playing);
                } catch (Throwable ignored) {
                }
            });
        }

        @Override
        public void onTrackReady(MusicTrack track) {
            App.post(() -> listeners.forEach(l -> l.onTrackReady(track)));
        }

        @Override
        public void onLyricReady(MusicTrack track) {
            App.post(() -> listeners.forEach(l -> l.onLyricReady(track)));
        }

        @Override
        public void onCoverChanged(MusicTrack track) {
            App.post(() -> listeners.forEach(l -> l.onCoverChanged(track)));
        }

        @Override
        public void onTrackFailed(MusicTrack track, int reasonRes) {
            App.post(() -> listeners.forEach(l -> l.onTrackFailed(track, reasonRes)));
        }

        @Override
        public void onProgress(long position, long duration) {
            listeners.forEach(l -> l.onProgress(position, duration));
        }

        @Override
        public void onQueueChanged() {
            App.post(() -> {
                listeners.forEach(Listener::onQueueChanged);
                syncMiniBar();
            });
        }

        @Override
        public void onModeChanged(int mode) {
            // 复核必-15：模式变化也要同步迷你条（其它 onTrackChanged／onPlayingChanged／
            // onQueueChanged 三处都调 syncMiniBar，只这一处漏了），先同步再转发。
            App.post(() -> {
                syncMiniBar();
                listeners.forEach(l -> l.onModeChanged(mode));
            });
        }

        @Override
        public void onSleepChanged(int sleep) {
            App.post(() -> listeners.forEach(l -> l.onSleepChanged(sleep)));
        }

        @Override
        public void onAudioInfoChanged() {
            App.post(() -> listeners.forEach(Listener::onAudioInfoChanged));
        }

        @Override
        public void onKeyIssue(String platform, String detail) {
            App.post(() -> listeners.forEach(l -> l.onKeyIssue(platform, detail)));
        }
    };

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 引用计数绑定：页 onCreate 调 bind，onDestroy 调 unbind */
    public void bind(Context context) {
        if (app == null) app = context.getApplicationContext();
        bindRef++;
        ensureBound();
    }

    private void ensureBound() {
        if (bound || app == null) return;
        try {
            Intent it = new Intent(app, MusicPlaybackService.class);
            ContextCompat.startForegroundService(app, it);
            Intent bindIt = new Intent(app, MusicPlaybackService.class).setAction(MusicPlaybackService.LOCAL_BIND_ACTION);
            bound = app.bindService(bindIt, conn, Context.BIND_AUTO_CREATE);
            if (!bound) bindRef = 0;
        } catch (Throwable t) {
            bound = false;
            bindRef = 0;
            SpiderDebug.log("music", "bind failed %s", t.getMessage());
        }
    }

    public void unbind() {
        bindRef = Math.max(0, bindRef - 1);
        if (bindRef > 0 || !bound) return;
        try {
            if (svc != null) svc.removeListener(serviceListener);
            app.unbindService(conn);
        } catch (Throwable ignored) {
        }
        svc = null;
        bound = false;
    }

    private void runOnService(Runnable r) {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s != null) {
            try {
                r.run();
                return;
            } catch (Throwable ignored) {
            }
        }
        synchronized (pending) {
            pending.add(r);
        }
        ensureBound();
    }

    private void pushAll() {
        MusicPlaybackService s = svc;
        if (s == null) return;
        MusicTrack t = s.current();
        boolean playing = s.isPlaying();
        App.post(() -> {
            listeners.forEach(l -> l.onTrackChanged(t));
            listeners.forEach(l -> l.onPlayingChanged(playing));
            listeners.forEach(Listener::onQueueChanged);
            listeners.forEach(l -> l.onModeChanged(s.getQueue().getMode()));
            listeners.forEach(l -> l.onSleepChanged(s.getSleep()));
            // W-E：重绑时当前曲若已落位，补发一次落位信号——只发一次 onTrackChanged 的
            // pushAll 无法让计次页区分“已落位还是取链中”，已落位时以 onTrackReady 补齐；
            // 取链中（未落位）不补发，页内仍按取链期处理，不许把取链中误报成已落位
            if (t != null && s.isCurrentLoaded()) listeners.forEach(l -> l.onTrackReady(t));
        });
    }

    // ================= 播放控制 =================

    /** PM（视频）专用暂停入口：暂停并置所有权旗；本入口不清自己的旗。 */
    public void pauseForVideo() {
        runOnService(() -> service().pause());
        pausedByVideo = true;
    }

    public boolean isPausedByVideo() {
        return pausedByVideo;
    }

    /** 取走所有权旗：返回此前是否持有，调用后旗已清（PM 续播专用）。 */
    public boolean consumePausedByVideo() {
        boolean was = pausedByVideo;
        pausedByVideo = false;
        return was;
    }

    /**
     * 音乐起播停视频（X-28 后半，与 PlayerManager.pauseMusicForVideo 对称）：
     * 经 Server 登记表取当前视频服务（壳子内既有的跨组件取法，Web 桥与本地
     * 服务同款）；服务没起来、播放器已释放、视频没在播，三种情况都直接返回，
     * 无副作用、不抛。本类的每个出声起播入口都先调它。
     */
    public static void pauseVideoForMusic() {
        try {
            PlaybackService videoService = Server.get().getService();
            if (videoService == null) return;
            PlayerManager player = videoService.player();
            if (player == null || player.isReleased()) return;
            if (!player.isPlaying()) return;
            player.pauseForMusic();
        } catch (Throwable ignored) {
        }
    }

    public void play(List<MusicTrack> queue, int index) {
        play(queue, index, null, null, null);
    }

    /** 播队列并写入队列级来源／route（B7：入队时写，面板不再反查） */
    public void play(List<MusicTrack> queue, int index,
                     String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().playQueue(new ArrayList<>(queue), index, source, route, routeParams));
    }

    /**
     * R4 服务钩子（控制器侧）：新队列＋模式一次定（随心听／全局随机入口用），
     * 模式与起队在服务内同一调用完成，不分两步排队串队；模式仍是全局偏好存盘。
     */
    public void playWithMode(List<MusicTrack> queue, int index, int mode,
                             String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().playQueueWithMode(new ArrayList<>(queue), index, mode, source, route, routeParams));
    }

    /**
     * 视频侧列表整拉专用：入队＋列表会话绑定在同一发服务任务里完成——不分两步，
     * 防中间插进别的换队把绑定写到别人队上。会话标识出自哪个列表（源＋分类／搜索词），
     * 该列表后续翻页续载的新歌按 appendToListQueue 同步追进队尾。
     */
    public void playListSession(List<MusicTrack> queue, int index,
                                String source, String route, java.util.Map<String, String> routeParams,
                                String session) {
        if (queue == null || queue.isEmpty()) return;
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> {
            service().playQueue(new ArrayList<>(queue), index, source, route, routeParams);
            service().bindQueueListSession(session);
        });
    }

    /** 同列表续载追加：会话对得上才进当前队列队尾，对不上（队列已换）由服务侧丢弃。 */
    public void appendToListQueue(String session, List<MusicTrack> tracks) {
        if (session == null || session.isEmpty() || tracks == null || tracks.isEmpty()) return;
        runOnService(() -> service().appendToQueueIfSession(session, new ArrayList<>(tracks)));
    }

    /** 播队列并从断点位置起 */
    public void playFrom(List<MusicTrack> queue, int index, long position) {
        playFrom(queue, index, position, null, null, null);
    }

    /** 播队列并从断点位置起，同时写入队列级来源／route（B7） */
    public void playFrom(List<MusicTrack> queue, int index, long position,
                         String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().playQueueFrom(new ArrayList<>(queue), index, position, source, route, routeParams));
    }

    public void playAt(int index) {
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().playAt(index));
    }

    /**
     * W-E 配合（R2 暂停态恢复）：只换队不起播——换队并把目标歌加载到断点位置后暂停，
     * 不出声；不要用 playFrom 假装恢复（它会起播）。空队直接返回。
     */
    public void loadQueuePaused(List<MusicTrack> queue, int index, long position,
                                String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        pausedByVideo = false;
        runOnService(() -> service().loadQueuePaused(new ArrayList<>(queue), index, position, source, route, routeParams));
    }

    /** 当前曲是否已落位（见 MusicPlaybackService.isCurrentLoaded）；服务未连接时 false */
    public boolean isCurrentLoaded() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s != null && s.isCurrentLoaded();
    }

    public void toggle() {
        pausedByVideo = false;
        // toggle 是双向键：当前没在播时这一按是切向播放、才停视频；
        // 正在播时这一按是暂停，只停音乐，不许连带把在播的视频停掉。
        if (!isPlaying()) pauseVideoForMusic();
        runOnService(() -> service().toggle());
    }

    public void play() {
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().play());
    }

    public void pause() {
        pausedByVideo = false;
        runOnService(() -> service().pause());
    }

    public void next() {
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().next());
    }

    /** 复核 B6：迷你条下一首设防口径——不再直接复用 hasNext()（MusicQueue.hasNext 本体不动）：
     * P-5b 顺序单首队手动下一首＝重头播，当前歌非空即为 true；否则按 nextIndex() >= 0 判定。
     * 后者同时修顺序模式游标 -1 但队非空时 hasNext() 为 true 的死钮（nextIndex() 在 index < 0 时返回 -1）。
     * 服务为空时按无下一首处理（不亮可点态）。 */
    public boolean canNext() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) return false;
        try {
            MusicQueue q = s.getQueue();
            if (q.getMode() == MusicQueue.MODE_SEQUENTIAL && q.size() == 1 && q.current() != null) return true;
            return q.nextIndex() >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void prev() {
        pausedByVideo = false;
        pauseVideoForMusic();
        runOnService(() -> service().prev());
    }

    public void seekTo(long ms) {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s != null) s.seekTo(ms);
    }

    public void removeAt(int i) {
        runOnService(() -> service().removeAt(i));
    }

    /** 队列移除契约（另一工调用口径，方法名定死）：按 key 删，key 口径见 MusicQueue.trackKey */
    public void removeByKey(String key) {
        if (key == null) return;
        runOnService(() -> service().removeByKey(key));
    }

    public void move(int from, int to) {
        runOnService(() -> service().move(from, to));
    }

    public void clearQueue() {
        runOnService(() -> service().clearQueue());
    }

    public void insertNext(MusicTrack track) {
        runOnService(() -> service().insertNext(track));
    }

    /** 加到播放队尾 */
    public void appendTrack(MusicTrack track) {
        if (track == null) return;
        runOnService(() -> service().appendTrack(track));
    }

    /** 倍速 */
    public void setSpeed(float speed) {
        runOnService(() -> service().setSpeed(speed));
    }

    public float getSpeed() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? MusicStore.getSpeed() : s.getSpeed();
    }

    /** 音频会话 id（只读转发，X-15 律动包络用；服务不在时 0） */
    public int audioSessionId() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? 0 : s.getAudioSessionId();
    }

    /** 取链渠道显示串（只读转发，三-42 信息页用；服务不在／非起播那首／无记录时 null） */
    public String playChannelLabel(MusicTrack track) {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null || track == null ? null : s.getPlayChannelLabel(track);
    }

    public void setMode(int mode) {
        runOnService(() -> service().setMode(mode));
    }

    public void cycleMode() {
        runOnService(() -> service().cycleMode());
    }

    public int getMode() {
        // 读状态走空安全取法，不许走会抛的 service()：播放页 initView 在 bind 的
        // onServiceConnected 回来之前就读模式，服务没就绪时抛 IllegalStateException
        // 会直接崩掉开页（1.4.11 真机实证）。服务不在时回落存盘模式，口径与本类
        // 其它读法（getSpeed/queue 等）一致。
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? MusicStore.getMode() : s.getQueue().getMode();
    }

    public void setQuality(int quality) {
        MusicStore.setQuality(quality);
        // 高10：选档不许只落盘——写档后把「当前曲按新档重取」递给服务侧判定
        // （无当前曲／本地类／生效档没变都在 reloadCurrent 里挡下，这里无条件递）。
        // 不走 reloadQuality()：它带 pauseVideoForMusic，换档不是起播，
        // 视频正在播时不许被一次换档暂停。
        runOnService(() -> service().reloadCurrent());
    }

    public int getQuality() {
        return MusicStore.getQuality();
    }

    /** 音质切换后重载当前歌曲 */
    public void reloadQuality() {
        pauseVideoForMusic();
        runOnService(() -> service().reloadCurrent());
    }

    public void setSleep(int sleep) {
        runOnService(() -> service().setSleep(sleep));
    }

    /** 第二波 B4 接线：面板关“播完本首再停”开关时调服务侧清等待态（定时彻底失效）。 */
    public void clearSleepWaitingForSongEnd() {
        runOnService(() -> service().clearSleepWaitingForSongEnd());
    }

    /** 第二波 B4 显示层用：服务侧是否正处分钟档到点后的等待态（面板不再只凭“档位＋剩余 -1”推断）。 */
    public boolean isSleepWaitingForSongEnd() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s != null && s.isSleepWaitingForSongEnd();
    }

    public int getSleep() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? MusicPlaybackService.SLEEP_OFF : s.getSleep();
    }

    public long sleepRemainingMs() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? -1 : s.getSleepRemainingMs();
    }

    // ================= 音效（App 内面板 → 服务 audiofx） =================

    /** 音效全量状态（面板打开用）；服务没起来不返回 null（体检 E-P7）：
     * 按存档回落组装（live=false，与 parametricBands 的回落口径一致），面板照常
     * 打开、开关与存档值如实回显；预设名/频段/设备支持度是设备相关项，服务没起
     * 时不伪造，面板按 live=false 不贴「不支持」。改动仍走 runOnService 排队，
     * 服务起来后由服务 setter 写存档并应用。 */
    public MusicPlaybackService.FxState fxState() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s != null) {
            try {
                return s.fxState();
            } catch (Throwable t) {
                // 落到下面的存档回落，不让面板因服务异常自关
            }
        }
        MusicPlaybackService.FxState st = new MusicPlaybackService.FxState();
        st.live = false;
        st.enabled = MusicStore.isFxEnabled();
        st.preset = MusicStore.getFxPreset();
        st.bass = MusicStore.getFxBass();
        st.virt = MusicStore.getFxVirtualizer();
        st.levels = MusicStore.getFxBands();
        st.bassSupported = true;
        st.virtSupported = true;
        st.eqSupported = false;
        return st;
    }

    public void setFxEnabled(boolean on) {
        runOnService(() -> service().setFxEnabled(on));
    }

    public void setFxPreset(int preset) {
        runOnService(() -> service().setFxPreset(preset));
    }

    public void setFxBandLevel(int band, int millibel) {
        runOnService(() -> service().setFxBandLevel(band, millibel));
    }

    public void setFxBass(int strength) {
        runOnService(() -> service().setFxBass(strength));
    }

    public void setFxVirtualizer(int strength) {
        runOnService(() -> service().setFxVirtualizer(strength));
    }

    // ================= 参数均衡器（B6：与系统 audiofx 并存的另一套，面板走这里） =================

    public void setParametricEnabled(boolean on) {
        runOnService(() -> service().setParametricEnabled(on));
    }

    public void setParametricBands(java.util.List<ParametricEq.Band> bands) {
        runOnService(() -> service().setParametricBands(bands));
    }

    public boolean isParametricEnabled() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        // 回落口径与 parametricBands()/服务自身一致（体检 E-P7）：服务不在时读存档，
        // 不许恒 false 让面板回显假关（存档为开时）。
        return s == null ? MusicStore.isParametricEnabled() : s.isParametricEnabled();
    }

    public java.util.List<ParametricEq.Band> parametricBands() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? MusicStore.getParametricBands() : s.parametricBands();
    }

    public boolean openEqualizer(Activity activity) {
        // 同 getMode：空安全取法。服务不在时走下面已写好的可见提示分支，
        // 不许抛 IllegalStateException 把调用方面板崩掉。
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) {
            Notify.show("播放器还没起来");
            return false;
        }
        boolean ok = s.openEqualizer(activity);
        if (!ok) Notify.show("没找到系统均衡器");
        return ok;
    }

    // ================= 状态 =================

    private MusicPlaybackService service() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) throw new IllegalStateException("service not ready");
        return s;
    }

    /** 服务在跑且有队列 */
    public boolean isActive() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s != null && s.hasQueue();
    }

    public boolean isPlaying() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s != null && s.isPlaying();
    }

    public MusicTrack current() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? null : s.current();
    }

    public List<MusicTrack> queue() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? new ArrayList<>() : s.getQueue().getTracks();
    }

    /** 队列级来源（B7：入队时写入；空＝未知，老断点队列回落面板反查） */
    public String queueSource() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? null : s.getQueue().getSource();
    }

    /** 队列级 route（B7：空＝无跳转落点，来源行只展示不可点） */
    public String queueRoute() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? null : s.getQueue().getRoute();
    }

    /** route 参数（B7：list 路由带 mode／keyword／sitePlatform／playlistId／title） */
    public java.util.Map<String, String> queueRouteParams() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? null : s.getQueue().getRouteParams();
    }

    public int index() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? -1 : s.getQueue().getIndex();
    }

    /** 封面卡片后叠卡直调口径：直接取服务侧 MusicQueue.nextIndex()/prevIndex()（含随机洗牌顺序），不由活动侧近似推算。 */
    public int queueNextIndex() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) return -1;
        try {
            return s.getQueue().nextIndex();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public int queuePrevIndex() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) return -1;
        try {
            return s.getQueue().prevIndex();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public long position() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? 0 : s.position();
    }

    public long duration() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? 0 : s.duration();
    }

    /** 当前音频真实参数（音质格展示用）；无服务/未选轨返回 null */
    public MusicAudioInfo audioInfo() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? null : s.audioInfo();
    }

    // ================= 迷你条同步（mobile 侧） =================

    private void syncMiniBar() {
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar bar =
                    com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(app == null ? App.get() : app);
            MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
            if (s != null && s.hasQueue()) bar.registerMusic();
            else bar.unregisterMusic();
            bar.refreshMusic();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 回音乐首页时页面侧主动对一次迷你条（1.4.27 查修批）：迷你条会话可能已被策略层
     * 暂停自动关闭摘掉（会话摘除后服务队列还在、但没有任何事件会再触发同步），
     * 首页 onResume 调这里按服务现队列重注册——有可续播内容时条必须在，
     * 这是 1.4.26 的在位口径，不许只等播放事件把条带回来。
     */
    public void refreshMiniBar() {
        syncMiniBar();
    }

    // ================= 语音/后台直调用（静态） =================

    public static boolean isActiveStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        return s != null && s.hasQueue();
    }

    public static boolean toggleStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        if (instance != null) instance.pausedByVideo = false;
        // 同 toggle()：这一按是暂停时不许连带停掉在播的视频
        if (!s.isPlaying()) pauseVideoForMusic();
        s.toggle();
        return true;
    }

    public static boolean nextStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        if (instance != null) instance.pausedByVideo = false;
        pauseVideoForMusic();
        s.next();
        return true;
    }

    public static boolean prevStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        if (instance != null) instance.pausedByVideo = false;
        pauseVideoForMusic();
        s.prev();
        return true;
    }
}
