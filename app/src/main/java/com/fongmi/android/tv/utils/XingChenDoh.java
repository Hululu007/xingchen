package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

public class XingChenDoh {

    public static final String KEY = "xingchen.doh_provider";
    public static final String[] NAMES = {"系统", "腾讯", "阿里", "360"};
    private static final String[] URLS = {"", "https://doh.pub/dns-query", "https://dns.alidns.com/dns-query", "https://doh.360.cn/dns-query"};

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
        // FongMi 原版行为：内置条目不带 ips（无 bootstrap），ips 只从配置下发来
        String json = "{\"name\":\"" + NAMES[i] + "\",\"url\":\"" + URLS[i] + "\"}";
        return Doh.objectFrom(json);
    }

    public static void apply(Context context) {
        try {
            if (getIndex(context) == 0) {
                // 系统模式：清除 DoH，直接走系统 DNS
                OkHttp.dns().clearDoh();
            } else {
                OkHttp.dns().setDoh(toDoh(context));
            }
            // 注意：不要在这里 resetClients，会把封面搞没（1.1.8 教训）
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
