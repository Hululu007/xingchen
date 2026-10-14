package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkPageParser;
import com.fongmi.android.tv.api.hk.HkParserKit;
import com.fongmi.android.tv.api.hk.HkRule;

import java.net.URLDecoder;
import java.util.function.Consumer;

import timber.log.Timber;

/**
 * 照抄 8.83 RouteBlocker（smali_classes2/ui/home/model/RouteBlocker.smali）：
 * isRoute 的前置检查、分支判定与"命中 hiker:// 路由恒吞掉（返回 true）"语义逐字转写；
 * 各目标页（书签/工具/文件浏览/历史/收藏/规则管理/全局搜索等）在星辰由宿主钩子承接，未接时只吞不跳。
 * webdav / webRule 两支按既定决策不移植（WebDAV 与远程编辑子系统不搬），命中仍返回 true。
 */
public final class HkRouteBlocker {

    private HkRouteBlocker() {
    }

    /** 路由目标页与规则库查询的宿主钩子（宿主阶段绑定真实现）。 */
    public interface RouteHooks {
        /** bookmarkGroup：展示某分组的书签，点中后以书签 url 回调（原版 consumer → WebUtil.goWeb）。 */
        default void showBookmarks(Activity activity, String group, Consumer<String> onUrl) {
        }

        /** tools：MoreSettingOfficer.showToolsView。 */
        default void showTools(Activity activity) {
        }

        /** webSearch：WebUtil.goWebHomeAndSearch。 */
        default void goWebHomeAndSearch(Activity activity) {
        }

        /** 打开网页（原版 WebUtil.goWeb，bookmarkGroup 回调与 home 路由 http 候选共用）。 */
        default void goWeb(Context context, String url) {
        }

        /** explore / download(home_late) / localMedia：文件浏览与本地媒体页。 */
        default void openFileBrowser(Activity activity, String path) {
        }

        default void openLocalMedia(Activity activity) {
        }

        /** rulesGroup 后台任务（lambda$isRoute$3）：查组内规则、滤隐私规则、按 article_list_order 排序后以书签式列表展示。 */
        default void showRulesGroup(Activity activity, String group) {
        }

        /** home 路由：打开规则管理页（原版 ArticleListRuleMagActivity）。 */
        default void openRuleManager(Activity activity) {
        }

        /** home@规则名：原版按标题查规则库，存在才发 OnHomePageChangedEvent。 */
        default boolean ruleExistsByTitle(String title) {
            return false;
        }

        /** home@规则名：切换小程序首页到该规则（原版 OnHomePageChangedEvent）。 */
        default void onHomePageChanged(String ruleTitle) {
        }

        /** 当前活动是否为小程序主页（原版 MainActivity / RuleWindowActivity 型判断，决定 home 路由后是否 finish）。 */
        default boolean isHomeActivity(Activity activity) {
            return false;
        }

        /** bookmark / js / downloaded / history / collection / adUrl / adRule 目标页。 */
        default void openBookmarkPage(Activity activity) {
        }

        default void openJsList(Activity activity) {
        }

        default void openDownloadRecords(Activity activity) {
        }

        default void openHistory(Activity activity, int fromActivity, String search) {
        }

        default void openCollection(Activity activity, int fromActivity, String group, String search) {
        }

        default void openAdUrlList(Activity activity) {
        }

        default void openAdRuleList(Activity activity) {
        }

        /** setting / settingMore：原版 EventBus ShowSettingMenuEvent / ShowMoreSettingMenuEvent。 */
        default void showSettingMenu(Activity activity) {
        }

        default void showMoreSettingMenu(Activity activity) {
        }

        /** 全局搜索弹窗（原版 GlobalSearchPopup.startSearch(activity, engine, keyword, "main", color)；color 为 8.83 资源色，宿主自理）。 */
        default void startGlobalSearch(Activity activity, String engine, String keyword) {
        }

        /** 按标题查规则（原版 LitePal title = ?），供 search 路由解析引擎/分组；无则 null。 */
        default HkRule findRuleByTitle(String title) {
            return null;
        }

        /** search 路由的委托搜索（原版 startSearchByDelegateRules）。 */
        default void startDelegateSearch(Activity activity, String keyword, HkRule rule, String rules, boolean delegateOnly) {
        }

        /** search 路由 rule 非空且 simple 为空时：原版 SearchModel.getEngines 异步选引擎（RouteBlocker$3）。 */
        default void loadSearchEngines(Activity activity, String ruleName, String keyword) {
        }

        /** search 路由 simple 非空：原版 SearchInOneRuleActivity（extras wd / rule）。 */
        default void openSearchInOneRule(Activity activity, String keyword, String ruleName) {
        }
    }

    public static volatile RouteHooks hooks = new RouteHooks() {
    };

    private static RouteHooks hooks() {
        RouteHooks h = hooks;
        return h != null ? h : new RouteHooks() {
        };
    }

    /** 照抄 RouteBlocker.isRoute(Activity, String)：转四参版（rule/item 为 null）。 */
    public static boolean isRoute(Activity activity, String url) {
        return isRoute(activity, url, null, null);
    }

