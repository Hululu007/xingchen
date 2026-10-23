package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkCommonParser;
import com.fongmi.android.tv.api.hk.HkJsRuntime;
import com.fongmi.android.tv.api.hk.HkHttpParser;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkLoading;
import com.fongmi.android.tv.api.hk.HkPageParser;
import com.fongmi.android.tv.api.hk.HkPlayData;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.read.ReadingGate;
import com.fongmi.android.tv.read.ReadingRouter;
import com.fongmi.android.tv.read.ReadingSession;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.download.DownloadManager;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import android.util.Log;

/**
 * 照 8.83 FilmListActivity 的宿主职责转写（星辰等价）：每开一级子列表/详情就新开一个本活动实例
 * （standard 启动、系统返回栈），规则经 putTempRule 等价通道传递（Intent "data" 小数据直传、
 * 大于 0x32000 字节走静态槽 + 缓存文件兜底，数据全丢时弹「页面数据被系统回收」退出）。
 * 本活动同时实现 Fragment 的 HostListener 与 PageOpener，并负责全套静态接线的一次性初始化：
 * 引擎桥（单例）、DetailBridge、两处 activityProvider、HkFuck.loadList()。
 * 原版宿主的收藏星标/页面背景图/双击回顶等纯装饰机能未搬（不影响列表机能，宿主视觉保持星辰海阔页浅色风格）。
 */
