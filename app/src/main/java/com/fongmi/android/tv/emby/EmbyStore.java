package com.fongmi.android.tv.emby;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.gitcloud.secure.GitCloudTokenStore;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emby 服务器配置与凭证存档。多服模型：服务器列表 + 当前服 + 按服栏目开关 + 按服首页缓存。
 * 键名前缀 emby2.，与点播线路配置完全独立，线路管理的「清空」波及不到。
 * 密码不落盘：只在登录请求体里出现一次，存档只留 token / userId / userName。
 */
public class EmbyStore {

    private static final String K_SERVERS = "emby2.servers";
    private static final String K_ACTIVE = "emby2.active";
    private static final String K_LAST = "emby2.last";
    private static final String K_SECTIONS = "emby2.sections";
    private static final String K_HOMECACHE = "emby2.homecache.";
    private static final String K_HOMECACHE_TIME = "emby2.homecache.time.";
    private static final String K_DEVICE = "emby2.device_id";

    /** 单台 Emby 服务器。token 为空 = 未登录（地址保留，换号不用重填）。 */
    public static class EmbyServer {
        public String id = "";
        public String alias = "";
        public String baseUrl = "";
        /** 备选地址（主地址连不上时登录探测用，主在前备选在后）。 */
        public final List<String> altUrls = new ArrayList<>();
        public String userId = "";
        public String userName = "";
        public String token = "";
        public long lastUsed = 0;
        /** 本服是否走节点（默认 false=直连）。开关只许经 setProxyEnabled 改。 */
        public boolean proxyEnabled = false;
        /** 服卡是否显示用户名（默认显示）。 */
        public boolean showUser = true;
        /** 服卡是否显示上次播放时间（默认不显示）。 */
        public boolean showLastPlayed = false;
        /** 上次播放时间（reportPlaying 时顺手记）。 */
        public long lastPlayedAt = 0;

        public boolean isLogin() {
            return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(userId) && !TextUtils.isEmpty(token);
        }

