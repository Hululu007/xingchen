package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.utils.download.DownloadManager;
import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
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
        HkDetailBridge.FileEditCallbackHost {
    private static final String TAG = "HkListActivity";

    private static final int TEMP_RULE_LIMIT = 0x32000;
    private static final String PREF_LAST_CLICK = "hk_last_click";

    /** 原版 Application.tempRuleData 等价：大数据规则 JSON 的进程内静态槽。 */
    private static volatile String tempRuleData;
    /** 原版 FilmListActivity.tempParentData 等价：#autoPage# 子页的父页续页数据静态槽（消费后清空）。 */
    private static volatile HkArticleFragment.AutoPageData tempParentData;
    private static final AtomicBoolean STATICS_READY = new AtomicBoolean(false);
    private static volatile WeakReference<Activity> currentRef;

    private HkArticleFragment fragment;
    private HkRule rule;
    private String pageTitle;
    private boolean childHasWebView;
    /** editFile:// 支登记的保存后回调 JS（原版宿主同名字段族），编辑页以 0x4e16 回流后逐个求值。 */
    private final java.util.List<String> fileEditCallbackJs = new java.util.ArrayList<>();

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
        int length = ruleJson.getBytes(StandardCharsets.UTF_8).length;
        Log.d(TAG, String.format("变量传递, length: %s", length));
        if (length > TEMP_RULE_LIMIT) {
            tempRuleData = ruleJson;
            writeTempRuleCache(ruleJson);
        } else {
            intent.putExtra("data", ruleJson);
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
        HkArticleListModel.setEngineBridge(new HkEngineBridge(app));
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
                JSONArray arr = new JSONArray();
                for (HkArticleFragment.HkChapter ch : chapters) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", !TextUtils.isEmpty(ch.memoryTitle) ? ch.memoryTitle
                                : (ch.title == null ? "" : ch.title));
                        o.put("url", playUrlForChapter(ch));
                        o.put("pic", ch.pic == null ? "" : ch.pic);
                        arr.put(o);
                    } catch (Exception ignored) {
                    }
                }
                String selName = !TextUtils.isEmpty(sel.memoryTitle) ? sel.memoryTitle
                        : (sel.title == null ? "" : sel.title);
                String name = !TextUtils.isEmpty(mTitle) ? mTitle
                        : (!TextUtils.isEmpty(film) ? film : selName);
                VideoActivity.startHkPlay(activity, ruleTitle, "默认", selUrl, selName, name,
                        sel.pic == null ? "" : sel.pic, arr.toString(), selIdx);
            }

            @Override
            public void showDownloadDialog(Activity activity, String title, String url, String film) {
                if (activity == null || TextUtils.isEmpty(url)) return;
                String name = !TextUtils.isEmpty(title) ? title : url;
                new AlertDialog.Builder(activity)
                        .setTitle("下载资源")
                        .setMessage(name + "\n" + url)
                        .setPositiveButton("下载", (d, w) -> {
                            DownloadManager.get().add(url, name);
                            HkRouteBlocker.toastShortBottom(activity, "已加入下载：" + name);
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }

            @Override
            public void startFastDownload(Context context, String title, String url, String film) {
                if (TextUtils.isEmpty(url)) return;
                String name = !TextUtils.isEmpty(title) ? title : url;
                DownloadManager.get().add(url, name);
                if (context != null) {
                    HkRouteBlocker.toastShortBottom(context, "已加入下载：" + name);
                }
            }
        };
        HkFuck.loadList();
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

    /** x5Play:// 承载页（F3）：合成 x5_webview_single 规则开行内 WebView 页（与列表/搜索页 x5:// 支同构）。 */
    static void openX5PlayPage(Activity activity, String title, String target) {
        HkRule rule = new HkRule();
        rule.setUrl("hiker://empty");
        rule.setFindRule("js:setResult([{\n    url:\"" + target
                + "\",\ndesc:\"100%&&float\",\nextra:{canBack: true}\n}]);");
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
            data = tempRuleData;
            if (data == null) data = readTempRuleCache();
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
        // 活动标题必须真实设置：HkFuck.inList(Activity) 与 Fragment.activityTitle() 都读它
        setTitle(pageTitle == null ? "" : pageTitle);
        setContentView(R.layout.activity_hk_list);
        TextView titleView = findViewById(R.id.hk_list_title);
        if (titleView != null) titleView.setText(pageTitle);
        android.view.View back = findViewById(R.id.hk_list_back);
        if (back != null) back.setOnClickListener(v -> finish());
        if (savedInstanceState != null) {
            androidx.fragment.app.Fragment restored =
                    getSupportFragmentManager().findFragmentById(R.id.hk_list_container);
            if (restored instanceof HkArticleFragment) {
                fragment = (HkArticleFragment) restored;
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
                .add(R.id.hk_list_container, fragment)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentRef = new WeakReference<>(this);
    }

    @Override
    protected void onPause() {
        if (currentActivity() == this) currentRef = null;
        super.onPause();
    }

    /** 照原版 initView 数据全丢分支：温馨提示「页面数据被系统回收，即将退出该页面。」确认后退出。 */
    private void showRecycledAndExit() {
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
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

    private static void writeTempRuleCache(String ruleJson) {
        // 用流而非 java.nio.file.Files：minSdk 24 的设备上后者不存在（API 26 才有）
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tempRuleCacheFile())) {
            out.write(ruleJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String readTempRuleCache() {
        try {
            File file = tempRuleCacheFile();
            if (!file.exists()) return null;
            return readFileText(file);
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
}