public class HkListActivity extends AppCompatActivity
        implements HkArticleFragment.HostListener, HkArticleFragment.PageOpener,
        HkArticleFragment.PageUrlOpener,
        HkDetailBridge.FileEditCallbackHost {
    private static final String TAG = "HkListActivity";

    private static final int TEMP_RULE_LIMIT = 0x32000;
    private static final String PREF_LAST_CLICK = "hk_last_click";

    /** 原版 Application.tempRuleData 等价：大数据规则 JSON 的进程内静态槽。 */
    private static volatile String tempRuleData;
    /** 静态槽/缓存文件里那份规则的指纹（putTempRule 写入、onCreate 回退消费时核对）：
     * 没有指纹或对不上就绝不消费——此前无条件回退会把上一个大规则的 body
     * 静默安到本次开页头上（标题对、内容全错的串页，1.3.11 花都影视实证）。 */
    private static volatile String tempRuleKey;
    /** 原版 FilmListActivity.tempParentData 等价：#autoPage# 子页的父页续页数据静态槽（消费后清空）。 */
    private static volatile HkArticleFragment.AutoPageData tempParentData;
    private static final AtomicBoolean STATICS_READY = new AtomicBoolean(false);
    private static volatile WeakReference<Activity> currentRef;

    private HkArticleFragment fragment;
    private HkRule rule;
    private String pageTitle;
    /** 顶部小程序切换 tabs 的规则集（自旧内容页迁入，行为不变）。 */
    private final java.util.List<HkRule> miniappRules = new java.util.ArrayList<>();
    private boolean childHasWebView;
    /** editFile:// 支登记的保存后回调 JS（原版宿主同名字段族），编辑页以 0x4e16 回流后逐个求值。 */
    private final java.util.List<String> fileEditCallbackJs = new java.util.ArrayList<>();

    /** 规则 showLoading/hideLoading 弹窗（8.83 LoadingEvent 等价）：此前只有旧内页
     * HkPageActivity 注册过监听，而打开规则早已改走本页，监听从未注册、弹窗从未出现
     * （18AV「正在获取域名」在星辰里看不到就是这个原因）。 */
    private android.app.Dialog hkLoadingDialog;
    private TextView hkLoadingText;
    private final Handler hkLoadingWatchdog = new Handler(Looper.getMainLooper());
    private final Runnable hkLoadingWatchdogRun = this::hideHkLoading;
    private final HkLoading.Listener hkLoadingListener = new HkLoading.Listener() {
        @Override
        public void onShowLoading(String ruleTitle, String text) {
            if (isFinishing()) return;
            if (rule == null || ruleTitle == null || !ruleTitle.equals(rule.getTitle())) return;
            showHkLoading(text);
        }

        @Override
        public void onHideLoading(String ruleTitle) {
            if (rule == null || ruleTitle == null || !ruleTitle.equals(rule.getTitle())) return;
            hideHkLoading();
        }
    };

    /** 与旧内页同款：深色小盒（#212121/圆角16）+ 菊花 + 文案，60 秒看门狗兜底关。 */
    private void showHkLoading(String text) {
        try {
            if (hkLoadingDialog == null) {
                LinearLayout root = new LinearLayout(this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                int pad = dp(22);
                box.setPadding(pad, pad, pad, pad);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setColor(0xFF212121);
                bg.setCornerRadius(dp(16));
                box.setBackground(bg);

                ProgressBar bar = new ProgressBar(this);
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(28), dp(28));
                barLp.gravity = Gravity.CENTER_HORIZONTAL;
                box.addView(bar, barLp);

                hkLoadingText = new TextView(this);
                hkLoadingText.setTextColor(0xFFEEEEEE);
                hkLoadingText.setTextSize(14);
                hkLoadingText.setGravity(Gravity.CENTER);
                hkLoadingText.setMaxLines(1);
                hkLoadingText.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tvLp.gravity = Gravity.CENTER_HORIZONTAL;
                tvLp.topMargin = dp(8);
                box.addView(hkLoadingText, tvLp);

                root.addView(box, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                hkLoadingDialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
                hkLoadingDialog.setContentView(root);
                hkLoadingDialog.setCancelable(true);
                hkLoadingDialog.setCanceledOnTouchOutside(true);
                if (hkLoadingDialog.getWindow() != null) {
                    hkLoadingDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    hkLoadingDialog.getWindow().setDimAmount(0.5f);
                }
            }
            if (TextUtils.isEmpty(text)) {
                hkLoadingText.setVisibility(View.GONE);
            } else {
                hkLoadingText.setVisibility(View.VISIBLE);
                hkLoadingText.setText(text);
            }
            if (!hkLoadingDialog.isShowing() && !isFinishing()) hkLoadingDialog.show();
            hkLoadingWatchdog.removeCallbacks(hkLoadingWatchdogRun);
            hkLoadingWatchdog.postDelayed(hkLoadingWatchdogRun, 60000);
        } catch (Throwable ignored) {
        }
    }

    private void hideHkLoading() {
        try {
            hkLoadingWatchdog.removeCallbacks(hkLoadingWatchdogRun);
            if (hkLoadingDialog != null && hkLoadingDialog.isShowing()) hkLoadingDialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void addFileEditCallbackJs(String js) {
        if (js != null) fileEditCallbackJs.add(js);
    }

    /** 当前列表页活动（供两处 activityProvider 与 HkFuck 等无上下文接缝取用）。 */
    public static Activity currentActivity() {
        WeakReference<Activity> ref = currentRef;
        Activity activity = ref == null ? null : ref.get();
        return activity != null && !activity.isFinishing() ? activity : null;
    }

    /**
     * 照原版 FilmListActivity.putTempRule(Intent, String)：空串直接放 "data" extra；
     * 字节长大于 0x32000 时放静态槽并写缓存文件，Intent 不带数据；否则放 "data" extra。
     */
    public static void putTempRule(Intent intent, String ruleJson) {
        if (TextUtils.isEmpty(ruleJson)) {
            intent.putExtra("data", ruleJson);
            return;
        }
        // 每次开页都给 Intent 带上规则指纹：走静态槽/缓存文件的大规则靠它认领，
        // 别的开页（没经 putTempRule 的）没有指纹，永远认领不到残留（见 tempRuleKey）。
        String key = md5Hex(ruleJson);
        intent.putExtra("ruleKey", key);
        int length = ruleJson.getBytes(StandardCharsets.UTF_8).length;
        Log.d(TAG, String.format("变量传递, length: %s", length));
        if (length > TEMP_RULE_LIMIT) {
            tempRuleData = ruleJson;
            tempRuleKey = key;
            writeTempRuleCache(key, ruleJson);
        } else {
            intent.putExtra("data", ruleJson);
        }
    }

    private static String md5Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /** 规则第一页入口（主页型宿主，listHost=false）：供规则列表/搜索等开页方调用。 */
    public static void startRule(Context context, HkRule rule, String title) {
        Intent intent = new Intent(context, HkListActivity.class);
        putTempRule(intent, new Gson().toJson(rule));
        intent.putExtra("title", !TextUtils.isEmpty(title) ? title : rule.getTitle());
        intent.putExtra("listHost", false);
        context.startActivity(intent);
    }

    /** 全套静态接线一次性初始化（引擎单例桥 + DetailBridge + 活动供给 + Fuck 名单）。 */
    /** 桥与静态接线（搜索宿主同包共用，故包内可见）。 */
    static void ensureStatics() {
        if (!STATICS_READY.compareAndSet(false, true)) return;
        Context app = App.get();
        HkEngineBridge engineBridge = new HkEngineBridge(app);
        HkArticleListModel.setEngineBridge(engineBridge);
        HkCommonParser.setJsEval((rule, js, input) -> {
            HkRule effective = rule != null ? rule : HkEngineManager.currentRule();
            return effective != null ? engineBridge.evalWithRule(effective, js, input) : input;
        });
        HkArticleFragment.setDetailBridge(new HkDetailBridge());
        HkEngineBridge.activityProvider = HkListActivity::currentActivity;
        HkDetailBridge.activityProvider = HkListActivity::currentActivity;
        // DetailBridge 宿主钩子：文件编辑器（editFile:// 的 0x4e16 回流在本类闭环）、
        // 播放交接（F：PlayerChooser → VideoActivity.startHkPlay）、下载对话框/快速下载（G）。
        HkDetailBridge.hooks = new HkDetailBridge.HostHooks() {
            @Override
            public void openFileEditor(Activity activity, String path) {
                if (activity instanceof HkListActivity) {
                    ((HkListActivity) activity).openFileEditor(path);
                }
            }

            @Override
            public void startPlayerSingle(Activity activity, String title, String url) {
                if (activity == null || TextUtils.isEmpty(url)) return;
                if (url.startsWith("x5Play://")) {
                    openX5PlayPage(activity, title, url.substring("x5Play://".length()));
                    return;
                }
                JSONArray arr = new JSONArray();
                try {
                    JSONObject o = new JSONObject();
                    o.put("name", title == null ? "" : title);
                    o.put("url", url);
                    o.put("pic", "");
                    arr.put(o);
                } catch (Exception ignored) {
                }
                VideoActivity.startHkPlay(activity, hostRuleTitle(activity), "默认",
                        url, title, title, "", arr.toString(), 0);
            }

            @Override
            public void startPlayerChapters(Activity activity, List<HkArticleFragment.HkChapter> chapters,
                                            String pageUrl, String mTitle, Bundle extraData) {
                if (activity == null || chapters == null || chapters.isEmpty()) return;
                String film = extraData == null ? null : extraData.getString("film");
                String ruleTitle = "";
                String ruleJson = extraData == null ? null : extraData.getString("rule");
                if (!TextUtils.isEmpty(ruleJson)) {
                    try {
                        HkRule r = new Gson().fromJson(ruleJson, HkRule.class);
                        if (r != null && r.getTitle() != null) ruleTitle = r.getTitle();
                    } catch (Exception ignored) {
                    }
                }
                if (TextUtils.isEmpty(ruleTitle)) {
                    ruleTitle = !TextUtils.isEmpty(film) ? film : hostRuleTitle(activity);
                }
                // 阅读模块阶段二：小说规则的章节表改道小说阅读器，不进 VideoActivity。
                // 视频规则原样走下（type 不是"小说"）。
                if (ReadingGate.isHkNovelRule(ruleTitle)) {
                    try {
                        ReadingSession ns = new ReadingSession();
                        ns.kind = ReadingSession.Kind.NOVEL;
                        ns.sourceName = ruleTitle;
                        ns.ruleTitle = ruleTitle;
                        ns.bookTitle = !TextUtils.isEmpty(mTitle) && !mTitle.equals(ruleTitle) ? mTitle : "";
                        for (HkArticleFragment.HkChapter ch : chapters) {
                            ReadingSession.Chapter rc = new ReadingSession.Chapter(
                                    !TextUtils.isEmpty(ch.title) ? ch.title
                                            : (ch.memoryTitle == null ? "" : ch.memoryTitle),
                                    playUrlForChapter(ch));
                            rc.pic = ch.pic == null ? "" : ch.pic;
                            ns.chapters.add(rc);
                        }
                        int nidx = 0;
                        for (int i = 0; i < chapters.size(); i++) {
                            if (chapters.get(i).use) {
                                nidx = i;
                                break;
                            }
                        }
                        ns.chapterIndex = nidx;
                        ReadingSession.Chapter cur = ns.currentChapter();
                        ns.chapterUrl = cur.url;
                        if (TextUtils.isEmpty(ns.bookTitle)) ns.bookTitle = cur.name;
                        // 有阅读进度则从上次章节/位置继续
                        ReadingStore.Record nprog = ReadingStore.find("NOVEL", ruleTitle, ns.bookTitle);
                        if (nprog != null && nprog.chapterIndex >= 0 && nprog.chapterIndex < ns.chapters.size()) {
                            ns.chapterIndex = nprog.chapterIndex;
                            ns.chapterUrl = ns.currentChapter().url;
                        }
                        ReadingRouter.openReading(activity, ns);
                    } catch (Throwable t) {
                        android.util.Log.e(TAG, "novel divert failed", t);
                    }
                    return;
                }
                int selIdx = 0;
                for (int i = 0; i < chapters.size(); i++) {
                    if (chapters.get(i).use) {
                        selIdx = i;
                        break;
                    }
                }
                HkArticleFragment.HkChapter sel = chapters.get(selIdx);
                String selUrl = playUrlForChapter(sel);
                if (selUrl.startsWith("x5Play://")) {
                    openX5PlayPage(activity, sel.memoryTitle, selUrl.substring("x5Play://".length()));
                    return;
                }
                String selName = !TextUtils.isEmpty(sel.memoryTitle) ? sel.memoryTitle
                        : (sel.title == null ? "" : sel.title);
                // 播放器标题用页面标题：子列表页的活动标题就是被点视频名（dealRule 经 req.title 传入），
                // 原版 PlayerChooser 用的正是这个 MTitle；一级列表页活动标题与规则名相同、不可用，回退选中集标题
                String name = !TextUtils.isEmpty(mTitle) && !mTitle.equals(ruleTitle) ? mTitle : selName;
                // 多线路展开：章节地址是播放数据（urls/names/headers）时按 names 展开成线路，
                // 与 8.83 播放器内按集解析、线路序号对所有集统一的语义一致；普通集走原扁平载荷不变
                java.util.List<HkPlayData> playDatas = new java.util.ArrayList<>();
                java.util.List<String> rawUrls = new java.util.ArrayList<>();
                for (HkArticleFragment.HkChapter ch : chapters) {
                    String raw = playUrlForChapter(ch);
                    rawUrls.add(raw);
                    playDatas.add(playDataOf(raw));
                }
                java.util.List<String> lineNames = lineNamesOf(playDatas, selIdx);
                String payload;
                String firstLineUrl;
                if (lineNames.size() <= 1) {
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < chapters.size(); i++) {
                        try {
                            JSONObject o = new JSONObject();
                            HkArticleFragment.HkChapter ch = chapters.get(i);
                            o.put("name", !TextUtils.isEmpty(ch.title) ? ch.title
                                    : (ch.memoryTitle == null ? "" : ch.memoryTitle));
                            o.put("url", rawUrls.get(i));
                            o.put("pic", ch.pic == null ? "" : ch.pic);
                            arr.put(o);
                        } catch (Exception ignored) {
                        }
                    }
                    payload = arr.toString();
                    firstLineUrl = selUrl;
                } else {
                    JSONObject flagsRoot = buildFlagsPayload(chapters, rawUrls, playDatas, lineNames);
                    payload = flagsRoot == null ? "[]" : flagsRoot.toString();
                    String fromPayload = "";
                    if (flagsRoot != null) {
                        try {
                            fromPayload = flagsRoot.getJSONArray("flags").getJSONObject(0)
                                    .getJSONArray("episodes").getJSONObject(selIdx).optString("url", "");
                        } catch (Exception ignored) {
                        }
                    }
                    firstLineUrl = !TextUtils.isEmpty(fromPayload) ? fromPayload
                            : expandedUrl(playDatas.get(selIdx), rawUrls.get(selIdx), 0);
                }
                VideoActivity.startHkPlay(activity, ruleTitle, lineNames.get(0), firstLineUrl, selName, name,
                        sel.pic == null ? "" : sel.pic, payload, selIdx);
            }

            @Override
            public void showDownloadDialog(Activity activity, String title, String url, String film, java.util.Map<String, String> headers) {
                if (activity == null || TextUtils.isEmpty(url)) return;
                String name = !TextUtils.isEmpty(title) ? title : url;
                new HkDialogs(activity)
                        .setTitle("下载资源")
                        .setMessage(name + "\n" + url)
                        .setPositiveButton("下载", (d, w) -> {
                            // A11：照 C-01 移动端 VideoActivity onDownload 的 IAE 口径，撞 Emby 特征 URL 时只提示、不闪退。
                            try {
                                DownloadManager.get().add(url, name, headers);
                                HkRouteBlocker.toastShortBottom(activity, "已加入下载：" + name);
                            } catch (IllegalArgumentException e) {
                                HkRouteBlocker.toastShortBottom(activity, e.getMessage());
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void startFastDownload(Context context, String title, String url, String film) {
                if (TextUtils.isEmpty(url)) return;
                String name = !TextUtils.isEmpty(title) ? title : url;
                // A11：同上，照 C-01 口径包 IAE，撞 Emby 特征 URL 时只提示、不闪退、不报“已加入下载”。
                try {
                    DownloadManager.get().add(url, name);
                } catch (IllegalArgumentException e) {
                    HkRouteBlocker.toastShortBottom(context, e.getMessage());
                    return;
                }
                if (context != null) {
                    HkRouteBlocker.toastShortBottom(context, "已加入下载：" + name);
                }
            }

            /**
             * 阅读模块阶段二：文章页/搜索页的 pics:// 入口（此前是空实现，点了没反应）。
             * PictureListRequest（pics/chapters/nowPage/fromLastPage）直接转阅读会话，
             * 进漫画阅读器。章节表、当前页、从末页起读全部保留。
             */
            @Override
            public void openPictureList(Activity activity,
                                        HkArticleFragment.PictureListRequest request) {
                if (activity == null || request == null) return;
                try {
                    ReadingSession s = new ReadingSession();
                    s.kind = ReadingSession.Kind.COMIC;
                    String ruleTitle = hostRuleTitle(activity);
                    if (!TextUtils.isEmpty(request.ruleJson)) {
                        try {
                            HkRule r = new Gson().fromJson(request.ruleJson, HkRule.class);
                            if (r != null && !TextUtils.isEmpty(r.getTitle())) ruleTitle = r.getTitle();
                        } catch (Exception ignored) {
                        }
                    }
                    s.sourceName = ruleTitle;
                    s.ruleTitle = ruleTitle;
                    s.bookTitle = !TextUtils.isEmpty(request.mTitle) ? request.mTitle
                            : (!TextUtils.isEmpty(request.title) ? request.title : "");
                    if (request.pics != null) s.images = new ArrayList<>(request.pics);
                    s.referer = request.url == null ? "" : request.url;
                    // request.url 就是本章真实页地址：另存 pageUrl 供阅读器组图（与 referer 分开）
                    s.pageUrl = request.url == null ? "" : request.url;
                    if (request.chapters != null) {
                        for (HkArticleFragment.HkChapter ch : request.chapters) {
                            ReadingSession.Chapter c = new ReadingSession.Chapter(
                                    !TextUtils.isEmpty(ch.title) ? ch.title
                                            : (ch.memoryTitle == null ? "" : ch.memoryTitle),
                                    ch.url == null ? "" : ch.url);
                            c.pic = ch.pic == null ? "" : ch.pic;
                            s.chapters.add(c);
                        }
                    }
                    // 当前章：按 url 匹配章节表，匹配不上则单章模式
                    int idx = 0;
                    if (!TextUtils.isEmpty(request.url) && !s.chapters.isEmpty()) {
                        for (int i = 0; i < s.chapters.size(); i++) {
                            if (request.url.equals(s.chapters.get(i).url)) {
                                idx = i;
                                break;
                            }
                        }
                    }
                    s.chapterIndex = idx;
                    s.chapterUrl = request.url == null ? "" : request.url;
                    // 起始页：nowPage / 从末页起读（原版 PictureListActivity 入参语义）
                    int startPage = Math.max(0, request.nowPage);
                    if (request.fromLastPage && !s.images.isEmpty()) {
                        startPage = s.images.size() - 1;
                    }
                    ReadingStore.Record prog = ReadingStore.find("COMIC", ruleTitle, s.bookTitle);
                    if (prog != null && prog.position > 0) startPage = prog.position;
                    ReadingRouter.openReadingWithStartPage(activity, s, startPage);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "openPictureList failed", t);
                }
            }

            // ---- 高-5 补绑（2026-10-23 全树重查）：以下 14 个钩子此前只有接口空默认，
            // 海阔里点磁力/种子/ed2k/看图/分享/口令导入全无反应。逐个接到星辰既有能力上，
            // 实现都在本类静态助手里，不另造平行实现。 ----

            @Override
            public boolean checkText(Activity activity, String text) {
                return importRuleText(activity, text);
            }

            @Override
            public boolean checkAutoText(Context context, String text) {
                Activity activity = context instanceof Activity ? (Activity) context : currentActivity();
                return importRuleText(activity, text);
            }

            @Override
            public void shareFile(Context context, String absolutePath) {
                shareLocalFile(context, absolutePath);
            }

            @Override
            public void checkDownloadedFile(File file) {
                checkDownloadedRuleFile(file);
            }

            @Override
            public void showImageViewer(java.util.List<String> urls, int selectPos,
                                        java.util.Map<Integer, Integer> posMap,
                                        android.widget.ImageView srcView) {
                // posMap 是原版图集页码映射，图集本身走 openPictureList 的阅读器，普通看图不消费；
                // srcView 只用来取宿主页面（原版的小图→大图共享元素动画不搬）。
                Activity activity = srcView != null && srcView.getContext() instanceof Activity
                        ? (Activity) srcView.getContext() : currentActivity();
                if (activity == null || urls == null || urls.isEmpty()) return;
                showImageDialog(activity, urls, selectPos);
            }

            @Override
            public void showSingleImage(String url, android.widget.ImageView srcView) {
                Activity activity = srcView != null && srcView.getContext() instanceof Activity
                        ? (Activity) srcView.getContext() : currentActivity();
                if (activity == null || TextUtils.isEmpty(url)) return;
                java.util.List<String> urls = new ArrayList<>();
                urls.add(url);
                showImageDialog(activity, urls, 0);
            }

            @Override
            public void startDownloadMagnet(Context context, String url) {
                playThunderLink(context, url, null);
            }

            @Override
            public void startParseTorrent(Context context, String url) {
                playThunderLink(context, url, null);
            }

            @Override
            public void startParseFTPOrEd2k(Context context, String url,
                                            java.util.function.Consumer<String> consumer) {
                // 原版 consumer 是把迅雷解析出的本地地址回点进播放器；星辰的解析在播放器内
                // 由 Source.fetch 的 Thunder.Parser 完成，没有播放链外的独立解析面，
                // 故直达播放器、不回调用 consumer（回点只会把同一条 ed2k 再送回本分支）。
                playThunderLink(context, url, null);
            }

            @Override
            public void startDownloadMagnetList(Context context, String url,
                                                java.util.List<HkArticleFragment.HkChapter> chapters) {
                playThunderLink(context, url, chapters);
            }

            @Override
            public void startParseTorrentList(Context context, String url,
                                              java.util.List<HkArticleFragment.HkChapter> chapters) {
                playThunderLink(context, url, chapters);
            }

            @Override
            public void startParseFTPOrEd2kList(Context context, String url,
                                                java.util.List<HkArticleFragment.HkChapter> chapters) {
                playThunderLink(context, url, chapters);
            }

            @Override
            public void shareRule(HkRule rule, boolean moreWays) {
                shareHkRule(rule, moreWays);
            }

            @Override
            public void showPosDetail(Activity activity, HkItem item, HkRule rule, int position) {
                showItemDetail(activity, item, rule);
            }
        };
        HkFuck.loadList();
        // hiker:// 目标页路由钩子（C1）：搜索 + 目标页全量装配，搜索页/列表页/小程序页共用
        HkRouteBlocker.wireAllHooks();
    }

    /** 本页规则标题（播放交接取 siteKey 用）。 */
    public String currentRuleTitle() {
        return rule != null && rule.getTitle() != null ? rule.getTitle() : "";
    }

    /** 播放交接时按宿主活动取规则标题：列表页取本页规则，搜索页取当前发动机。 */
    static String hostRuleTitle(Activity activity) {
        if (activity instanceof HkListActivity) {
            return ((HkListActivity) activity).currentRuleTitle();
        }
        if (activity instanceof HkSearchActivity) {
            return ((HkSearchActivity) activity).currentEngineTitle();
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 高-5 补绑的宿主能力（2026-10-23）：全部复用既有入口——导入走 HkRuleManager 中央分发、
    // 迅雷链走 VideoActivity.startHkPlay（播放器内 Source.fetch 的 Thunder.Parser 解析）、
    // 分享走 HkRuleManager.buildShareCommand + 系统分享、看图走 HkDialogs + ImgUtil。
    // ------------------------------------------------------------------

    /**
     * 口令/规则文本导入（原版 AutoImportHelper.checkText/checkAutoText 的星辰落点）：
     * 识别与导入全走 HkRuleManager 中央分发（云口令/￥home_rule￥包装/base64 壳/裸 JSON/JS 规则），
     * 真导入了才返回 true。云口令要联网拉取：命中即认领、后台导入（原版 RuleImporterManager.parse
     * 命中即 true 同款），拉取失败由后台提示交代，不假报成功。
     */
    static boolean importRuleText(Activity activity, String text) {
        if (activity == null || TextUtils.isEmpty(text)) return false;
        String t = text.trim();
        if (HkRuleManager.isCloudCode(t)) {
            new Thread(() -> {
                try {
                    HkRule rule = HkRuleManager.get().importByCloudCode(t);
                    App.post(() -> onRuleImported(activity, rule));
                } catch (Exception e) {
                    App.post(() -> HkRouteBlocker.toastShortBottom(activity,
                            "口令导入失败：" + e.getMessage()));
                }
            }).start();
            return true;
        }
        try {
            HkRule imported;
            if (HkRuleManager.isJsRuleText(t)) {
                imported = HkRuleManager.get().importJsRule(t, "口令导入");
            } else {
                java.util.List<HkRule> list = HkRuleManager.get().importJsonList(t);
                if (list == null || list.isEmpty()) return false;
                imported = list.get(0);
            }
            if (imported == null) return false;
            onRuleImported(activity, imported);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 导入成功交代：像海阔原版一样问「是否打开」，打开走本页的规则入口。 */
    static void onRuleImported(Activity activity, HkRule rule) {
        if (activity == null || activity.isFinishing() || rule == null) return;
        String title = TextUtils.isEmpty(rule.getTitle()) ? "小程序" : rule.getTitle();
        try {
            new HkDialogs(activity)
                    .setTitle("导入成功")
                    .setMessage("已导入「" + title + "」，是否打开？")
                    .setPositiveButton("打开", (d, w) -> startRule(activity, rule, title))
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    /**
     * openFile:// 的下载文件检查（原版 DownloadRecordsFragment.checkDownload 的口径）：
     * 小于 4MB 的 .txt/.json/.hiker，内容像规则（find_rule 特征/分享口令/云口令/JS 规则）时
     * 走导入并问是否打开；不是规则就静默放行——桥随后照原版用系统应用打开该文件。
     */
    static void checkDownloadedRuleFile(File file) {
        if (file == null || !file.exists()) return;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (!(name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".hiker"))) return;
        if (file.length() >= 4L * 1024 * 1024) return;
        new Thread(() -> {
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                }
                String text = bos.toString(StandardCharsets.UTF_8.name());
                if (TextUtils.isEmpty(text)) return;
                boolean ruleLike = text.contains("\"find_rule\"")
                        || HkRuleManager.isShareCommandText(text)
                        || HkRuleManager.isCloudCode(text)
                        || HkRuleManager.isJsRuleText(text);
                if (!ruleLike) return;
                App.post(() -> {
                    Activity activity = currentActivity();
                    if (activity != null) importRuleText(activity, text);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    /**
     * 迅雷链播放（磁力/种子/ed2k/ftp，单条与带选集列表共用）：星辰没有独立的迅雷面板，
     * 既有能力就是播放器——集地址进 VideoActivity 后由 Source.fetch 的 Thunder.Parser
     * 解析出文件列表（多文件在播放器内成选集），与旧宿主 HkPageActivity.playP2pInApp 同路，
     * 交接面与 startPlayerSingle/startPlayerChapters 相同，不碰迅雷模块内部。
     */
    static void playThunderLink(Context context, String url,
                                java.util.List<HkArticleFragment.HkChapter> chapters) {
        if (TextUtils.isEmpty(url)) {
            HkRouteBlocker.toastShortBottom(context, "链接不能为空");
            return;
        }
        Activity activity = context instanceof Activity ? (Activity) context : currentActivity();
        if (activity == null) {
            HkRouteBlocker.toastShortBottom(context, "无法打开播放器");
            return;
        }
        String ruleTitle = hostRuleTitle(activity);
        try {
            if (chapters == null || chapters.isEmpty()) {
                String name = thunderLinkName(url);
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("url", url);
                o.put("pic", "");
                arr.put(o);
                VideoActivity.startHkPlay(activity, ruleTitle, "默认", url, name, name, "",
                        arr.toString(), 0);
                return;
            }
            JSONArray arr = new JSONArray();
            int selIdx = -1;
            for (int i = 0; i < chapters.size(); i++) {
                HkArticleFragment.HkChapter ch = chapters.get(i);
                String raw = playUrlForChapter(ch);
                if (selIdx < 0 && (url.equals(raw) || url.equals(ch.url))) selIdx = i;
                JSONObject o = new JSONObject();
                o.put("name", !TextUtils.isEmpty(ch.title) ? ch.title
                        : (ch.memoryTitle == null ? "" : ch.memoryTitle));
                o.put("url", raw);
                o.put("pic", ch.pic == null ? "" : ch.pic);
                arr.put(o);
            }
            if (selIdx < 0) {
                selIdx = 0;
                for (int i = 0; i < chapters.size(); i++) {
                    if (chapters.get(i).use) {
                        selIdx = i;
                        break;
                    }
                }
            }
            HkArticleFragment.HkChapter sel = chapters.get(selIdx);
            String selName = !TextUtils.isEmpty(sel.title) ? sel.title
                    : (sel.memoryTitle == null ? "" : sel.memoryTitle);
            VideoActivity.startHkPlay(activity, ruleTitle, "默认", playUrlForChapter(sel),
                    selName, selName, sel.pic == null ? "" : sel.pic, arr.toString(), selIdx);
        } catch (Exception e) {
            android.util.Log.e(TAG, "hk thunder play failed", e);
            HkRouteBlocker.toastShortBottom(activity, "无法播放此链接");
        }
    }

    /** 迅雷链链接的显示名：磁力取 dn=、ed2k 取文件名段、其余取地址文件名，都没有回「播放」。 */
    static String thunderLinkName(String url) {
        try {
            String u = url.split(";")[0];
            if (u.startsWith("magnet:")) {
                String dn = android.net.Uri.parse(u).getQueryParameter("dn");
                return TextUtils.isEmpty(dn) ? "磁力链接" : dn;
            }
            if (u.startsWith("ed2k://")) {
                String[] segs = u.split("\\|");
                if (segs.length > 2 && !TextUtils.isEmpty(segs[2])) {
                    return java.net.URLDecoder.decode(segs[2], "UTF-8");
                }
                return "ed2k 链接";
            }
            String path = android.net.Uri.parse(u).getLastPathSegment();
            if (!TextUtils.isEmpty(path)) return path;
        } catch (Exception ignored) {
        }
        return "播放";
    }

    /** share:// 分享本地文件（原版 ShareUtil.findChooserToSend）：系统分享，Uri 走 FileUtil 的 FileProvider 既有入口。 */
    static void shareLocalFile(Context context, String absolutePath) {
        if (context == null || TextUtils.isEmpty(absolutePath)) return;
        File file = new File(absolutePath);
        if (!file.exists()) {
            HkRouteBlocker.toastShortBottom(context, "文件不存在" + absolutePath);
            return;
        }
        try {
            String mime = java.net.URLConnection.guessContentTypeFromName(file.getName());
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(TextUtils.isEmpty(mime) ? "*/*" : mime);
            intent.putExtra(Intent.EXTRA_STREAM,
                    com.fongmi.android.tv.utils.FileUtil.getShareUri(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "分享文件");
            if (!(context instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            HkRouteBlocker.toastShortBottom(context, "分享失败：" + e.getMessage());
        }
    }

    /**
     * 分享规则（原版 ShareRuleUtil.shareRule/shareRuleByMoreWay 的星辰落点）：
     * 普通分享＝明文口令（HkRuleManager.buildShareCommand 的 ￥home_rule￥ 格式，海阔官方可导入）
     * 走系统分享；更多分享＝卡片菜单三项（明文口令/完整编码/云剪贴板），全走既有件。
     * 原版菜单里的「提交到云仓库」依赖海阔官方服务端，星辰未实现（HkShareHelper 同注），不列。
     */
    static void shareHkRule(HkRule rule, boolean moreWays) {
        if (rule == null) return;
        Activity activity = currentActivity();
        String ruleJson = new Gson().toJson(rule);
        if (!moreWays) {
            shareRuleCommand(activity, ruleJson);
            return;
        }
        if (activity == null || activity.isFinishing()) return;
        String[] options = {"明文口令分享", "完整编码分享", "云剪贴板分享"};
        HkDialogs.cards(activity, "分享规则", options, "取消", (dialog, which) -> {
            if (which == 0) shareRuleCommand(activity, ruleJson);
            else if (which == 1) shareRuleEncoded(activity, ruleJson);
            else if (which == 2) shareRuleToCloud(activity, rule);
        });
    }

    static void shareRuleCommand(Activity activity, String ruleJson) {
        String command = HkRuleManager.buildShareCommand(ruleJson);
        if (TextUtils.isEmpty(command)) return;
        if (activity == null) {
            // 没有可用页面时退回既有剪贴板分享（与 HkShareHelper.shareAsCommand 同款口令）。
            com.fongmi.android.tv.api.hk.HkShareHelper.shareAsCommand(App.get(), ruleJson);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, command);
            activity.startActivity(Intent.createChooser(intent, "分享规则"));
        } catch (Exception e) {
            HkRouteBlocker.toastShortBottom(activity, "分享失败：" + e.getMessage());
        }
    }

    /** 完整编码分享（原版同款）：明文口令整体 Base64 后以 rule:// 形式复制到剪贴板。 */
    static void shareRuleEncoded(Activity activity, String ruleJson) {
        String command = HkRuleManager.buildShareCommand(ruleJson);
        if (activity == null || TextUtils.isEmpty(command)) return;
        try {
            String encoded = android.util.Base64.encodeToString(
                    command.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("",
                        "rule://" + encoded.replaceAll("\\s+", "")));
            }
            HkRouteBlocker.toastShortBottom(activity, "口令已复制到剪贴板");
        } catch (Exception e) {
            HkRouteBlocker.toastShortBottom(activity, "分享失败：" + e.getMessage());
        }
    }

    /** 云剪贴板分享：走 HkRuleManager.exportAsCloudCode（云2 textdb 通道，海阔官方 App 可导入）。 */
    static void shareRuleToCloud(Activity activity, HkRule rule) {
        if (activity == null || rule == null || TextUtils.isEmpty(rule.getTitle())) return;
        HkRouteBlocker.toastShortBottom(activity, "正在生成云口令…");
        String title = rule.getTitle();
        new Thread(() -> {
            try {
                String code = HkRuleManager.get().exportAsCloudCode(title);
                App.post(() -> {
                    if (activity.isFinishing()) return;
                    try {
                        new HkDialogs(activity)
                                .setTitle("云口令")
                                .setMessage(code)
                                .setPositiveButton("复制", (d, w) -> {
                                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("", code));
                                    }
                                    HkRouteBlocker.toastShortBottom(activity, "口令已复制到剪贴板");
                                })
                                .setNegativeButton("关闭", null)
                                .show();
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                App.post(() -> HkRouteBlocker.toastShortBottom(activity,
                        "生成云口令失败：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 看图（原版 BigImageViewPager 的星辰落点）：全树没有独立图片浏览器，
     * 照旧宿主 HkPageActivity 的 image 支同款——HkDialogs 承载 ImageView + ImgUtil 加载，
     * 多图加上/下一张切换。无 Referer 信息可用（钩子签名不带页面地址），个别防盗链图可能加载不出，
     * 按封面定案失败保持透明、不垫图。
     */
    static void showImageDialog(Activity activity, java.util.List<String> urls, int startPos) {
        if (activity == null || activity.isFinishing() || urls == null || urls.isEmpty()) return;
        final java.util.List<String> list = new ArrayList<>(urls);
        final int[] pos = {Math.max(0, Math.min(startPos, list.size() - 1))};
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        android.widget.ImageView iv = new android.widget.ImageView(activity);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        root.addView(iv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (screenH * 0.6f)));
        if (list.size() > 1) {
            LinearLayout bar = new LinearLayout(activity);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(0, (int) (8 * density), 0, 0);
            TextView prev = new TextView(activity);
            prev.setText("上一张");
            prev.setTextSize(15);
            prev.setPadding((int) (16 * density), (int) (8 * density), (int) (16 * density), (int) (8 * density));
            final TextView indicator = new TextView(activity);
            indicator.setTextSize(14);
            indicator.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
            TextView next = new TextView(activity);
            next.setText("下一张");
            next.setTextSize(15);
            next.setPadding((int) (16 * density), (int) (8 * density), (int) (16 * density), (int) (8 * density));
            prev.setOnClickListener(v -> {
                pos[0] = (pos[0] - 1 + list.size()) % list.size();
                loadImageAt(iv, indicator, list, pos[0]);
            });
            next.setOnClickListener(v -> {
                pos[0] = (pos[0] + 1) % list.size();
                loadImageAt(iv, indicator, list, pos[0]);
            });
            bar.addView(prev);
            bar.addView(indicator);
            bar.addView(next);
            root.addView(bar);
            loadImageAt(iv, indicator, list, pos[0]);
        } else {
            loadImageAt(iv, null, list, pos[0]);
        }
        try {
            new HkDialogs(activity)
                    .setView(root)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception ignored) {
        }
    }

    static void loadImageAt(android.widget.ImageView iv, TextView indicator,
                            java.util.List<String> urls, int pos) {
        com.fongmi.android.tv.utils.ImgUtil.load("", urls.get(pos), iv);
        if (indicator != null) indicator.setText((pos + 1) + " / " + urls.size());
    }

    /** 调试数据（原版 FileDetailPopup + ArticleListFragment.getDetailData 七行）：卡片弹窗列行，点行复制整行。 */
    static void showItemDetail(Activity activity, HkItem item, HkRule rule) {
        if (activity == null || activity.isFinishing() || item == null) return;
        String url = item.getUrl() == null ? "" : item.getUrl();
        String link = url.length() > 200 ? url.substring(0, 200) + "..." : url;
        final String[] data = {
                "页面：" + (rule == null ? "" : hkEmptyIfNull(rule.getUrl())),
                "标题：" + hkEmptyIfNull(item.getTitle()),
                "描述：" + hkEmptyIfNull(item.getDesc()),
                "类型：" + hkEmptyIfNull(item.getType()),
                "链接：" + link,
                "图片：" + hkEmptyIfNull(item.getPic()),
                "附加：" + hkEmptyIfNull(item.getExtra()),
        };
        HkDialogs.cards(activity, "调试数据", data, "关闭", (dialog, which) -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", data[which]));
            }
            HkRouteBlocker.toastShortBottom(activity, "已复制");
        });
    }

    static String hkEmptyIfNull(String s) {
        return s == null ? "" : s;
    }

    /**
     * 照 8.83 VideoPlayerActivity 的 codeAndHeader 语义：集地址带 @lazyRule= 时把 codeAndHeader 拼回页面段，
     * 使引擎 HkRouter.play 取到的 lazyPageUrl 自带抓取指令（;get / ;{headers}），headers 随交接不丢。
     */
    static String playUrlForChapter(HkArticleFragment.HkChapter chapter) {
        String url = chapter.url == null ? "" : chapter.url;
        String cah = chapter.codeAndHeader;
        if (cah != null && !cah.isEmpty() && url.contains("@lazyRule=") && !url.contains(cah)) {
            int lr = url.indexOf("@lazyRule=");
            url = url.substring(0, lr) + cah + url.substring(lr);
        }
        return url;
    }

    /** 地址是 8.83 播放数据（{urls,...} 的 JSON 串）时返回解析结果（走已移植的 HttpParser.getPlayData），否则返回空。 */
    static HkPlayData playDataOf(String url) {
        if (url == null) return null;
        String t = url.trim();
        if (!t.startsWith("{")) return null;
        HkPlayData pd = HkHttpParser.getPlayData(t);
        return pd != null && pd.getUrls() != null && !pd.getUrls().isEmpty() ? pd : null;
    }

    private static java.util.List<String> namesOf(HkPlayData pd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (pd == null || pd.getNames() == null) return out;
        java.util.Set<String> used = new java.util.HashSet<>();
        java.util.List<String> names = pd.getNames();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i) == null ? "" : names.get(i).trim();
            if (n.isEmpty()) n = "线路" + (i + 1);
            if (!used.add(n)) n = n + " " + (i + 1);
            used.add(n);
            out.add(n);
        }
        return out;
    }

    /** 线路名：选中集的 names 有多条就用它；否则找任一集多条的；全是单地址回单个「默认」。 */
    static java.util.List<String> lineNamesOf(java.util.List<HkPlayData> playDatas, int selIdx) {
        java.util.List<String> names = namesOf(playDatas.get(selIdx));
        if (names.size() > 1) return names;
        for (HkPlayData pd : playDatas) {
            java.util.List<String> ns = namesOf(pd);
            if (ns.size() > 1) return ns;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("默认");
        return out;
    }

    /** 某集在某条线路下的实际地址：取 urls[线路]（越界回 urls[0]），该线路的请求头/字幕/分离音频随址打包。 */
    static String expandedUrl(HkPlayData pd, String rawUrl, int line) {
        if (pd == null) return rawUrl;
        java.util.List<String> urls = pd.getUrls();
        if (urls == null || urls.isEmpty()) return rawUrl;
        String u = urls.get(line < urls.size() ? line : 0);
        u = u == null ? "" : u.trim();
        if (u.isEmpty()) return rawUrl;
        java.util.List<String> kvs = new java.util.ArrayList<>();
        java.util.List<Map<String, String>> headers = pd.getHeaders();
        if (headers != null && line < headers.size() && headers.get(line) != null) {
            for (Map.Entry<String, String> e : headers.get(line).entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (!k.isEmpty() && !v.isEmpty()) kvs.add(k + "@" + v);
            }
        }
        if (pd.getHeader() != null && line == 0) {
            for (Map.Entry<String, String> e : pd.getHeader().entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (!k.isEmpty() && !v.isEmpty()) kvs.add(k + "@" + v);
            }
        }
        // HkPlayData 未含 subtitle/audioUrls 字段，从原始串补读（8.83 PlayData 有这两项）
        String subtitle = "";
        String audio = "";
        try {
            JSONObject raw = new JSONObject(rawUrl.trim());
            subtitle = raw.optString("subtitle", "").trim();
            JSONArray audioUrls = raw.optJSONArray("audioUrls");
            if (audioUrls != null && audioUrls.length() > 0) {
                audio = audioUrls.optString(line < audioUrls.length() ? line : 0, "").trim();
            }
        } catch (Exception ignored) {
        }
        if (kvs.isEmpty() && subtitle.isEmpty() && audio.isEmpty()) return u;
        try {
            JSONObject mini = new JSONObject();
            JSONArray mu = new JSONArray();
            mu.put(u);
            mini.put("urls", mu);
            if (!kvs.isEmpty()) {
                JSONArray mh = new JSONArray();
                for (String kv : kvs) mh.put(kv);
                mini.put("headers", mh);
            }
            if (!subtitle.isEmpty()) mini.put("subtitle", subtitle);
            if (!audio.isEmpty()) {
                JSONArray ma = new JSONArray();
                ma.put(audio);
                mini.put("audioUrls", ma);
            }
            return mini.toString();
        } catch (Exception e) {
            return u;
        }
    }

    /** 多线路载荷：{flags:[{name, episodes:[{name,url,pic}]}]}，播放页据此展开线路与选集。 */
    static JSONObject buildFlagsPayload(java.util.List<HkArticleFragment.HkChapter> chapters,
                                        java.util.List<String> rawUrls,
                                        java.util.List<HkPlayData> playDatas,
                                        java.util.List<String> lineNames) {
        try {
            JSONObject root = new JSONObject();
            JSONArray flags = new JSONArray();
            for (int line = 0; line < lineNames.size(); line++) {
                JSONObject f = new JSONObject();
                f.put("name", lineNames.get(line));
                JSONArray eps = new JSONArray();
                for (int i = 0; i < chapters.size(); i++) {
                    HkArticleFragment.HkChapter ch = chapters.get(i);
                    JSONObject o = new JSONObject();
                    o.put("name", !TextUtils.isEmpty(ch.title) ? ch.title
                            : (ch.memoryTitle == null ? "" : ch.memoryTitle));
                    o.put("url", expandedUrl(playDatas.get(i), rawUrls.get(i), line));
                    o.put("pic", ch.pic == null ? "" : ch.pic);
                    eps.put(o);
                }
                f.put("episodes", eps);
                flags.put(f);
            }
            root.put("flags", flags);
            return root;
        } catch (Exception e) {
            return null;
        }
    }

    /** x5Play:// 承载页（F3）：合成 x5_webview_single 规则开行内 WebView 页（与列表/搜索页 x5:// 支同构）。 */
    static void openX5PlayPage(Activity activity, String title, String target) {
        HkRule rule = new HkRule();
        rule.setUrl("hiker://empty");
        rule.setFindRule("js:setResult([{\n    url:\"" + target
                + "\",\ncol_type:\"x5_webview_single\",\ndesc:\"100%&&float\",\nextra:{canBack: true}\n}]);");
        rule.setColType(HkColType.X5_WEB_VIEW.code);
        rule.setTitle(title == null ? "" : title);
        startRule(activity, rule, title);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureStatics();
        // 照原版 initView 的规则摄入顺序：Intent "data" → 静态槽 → 缓存文件，全空走回收提示退出
        String data = getIntent().getStringExtra("data");
        if (TextUtils.isEmpty(data)) {
            // 回退消费必须凭 Intent 里的规则指纹认领，对不上就当没有（走回收提示），
            // 绝不把上一个大规则静默安过来——那正是串页（花都影视出 MissAV 内容）的机制。
            String key = getIntent().getStringExtra("ruleKey");
            if (key != null && key.equals(tempRuleKey)) data = tempRuleData;
            if (data == null && key != null) data = readTempRuleCache(key);
        }
        if (TextUtils.isEmpty(data)) {
            showRecycledAndExit();
            return;
        }
        try {
            rule = new Gson().fromJson(data, HkRule.class);
        } catch (Exception e) {
            Log.e(TAG, "hk error", e);
        }
        if (rule == null) {
            showRecycledAndExit();
            return;
        }
        // 照原版 initData：消费 tempParentData 静态槽（取走后清空）
        HkArticleFragment.AutoPageData parentData = tempParentData;
        tempParentData = null;
        pageTitle = getIntent().getStringExtra("title");
        if (TextUtils.isEmpty(pageTitle)) pageTitle = rule.getTitle();
        // 赶在列表加载触发引擎 init（跑 preRule）之前注册，否则第一声 showLoading 会漏掉
        HkLoading.setListener(hkLoadingListener);
        // 活动标题必须真实设置：HkFuck.inList(Activity) 与 Fragment.activityTitle() 都读它
        setTitle(pageTitle == null ? "" : pageTitle);
        setContentView(R.layout.activity_hk_list);
        // 液态玻璃动态背景（LinearLayout 根走 decor 注入，视觉等同 XML 首孩子）
        HkDynamicBgView.attachTo(this);
        // 海阔浅色页状态栏：透明＋深色图标，动态背景（decor 注入）画到顶，融成一片
        EdgeToEdge.enable(this,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT));
        buildMiniappTabs();
        if (savedInstanceState != null) {
            androidx.fragment.app.Fragment restored =
                    getSupportFragmentManager().findFragmentById(R.id.hk_list_container);
            if (restored instanceof HkArticleFragment) {
                fragment = (HkArticleFragment) restored;
                // 恢复出的 Fragment 实例字段（宿主接线四件）已随进程回收丢失，必须与新鲜分支同口径补齐，
                // 否则子页点击被 null 守卫静默吞掉、续看记录失效
                fragment.setHostListener(this);
                fragment.setListHost(getIntent().getBooleanExtra("listHost", false));
                fragment.setPageOpener(this);
                fragment.setImmersiveTheme(false);
                if (parentData != null) fragment.setAutoPageData(parentData);
                return;
            }
        }
        fragment = new HkArticleFragment().instance(rule);
        fragment.setHostListener(this);
        fragment.setListHost(getIntent().getBooleanExtra("listHost", false));
        fragment.setPageOpener(this);
        fragment.setImmersiveTheme(false);
        if (parentData != null) fragment.setAutoPageData(parentData);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.xc_frag_fade_in, R.anim.xc_frag_fade_out)
                .add(R.id.hk_list_container, fragment)
                .commit();
        // 精修 P4：与主 App 同一套渐入渐出（不改继承，只加两行）
        XcFadeTransition.applyEnter(this);
    }

    @Override
    public void finish() {
        super.finish();
        XcFadeTransition.applyExit(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentRef = new WeakReference<>(this);
        HkLoading.setListener(hkLoadingListener);
    }

    @Override
    protected void onPause() {
        if (currentActivity() == this) currentRef = null;
        HkLoading.clearListener(hkLoadingListener);
        hideHkLoading();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        HkLoading.clearListener(hkLoadingListener);
        hideHkLoading();
        HkWebkitParser.destroy0();
        HkX5WebViewParser.destroy0();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this);
        super.onStop();
    }

    /** backToHome：8.83 BackMainEvent 清栈。星辰等价：子页级实例（listHost=true）直接退出，规则首页级留守。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBackMainEvent(com.fongmi.android.tv.api.hk.event.video.BackMainEvent event) {
        if (getIntent().getBooleanExtra("listHost", false)) finish();
    }

    /** 照原版 initView 数据全丢分支：温馨提示「页面数据被系统回收，即将退出该页面。」确认后退出。 */
    private void showRecycledAndExit() {
        new HkDialogs(this)
                .setTitle("温馨提示")
                .setMessage("页面数据被系统回收，即将退出该页面。")
                .setCancelable(false)
                .setPositiveButton("知道了", (dialog, which) -> finish())
                .show();
    }

    // —— PageOpener（子列表新开实例 = 原版新开 FilmListActivity）——

    @Override
    public void openSubList(HkArticleFragment.SubListRequest req) {
        if (req == null || req.subRule == null) return;
        doOpenSubList(req);
    }

    /**
     * 子页打开本体：照 8.83——先开页、页内加载（取数失败在新页里报错），
     * 宿主不在开页前预抓、不拦点击（旧详情预取闸已整链拆除）。
     */
    private void doOpenSubList(HkArticleFragment.SubListRequest req) {
        Intent intent = new Intent(this, HkListActivity.class);
        putTempRule(intent, new Gson().toJson(req.subRule));
        intent.putExtra("title", req.title);
        intent.putExtra("parentTitle", req.parentTitle);
        intent.putExtra("parentUrl", req.parentUrl);
        intent.putExtra("picUrl", req.picUrl);
        intent.putExtra("fromActivity", req.fromActivity);
        intent.putExtra("fromLastClick", req.fromLastClick);
        intent.putExtra("listHost", true);
        if (req.autoPageData != null) tempParentData = req.autoPageData;
        // 子页参数已由 HkPageParser 写进子规则的 params 字段（可序列化），随规则 JSON 走，
        // 不再走静态槽交接（槽竞态会把参数串给别的页面，1.3.11 审计定案已撤）。
        // 新窗口条目：原版以 NEW_TASK 独立任务打开（func://background 的退后台判定正读此标志）
        if (req.newWindow) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // req.windowId 是原版多窗口管理器的窗口号，星辰无多窗口管理器，仅透传不消费
        intent.putExtra("windowId", req.windowId);
        // 照原版 dealRule：普通子页以请求码 2 打开（子页可回传 refreshPage 让本页刷新），新窗口直开
        if (req.newWindow) {
            startActivity(intent);
        } else {
            startActivityForResult(intent, 2);
        }
    }

    /**
     * Fragment.toNextPage 的开页接缝（摸鱼日报分类 lazy 返回 hiker://page/type1?fypage 走这里）。
     * 组页照 8.83 toNextPage：HkPageParser.getNextPage(rule, pageUrl, paramsJson) 组子规则，
     * 组不出页（返 null／抛 ParseException）就弹错留本页、绝不拿原规则回落开页——
     * 回落开出来的新页装的是原封不动的首页规则，就是「点二级又重开一个首页」的字面成因（W1）。
     * 开页经 openSubList 标准子页通道（返回栈/标题与现树一致）。
     */
    @Override
    public void openPageUrl(HkRule rule, String pageUrl, int position) {
        com.github.catvod.crawler.SpiderDebug.log("hk", "openPageUrl entry rule=%s pageUrl=%s pos=%d",
                rule == null ? "" : rule.getTitle(), pageUrl, position);
        try {
            String paramsJson = fragment == null ? "" : fragment.getItemExtraJson(position);
            String title = fragment == null ? "" : fragment.getItemTitle(position);
            HkRule next = HkPageParser.getNextPage(rule, pageUrl, paramsJson);
            com.github.catvod.crawler.SpiderDebug.log("hk", "openPageUrl next=%s", next != null);
            if (next == null) {
                // 照 8.83 lambda$toNextPage$35：失败弹错留本页（原文 ToastMgr.shortCenter
                // 弹异常消息），不开任何新页。getNextPage 仅对非 page url 返 null，
                // 本接缝调用方已先过 isPageUrl，此分支为防御性死路，文案走解析器同族口径。
                runOnUiThread(() -> HkRouteBlocker.toastShortBottom(this, "找不到对应的页面"));
                return;
            }
            HkArticleFragment.SubListRequest req = new HkArticleFragment.SubListRequest();
            req.subRule = next;
            req.title = title;
            req.parentTitle = pageTitle;
            req.parentUrl = rule == null ? "" : rule.getUrl();
            req.fromActivity = hashCode();
            // 直接开新页，由新页自己的顶部进度条接管加载（先开页、页内加载）。
            runOnUiThread(() -> doOpenSubList(req));
        } catch (Exception e) {
            Log.e(TAG, "openPageUrl", e);
            runOnUiThread(() -> HkRouteBlocker.toastShortBottom(this, String.valueOf(e.getMessage())));
        }
    }

    /**
     * 照原版 FilmListActivity.onActivityResult 的宿主分支：请求码 2 子页回传 refreshPage → 本页刷新；
     * 0x961 扫码结果 → dealQRContent；0x4e16 文件编辑保存 → 逐个求值登记的回调 JS。
     * Fragment 自有请求码（0x2382/0x2383/0x2385）由 super 分发，不在此处理。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) {
            if (data != null && data.getBooleanExtra("refreshPage", false) && fragment != null) {
                fragment.refreshFromHost(data.getBooleanExtra("scrollTop", false));
            }
            return;
        }
        if (requestCode == 0x961) {
            if (resultCode == RESULT_OK && data != null) {
                // 星辰 ScanActivity 的结果键是 "address"（原版扫码页是 "codedContent"，两侧都认）
                String content = data.getStringExtra("address");
                if (TextUtils.isEmpty(content)) content = data.getStringExtra("codedContent");
                if (!TextUtils.isEmpty(content)) dealQRContent(content);
            }
            return;
        }
        if (requestCode == 0x4e16) {
            if (resultCode == RESULT_OK && data != null
                    && data.getBooleanExtra("saved", false) && !fileEditCallbackJs.isEmpty()) {
                java.util.List<String> callbacks = new java.util.ArrayList<>(fileEditCallbackJs);
                fileEditCallbackJs.clear();
                HkRule currentRule = rule;
                new Thread(() -> {
                    for (String js : callbacks) {
                        try {
                            com.fongmi.android.tv.api.hk.HkJsRuntime rt =
                                    HkEngineManager.runtimeFor(currentRule);
                            HkEngineManager.ensureInit(rt);
                            rt.evalJS(com.fongmi.android.tv.api.hk.HkJsRuntime.getMyRule(currentRule) + js, "");
                        } catch (Exception e) {
                            Log.e(TAG, "hk error", e);
                        }
                    }
                }, "hk-file-edit-callback").start();
            }
        }
    }

    /** 照原版宿主承接 IconOneSearch 的相机权限结果（请求码同为 0x961）：给权后开扫码页，拒绝弹「没有相机权限！」。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 0x961) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (granted) {
            startActivityForResult(new Intent(this,
                    com.fongmi.android.tv.ui.activity.ScanActivity.class), 0x961);
        } else {
            HkRouteBlocker.toastShortBottom(this, "没有相机权限！");
        }
    }

    /** 照原版 dealQRContent：http 开头走网页；其余弹内容框（确认键 = 复制全文）。 */
    private void dealQRContent(String content) {
        if (content.startsWith("http")) {
            new HkDetailBridge().goWeb(this, content);
            return;
        }
        new HkDialogs(this)
                .setMessage(content)
                .setPositiveButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", content));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * editFile:// 的文件编辑器等价（原版 HomeHtmlEditActivity，请求码 0x4e16 回流）：
     * 读文件全文进编辑框，保存写回后直接在本页走 0x4e16 保存回流（求值登记的回调 JS）。
     */
    public void openFileEditor(String path) {
        String content;
        try {
            content = readFileText(new File(path));
        } catch (Exception e) {
            Log.e(TAG, "hk error", e);
            HkRouteBlocker.toastShortBottom(this, "无法打开文件：" + path);
            return;
        }
        EditText edit = new EditText(this);
        edit.setText(content);
        edit.setTextColor(0xFF222222);
        edit.setTextSize(13f);
        edit.setTypeface(android.graphics.Typeface.MONOSPACE);
        edit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        edit.setPadding(pad, pad, pad, pad);
        new HkDialogs(this)
                .setTitle(new File(path).getName())
                .setView(edit)
                .setPositiveButton("保存", (dialog, which) -> {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(path)) {
                        out.write(edit.getText().toString().getBytes(StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        Log.e(TAG, "hk error", e);
                        HkRouteBlocker.toastShortBottom(this, "保存失败：" + e.getMessage());
                        return;
                    }
                    onActivityResult(0x4e16, RESULT_OK, new Intent().putExtra("saved", true));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 对应原版 HighLightViewActivity 看代码（标题固定「代码」）：只读滚动文本弹窗等价。 */
    @Override
    public void openCodeView(String code) {
        TextView text = new TextView(this);
        text.setText(code == null ? "" : code);
        text.setTextSize(13f);
        text.setTextColor(0xFF222222);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        new HkDialogs(this)
                .setTitle("代码")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 行内高亮编辑等价：可编辑文本弹窗，确定后回填（原版经 0x2382 结果槽回填，机能等价）。 */
    @Override
    public void openHighlightEditor(String text, Consumer<String> callback) {
        EditText edit = new EditText(this);
        edit.setText(text == null ? "" : text);
        edit.setTextColor(0xFF222222);
        edit.setTextSize(14f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        edit.setPadding(pad, pad, pad, pad);
        new HkDialogs(this)
                .setTitle("编辑")
                .setView(edit)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (callback != null) callback.accept(edit.getText().toString());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // —— HostListener ——

    @Override
    public void onChildChanged(HkArticleFragment fragment, boolean hasWebView) {
        childHasWebView = hasWebView;
    }

    @Override
    public void hideLoading() {
        // 本宿主无独立加载指示（Fragment 自带进度线），对应原版 hideLoading 的空等价
    }

    /** 续看位置记录：以（页面规则 url + 页面标题）为键存「条目标题@@下标」，供本页重开时恢复。 */
    @Override
    public void onMemoryPosition(int position) {
        if (fragment == null || rule == null) return;
        HkItem item = fragment.getItemAt(position);
        if (item == null) return;
        putLastClick(rule.getUrl(), pageTitle, item.getTitle() + "@@" + position);
    }

    /** 照原版 FilmListActivity.background()：带 NEW_TASK 标志的窗口才可退后台。 */
    @Override
    public void onBackground() {
        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            moveTaskToBack(true);
            HkRouteBlocker.toastShortBottom(this, "已后台，通过系统多任务界面可以回到当前窗口");
        } else {
            HkRouteBlocker.toastShortBottom(this, "当前页面不支持退到后台");
        }
    }

    @Override
    public String onQueryLastClick(String ruleUrl, String title) {
        return getLastClick(ruleUrl, title);
    }

    /** 对应原版 genLastClickText + 上次点击提示：取 "@@" 前标题文本作底部短提示。 */
    @Override
    public void onShowLastClick(String lastClick) {
        if (TextUtils.isEmpty(lastClick)) return;
        String text = HkStringUtil.getTitleText(lastClick.split("@@")[0]);
        if (!TextUtils.isEmpty(text)) HkRouteBlocker.toastShortBottom(this, text);
    }

    /** #autoPage# 续页时把父页的续看记录推进到当前章节（键 = 父页 url + 父页标题）。 */
    @Override
    public void onAutoPageLastClick(HkArticleFragment.AutoPageData data, HkItem item, int globalPos) {
        if (data == null || item == null) return;
        putLastClick(data.url, data.title, item.getTitle() + "@@" + globalPos);
    }

    // —— 续看记录存储（原版 ViewCollection/ViewHistory 的星辰等价，键序与原版查询一致：url + title）——

    private static String lastClickKey(String ruleUrl, String pageTitle) {
        return (ruleUrl == null ? "" : ruleUrl) + "\n" + (pageTitle == null ? "" : pageTitle);
    }

    private static void putLastClick(String ruleUrl, String pageTitle, String value) {
        try {
            App.get().getSharedPreferences(PREF_LAST_CLICK, Context.MODE_PRIVATE)
                    .edit().putString(lastClickKey(ruleUrl, pageTitle), value).apply();
        } catch (Exception ignored) {
        }
    }

    private static String getLastClick(String ruleUrl, String pageTitle) {
        try {
            SharedPreferences sp = App.get().getSharedPreferences(PREF_LAST_CLICK, Context.MODE_PRIVATE);
            String value = sp.getString(lastClickKey(ruleUrl, pageTitle), null);
            if (value != null) return value;
            if (!TextUtils.isEmpty(pageTitle)) return null;
            // 标题缺失时按 url 前缀兜底取一条（原版查询在标题为空时同样放宽）
            String prefix = (ruleUrl == null ? "" : ruleUrl) + "\n";
            for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String) {
                    return (String) entry.getValue();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // —— tempRule 缓存文件（原版 DataTransferUtils "tempRule" 的等价兜底）——

    private static File tempRuleCacheFile() {
        return new File(App.get().getCacheDir(), "hk_temp_rule.txt");
    }

    private static void writeTempRuleCache(String key, String ruleJson) {
        // 用流而非 java.nio.file.Files：minSdk 24 的设备上后者不存在（API 26 才有）
        // 文件首行写规则指纹，读时核对，防跨规则认领（旧格式无指纹行自然核不上、作废一次自愈）
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tempRuleCacheFile())) {
            out.write((key + "\n" + ruleJson).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readTempRuleCache(String expectedKey) {
        try {
            File file = tempRuleCacheFile();
            if (!file.exists() || expectedKey == null) return null;
            String text = readFileText(file);
            int nl = text.indexOf('\n');
            if (nl < 0) return null;
            if (!expectedKey.equals(text.substring(0, nl).trim())) return null;
            return text.substring(nl + 1);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readFileText(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int offset = 0;
            int read;
            while (offset < bytes.length && (read = in.read(bytes, offset, bytes.length - offset)) > 0) {
                offset += read;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ================= 顶部小程序切换 tabs（星辰自有功能，自旧内容页 HkContentFragment 原样迁入） =================

    /**
     * 内容页顶部小程序切换 tabs：横向显示已安装的小程序，点击切换规则。
     * 新架构下切换 = 以新页打开目标规则并关掉本页（返回栈回小程序首页，与开页导航一致）。
     * 仅规则首页级（listHost=false）显示；子列表级保持 8.83 原版页眉，不挂此栏。
     */
    private void buildMiniappTabs() {
        View row = findViewById(R.id.hk_miniapp_tabs_row);
        if (row == null) return;
        if (getIntent().getBooleanExtra("listHost", false)) {
            row.setVisibility(View.GONE);
            return;
        }
        // 液态玻璃：页签条换玻璃悬浮条（圆角 12dp）；选中奶油胶囊＋金下划线已随 hk_chip_selected 自动生效
        row.setBackground(new HkGlassDrawable(this, 12));
        try {
            List<HkRule> rules = HkRuleManager.get().getRules();
            java.util.List<HkRule> enabled = new java.util.ArrayList<>();
            for (HkRule r : rules) {
                // 顶部 tabs 不需要"我的主页"（HikerHome 未实现，打开必报错）
                // 标题为空（脏数据已在 getTitle 清洗）的规则不展示，避免空白 tab
                if (r.isEnabled() && !isHomePageRule(r) && !r.getTitle().isEmpty()) enabled.add(r);
            }
            // 当前规则若不在 tabs 列表里（未启用或刚导入），追加进去并选中，保证 tabs 跟随当前页面
            HkRule cur = rule;
            String curTitle = cur == null ? null : cur.getTitle();
            boolean hasCur = false;
            if (curTitle != null) {
                for (HkRule r : enabled) {
                    if (curTitle.equals(r.getTitle())) {
                        hasCur = true;
                        break;
                    }
                }
            }
            if (!hasCur && cur != null) {
                enabled.add(cur);
            }
            miniappRules.clear();
            miniappRules.addAll(enabled);
            if (enabled.size() <= 1) {
                row.setVisibility(View.GONE);
                return;
            }
            java.util.List<String[]> pairs = new java.util.ArrayList<>();
            int sel = 0;
            for (int i = 0; i < enabled.size(); i++) {
                HkRule r = enabled.get(i);
                pairs.add(new String[]{r.getTitle(), String.valueOf(i)});
                if (curTitle != null && curTitle.equals(r.getTitle())) sel = i;
            }
            final java.util.List<HkRule> finalEnabled = enabled;
            MiniappChipAdapter adapter = new MiniappChipAdapter(pairs, sel, idx -> {
                try {
                    int i = Integer.parseInt(idx);
                    if (i >= 0 && i < finalEnabled.size()) {
                        HkRule target = finalEnabled.get(i);
                        if (rule == null || !target.getTitle().equals(rule.getTitle())) {
                            switchRule(target);
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
            RecyclerView tabsRv = findViewById(R.id.hk_rv_miniapp_tabs);
            tabsRv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            // 横向 tab 行不抢垂直滚动：避免嵌套滚动冲突
            tabsRv.setHasFixedSize(true);
            tabsRv.setNestedScrollingEnabled(false);
            tabsRv.setAdapter(adapter);
            // 滚动到选中项，保证当前小程序的 tab 可见
            final int finalSel = sel;
            tabsRv.post(() -> {
                try {
                    ((LinearLayoutManager) tabsRv.getLayoutManager())
                            .scrollToPositionWithOffset(finalSel, 0);
                } catch (Throwable ignored) {
                }
            });
            View all = findViewById(R.id.hk_btn_miniapp_all);
            if (all != null) all.setOnClickListener(v -> showMiniappSwitchDialog());
            row.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            row.setVisibility(View.GONE);
        }
    }

    /** 是否为"我的主页"规则：顶部 tabs 暂不展示它（HikerHome 未实现）。 */
    private static boolean isHomePageRule(HkRule r) {
        return r != null && "我的主页".equals(r.getTitle());
    }

    /** 切规则：新页打开目标规则、关掉本页（新架构的开页导航语义）。 */
    private void switchRule(HkRule target) {
        if (target == null) return;
        startRule(this, target, target.getTitle());
        finish();
    }

    /**
     * 小程序切换：顶部 tabs 栏最右「全部」按钮弹窗。
     * 星辰风格：毛玻璃底 + 独立选项卡片。8.83 内容页无此功能。
     */
    private void showMiniappSwitchDialog() {
        if (miniappRules.size() <= 1) return;
        final String curTitle = rule == null ? null : rule.getTitle();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.xc_dialog_light);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("选择小程序");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setClipToPadding(false);
        int rows = (miniappRules.size() + 1) / 2;
        int gridH = Math.min(rows * dp(66), dp(360));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, gridH);
        rlp.topMargin = dp(12);
        card.addView(rv, rlp);

        final AlertDialog[] holder = new AlertDialog[1];
        rv.setAdapter(new MiniappSwitchAdapter(miniappRules, curTitle, target -> {
            if (holder[0] != null) holder[0].dismiss();
            if (target != null && (rule == null || !target.getTitle().equals(rule.getTitle()))) {
                switchRule(target);
            }
        }));

        FrameLayout outer = new FrameLayout(this);
        int m = dp(24);
        outer.setPadding(m, 0, m, 0);
        outer.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        AlertDialog dialog = new HkDialogs(this)
                .setView(outer).create();
        holder[0] = dialog;
        // 浅色卡片自成一面：清掉窗口暗色玻璃底，不许黑层从卡片边缘探出
        HkDialogs.clearWindowBackground(dialog);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 小程序切换 tabs 的横向 pill 适配器（旧内容页 ChipAdapter 的金色模式原样）。 */
    private static class MiniappChipAdapter extends RecyclerView.Adapter<MiniappChipAdapter.Holder> {
        interface OnPick {
            void onPick(String value);
        }

        private final java.util.List<String[]> pairs;
        private int selected;
        private final OnPick listener;

        MiniappChipAdapter(java.util.List<String[]> pairs, int selected, OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView chip;
            com.google.android.material.card.MaterialCardView card;

            Holder(View v) {
                super(v);
                chip = v.findViewById(R.id.tv_chip);
                android.view.View p = (android.view.View) chip.getParent();
                card = p instanceof com.google.android.material.card.MaterialCardView
                        ? (com.google.android.material.card.MaterialCardView) p : null;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_chip, parent, false);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.rightMargin = (int) (8 * parent.getContext().getResources().getDisplayMetrics().density + 0.5f);
            v.setLayoutParams(lp);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] pair = pairs.get(position);
            h.chip.setText(pair[0]);
            boolean sel = position == selected;
            h.chip.setSelected(sel);
            // 金色主题：小程序切换 tabs 在圆角框内，透明底 + 金色系文字，与分类 pills 区分
            if (h.card != null) {
                h.card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                h.card.setStrokeWidth(0);
            }
            // v5 基准：选中=奶油胶囊＋金下划线，未选中透明
            h.chip.setBackgroundResource(sel ? R.drawable.hk_chip_selected : 0);
            h.chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            h.chip.setTextColor(sel ? 0xFF8A6D00 : 0xFF8A8F98);
            h.chip.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                selected = pos;
                notifyDataSetChanged();
                if (listener != null) listener.onPick(pairs.get(pos)[1]);
            });
        }

        @Override
        public int getItemCount() {
            return pairs.size();
        }
    }

    /** 「全部」弹窗的 2 列选项卡片适配器：独立卡片框、有间距，当前选中高亮（旧内容页原样）。 */
    private static class MiniappSwitchAdapter extends RecyclerView.Adapter<MiniappSwitchAdapter.Holder> {
        interface OnPick {
            void onPick(HkRule rule);
        }

        private final java.util.List<HkRule> rules;
        private final String curTitle;
        private final OnPick listener;

        MiniappSwitchAdapter(java.util.List<HkRule> rules, String curTitle, OnPick listener) {
            this.rules = rules;
            this.curTitle = curTitle;
            this.listener = listener;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView name;

            Holder(View v) {
                super(v);
                name = (TextView) v;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.content.Context ctx = parent.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            TextView tv = new TextView(ctx);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextSize(15);
            int p = (int) (14 * d + 0.5f);
            tv.setPadding(p, p, p, p);
            GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int mg = (int) (6 * d + 0.5f);
            lp.setMargins(mg, mg, mg, mg);
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            HkRule r = rules.get(position);
            h.name.setText(r.getTitle());
            boolean sel = curTitle != null && curTitle.equals(r.getTitle());
            h.name.setTextColor(sel ? 0xFFD9A420 : 0xFF1A1D24);
            h.name.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            h.name.setOnClickListener(v -> {
                if (listener != null) listener.onPick(r);
            });
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }
    }
}
