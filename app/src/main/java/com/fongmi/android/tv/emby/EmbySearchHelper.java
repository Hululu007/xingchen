package com.fongmi.android.tv.emby;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Emby 聚合搜索·层1：Emby 内部多服聚合。
 * 遍历已登录服务器并发搜（每服 8 秒超时、失败隔离、某服挂了不拖死整体），
 * 结果按服标注 "Emby·服别名"，同名不同服保留两条。
 * 调用方必须在子线程调用（同步阻塞）。
 */
public class EmbySearchHelper {

    /** 每服超时秒数 */
    public static final int TIMEOUT_SECONDS = 8;
    /** 每服最多取条数 */
    public static final int LIMIT = 50;

    public static class ServerResult {
        public EmbyStore.EmbyServer server;
        public final List<EmbyItem> items = new ArrayList<>();
        public String error = "";
    }

    /**
     * 并发搜所有已登录服。返回每服结果（含失败项，error 非空表示该服失败）。
     * 同步调用，调用方负责子线程。
     */
    public static List<ServerResult> searchAll(String keyword) {
        List<ServerResult> results = new ArrayList<>();
        if (TextUtils.isEmpty(keyword)) return results;
        List<EmbyStore.EmbyServer> servers = new ArrayList<>();
        try {
            for (EmbyStore.EmbyServer s : EmbyStore.servers()) if (s != null && s.isLogin()) servers.add(s);
        } catch (Throwable ignored) {
        }
        if (servers.isEmpty()) return results;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(servers.size(), 4));
        try {
            List<Future<ServerResult>> futures = new ArrayList<>();
            for (EmbyStore.EmbyServer s : servers) {
                futures.add(pool.submit(new SearchOne(s, keyword)));
            }
            for (Future<ServerResult> f : futures) {
                try {
                    ServerResult r = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (r != null) results.add(r);
                } catch (Throwable e) {
                    // 失败隔离：超时/异常只记这一服，不拖死整体
                    f.cancel(true);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /** 合并为一维条目（标注来源服），供 AI「Emby 播放+片名」等取首条直用。 */
    public static List<EmbyItem> merge(List<ServerResult> results) {
        List<EmbyItem> all = new ArrayList<>();
        if (results == null) return all;
        for (ServerResult r : results) {
            if (r == null || r.server == null) continue;
            all.addAll(r.items);
        }
        return all;
    }

    private static class SearchOne implements Callable<ServerResult> {
        private final EmbyStore.EmbyServer server;
        private final String keyword;

        SearchOne(EmbyStore.EmbyServer server, String keyword) {
            this.server = server;
            this.keyword = keyword;
        }

        @Override
        public ServerResult call() {
            ServerResult r = new ServerResult();
            r.server = server;
            try {
                EmbyApi api = new EmbyApi(server.baseUrl, server.token, server.userId, server.id);
                EmbyApi.ItemPage page = api.search(keyword, 0, LIMIT);
                if (page != null) r.items.addAll(page.items);
            } catch (Throwable e) {
                r.error = e.getMessage() == null ? "搜索失败" : e.getMessage();
            }
            return r;
        }
    }
}