        public String displayName() {
            if (!TextUtils.isEmpty(alias)) return alias;
            return TextUtils.isEmpty(baseUrl) ? id : baseUrl;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("alias", alias);
                o.put("baseUrl", baseUrl);
                JSONArray alts = new JSONArray();
                for (String u : altUrls) if (u != null && !u.trim().isEmpty()) alts.put(u.trim());
                o.put("altUrls", alts);
                o.put("userId", userId);
                o.put("userName", userName);
                o.put("token", token);
                o.put("lastUsed", lastUsed);
                o.put("proxyEnabled", proxyEnabled);
                o.put("showUser", showUser);
                o.put("showLastPlayed", showLastPlayed);
                o.put("lastPlayedAt", lastPlayedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static EmbyServer fromJson(JSONObject o) {
            EmbyServer s = new EmbyServer();
            if (o == null) return s;
            s.id = o.optString("id", "");
            s.alias = o.optString("alias", "");
            s.baseUrl = trimUrl(o.optString("baseUrl", ""));
            JSONArray alts = o.optJSONArray("altUrls");
            if (alts != null) for (int i = 0; i < alts.length(); i++) {
                String u = trimUrl(alts.optString(i, ""));
                if (!u.isEmpty() && !s.altUrls.contains(u)) s.altUrls.add(u);
            }
            s.userId = o.optString("userId", "");
            s.userName = o.optString("userName", "");
            s.token = o.optString("token", "");
            s.lastUsed = o.optLong("lastUsed", 0);
            s.proxyEnabled = o.optBoolean("proxyEnabled", false);
            s.showUser = o.optBoolean("showUser", true);
            s.showLastPlayed = o.optBoolean("showLastPlayed", false);
            s.lastPlayedAt = o.optLong("lastPlayedAt", 0);
            return s;
        }
    }

    private static String trimUrl(String u) {
        u = u == null ? "" : u.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /** ---- 服务器列表 ---- */

    public static List<EmbyServer> servers() {
        List<EmbyServer> list = new ArrayList<>();
        boolean[] migrated = new boolean[1];
        try {
            JSONArray arr = new JSONArray(Prefers.getString(K_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                EmbyServer s = EmbyServer.fromJson(arr.optJSONObject(i));
                if (!TextUtils.isEmpty(s.id)) {
                    s.token = takeToken(s.id, s.token, migrated);
                    list.add(s);
                }
            }
        } catch (Exception ignored) {
        }
        // 读时真发生了迁移（内联明文已进加密仓）才顺手把剥离版回写一次，
        // 不让老明文在 Prefers 里躺到下一次 persist；没迁移的读不写盘。
        // 回写失败只留痕，不影响本次读结果。
        if (migrated[0] && !list.isEmpty()) {
            try {
                persist(list);
            } catch (Throwable e) {
                SpiderDebug.log("emby", "rewrite stripped servers failed error=%s", e.getMessage());
            }
        }
        if (list.isEmpty()) migrateLegacy(list);
        return list;
    }

    /** 骨架单服老键一次性迁移：只跑一次，迁完删老键。 */
    private static void migrateLegacy(List<EmbyServer> list) {
        try {
            String base = Prefers.getString("emby_base_url", "");
            String token = Prefers.getString("emby_token", "");
            if (TextUtils.isEmpty(base) || TextUtils.isEmpty(token)) return;
            EmbyServer s = new EmbyServer();
            s.id = UUID.randomUUID().toString();
            s.alias = "默认服务器";
            s.baseUrl = trimUrl(base);
            s.userId = Prefers.getString("emby_user_id", "");
            s.userName = Prefers.getString("emby_user_name", "");
            s.token = token;
            s.lastUsed = System.currentTimeMillis();
            list.add(s);
            persist(list);
            Prefers.remove("emby_base_url");
            Prefers.remove("emby_user_id");
            Prefers.remove("emby_user_name");
            Prefers.remove("emby_token");
        } catch (Exception ignored) {
        }
    }

    private static void persist(List<EmbyServer> list) {
        JSONArray arr = new JSONArray();
        for (EmbyServer s : list) {
            // 访问令牌不进明文 JSON：同步进加密令牌仓（与 cloud/gitcloud 同口径），
            // 落盘的服务器列表只留空位，读回时由 servers() 回填。
            // 只有加密写回读校验通过才剥离；校验不过保留内联值落盘，不静默丢令牌。
            boolean stored = tokenSync(s.id, s.token);
            JSONObject o = s.toJson();
            if (stored) o.remove("token");
            arr.put(o);
        }
        Prefers.put(K_SERVERS, arr.toString());
        // 服地址/走节点开关可能变了，按 host 认领表失效重建
        EmbyProxyGate.invalidate();
    }

    private static String tokenKey(String serverId) {
        return TextUtils.isEmpty(serverId) ? "" : "emby_tok:" + serverId;
    }

    /**
     * 令牌同步进加密仓；返回 true 才表示落盘副本可以剥离：
     * 非空令牌写后必须回读校验（读回值等于刚写值），校验不过／写抛错都留痕并返回 false，
     * 调用方保留内联值落盘，不许先剥离再丢令牌。空令牌＝清除，删仓即算完成。
     */
    private static boolean tokenSync(String serverId, String token) {
        String key = tokenKey(serverId);
        if (TextUtils.isEmpty(key)) return TextUtils.isEmpty(token);
        try {
            if (TextUtils.isEmpty(token)) {
                GitCloudTokenStore.remove(key);
                return true;
            }
            GitCloudTokenStore.put(key, token);
            String back = GitCloudTokenStore.get(key);
            if (token.equals(back)) return true;
            SpiderDebug.log("emby", "secure token verify failed server=%s, keep inline", serverId);
            return false;
        } catch (Throwable e) {
            SpiderDebug.log("emby", "secure token store failed server=%s error=%s, keep inline", serverId, e.getMessage());
            return false;
        }
    }

    /** 内联有值＝老明文，迁入加密仓后用它（migrated 置真供读路径回写剥离版）；内联为空＝从加密仓回填 */
    private static String takeToken(String serverId, String inline, boolean[] migrated) {
        String key = tokenKey(serverId);
        if (TextUtils.isEmpty(key)) return inline == null ? "" : inline;
        try {
            if (!TextUtils.isEmpty(inline)) {
                GitCloudTokenStore.put(key, inline);
                if (migrated != null) migrated[0] = true;
                return inline;
            }
            String stored = GitCloudTokenStore.get(key);
            return stored == null ? "" : stored;
        } catch (Throwable e) {
            return inline == null ? "" : inline;
        }
    }

    public static EmbyServer getServer(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (EmbyServer s : servers()) if (id.equals(s.id)) return s;
        return null;
    }

    public static EmbyServer active() {
        String id = Prefers.getString(K_ACTIVE, "");
        EmbyServer s = getServer(id);
        return s;
    }

    /** null 或空串 = 清除当前服。 */
    public static void setActive(String id) {
        if (TextUtils.isEmpty(id)) {
            Prefers.put(K_ACTIVE, "");
            return;
        }
        EmbyServer s = getServer(id);
        if (s == null) return;
        s.lastUsed = System.currentTimeMillis();
        List<EmbyServer> list = servers();
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) list.set(i, s);
        persist(list);
        Prefers.put(K_ACTIVE, id);
    }

    /** 上次进入的那台服：只在 EmbyMode.enter 落点；exit 清 active 时不清除；重启仍在。 */
    public static EmbyServer lastEntered() {
        return getServer(Prefers.getString(K_LAST, ""));
    }

    public static void setLastEntered(String id) {
        if (TextUtils.isEmpty(id)) return;
        if (getServer(id) == null) return;
        Prefers.put(K_LAST, id);
    }

    /** 清除上次进入记录（enter 挂载最终失败回滚时用，避免下次又自动进同一台失败的服）。 */
    public static void clearLastEntered() {
        Prefers.remove(K_LAST);
    }

    /** 任一服含 token 即算有登录。 */
    public static boolean hasLogin() {
        for (EmbyServer s : servers()) if (s.isLogin()) return true;
        return false;
    }

    public static void saveServer(EmbyServer s) {
        if (s == null) return;
        if (TextUtils.isEmpty(s.id)) s.id = UUID.randomUUID().toString();
        s.baseUrl = trimUrl(s.baseUrl);
        List<EmbyServer> list = servers();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
                break;
            }
        }
        if (!found) list.add(s);
        persist(list);
    }