    /** 照抄 RouteBlocker.isRoute(Activity, String, Object, BaseResultItem)（smali:29）。 */
    public static boolean isRoute(Activity activity, String url, HkRule rule, HkItem item) {
        if (url == null) return false;
        if (url.startsWith("hiker://empty")) return false;
        if (!url.startsWith("hiker://")) return false;
        if (HkPageParser.isPageUrl(url)) return false;
        if (HkUrlDetector.getImageIdByUrl(url) > 0) return false;
        String route = HkStringUtil.replaceOnce(url, "hiker://", "");
        RouteHooks h = hooks();

        if (route.startsWith("bookmarkGroup")) {
            String group = route.replace("bookmarkGroup@", "");
            // 原版 consumer（lambda$isRoute$0）：WebUtil.goWeb(activity, url, false)
            h.showBookmarks(activity, group, target -> h.goWeb(activity, target));
            return true;
        }
        if (route.equals("tools")) {
            h.showTools(activity);
            return true;
        }
        if (route.equals("webSearch")) {
            h.goWebHomeAndSearch(activity);
            return true;
        }
        if (route.startsWith("explore")) {
            String path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            String[] segs = route.split("path=");
            if (segs.length > 1) {
                path = com.fongmi.android.tv.api.hk.HkJsRuntime.getFilePath(
                        HkParserKit.arrayToString(segs, 1, "path="));
            }
            h.openFileBrowser(activity, path);
            return true;
        }
        if (route.startsWith("localMedia")) {
            h.openLocalMedia(activity);
            return true;
        }
        if (route.equals("download") && item != null
                && "hiker://images/home_late".equals(item.getPic())) {
            h.openFileBrowser(activity,
                    android.os.Environment.getExternalStorageDirectory().getAbsolutePath());
            return true;
        }
        if (route.startsWith("rulesGroup")) {
            String group = route.replace("rulesGroup@", "");
            // 原版 HeavyTaskUtil.executeNewTask(lambda$isRoute$3)：后台查组内规则后回主线程展示
            new Thread(() -> {
                try {
                    h.showRulesGroup(activity, group);
                } catch (Exception e) {
                    Timber.e(e);
                }
            }, "hk-rulesGroup").start();
            return true;
        }
        if (route.startsWith("home")) {
            if (route.equals("home")) {
                h.openRuleManager(activity);
            } else if (route.contains("@")) {
                String[] parts = route.split("@");
                boolean handled = false;
                if (parts.length == 2 && !TextUtils.isEmpty(parts[1])) {
                    String[] candidates = parts[1].split("\\|\\|");
                    if (candidates.length > 1) {
                        for (String candidate : candidates) {
                            if (candidate.startsWith("http")) {
                                h.goWeb(activity, candidate);
                                handled = true;
                                break;
                            }
                            if (h.ruleExistsByTitle(candidate)) {
                                h.onHomePageChanged(candidate);
                                handled = true;
                                break;
                            }
                        }
                    } else {
                        h.onHomePageChanged(parts[1]);
                    }
                    handled = true;
                }
                if (!handled) {
                    toastShortBottom(activity, "首页路由规则有误");
                } else if (!h.isHomeActivity(activity)) {
                    activity.finish();
                }
            } else {
                toastShortBottom(activity, "首页路由规则有误");
            }
            return true;
        }
        if (route.equals("searchSetting")) {
            toastShortBottom(activity, "该页面已下线");
            return true;
        }
        if (route.startsWith("bookmark")) {
            h.openBookmarkPage(activity);
            return true;
        }
        if (route.startsWith("js")) {
            h.openJsList(activity);
            return true;
        }
        if (route.startsWith("download")) {
            // 原版此处复用寄存器里的 "download" 做 startsWith（cond_15），故 download* 一律落下载记录页
            h.openDownloadRecords(activity);
            return true;
        }
        if (route.startsWith("history")) {
            String search = null;
            String param = android.net.Uri.parse("hiker://" + route).getQueryParameter("rule");
            if (!TextUtils.isEmpty(param)) {
                try {
                    search = URLDecoder.decode(param, "utf-8");
                } catch (Exception e) {
                    Timber.e(e);
                    search = param;
                }
            }
            h.openHistory(activity, activity.hashCode(), search);
            return true;
        }
        if (route.startsWith("collection")) {
            android.net.Uri uri = android.net.Uri.parse("hiker://" + route);
            String group = decodeQuery(uri.getQueryParameter("group"));
            String search = decodeQuery(uri.getQueryParameter("rule"));
            h.openCollection(activity, activity.hashCode(), group, search);
            return true;
        }
        if (route.equals("adUrl")) {
            h.openAdUrlList(activity);
            return true;
        }
        if (route.equals("adRule")) {
            h.openAdRuleList(activity);
            return true;
        }
        if (route.equals("setting")) {
            h.showSettingMenu(activity);
            return true;
        }
        if (route.equals("webdav")) {
            // WebDAV 子系统按既定决策不移植：原版弹备份文件名输入框（lambda$isRoute$7/$4），星辰仅吞路由
            return true;
        }
        if (route.equals("webRule")) {
            // 远程规则编辑子系统（RemoteServerManager）星辰无对应物：原版复制本机编辑链接并 toast，星辰仅吞路由
            return true;
        }
        if (route.equals("settingMore")) {
            h.showMoreSettingMenu(activity);
            return true;
        }
        if (route.startsWith("search")) {
            startSearchByRoute(activity, route, rule, item);
        }
        // 原版：通过前置检查的 hiker:// 路由最终恒返回 true（未匹配分支也被吞掉）
        return true;
    }

