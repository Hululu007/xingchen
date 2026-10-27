package com.fongmi.android.tv.legado;

import android.net.Uri;

import com.github.catvod.utils.Prefers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阅读通行证：人机验证完成后按站（host）存下的 Cookie，
 * 后续该站的书源请求自动带上（LegadoHttpAndroid 注入/回填）。
 * 存的是可直接当 Cookie 请求头用的整串（"a=1; b=2"），与字幕过盾
 * ShieldBypass 的存法同口径，只是键前缀分开、互不串味。
 */
public final class LegadoCookies {

    private static final String KEY_PREFIX = "legado_cookie_";
    private static final String KEY_TIME_PREFIX = "legado_cookie_at_";

    private LegadoCookies() {
    }

    public static String hostOf(String url) {
        if (url == null) return "";
        try {
            String host = Uri.parse(url).getHost();
            return host == null ? "" : host;
        } catch (Throwable e) {
            return "";
        }
    }

    public static boolean has(String host) {
        return host != null && !host.isEmpty() && !cookieHeader(host).isEmpty();
    }

    /** 取已存的 Cookie 整串（可直接作 Cookie 请求头）；无则空串。 */
    public static String cookieHeader(String host) {
        if (host == null || host.isEmpty()) return "";
        String v = Prefers.getString(KEY_PREFIX + host, "");
        return v == null ? "" : v;
    }

    /** 存整串 Cookie 并盖时间戳（验证页 harvest 后调用）。 */
    public static void save(String host, String cookieHeader) {
        if (host == null || host.isEmpty()) return;
        if (cookieHeader == null || cookieHeader.isEmpty()) return;
        Prefers.put(KEY_PREFIX + host, cookieHeader);
        Prefers.put(KEY_TIME_PREFIX + host, String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 把一次响应带回的 Set-Cookie 并进已存通行证（站点轮换 cookie 时保鲜）。
     * 只对已有通行证的站生效，不给全体书源凭空开 cookie 罐。
     */
    public static void merge(String host, Map<String, String> setCookies) {
        if (!has(host) || setCookies == null || setCookies.isEmpty()) return;
        Map<String, String> jar = parse(cookieHeader(host));
        jar.putAll(setCookies);
        save(host, join(jar));
    }

    /** 通行证保存时间（毫秒）；没存过返回 0。 */
    public static long savedAt(String host) {
        if (host == null || host.isEmpty()) return 0;
        try {
            return Long.parseLong(Prefers.getString(KEY_TIME_PREFIX + host, "0"));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 窗口内刚存过（并行请求里别人刚验完，本次可免弹直接带证重试）。 */
    public static boolean recentlySaved(String host, long windowMs) {
        long at = savedAt(host);
        return at > 0 && System.currentTimeMillis() - at <= windowMs;
    }

    static Map<String, String> parse(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null) return out;
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0) out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            else if (!p.isEmpty()) out.put(p, "");
        }
        return out;
    }

    static String join(Map<String, String> jar) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey());
            if (e.getValue() != null && !e.getValue().isEmpty()) sb.append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
