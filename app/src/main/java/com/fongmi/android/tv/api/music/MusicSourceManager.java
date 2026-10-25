package com.fongmi.android.tv.api.music;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicSearchResult;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicPluginTest;
import com.fongmi.android.tv.bean.music.MusicQuality;
import com.fongmi.android.tv.bean.music.MusicRuntimeStatus;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 音乐源管理：插件列表（内置＋用户增删开关），存偏好；插件版本更新检查。
 * 管两种脚本格式：FreeJS（MusicFree 插件）和 落雪（LX 自定义源），format 字段区分，
 * 桥按格式分流（bridges/lxBridges），两引擎互不干扰。老数据没有 format 按 FreeJS 处理。
 * 本地文件安装的插件存 App 私有目录 music_plugins，删插件连文件一起删。
 */
public class MusicSourceManager {

    /**
     * 源稳定色（批 5 F2／D-09）：同一个源在任何页面都必须是同一个颜色，颜色只许从这里取，
     * 页面不许自备色盘自选色（此前插件行色盘写在 MusicPluginsActivity 里，别的页面再写一份就会串色）。
     * 键＝源地址（改名不变、换地址才换色），无地址退源名；色盘沿用此前插件行已交付的 8 色，
     * 哈希口径与旧页面实现一致（仅补 Integer.MIN_VALUE 时 Math.abs 仍为负的越界守卫），存量源颜色不变。
     */
    private static final int[] SOURCE_PALETTE = {
            0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF66BB6A,
            0xFF26A69A, 0xFF42A5F5, 0xFF7E57C2, 0xFFEC407A};

    public static int stableColorForKey(String key) {
        String k = key == null ? "" : key;
        int h = k.hashCode();
        int idx = h == Integer.MIN_VALUE ? 0 : Math.abs(h) % SOURCE_PALETTE.length;
        return SOURCE_PALETTE[idx];
    }

    /** 源行染色统一入口：有地址按地址、无地址按名，同源恒同色。 */
    public static int siteColor(MusicSite s) {
        if (s == null) return SOURCE_PALETTE[0];
        String key = s.srcUrl != null && !s.srcUrl.isEmpty() ? s.srcUrl : (s.name == null ? "" : s.name);
        return stableColorForKey(key);
    }

    /** 脚本格式判据见 detectFormat */
    public static final String FORMAT_FREEJS = "freejs";
    public static final String FORMAT_LX = "lx";

    private static final String K_SITES = "music_freejs_sites";
    private static final String K_PRIMARY = "music_freejs_primary";
    /** 用户点「忽略」的插件更新：srcUrl → 被忽略的版本号（有更高版本时重新提示） */
    private static final String K_IGNORED_UPDATES = "music_ignored_plugin_updates";
    /** 已播种过的预置地址集合（补播种判据：记过的预置被用户删了也不复活，见 BuiltinReseed） */
    private static final String K_SEEDED_BUILTINS = "music_builtin_seeded_urls";
    private static final String PLUGIN_DIR = "music_plugins";

    private static volatile MusicSourceManager instance;

    private final Gson gson = new Gson();
    private final Map<String, FreeJsBridge> bridges = new ConcurrentHashMap<>();
    private final Map<String, LxBridge> lxBridges = new ConcurrentHashMap<>();

    // ===== C4/C5/C10 运行时态（内存态，不落盘） =====
    /** 检测探针关键词（星辰侧自定通用词，不照抄瓜子源专属探针曲目） */
    public static final String TEST_PROBE_KEYWORD = "爱情";
    /** 隔离阈值：连续 3 次请求超时即隔离（瓜子 QUARANTINE_TIMEOUT_THRESHOLD） */
    public static final int QUARANTINE_THRESHOLD = 3;
    /** 隔离恢复：60s 后下次取链时重建桥（瓜子 QUARANTINE_RECOVERY_MS） */
    public static final long QUARANTINE_RECOVERY_MS = 60000;
    private final Map<String, Long> quarantineUntil = new ConcurrentHashMap<>();
    private final Map<String, Integer> timeoutCounts = new ConcurrentHashMap<>();
    private final java.util.Set<String> failedNames = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> testing = ConcurrentHashMap.newKeySet();
    private volatile int initializingCount;
    private volatile boolean reloading;
    private volatile long testGeneration;

    /** 插件检测回调（manager 回调在检测线程上，调用方自己切回主线程） */
    public interface PluginTestCallback {
        void onTesting();

        void onResult(MusicPluginTest result);
    }

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

    /** 内建真插件：官方 MusicFreePlugins 三份＋酷我一份（元力KW，qwerwhr 聚合仓，jsDelivr；原 cnmb 地址域名已注销） */
    /** 已下架的预置 bilibili 地址（存量清理按它精确匹配，只删这一条播种记录） */
    private static final String RETIRED_BUILTIN_BILIBILI =
            "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/bilibili/index.js";

    /** 预置酷我地址（元力KW）：搜索排去重按它认预置酷我身份（名字会被插件元数据回填改写、不可靠） */
    private static final String BUILTIN_KUWO_YUANLI =
            "https://cdn.jsdelivr.net/gh/qwerwhr/musicfree-plugins@master/%E5%85%83%E5%8A%9BKW.js";

