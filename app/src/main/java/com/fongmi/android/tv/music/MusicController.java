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
import com.fongmi.android.tv.service.MusicPlaybackService;
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
                listeners.forEach(l -> l.onPlayingChanged(playing));
                syncMiniBar();
            });
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
            App.post(() -> listeners.forEach(l -> l.onModeChanged(mode)));
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
        });
    }

    // ================= 播放控制 =================

    public void play(List<MusicTrack> queue, int index) {
        play(queue, index, null, null, null);
    }

    /** 播队列并写入队列级来源／route（B7：入队时写，面板不再反查） */
    public void play(List<MusicTrack> queue, int index,
                     String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        runOnService(() -> service().playQueue(new ArrayList<>(queue), index, source, route, routeParams));
    }

    /** 播队列并从断点位置起 */
    public void playFrom(List<MusicTrack> queue, int index, long position) {
        playFrom(queue, index, position, null, null, null);
    }

    /** 播队列并从断点位置起，同时写入队列级来源／route（B7） */
    public void playFrom(List<MusicTrack> queue, int index, long position,
                         String source, String route, java.util.Map<String, String> routeParams) {
        if (queue == null || queue.isEmpty()) return;
        runOnService(() -> service().playQueueFrom(new ArrayList<>(queue), index, position, source, route, routeParams));
    }

    public void playAt(int index) {
        runOnService(() -> service().playAt(index));
    }

    public void toggle() {
        runOnService(() -> service().toggle());
    }

    public void play() {
        runOnService(() -> service().play());
    }

    public void pause() {
        runOnService(() -> service().pause());
    }

    public void next() {
        runOnService(() -> service().next());
    }

    public void prev() {
        runOnService(() -> service().prev());
    }

    public void seekTo(long ms) {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s != null) s.seekTo(ms);
    }

    public void removeAt(int i) {
        runOnService(() -> service().removeAt(i));
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

    public void setMode(int mode) {
        runOnService(() -> service().setMode(mode));
    }

    public void cycleMode() {
        runOnService(() -> service().cycleMode());
    }

    public int getMode() {
        MusicPlaybackService s = service();
        return s == null ? MusicStore.getMode() : s.getQueue().getMode();
    }

    public void setQuality(int quality) {
        MusicStore.setQuality(quality);
    }

    public int getQuality() {
        return MusicStore.getQuality();
    }

    /** 音质切换后重载当前歌曲 */
    public void reloadQuality() {
        runOnService(() -> service().reloadCurrent());
    }

    public void setSleep(int sleep) {
        runOnService(() -> service().setSleep(sleep));
    }

    public int getSleep() {
        MusicPlaybackService s = service();
        return s == null ? MusicPlaybackService.SLEEP_OFF : s.getSleep();
    }

    public long sleepRemainingMs() {
        MusicPlaybackService s = service();
        return s == null ? -1 : s.getSleepRemainingMs();
    }

    // ================= 音效（App 内面板 → 服务 audiofx） =================

    /** 音效全量状态（面板打开用）；服务没起来返回 null */
    public MusicPlaybackService.FxState fxState() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        if (s == null) return null;
        try {
            return s.fxState();
        } catch (Throwable t) {
            return null;
        }
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
        return s != null && s.isParametricEnabled();
    }

    public java.util.List<ParametricEq.Band> parametricBands() {
        MusicPlaybackService s = svc != null ? svc : MusicPlaybackService.get();
        return s == null ? MusicStore.getParametricBands() : s.parametricBands();
    }

    public boolean openEqualizer(Activity activity) {
        MusicPlaybackService s = service();
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

    // ================= 语音/后台直调用（静态） =================

    public static boolean isActiveStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        return s != null && s.hasQueue();
    }

    public static boolean toggleStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        s.toggle();
        return true;
    }

    public static boolean nextStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        s.next();
        return true;
    }

    public static boolean prevStatic() {
        MusicPlaybackService s = MusicPlaybackService.get();
        if (s == null || !s.hasQueue()) return false;
        s.prev();
        return true;
    }
}
