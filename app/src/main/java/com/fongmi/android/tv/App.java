package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.playback.PlaybackRemoteSyncer;
import com.fongmi.android.tv.player.PlaybackMemoryMonitor;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.mpv.PlaybackRecoveryMonitor;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.setting.ProxySetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.AdBlock;
import com.fongmi.android.tv.utils.DanmakuSearchListFocusFixer;
import com.fongmi.android.tv.utils.NsdDeviceDiscovery;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PreviousProcessExitLogger;
import com.fongmi.android.tv.receiver.RuntimeReceiver;
import com.fongmi.android.tv.utils.RuntimeInstaller;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.hook.Hook;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private volatile Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    // Application 构造前被触发的静态入口兜底（其他进程的 Provider 抢先、工具直调等）：
    // 此前全是 get().xxx 直调，get() 为 null 即裸 NPE、无从诊断（S5-12）。
    private static final Gson FALLBACK_GSON = new Gson();
    private static final Handler FALLBACK_HANDLER = HandlerCompat.createAsync(Looper.getMainLooper());

    private static Handler handler() {
        App app = get();
        return app == null ? FALLBACK_HANDLER : app.handler;
    }

    public static Gson gson() {
        App app = get();
        return app == null ? FALLBACK_GSON : app.gson;
    }

    public static long time() {
        App app = get();
        return app == null ? System.currentTimeMillis() : app.time;
    }

    public static Activity activity() {
        App app = get();
        return app == null ? null : app.activity;
    }

    public static void post(Runnable runnable) {
        handler().post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        handler().removeCallbacks(runnable);
        if (delayMillis >= 0) handler().postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        handler().removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) handler().removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (PlaybackRecoveryMonitor.isRecoveryProcess(base)) return;
        Init.set(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 进程守卫一刀切落到全套（S5-07）：此前只有后台服务的投递点按主进程守，
        // 守卫之前的监听/观察/设置一套（ThemeManager、AiActivityTracker、内存/系统
        // 监控、插件目录 watch 等）在崩溃页进程（:error_activity）里照样全跑。
        // 口径与 startBackgroundServices 的注释一致：非主进程什么都不起，
        // 只留 Application 本体供系统在该进程拉起页面（崩溃页/恢复页）。
        if (!PlaybackRecoveryMonitor.isMainProcess(this)) return;
        try { com.xingchen.tv.theme.ThemeManager.init(this); } catch (Exception e) {}
        try { com.fongmi.android.tv.ai.AiActivityTracker.init(this); } catch (Exception e) {}
        PlaybackMemoryMonitor.process().initialize(this);
        PlaybackSystemConditionMonitor.process().initialize(this);
        Setting.applyLanguage();
        DebugLogStore.restoreEnabled();
        if (DebugLogStore.isEnabled()) {
            PlaybackRecoveryMonitor.logPreviousResult(this);
            Setting.logDebugEnvironment("restore");
            PreviousProcessExitLogger.log(this);
        }
        Notify.createChannel();
        ProxySetting.apply();
        AdBlock.refresh(this);
        DanmakuSearchListFocusFixer.start();
        registerActivityLifecycleCallbacks(this);
        // 上游 App 同位置：启动即登记运行时包安装／卸载广播，RuntimeModule.refresh()
        // 靠它感知外置包变化；ModuleViewModel 各自的接收器只跟自己那块模块。
        RuntimeReceiver.register(this);
        // 海阔图片毒缓存一次性清理：跨版本首次启动清 hiker files 根的 cache/，
        // 内部自吞异常＋后台执行，不影响启动（见 HkCachePurge 类头定案说明）。
        try { com.fongmi.android.tv.api.hk.HkCachePurge.onStartup(); } catch (Throwable ignored) {}
        try {
            com.fongmi.android.tv.api.loader.BaseLoader.get()
                    .watchPyPlugins(new java.io.File(getFilesDir(), "plugins/py"));
            com.fongmi.android.tv.api.loader.BaseLoader.get()
                    .watchJsPlugins(new java.io.File(getFilesDir(), "plugins/js"));
        } catch (Throwable ignored) {}
        // 后台服务全家只许主进程起：崩溃页进程（:error_activity）此前无守卫，
        // 会在崩溃页进程里再起一整套（本地服务/RemoteAgent/节点内核/Python 预热），
        // 主进程崩后残留占位，重新拉起时播放全线对着残留状态假死、须强退才恢复。
        // 恢复页进程已有 isRecoveryProcess 守卫，这里按主进程一刀切补齐。
        if (PlaybackRecoveryMonitor.isMainProcess(this)) post(this::startBackgroundServices, 1200);
    }

    @Override
    public void onTrimMemory(int level) {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onTrimMemory(level);
        super.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        if (!PlaybackRecoveryMonitor.isRecoveryProcess(this)) PlaybackMemoryMonitor.process().onLowMemory();
        super.onLowMemory();
    }

    private void startBackgroundServices() {
        // 双保险：投递点已按主进程守卫，这里再挡一次，非主进程绝不起后台全家。
        if (!PlaybackRecoveryMonitor.isMainProcess(this)) return;
        SpiderDebug.log("startup", "background services start cost=%sms", System.currentTimeMillis() - time);
        Server.get().start();
        PlaybackRemoteSyncer.start();
        RemoteAgent.get().start();
        NsdDeviceDiscovery.register();
        Task.execute(() -> {
            try {
                com.fongmi.chaquo.Loader.warmUp();
            } catch (Throwable ignored) {}
        });
        // 节点开关是长期设置：开着退出再进，后台自动把内核拉起来；
        // 拉起失败静默降级为直连并把开关置回关，不卡启动。
        Task.execute(() -> com.fongmi.android.tv.clash.MihomoManager.get().bootRestore(getApplicationContext()));
        SpiderDebug.log("startup", "background services ready cost=%sms", System.currentTimeMillis() - time);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        // 上游 App.onActivityResumed 同段：活动恢复时续跑外置运行时安装确认／卸载，
        // 并把模块更新提示（Updater.checkModules 攒下的）投出来
        RuntimeInstaller.resumeUpdate(activity);
        Updater.create().notifyModules();
        // 听书迷你条：跟人走（贴各页底部、全局底栏上方）
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this)
                    .onActivityResumed(activity);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        try {
            com.fongmi.android.tv.ui.minibar.ListenMiniBar.get(this)
                    .onActivityPaused(activity);
        } catch (Throwable ignored) {
        }
    }

    @Override
        public void onActivityCreated(@NonNull Activity activity, android.os.Bundle savedInstanceState) {
        try {
            android.view.View v = activity.findViewById(android.R.id.content);
            if (v != null) v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        } catch (Exception e) {}
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}
