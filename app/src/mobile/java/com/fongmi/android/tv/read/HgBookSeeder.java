package com.fongmi.android.tv.read;

import com.fongmi.android.tv.legado.HgBookBridge;
import com.fongmi.android.tv.legado.LegadoSource;
import com.fongmi.android.tv.legado.LegadoStore;
import com.google.gson.Gson;

/**
 * 红果内置书源种子：把红果小说/红果听书两个哨兵源种进书源库（已存在不重复）。
 * 实现不在位（朋友版剥了红果包）时不种，免得留下点开必死的源。
 * 书源管理页与搜书页打开时各调一次。
 */
public final class HgBookSeeder {

    private HgBookSeeder() {
    }

    public static synchronized void ensureSeeded() {
        if (!HgBookBridge.available()) return;
        try {
            if (LegadoStore.get(HgBookBridge.URL_NOVEL) == null) {
                LegadoStore.importText(sourceJson(HgBookBridge.URL_NOVEL,
                        HgBookBridge.NAME_NOVEL, LegadoSource.TYPE_TEXT));
            }
            if (LegadoStore.get(HgBookBridge.URL_AUDIO) == null) {
                LegadoStore.importText(sourceJson(HgBookBridge.URL_AUDIO,
                        HgBookBridge.NAME_AUDIO, LegadoSource.TYPE_AUDIO));
            }
        } catch (Throwable ignored) {
        }
    }

    private static String sourceJson(String url, String name, int type) {
        LegadoSource s = new LegadoSource();
        s.bookSourceUrl = url;
        s.bookSourceName = name;
        s.bookSourceType = type;
        s.bookSourceGroup = "内置";
        return new Gson().toJson(s);
    }
}
