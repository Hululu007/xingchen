package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSearchResult;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 音乐源管理：插件列表（内置＋用户增删开关），存偏好；插件版本更新检查。
 * 管两种脚本格式：FreeJS（MusicFree 插件）和 落雪（LX 自定义源），format 字段区分，
 * 桥按格式分流（bridges/lxBridges），两引擎互不干扰。老数据没有 format 按 FreeJS 处理。
 * 本地文件安装的插件存 App 私有目录 music_plugins，删插件连文件一起删。
 */
public class MusicSourceManager {

    /** 脚本格式判据见 detectFormat */
    public static final String FORMAT_FREEJS = "freejs";
    public static final String FORMAT_LX = "lx";

    private static final String K_SITES = "music_freejs_sites";
    private static final String K_PRIMARY = "music_freejs_primary";
    private static final String PLUGIN_DIR = "music_plugins";

    private static volatile MusicSourceManager instance;

    private final Gson gson = new Gson();
    private final Map<String, FreeJsBridge> bridges = new ConcurrentHashMap<>();
    private final Map<String, LxBridge> lxBridges = new ConcurrentHashMap<>();

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

    /** null/空 format 的老数据按 FreeJS 处理 */
    public static boolean isLx(MusicSite s) {
        return s != null && FORMAT_LX.equals(s.format);
    }

    public static boolean isLocal(MusicSite s) {
        return s != null && !TextUtils.isEmpty(s.localPath);
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
        for (MusicSite s : list) s.format = FORMAT_FREEJS;
        return list;
    }

    /* ---------------- 格式判据与脚本读取 ---------------- */

    /** 落雪特征：解构/引用 globalThis.lx、lx.on 注册、EVENT_NAMES 常量（三者居一） */
    private static final Pattern LX_HINT = Pattern.compile("globalThis\\.lx|\\blx\\s*\\.\\s*on\\s*\\(|EVENT_NAMES");
    /** FreeJS 特征：module.exports 导出且带 platform 声明（或 supportedSearchType） */
    private static final Pattern FREEJS_PLATFORM = Pattern.compile("platform\\s*[:=]");

    /**
     * 格式判据：先落雪后 FreeJS（落雪脚本是打包产物，体内可能出现 module.exports 字样）。
     * 落雪＝含 lx 全局注册特征；FreeJS＝含 module.exports 且含 platform 声明；
     * 都不是返回 null（安装时报「不支持的脚本」）。已用真脚本验过（落雪官方源/全豆要、
     * MusicFree bilibili 插件）。
     */
    public static String detectFormat(String js) {
        if (TextUtils.isEmpty(js)) return null;
        if (LX_HINT.matcher(js).find()) return FORMAT_LX;
        if (js.contains("module.exports")
                && (FREEJS_PLATFORM.matcher(js).find() || js.contains("supportedSearchType"))) {
            return FORMAT_FREEJS;
        }
        return null;
    }

