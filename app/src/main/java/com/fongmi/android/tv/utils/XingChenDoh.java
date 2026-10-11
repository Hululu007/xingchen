package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

public class XingChenDoh {

    public static final String KEY = "xingchen.doh_provider";
    public static final String[] NAMES = {"系统", "腾讯", "阿里", "360"};
    private static final String[] URLS = {"", "https://doh.pub/dns-query", "https://dns.alidns.com/dns-query", "https://doh.360.cn/dns-query"};
    /**
     * DoH 服务器直连 IP（bootstrap JSON）：运营商劫持 DNS 时，DoH 连服务器域名都解析不出来，
     * 用这些 IP 直连 443 端口做 DoH 查询，不走 DNS。
     * FongMi 正统机制：ips 走 Doh 原生 @SerializedName("ips") 字段，经 Doh.objectFrom(JSON) 解析，
     * 不新增 setter 方法。IP 数值经 Google(8.8.8.8) 与 Cloudflare(1.1.1.1) DoH 双源交叉验证（2026-10-11）。
     */
    private static final String[] IPS_JSON = {
            "[]",
            "[\"1.12.12.12\",\"120.53.53.53\"]",        // 腾讯 doh.pub
            "[\"223.5.5.5\",\"223.6.6.6\"]",            // 阿里 dns.alidns.com
            "[\"101.198.192.33\",\"101.198.193.29\"]",  // 360 doh.360.cn
    };

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("xingchen", Context.MODE_PRIVATE);
    }

    public static int getIndex(Context context) {
        int i = prefs(context).getInt(KEY, 0);
        return (i >= 0 && i < NAMES.length) ? i : 0;
    }

    public static String getName(Context context) {
        return NAMES[getIndex(context)];
    }

    public static Doh toDoh(Context context) {
        int i = getIndex(context);
        // FongMi 正统方式：拼出带 ips 的 JSON，走原生 Doh.objectFrom() 解析，不新增 setter
        String json = "{\"name\":\"" + NAMES[i] + "\",\"url\":\"" + URLS[i] + "\",\"ips\":" + IPS_JSON[i] + "}";
        return Doh.objectFrom(json);
    }

    public static void apply(Context context) {
        try {
            if (getIndex(context) == 0) {
                // 系统模式：不启用 DoH，传空 URL 的 Doh 使 OkDns 走 Dns.SYSTEM（跟默影视一致）
                OkHttp.dns().setDoh(new Doh().name(NAMES[0]).url(""));
            } else {
                OkHttp.dns().setDoh(toDoh(context));
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        // 同步应用到海阔客户端（8.83 loadDns 逻辑）
        try {
            com.fongmi.android.tv.api.hk.HkHttp.applyDoh(context);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void setIndex(Context context, int index) {
        prefs(context).edit().putInt(KEY, index).apply();
        apply(context);
    }
}
