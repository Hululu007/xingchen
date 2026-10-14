package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import com.fongmi.android.tv.App;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import timber.log.Timber;

/**
 * 照 8.83 com.example.hikerview.ui.rules.service.FuckKt 转写（Kotlin 顶层函数 → 静态类）。
 * 语义：用户维护的 fuck-list.txt（根目录/cache/ 下）逐行是规则标题关键词；当前页面标题（Activity 标题，
 * 原版即规则标题）命中任一行时，x5Rule/webRule/lazy/video:// 四处不走内置嗅探解析、改用浏览器打开真实页。
 * 文件中以 ":" 开头的行是屏蔽域名，汇入 blockDoms（原版 ArticleListRuleEditActivity.blockDoms，规则编辑页未移植，
 * 此处保留同一静态表供宿主阶段接入）。
 * 加载时机照原版：SettingConfig 初始化时 loadList()——星辰由宿主在启动接线时调用 loadList()。
 * goWeb 的目标页打开走 WebUtil.goWeb(context, url, false)（原版内置浏览器页），星辰默认实现为系统浏览器，
 * 宿主可经 goWebHook 换成内置 WebView 页。
 */
public class HkFuck {

    private static final List<String> list = new ArrayList<>();
    public static final List<String> blockDoms = new ArrayList<>();

    /** WebUtil.goWeb 的宿主接缝：(context, url) → 是否已打开。 */
    public interface GoWebHook {
        boolean goWeb(Context context, String url);
    }

    public static volatile GoWebHook goWebHook = (context, url) -> {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Timber.e(e);
            return false;
        }
    };

    public static boolean inList(Activity activity) {
        if (activity == null) return false;
        CharSequence title = activity.getTitle();
        return inList(title == null ? null : title.toString());
    }

    public static boolean inList(String title) {
        if (title == null || title.length() == 0) return false;
        for (String s : list) {
            if (title.contains(s)) return true;
        }
        return false;
    }

    public static boolean goWeb(Context context, String pageUrl, String url) {
        if (url == null || url.length() == 0) return false;
        String uu = HkStringUtil.getRealUrl(pageUrl, url);
        if (uu == null || uu.length() == 0) return false;
        if (!uu.toLowerCase(Locale.getDefault()).startsWith("http")) return false;
        GoWebHook hook = goWebHook;
        if (hook != null) hook.goWeb(context, uu);
        return true;
    }

    /** 照原版 loadList：IO 线程读根目录/cache/fuck-list.txt，存在则 loadFromText。 */
    public static void loadList() {
        new Thread(() -> {
            try {
                File file = fuckListFile(App.get());
                if (file != null && file.exists()) {
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    loadFromText(text);
                }
            } catch (Exception e) {
                Timber.e(e);
            }
        }, "hk-fuck-load").start();
    }

    /** 照原版 loadFromText：清空后逐行入表（非空且不以 ":" 开头）；":" 开头行剥首字入 blockDoms（去重）。 */
    public static void loadFromText(String text) {
        list.clear();
        if (text == null) return;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.length() > 0 && !line.startsWith(":")) list.add(line);
        }
        for (String line : lines) {
            if (line.length() > 0 && line.startsWith(":")) {
                String dom = line.substring(1);
                if (!blockDoms.contains(dom)) blockDoms.add(dom);
            }
        }
    }

    /** 照原版 deleteFile：文件存在则删除，并清空 list。 */
    public static void deleteFile(Context context) {
        try {
            File file = fuckListFile(context);
            if (file != null && file.exists()) file.delete();
        } catch (Exception e) {
            Timber.e(e);
        }
        list.clear();
    }

    /** UriUtils.getRootDir = getExternalFilesDir(Documents)（不存在则建），其下 cache/fuck-list.txt。 */
    private static File fuckListFile(Context context) {
        if (context == null) return null;
        File root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (root == null) return null;
        if (!root.exists()) root.mkdirs();
        return new File(new File(root, "cache"), "fuck-list.txt");
    }
}
