package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    protected void beforeLoad() {
    }

    protected void onLoadSuccess() {
    }

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            beforeLoad();
            if (config == null) config = defaultConfig();
            Server.get().start();
            load(config);
            onLoadSuccess();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public void load(Callback callback) {
        beforeLoad();
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) {
            cancelLoad();
            future.cancel(true);
        }
        future = Task.submit(() -> loadConfig(id, config, callback));
        callback.start();
    }

    public void cancel() {
        taskId.incrementAndGet();
        cancelLoad();
        OkHttp.cancel(getTag());
        if (future != null && !future.isDone()) future.cancel(true);
    }

    // 取消在途加载的附带清理钩（上游同名口径）：默认无操作，需要收口的
    // 子类覆写——VodConfig 用它取消 Node 配置加载（NodeRuntime.cancelLoad）。
    protected void cancelLoad() {
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        boolean success = false;
        try {
            Server.get().start();
            OkHttp.cancel(getTag());
            load(config);
            if (taskId.get() != id) return;
            if (config.equals(this.config)) config.update();
            onLoadSuccess();
            App.post(() -> Notify.show(config.getNotice()));
            App.post(callback::success);
            success = true;
        } catch (Throwable e) {
            e.printStackTrace();
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            // 只在成功时发事件：失败路径已有 callback.error 弹错＋StateEvent.empty，
            // 再发事件会让首页（HomeActivity VOD 分支 → RefreshEvent.home）无条件重拉一次。
            // 失败时无人依赖该事件：消费方核查——HomeActivity（VOD 转首页刷新／LIVE、COMMON 只调
            // setNavigation）、leanback Home（VOD 转首页刷新）、VideoActivity 两版（只刷新解析器列表，
            // 失败时解析器本就为空／旧数据）、PlaybackService（VOD/LIVE 清浏览树，失败不清、留旧树，
            // 且 session 为空时直接返回）、SettingFragment/leanback Setting（只刷新壁纸文案）。
            if (success && taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
