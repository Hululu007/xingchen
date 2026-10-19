package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSearchResult;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音乐源管理：插件列表（内置＋用户增删开关），存偏好；插件版本更新检查。
 * 插件 JS 经壳子 OkHttp 下载（吃代理/DoH/节点定向）。
 */
public class MusicSourceManager {

    private static final String K_SITES = "music_freejs_sites";
    private static final String K_PRIMARY = "music_freejs_primary";

    private static volatile MusicSourceManager instance;

    private final Gson gson = new Gson();
    private final Map<String, FreeJsBridge> bridges = new ConcurrentHashMap<>();

    private MusicSourceManager() {
    }

    public static MusicSourceManager get() {
        if (instance == null) {
            synchronized (MusicSourceManager.class) {
                if (instance == null) instance = new MusicSourceManager();
            }
        }
        return instance;
    }

    /** 内建真插件：官方 MusicFreePlugins（jsDelivr，已验证）＋用户线索的 kw.js */
    public static List<MusicSite> builtinSites() {
        List<MusicSite> list = new ArrayList<>();
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "bilibili", "",
                "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/bilibili/index.js"));
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "歌词网", "",
                "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciwang/index.js"));
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "歌词千寻", "",
                "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciqianxun/index.js"));
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "酷我音乐", "",
                "https://music.cnmb.us.ci/plugins/kw.js"));
        return list;
    }

    /* ---------------- 列表存取 ---------------- */

    public synchronized List<MusicSite> listSites() {
        String json = Prefers.getString(K_SITES, "");
        if (TextUtils.isEmpty(json)) {
            List<MusicSite> builtin = builtinSites();
            saveSites(builtin);
            return builtin;
        }
        try {
            Type type = new TypeToken<List<MusicSite>>() {
            }.getType();
            List<MusicSite> list = gson.fromJson(json, type);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void saveSites(List<MusicSite> sites) {
        try {
            Prefers.put(K_SITES, gson.toJson(sites));
        } catch (Exception e) {
            SpiderDebug.log("music", "save sites failed %s", e.getMessage());
        }
    }

    public List<MusicSite> enabledSites() {
        List<MusicSite> result = new ArrayList<>();
        for (MusicSite s : listSites()) {
            if (s.enabled) result.add(s);
        }
        return result;
    }

    /** 加插件：下载→读元数据→入库（阻塞，请在子线程调） */
    public synchronized MusicSite addSite(String srcUrl) throws Exception {
        if (TextUtils.isEmpty(srcUrl) || !srcUrl.startsWith("http")) {
            throw new IllegalArgumentException("插件地址不对");
        }
        for (MusicSite s : listSites()) {
            if (srcUrl.equals(s.srcUrl)) throw new IllegalStateException("这个插件已经加过了");
        }
        String js = downloadJs(srcUrl);
        MusicSite site = new MusicSite(FreeJsMusicSource.PLATFORM, "", "", srcUrl);
        FreeJsBridge bridge = new FreeJsBridge(site, js);
        try {
            bridge.init();
        } finally {
            bridge.destroy();
        }
        if (TextUtils.isEmpty(site.name)) site.name = srcUrl;
        List<MusicSite> list = listSites();
        list.add(site);
        saveSites(list);
        return site;
    }

    public synchronized void removeSite(String srcUrl) {
        List<MusicSite> list = listSites();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (srcUrl.equals(list.get(i).srcUrl)) list.remove(i);
        }
        saveSites(list);
        FreeJsBridge b = bridges.remove(srcUrl);
        if (b != null) b.destroy();
        if (srcUrl.equals(Prefers.getString(K_PRIMARY, ""))) Prefers.put(K_PRIMARY, "");
    }

    public synchronized void setEnabled(String srcUrl, boolean enabled) {
        List<MusicSite> list = listSites();
        for (MusicSite s : list) {
            if (srcUrl.equals(s.srcUrl)) s.enabled = enabled;
        }
        saveSites(list);
        if (!enabled) {
            FreeJsBridge b = bridges.remove(srcUrl);
            if (b != null) b.destroy();
        }
    }

    /* ---------------- 桥 ---------------- */

    private String downloadJs(String srcUrl) throws Exception {
        String js = OkHttp.string(srcUrl, 20000);
        if (TextUtils.isEmpty(js)) throw new IllegalStateException("插件下载失败：" + srcUrl);
        return js;
    }

    /** 取某插件的桥（懒加载，阻塞，请在子线程调） */
    public FreeJsBridge bridgeOf(MusicSite site) throws Exception {
        if (site == null || TextUtils.isEmpty(site.srcUrl)) return null;
        FreeJsBridge b = bridges.get(site.srcUrl);
        if (b != null) return b;
        synchronized (bridges) {
            b = bridges.get(site.srcUrl);
            if (b != null) return b;
            FreeJsBridge nb = new FreeJsBridge(site, downloadJs(site.srcUrl));
            nb.init();
            bridges.put(site.srcUrl, nb);
            return nb;
        }
    }

    /** 按 track.rawJson 里的 _plugin 找桥 */
    public FreeJsBridge bridgeForTrack(MusicTrack track) throws Exception {
        if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
        try {
            String plugin = com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
            for (MusicSite s : enabledSites()) {
                if (plugin.equals(s.srcUrl)) return bridgeOf(s);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /* ---------------- 主源（语音"切换音乐源"用） ---------------- */

    public String primaryKey() {
        return Prefers.getString(K_PRIMARY, "");
    }

    public FreeJsBridge primaryBridge() throws Exception {
        List<MusicSite> enabled = enabledSites();
        if (enabled.isEmpty()) return null;
        String key = primaryKey();
        for (MusicSite s : enabled) {
            if (key.equals(s.srcUrl)) return bridgeOf(s);
        }
        return bridgeOf(enabled.get(0));
    }

    /** 切到下一个启用的源，返回切到的源（阻塞，请在子线程调） */
    public MusicSite rotatePrimary() throws Exception {
        List<MusicSite> enabled = enabledSites();
        if (enabled.isEmpty()) throw new IllegalStateException("没有可用的音乐源");
        String key = primaryKey();
        int idx = 0;
        for (int i = 0; i < enabled.size(); i++) {
            if (key.equals(enabled.get(i).srcUrl)) {
                idx = (i + 1) % enabled.size();
                break;
            }
        }
        MusicSite next = enabled.get(idx);
        Prefers.put(K_PRIMARY, next.srcUrl);
        return next;
    }

    /* ---------------- 统一搜索（聚合全部启用源，阻塞） ---------------- */

    public MusicSearchResult searchAll(String keyword, int page, String type) {
        MusicSearchResult all = new MusicSearchResult();
        all.setPage(page);
        all.setType(type);
        List<MusicTrack> tracks = new ArrayList<>();
        boolean end = true;
        for (MusicSite site : enabledSites()) {
            try {
                FreeJsBridge b = bridgeOf(site);
                if (b == null) continue;
                MusicSearchResult r = b.search(keyword, page, type);
                if (r != null && !r.isEmpty()) tracks.addAll(r.getTracks());
                if (r != null && !r.isEnd()) end = false;
            } catch (Throwable t) {
                SpiderDebug.log("music", "searchAll error site=%s %s", site.name, t.getMessage());
            }
        }
        all.setTracks(tracks);
        all.setEnd(end);
        return all;
    }

    /* ---------------- 版本更新检查（阻塞，请在子线程调） ---------------- */

    /**
     * 检查插件更新：重下插件读 version，比本地新就返回新版本号，否则返回 null。
     * MusicFree 插件自带 version 字段。
     */
    public String checkUpdate(MusicSite site) {
        if (site == null || TextUtils.isEmpty(site.srcUrl)) return null;
        try {
            String js = downloadJs(site.srcUrl);
            MusicSite tmp = new MusicSite(FreeJsMusicSource.PLATFORM, "", site.version, site.srcUrl);
            FreeJsBridge bridge = new FreeJsBridge(tmp, js);
            try {
                bridge.init();
            } finally {
                bridge.destroy();
            }
            String remote = tmp.version;
            if (!TextUtils.isEmpty(remote) && !remote.equals(site.version)) {
                if (compareVersion(remote, site.version) > 0) return remote;
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "checkUpdate error site=%s %s", site.name, t.getMessage());
        }
        return null;
    }

    /** 有更新就更新本地版本号（不换 JS，JS 每次懒加载都重下） */
    public void applyUpdate(MusicSite site, String newVersion) {
        List<MusicSite> list = listSites();
        for (MusicSite s : list) {
            if (site.srcUrl.equals(s.srcUrl)) s.version = newVersion;
        }
        saveSites(list);
    }

    private int compareVersion(String a, String b) {
        try {
            String[] pa = a.split("\\.");
            String[] pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int x = i < pa.length ? Integer.parseInt(pa[i].replaceAll("\\D", "")) : 0;
                int y = i < pb.length ? Integer.parseInt(pb[i].replaceAll("\\D", "")) : 0;
                if (x != y) return x - y;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public void destroy() {
        for (FreeJsBridge b : bridges.values()) {
            try {
                b.destroy();
            } catch (Throwable ignored) {
            }
        }
        bridges.clear();
    }
}
