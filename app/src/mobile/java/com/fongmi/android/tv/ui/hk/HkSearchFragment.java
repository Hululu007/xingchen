package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.Animatable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkHttpParser;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkPageParser;
import com.fongmi.android.tv.api.hk.HkParserKit;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkSearchEngine;
import com.fongmi.android.tv.api.hk.SearchResult;
import com.fongmi.android.tv.ui.hk.view.HkCenterListPopup;
import com.fongmi.android.tv.utils.Task;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.scwang.smart.refresh.layout.SmartRefreshLayout;

import org.greenrobot.eventbus.EventBus;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import android.util.Log;

/**
 * 照 8.83 SearchFragment（ui/search/SearchFragment.smali，6233 行）转写：
 * 单发动机搜索页。initData 为统一加载入口（状态 1 + StatusEvent + SearchModel.process）；
 * bindArrayToView 做精准模式 filterData + 首/末页指纹去重 + 空态/失败伪条目（C3/C4/C5）；
 * 点击走 dealUrl → dealLazyRule / toNextPage / dealUrlSimply / dealWithUrl 管线（与文章页同构，
 * 条目为 SearchResult）；宿主经 SearchHost 打开详情规则页。
 */
public class HkSearchFragment extends Fragment implements HkSearchModel.Callback {
    private static final String TAG = "HkSearchFragment";

    /** 宿主接缝：详情规则页打开（原版直接 start FilmListActivity）与单规则宿主判定（自动打开唯一结果）。 */
    public interface SearchHost {
        void openSearchRulePage(HkRule rule, String title);

        boolean isOneRuleHost();
    }

    private HkSearchAdapter adapter;
    private String firstPageData;
    private String group;
    private boolean isRefresh;
    private String keyWord;
    private String lastPageData;
    private String myUrl;
    private String orginalUrl;
    private int page = 1;
    private AppCompatImageView progressView1;
    private final List<SearchResult> results = new ArrayList<>();
    private HkSearchEngine searchEngine;
    private HkSearchModel searchModel;
    private SmartRefreshLayout smartRefreshLayout;
    private RecyclerView recyclerView;
    private SearchHost searchHost;

    public HkSearchFragment() {
        // 照原版构造：searchModel 预建并挂 storeUrl 回调（取页成功后回填 myUrl）
        searchModel = new HkSearchModel().withUrlParseCallBack(url -> myUrl = url);
    }

    public HkSearchFragment newInstance(String keyWord, HkSearchEngine engine, int ignored, String group) {
        this.keyWord = keyWord;
        this.searchEngine = engine;
        this.group = group;
        return this;
    }

    public void setSearchHost(SearchHost host) {
        this.searchHost = host;
    }

    public HkSearchEngine getSearchEngine() {
        return searchEngine;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hk_search_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView(view);
        initData();
    }