    /** 删服；若删的是当前服一并清除。 */
    public static void removeServer(String id) {
        if (TextUtils.isEmpty(id)) return;
        List<EmbyServer> list = servers();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(id)) list.remove(i);
        persist(list);
        tokenSync(id, "");
        if (id.equals(Prefers.getString(K_ACTIVE, ""))) Prefers.put(K_ACTIVE, "");
        Map<String, Map<String, Boolean>> map = sections();
        if (map.remove(id) != null) persistSections(map);
        Prefers.remove(K_HOMECACHE + id);
        Prefers.remove(K_HOMECACHE_TIME + id);
        try {
            clearCustomIcon(App.get(), id);
        } catch (Throwable ignored) {
        }
    }

    /** 登录成功：只存 token / userId / userName，密码不写盘。 */
    public static void saveLogin(String serverId, String userId, String userName, String token) {
        EmbyServer s = getServer(serverId);
        if (s == null) return;
        s.userId = userId == null ? "" : userId;
        s.userName = userName == null ? "" : userName;
        s.token = token == null ? "" : token;
        s.lastUsed = System.currentTimeMillis();
        saveServer(s);
    }

    /** 退出登录：只清凭证三件套，地址与别名保留。 */
    public static void logout(String serverId) {
        EmbyServer s = getServer(serverId);
        if (s == null) return;
        s.userId = "";
        s.userName = "";
        s.token = "";
        saveServer(s);
    }

