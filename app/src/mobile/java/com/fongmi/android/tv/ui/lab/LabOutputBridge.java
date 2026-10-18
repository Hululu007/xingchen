package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.Notify;

/**
 * 服务产出接入：按包声明走——
 * live → 写进直播源（沿用壳子加源落点：Config type=1＋LiveConfig.load）
 * vod  → 写进点播接口源（Config type=0＋VodConfig.load）
 * pan/tool → 只给打开/复制地址（未深接，报告里标注）
 * 声明缺失时，UI 给「当直播源用/当点播源用」两钮让用户点。
 */
public final class LabOutputBridge {

    private LabOutputBridge() {
    }

    /** 主动作：按声明接入。kind 为空时返回 false，由 UI 弹出两钮。 */
    public static boolean apply(Activity act, LabManifest manifest, int port) {
        if (manifest == null || manifest.output == null || TextUtils.isEmpty(manifest.output.kind)) return false;
        String kind = manifest.output.kind;
        String url = address(manifest, port);
        if (TextUtils.isEmpty(url)) {
            Notify.show("这个服务没给地址，先看看运行记录");
            return true;
        }
        if ("live".equals(kind)) {
            asLive(act, url, manifest.name);
            return true;
        }
        if ("vod".equals(kind)) {
            asVod(act, url, manifest.name);
            return true;
        }
        // pan / tool：只打开或复制
        openOrCopy(act, url);
        return true;
    }

    /** 产出地址：target 模板里的 {port} 换成实际端口 */
    public static String address(LabManifest manifest, int port) {
        if (manifest == null || manifest.output == null) return "";
        return manifest.output.target.replace("{port}", String.valueOf(port));
    }

    public static void asLive(Activity act, String url, String name) {
        saveAndLoad(act, 1, url, name, true);
    }

    public static void asVod(Activity act, String url, String name) {
        saveAndLoad(act, 0, url, name, false);
    }

    private static void saveAndLoad(Activity act, int type, String url, String name, boolean live) {
        new Thread(() -> {
            try {
                com.fongmi.android.tv.db.AppDatabase db = com.fongmi.android.tv.db.AppDatabase.get();
                com.fongmi.android.tv.bean.Config exists = db.getConfigDao().find(url, type);
                com.fongmi.android.tv.bean.Config config = exists != null
                        ? exists.name(name).update()
                        : Config.create(type).url(url).name(name).update();
                Callback cb = new Callback() {
                    @Override
                    public void error(String msg) {
                        runUi(act, () -> Notify.show(msg == null ? "加源失败" : msg));
                    }
                };
                if (live) LiveConfig.load(config, cb);
                else VodConfig.load(config, cb);
                runUi(act, () -> Notify.show("已接好，去" + (live ? "直播" : "点播") + "里用吧"));
            } catch (Exception e) {
                runUi(act, () -> Notify.show("加源失败，换个方式试试"));
            }
        }).start();
    }

    public static void openOrCopy(Activity act, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            try {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("lab", url));
                Notify.show("已复制地址");
            } catch (Exception ignored) {
            }
        }
    }

    private static void runUi(Activity act, Runnable r) {
        try {
            act.runOnUiThread(r);
        } catch (Exception ignored) {
        }
    }
}
