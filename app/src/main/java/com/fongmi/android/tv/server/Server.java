package com.fongmi.android.tv.server;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

public class Server {

    private volatile PlaybackService service;
    private volatile Nano nano;
    private volatile boolean manage;
    /** 停启代次：每次 start() 递增；stop() 入队时捕获当时的代次，任务真执行时
     * 代次已变说明期间又 start 过，这个排队中的 stop 必须放弃，不许停掉新服务。 */
    private volatile int generation;
    /** 起服务失败的已重试次数：端口被崩后残留进程占着时，限次重试等占位者消亡后自愈。 */
    private volatile int startRetries;
    private static final int START_RETRY_MAX = 12;
    private static final long START_RETRY_DELAY_MS = 5000;
    private PlaybackRouteRegistry.Registration routeRegistration;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public void setService(PlaybackService service) {
        this.service = service;
    }

    public boolean isRunning() {
        return nano != null;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + Proxy.getPort();
    }

    public synchronized void startManage() {
        manage = true;
        start();
    }

    public synchronized void stopManage() {
        manage = false;
        if (service == null) stop();
    }

    public synchronized void start() {
        // 无论本次是否真新建（已在跑时是空转），都算一次新的启动意图：
        // 递增代次，让此前排队中的 stop 任务执行时自行放弃。
        generation++;
        if (nano != null) {
            startRetries = 0;
            return;
        }
        for (int i = 9978; i < 9999; i++) {
            try {
                nano = new Nano(i);
                nano.start(500);
                Proxy.set(i);
                if (routeRegistration != null) routeRegistration.close();
                routeRegistration = PlaybackRouteRegistry.registerAppService(i, PlaybackRouteRegistry.AppOwner.MAIN_SERVER);
                break;
            } catch (Throwable e) {
                nano = null;
            }
        }
        if (nano != null) {
            startRetries = 0;
            return;
        }
        // 候选端口全被占（典型是崩后残留进程还占着本地服务端口）：此前这里静默
        // 放弃，Proxy 端口停在未起态，播放全线对着不存在的本地服务转圈假死且无
        // 任何日志。改成留痕＋限次重试：占位者被系统回收/退出后本进程能自愈，
        // 不必靠用户强退。重试有上限，长期被别家占满时不无限空转。
        SpiderDebug.log("server", "local server start failed: ports 9978-9998 busy, retry=%d/%d", startRetries, START_RETRY_MAX);
        if (startRetries < START_RETRY_MAX) {
            startRetries++;
            App.post(this::start, START_RETRY_DELAY_MS);
        }
    }

    public void stop() {
        final int gen = generation;
        Task.execute(() -> {
            synchronized (this) {
                // 代次已变＝排队期间又 start 过：放弃本次 stop，不许停新服务。
                if (gen != generation) return;
                if (manage || service != null) return;
                if (nano != null) nano.stop();
                nano = null;
                if (routeRegistration != null) routeRegistration.close();
                routeRegistration = null;
                // 端口复位到未起态：catvod Proxy 的初始值就是 -1（set(-1) 即复位，
                // 不是置 0）；引用方口径已核：NsdDeviceDiscovery 以 getPort() <= 0
                // 守门、Server.getAddress／Proxy.getUrl 只拼字符串，与冷启未 start
                // 时拿到 -1 的既有行为一致，不引入新形态。
                Proxy.set(-1);
            }
        });
    }
}