    /** 取脚本原文：本地装的读文件，在线装的现下（桥懒加载每次重读，更新天然生效） */
    private String readJs(MusicSite site) throws Exception {
        if (isLocal(site)) {
            File f = new File(site.localPath);
            if (!f.exists()) throw new IllegalStateException("插件文件不见了：" + site.name);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                int n;
                while ((n = in.read(buf, off, buf.length - off)) > 0) off += n;
                return new String(buf, 0, off, "UTF-8");
            }
        }
        return downloadJs(site.srcUrl);
    }

    private File pluginDir() {
        File dir = new File(App.get().getFilesDir(), PLUGIN_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
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

    /** 按格式取启用的插件（FreeJS/落雪各自的源实现用） */
    public List<MusicSite> enabledSitesOfFormat(String format) {
        List<MusicSite> result = new ArrayList<>();
        for (MusicSite s : enabledSites()) {
            if (FORMAT_LX.equals(format) ? isLx(s) : !isLx(s)) result.add(s);
        }
        return result;
    }

    /** 加插件（在线地址）：下载→判格式→读元数据→入库（阻塞，请在子线程调） */
    public synchronized MusicSite addSite(String srcUrl) throws Exception {
        if (TextUtils.isEmpty(srcUrl) || !srcUrl.startsWith("http")) {
            throw new IllegalArgumentException("插件地址不对");
        }
        for (MusicSite s : listSites()) {
            if (srcUrl.equals(s.srcUrl)) throw new IllegalStateException("这个插件已经加过了");
        }
        String js = downloadJs(srcUrl);
        MusicSite site = probe(js, srcUrl, null);
        List<MusicSite> list = listSites();
        list.add(site);
        saveSites(list);
        return site;
    }

    /** 加插件（本地文件）：读文件→判格式→读元数据→存插件目录→入库（阻塞，请在子线程调） */
    public synchronized MusicSite addLocalSite(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("文件是空的");
        String js = new String(bytes, "UTF-8");
        String format = detectFormat(js);
        if (format == null) throw new IllegalStateException("不支持的脚本：不是 FreeJS 插件也不是落雪脚本");
        // 先校验能初始化再落盘，别把坏脚本存进插件目录
        MusicSite tmp = probe(js, "", null);
        File file = new File(pluginDir(), "plugin_" + System.currentTimeMillis() + ".js");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        tmp.srcUrl = "local://" + file.getName();
        tmp.localPath = file.getAbsolutePath();
        List<MusicSite> list = listSites();
        list.add(tmp);
        saveSites(list);
        return tmp;
    }

    /** 按格式试初始化读元数据（桥用完即毁），返回待入库的 site */
    private MusicSite probe(String js, String srcUrl, String localPath) throws Exception {
        String format = detectFormat(js);
        if (format == null) throw new IllegalStateException("不支持的脚本：不是 FreeJS 插件也不是落雪脚本");
        if (FORMAT_LX.equals(format)) {
            MusicSite site = new MusicSite(LxMusicSource.PLATFORM, "", "", srcUrl);
            site.format = FORMAT_LX;
            site.localPath = localPath;
            LxBridge bridge = new LxBridge(site, js);
            try {
                bridge.init();
            } finally {
                bridge.destroy();
            }
            if (TextUtils.isEmpty(site.name)) site.name = "落雪插件";
            return site;
        }
        MusicSite site = new MusicSite(FreeJsMusicSource.PLATFORM, "", "", srcUrl);
        site.format = FORMAT_FREEJS;
        site.localPath = localPath;
        FreeJsBridge bridge = new FreeJsBridge(site, js);
        try {
            bridge.init();
        } finally {
            bridge.destroy();
        }
        if (TextUtils.isEmpty(site.name)) site.name = TextUtils.isEmpty(srcUrl) ? "音乐插件" : srcUrl;
        return site;
    }

    public synchronized void removeSite(String srcUrl) {
        List<MusicSite> list = listSites();
        MusicSite removed = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (srcUrl.equals(list.get(i).srcUrl)) {
                removed = list.get(i);
                list.remove(i);
            }
        }
        saveSites(list);
        FreeJsBridge b = bridges.remove(srcUrl);
        if (b != null) b.destroy();
        LxBridge lb = lxBridges.remove(srcUrl);
        if (lb != null) lb.destroy();
        if (removed != null && isLocal(removed)) {
            try {
                new File(removed.localPath).delete();
            } catch (Throwable ignored) {
            }
        }
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
            LxBridge lb = lxBridges.remove(srcUrl);
            if (lb != null) lb.destroy();
        }
    }

    /* ---------------- 桥 ---------------- */

    private String downloadJs(String srcUrl) throws Exception {
        String js = OkHttp.string(srcUrl, 20000);
        if (TextUtils.isEmpty(js)) throw new IllegalStateException("插件下载失败：" + srcUrl);
        return js;
    }

    /** 取某 FreeJS 插件的桥（懒加载，阻塞，请在子线程调）；落雪插件返回 null，别串桥 */
    public FreeJsBridge bridgeOf(MusicSite site) throws Exception {
        if (site == null || TextUtils.isEmpty(site.srcUrl) || isLx(site)) return null;
        FreeJsBridge b = bridges.get(site.srcUrl);
        if (b != null) return b;
        synchronized (bridges) {
            b = bridges.get(site.srcUrl);
            if (b != null) return b;
            FreeJsBridge nb = new FreeJsBridge(site, readJs(site));
            nb.init();
            bridges.put(site.srcUrl, nb);
            return nb;
        }
    }

    /** 取某落雪插件的桥（懒加载，阻塞，请在子线程调） */
    public LxBridge lxBridgeOf(MusicSite site) throws Exception {
        if (site == null || TextUtils.isEmpty(site.srcUrl) || !isLx(site)) return null;
        LxBridge b = lxBridges.get(site.srcUrl);
        if (b != null) return b;
        synchronized (lxBridges) {
            b = lxBridges.get(site.srcUrl);
            if (b != null) return b;
            LxBridge nb = new LxBridge(site, readJs(site));
            nb.init();
            lxBridges.put(site.srcUrl, nb);
            return nb;
        }
    }

    /** 按 track.rawJson 里的 _plugin 找桥（FreeJS 曲目专用） */
    public FreeJsBridge bridgeForTrack(MusicTrack track) throws Exception {
        if (track == null || TextUtils.isEmpty(track.rawJson)) return null;
        try {
            String plugin = com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
            for (MusicSite s : enabledSitesOfFormat(FORMAT_FREEJS)) {
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
        // 主源只在 FreeJS 插件里轮换（语音「切换音乐源」的老语义），落雪不掺
        List<MusicSite> enabled = enabledSitesOfFormat(FORMAT_FREEJS);
        if (enabled.isEmpty()) return null;
        String key = primaryKey();
        for (MusicSite s : enabled) {
            if (key.equals(s.srcUrl)) return bridgeOf(s);
        }
        return bridgeOf(enabled.get(0));
    }

    /** 切到下一个启用的 FreeJS 源，返回切到的源（阻塞，请在子线程调） */
    public MusicSite rotatePrimary() throws Exception {
        List<MusicSite> enabled = enabledSitesOfFormat(FORMAT_FREEJS);
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

    /* ---------------- 统一搜索（聚合全部启用 FreeJS 源，阻塞） ---------------- */

    public MusicSearchResult searchAll(String keyword, int page, String type) {
        MusicSearchResult all = new MusicSearchResult();
        all.setPage(page);
        all.setType(type);
        List<MusicTrack> tracks = new ArrayList<>();
        boolean end = true;
        for (MusicSite site : enabledSitesOfFormat(FORMAT_FREEJS)) {
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
     * 检查插件更新（仅在线装的）：重下脚本读版本，比本地新就返回新版本号，否则 null。
     * 本地装的没有源地址，不可更新，直接返回 null。
     */
    public String checkUpdate(MusicSite site) {
        if (site == null || TextUtils.isEmpty(site.srcUrl) || isLocal(site)) return null;
        try {
            String js = downloadJs(site.srcUrl);
            MusicSite tmp;
            if (isLx(site)) {
                tmp = new MusicSite(LxMusicSource.PLATFORM, "", site.version, site.srcUrl);
                tmp.format = FORMAT_LX;
                LxBridge bridge = new LxBridge(tmp, js);
                try {
                    bridge.init();
                } finally {
                    bridge.destroy();
                }
            } else {
                tmp = new MusicSite(FreeJsMusicSource.PLATFORM, "", site.version, site.srcUrl);
                FreeJsBridge bridge = new FreeJsBridge(tmp, js);
                try {
                    bridge.init();
                } finally {
                    bridge.destroy();
                }
            }
            String remote = tmp.version;
            if (!TextUtils.isEmpty(remote) && !remote.equals(site.version)) {
                if (compareVersion(remote, site.version) > 0) {
                    // 把新名字先挂到入参上，applyUpdate 落盘时一并带上（此处不落盘）
                    if (!TextUtils.isEmpty(tmp.name)) site.name = tmp.name;
                    return remote;
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "checkUpdate error site=%s %s", site.name, t.getMessage());
        }
        return null;
    }

    /** 有更新就更新本地元数据（版本＋名字），并销毁旧桥让新脚本生效 */
    public void applyUpdate(MusicSite site, String newVersion) {
        List<MusicSite> list = listSites();
        for (MusicSite s : list) {
            if (site.srcUrl.equals(s.srcUrl)) {
                s.version = newVersion;
                if (!TextUtils.isEmpty(site.name)) s.name = site.name;
            }
        }
        saveSites(list);
        FreeJsBridge b = bridges.remove(site.srcUrl);
        if (b != null) b.destroy();
        LxBridge lb = lxBridges.remove(site.srcUrl);
        if (lb != null) lb.destroy();
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
        for (LxBridge b : lxBridges.values()) {
            try {
                b.destroy();
            } catch (Throwable ignored) {
            }
        }
        lxBridges.clear();
    }
}