    private static String decodeQuery(String value) {
        if (TextUtils.isEmpty(value)) return value;
        try {
            return URLDecoder.decode(value, "utf-8");
        } catch (Exception e) {
            Timber.e(e);
            return value;
        }
    }

    /** 照抄 RouteBlocker.startSearchByRoute（smali:1929）：解析查询串、偏好读写、引擎/分组解析，分发走钩子。 */
    private static void startSearchByRoute(Activity activity, String route, HkRule ruleObj, HkItem item) {
        RouteHooks h = hooks();
        String s = null;
        String ruleName = null;
        String group = null;
        String simple = null;
        String[] parts = route.split("search\\?");
        if (parts.length > 1) {
            for (String param : parts[1].split("&")) {
                String[] kv = param.split("=");
                if (kv.length < 2) continue;
                String key = kv[0];
                String value = HkParserKit.arrayToString(kv, 1, "=");
                if ("s".equals(key)) s = value;
                else if ("rule".equals(key)) ruleName = value;
                else if ("simple".equals(key)) simple = value;
                else if ("ruleGroup".equals(key) || "group".equals(key)) group = value;
            }
        } else {
            // 原版 cond_11：无查询串时仅当活动为小程序主页才打开全局搜索
            if (h.isHomeActivity(activity)) {
                h.startGlobalSearch(activity, prefEngine(activity), s);
            }
            return;
        }
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        if (HkStringUtil.isEmpty(s)) {
            String engine = prefEngine(activity);
            if (HkStringUtil.isNotEmpty(ruleName)) {
                try {
                    HkRule found = h.findRuleByTitle(ruleName);
                    if (found != null) {
                        try {
                            sp.edit().putString("searchEngine", ruleName).apply();
                            if (HkStringUtil.isNotEmpty(found.getGroup())) group = found.getGroup();
                        } catch (Exception e) {
                            Timber.e(e);
                        }
                    } else {
                        ruleName = engine;
                    }
                } catch (Exception e) {
                    ruleName = engine;
                    Timber.e(e);
                }
                engine = ruleName;
            }
            if (HkStringUtil.isNotEmpty(group)) {
                sp.edit().putString("searchGroup", group).apply();
            }
            h.startGlobalSearch(activity, engine, "");
            return;
        }
        // s 非空
        if (ruleObj != null && item != null) {
            String rules = item.getExtra("rules");
            if (HkStringUtil.isNotEmpty(rules)) {
                boolean delegateOnly = "true".equals(item.getExtra("delegateOnlySearch"));
                h.startDelegateSearch(activity, s, ruleObj, rules, delegateOnly);
                return;
            }
        }
        if (HkStringUtil.isEmpty(ruleName) && HkStringUtil.isEmpty(group)) {
            h.startGlobalSearch(activity, prefEngine(activity), s);
            return;
        }
        if (HkStringUtil.isEmpty(ruleName)) {
            // 原版 cond_10：只有分组时存分组、用偏好引擎全局搜索
            if (HkStringUtil.isNotEmpty(group)) {
                sp.edit().putString("searchGroup", group).apply();
                h.startGlobalSearch(activity, prefEngine(activity), s);
            }
            return;
        }
        // rule 非空
        if (HkStringUtil.isNotEmpty(simple) || "true".equals(simple)) {
            h.openSearchInOneRule(activity, s, ruleName);
            return;
        }
        sp.edit().putString("searchEngine", ruleName).apply();
        h.loadSearchEngines(activity, ruleName, s);
    }

    private static String prefEngine(Activity activity) {
        return PreferenceManager.getDefaultSharedPreferences(activity)
                .getString("searchEngine", "百度");
    }

    /** 照抄 ToastMgr.shortBottomCenter 的等价实现（底部居中短 toast，与 Fragment.toastBottom 同款）。 */
    static void toastShortBottom(Context context, String text) {
        if (context == null) return;
        android.widget.Toast toast = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT);
        toast.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    static void toastShortCenter(Context context, String text) {
        if (context == null) return;
        android.widget.Toast toast = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT);
        toast.setGravity(android.view.Gravity.CENTER, 0, 0);
        toast.show();
    }

    static void toastLongCenter(Context context, String text) {
        if (context == null) return;
        android.widget.Toast toast = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG);
        toast.setGravity(android.view.Gravity.CENTER, 0, 0);
        toast.show();
    }
}
