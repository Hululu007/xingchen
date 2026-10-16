package com.fongmi.android.tv.emby;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.clash.SourceProxyPolicy;
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
    private static final String K_SECTIONS = "emby2.sections";
    private static final String K_HOMECACHE = "emby2.homecache.";
    private static final String K_HOMECACHE_TIME = "emby2.homecache.time.";
    private static final String K_DEVICE = "emby2.device_id";

    /** 单台 Emby 服务器。token 为空 = 未登录（地址保留，换号不用重填）。 */
    public static class EmbyServer {
        public String id = "";
        public String alias = "";
        public String baseUrl = "";
        public String userId = "";
        public String userName = "";
        public String token = "";
        public long lastUsed = 0;
        /** 本服是否走节点（默认 false=直连）。开关只许经 setProxyEnabled 改。 */
        public boolean proxyEnabled = false;

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
                o.put("userId", userId);
                o.put("userName", userName);
                o.put("token", token);
                o.put("lastUsed", lastUsed);
                o.put("proxyEnabled", proxyEnabled);
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
            s.userId = o.optString("userId", "");
            s.userName = o.optString("userName", "");
            s.token = o.optString("token", "");
            s.lastUsed = o.optLong("lastUsed", 0);
            s.proxyEnabled = o.optBoolean("proxyEnabled", false);
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
        try {
            JSONArray arr = new JSONArray(Prefers.getString(K_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                EmbyServer s = EmbyServer.fromJson(arr.optJSONObject(i));
                if (!TextUtils.isEmpty(s.id)) list.add(s);
            }
        } catch (Exception ignored) {
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
        for (EmbyServer s : list) arr.put(s.toJson());
        Prefers.put(K_SERVERS, arr.toString());
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
        if (id.equals(Prefers.getString(K_ACTIVE, ""))) Prefers.put(K_ACTIVE, "");
        Map<String, Map<String, Boolean>> map = sections();
        if (map.remove(id) != null) persistSections(map);
        Prefers.remove(K_HOMECACHE + id);
        Prefers.remove(K_HOMECACHE_TIME + id);
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