    @Override
    public void onDestroyView() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        super.onDestroyView();
    }

    protected void initView(View root) {
        smartRefreshLayout = root.findViewById(R.id.refresh_layout);
        smartRefreshLayout.setOnRefreshListener(layout -> {
            page = 1;
            isRefresh = true;
            initData();
        });
        orginalUrl = searchEngine.getSearch_url();
        // 照原版 initView（:5661）：search_url 含 fypage 才挂 OnLoadMoreListener（不动 enable）；
        // 不含时 setEnableAutoLoadMore(false) + setEnableLoadMore(false)。
        boolean hasPage = orginalUrl != null && orginalUrl.contains("fypage");
        if (hasPage) {
            smartRefreshLayout.setOnLoadMoreListener(layout -> {
                page = page + 1;
                isRefresh = false;
                initData();
            });
        } else {
            smartRefreshLayout.setEnableAutoLoadMore(false);
            smartRefreshLayout.setEnableLoadMore(false);
        }
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        recyclerView = root.findViewById(R.id.search_recycleview_v2);
        LinearLayoutManager manager = new LinearLayoutManager(getContext());
        manager.setOrientation(LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(manager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        adapter = new HkSearchAdapter(getContext(), results, keyWord, searchEngine);
        adapter.setOnItemClickListener(new HkSearchAdapter.OnItemClickListener() {
            @Override
            public void onClick(View view, int position, String desc) {
                onItemClick(view, position, desc);
            }

            @Override
            public void onLongClick(View view, int position, String desc) {
                onItemLongClick(view, position, desc);
            }
        });
        recyclerView.setAdapter(adapter);
        progressView1 = root.findViewById(R.id.progress_image_view1);
    }

    protected void initData() {
        searchEngine.setStatus(1);
        EventBus.getDefault().post(new HkSearchStatusEvent(searchEngine, group));
        loading(true);
        searchModel.params(keyWord, searchEngine, page, Boolean.TRUE);
        searchModel.process(HkSearchModel.SEARCH_BY_RULE, this);
    }

    /** 照原版 loading(Z)：未 attach 直接返回；显示时启 AVD 动画，隐藏时停并 INVISIBLE。 */
    public void loading(boolean show) {
        if (!isAdded() || isDetached()) return;
        if (progressView1 == null) return;
        try {
            if (show) {
                progressView1.setVisibility(View.VISIBLE);
                if (progressView1.getDrawable() instanceof Animatable) {
                    ((Animatable) progressView1.getDrawable()).start();
                }
            } else {
                if (progressView1.getDrawable() instanceof Animatable) {
                    ((Animatable) progressView1.getDrawable()).stop();
                }
                progressView1.setVisibility(View.INVISIBLE);
            }
        } catch (Exception e) {
            Log.e(TAG, "hk error", e);
        }
    }

    // ---------------- BaseCallback 三法 ----------------

    @Override
    public void bindArrayToView(String key, List<SearchResult> list) {
        Activity activity = getActivity();
        if (activity == null) return;
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            int smode = prefs.getInt("smode", 0);
            filterData(list);
            String json = new Gson().toJson(list);
            activity.runOnUiThread(() -> bindArrayOnUi(list, json, smode));
        } catch (Exception e) {
            Log.e(TAG, "hk error", e);
        }
    }

    /** 照 lambda$bindArrayToView$13：指纹去重、空态伪条目、状态 2/4、唯一结果自动打开。 */
    private void bindArrayOnUi(List<SearchResult> list, String json, int smode) {
        loading(false);
        if (smartRefreshLayout != null) smartRefreshLayout.finishRefresh(true);
        List<SearchResult> oldResults = new ArrayList<>(results);
        int oldSize = results.size();
        if (isRefresh) {
            results.clear();
            isRefresh = false;
        }
        if (list.size() < 1) {
            if (smartRefreshLayout != null) smartRefreshLayout.finishLoadMore();
        } else if (results.size() > 0) {
            if (smartRefreshLayout != null) smartRefreshLayout.finishLoadMore();
            if (!json.equals(firstPageData) && !json.equals(lastPageData)) {
                results.addAll(list);
            }
        } else {
            if (smartRefreshLayout != null) smartRefreshLayout.finishLoadMore();
            results.addAll(list);
        }
        boolean autoOpen = false;
        if (results.size() < 1) {
            results.addAll(oldResults);
            searchEngine.setStatus(4);
            EventBus.getDefault().post(new HkSearchStatusEvent(searchEngine, group));
            String title = "没有找到你想要的噢-_-" + (smode == 1 ? "（当前为精准搜索模式）" : "");
            if (oldResults.size() < 1 || !title.equals(oldResults.get(0).getTitle())) {
                results.add(new SearchResult(title, "小棉袄"));
            }
        } else {
            autoOpen = checkAutoOpen();
            searchEngine.setStatus(2);
            EventBus.getDefault().post(new HkSearchStatusEvent(searchEngine, group));
        }
        if (!autoOpen && adapter != null) adapter.notifyDataSetChanged();
        if (oldSize == 0) {
            firstPageData = json;
        } else {
            lastPageData = json;
        }
        if (autoOpen) {
            SearchResult only = results.get(0);
            dealUrl(null, only.getUrl(), 0, only);
        }
    }

    /** 唯一结果自动打开判定（原版仅单规则宿主）：url 非空、非 hiker://search、extra.rules 非空。 */
    private boolean checkAutoOpen() {
        if (searchHost == null || !searchHost.isOneRuleHost()) return false;
        if (results.size() != 1) return false;
        SearchResult only = results.get(0);
        if (!HkStringUtil.isNotEmpty(only.getUrl())) return false;
        if (only.getUrl().startsWith("hiker://search")) return false;
        return HkStringUtil.isNotEmpty(extraField(only, "rules"));
    }

    @Override
    public void bindObjectToView(String key, SearchResult item) {
        // 原版为空实现
    }

    @Override
    public void error(String title, String msg, String code, Exception e) {
        Activity activity = getActivity();
        if (activity == null || !isAdded() || isDetached()) return;
        activity.runOnUiThread(() -> errorOnUi(title, msg, code, e));
    }

    /** 照 lambda$error$14：状态 3 + "数据获取失败-_-" 伪条目（content 为错误全文）。 */
    private void errorOnUi(String title, String msg, String code, Exception e) {
        if (!isAdded() || isDetached()) return;
        loading(false);
        if (isRefresh && smartRefreshLayout != null) {
            smartRefreshLayout.finishRefresh(false);
            smartRefreshLayout.finishLoadMore(false);
        }
        searchEngine.setStatus(3);
        EventBus.getDefault().post(new HkSearchStatusEvent(searchEngine, group));
        SearchResult pseudo = new SearchResult("数据获取失败-_-", "小棉袄");
        pseudo.setContent(getErrorMsg(title, msg, code, e));
        results.add(pseudo);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** 照 DebugUtil.getErrorMsg(4 参) 的拼串格式。 */
    static String getErrorMsg(String title, String msg, String code, Throwable e) {
        StringWriter sw = new StringWriter();
        if (e != null) e.printStackTrace(new PrintWriter(sw));
        return "错误：" + title + "   错误码：" + code + "   描述：" + msg + "   堆栈：" + sw;
    }

    // ---------------- 精准模式过滤（C6） ----------------

    /** 照原版 filterData：smode==0 不过滤；单条特殊页豁免；余下按 searchContains(title, keyWord) 逐条删。 */
    private void filterData(List<SearchResult> list) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        int smode = prefs.getInt("smode", 0);
        if (smode == 0) return;
        if (list == null || list.isEmpty()) return;
        if (list.size() == 1) {
            SearchResult only = list.get(0);
            String title = only.getTitle();
            String url = only.getUrl();
            if (title != null) {
                if (title.contains("验证码")) return;
                if (title.contains("聚合搜索")) return;
                if (title.contains("聚搜")) return;
                if (title.contains("点我")) return;
                if (title.contains("加载") && HkStringUtil.isNotEmpty(extraField(only, "id"))) return;
            }
            if (url != null && url.equals("hiker://empty")) return;
        }
        Iterator<SearchResult> it = list.iterator();
        while (it.hasNext()) {
            SearchResult r = it.next();
            String u = r.getUrl();
            if (HkStringUtil.isNotEmpty(u)) {
                if (u.startsWith("hiker://search")) continue;
                if (u.startsWith("海阔视界")) continue;
            }
            if (!HkStringUtil.searchContains(r.getTitle(), keyWord, false)) {
                it.remove();
            }
        }
    }

    /** SearchResult.extra（BaseExtra JSON）取字段（原版 getBaseExtra().getXxx() 等价）。 */
    static String extraField(SearchResult result, String field) {
        if (result == null || result.getExtra() == null) return null;
        try {
            JsonObject obj = JsonParser.parseString(result.getExtra()).getAsJsonObject();
            if (obj.has(field) && !obj.get(field).isJsonNull()) {
                return obj.get(field).getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ---------------- 条目转换 ----------------

    /** 照原版 toArticleList：SearchResult → HkItem（桥 dealUrlSimply 的条目入参；extra 原始 JSON 串透传）。 */
    static HkItem toItem(SearchResult r) {
        HkItem item = new HkItem();
        if (r == null) return item;
        item.setTitle(r.getTitle());
        item.setUrl(r.getUrl());
        item.setPic(r.getImg());
        item.setDesc(r.getDesc());
        item.setContent(r.getContent());
        item.setType(r.getType());
        // extra 已是 8.83 语义的原始 JSON 串（数字保持数字），直接透传，不再转 Map
        if (r.getExtra() != null) item.setExtra(r.getExtra());
        return item;
    }

    private String getItemTitle(int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return "";
        String t = adapter.getList().get(position).getTitle();
        return t == null ? "" : t;
    }

    /** 照 SearchFragment.getArticleListRule：引擎 + 目标 url 组装详情规则。 */
    static HkRule getArticleListRule(HkSearchEngine engine, String url, String codeAndHeader,
                                     String findRule, String colType) {
        HkRule rule = new HkRule();
        rule.setUa(engine.getUa());
        if (!url.contains(";") && HkStringUtil.isNotEmpty(codeAndHeader)) {
            rule.setUrl(url + codeAndHeader);
        } else {
            rule.setUrl(url);
        }
        rule.setFindRule(findRule);
        rule.setGroup(engine.getGroup());
        rule.setColType(colType);
        rule.setPreRule(engine.getPreRule());
        rule.setTitle(engine.getTitle());
        rule.setDisplayName(engine.getDisplayName());
        rule.setLastChapterRule(engine.getLast_chapter_rule());
        rule.setPages(engine.getPages());
        return rule;
    }

    private HkArticleFragment.DetailBridge bridge() {
        return HkArticleFragment.detailBridge();
    }

    // ---------------- 点击管线（照 SearchFragment$1 + dealUrl/dealWithUrl） ----------------

    /** 照 SearchFragment$1.onClick：伪条目两支、hiker://home 路由、余下走 dealUrl。 */
    private void onItemClick(View view, int position, String desc) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return;
        SearchResult result = adapter.getList().get(position);
        String title = result.getTitle();
        if (title != null && title.startsWith("没有找到你想要的噢-_-")) {
            if (title.contains("精准") && getContext() != null) {
                new HkDialogs(getContext())
                        .setTitle("温馨提示")
                        .setMessage("当前为精准搜索模式，可能会过滤掉一些结果，可以改为默认搜索模式查看全部搜索结果")
                        .setPositiveButton("知道了", null)
                        .show();
            }
            return;
        }
        if ("数据获取失败-_-".equals(title) && "小棉袄".equals(desc)) {
            showPosDetail(position);
            return;
        }
        String url = result.getUrl();
        if (url != null && url.startsWith("hiker://home")) {
            HkRouteBlocker.isRoute(getActivity(), url);
            return;
        }
        dealUrl(view, url, position, result);
    }

    /** 照原版 dealUrl：@lazyRule= 先行、isPageUrl 走详情规则、dealUrlSimply、x5 合成规则、@rule= 子规则、余下 dealWithUrl。 */
    private void dealUrl(View view, String url, int position, SearchResult result) {
        if (url == null) url = "";
        String[] ruleSplit = url.split("@rule=");
        String[] lazySplit = ruleSplit[0].split("@lazyRule=");
        HkArticleFragment.DetailBridge bridge = bridge();
        String codeAndHeader = bridge == null ? ""
                : bridge.getCodeAndHeader(HkHttpParser.getUrlAppendUA(orginalUrl, searchEngine.getUa()), lazySplit);
        if (lazySplit.length > 1) {
            dealLazyRule(view, position, lazySplit, codeAndHeader);
            return;
        }
        if (HkPageParser.isPageUrl(url)) {
            toNextPage(position, searchEngine, url, codeAndHeader,
                    searchEngine.getDetail_find_rule(), searchEngine.getDetail_col_type(), result);
            return;
        }
        if (bridge != null && bridge.dealUrlSimply(getActivity(), view, searchEngine.toRule(),
                toItem(result), url,
                r -> dealWithUrl(view, r, position, codeAndHeader), null)) {
            return;
        }
        if (url.startsWith("x5://")) {
            openSyntheticWebRule(url, "x5://", position);
            return;
        }
        if (ruleSplit.length > 1) {
            String ruleStr = HkParserKit.arrayToString(ruleSplit, 1, "@rule=");
            String[] seg = ruleStr.split("==>");
            String head = seg[0];
            String[] semi = head.split(";");
            String colType = searchEngine.getDetail_col_type();
            if (semi.length == 6 && !ruleStr.startsWith("js:")) {
                colType = semi[5];
                head = HkParserKit.arrayToString(semi, 0, 5, ";");
            }
            String tail = "";
            if (seg.length > 1) tail = "==>" + HkParserKit.arrayToString(seg, 1, "==>");
            dealRule(searchEngine, result, ruleSplit[0], codeAndHeader, head + tail, colType, position);
            return;
        }
        dealWithUrl(view, url, position, codeAndHeader);
    }

    /** 照原版 x5:// 支：合成 x5_webview_single 规则开页（find_rule 为 js:setResult 单条）。 */
    private void openSyntheticWebRule(String url, String prefix, int position) {
        String target = HkStringUtil.replaceOnceIgnoreCase(url, prefix, "");
        HkRule rule = new HkRule();
        rule.setUrl("hiker://empty");
        rule.setFindRule("js:setResult([{\n    url:\"" + target
                + "\",\ndesc:\"100%&&float\",\nextra:{canBack: true}\n}]);");
        rule.setColType(HkColType.X5_WEB_VIEW.code);
        rule.setGroup(searchEngine.getGroup());
        rule.setTitle(searchEngine.getTitle());
        rule.setDisplayName(searchEngine.getDisplayName());
        if (searchHost != null) {
            searchHost.openSearchRulePage(rule, getItemTitle(position));
        }
    }

    /** 照原版 dealLazyRule + 回调：Fuck 直开优先；余下桥 parseLazyRule，成功回灌 dealWithUrl。 */
    private void dealLazyRule(View view, int position, String[] lazySplit, String codeAndHeader) {
        if (HkFuck.inList(getActivity()) && HkFuck.goWeb(getContext(), myUrl, lazySplit[0])) return;
        HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
        if (bridge == null) return;
        bridge.parseLazyRule(searchEngine.toRule(), lazySplit, position, codeAndHeader, myUrl,
                new HkArticleListModel.LazyCallback() {
                    @Override
                    public void start() {
                        showLoadingDialog();
                    }

                    @Override
                    public void success(String result) {
                        dismissLoadingDialog();
                        if (result != null && result.startsWith("error:")) {
                            // 引擎真实错误（含行号），直接弹原文，不走 URL 兜底
                            HkRouteBlocker.toastShortBottom(getContext(), result);
                            return;
                        }
                        dealWithUrl(view, result, position, codeAndHeader);
                    }

                    @Override
                    public void error(String msg) {
                        dismissLoadingDialog();
                    }
                });
    }

    private android.app.AlertDialog loadingDialog;

    private void showLoadingDialog() {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() -> {
            dismissLoadingDialog();
            android.widget.TextView text = new android.widget.TextView(activity);
            text.setText("动态解析规则中，请稍候");
            text.setTextSize(15);
            text.setTextColor(0xFF222222);
            text.setPadding(48, 40, 48, 40);
            loadingDialog = new android.app.AlertDialog.Builder(activity)
                    .setView(text).setCancelable(false).create();
            loadingDialog.show();
        });
    }

    private void dismissLoadingDialog() {
        Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            if (loadingDialog != null) {
                loadingDialog.dismiss();
                loadingDialog = null;
            }
        });
    }

    /** 照原版 dealRule：组装详情规则 + params=条目 extra → 经宿主开页。 */
    private void dealRule(HkSearchEngine engine, SearchResult result, String url, String codeAndHeader,
                          String findRule, String colType, int position) {
        HkRule rule = getArticleListRule(engine, url, codeAndHeader, findRule, colType);
        SearchResult item = adapter.getList().get(position);
        if (HkStringUtil.isNotEmpty(item.getExtra())) {
            rule.setParams(item.getExtra());
        }
        if (searchHost != null) {
            searchHost.openSearchRulePage(rule, getItemTitle(position));
        }
    }

    /** 照原版 toNextPage：后台 getArticleListRule + PageParser.getNextPage（extra 为 paramsJson），UI 线程经宿主开页。 */
    private void toNextPage(int position, HkSearchEngine engine, String url, String codeAndHeader,
                            String findRule, String colType, SearchResult result) {
        Task.execute(() -> {
            try {
                HkRule rule = getArticleListRule(engine, url, codeAndHeader, findRule, colType);
                HkRule next = HkPageParser.getNextPage(rule, url, result.getExtra());
                HkRule open = next != null ? next : rule;
                Activity activity = getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.runOnUiThread(() -> {
                        if (searchHost != null) {
                            searchHost.openSearchRulePage(open, getItemTitle(position));
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "hk error", e);
                Activity activity = getActivity();
                if (activity != null && !activity.isFinishing()) {
                    activity.runOnUiThread(() ->
                            HkRouteBlocker.toastShortBottom(activity, String.valueOf(e.getMessage())));
                }
            }
        });
    }

    /** 照原版 dealWithUrl 的主分支集（与文章页同构，条目为 SearchResult）。 */
    private void dealWithUrl(View view, String url, int position, String codeAndHeader) {
        if (position < 0 || adapter == null || position >= adapter.getList().size()) return;
        if (getContext() == null || getActivity() == null) return;
        HkArticleFragment.DetailBridge bridge = bridge();
        SearchResult result = adapter.getList().get(position);
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (HkPageParser.isPageUrl(url)) {
            toNextPage(position, searchEngine, url, codeAndHeader,
                    searchEngine.getDetail_find_rule(), searchEngine.getDetail_col_type(), result);
            return;
        }
        if (bridge != null && bridge.dealUrlSimply(getActivity(), view, searchEngine.toRule(),
                toItem(result), url,
                r -> dealWithUrl(view, r, position, codeAndHeader), null)) {
            return;
        }
        if (lower.startsWith("javascript")) {
            if (bridge != null) bridge.goWeb(getContext(), url);
            return;
        }
        if (url.startsWith("x5://")) {
            openSyntheticWebRule(url, "x5://", position);
            return;
        }
        if (bridge != null && bridge.canParseX5(url)) {
            if (HkFuck.inList(getActivity()) && HkFuck.goWeb(getContext(), myUrl, url)) return;
            boolean started = bridge.parseX5(getActivity(), url, result.getExtra(),
                    r -> dealWithUrl(view, r, position, codeAndHeader));
            if (started) showLoadingDialog();
            return;
        }
        if (bridge != null && bridge.canParseWebkit(url)) {
            if (HkFuck.inList(getActivity())) {
                HkFuck.goWeb(getContext(), myUrl, url);
                return;
            }
            boolean started = bridge.parseWebkit(getActivity(), url, result.getExtra(),
                    r -> dealWithUrl(view, r, position, codeAndHeader));
            if (started) showLoadingDialog();
            return;
        }
        if (lower.startsWith("pics://")) {
            String body = url.substring("pics://".length());
            String[] parts = body.split("&&");
            List<String> pics = new ArrayList<>();
            for (String part : parts) {
                if (!part.isEmpty()) pics.add(part);
            }
            HkArticleFragment.PictureListRequest req = new HkArticleFragment.PictureListRequest();
            req.pics = new ArrayList<>(pics);
            req.url = myUrl;
            req.title = getItemTitle(position);
            req.chapters = getChapters(url, position, "", codeAndHeader);
            req.cUrl = searchEngine.getUrl();
            req.mTitle = getItemTitle(position);
            int now = 0;
            for (int i = 0; i < req.chapters.size(); i++) {
                if (req.chapters.get(i).use) now = i;
            }
            req.nowPage = now;
            if (bridge != null) bridge.openPictureList(getActivity(), req);
            return;
        }
        if (url.startsWith("magnet:?") || url.startsWith("thunder://")) {
            if (bridge != null) bridge.startDownloadMagnet(getContext(), url);
            return;
        }
        if (url.split(";")[0].endsWith(".torrent")) {
            if (bridge != null) bridge.startParseTorrent(getContext(), url);
            return;
        }
        if (bridge != null && bridge.isFTPOrEd2k(url)) {
            bridge.startParseFTPOrEd2k(getContext(), url, null);
            return;
        }
        if (bridge != null && bridge.isScheme(url)) {
            bridge.findChooserToDeal(getContext(), url);
            return;
        }
        if (bridge != null && bridge.isImage(url)) {
            List<String> urls = new ArrayList<>();
            urls.add(bridge.clearTag(url));
            ImageView srcView = view instanceof ImageView ? (ImageView) view : null;
            if (srcView != null) {
                bridge.showSingleImage(bridge.clearTag(url), srcView);
            } else {
                bridge.showImageViewer(urls, 0, null, null);
            }
            return;
        }
        if (bridge != null && bridge.isVideoOrMusic(url)) {
            if (url.startsWith("video://") && HkFuck.inList(getActivity())
                    && HkFuck.goWeb(getContext(), myUrl, url)) return;
            String cleared = bridge.clearTag(url);
            String itemCleared = bridge.clearTag(result.getUrl());
            if (!cleared.equals(itemCleared) && !TextUtils.isEmpty(codeAndHeader)
                    && !bridge.canParseX5(result.getUrl()) && !bridge.canParseWebkit(result.getUrl())
                    && HkListActivity.playDataOf(url) == null) {
                bridge.startPlayerSingle(getActivity(), getItemTitle(position), url);
                return;
            }
            String actTitle = getActivity().getTitle() == null ? "" : String.valueOf(getActivity().getTitle());
            String titlePrefix = actTitle.isEmpty() ? "" : actTitle + "-";
            List<HkArticleFragment.HkChapter> chapters = getChapters(url, position, titlePrefix, codeAndHeader);
            android.os.Bundle bundle = new android.os.Bundle();
            String vc = bridge.getViewCollectionExtraData(getActivity(), searchEngine.getUrl());
            if (!TextUtils.isEmpty(vc)) bundle.putString("viewCollectionExtraData", vc);
            if (getActivity().getTitle() != null) {
                bundle.putString("film", String.valueOf(getActivity().getTitle()));
            }
            bundle.putString("rule", new Gson().toJson(searchEngine.toRule()));
            bridge.startPlayerChapters(getActivity(), chapters, searchEngine.getUrl(), actTitle, bundle);
            return;
        }
        HkRouteBlocker.toastShortBottom(getContext(), "未知链接：" + url);
    }

    /**
     * 照原版 getChapters 的同型分组算法（SearchResult 版）：
     * 以点击项为中心，向前后收同 type 的条目为选集，点击项 use=true。
     */
    private List<HkArticleFragment.HkChapter> getChapters(String url, int position,
                                                          String titlePrefix, String codeAndHeader) {
        List<HkArticleFragment.HkChapter> chapters = new ArrayList<>();
        List<SearchResult> list = adapter.getList();
        SearchResult clicked = list.get(position);
        String type = clicked.getType();
        for (int i = position - 1; i >= 0; i--) {
            if (!TextUtils.equals(type, list.get(i).getType())) break;
            chapters.add(0, buildChapter(list.get(i), i, titlePrefix, codeAndHeader, false));
        }
        HkArticleFragment.HkChapter cur = buildChapter(clicked, position, titlePrefix, codeAndHeader, true);
        cur.url = url;
        chapters.add(cur);
        for (int i = position + 1; i < list.size(); i++) {
            if (!TextUtils.equals(type, list.get(i).getType())) break;
            chapters.add(buildChapter(list.get(i), i, titlePrefix, codeAndHeader, false));
        }
        return chapters;
    }

    private HkArticleFragment.HkChapter buildChapter(SearchResult r, int pos, String titlePrefix,
                                                     String codeAndHeader, boolean use) {
        HkArticleFragment.HkChapter ch = new HkArticleFragment.HkChapter();
        ch.memoryTitle = r.getTitle();
        ch.title = titlePrefix + (r.getTitle() == null ? "" : r.getTitle());
        ch.url = r.getUrl();
        ch.use = use;
        ch.realPos = pos;
        ch.extra = r.getExtra();
        ch.pic = r.getImg();
        if (!TextUtils.isEmpty(codeAndHeader)) {
            ch.codeAndHeader = codeAndHeader;
            ch.originalUrl = r.getUrl();
        }
        return ch;
    }

    // ---------------- 长按菜单与调试数据（照 SearchFragment$1.onLongClick） ----------------

    private void onItemLongClick(View view, int position, String desc) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return;
        SearchResult result = adapter.getList().get(position);
        String url = result.getUrl();
        List<String> options = new ArrayList<>();
        options.add("调试数据");
        options.add("复制链接");
        options.add("网页搜索");
        options.add("刷新页面");
        options.add("网页查看");
        if (url != null && (url.startsWith("magnet:") || url.startsWith("thunder://")
                || url.split(";")[0].endsWith(".torrent")
                || (bridge() != null && bridge().isVideoOrMusic(url)))) {
            options.add("下载资源");
        }
        if (getContext() == null) return;
        new HkCenterListPopup(getContext())
                .withTitle("请选择操作")
                .with(options, 3, new HkCenterListPopup.ClickListener() {
                    @Override
                    public void click(String text, int pos) {
                        onLongMenuSelected(result, position, text);
                    }

                    @Override
                    public void onLongClick(String text, int pos) {
                    }
                })
                .show();
    }

    private void onLongMenuSelected(SearchResult result, int position, String text) {
        HkArticleFragment.DetailBridge bridge = bridge();
        String url = result.getUrl();
        switch (text) {
            case "调试数据":
                showPosDetail(position);
                break;
            case "复制链接":
                copyToClipboard(url == null ? "" : HkStringUtil.getFirstPageUrl(url));
                HkRouteBlocker.toastShortBottom(getContext(), "已复制链接");
                break;
            case "网页搜索":
                if (bridge != null && url != null) bridge.goWeb(getContext(), url);
                break;
            case "刷新页面":
                page = 1;
                isRefresh = true;
                initData();
                break;
            case "网页查看":
                if (url != null && (url.startsWith("http") || url.startsWith("webview://")
                        || url.startsWith("hiker://empty#http") || url.startsWith("hiker://empty##http"))) {
                    if (bridge != null) bridge.goWeb(getContext(), HkStringUtil.getRealUrl(url));
                } else {
                    HkRouteBlocker.toastShortBottom(getContext(), "当前链接不支持网页查看");
                }
                break;
            case "下载资源":
                downloadResult(result, position);
                break;
            default:
                break;
        }
    }

    private void downloadResult(SearchResult result, int position) {
        HkArticleFragment.DetailBridge bridge = bridge();
        if (bridge == null) return;
        String url = result.getUrl();
        if (TextUtils.isEmpty(url)) {
            HkRouteBlocker.toastShortBottom(getContext(), "链接为空，请检查规则");
            return;
        }
        if (bridge.isCannotHandleScheme(url) || url.split(";")[0].endsWith(".torrent")) {
            bridge.findChooserToDeal(getContext(), url);
            return;
        }
        String title = getItemTitle(position);
        if (!bridge.isVideoOrMusic(url)) {
            HkRouteBlocker.toastShortBottom(getContext(), title + "非视频格式，不支持下载");
            return;
        }
        String tagged = bridge.clearTag(url);
        java.util.Map<String, String> headers = HkHttpParser.getHeaders(tagged);
        String real = HkHttpParser.getRealUrlFilterHeaders(tagged);
        bridge.showDownloadDialog(getActivity(), title, real, title, headers);
    }

    /** 照原版 showPosDetail：FileDetailPopup 等价（原生对话框列出调试字段，点行复制）。 */
    private void showPosDetail(int position) {
        if (getContext() == null || adapter == null
                || position < 0 || position >= adapter.getList().size()) return;
        String[] data = getDetailData(position);
        new HkDialogs(getContext())
                .setTitle("调试数据")
                .setItems(data, (dialog, which) -> copyToClipboard(data[which]))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 照 ClipboardUtil.copyToClipboardForce：直接写主剪贴板。 */
    private void copyToClipboard(String text) {
        if (getContext() == null) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", text));
        }
    }

    /** 照原版 getDetailData：页面（>200 截断）/标题/描述/详情/类型/链接/图片/附加 八行。 */
    private String[] getDetailData(int position) {
        SearchResult r = adapter.getList().get(position);
        String url = r.getUrl() == null ? "" : r.getUrl();
        String pageText = url.length() > 200 ? url.substring(0, 200) + "..." : url;
        return new String[]{
                "页面：" + pageText,
                "标题：" + nullToEmpty(r.getTitle()),
                "描述：" + nullToEmpty(r.getDesc()),
                "详情：" + nullToEmpty(r.getContent()),
                "类型：" + nullToEmpty(r.getType()),
                "链接：" + url,
                "图片：" + nullToEmpty(r.getImg()),
                "附加：" + nullToEmpty(r.getExtra()),
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