    /** 播放开始上报时顺手记：本服上次播放时间（服务器管理页展示用）。 */
    public static void touchLastPlayed(String serverId) {
        EmbyServer s = getServer(serverId);
        if (s == null) return;
        s.lastPlayedAt = System.currentTimeMillis();
        saveServer(s);
    }

    /** ---- 自定义图标（只存本地应用私有目录，不上传） ---- */

    private static final String DIR_ICON = "emby_icons";

    /** 本服自定义图标文件（不存在 = 用默认图标）。 */
    public static java.io.File iconFile(Context context, String serverId) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), DIR_ICON);
            return new java.io.File(dir, serverId + ".png");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 本服有没有自定义图标。 */
    public static boolean hasCustomIcon(Context context, String serverId) {
        java.io.File f = iconFile(context, serverId);
        return f != null && f.exists() && f.length() > 0;
    }

    /** 存自定义图标：把选中的图拷进私有目录（按 serverId 存）。 */
    public static boolean saveCustomIcon(Context context, String serverId, android.net.Uri uri) {
        if (context == null || TextUtils.isEmpty(serverId) || uri == null) return false;
        try {
            java.io.File f = iconFile(context, serverId);
            java.io.File dir = f.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            java.io.InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            java.io.OutputStream out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            return f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 恢复默认图标：删掉自定义图标文件。删服时也调这个。 */
    public static void clearCustomIcon(Context context, String serverId) {
        try {
            java.io.File f = iconFile(context, serverId);
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** ---- 全量页排序/筛选偏好（全局存一份） ---- */

    private static final String K_SORT = "emby2.libsort";
    private static final String K_PLAYED = "emby2.libplayed";
    private static final String K_YEAR = "emby2.libyear";
    private static final String K_RATING = "emby2.librating";
    private static final String K_TAG = "emby2.libtag";

    /** 排序偏好，形如 "SortName:Ascending"。 */
    public static String libSort() {
        return Prefers.getString(K_SORT, "SortName:Ascending");
    }

    public static void setLibSort(String sort) {
        Prefers.put(K_SORT, sort == null ? "SortName:Ascending" : sort);
    }

    /** 已看筛选：""=全部，"IsPlayed"=已看，"IsUnplayed"=未看，"IsFavorite"=收藏。 */
    public static String libPlayedFilter() {
        return Prefers.getString(K_PLAYED, "");
    }

    public static void setLibPlayedFilter(String f) {
        Prefers.put(K_PLAYED, f == null ? "" : f);
    }

    public static String libYear() {
        return Prefers.getString(K_YEAR, "");
    }

    public static void setLibYear(String y) {
        Prefers.put(K_YEAR, y == null ? "" : y);
    }

    public static String libRating() {
        return Prefers.getString(K_RATING, "");
    }

    public static void setLibRating(String r) {
        Prefers.put(K_RATING, r == null ? "" : r);
    }

    public static String libTag() {
        return Prefers.getString(K_TAG, "");
    }

    public static void setLibTag(String t) {
        Prefers.put(K_TAG, t == null ? "" : t);
    }

    /** ---- 每台安装唯一的 Emby DeviceId（防多人同服撞号） ---- */

    /**
     * 每台安装唯一的 DeviceId：空则生成（UUID 去横线小写 hex）并持久化。
     * 所有 Emby 鉴权头的 DeviceId= 都必须用它；服务端返回的 DirectStreamUrl 里
     * 自带的 DeviceId 参数是服务端拼的，不用动。
     */
    public static String deviceId() {
        try {
            String id = Prefers.getString(K_DEVICE, "");
            if (!TextUtils.isEmpty(id)) return id;
            id = UUID.randomUUID().toString().replace("-", "").toLowerCase();
            Prefers.put(K_DEVICE, id);
            return id;
        } catch (Throwable t) {
            return "xingchen-android";
        }
    }

    /** ---- 走节点（按服独立存 EmbyStore，框架表只做镜像） ---- */

    /** 本服是否开了「走节点」（默认关 = 直连）。 */
    public static boolean isProxyEnabled(String serverId) {
        EmbyServer s = getServer(serverId);
        return s != null && s.proxyEnabled;
    }

    /**
     * 改本服「走节点」开关的唯一入口：写 EmbyStore 自有记录，同时镜像一份
     * SourceProxyPolicy.setEnabled(ctx, KIND_EMBY, serverId, on)。禁直写框架表。
     */
    public static void setProxyEnabled(Context context, String serverId, boolean on) {
        if (TextUtils.isEmpty(serverId)) return;
        EmbyServer s = getServer(serverId);
        if (s == null) return;
        s.proxyEnabled = on;
        saveServer(s);
        try {
            SourceProxyPolicy.setEnabled(context, SourceProxyPolicy.KIND_EMBY, serverId, on);
        } catch (Throwable ignored) {
        }
    }

    /** ---- 专属首页栏目显隐（按服分别存，未配置默认全显示） ---- */

    private static Map<String, Map<String, Boolean>> sections() {
        Map<String, Map<String, Boolean>> map = new HashMap<>();
        try {
            JSONObject root = new JSONObject(Prefers.getString(K_SECTIONS, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String sid = keys.next();
                JSONObject o = root.optJSONObject(sid);
                if (o == null) continue;
                Map<String, Boolean> inner = new HashMap<>();
                Iterator<String> ks = o.keys();
                while (ks.hasNext()) {
                    String k = ks.next();
                    inner.put(k, o.optBoolean(k, true));
                }
                map.put(sid, inner);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void persistSections(Map<String, Map<String, Boolean>> map) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Map<String, Boolean>> e : map.entrySet()) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, Boolean> i : e.getValue().entrySet()) o.put(i.getKey(), i.getValue());
                root.put(e.getKey(), o);
            }
            Prefers.put(K_SECTIONS, root.toString());
        } catch (Exception ignored) {
        }
    }

    /** sectionKey 取值：resume、libraries、latest、nextup、lib:<viewId>。 */
    public static boolean sectionVisible(String serverId, String sectionKey) {
        if (TextUtils.isEmpty(serverId) || TextUtils.isEmpty(sectionKey)) return true;
        Map<String, Boolean> inner = sections().get(serverId);
        if (inner == null || !inner.containsKey(sectionKey)) return true;
        return Boolean.TRUE.equals(inner.get(sectionKey));
    }

    public static void setSectionVisible(String serverId, String sectionKey, boolean visible) {
        if (TextUtils.isEmpty(serverId) || TextUtils.isEmpty(sectionKey)) return;
        Map<String, Map<String, Boolean>> map = sections();
        Map<String, Boolean> inner = map.get(serverId);
        if (inner == null) {
            inner = new HashMap<>();
            map.put(serverId, inner);
        }
        inner.put(sectionKey, visible);
        persistSections(map);
    }

    /** ---- 专属首页首屏缓存（按服，换服各自生效） ---- */

    public static void saveHomeCache(String serverId, String json) {
        if (TextUtils.isEmpty(serverId)) return;
        Prefers.put(K_HOMECACHE + serverId, json == null ? "" : json);
        Prefers.put(K_HOMECACHE_TIME + serverId, System.currentTimeMillis());
    }

    public static String homeCache(String serverId) {
        if (TextUtils.isEmpty(serverId)) return "";
        return Prefers.getString(K_HOMECACHE + serverId, "");
    }

    public static long homeCacheTime(String serverId) {
        if (TextUtils.isEmpty(serverId)) return 0;
        return Prefers.getLong(K_HOMECACHE_TIME + serverId, 0);
    }
}