    /** 是否应用自带的预置酷我（按播种地址认，不看名字） */
    public static boolean isBuiltinKuwo(String srcUrl) {
        return BUILTIN_KUWO_YUANLI.equals(srcUrl);
    }

    /** 自带歌词预置地址（种子身份即歌词型：歌词网／歌词千寻搜不了歌，计数归类不依赖检测结果） */
    private static final String BUILTIN_LYRIC_GECIWANG =
            "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciwang/index.js";
    private static final String BUILTIN_LYRIC_GECIQIANXUN =
            "https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciqianxun/index.js";

    /** 是否应用自带的歌词型预置（按播种地址认，不看名字，用户改名／改地址后按能力判据走） */
    public static boolean isBuiltinLyricPreset(String srcUrl) {
        return BUILTIN_LYRIC_GECIWANG.equals(srcUrl) || BUILTIN_LYRIC_GECIQIANXUN.equals(srcUrl);
    }

    public static List<MusicSite> builtinSites() {
        List<MusicSite> list = new ArrayList<>();
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "歌词网", "",
                BUILTIN_LYRIC_GECIWANG));
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "歌词千寻", "",
                BUILTIN_LYRIC_GECIQIANXUN));
        list.add(new MusicSite(FreeJsMusicSource.PLATFORM, "酷我音乐", "",
                BUILTIN_KUWO_YUANLI));
        /* 自写油管音乐源（自有站托管，2026-10-24）：必须走壳子代理、曲库随节点国家，
           坏了只改托管脚本不动壳子；朋友版播种整套摘除的口径同上三件。
           后加预置：打 lateBuiltin 标记，老用户存量靠 listSites 补播种（见 BuiltinReseed） */
        MusicSite ytMusic = new MusicSite(FreeJsMusicSource.PLATFORM, "油管音乐（需代理）", "",
                "https://xingchen-dl.pages.dev/music-sources/ytmusic.js");
        ytMusic.lateBuiltin = true;
        list.add(ytMusic);
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

    /** 在线脚本缓存的探新间隔：缓存比这旧就进桥前探一次远端版本（探新失败照常用缓存） */
    private static final long JS_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;
    /** 脚本内版本号（FreeJS 导出 version 字段形态：version: "1.1.0"／"version": "…"） */
    private static final Pattern SCRIPT_VERSION = Pattern.compile("version\\s*[:=]\\s*[\"']([0-9][^\"']*)[\"']");

    /**
     * 取脚本原文：本地装的读文件；在线装的优先读落盘缓存，缺了才下载并写缓存
     * （桥重建不再每次走网络——第十件实测下载是冷启大头之一）。
     * 缓存除 applyUpdate 手动失效外，还有版本感知探新（见 refreshCachedJs）：
     * 托管脚本在服务端升版（如油管源加登录声明）时，老缓存若永不失效，
     * 装了新版 App 也永远读旧脚本，新能力（如 loginProvider 声明）到不了桥。
     * checkUpdate 直调 downloadJs 不经这里，查更新语义不受影响。
     */
    private String readJs(MusicSite site) throws Exception {
        if (isLocal(site)) {
            File f = new File(site.localPath);
            if (!f.exists()) throw new IllegalStateException("插件文件不见了：" + site.name);
            return readTextFile(f);
        }
        File cache = jsCacheFile(site.srcUrl);
        if (cache != null && cache.isFile() && cache.length() > 0) {
            String cached = null;
            try {
                cached = readTextFile(cache);
            } catch (Throwable ignored) {
            }
            if (cached != null) {
                if (!jsCacheStale(cache)) return cached;
                return refreshCachedJs(site, cache, cached);
            }
        }
        String js = downloadJs(site.srcUrl);
        if (cache != null) {
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(js.getBytes("UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        return js;
    }

    /**
     * 缓存是否过期：落盘超过探新间隔，或 App 在缓存落盘之后更新过
     * （装新版后第一次建桥必探新——托管脚本与 App 配套升级时不许还读旧脚本）。
     */
    private boolean jsCacheStale(File cache) {
        long written = cache.lastModified();
        if (System.currentTimeMillis() - written > JS_CACHE_TTL_MS) return true;
        return written < appLastUpdateTime();
    }

    private static long appLastUpdateTime() {
        try {
            return App.get().getPackageManager()
                    .getPackageInfo(App.get().getPackageName(), 0).lastUpdateTime;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 版本感知探新：重下远端脚本，脚本内版本号高于缓存才换（换前先过格式判据，
     * 免得错误页/坏脚本把能用的缓存顶掉）；版本号读不到时同样以远端为准
     * （落雪脚本多无 version 字段，作者升版只能靠探）。探新/下载失败、
     * 远端不比缓存新，一律照常用缓存，只把缓存时间往后推一格，
     * 免得每次建桥都重下。返回本次建桥要用的脚本原文。
     */
    private String refreshCachedJs(MusicSite site, File cache, String cached) {
        String fresh;
        try {
            fresh = downloadJs(site.srcUrl);
        } catch (Throwable t) {
            return cached;
        }
        if (TextUtils.isEmpty(fresh) || detectFormat(fresh) == null) {
            touchJsCache(cache);
            return cached;
        }
        String oldV = scriptVersion(cached);
        String newV = scriptVersion(fresh);
        if (oldV != null && newV != null && compareVersion(newV, oldV) <= 0) {
            touchJsCache(cache);
            return cached;
        }
        try (FileOutputStream out = new FileOutputStream(cache)) {
            out.write(fresh.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        }
        SpiderDebug.log("music", "插件脚本已按版本刷新 site=%s %s -> %s", site.name, oldV, newV);
        return fresh;
    }

    private static void touchJsCache(File cache) {
        try {
            cache.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    /** 脚本内声明的版本号（取首个 version 字段）；读不到返回 null */
    private static String scriptVersion(String js) {
        if (TextUtils.isEmpty(js)) return null;
        java.util.regex.Matcher m = SCRIPT_VERSION.matcher(js);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String readTextFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            int n;
            while ((n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        }
    }

    /** 在线脚本落盘缓存文件（键＝srcUrl 的 MD5）；取不到目录返回 null＝不缓存 */
    private File jsCacheFile(String srcUrl) {
        if (TextUtils.isEmpty(srcUrl)) return null;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(srcUrl.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("jscache_");
            for (byte b : digest) sb.append(String.format("%02x", b));
            return new File(pluginDir(), sb.append(".js").toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把「是否支持搜歌」的实测结果落盘（搜索页枚举免建桥用）；值没变不写盘 */
    public void noteMusicSearch(MusicSite site, boolean supported) {
        if (site == null || TextUtils.isEmpty(site.srcUrl)) return;
        int v = supported ? 1 : 2;
        if (site.musicSearch == v) return;
        site.musicSearch = v;
        List<MusicSite> list = listSites();
        for (MusicSite s : list) {
            if (site.srcUrl.equals(s.srcUrl)) s.musicSearch = v;
        }
        saveSites(list);
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
            // 首装整表播种：地址全记入已播种集合，之后用户删哪件都不复活
            saveSeededBuiltins(BuiltinReseed.allUrls(builtin));
            return builtin;
        }
        try {
            Type type = new TypeToken<List<MusicSite>>() {
            }.getType();
            List<MusicSite> list = gson.fromJson(json, type);
            if (list == null) return new ArrayList<>();
            // 存量清理（2026-10-20 拍板摘掉预置 bilibili）：老版本播种进去的
            // bilibili 记录按预置地址精确匹配删掉并落盘，只删这一条；
            // 用户手动加的同名/其它地址插件一律不碰。清完存盘，后续不再触发。
            boolean removed = list.removeIf(s -> s != null
                    && RETIRED_BUILTIN_BILIBILI.equals(s.srcUrl) && TextUtils.isEmpty(s.localPath));
            if (removed) {
                saveSites(list);
                FreeJsBridge b = bridges.remove(RETIRED_BUILTIN_BILIBILI);
                if (b != null) b.destroy();
                SpiderDebug.log("music", "已清理下架的预置 bilibili 插件记录");
            }
            // 补播种（2026-10-25）：后加的预置（油管音乐）只在首装时随整表种下，
            // 老用户存量非空永远拿不到，这里把从没播种过的后加预置补到列表尾。
            // 决策细则见 BuiltinReseed：记过的（含用户已删的）不复活、老预置不补、
            // 存量已有的不重复加；朋友版 builtinSites 为空表，本段恒无动作。
            List<MusicSite> builtin = builtinSites();
            Set<String> seeded = loadSeededBuiltins();
            boolean seededFresh = seeded == null;
            if (seededFresh) seeded = BuiltinReseed.initialSeeded(list, builtin);
            Set<String> seededBefore = new HashSet<>(seeded);
            List<MusicSite> appends = BuiltinReseed.planAppends(list, builtin, seeded);
            for (MusicSite b : appends) {
                list.add(b);
                SpiderDebug.log("music", "已补播种后加预置插件：%s", b.name);
            }
            if (seededFresh || !seeded.equals(seededBefore)) saveSeededBuiltins(seeded);
            if (!appends.isEmpty()) saveSites(list);
            return list;
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

    /** 已播种过的预置地址集合；键还没建（老存量没跑过补播种）返回 null，内容坏了按空集合 */
    private Set<String> loadSeededBuiltins() {
        String json = Prefers.getString(K_SEEDED_BUILTINS, "");
        if (TextUtils.isEmpty(json)) return null;
        try {
            Type type = new TypeToken<List<String>>() {
            }.getType();
            List<String> urls = gson.fromJson(json, type);
            return urls == null ? new HashSet<>() : new HashSet<>(urls);
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private void saveSeededBuiltins(Set<String> seeded) {
        try {
            Prefers.put(K_SEEDED_BUILTINS, gson.toJson(new ArrayList<>(seeded)));
        } catch (Exception e) {
            SpiderDebug.log("music", "save seeded builtins failed %s", e.getMessage());
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
            quarantineUntil.remove(srcUrl);
            timeoutCounts.remove(srcUrl);
        }
    }

    /* ---------------- 桥 ---------------- */

    private String downloadJs(String srcUrl) throws Exception {
        String js = OkHttp.string(srcUrl, 20000);
        if (TextUtils.isEmpty(js)) throw new IllegalStateException("插件下载失败：" + srcUrl);
        return js;
    }

    /** 取某 FreeJS 插件的桥（懒加载，阻塞，请在子线程调）；落雪插件返回 null，别串桥。
     * C10：隔离期内（60s 未到）拒绝建桥并抛错，调用方按失败收口；创建失败记启动失败名表。 */
    public FreeJsBridge bridgeOf(MusicSite site) throws Exception {
        if (site == null || TextUtils.isEmpty(site.srcUrl) || isLx(site)) return null;
        checkQuarantine(site);
        checkBlocked(site);
        FreeJsBridge b = bridges.get(site.srcUrl);
        if (b != null) return b;
        synchronized (bridges) {
            b = bridges.get(site.srcUrl);
            if (b != null) return b;
            initializingCount++;
            try {
                FreeJsBridge nb = new FreeJsBridge(site, readJs(site));
                nb.init();
                bridges.put(site.srcUrl, nb);
                failedNames.remove(site.srcUrl);
                return nb;
            } catch (Throwable t) {
                failedNames.add(site.srcUrl);
                throw t;
            } finally {
                initializingCount--;
            }
        }
    }

    /** 取某落雪插件的桥（懒加载，阻塞，请在子线程调）；隔离／启动失败口径同 bridgeOf */
    public LxBridge lxBridgeOf(MusicSite site) throws Exception {
        if (site == null || TextUtils.isEmpty(site.srcUrl) || !isLx(site)) return null;
        checkQuarantine(site);
        checkBlocked(site);
        LxBridge b = lxBridges.get(site.srcUrl);
        if (b != null) return b;
        synchronized (lxBridges) {
            b = lxBridges.get(site.srcUrl);
            if (b != null) return b;
            initializingCount++;
            try {
                LxBridge nb = new LxBridge(site, readJs(site));
                nb.init();
                lxBridges.put(site.srcUrl, nb);
                failedNames.remove(site.srcUrl);
                return nb;
            } catch (Throwable t) {
                failedNames.add(site.srcUrl);
                throw t;
            } finally {
                initializingCount--;
            }
        }
    }

    /**
     * 按 track.rawJson 里的 _plugin 找桥（FreeJS 曲目专用）。
     * 归属不明就是不明：宁可返回 null 让上层按失败收口并留日志，
     * 绝不默默换别的插件取址——换源取址是串源，比播不了更坏。
     */
    public FreeJsBridge bridgeForTrack(MusicTrack track) throws Exception {
        if (track == null || TextUtils.isEmpty(track.rawJson)) {
            SpiderDebug.log("music", "bridgeForTrack: track 无 rawJson，归属不明 id=%s",
                    track == null ? "null" : String.valueOf(track.id));
            return null;
        }
        try {
            String plugin = com.google.gson.JsonParser.parseString(track.rawJson)
                    .getAsJsonObject().get("_plugin").getAsString();
            for (MusicSite s : enabledSitesOfFormat(FORMAT_FREEJS)) {
                if (plugin.equals(s.srcUrl)) return bridgeOf(s);
            }
            SpiderDebug.log("music", "bridgeForTrack: _plugin=%s 与任何启用插件对不上 id=%s",
                    plugin, String.valueOf(track.id));
        } catch (Exception e) {
            SpiderDebug.log("music", "bridgeForTrack: rawJson 解析失败 id=%s %s",
                    String.valueOf(track.id), e.getMessage());
        }
        return null;
    }

    /* ---------------- 主源（语音"切换音乐源"用） ---------------- */

    public String primaryKey() {
        return Prefers.getString(K_PRIMARY, "");
    }

    public FreeJsBridge primaryBridge() throws Exception {
        // 主源只在 FreeJS 插件里轮换（语音「切换音乐源」的老语义），落雪不掺；
        // C5 开启时同一平台多个插件按耗时表升序取（最快优先，无记录排后），未开启保持现有顺序；
        // C4 已屏蔽／C10 隔离期内的插件不进候选
        List<MusicSite> enabled = orderCandidates(enabledSitesOfFormat(FORMAT_FREEJS));
        if (enabled.isEmpty()) return null;
        String key = primaryKey();
        for (MusicSite s : enabled) {
            if (key.equals(s.srcUrl)) return bridgeOf(s);
        }
        // 没指定主源时不许闭眼拿第一个：歌词型插件（歌词网/歌词千寻）搜不了歌，
        // 拿它当主源聚合搜永远空（预置 bilibili 摘除后第一个正好是歌词网）。
        // 优先第一个能搜歌的插件（落盘判据已知就免建桥实测，第十件），
        // 都判不出来才回退第一个。
        for (MusicSite s : enabled) {
            if (s.musicSearch == 2) continue;
            if (s.musicSearch == 1) return bridgeOf(s);
            try {
                FreeJsBridge b = bridgeOf(s);
                if (b == null) continue;
                boolean ok = b.supportsMusicSearch();
                noteMusicSearch(s, ok);
                if (ok) return b;
            } catch (Throwable ignored) {
            }
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
        for (MusicSite site : orderCandidates(enabledSitesOfFormat(FORMAT_FREEJS))) {
            try {
                FreeJsBridge b = bridgeOf(site);
                if (b == null) continue;
                MusicSearchResult r = b.search(keyword, page, type);
                if (r != null && !r.isEmpty()) {
                    // 单插件屏蔽平台：按内容平台判（GZ：屏蔽的平台不再用于该插件的内容）。
                    // 旧口径拿 t.platform 比——FreeJS 歌恒 "freejs"（源层），用户在面板屏蔽
                    // "freejs" 等于整插件自屏蔽、屏蔽 kw/tx 等内容平台又永远匹配不上；
                    // 内容平台取不到（单平台插件条目不带 platform 字段）时不按平台过滤。
                    for (MusicTrack t : r.getTracks()) {
                        if (t == null) continue;
                        String cp = com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(t);
                        if (cp == null || !MusicStore.isPlatformBlocked(site.srcUrl, cp)) tracks.add(t);
                    }
                }
                if (r != null && !r.isEnd()) end = false;
                noteBridgeSuccess(site.srcUrl);
            } catch (Throwable t) {
                if (isTimeoutError(t)) noteBridgeTimeout(site.srcUrl);
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
                if (compareVersion(remote, site.version) > 0 && !isUpdateIgnored(site.srcUrl, remote)) {
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

    /** 该插件的该版本是否被用户点过「忽略」（瓜子更新弹窗「忽略」语义） */
    public synchronized boolean isUpdateIgnored(String srcUrl, String version) {
        if (TextUtils.isEmpty(srcUrl) || TextUtils.isEmpty(version)) return false;
        try {
            String json = Prefers.getString(K_IGNORED_UPDATES, "");
            if (TextUtils.isEmpty(json)) return false;
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> map = gson.fromJson(json, type);
            return map != null && version.equals(map.get(srcUrl));
        } catch (Exception e) {
            return false;
        }
    }

    /** 记一次「忽略」：只忽略这一版，远端再出更高版本时 checkUpdate 照常返回新版 */
    public synchronized void ignoreUpdate(String srcUrl, String version) {
        if (TextUtils.isEmpty(srcUrl) || TextUtils.isEmpty(version)) return;
        try {
            String json = Prefers.getString(K_IGNORED_UPDATES, "");
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> map = TextUtils.isEmpty(json) ? null : gson.fromJson(json, type);
            if (map == null) map = new java.util.HashMap<>();
            map.put(srcUrl, version);
            Prefers.put(K_IGNORED_UPDATES, gson.toJson(map));
        } catch (Exception ignored) {
        }
    }

    /** 有更新就更新本地元数据（版本＋名字），并销毁旧桥让新脚本生效 */
    public void applyUpdate(MusicSite site, String newVersion) {
        List<MusicSite> list = listSites();
        for (MusicSite s : list) {
            if (site.srcUrl.equals(s.srcUrl)) {
                s.version = newVersion;
                if (!TextUtils.isEmpty(site.name)) s.name = site.name;
                s.musicSearch = 0; // 新脚本可能改了搜索支持，判据清回未知重新实测
            }
        }
        saveSites(list);
        // 脚本落盘缓存一并失效，否则重建桥读回的还是旧脚本（第十件）
        File cache = jsCacheFile(site.srcUrl);
        if (cache != null && cache.exists()) cache.delete();
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


    /* ---------------- C4 插件检测／C5 智能切换／C10 隔离与重载 ---------------- */

    /** 隔离检查：隔离期内抛错（调用方按失败收口）；60s 到期自动解除，下次取链重建桥 */
    private void checkQuarantine(MusicSite site) {
        Long until = quarantineUntil.get(site.srcUrl);
        if (until == null) return;
        if (System.currentTimeMillis() >= until) {
            quarantineUntil.remove(site.srcUrl);
            timeoutCounts.remove(site.srcUrl);
            return;
        }
        throw new IllegalStateException("插件已被自动停用，60s 后自动恢复");
    }

    public boolean isQuarantined(String srcUrl) {
        Long until = quarantineUntil.get(srcUrl);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            quarantineUntil.remove(srcUrl);
            timeoutCounts.remove(srcUrl);
            return false;
        }
        return true;
    }

    /**
     * 桥级拦截检测屏蔽（BLOCKED）：旧口径 isBlocked 只在 orderCandidates（聚合搜索／主源）
     * 消费，单源搜索与取链经 bridgeOf/lxBridgeOf 直达、拦不住——与隔离同位补在这里，
     * 所有建桥路径一律生效。检测中豁免：testPlugin 先把 srcUrl 记进 testing 才建桥
     * 检测，被屏蔽插件必须能靠重新检测通过来解封，桥都建不了就永远解不了。
     */
    private void checkBlocked(MusicSite site) {
        if (site == null || TextUtils.isEmpty(site.srcUrl)) return;
        if (testing.contains(site.srcUrl)) return;
        if (isBlocked(site.srcUrl)) throw new IllegalStateException("插件已被自动屏蔽（检测未通过）：" + site.srcUrl);
    }

    /** 是否在检测屏蔽列表里（C4：检测失败／超时且自动屏蔽开启时进表，检测通过自动解除） */
    public boolean isBlocked(String srcUrl) {
        MusicPluginTest t = loadPluginTest(srcUrl);
        return t != null && t.state == MusicPluginTest.State.BLOCKED;
    }

    public boolean isTesting(String srcUrl) {
        return testing.contains(srcUrl);
    }

    public MusicPluginTest loadPluginTest(String srcUrl) {
        if (TextUtils.isEmpty(srcUrl)) return null;
        return MusicStore.loadPluginTests().get(srcUrl);
    }

    private void savePluginTest(String srcUrl, MusicPluginTest test) {
        java.util.Map<String, MusicPluginTest> map = MusicStore.loadPluginTests();
        map.put(srcUrl, test);
        MusicStore.savePluginTests(map);
    }

    /** 候选排序：C5 开启时按耗时表升序（最快优先，无记录排后）；未开启保持现有顺序 */
    private List<MusicSite> orderCandidates(List<MusicSite> sites) {
        List<MusicSite> out = new ArrayList<>();
        for (MusicSite s : sites) {
            if (s == null || TextUtils.isEmpty(s.srcUrl)) continue;
            if (isQuarantined(s.srcUrl) || isBlocked(s.srcUrl)) continue;
            out.add(s);
        }
        if (!MusicStore.isSmartSwitch()) return out;
        final java.util.Map<String, java.util.Map<String, Long>> latency = MusicStore.loadPluginLatency();
        out.sort(Comparator.comparingLong(s -> {
            java.util.Map<String, Long> per = latency.get(s.platform);
            Long v = per == null ? null : per.get(s.srcUrl);
            return v == null ? Long.MAX_VALUE : v;
        }));
        return out;
    }

    private static boolean isTimeoutError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.util.concurrent.TimeoutException) return true;
            String m = c.getMessage();
            if (m != null && (m.contains("timed out") || m.contains("Timeout") || m.contains("超时"))) return true;
        }
        return false;
    }

    /** 桥调用成功：连续超时计数清零（瓜子成功清零口径） */
    public void noteBridgeSuccess(String srcUrl) {
        if (!TextUtils.isEmpty(srcUrl)) timeoutCounts.remove(srcUrl);
    }

    /** 桥调用超时：连续计数＋1，满 3 次即隔离（名进隔离表，60s 后自动重建） */
    public void noteBridgeTimeout(String srcUrl) {
        if (TextUtils.isEmpty(srcUrl)) return;
        int n = timeoutCounts.getOrDefault(srcUrl, 0) + 1;
        timeoutCounts.put(srcUrl, n);
        if (n >= QUARANTINE_THRESHOLD) {
            quarantineUntil.put(srcUrl, System.currentTimeMillis() + QUARANTINE_RECOVERY_MS);
            SpiderDebug.log("music", "插件连续 %s 次请求超时，已自动停用，60s 后自动重建：%s", n, srcUrl);
        }
    }

    /** C5 门槛：完成过检测的音源插件 ≥2 且至少一个平台可用（AVAILABLE） */
    public int testedPluginCount() {
        int n = 0;
        for (MusicPluginTest t : MusicStore.loadPluginTests().values()) {
            if (t != null && t.state != MusicPluginTest.State.UNTESTED) n++;
        }
        return n;
    }

    public boolean hasAvailableTestedPlugin() {
        for (MusicPluginTest t : MusicStore.loadPluginTests().values()) {
            if (t != null && t.state == MusicPluginTest.State.AVAILABLE) return true;
        }
        return false;
    }

    /** 尝试开启智能切换：门槛不过返回 false（调用方弹“暂时无法开启”弹窗，N 取实数） */
    public boolean tryEnableSmartSwitch() {
        if (testedPluginCount() >= 2 && hasAvailableTestedPlugin()) {
            MusicStore.setSmartSwitch(true);
            return true;
        }
        return false;
    }

    // ===== 第二波：播放路由取值（只加新键，不动已有键；存取本体在 MusicStore） =====
    /** 取链策略（瓜子 LxPluginPriority：fallback 自动切换／pluginOnly 仅插件） */
    public String getLinkPolicy() {
        return MusicStore.getLinkPolicy();
    }

    public void setLinkPolicy(String policy) {
        MusicStore.setLinkPolicy(policy);
    }

    /** 某平台指定的取链插件 srcUrl（null = 自动） */
    public String getPlatformPlugin(String platform) {
        return MusicStore.getPlatformPlugin(platform);
    }

    public void setPlatformPlugin(String platform, String srcUrl) {
        MusicStore.setPlatformPlugin(platform, srcUrl);
    }

    /**
     * C4 检测（子线程跑，回调在检测线程上）：搜索固定探针关键词＋首条取链计时＋歌词探针。
     * 任一步失败／超时记 FAILED；音源可用且歌词可用（或插件无歌词能力时只看音源）记 AVAILABLE；
     * 歌词型插件（搜不到 music 但 lyric 搜＋取词成功）同样记 AVAILABLE（音源档为空、只看歌词）。
     * 自动屏蔽开启时 FAILED 转 BLOCKED；检测通过自动解除屏蔽与隔离。
     */
    public void testPlugin(MusicSite site, PluginTestCallback cb) {
        if (site == null || TextUtils.isEmpty(site.srcUrl)) return;
        if (!testing.add(site.srcUrl)) return;
        final long gen = testGeneration;
        Task.execute(() -> {
            try {
                if (cb != null) cb.onTesting();
                quarantineUntil.remove(site.srcUrl);
                MusicPluginTest r = runPluginTest(site);
                if (gen != testGeneration) return;
                if (r.state == MusicPluginTest.State.FAILED && MusicStore.isAutoBlockInvalid()) {
                    r.state = MusicPluginTest.State.BLOCKED;
                }
                if (r.state == MusicPluginTest.State.AVAILABLE) {
                    quarantineUntil.remove(site.srcUrl);
                    timeoutCounts.remove(site.srcUrl);
                    failedNames.remove(site.srcUrl);
                }
                r.testedAt = System.currentTimeMillis();
                savePluginTest(site.srcUrl, r);
                if (cb != null) cb.onResult(r);
            } catch (Throwable t) {
                SpiderDebug.log("music", "testPlugin error site=%s %s", site.name, t.getMessage());
            } finally {
                testing.remove(site.srcUrl);
            }
        });
    }

    private MusicPluginTest runPluginTest(MusicSite site) {
        MusicPluginTest r = new MusicPluginTest();
        try {
            MusicTrack first;
            boolean lyricOnly = false;
            if (isLx(site)) {
                LxBridge b = lxBridgeOf(site);
                String key = null;
                for (Map.Entry<String, LxBridge.LxSource> e : b.sources().entrySet()) {
                    if (LxPlatformSearch.supported(e.getKey()) && e.getValue().actions.contains("musicUrl")) {
                        key = e.getKey();
                        break;
                    }
                }
                if (key == null) {
                    r.message = "该插件没有可检测的平台";
                    r.searchMessage = "没有可检测的平台";
                    r.state = MusicPluginTest.State.FAILED;
                    return r;
                }
                List<MusicTrack> tracks = LxPlatformSearch.search(key, TEST_PROBE_KEYWORD);
                if (tracks == null || tracks.isEmpty()) {
                    r.message = "搜不到探针歌曲";
                    r.searchMessage = "搜不到探针歌曲";
                    r.state = MusicPluginTest.State.FAILED;
                    return r;
                }
                r.searchOk = true;
                first = tracks.get(0);
                String platform = platformOf(first, key);
                JsonObject info = lxMusicInfo(first, platform);
                long a0 = System.currentTimeMillis();
                String resp = b.request(platform, "musicUrl", info, "320k");
                String url = extractUrl(resp);
                long ms = System.currentTimeMillis() - a0;
                r.audioOk = !TextUtils.isEmpty(url);
                r.latencyMs = ms;
                if (!r.audioOk) r.playMessage = "取不到播放地址";
                if (r.audioOk) MusicStore.notePluginLatency(site.platform, site.srcUrl, ms, true);
                if (b.supports(platform, "lyric")) {
                    r.lyricCapable = true;
                    String lr = b.request(platform, "lyric", info, "");
                    String text = extractLyric(lr);
                    r.lyricOk = !TextUtils.isEmpty(text);
                    if (r.lyricOk) r.preview = previewOf(text);
                    else r.lyricMessage = "歌词为空";
                } else {
                    r.lyricCapable = false;
                }
            } else {
                FreeJsBridge b = bridgeOf(site);
                if (b == null) {
                    r.message = "桥建不起来";
                    r.searchMessage = "桥建不起来";
                    r.state = MusicPluginTest.State.FAILED;
                    return r;
                }
                MusicSearchResult sr = b.search(TEST_PROBE_KEYWORD, 1, "music");
                List<MusicTrack> tracks = sr == null ? null : sr.getTracks();
                if (tracks == null || tracks.isEmpty()) {
                    MusicSearchResult lr = b.search(TEST_PROBE_KEYWORD, 1, "lyric");
                    List<MusicTrack> ltracks = lr == null ? null : lr.getTracks();
                    if (ltracks == null || ltracks.isEmpty()) {
                        r.message = "搜不到探针歌曲";
                        r.searchMessage = "搜不到探针歌曲";
                        r.state = MusicPluginTest.State.FAILED;
                        return r;
                    }
                    lyricOnly = true;
                    first = ltracks.get(0);
                } else {
                    first = tracks.get(0);
                }
                r.searchOk = true;
                if (!lyricOnly) {
                    long a0 = System.currentTimeMillis();
                    com.fongmi.android.tv.bean.music.MusicMediaSource ms = b.getMediaSource(first, MusicQuality.STANDARD);
                    long took = System.currentTimeMillis() - a0;
                    r.audioOk = ms != null && !TextUtils.isEmpty(ms.url);
                    r.latencyMs = took;
                    if (!r.audioOk) r.playMessage = "取不到播放地址";
                    if (r.audioOk) MusicStore.notePluginLatency(site.platform, site.srcUrl, took, true);
                }
                try {
                    com.fongmi.android.tv.bean.music.MusicLyric lyric = b.getLyric(first);
                    r.lyricOk = lyric != null && !TextUtils.isEmpty(lyric.rawLrc);
                    r.lyricCapable = r.lyricOk;
                    if (r.lyricOk) r.preview = previewOf(lyric.rawLrc);
                    else if (!lyricOnly) r.lyricMessage = "歌词为空";
                } catch (Throwable ignored) {
                    r.lyricCapable = false;
                }
            }
            if (lyricOnly) {
                r.state = r.lyricOk ? MusicPluginTest.State.AVAILABLE : MusicPluginTest.State.FAILED;
                if (!r.lyricOk) {
                    r.message = "歌词探针失败";
                    if (r.lyricMessage == null || r.lyricMessage.isEmpty()) r.lyricMessage = "歌词探针失败";
                }
            } else if (r.audioOk && (r.lyricOk || !r.lyricCapable)) {
                r.state = MusicPluginTest.State.AVAILABLE;
            } else {
                r.state = MusicPluginTest.State.FAILED;
                r.message = !r.audioOk ? "取链失败" : "歌词探针失败";
            }
            return r;
        } catch (Throwable t) {
            r.state = MusicPluginTest.State.FAILED;
            r.message = t.getMessage() == null ? "检测失败" : t.getMessage();
            // 批 6：异常落在哪一段就记哪一段的原因（搜索未过＝搜索段，其余归取播放段兜底）
            if (!r.searchOk && (r.searchMessage == null || r.searchMessage.isEmpty())) {
                r.searchMessage = r.message;
            } else if (!r.audioOk && (r.playMessage == null || r.playMessage.isEmpty())) {
                r.playMessage = r.message;
            }
            return r;
        }
    }

    private static String platformOf(MusicTrack track, String fallback) {
        try {
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            String p = wrap.has("_platform") ? wrap.get("_platform").getAsString() : "";
            return TextUtils.isEmpty(p) ? fallback : p;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 落雪 musicInfo（照 LxMusicSource.musicInfo 的字段拼，不走跨插件回退，只问自有桥） */
    private static JsonObject lxMusicInfo(MusicTrack track, String platform) {
        JsonObject info = new JsonObject();
        try {
            JsonObject wrap = JsonParser.parseString(track.rawJson).getAsJsonObject();
            if (wrap.has("_item") && wrap.get("_item").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : wrap.getAsJsonObject("_item").entrySet()) {
                    info.add(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable ignored) {
        }
        info.addProperty("name", track.title == null ? "" : track.title);
        info.addProperty("singer", track.artist == null ? "" : track.artist);
        info.addProperty("albumName", track.album == null ? "" : track.album);
        if (!info.has("album")) info.addProperty("album", track.album == null ? "" : track.album);
        info.addProperty("source", platform);
        info.addProperty("type", "music");
        if (track.duration > 0) info.addProperty("interval", track.duration / 1000);
        if (!TextUtils.isEmpty(track.cover)) info.addProperty("img", track.cover);
        return info;
    }

    /** 落雪取链结果取 url（照 LxMusicSource.extractUrl 口径） */
    private static String extractUrl(String result) {
        if (TextUtils.isEmpty(result)) return null;
        String r = result.trim();
        if (r.startsWith("http")) return r;
        if (r.startsWith("\"")) {
            try {
                String s = JsonParser.parseString(r).getAsString();
                return s.startsWith("http") ? s : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (r.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(r).getAsJsonObject();
                String url = o.has("url") ? o.get("url").getAsString() : "";
                return url.startsWith("http") ? url : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 落雪歌词结果取正文（照 LxMusicSource.extractLyric 口径） */
    private static String extractLyric(String result) {
        if (TextUtils.isEmpty(result)) return null;
        String r = result.trim();
        if (r.startsWith("\"")) {
            try {
                return JsonParser.parseString(r).getAsString();
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (r.startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(r).getAsJsonObject();
                String s = firstNonEmpty(o, "lyric", "rawLrc", "lrc");
                return s.isEmpty() ? null : s;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return r.contains("[") ? r : null;
    }

    private static String firstNonEmpty(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                String v = o.get(k).getAsString();
                if (!TextUtils.isEmpty(v)) return v;
            }
        }
        return "";
    }

    private static String previewOf(String text) {
        if (text == null) return "";
        String line = text.split("\n")[0].replaceAll("^\\[.*?\\]", "").trim();
        return line.length() > 30 ? line.substring(0, 30) : line;
    }

    /** C10 运行时状态快照（内存态，不落盘） */
    public MusicRuntimeStatus runtimeStatus() {
        MusicRuntimeStatus st = new MusicRuntimeStatus();
        st.liveRunners = bridges.size() + lxBridges.size();
        List<String> qNames = new ArrayList<>();
        for (String srcUrl : new ArrayList<>(quarantineUntil.keySet())) {
            if (isQuarantined(srcUrl)) qNames.add(nameOf(srcUrl));
        }
        st.quarantinedRunners = qNames.size();
        st.quarantinedNames = qNames;
        st.failedPlugins = failedNames.size();
        List<String> fNames = new ArrayList<>();
        for (String srcUrl : failedNames) fNames.add(nameOf(srcUrl));
        st.failedNames = fNames;
        st.enabledLocalPlugins = enabledSites().size();
        st.initializing = initializingCount > 0;
        st.reloading = reloading;
        return st;
    }

    private String nameOf(String srcUrl) {
        for (MusicSite s : listSites()) {
            if (srcUrl.equals(s.srcUrl)) return s.name == null ? srcUrl : s.name;
        }
        return srcUrl;
    }

    /**
     * C10 重新加载：清 bridges／lxBridges 并 destroy，中断正在进行的检测（代次失效，旧回调丢弃），
     * 隔离表清掉（手动重载即恢复候选），启动失败名表保留（瓜子口径：重载解决不了启动失败，需重导入或更新）。
     * 当前播放不受影响：播放器持有的是已解析出的直链 MediaItem，不直持桥实例（MusicPlaybackService
     * prepareTrack → MusicRepository.playUrl 逐次取桥取链，播中那首的 URL 已进 ExoPlayer），
     * 清管理器缓存只影响下一次取链（重建桥），不碰播中媒体。
     */
    public void reloadBridges() {
        reloading = true;
        testGeneration++;
        testing.clear();
        try {
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
            quarantineUntil.clear();
            timeoutCounts.clear();
        } finally {
            reloading = false;
        }
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
