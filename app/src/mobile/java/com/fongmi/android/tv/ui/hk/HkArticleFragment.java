package com.fongmi.android.tv.ui.hk;

import android.graphics.drawable.Animatable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.ui.hk.view.MyWebProgress;
import com.fongmi.android.tv.ui.hk.view.RecyclerFastScroller;
import com.fongmi.android.tv.utils.Task;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * 照 8.83 ArticleListFragment 转写（切片 1：骨架/初始化/刷新/加载态）。
 * 原版经 BaseFragment 的 initLayout/initView/initData 三段初始化，此处内联到 onViewCreated。
 * 数据经 HkArticleListModel（对应 ArticleListModel）.params(...).process("ARTICLE_LIST_NEW", 本 Fragment) 回灌。
 */
public class HkArticleFragment extends Fragment implements HkArticleListModel.Callback {

    private static final String TAG = "ArticleListFragment";

    // —— 原版字段（切片 1 用到的核心集，其余子系统字段随各切片补入）——
    private HkArticleAdapter adapter;
    private HkArticleListModel articleListModel;
    private HkRule articleListRule;
    private HkRule articleListRuleDTO;
    private int bgViewHeight;
    private boolean cannotRefresh;
    private String currentPageUrl;
    private RecyclerFastScroller fastScroller;
    private String firstPageData;
    private GridLayoutManager gridLayoutManager;
    private boolean hasShowLastClick;
    private int headerHeight;
    private int headerSize = -1;
    private boolean immersiveTheme;
    private boolean isFullTheme;
    private boolean isLoading;
    private boolean isReadTheme;
    private String lastPageData;
    private int lastSelectedPosition;
    private int lastVisibleItem;
    /** 对应原版 ArticleListFragment$LoadListener（只有 complete()）。 */
    public interface LoadListener {
        void complete();
    }

    private LoadListener loadListener;
    private int loadingPage;
    private int maxLoadedPage;
    private boolean memoryPos = true;
    private String myUrl = "";
    private String nextPageUrl;
    private boolean noHistory;
    private boolean noRecordHistory;
    private View notify_bg;
    private TextView notify_text;
    private String onCloseJS;
    private String onRefreshJS;
    private String originalPageUrl;
    private int page = 1;
    private AppCompatImageView progressView1;
    private MyWebProgress progress_bar;
    private RecyclerView recyclerView;
    private int scrollFlag = -2;
    private RecyclerView.OnScrollListener scrollListener;
    private int scrollPos = -1;
    private SmartRefreshLayout smartRefreshLayout;
    private RelativeLayout webViewContainer;
    private HkWebViewHolder webViewHolder;

    /**
     * 照抄原版 instance(ArticleListRule)：克隆规则并归一化——col_type 空补 movie_3；
     * firstHeader 取首个非空分类组（class→area→year→sort，全空回 "class"）；
     * DTO 再克隆一份，剥 #noHistory#/#noRecordHistory# 标记（#autoPage# 只置历史标记不剥），
     * 四组导航 url 只保留 & 分隔的第一段。
     */
    public HkArticleFragment instance(HkRule rule) {
        HkRule dto;
        try {
            dto = rule.copyRule();
        } catch (Exception e) {
            dto = rule;
        }
        if (TextUtils.isEmpty(dto.getColType())) {
            dto.setColType(HkColType.MOVIE_3.code);
        } else {
            dto.setColType(dto.getColType());
        }
        if (!TextUtils.isEmpty(dto.getClassName())) dto.setFirstHeader("class");
        else if (!TextUtils.isEmpty(dto.getAreaName())) dto.setFirstHeader("area");
        else if (!TextUtils.isEmpty(dto.getYearName())) dto.setFirstHeader("year");
        else if (!TextUtils.isEmpty(dto.getSortName())) dto.setFirstHeader("sort");
        else dto.setFirstHeader("class");
        this.articleListRule = dto;
        try {
            this.articleListRuleDTO = dto.copyRule();
            originalPageUrl = rule.getUrl();
            currentPageUrl = rule.getUrl();
            HkRule d = this.articleListRuleDTO;
            if (TextUtils.isEmpty(d.getUrl())) {
                noHistory = true;
                noRecordHistory = true;
            } else {
                if (d.getUrl().contains("#noHistory#")) {
                    noHistory = true;
                    d.setUrl(d.getUrl().replaceFirst(java.util.regex.Pattern.quote("#noHistory#"), ""));
                }
                if (d.getUrl().contains("#noRecordHistory#")) {
                    noRecordHistory = true;
                    d.setUrl(d.getUrl().replaceFirst(java.util.regex.Pattern.quote("#noRecordHistory#"), ""));
                }
                if (d.getUrl().contains("#autoPage#")) {
                    noRecordHistory = true;
                    noHistory = true;
                }
            }
            if (!TextUtils.isEmpty(d.getAreaUrl())) d.setAreaUrl(d.getAreaUrl().split("&")[0]);
            if (!TextUtils.isEmpty(d.getClassUrl())) d.setClassUrl(d.getClassUrl().split("&")[0]);
            if (!TextUtils.isEmpty(d.getYearUrl())) d.setYearUrl(d.getYearUrl().split("&")[0]);
            if (!TextUtils.isEmpty(d.getSortUrl())) d.setSortUrl(d.getSortUrl().split("&")[0]);
        } catch (Exception ignored) {
        }
        return this;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_article_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView(view);
        initData();
    }

    /** 照抄原版 initData：先亮加载态；cacheOnly 命中缓存时直接返回（缓存子系统随切片补入）；否则走模型取数。 */
    protected void initData() {
        loading(true);
        loadingPage = page;
        articleListModel.params(requireContext(), page, false, articleListRuleDTO,
                        notLoadPreRule(), isFromHistory())
                .process("ARTICLE_LIST_NEW", this);
    }

    /** 照抄原版 initView：找齐控件、装 GridLayoutManager(60)+SpanSizeLookup、刷新/加载更多、FastScroller、通知条、进度条。 */
    protected void initView(View root) {
        recyclerView = root.findViewById(R.id.frag_article_list_recycler_view);
        recyclerView.post(() -> {
            View bg = root.findViewById(R.id.bg);
            if (bg != null && getContext() != null) {
                bgViewHeight = pxToDp(bg.getMeasuredHeight());
            }
        });
        progressView1 = root.findViewById(R.id.progress_image_view1);
        ((Animatable) progressView1.getDrawable()).start();
        webViewContainer = root.findViewById(R.id.webview_container);
        gridLayoutManager = new GridLayoutManager(getContext(), 60);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (adapter == null) return 60;
                if (recyclerView.getAdapter() != adapter) return 60;
                try {
                    int viewType = adapter.getItemViewType(position);
                    if (viewType == -10) return 0;
                    if (getActivity() != null && getActivity().getResources().getConfiguration()
                            .orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                        return HkColType.landSpanCountByItemType(viewType);
                    }
                    return HkColType.spanCountByItemType(viewType);
                } catch (Exception e) {
                    return 60;
                }
            }
        });
        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        articleListModel = new HkArticleListModel()
                .withUrlParseCallBack(url -> myUrl = url);
        smartRefreshLayout = root.findViewById(R.id.refresh_layout);
        cannotRefresh = articleListRule != null
                && !TextUtils.isEmpty(articleListRule.getUrl())
                && articleListRule.getUrl().contains("#noRefresh#");
        if (cannotRefresh) {
            smartRefreshLayout.setEnableRefresh(false);
        } else {
            smartRefreshLayout.setOnRefreshListener(refreshLayout -> refresh());
        }
        smartRefreshLayout.setEnableAutoLoadMore(true);
        smartRefreshLayout.setOnLoadMoreListener(refreshLayout -> onLoadMore());
        fastScroller = root.findViewById(R.id.frag_article_list_recycler_scroller);
        fastScroller.attachRecyclerView(recyclerView);
        initAdapter();
        fastScroller.setTouchTargetWidth(10);
        fastScroller.setMarginLeft(10);
        fastScroller.setMinItemCount(isReadTheme ? 0x1e8480 : 200);
        fastScroller.setBarColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                android.R.color.transparent));
        android.graphics.drawable.Drawable handle = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.fastscroll_handle);
        fastScroller.setDrawable(handle, handle);
        fastScroller.setMaxScrollHandleHeight(dpToPx(40));
        fastScroller.setPressedListener(pressed -> onFastScrollPressed(pressed));
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this);
        notify_bg = root.findViewById(R.id.notify_bg);
        notify_text = notify_bg.findViewById(R.id.notify_text);
        notify_bg.findViewById(R.id.notify_queue).setOnClickListener(v -> onNotifyQueueClick());
        notify_bg.setOnClickListener(v -> onNotifyClick());
        notify_bg.setOnLongClickListener(v -> onNotifyLongClick());
        progress_bar = root.findViewById(R.id.progress_bar);
        progress_bar.setColor(androidx.core.content.ContextCompat.getColor(requireContext(),
                R.color.progress_blue));
        if (scrollListener != null) {
            recyclerView.addOnScrollListener(scrollListener);
            fastScroller.setScrollListener(offset -> onFastScrollOffset(offset));
        }
    }

    /**
     * 照抄原版 finishParse(DestroyEvent)：WebView 嗅探桥 fy_bridge_app.finishParse 投递的事件，
     * 页面可见时按 mode 路由到对应 Parser 的 finishParse（ticket 在 Parser 内校验）。
     */
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void finishParse(HkDestroyEvent event) {
        if (getActivity() != null && !getActivity().isFinishing() && getUserVisibleHint()) {
            if ("webkit".equals(event.getMode())) {
                HkWebkitParser.finishParse(getContext(), event.getUrl(), event.getTicket());
            } else {
                HkX5WebViewParser.finishParse(getContext(), event.getUrl(), event.getTicket());
            }
        }
    }

    /**
     * 照抄原版 onRefreshWebView(OnRefreshWebViewEvent)：行内 WebView 在场时 setUrl + loadUrl 刷新目标页；
     * http 链接再显示顶部进度（原版看 X5Extra.showProgress，星辰按 HkX5Extra 默认 true 处理）。
     */
    @org.greenrobot.eventbus.Subscribe(threadMode = org.greenrobot.eventbus.ThreadMode.MAIN)
    public void onRefreshWebView(HkDetailBridge.RefreshWebViewEvent event) {
        if (!isResumed() || event == null) return;
        if (webViewHolder == null || webViewHolder.getWebView() == null) return;
        webViewHolder.setUrl(event.url);
        webViewHolder.getWebView().loadUrl(event.url);
        if (HkStringUtil.isNotEmpty(event.url) && event.url.startsWith("http")
                && progress_bar != null) {
            progress_bar.show();
        }
    }

    /** 照抄原版 initAdapter。 */
    private void initAdapter() {
        adapter = new HkArticleAdapter(requireContext(), recyclerView, new ArrayList<>()) {
        };
        adapter.setImmersiveTheme(immersiveTheme);
        fastScroller.attachAdapter(adapter);
        adapter.setOnItemClickListener(new HkArticleAdapter.OnItemClickListener() {
            @Override
            public void onClassClick(View view, String url, String desc) {
                HkArticleFragment.this.onClassClick(view, url, desc);
            }

            @Override
            public void onClick(View view, int position) {
                onItemClick(view, position);
            }

            @Override
            public void onCodeClick(View view, int position, String code) {
                HkArticleFragment.this.onCodeClick(view, position, code);
            }

            @Override
            public void onLoadMore(View view, int position) {
                HkArticleFragment.this.onLoadMoreFooter(view, position);
            }

            @Override
            public void onLongClick(View view, int position) {
                HkArticleFragment.this.onItemLongClick(view, position);
            }

            @Override
            public void onUrlClick(View view, int position, String url) {
                onItemUrlClick(view, position, url);
            }
        });
        // 宿主接线（适配器钩子）：规则信息与引擎/建议/富文本/行内 WebView
        if (articleListRuleDTO != null) {
            adapter.setPageUrl(articleListRuleDTO.getUrl());
            adapter.setRuleTitle(articleListRuleDTO.getTitle());
            adapter.setRuleHeaderInfo(articleListRuleDTO.getUrl(), articleListRuleDTO.getFirstHeader());
        }
        adapter.setWebViewHolder(webViewHolder);
        // 照原版 initAdapter 的三钩子接线：输入 onChange 求值（引擎单例）、百度建议词、富文本动作
        adapter.setOnInputEvalListener((onChangeJs, text) -> {
            HkRule r = articleListRuleDTO;
            if (r == null) return;
            try {
                com.fongmi.android.tv.api.hk.HkJsRuntime rt = HkEngineManager.runtimeFor(r);
                HkEngineManager.ensureInit(rt);
                rt.evalJS(com.fongmi.android.tv.api.hk.HkJsRuntime.getMyRule(r) + onChangeJs, text);
            } catch (Exception e) {
                timber.log.Timber.e(e);
            }
        });
        adapter.setSuggestLoader(HkSuggestModel::loadFromRemote);
        adapter.setOnRichTextActionListener(new HkArticleAdapter.OnRichTextActionListener() {
            @Override
            public void onImageClick(java.util.List<String> urls, int position) {
                if (detailBridge != null) detailBridge.showImageViewer(urls, position, null, null);
            }

            @Override
            public boolean checkCodeImport(String trimmedText) {
                return HkDetailBridge.hooks != null
                        && HkDetailBridge.hooks.checkText(getActivity(), trimmedText);
            }
        });
        recyclerView.setAdapter(adapter);
        recyclerView.addItemDecoration(adapter.getDividerItem());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                // 照原版 Fragment$6：滚动时记录最后可见位置（其余为阅读进度跟踪，阅读子系统不移植）
                if (gridLayoutManager != null) {
                    lastVisibleItem = gridLayoutManager.findLastVisibleItemPosition();
                }
                onListScrolled(dx, dy);
            }
        });
    }

    /** 宿主承接原版 OnRefreshPageEvent（子页以请求码 2 返回 refreshPage 时）：scrollTop 先回顶，再刷新。 */
    public void refreshFromHost(boolean scrollTop) {
        if (scrollTop && recyclerView != null) recyclerView.scrollToPosition(0);
        refresh();
    }

    /** 照抄原版 refresh()：回第一页；onRefreshJS 非空时先在后台跑该 JS（切片 2 补全后续），否则直接走模型。 */
    private void refresh() {
        page = 1;
        loading(true);
        if (onRefreshJS != null && !onRefreshJS.isEmpty()) {
            Task.execute(() -> runOnRefreshJs());
            return;
        }
        loadingPage = page;
        articleListModel.params(requireContext(), page, true, articleListRuleDTO,
                        notLoadPreRule(), isFromHistory())
                .process("ARTICLE_LIST_NEW", this);
    }

    /** 照抄原版 loading(boolean)：结束时收刷新/加载圈；开始且在第一页时亮顶部 AVD 进度线。 */
    @Override
    public void loading(boolean show) {
        if (!show) {
            try {
                if (smartRefreshLayout != null) {
                    smartRefreshLayout.finishRefresh(true);
                    smartRefreshLayout.finishLoadMore(true);
                }
            } catch (Exception ignored) {
            }
        }
        isLoading = show;
        try {
            if (show) {
                if (page == 1) {
                    progressView1.setVisibility(View.VISIBLE);
                    ((Animatable) progressView1.getDrawable()).start();
                }
            } else {
                progressView1.setVisibility(View.INVISIBLE);
                ((Animatable) progressView1.getDrawable()).stop();
            }
        } catch (Exception ignored) {
        }
    }

    // —— BaseCallback 数据回灌（切片 2：照 ArticleListFragment.bindArrayToView + lambda$bindArrayToView$43 转写）——

    /** 宿主回调：对应原版宿主 MainActivity.onChildChanged 与 ArticleListIsland.hideLoading（宿主才有）。 */
    public interface HostListener {
        void onChildChanged(HkArticleFragment fragment, boolean hasWebView);

        default void hideLoading() {
        }

        /** 对应原版 memoryPosition 的记录本体（历史/续看位置），宿主阶段承接。 */
        default void onMemoryPosition(int position) {
        }

        /** 对应原版 func://background 时 FilmListActivity.background()，宿主阶段承接。 */
        default void onBackground() {
        }

        /** 对应原版 showLastClick 首调的历史恢复查询：先 ViewCollection（CUrl+MTitle）后 ViewHistory（url+title+二级列表），返回 lastClick 串，无则 null。 */
        default String onQueryLastClick(String ruleUrl, String title) {
            return null;
        }

        /** 对应原版 FilmListActivity.showLastClick(LastClickShowEvent)：宿主侧的上次点击提示呈现。 */
        default void onShowLastClick(String lastClick) {
        }

        /** 对应原版 updateAutoPageLastClick：#autoPage# 续页时宿主侧记录父页 lastClick。 */
        default void onAutoPageLastClick(AutoPageData data, HkItem item, int globalPos) {
        }
    }

    private HostListener hostListener;
    private boolean mainHost;
    private boolean listHost;
    private int cacheMergeState;
    private String autoCacheMd5;
    private String cacheCode;

    public void setHostListener(HostListener hostListener) {
        this.hostListener = hostListener;
        this.mainHost = hostListener != null;
    }

    /** 对应原版 activity instanceof FilmListActivity 的判定（子页面宿主时为 true）。 */
    public void setListHost(boolean listHost) {
        this.listHost = listHost;
    }

    @Override
    public void bindArrayToView(String key, List<HkItem> list) {
        bindArrayToView(key, list, false);
    }

    /** 照抄原版 bindArrayToView(String, List, boolean)。 */
    public void bindArrayToView(String key, List<HkItem> list, boolean fromCache) {
        if (getContext() == null) return;
        if (fromCache && adapter != null && !adapter.getList().isEmpty()) return;
        boolean isNew = !TextUtils.isEmpty(key) && key.endsWith("ARTICLE_LIST_NEW");
        boolean isPre = !TextUtils.isEmpty(key) && key.startsWith("ARTICLE_LIST_PRE");
        boolean isAfter = !TextUtils.isEmpty(key) && key.startsWith("ARTICLE_LIST_AFTER");
        if (!fromCache && isNew && !TextUtils.isEmpty(autoCacheMd5) && !isPre && !isAfter) {
            saveAutoCache(list);
        }
        updateReadData(isNew && !isAfter, list);
        if (isNew) hookHomeData(list);
        if (isNew && !isPre && listHost) injectReverseMode(list);
        String articlesIdStr = getArticlesIdStr(list);
        updateWebViewHolder(list);
        injectTextConfig(list);
        updateReadLayout(list);
        try {
            runWaitUI(() -> applyListToView(isNew, isPre, isAfter, articlesIdStr, fromCache, list, key));
        } catch (Exception ignored) {
        }
    }

    /** 照抄原版 lambda$bindArrayToView$43（UI 线程体）：分页状态收尾、宿主通知、收刷新圈，再按 NEW/合并/PRE/AFTER 分路落表。 */
    private void applyListToView(boolean isNew, boolean isPre, boolean isAfter, String idStr,
                                 boolean fromCache, List<HkItem> list, String key) {
        try {
            if (!isNew) {
                if (isPre) maxLoadedPage = page;
                else maxLoadedPage = Math.max(maxLoadedPage, loadingPage);
                loadingPage = -1;
                isLoading = false;
            }
            if (mainHost && hostListener != null) {
                hostListener.onChildChanged(this, webViewHolder != null);
            }
            if (smartRefreshLayout != null) {
                smartRefreshLayout.finishRefresh(true);
                smartRefreshLayout.finishLoadMore(true);
            }
            if (!isPre && !isAfter) {
                firstPageData = idStr;
                boolean merged = false;
                if (fromCache) {
                    cacheMergeState = 1;
                    clearGlideMemory();
                    updateWebView(false);
                } else if (cacheMergeState == 1) {
                    updateWebView(true);
                    list.addAll(0, initHeaders());
                    if (mainHost) list.add(initFooter());
                    merged = mergeCachedList(list);
                    cacheMergeState = 2;
                } else {
                    updateWebView(false);
                }
                if (cacheMergeState <= 0) clearGlideMemory();
                if (!merged) replaceListWith(list);
            } else if ("ARTICLE_LIST".equals(key) || isAfter) {
                updateWebView(true);
                if (headerSize == -1) headerSize = initHeaders().size();
                List<HkItem> cur = adapter.getList();
                if (cur.isEmpty()) list.addAll(0, initHeaders());
                int insertBase = cur.size() - 1;
                boolean canInsert = list != null && !list.isEmpty() && idStr != null
                        && !idStr.equals(firstPageData) && !idStr.equals(lastPageData);
                if (canInsert && idStr.length() > 50) {
                    String sub = idStr.substring(1);
                    if (!TextUtils.isEmpty(firstPageData) && firstPageData.endsWith(sub)) {
                        canInsert = false;
                    } else if (!TextUtils.isEmpty(lastPageData) && lastPageData.endsWith(sub)) {
                        canInsert = false;
                    }
                }
                if (canInsert) {
                    if (mainHost) {
                        list.add(initFooter());
                        if (!cur.isEmpty() && HkColType.BIG_BIG_BLANK_BLOCK.code.equals(
                                cur.get(cur.size() - 1).getType())) {
                            cur.remove(cur.size() - 1);
                        }
                    }
                    cur.addAll(list);
                    adapter.notifyItemRangeChanged(insertBase, list.size());
                }
                if (isPre) firstPageData = idStr;
                else lastPageData = idStr;
            }
            showLastClick();
            if (loadListener != null) loadListener.complete();
        } catch (Exception ignored) {
        } finally {
            loading(false);
        }
    }

    /** 缓存合并路（原版 lambda$43 的 cacheMergeState==1 分支）：与现有列表做最长公共前缀扫描，尾部按 hasFlex 决定整表/区间通知，再插入增量。 */
    private boolean mergeCachedList(List<HkItem> list) {
        List<HkItem> cur = adapter.getList();
        int lastMatch = -1;
        int i = 0;
        while (i < cur.size() && i < list.size()) {
            if (!itemsEqual(cur.get(i), list.get(i))) break;
            lastMatch = i;
            i++;
        }
        if (lastMatch < 0) return false;
        if (list.size() > lastMatch + 1) {
            if (lastMatch + 1 < cur.size()) {
                int count = cur.size() - lastMatch - 1;
                boolean hasFlex = adapter.hasFlex(lastMatch + 1, count);
                for (int j = cur.size() - 1; j > lastMatch; j--) cur.remove(j);
                if (hasFlex) adapter.notifyDataChanged();
                else adapter.notifyItemRangeRemoved(lastMatch + 1, count);
            }
            List<HkItem> sub = new ArrayList<>(list.subList(lastMatch + 1, list.size()));
            int insertPos = cur.size();
            cur.addAll(sub);
            adapter.notifyItemRangeInserted(insertPos, sub.size());
        } else if (cur.size() > lastMatch + 1) {
            int count = cur.size() - lastMatch - 1;
            for (int j = cur.size() - 1; j > lastMatch; j--) cur.remove(j);
            adapter.notifyItemRangeRemoved(lastMatch + 1, count);
        }
        return true;
    }

    /** 常规落表路（原版 lambda$43 的 goto_b 段）：保留已有 header 组、清掉其后旧内容，再接新列表。 */
    private void replaceListWith(List<HkItem> list) {
        List<HkItem> cur = adapter.getList();
        int oldSize = cur.size();
        if (oldSize == 0) list.addAll(0, initHeaders());
        boolean foundHeader = false;
        for (int i = cur.size() - 1; i >= 0; i--) {
            try {
                if ("header".equals(cur.get(i).getType())) foundHeader = true;
                else if (!foundHeader) cur.remove(i);
            } catch (Exception ignored) {
            }
        }
        if (oldSize != 0 && cur.size() == 0) list.addAll(0, initHeaders());
        if (mainHost) list.add(initFooter());
        adapter.notifyDataChanged();
        int pos = cur.size() - 1;
        cur.addAll(list);
        adapter.notifyItemRangeChanged(pos, list.size());
    }

    /** 照抄原版 initHeaders：非沉浸且非子页面宿主时先垫一个 blank_block；四组分类各生成一个 header 条目。 */
    private synchronized List<HkItem> initHeaders() {
        List<HkItem> headers = new ArrayList<>();
        if (!immersiveTheme && !listHost) {
            HkItem blank = new HkItem();
            blank.setType(HkColType.BLANK_BLOCK.code);
            headers.add(blank);
        }
        if (immersiveTheme && getView() != null) {
            View container = getView().findViewById(R.id.bodyContainer);
            if (container != null) {
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) container.getLayoutParams();
                lp.setMargins(0, 0, 0, 0);
            }
        }
        // 原版此处还有隐私规则替换判定（SettingConfig.shouldReplacePrivacyRule）——星辰无隐私模式子系统，恒不触发。
        if (articleListRule != null) {
            addHeaderItem(headers, articleListRule.getClassName(), articleListRule.getClassUrl(), "class");
            addHeaderItem(headers, articleListRule.getAreaName(), articleListRule.getAreaUrl(), "area");
            addHeaderItem(headers, articleListRule.getYearName(), articleListRule.getYearUrl(), "year");
            addHeaderItem(headers, articleListRule.getSortName(), articleListRule.getSortUrl(), "sort");
        }
        return headers;
    }

    private void addHeaderItem(List<HkItem> headers, String name, String url, String desc) {
        if (TextUtils.isEmpty(name)) return;
        HkItem item = new HkItem();
        item.setType("header");
        item.setTitle(name);
        item.setUrl(url);
        item.setDesc(desc);
        headers.add(item);
    }

    /** 照抄原版 initFooter：一个 big_big_blank_block 条目。 */
    private HkItem initFooter() {
        HkItem item = new HkItem();
        item.setType(HkColType.BIG_BIG_BLANK_BLOCK.code);
        return item;
    }

    /** 照抄原版 IDUtilsKt.getArticlesIdStr：列表 JSON 指纹；超 100 条时只取首 50 + 尾 50 的 JSON 拼接。 */
    public static String getArticlesIdStr(List<HkItem> list) {
        if (list == null) return "null";
        com.google.gson.Gson gson = new com.google.gson.Gson();
        if (list.size() > 100) {
            return gson.toJson(list.subList(0, 50))
                    + gson.toJson(list.subList(list.size() - 50, list.size()));
        }
        return gson.toJson(list);
    }

    /** 照抄原版 ArticleList.equals(ArticleList, ArticleList)：title/url/desc/type/pic/content/extra 全等（null 安全）。 */
    public static boolean itemsEqual(HkItem a, HkItem b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return TextUtils.equals(a.getTitle(), b.getTitle())
                && TextUtils.equals(a.getUrl(), b.getUrl())
                && TextUtils.equals(a.getDesc(), b.getDesc())
                && TextUtils.equals(a.getType(), b.getType())
                && TextUtils.equals(a.getPic(), b.getPic())
                && TextUtils.equals(a.getContent(), b.getContent())
                && java.util.Objects.equals(a.getExtra(), b.getExtra());
    }

    /** 照抄原版 runWaitUI：投递到 UI 线程并以 CountDownLatch 等待（最长 30 秒）；已在 UI 线程时直接跑。 */
    private void runWaitUI(Runnable runnable) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run();
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                try {
                    runnable.run();
                } finally {
                    latch.countDown();
                }
            });
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    runnable.run();
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private void clearGlideMemory() {
        if (getContext() != null) {
            try {
                com.bumptech.glide.Glide.get(getContext()).clearMemory();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 照抄原版 showLastClick：首调只置 hasShowLastClick 并走恢复段（阅读分支不移植；
     * 列表宿主时向宿主历史/收藏存储查 lastClick，命中则回主线程 applyLastClick）；
     * 后续调用把 lastSelectedPosition 处的条目标记为选中并单条刷新。
     */
    private void showLastClick() {
        if (!hasShowLastClick) {
            hasShowLastClick = true;
            if (listHost && hostListener != null && articleListRule != null) {
                String title = null;
                if (getActivity() != null && getActivity().getIntent() != null) {
                    title = getActivity().getIntent().getStringExtra("title");
                }
                String lastClick = hostListener.onQueryLastClick(articleListRule.getUrl(), title);
                if (!TextUtils.isEmpty(lastClick) && getActivity() != null && !getActivity().isFinishing()) {
                    String click = lastClick;
                    getActivity().runOnUiThread(() -> applyLastClick(click));
                }
            }
            return;
        }
        int pos = lastSelectedPosition;
        if (adapter != null && pos >= 0 && pos < adapter.getList().size()) {
            HkItem item = adapter.getList().get(pos);
            if (!item.isSel()) {
                item.setSel(true);
                adapter.notifyItemChanged(pos);
            }
        }
    }

    /** 照抄 lambda$showLastClick$53/$54 的公共核：定位 → 滚动 → 通知宿主 → refreshLastClick(pos, lastClick, 3)。 */
    private void applyLastClick(String lastClick) {
        int pos = findPosByTitle(lastClick);
        if (pos > 10) scrollFlag = -1;
        int goodPos = calcGoodPosForScroll(pos);
        if (goodPos > 0 && recyclerView != null) recyclerView.scrollToPosition(goodPos);
        if (hostListener != null) hostListener.onShowLastClick(lastClick);
        refreshLastClick(pos, lastClick, 3);
    }

    /**
     * 照抄原版 findPosByTitle：空 → 0；"标题@@下标" 形式优先解析下标（失败记日志回 0），
     * 否则按标题文本在当前列表扫描首个同名条目（扫描上限 0x186a0 是原版迭代护栏，此处以列表长为界等价）。
     */
    public int findPosByTitle(String title) {
        if (TextUtils.isEmpty(title) || adapter == null) return 0;
        String name = title;
        int parsed = 0;
        if (title.contains("@@")) {
            String[] parts = title.split("@@");
            name = parts.length > 0 ? parts[0] : title;
            try {
                if (parts.length > 1) parsed = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                timber.log.Timber.e(e);
            }
        }
        java.util.List<HkItem> list = adapter.getList();
        for (int i = 0; i < list.size(); i++) {
            String t = detailBridge == null ? list.get(i).getTitle() : detailBridge.getTitleText(list.get(i).getTitle());
            if (name.equals(t)) return i;
        }
        return Math.min(parsed, Math.max(0, list.size() - 1));
    }

    /** 对应原版 calcGoodPosForScroll（经 calcGoodPosForImmersiveTheme 回退同组条目）：回退细则宿主阶段复核（smali 3065），现按已解码门限（≤5 回 0）+ 回退到同 type 连续段段首实现。 */
    private int calcGoodPosForScroll(int position) {
        if (position <= 5 || adapter == null) return 0;
        java.util.List<HkItem> list = adapter.getList();
        if (position >= list.size()) return 0;
        String type = list.get(position).getType();
        int start = position;
        for (int i = position - 1; i >= 0; i--) {
            if (!TextUtils.equals(type, list.get(i).getType())) break;
            start = i;
        }
        return start;
    }

    /** 照抄原版 refreshLastClick 的重试外壳：次数耗尽或活动结束即止；列表为空时延迟重试，否则走 refreshLastClick0 的标记核。 */
    public void refreshLastClick(int position, String title, int tries) {
        if (tries <= 0) return;
        if (getActivity() == null || getActivity().isFinishing()) return;
        if (adapter == null || adapter.getList().isEmpty()) {
            if (recyclerView != null) {
                recyclerView.postDelayed(() -> refreshLastClick(position, title, tries - 1), 200);
            }
            return;
        }
        lastSelectedPosition = position;
        showLastClickMarkOnly();
    }

    private void showLastClickMarkOnly() {
        int pos = lastSelectedPosition;
        if (adapter != null && pos >= 0 && pos < adapter.getList().size()) {
            HkItem item = adapter.getList().get(pos);
            if (!item.isSel()) {
                item.setSel(true);
                adapter.notifyItemChanged(pos);
            }
        }
    }

    // —— 子系统占位（原版对应物不在移植范围内，已在施工图登记）——

    /** 原版 AutoCacheUtilKt.saveCache：自动缓存子系统不在本轮范围。 */
    private void saveAutoCache(List<HkItem> list) {
    }

    /** 原版 updateReadData：阅读数据子系统不移植。 */
    private void updateReadData(boolean isNew, List<HkItem> list) {
    }

    /** 原版 hookHomeData：小程序首页数据钩子，首页按用户令保持星辰现状、不接。 */
    private void hookHomeData(List<HkItem> list) {
    }

    /** 原版 injectReverseMode：FilmListActivity 反转模式，宿主阶段再接。 */
    private void injectReverseMode(List<HkItem> list) {
    }

    /** 原版 injectTextConfig：阅读字号配置不移植。 */
    private void injectTextConfig(List<HkItem> list) {
    }

    /** 原版 updateReadLayout：阅读底部栏不移植。 */
    private void updateReadLayout(List<HkItem> list) {
    }

    /**
     * 照抄原版 updateWebViewHolder（ArticleListFragment.smali:22986）：遍历新数据，
     * x5_webview_single 条目 → 建/更新行内 WebView holder（setUrl + 按 desc 定模式 + extra 解析 X5Extra）；
     * video 条目 → holder 记 videoUrl/videoHeaders、url 置空页、条目 desc 改 "float&&0"。
     */
    private void updateWebViewHolder(List<HkItem> list) {
        if (list == null || list.isEmpty()) return;
        for (HkItem item : list) {
            if (item == null) continue;
            HkColType type = HkColType.byCode(item.getType());
            if (type == HkColType.X5_WEB_VIEW) {
                ensureWebViewHolder();
                webViewHolder.setUrl(item.getUrl());
                updateModeByDesc(item.getDesc());
                String extra = extraJson(item);
                if (!TextUtils.isEmpty(extra)) {
                    try {
                        webViewHolder.setX5Extra(
                                new com.google.gson.Gson().fromJson(extra, HkX5Extra.class));
                    } catch (Exception ignored) {
                    }
                }
                if (adapter != null) adapter.setWebViewHolder(webViewHolder);
            } else if (type == HkColType.VIDEO) {
                ensureWebViewHolder();
                webViewHolder.setVideoUrl(item.getUrl());
                String extra = extraJson(item);
                if (!TextUtils.isEmpty(extra)) {
                    try {
                        HkX5Extra x5 = new com.google.gson.Gson().fromJson(extra, HkX5Extra.class);
                        if (x5 != null) webViewHolder.setVideoHeaders(x5.getHeaders());
                    } catch (Exception ignored) {
                    }
                }
                webViewHolder.setUrl("file:///android_asset/empty.html");
                item.setDesc("float&&0");
                updateModeByDesc("float&&0");
                if (adapter != null) adapter.setWebViewHolder(webViewHolder);
            }
        }
    }

    /** 原版 holder 惰性创建 + setLazyRuleConsumer(consumeLazyRuleForWebView)。 */
    private void ensureWebViewHolder() {
        if (webViewHolder != null) return;
        webViewHolder = new HkWebViewHolder();
        webViewHolder.setLazyRuleConsumer(this::consumeLazyRuleForWebView);
    }

    /** 照抄原版 updateModeByDesc（:21142）：先 setExtra(desc)，desc 非空时按 "&&" 分段匹配 Mode 名写回 holder。 */
    private void updateModeByDesc(String desc) {
        if (webViewHolder == null) return;
        webViewHolder.setExtra(desc);
        if (TextUtils.isEmpty(desc)) return;
        for (String raw : desc.split("&&")) {
            String seg = raw.trim();
            for (HkWebViewHolder.Mode mode : HkWebViewHolder.Mode.values()) {
                if (seg.equalsIgnoreCase(mode.name())) {
                    webViewHolder.setMode(mode);
                }
            }
        }
    }

    /** 照抄原版 consumeLazyRuleForWebView（:4731）：行内 WebView 的 JS 桥回吐 lazy 地址时的消费口。 */
    private void consumeLazyRuleForWebView(String url) {
        if (url == null || adapter == null) return;
        String[] lazySplit = url.split("@lazyRule=");
        String codeAndHeader = detailBridge == null ? ""
                : detailBridge.getCodeAndHeader(
                        com.fongmi.android.tv.api.hk.HkHttpParser.getUrlAppendUA(
                                articleListRule.getUrl(), articleListRule.getUa()),
                        lazySplit);
        List<HkItem> items = adapter.getList();
        for (int i = 0; i < items.size(); i++) {
            if (HkColType.byCode(items.get(i).getType()) == HkColType.X5_WEB_VIEW) {
                dealLazyRule(webViewHolder == null ? null : webViewHolder.getWebView(),
                        i, lazySplit, codeAndHeader);
                return;
            }
        }
    }

    /**
     * 照原版 updateWebView（:21968）的创建点语义：holder 在场且 WebView 未建时才建并加载；
     * show=false（缓存预演）阶段不建，等合并/最终数据（show=true）再建。
     * WebView 的设置/桥/拦截由 HkWebkitHolder.initWebView 承担（原版 holder.initWebView 等价面）。
     */
    private void updateWebView(boolean show) {
        if (getActivity() == null || getActivity().isFinishing() || webViewHolder == null) return;
        if (!show) return;
        if (webViewHolder.getWebView() == null) {
            webViewHolder.ensureWebView(getActivity());
        }
    }

    /** 照抄原版 bindObjectToView：updatePage 改页码；onRefresh/refresh 累积 onRefreshJS；onClose/close 累积 onCloseJS。 */
    @Override
    public void bindObjectToView(String key, HkItem item) {
        if ("updatePage".equals(key)) {
            page = Integer.parseInt(item.getTitle());
            return;
        }
        loadingPage = -1;
        if (!TextUtils.isEmpty(key) && item != null) {
            switch (key) {
                case "onRefresh":
                case "refresh":
                    onRefreshJS = appendJs(onRefreshJS, item.getTitle());
                    break;
                case "onClose":
                case "close":
                    onCloseJS = appendJs(onCloseJS, item.getTitle());
                    break;
                default:
                    break;
            }
        }
    }

    /** 照抄原版 appendJs：空串互让、已包含则不重复、否则以 "\n;\n" 拼接。 */
    private String appendJs(String base, String js) {
        if (TextUtils.isEmpty(js)) return base;
        if (TextUtils.isEmpty(base)) return js;
        if (base.contains(js)) return base;
        return base + "\n;\n" + js;
    }

    /** 照抄原版 error：仅在宿主存在时把 (title, 异常) 投到 UI 线程呈现（msg 参数原版不用）。 */
    @Override
    public void error(String key, String title, String msg, Exception e) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> showLoadError(title, e));
        }
    }

    /** 照抄原版 lambda$error$55：收刷新圈、页码回退到已加载的最大页，再弹原版错误窗（5 秒自动消失）。 */
    private void showLoadError(String title, Exception e) {
        try {
            if (hostListener != null) hostListener.hideLoading();
            if (smartRefreshLayout != null) smartRefreshLayout.finishRefresh(true);
            loadingPage = -1;
            page = maxLoadedPage > 0 ? maxLoadedPage : 1;
            String ruleTitle = articleListRuleDTO == null ? "" : articleListRuleDTO.getTitle();
            com.fongmi.android.tv.api.hk.HkErrorUtil.showErrorMsg(getActivity(),
                    "规则执行过程中出现错误", "home@" + ruleTitle, title, e);
        } finally {
            loading(false);
        }
    }

    // —— 规则/来源判定 ——

    private boolean isFromHistory() {
        return getActivity() != null
                && getActivity().getIntent() != null
                && getActivity().getIntent().getBooleanExtra("fromHistory", false);
    }

    /** 照抄原版 notLoadPreRule：来自历史记录时要跑 preRule（返回 false）；子页面宿主里不重跑（返回 true）；首页宿主跑。 */
    private boolean notLoadPreRule() {
        if (isFromHistory()) return false;
        return listHost;
    }

    // —— 条目点击管线（切片 3：照 ArticleListFragment.clickItem/dealRule/dealLazyRule 转写）——

    /** 子页面打开接缝（对应原版 FilmListActivity + Intent extras + putTempRule 静态传递），宿主阶段实现。 */
    public interface PageOpener {
        void openSubList(SubListRequest request);

        /** 对应原版 onCodeClick 开 HighLightViewActivity 看代码（标题固定「代码」）。 */
        default void openCodeView(String code) {
        }

        /** 对应原版 ArticleListRuleEditActivity.editRule（长按菜单「编辑规则」）。 */
        default void openRuleEditor(String ruleJson) {
        }

        default void openMediaFiles() {
        }

        default void openDebug(String url) {
        }

        default void openHighlightEditor(String text, java.util.function.Consumer<String> callback) {
        }
    }

    /** 照原版 dealRule 组装的子页面请求（字段与 Intent extras 一一对应）。 */
    public static class SubListRequest {
        public HkRule subRule;
        public String title;
        public String picUrl;
        public int fromActivity;
        public String parentTitle;
        public String parentUrl;
        public boolean fromLastClick;
        public AutoPageData autoPageData;
        public java.util.List<HkItem> autoPageNextData;
        public int autoPagePosition;
        public boolean autoPageNoRecordHistory;
        public boolean newWindow;
        public String windowId;
    }

    /** DetailUIHelper/RouteBlocker/LazyRuleParser/UrlDetector/PlayerChooser/ThunderManager 等引擎与宿主侧接缝，引擎单例化/宿主阶段绑定真实现。 */
    public interface DetailBridge {
        boolean isRoute(android.app.Activity activity, String url, HkRule rule, HkItem item);

        /**
         * 照原版 DetailUIHelper.dealUrlSimply 7 参版：resultConsumer = 结果回灌（原版 p5），
         * magnetConsumer = 磁力/种子/FTP 路由（原版 p6，可为 null）。
         */
        boolean dealUrlSimply(android.app.Activity activity, View view, HkRule rule, HkItem item, String url,
                              java.util.function.Consumer<String> resultConsumer,
                              java.util.function.Consumer<String> magnetConsumer);

        String getCodeAndHeader(String urlWithUa, String[] lazyParts);

        void showDownloadDialog(android.app.Activity activity, String title, String url, String film);

        /** 批量快速下载（原版 fastDownload $9 尾段：按 defaultDownloader 偏好直接入队，不弹编辑框）。 */
        void startFastDownload(android.content.Context context, String title, String url, String film);

        // —— UrlDetector（原版规则表随宿主阶段转写）——
        boolean isImage(String url);

        boolean isVideoOrMusic(String url);

        String clearTag(String url);

        String getTitleText(String title);

        String getViewCollectionExtraData(android.app.Activity activity, String ruleUrl);

        // —— WebView 嗅探解析（X5WebViewParser/WebkitParser）——
        boolean canParseX5(String url);

        boolean canParseWebkit(String url);

        boolean parseX5(android.app.Activity activity, String url, String extraJson,
                        java.util.function.Consumer<String> consumer);

        boolean parseWebkit(android.app.Activity activity, String url, String extraJson,
                            java.util.function.Consumer<String> consumer);

        // —— 播放器交接（PlayerChooser）——
        void startPlayerSingle(android.app.Activity activity, String title, String url);

        void startPlayerChapters(android.app.Activity activity, java.util.List<HkChapter> chapters,
                                 String pageUrl, String mTitle, android.os.Bundle extraData);

        void openPictureList(android.app.Activity activity, PictureListRequest request);

        void showImageViewer(java.util.List<String> urls, int selectPos,
                             java.util.Map<Integer, Integer> posMap, android.widget.ImageView srcView);

        void showSingleImage(String url, android.widget.ImageView srcView);

        // —— 网页/分享/迅雷 ——
        void goWeb(android.content.Context context, String url);

        void goWebWithExtraData(android.content.Context context, String url, android.os.Bundle bundle);

        void findChooserToDeal(android.content.Context context, String url);

        boolean isCannotHandleScheme(String lowerUrl);

        boolean isScheme(String url);

        String getFileExtensionFromUrl(String url);

        void startParseTorrent(android.content.Context context, String url);

        boolean isFTPOrEd2k(String url);

        void startParseFTPOrEd2k(android.content.Context context, String url,
                                 java.util.function.Consumer<String> consumer);

        boolean isMagnetOrTorrent(String url);

        void startDownloadMagnet(android.content.Context context, String url);

        // —— ThunderManager 带同组选集列表的三参版（原版 lambda$clickItem$32 调用面）——
        void startDownloadMagnetList(android.content.Context context, String url, java.util.List<HkChapter> chapters);

        void startParseTorrentList(android.content.Context context, String url, java.util.List<HkChapter> chapters);

        void startParseFTPOrEd2kList(android.content.Context context, String url, java.util.List<HkChapter> chapters);

        void toastBottom(String text);

        // —— 长按菜单/工具（HttpParser/StringUtil/ShareRuleUtil/CustomCopyPopup/FileDetailPopup）——
        String getFirstPageUrl(String url);

        String getHomeUrl(String url);

        String autoFixUrl(String base, String url);

        void shareRule(HkRule rule, boolean moreWays);

        void showCopyPopup(String text);

        void showPosDetail(android.app.Activity activity, HkItem item, HkRule rule, int position);

        void onBatchModeFinish(String mode, int count);
    }

    /** 对应原版 VideoChapter（播放选集条目）。 */
    public static class HkChapter {
        public String title;
        public String memoryTitle;
        public String url;
        public String originalUrl;
        public String extra;
        public String pic;
        public String codeAndHeader;
        public int realPos;
        public boolean use;
    }

    /** 对应原版 PictureListActivity 的入参集。 */
    public static class PictureListRequest {
        public java.util.ArrayList<String> pics;
        public String ruleJson;
        public String url;
        public String title;
        public java.util.List<HkChapter> chapters;
        public String cUrl;
        public String mTitle;
        public boolean fromLastPage;
        public int nowPage;
    }

    private PageOpener pageOpener;
    private static DetailBridge detailBridge;

    public void setPageOpener(PageOpener pageOpener) {
        this.pageOpener = pageOpener;
    }

    public static void setDetailBridge(DetailBridge bridge) {
        detailBridge = bridge;
    }

    /** 搜索页共用同一 DetailBridge 绑定（宿主 ensureStatics 时已注入）。 */
    public static DetailBridge detailBridge() {
        return detailBridge;
    }

    private String fileSelectJs;
    private int fileSelectPosition;
    private java.util.function.Consumer<String> highlightCallback;
    private boolean batchDownloadSelecting;
    private boolean batchDownloadAll;
    private int batchDownloadCount;
    private boolean batchDlanSelecting;
    private int continuousDlanMode = -1;
    private final java.util.List<String> continuousDlanHis = new ArrayList<>();
    private android.app.Dialog loadingPopup;

    /** 照抄原版 clickItem(View, int)：青豆光影特例、input 条目（EditText 回填/高亮编辑/js 求值回灌）、余者转三参版。 */
    protected void clickItem(View view, int position) {
        if (position < 0 || adapter == null || position >= adapter.getList().size()) return;
        HkItem item = adapter.getList().get(position);
        String url = item.getUrl();
        if ("hiker://home@青豆||光影剧场||https://m.douban.com/movie/".equals(url)) {
            if (pageOpener != null) pageOpener.openMediaFiles();
            return;
        }
        if (HkColType.input.code.equals(item.getType())) {
            if (view == null || !(view.getTag() instanceof android.widget.EditText)) return;
            android.widget.EditText edit = (android.widget.EditText) view.getTag();
            String text = edit.getText() == null ? "" : edit.getText().toString();
            if (getActivity() != null) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getActivity().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
            }
            if ("true".equals(item.getExtra("highlight"))) {
                if (pageOpener != null) {
                    highlightCallback = result -> {
                        if (!android.text.TextUtils.equals(result, edit.getText())) edit.setText(result);
                    };
                    pageOpener.openHighlightEditor(text, highlightCallback);
                }
                return;
            }
            String js = url.startsWith("js:") ? url.substring("js:".length()) : url;
            Task.execute(() -> {
                HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
                String result = bridge == null ? "" : bridge.evalWithRule(articleListRule, js, text);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (result != null && !result.isEmpty()) clickItem(view, position, result);
                    });
                }
            });
            return;
        }
        clickItem(view, position, url);
    }

    /** 照抄原版 clickItem(View, int, String)：转五参版（recordHistory=true, extraInput=""）。 */
    protected void clickItem(View view, int position, String url) {
        clickItem(view, position, url, true, "");
    }

    /**
     * 照抄原版 clickItem(View, int, String, boolean, String)：
     * 空链接 toast、hiker://debug、路由拦截、base64 解码、hiker://page/ 跳页、fileSelect:// 选文件、
     * DetailUIHelper.dealUrlSimply 简单链接、download://、@lazyRule 懒解析、@rule= 子规则、detail_find_rule 详情、余者 dealWithUrl。
     */
    protected void clickItem(View view, int position, String url, boolean recordHistory, String extraInput) {
        if (position < 0 || adapter == null || position >= adapter.getList().size()) return;
        if (getContext() == null) return;
        HkItem item = adapter.getList().get(position);
        if (getActivity() != null) {
            if (TextUtils.isEmpty(url)) {
                toastCenter("链接为空，规则有误！");
                return;
            }
            if (url.startsWith("hiker://debug")) {
                if (pageOpener != null) pageOpener.openDebug(url);
                return;
            }
            if (detailBridge != null && detailBridge.isRoute(getActivity(), url, articleListRuleDTO, item)) {
                return;
            }
            String decoded = url;
            if (url.startsWith("base64://")) {
                try {
                    decoded = new String(android.util.Base64.decode(
                            url.substring("base64://".length()).trim(), android.util.Base64.URL_SAFE));
                } catch (Exception ignored) {
                }
            }
            if (isPageUrl(decoded)) {
                toNextPage(position, decoded);
                return;
            }
            if (decoded.startsWith("fileSelect://")) {
                fileSelectJs = decoded.replaceFirst("(?i)fileSelect://", "");
                fileSelectPosition = position;
                android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                startActivityForResult(android.content.Intent.createChooser(pick, "选择文件"), 0x2385);
                return;
            }
            if (detailBridge != null && detailBridge.dealUrlSimply(getActivity(), view, articleListRuleDTO, item, decoded,
                    result -> clickItem(view, position, result),
                    magnetUrl -> onMagnetRoute(position, magnetUrl))) {
                // 简单链接已处理（原版 DetailUIHelper.dealUrlSimply 返回 true 的分支）
                if (webViewHolder != null) {
                    String itemUrl = item.getUrl();
                    if (itemUrl != null && (itemUrl.contains("refreshX5WebView") || itemUrl.contains("refreshPage"))) {
                        memoryPosition(position);
                    } else if (decoded.contains("x5WebView://") || decoded.contains("refreshPage")) {
                        if (!webViewHolder.isVideoItem()) memoryPosition(position);
                    } else {
                        memoryPosition(position);
                    }
                }
                return;
            }
            if (decoded.startsWith("download://")) {
                String real = decoded.replaceFirst("(?i)download://", "");
                if (detailBridge != null) {
                    detailBridge.showDownloadDialog(getActivity(), getItemTitle(position), real, getFilm());
                }
                return;
            }
            dispatchRuleClick(view, position, decoded, recordHistory, extraInput);
            return;
        }
        // 原版：getActivity() == null 时整段直接返回（goto_2），不做任何分发
    }

    /** clickItem 五参版后半（原版 cond_e 起）：拆 @rule=/@lazyRule=、批量模式分流、codeAndHeader、懒解析/子规则/详情/dealWithUrl。 */
    private void dispatchRuleClick(View view, int position, String decoded, boolean recordHistory, String extraInput) {
        HkItem item = adapter.getList().get(position);
        String[] ruleSplit = decoded.split("@rule=");
        String[] lazySplit = ruleSplit[0].split("@lazyRule=");
        if (batchDownloadSelecting) {
            batchDownloadCount++;
            if (notify_text != null) notify_text.setText("完成 (" + batchDownloadCount + ")");
            fastDownload(position, lazySplit);
            return;
        }
        if (batchDlanSelecting) {
            batchDlan(position, lazySplit);
            return;
        }
        if (continuousDlanMode != -1) {
            continuousDlan(position, lazySplit);
            return;
        }
        String codeAndHeader = detailBridge == null ? ""
                : detailBridge.getCodeAndHeader(
                        HkHttpParser_getUrlAppendUA(articleListRule.getUrl(), articleListRule.getUa()), lazySplit);
        if (recordHistory && TextUtils.isEmpty(codeAndHeader)) codeAndHeader = extraInput;
        if (lazySplit.length > 1) {
            dealLazyRule(view, position, lazySplit, codeAndHeader);
            return;
        }
        String itemTitle = getItemTitle(position);
        if (ruleSplit.length > 1) {
            String ruleStr = arrayToString(ruleSplit, 1, "@rule=");
            String[] seg = ruleStr.split("==>");
            String head = seg[0];
            String[] semi = head.split(";");
            String colType = articleListRule.getColType();
            if (semi.length == 6 && !ruleStr.startsWith("js:")) {
                colType = semi[5];
                head = arrayToString(semi, 0, 5, ";");
            }
            String tail = "";
            if (seg.length > 1) tail = "==>" + arrayToString(seg, 1, "==>");
            dealRule(articleListRule, ruleSplit[0], codeAndHeader, head + tail, colType, itemTitle, position);
            return;
        }
        if (recordHistory && !TextUtils.isEmpty(articleListRule.getDetailFindRule())
                && !decoded.startsWith("video://")) {
            dealRule(articleListRule, decoded, codeAndHeader, articleListRule.getDetailFindRule(),
                    articleListRule.getDetailColType(), itemTitle, position);
            return;
        }
        if (!recordHistory) codeAndHeader = null;
        dealWithUrl(view, decoded, position, codeAndHeader);
    }

    /**
     * 照抄原版 dealRule：组装子规则（url 不含 ";" 时拼 codeAndHeader；继承 ua/group/preRule/title/last_chapter_rule/pages；
     * 条目 extra 非空时作 params），组装子页面请求交宿主打开（原版 Intent+putTempRule+startActivityForResult(2)，新窗口条目走 startActivity）。
     */
    private void dealRule(HkRule parentRule, String url, String codeAndHeader, String findRule,
                          String colType, String title, int position) {
        if (url.contains("#noHistory#")) memoryPos = false;
        HkRule sub = new HkRule();
        sub.setUrl(url.contains(";") ? url : url + (codeAndHeader == null ? "" : codeAndHeader));
        sub.setUa(parentRule.getUa());
        sub.setFindRule(findRule);
        sub.setColType(colType);
        sub.setGroup(parentRule.getGroup());
        sub.setPreRule(parentRule.getPreRule());
        sub.setTitle(parentRule.getTitle());
        sub.setLastChapterRule(parentRule.getLastChapterRule());
        sub.setPages(parentRule.getPages());
        HkItem item = adapter.getList().get(position);
        if (item.getExtra() != null && !item.getExtra().isEmpty()) {
            sub.setParams(new com.google.gson.Gson().toJson(item.getExtra()));
        }
        SubListRequest req = new SubListRequest();
        req.subRule = sub;
        req.title = title;
        req.fromActivity = getContext() == null ? 0 : getContext().hashCode();
        req.parentTitle = activityTitle();
        req.parentUrl = articleListRule == null ? "" : articleListRule.getUrl();
        if (!TextUtils.isEmpty(sub.getUrl())
                && (sub.getUrl().contains("#readTheme#") || sub.getUrl().contains("#autoReader#"))) {
            req.fromLastClick = isClickLastPageBtn(parentRule.getUrl(), activityTitle(), position);
        }
        if (sub.getUrl() != null && sub.getUrl().contains("#autoPage#")) {
            AutoPageData data = new AutoPageData();
            data.nextData = new ArrayList<>(adapter.getList().subList(position, adapter.getList().size()));
            data.url = articleListRule.getUrl();
            data.title = activityTitle();
            data.currentPos = position;
            data.noRecordHistory = noRecordHistory;
            loadChapterCount(data, position);
            req.autoPageData = data;
            req.autoPageNextData = data.nextData;
            req.autoPagePosition = position;
            req.autoPageNoRecordHistory = noRecordHistory;
        }
        try {
            if (!TextUtils.isEmpty(item.getPic())) req.picUrl = item.getPic();
        } catch (Exception ignored) {
        }
        memoryPosition(position);
        req.newWindow = "true".equals(item.getExtra("newWindow"));
        req.windowId = item.getExtra("windowId");
        if (pageOpener != null) pageOpener.openSubList(req);
    }

    /** 照抄原版 dealLazyRule + $13 回调：解析前弹"动态解析规则中，请稍候"（#noLoading# 除外），成功以 recordHistory=false 回灌 clickItem，失败仅关弹窗（错误原文由解析器侧弹）。 */
    private void dealLazyRule(View view, int position, String[] lazySplit, String codeAndHeader) {
        // 照原版：FuckKt.inList(activity) 命中且 goWeb 打开成功则不再走内置解析（FuckKt 为 Fragment 直调，不经 DetailBridge）
        if (HkFuck.inList(getActivity()) && HkFuck.goWeb(getContext(), myUrl, lazySplit[0])) return;
        HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
        if (bridge == null) return;
        bridge.parseLazyRule(articleListRule, lazySplit, position, codeAndHeader, myUrl,
                new HkArticleListModel.LazyCallback() {
                    @Override
                    public void start() {
                        showLoadingPopup("动态解析规则中，请稍候",
                                lazySplit[0] != null && lazySplit[0].contains("#noLoading#"));
                    }

                    @Override
                    public void success(String result) {
                        clickItem(view, position, result, false, codeAndHeader);
                        dismissLoadingPopup();
                    }

                    @Override
                    public void error(String msg) {
                        dismissLoadingPopup();
                    }
                });
    }

    /** 原版 initLoadingPopup 的等价物（XPopup LoadingPopupView → 原生 Dialog，先行等价、宿主阶段可换）。 */
    private void showLoadingPopup(String title, boolean skipShow) {
        dismissLoadingPopup();
        if (getContext() == null) return;
        android.widget.TextView tv = new android.widget.TextView(getContext());
        tv.setText(title);
        tv.setPadding(dpToPx(24), dpToPx(18), dpToPx(24), dpToPx(18));
        loadingPopup = new android.app.Dialog(getContext());
        loadingPopup.setContentView(tv);
        loadingPopup.setCancelable(false);
        if (!skipShow) loadingPopup.show();
    }

    private void dismissLoadingPopup() {
        if (loadingPopup != null && loadingPopup.isShowing()) loadingPopup.dismiss();
        loadingPopup = null;
    }

    /** 照抄原版 PageParser.isPageUrl。 */
    private boolean isPageUrl(String url) {
        return !TextUtils.isEmpty(url) && url.startsWith("hiker://page/");
    }

    /** 照抄原版 toNextPage 入口：列表为空直接返回；解析与开页在后台经宿主接缝（原版 lambda 体随规则 pages 子系统在引擎阶段落码）。 */
    private void toNextPage(int position, String pageUrl) {
        if (adapter == null || adapter.getList().isEmpty()) return;
        Task.execute(() -> {
            if (pageOpener instanceof PageUrlOpener) {
                ((PageUrlOpener) pageOpener).openPageUrl(articleListRule, pageUrl, position);
            }
        });
    }

    /** hiker://page/ 开页接缝（PageOpener 的可选扩展）。 */
    public interface PageUrlOpener {
        void openPageUrl(HkRule rule, String pageUrl, int position);
    }

    /** 照抄原版 getItemTitle：extra.pageTitle 优先，其次条目标题（inheritTitle 细则见施工图待复核项）。 */
    private String getItemTitle(int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return "";
        HkItem item = adapter.getList().get(position);
        String pageTitle = item.getExtra("pageTitle");
        if (!TextUtils.isEmpty(pageTitle)) return pageTitle;
        return item.getTitle();
    }

    /** 照抄原版 getFilm：宿主标题（等于应用名时视为无，返回 null）。 */
    private String getFilm() {
        if (getActivity() == null) return null;
        CharSequence t = getActivity().getTitle();
        if (t == null) return null;
        String title = t.toString();
        if (title.equals(getString(R.string.app_name))) return null;
        return title;
    }

    /** 宿主标题（对应原版 DetailUIHelper.getActivityTitle 的用途：子页面 parentTitle）。 */
    private String activityTitle() {
        if (getActivity() == null) return "";
        String extra = getActivity().getIntent() == null ? null
                : getActivity().getIntent().getStringExtra("title");
        if (!TextUtils.isEmpty(extra)) return extra;
        CharSequence t = getActivity().getTitle();
        return t == null ? "" : t.toString();
    }

    /** 照抄原版 memoryPosition 的开关段：memoryPos 关时不记；记录本体（历史/续看位置）随宿主阶段承接。 */
    private void memoryPosition(int position) {
        if (!memoryPos) return;
        if (hostListener != null) hostListener.onMemoryPosition(position);
    }

    /** 阅读主题的"最后一页按钮"判定（阅读子系统不移植，恒 false）。 */
    private boolean isClickLastPageBtn(String url, String title, int position) {
        return false;
    }

    /** 照抄原版 StringUtil.arrayToString(数组, 起始下标, 连接符)。 */
    private static String arrayToString(String[] arr, int from, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < arr.length; i++) {
            if (i > from) sb.append(sep);
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    /** 照抄原版 StringUtil.arrayToString(数组, 起始, 结束(不含), 连接符)。 */
    private static String arrayToString(String[] arr, int from, int to, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to && i < arr.length; i++) {
            if (i > from) sb.append(sep);
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    private void toastCenter(String text) {
        if (getContext() != null) {
            android.widget.Toast toast = android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT);
            toast.setGravity(android.view.Gravity.CENTER, 0, 0);
            toast.show();
        }
    }

    private static String HkHttpParser_getUrlAppendUA(String url, String ua) {
        return com.fongmi.android.tv.api.hk.HkHttpParser.getUrlAppendUA(url, ua);
    }

    // —— 懒解析取址与下载分发（照原版 parseLazyRuleForUrl/$12/$11/$9 转写；下载对话框与入队本体经 DetailBridge 钩子承接）——

    /**
     * 照抄原版 parseLazyRuleForUrl：lazySplit[0] 为空 → toast「链接为空，请检查规则」；
     * 多段（带 @lazyRule= 规则体）走引擎桥懒解析（回调经 $12 包装）；单段先 callback.start()，
     * 再 X5/Webkit 嗅探或直通 success。
     */
    private void parseLazyRuleForUrl(int position, String[] lazySplit, HkArticleListModel.LazyCallback callback) {
        if (lazySplit == null || lazySplit.length == 0 || TextUtils.isEmpty(lazySplit[0])) {
            toastBottom("链接为空，请检查规则");
            return;
        }
        String codeAndHeader = detailBridge == null ? ""
                : detailBridge.getCodeAndHeader(
                        HkHttpParser_getUrlAppendUA(articleListRule.getUrl(), articleListRule.getUa()), lazySplit);
        if (lazySplit.length > 1) {
            HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
            if (bridge == null) return;
            bridge.parseLazyRule(articleListRule, lazySplit, position, codeAndHeader, myUrl,
                    wrapLazyCallback(position, callback));
            return;
        }
        callback.start();
        dispatchParsedUrl(position, lazySplit[0], callback);
    }

    /** 照抄原版 $12 包装回调：start/error 直传；success 先过 X5/Webkit 嗅探分发，余直传 delegate。 */
    private HkArticleListModel.LazyCallback wrapLazyCallback(int position, HkArticleListModel.LazyCallback delegate) {
        return new HkArticleListModel.LazyCallback() {
            @Override
            public void start() {
                delegate.start();
            }

            @Override
            public void success(String result) {
                dispatchParsedUrl(position, result, delegate);
            }

            @Override
            public void error(String msg) {
                delegate.error(msg);
            }
        };
    }

    /** parseLazyRuleForUrl 单段支与 $12.success 共用尾段：X5/Webkit 可解析走嗅探（结果回 callback.success），余直接 success。 */
    private void dispatchParsedUrl(int position, String url, HkArticleListModel.LazyCallback callback) {
        HkItem item = adapter == null || position < 0 || position >= adapter.getList().size()
                ? null : adapter.getList().get(position);
        String extra = item == null ? "" : extraJson(item);
        if (detailBridge != null && getActivity() != null && detailBridge.canParseX5(url)) {
            detailBridge.parseX5(getActivity(), url, extra, callback::success);
            return;
        }
        if (detailBridge != null && getActivity() != null && detailBridge.canParseWebkit(url)) {
            detailBridge.parseWebkit(getActivity(), url, extra, callback::success);
            return;
        }
        callback.success(url);
    }

    /**
     * 照抄原版 downloadSource + $11 回调（长按「下载资源」）：解析中弹「动态解析规则中，请稍候」；
     * 结果为空 toast「链接为空」；仍带 @lazyRule= 时以新 lazySplit 递归；
     * 不可处理 scheme/.torrent 交系统选择器；非视频且非系统下载格式 toast「{标题}非视频格式，不支持下载」；
     * 余 clearTag + getRealUrlFilterHeaders 后走下载对话框（DetailBridge 钩子承接本体）。
     * 注：原版尾段的 getSwitchPlayUrl(PlayData) 线路切换在星辰无 PlayData 对应物，沿本模块 download:// 支的既有口径直达对话框（欠账）。
     */
    private void downloadSource(int position, String[] lazySplit) {
        parseLazyRuleForUrl(position, lazySplit, new HkArticleListModel.LazyCallback() {
            @Override
            public void start() {
                showLoadingPopup("动态解析规则中，请稍候", false);
            }

            @Override
            public void success(String url) {
                dismissLoadingPopup();
                if (TextUtils.isEmpty(url)) {
                    toastBottom("链接为空");
                    return;
                }
                if (url.contains("@lazyRule=")) {
                    downloadSource(position, url.split("@lazyRule="));
                    return;
                }
                if (detailBridge == null) return;
                if (detailBridge.isCannotHandleScheme(url) || url.endsWith(".torrent")) {
                    detailBridge.findChooserToDeal(getContext(), url);
                    return;
                }
                String title = getItemTitle(position);
                if (!detailBridge.isVideoOrMusic(url) && !HkDetailBridge.useSystemDownload(title, url)) {
                    toastBottom(title + "非视频格式，不支持下载");
                    return;
                }
                String real = com.fongmi.android.tv.api.hk.HkHttpParser.getRealUrlFilterHeaders(
                        detailBridge.clearTag(url));
                detailBridge.showDownloadDialog(getActivity(), title, real, getFilm());
            }

            @Override
            public void error(String msg) {
                dismissLoadingPopup();
            }
        });
    }

    /**
     * 照抄原版 fastDownload + $9 回调（批量下载模式逐项分发）：结构同 $11，但终段不弹编辑框，
     * 按 defaultDownloader 偏好直接入队（DetailBridge.startFastDownload 钩子承接）；
     * 不可处理 scheme/.torrent 同样交系统选择器。原版的线路切换（getSwitchPlayUrl）欠账同 downloadSource。
     */
    private void fastDownload(int position, String[] lazySplit) {
        parseLazyRuleForUrl(position, lazySplit, new HkArticleListModel.LazyCallback() {
            @Override
            public void start() {
            }

            @Override
            public void success(String url) {
                if (TextUtils.isEmpty(url)) {
                    toastBottom("链接为空");
                    return;
                }
                if (url.contains("@lazyRule=")) {
                    fastDownload(position, url.split("@lazyRule="));
                    return;
                }
                if (detailBridge == null) return;
                if (detailBridge.isCannotHandleScheme(url) || url.endsWith(".torrent")) {
                    detailBridge.findChooserToDeal(getContext(), url);
                    return;
                }
                String real = com.fongmi.android.tv.api.hk.HkHttpParser.getRealUrlFilterHeaders(
                        detailBridge.clearTag(url));
                detailBridge.startFastDownload(getContext(), getItemTitle(position), real, getFilm());
            }

            @Override
            public void error(String msg) {
            }
        });
    }

    // —— 批量投屏模式：原版 $10 依赖 DLNA 投屏子系统，星辰未移植该子系统，模式分发先行空转（欠账）——

    private void batchDlan(int position, String[] lazySplit) {
    }

    private void continuousDlan(int position, String[] lazySplit) {
    }

    /**
     * 照抄原版 dealWithUrl：#noHistory# 剥离、hiker://page/、dealUrlSimply、javascript:、func://background、
     * x5:// 合成规则开页、X5/Webkit 嗅探、pics:// 图集、磁力/迅雷、.torrent、scheme 外部处理、图片预览、
     * 视频/音乐（直达播放或选集交接）、apk 下载、http/file 网页、未知链接 toast。
     */
    private void dealWithUrl(View view, String url, int position, String codeAndHeader) {
        if (position < 0 || adapter == null || position >= adapter.getList().size()) return;
        if (getContext() == null) return;
        if (getActivity() == null) return;
        HkItem item = adapter.getList().get(position);
        if (url.contains("#noHistory#")) {
            url = url.replaceFirst(java.util.regex.Pattern.quote("#noHistory#"), "");
            memoryPos = false;
        }
        if (isPageUrl(url)) {
            toNextPage(position, url);
            return;
        }
        if (detailBridge != null && detailBridge.dealUrlSimply(getActivity(), view, articleListRuleDTO, item, url,
                result -> dealWithUrl(view, result, position, codeAndHeader), null)) {
            if (webViewHolder != null) {
                String itemUrl = item.getUrl();
                if (itemUrl != null && (itemUrl.contains("refreshX5WebView") || itemUrl.contains("refreshPage"))) {
                    memoryPosition(position);
                } else if (url.contains("x5WebView://") || url.contains("refreshPage")) {
                    memoryPosition(position);
                }
            }
            return;
        }
        String itemTitle = getItemTitle(position);
        String actTitle = activityTitle();
        String titlePrefix = !TextUtils.isEmpty(actTitle) ? actTitle + "-" : actTitle;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("javascript")) {
            memoryPosition(position);
            if (detailBridge != null) detailBridge.goWeb(getContext(), url);
            return;
        }
        if (lower.startsWith("func://background")) {
            if (listHost && hostListener != null) hostListener.onBackground();
            return;
        }
        if (url.startsWith("x5://")) {
            String inner = url.replaceFirst("(?i)x5://", "");
            HkRule r = new HkRule();
            r.setUrl("hiker://empty");
            r.setFindRule("js:setResult([{\n    url:\"" + inner + "\",\ndesc:\"100%&&float\",\nextra:{canBack: true}\n}]);");
            r.setColType(HkColType.X5_WEB_VIEW.code);
            r.setGroup(articleListRule.getGroup());
            r.setTitle(articleListRule.getTitle());
            r.setPages(articleListRule.getPages());
            SubListRequest req = new SubListRequest();
            req.subRule = r;
            req.fromActivity = getContext().hashCode();
            req.parentTitle = actTitle;
            req.parentUrl = articleListRule.getUrl();
            req.title = titlePrefix + item.getTitle();
            req.newWindow = "true".equals(item.getExtra("newWindow"));
            req.windowId = item.getExtra("windowId");
            if (pageOpener != null) pageOpener.openSubList(req);
            memoryPosition(position);
            return;
        }
        if (detailBridge != null && detailBridge.canParseX5(url)) {
            // 照原版 X5 支：inList 命中且 goWeb 成功才 return，否则继续 parse0
            if (HkFuck.inList(getActivity()) && HkFuck.goWeb(getContext(), myUrl, url)) return;
            boolean started = detailBridge.parseX5(getActivity(), url, extraJson(item),
                    result -> {
                        dismissLoadingPopup();
                        clickItem(view, position, result, false, codeAndHeader);
                    });
            if (started) showLoadingPopup("动态解析规则中，请稍候", false);
            return;
        }
        if (detailBridge != null && detailBridge.canParseWebkit(url)) {
            // 照原版 Webkit 支：inList 命中即 goWeb 后无条件 return（不看 goWeb 结果，原版不对称怪癖照抄）
            if (HkFuck.inList(getActivity())) {
                HkFuck.goWeb(getContext(), myUrl, url);
                return;
            }
            boolean started = detailBridge.parseWebkit(getActivity(), url, extraJson(item),
                    result -> {
                        dismissLoadingPopup();
                        clickItem(view, position, result, false, codeAndHeader);
                    });
            if (started) showLoadingPopup("动态解析规则中，请稍候", false);
            return;
        }
        if (lower.startsWith("pics://")) {
            boolean fromLast = isClickLastPageBtn(articleListRule.getUrl(), actTitle, position);
            memoryPosition(position);
            String body = url.replaceFirst("(?i)pics://", "");
            PictureListRequest req = new PictureListRequest();
            req.pics = new ArrayList<>(java.util.Arrays.asList(body.split("&&")));
            req.ruleJson = new com.google.gson.Gson().toJson(articleListRule);
            req.url = articleListRule.getUrl();
            req.title = detailBridge == null ? item.getTitle() : detailBridge.getTitleText(item.getTitle());
            req.chapters = getChapters(url, position, titlePrefix, codeAndHeader);
            if (listHost) {
                req.cUrl = articleListRule.getUrl();
                req.mTitle = actTitle;
            }
            req.fromLastPage = fromLast;
            int nowPage = 0;
            for (int i = 0; i < req.chapters.size(); i++) {
                if (req.chapters.get(i).use) nowPage = i;
            }
            req.nowPage = nowPage;
            if (detailBridge != null) detailBridge.openPictureList(getActivity(), req);
            return;
        }
        if (url.startsWith("magnet:?") || url.startsWith("thunder://")) {
            if (detailBridge != null) detailBridge.startDownloadMagnet(getContext(), url);
            return;
        }
        if (url.split(";")[0].endsWith(".torrent")) {
            if (detailBridge != null) detailBridge.startParseTorrent(getContext(), url);
            return;
        }
        if (detailBridge != null && detailBridge.isFTPOrEd2k(url)) {
            detailBridge.startParseFTPOrEd2k(getContext(), url,
                    result -> {
                        dismissLoadingPopup();
                        clickItem(view, position, result, false, codeAndHeader);
                    });
            return;
        }
        if (detailBridge != null && detailBridge.isScheme(url)) {
            detailBridge.findChooserToDeal(getContext(), url);
            return;
        }
        String ext = detailBridge == null ? "" : detailBridge.getFileExtensionFromUrl(url);
        if (detailBridge != null && detailBridge.isImage(url)) {
            android.widget.ImageView srcView = null;
            if (view instanceof android.widget.ImageView) {
                srcView = (android.widget.ImageView) view;
            } else if (view != null) {
                android.view.View found = view.findViewById(R.id.item_reult_img);
                if (found instanceof android.widget.ImageView) srcView = (android.widget.ImageView) found;
            }
            if (detailBridge.clearTag(url).equals(detailBridge.clearTag(item.getUrl()))) {
                java.util.List<String> urls = new ArrayList<>();
                int selectPos = collectNeighborImages(urls, position);
                detailBridge.showImageViewer(urls, selectPos, imageUrlPosMap, srcView);
            } else {
                detailBridge.showSingleImage(url, srcView);
            }
            return;
        }
        if (detailBridge != null && detailBridge.isVideoOrMusic(url)) {
            memoryPosition(position);
            if (url.startsWith("video://") && HkFuck.inList(getActivity())
                    && HkFuck.goWeb(getContext(), myUrl, url)) return;
            String cleared = detailBridge.clearTag(url);
            String itemCleared = detailBridge.clearTag(item.getUrl());
            if (!cleared.equals(itemCleared) && !TextUtils.isEmpty(codeAndHeader)
                    && !detailBridge.canParseX5(item.getUrl()) && !detailBridge.canParseWebkit(item.getUrl())) {
                detailBridge.startPlayerSingle(getActivity(), itemTitle, url);
                return;
            }
            java.util.List<HkChapter> chapters = getChapters(url, position, titlePrefix, codeAndHeader);
            android.os.Bundle bundle = new android.os.Bundle();
            String vc = detailBridge.getViewCollectionExtraData(getActivity(), articleListRule.getUrl());
            if (!TextUtils.isEmpty(vc)) bundle.putString("viewCollectionExtraData", vc);
            if (getActivity().getTitle() != null) {
                bundle.putString("film", getActivity().getTitle().toString());
            }
            bundle.putString("rule", new com.google.gson.Gson().toJson(articleListRule));
            if (listHost) {
                detailBridge.startPlayerChapters(getActivity(), chapters, articleListRule.getUrl(), actTitle, bundle);
            } else {
                detailBridge.startPlayerChapters(getActivity(), chapters, myUrl, "", bundle);
            }
            return;
        }
        if (lower.startsWith("http") && "apk".equalsIgnoreCase(ext)) {
            if (detailBridge != null) detailBridge.showDownloadDialog(getActivity(), item.getTitle(), url, null);
            return;
        }
        if (lower.startsWith("http") || lower.startsWith("file://")) {
            memoryPosition(position);
            android.os.Bundle bundle = new android.os.Bundle();
            if (detailBridge != null) {
                String vc = detailBridge.getViewCollectionExtraData(getActivity(), articleListRule.getUrl());
                if (!TextUtils.isEmpty(vc)) bundle.putString("viewCollectionExtraData", vc);
            }
            if (getActivity().getTitle() != null) {
                bundle.putString("film", getActivity().getTitle().toString());
            }
            if (detailBridge != null) detailBridge.goWebWithExtraData(getContext(), url, bundle);
            return;
        }
        if (detailBridge != null && detailBridge.isCannotHandleScheme(lower)) {
            detailBridge.findChooserToDeal(getContext(), url);
            return;
        }
        if (detailBridge != null) detailBridge.toastBottom("未知链接：" + url);
    }

    private java.util.Map<Integer, Integer> imageUrlPosMap;

    /** 照抄原版图片分支的邻图收集：同 type 连续图片条目，向前最多 9 张、向后最多 16 张，返回当前条在图集中的下标，并回填 imageUrlPosMap（图集下标→条目下标）。 */
    private int collectNeighborImages(java.util.List<String> out, int position) {
        if (imageUrlPosMap == null) imageUrlPosMap = new java.util.HashMap<>();
        else imageUrlPosMap.clear();
        java.util.List<HkItem> list = adapter.getList();
        String type = list.get(position).getType();
        int start = position;
        int count = 0;
        int i = position;
        while (i >= 0) {
            HkItem it = list.get(i);
            if (!TextUtils.equals(type, it.getType())) break;
            if (detailBridge == null || !detailBridge.isImage(it.getUrl())) break;
            count++;
            if (count >= 9) break;
            start = i;
            i--;
        }
        int end = position;
        count = 0;
        i = position;
        while (i < list.size()) {
            HkItem it = list.get(i);
            if (!TextUtils.equals(type, it.getType())) break;
            if (detailBridge == null || !detailBridge.isImage(it.getUrl())) break;
            count++;
            if (count >= 16) break;
            end = i;
            i++;
        }
        int selectPos = 0;
        for (int j = start; j <= end; j++) {
            out.add(list.get(j).getUrl());
            imageUrlPosMap.put(out.size() - 1, j);
            if (j == position) selectPos = out.size() - 1;
        }
        return selectPos;
    }

    private String extraJson(HkItem item) {
        if (item.getExtra() == null || item.getExtra().isEmpty()) return "";
        return new com.google.gson.Gson().toJson(item.getExtra());
    }

    /**
     * 照抄原版 getChapters：以被点条目为 use=true 的当前集，向前/向后收集同组条目为选集。
     * 非 playlist 时向前只到同 type 连续段开头、向后遇不同 type 即止；playlist 时按 extra.cls 分组、全表收集。
     * 过滤：header、空 url、含 ignoreVideo/ignoreMusic、含 @rule= 的条目；每集带 codeAndHeader 时另存 originalUrl。
     */
    private java.util.List<HkChapter> getChapters(String url, int position, String titlePrefix, String codeAndHeader) {
        java.util.List<HkChapter> chapters = new ArrayList<>();
        java.util.List<HkItem> list = adapter.getList();
        HkItem clicked = list.get(position);
        String type = clicked.getType();
        boolean playList = isPlayList(clicked);
        String playListCls = getPlayListCls(clicked);
        int start;
        if (!playList) {
            start = position;
            for (int i = position - 1; i >= 0; i--) {
                if (!TextUtils.equals(type, list.get(i).getType())) break;
                start = i;
            }
        } else {
            start = 0;
        }
        for (int i = start; i < position; i++) {
            HkItem it = list.get(i);
            if (!chapterCandidate(it, playList, playListCls)) continue;
            chapters.add(buildChapter(it, i, titlePrefix, codeAndHeader, false));
        }
        HkChapter cur = new HkChapter();
        cur.memoryTitle = clicked.getTitle();
        cur.title = getResourceTitle(titlePrefix, position);
        cur.url = url;
        cur.use = true;
        cur.realPos = position;
        cur.extra = extraJson(clicked);
        addChapterPic(cur, position);
        if (!TextUtils.isEmpty(codeAndHeader)) {
            cur.codeAndHeader = codeAndHeader;
            cur.originalUrl = clicked.getUrl();
        }
        chapters.add(cur);
        for (int i = position + 1; i < list.size(); i++) {
            HkItem it = list.get(i);
            if (!playList && !TextUtils.equals(type, it.getType())) break;
            if (!chapterCandidate(it, playList, playListCls)) continue;
            chapters.add(buildChapter(it, i, titlePrefix, codeAndHeader, false));
        }
        return chapters;
    }

    /**
     * 照抄原版 findMagnetList（ArticleListFragment.smali:8329）：以活动标题为前缀组装同组选集，
     * 只保留磁力/种子或 FTP/ed2k 的集，供迅雷带列表版入口使用。
     */
    private java.util.List<HkChapter> findMagnetList(String url, int position) {
        String title = activityTitle();
        if (!TextUtils.isEmpty(title)) title = title + "-";
        java.util.List<HkChapter> chapters = getChapters(url, position, title, null);
        java.util.List<HkChapter> result = new ArrayList<>();
        for (HkChapter chapter : chapters) {
            if (detailBridge.isMagnetOrTorrent(chapter.url) || detailBridge.isFTPOrEd2k(chapter.url)) {
                result.add(chapter);
            }
        }
        return result;
    }

    /** 照抄原版 lambda$clickItem$32：dealUrlSimply 的磁力 consumer——按链接类型分发迅雷带列表版入口。 */
    private void onMagnetRoute(int position, String magnetUrl) {
        if (detailBridge == null) return;
        java.util.List<HkChapter> list = findMagnetList(magnetUrl, position);
        if (detailBridge.isMagnetOrTorrent(magnetUrl)) {
            detailBridge.startDownloadMagnetList(getActivity(), magnetUrl, list);
        } else if (magnetUrl.split(";")[0].endsWith(".torrent")) {
            detailBridge.startParseTorrentList(getActivity(), magnetUrl, list);
        } else if (detailBridge.isFTPOrEd2k(magnetUrl)) {
            detailBridge.startParseFTPOrEd2kList(getActivity(), magnetUrl, list);
        }
    }

    /** getChapters 的条目过滤（原版三段循环共用）：非 header、url 非空、不含 ignoreVideo/ignoreMusic/@rule=、playlist 时 cls 相同。 */
    private boolean chapterCandidate(HkItem item, boolean playList, String playListCls) {
        if ("header".equals(item.getType())) return false;
        String url = item.getUrl();
        if (TextUtils.isEmpty(url)) return false;
        if (url.contains("ignoreVideo") || url.contains("ignoreMusic")) return false;
        if (url.contains("@rule=")) return false;
        if (playList && !isSamePlayList(playListCls, item)) return false;
        return true;
    }

    private HkChapter buildChapter(HkItem item, int pos, String titlePrefix, String codeAndHeader, boolean use) {
        HkChapter chapter = new HkChapter();
        chapter.memoryTitle = item.getTitle();
        chapter.title = getResourceTitle(titlePrefix, pos);
        chapter.url = item.getUrl();
        chapter.extra = extraJson(item);
        chapter.realPos = pos;
        addChapterPic(chapter, pos);
        chapter.use = use;
        if (!TextUtils.isEmpty(codeAndHeader)) {
            chapter.codeAndHeader = codeAndHeader;
            chapter.originalUrl = item.getUrl();
        }
        return chapter;
    }

    /** 照抄原版 isPlayList：extra.cls 非空且含 "playlist"。 */
    private boolean isPlayList(HkItem item) {
        if (item == null) return false;
        String cls = item.getExtra("cls");
        return !TextUtils.isEmpty(cls) && cls.contains("playlist");
    }

    /** 照抄原版 getPlayListCls：extra.cls（无则 ""）。 */
    private String getPlayListCls(HkItem item) {
        if (item == null) return "";
        String cls = item.getExtra("cls");
        return cls == null ? "" : cls;
    }

    /** 照抄原版 isSamePlayList：extra.cls 全等。 */
    private boolean isSamePlayList(String cls, HkItem item) {
        if (item == null) return false;
        return TextUtils.equals(cls, item.getExtra("cls"));
    }

    /** 照抄原版 getResourceTitle：inheritTitle 时只取条目标题文本，否则前缀+标题文本。 */
    private String getResourceTitle(String prefix, int position) {
        HkItem item = adapter.getList().get(position);
        String titleText = detailBridge == null ? item.getTitle() : detailBridge.getTitleText(item.getTitle());
        if ("true".equals(item.getExtra("inheritTitle"))) return titleText;
        return (prefix == null ? "" : prefix) + titleText;
    }

    /** 照抄原版 addChapterPic：条目 pic 非空且非 "*" 时带入选集（细则见施工图待复核项）。 */
    private void addChapterPic(HkChapter chapter, int position) {
        String pic = adapter.getList().get(position).getPic();
        if (!TextUtils.isEmpty(pic) && !"*".equals(pic)) chapter.pic = pic;
    }

    /** 照抄原版 $5.onClick：rich_text/long_text 点击不走 clickItem（阅读主题翻页分支不移植、结构保留）；其余先置 memoryPos=true 再 clickItem。 */
    protected void onItemClick(View view, int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return;
        String type = adapter.getList().get(position).getType();
        if (HkColType.RICH_TEXT.code.equals(type) || HkColType.LONG_TEXT.code.equals(type)) return;
        memoryPos = true;
        clickItem(view, position);
    }

    /** 照抄原版 $5.onUrlClick：memoryPos 先置 false，autoFixUrl 后 clickItem，再置回 true。 */
    protected void onItemUrlClick(View view, int position, String url) {
        memoryPos = false;
        String base = myUrl;
        if (TextUtils.isEmpty(base) && articleListRuleDTO != null) base = articleListRuleDTO.getUrl();
        String fixed = detailBridge == null || base == null ? url : detailBridge.autoFixUrl(base, url);
        clickItem(view, position, fixed);
        memoryPos = true;
    }

    /** 照抄原版 $5.onClassClick：按 desc 把 url 写进 DTO 对应组并设 firstHeader，然后 page=1 整页重载（params 第二参 true、ARTICLE_LIST_NEW）。 */
    protected void onClassClick(View view, String url, String desc) {
        if (articleListRuleDTO == null || desc == null) return;
        switch (desc) {
            case "area":
                articleListRuleDTO.setAreaUrl(url);
                articleListRuleDTO.setFirstHeader("area");
                break;
            case "sort":
                articleListRuleDTO.setSortUrl(url);
                articleListRuleDTO.setFirstHeader("sort");
                break;
            case "year":
                articleListRuleDTO.setYearUrl(url);
                articleListRuleDTO.setFirstHeader("year");
                break;
            case "class":
                articleListRuleDTO.setClassUrl(url);
                articleListRuleDTO.setFirstHeader("class");
                break;
            default:
                break;
        }
        page = 1;
        loading(true);
        loadingPage = page;
        model.params(getContext(), page, true, articleListRuleDTO, notLoadPreRule(), isFromHistory())
                .process("ARTICLE_LIST_NEW", this);
    }

    /** 照抄原版 $5.onCodeClick：开代码查看页，标题固定「代码」。 */
    protected void onCodeClick(View view, int position, String code) {
        if (pageOpener != null) pageOpener.openCodeView(code);
    }

    /** 照抄原版 $5.onLoadMore（页脚点击）：加载中直接返回；无 fypage 时 toast「没有下一页了哦！」；否则 isLoading=true、page++ 走 ARTICLE_LIST。 */
    protected void onLoadMoreFooter(View view, int position) {
        if (isLoading) return;
        if (articleListRuleDTO == null || articleListRuleDTO.getUrl() == null
                || !articleListRuleDTO.getUrl().contains("fypage")) {
            toastBottom("没有下一页了哦！");
            return;
        }
        isLoading = true;
        page++;
        loading(true);
        loadingPage = page;
        model.params(getContext(), page, false, articleListRuleDTO, notLoadPreRule(), isFromHistory())
                .process("ARTICLE_LIST", this);
    }

    /**
     * 照抄原版 $5.onLongClick：rich_text/long_text 弹双项对话框（自由复制/调试数据）；
     * 其余弹规则菜单（8 个标准项 + extra.longClick 附加项；hiker://home@ 的规则库附加项由宿主规则库子系统在桥绑定阶段补，见施工图）。
     * 原版菜单是 XPopup 的 RuleMenuPopup，星辰用原生 HkCenterListPopup 等价。
     */
    protected void onItemLongClick(View view, int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return;
        if (getContext() == null) return;
        HkItem item = adapter.getList().get(position);
        String rawUrl = item.getUrl();
        String url = rawUrl == null ? "" : rawUrl;
        String[] lazySplit = url.split("@rule=")[0].split("@lazyRule=");
        String type = item.getType();
        if (HkColType.RICH_TEXT.code.equals(type) || HkColType.LONG_TEXT.code.equals(type)) {
            String[] items = {"自由复制", "调试数据"};
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setItems(items, (dialog, which) -> {
                        dealLongMenuClick(view, which == 0 ? "自由复制" : "调试数据", position, lazySplit);
                        dialog.dismiss();
                    })
                    .show();
            return;
        }
        java.util.List<String> titles = new ArrayList<>(java.util.Arrays.asList(
                "访问链接", "网站首页", "下载资源", "批量下载", "连续投屏", "批量投屏", "调试数据", "复制链接"));
        java.util.List<String[]> longClicks = parseLongClicks(item);
        for (String[] lc : longClicks) titles.add(lc[0]);
        int standardCount = 8;
        HkCenterListPopup popup = new HkCenterListPopup(requireContext());
        popup.with(titles, 1, new HkCenterListPopup.ClickListener() {
            @Override
            public void click(String text, int index) {
                if (index < standardCount) {
                    dealLongMenuClick(view, text, position, lazySplit);
                } else if (index - standardCount < longClicks.size()) {
                    runLongClickExtra(view, position, longClicks.get(index - standardCount));
                }
            }

            @Override
            public void onLongClick(String text, int index) {
                click(text, index);
            }
        });
        popup.show();
    }

    /** 解析条目 extra 里的 longClick 附加项（原版 BaseExtra.longClick：[{title, js}]），返回 [title, js] 列表。 */
    private java.util.List<String[]> parseLongClicks(HkItem item) {
        java.util.List<String[]> out = new ArrayList<>();
        String raw = item.getExtra("longClick");
        if (TextUtils.isEmpty(raw)) return out;
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(raw);
            if (el.isJsonArray()) {
                for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    com.google.gson.JsonObject o = e.getAsJsonObject();
                    String title = o.has("title") ? o.get("title").getAsString() : "";
                    String js = o.has("js") ? o.get("js").getAsString() : "";
                    if (!TextUtils.isEmpty(title)) out.add(new String[]{title, js});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 照抄原版 $5 的 longClick 附加项执行：后台 evalJS（规则上下文 + MY_URL 由引擎桥拼装），input=附加项标题；结果非空且非 "undefined" 时回主线程 clickItem 回灌。 */
    private void runLongClickExtra(View view, int position, String[] titleAndJs) {
        HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
        if (bridge == null) return;
        String js = titleAndJs[1];
        String title = titleAndJs[0];
        Task.execute(() -> {
            String result = bridge.evalWithRule(articleListRule, js, title);
            if (!TextUtils.isEmpty(result) && !"undefined".equalsIgnoreCase(result)
                    && getActivity() != null && !getActivity().isFinishing()) {
                getActivity().runOnUiThread(() -> clickItem(view, position, result));
            }
        });
    }

    /** 照抄原版 dealLongMenuClick：13 个菜单项按标题分发（守卫 toast 与文案逐字）。 */
    private void dealLongMenuClick(View view, String title, int position, String[] lazySplit) {
        if (getContext() == null || title == null) return;
        switch (title) {
            case "下载资源":
                downloadSource(position, lazySplit);
                break;
            case "分享规则":
                if (detailBridge != null) detailBridge.shareRule(articleListRule, false);
                break;
            case "复制链接": {
                String itemUrl = getItemUrl(position, lazySplit);
                if (!TextUtils.isEmpty(itemUrl)) {
                    copyToClipboard(detailBridge == null ? itemUrl : detailBridge.getFirstPageUrl(itemUrl));
                } else {
                    toastBottom("链接为空，请检查规则");
                }
                break;
            }
            case "快速搜索": {
                HkItem item = adapter.getList().get(position);
                String t = detailBridge == null ? item.getTitle() : detailBridge.getTitleText(item.getTitle());
                if (TextUtils.isEmpty(t)) {
                    toastCenter("标题为空，无法搜索");
                    break;
                }
                android.content.SharedPreferences sp =
                        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext());
                String group = sp.getString("searchGroup", "全部");
                String engine = sp.getString("searchEngine", "百度");
                if (detailBridge != null) {
                    detailBridge.isRoute(getActivity(),
                            "hiker://search?simple=false&ruleGroup=" + group + "&rule=" + engine + "&s=" + t,
                            null, null);
                }
                break;
            }
            case "批量下载":
                if (batchDlanSelecting) {
                    toastCenter("当前正在批量投屏中，不支持此操作");
                    return;
                }
                showBatchDownloadConfirm();
                break;
            case "批量投屏":
                if (batchDownloadSelecting) {
                    toastCenter("当前正在批量下载中，不支持此操作");
                    return;
                }
                if (continuousDlanMode != -1) {
                    toastCenter("当前正在连续投屏中，不支持此操作");
                    return;
                }
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("批量投屏")
                        .setMessage("点击确定后，点击页面中要投屏的内容即可快速添加到投屏队列，注意是投屏到世界直播，不是DLNA投屏！添加完成后请点击右下角的完成按钮结束批量投屏模式")
                        .setPositiveButton("确定", (dialog, which) -> {
                            batchDlanSelecting = true;
                            showComplete(true);
                            toastBottom("已开启批量投屏模式");
                        })
                        .setNegativeButton("取消", null)
                        .show();
                break;
            case "更多分享":
                if (!mainHost) {
                    toastCenter("当前页面不支持此操作");
                    break;
                }
                if (detailBridge != null) detailBridge.shareRule(articleListRule, true);
                break;
            case "网站首页":
                if (detailBridge != null) {
                    detailBridge.goWeb(getContext(), detailBridge.getHomeUrl(articleListRuleDTO.getUrl()));
                }
                break;
            case "编辑规则":
                if (!mainHost) {
                    toastCenter("当前页面不支持此操作");
                    break;
                }
                if (pageOpener != null) {
                    pageOpener.openRuleEditor(new com.google.gson.Gson().toJson(articleListRule));
                }
                break;
            case "自由复制":
                if (detailBridge != null) {
                    detailBridge.showCopyPopup(adapter.getList().get(position).getTitle());
                }
                break;
            case "调试数据":
                try {
                    showPosDetail(position);
                } catch (Exception e) {
                    timber.log.Timber.e(e);
                }
                break;
            case "访问链接": {
                String itemUrl = getItemUrl(position, lazySplit);
                if (!TextUtils.isEmpty(itemUrl)) {
                    android.os.Bundle bundle = new android.os.Bundle();
                    if (detailBridge != null) {
                        String vc = detailBridge.getViewCollectionExtraData(getActivity(), articleListRule.getUrl());
                        if (!TextUtils.isEmpty(vc)) bundle.putString("viewCollectionExtraData", vc);
                    }
                    if (getActivity() != null && getActivity().getTitle() != null) {
                        bundle.putString("film", getActivity().getTitle().toString());
                    }
                    if (detailBridge != null) {
                        detailBridge.goWebWithExtraData(getContext(), detailBridge.getFirstPageUrl(itemUrl), bundle);
                    }
                } else {
                    toastBottom("链接为空，请检查规则");
                }
                break;
            }
            case "连续投屏": {
                if (batchDownloadSelecting) {
                    toastCenter("当前正在批量下载中，不支持此操作");
                    return;
                }
                if (batchDlanSelecting) {
                    toastCenter("当前正在批量投屏中，不支持此操作");
                    return;
                }
                HkCenterListPopup popup = new HkCenterListPopup(requireContext());
                popup.withTitle("连续投屏模式");
                popup.with(java.util.Arrays.asList("DLNA投屏", "网页投屏", "投屏直链", "世界直播"), 2,
                        new HkCenterListPopup.ClickListener() {
                            @Override
                            public void click(String text, int index) {
                                continuousDlanMode = index + 1;
                                continuousDlanHis.clear();
                                toastBottom("已投屏所选内容，接下来点哪个投哪个哦~");
                                showComplete(false);
                                HkItem item = adapter.getList().get(position);
                                clickItem(view, position, item.getUrl());
                            }

                            @Override
                            public void onLongClick(String text, int index) {
                                click(text, index);
                            }
                        });
                popup.show();
                break;
            }
            default:
                break;
        }
    }

    /** 照抄原版批量下载确认弹窗（XPopup asConfirm 的原生等价）：「确定」进批量选择模式；「下载全部」再弹二次确认。 */
    private void showBatchDownloadConfirm() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("批量下载")
                .setMessage("点击确定后，点击页面中要下载的内容即可快速添加到下载队列，添加完成后请点击右下角的完成按钮结束批量下载模式")
                .setPositiveButton("确定", (dialog, which) -> {
                    batchDownloadSelecting = true;
                    if (notify_bg != null) notify_bg.setVisibility(View.VISIBLE);
                    toastBottom("已开启批量下载模式");
                })
                .setNegativeButton("下载全部", (dialog, which) -> showDownloadAllConfirm())
                .show();
    }

    /** 照抄原版「下载全部」二次确认弹窗：确定后 batchDownloadSelecting+batchDownloadAll 双置真、亮 notify 条。 */
    private void showDownloadAllConfirm() {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("下载全部")
                .setMessage("如想要下载全部剧集，请先点击要下载的第一项，然后软件就会陆续解析当前选择和后面的所有剧集，解析到下载地址后会加入下载任务，注意在未完成所有下载任务添加前不要离开页面，添加完任务会弹窗提示，此时即可离开页面，确定开始吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    batchDownloadSelecting = true;
                    batchDownloadAll = true;
                    if (notify_bg != null) notify_bg.setVisibility(View.VISIBLE);
                    toastBottom("请点击要开始下载的第一项");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 对应原版 showComplete/CompleteToolBar：宿主完成工具栏在桥绑定阶段承接；未接入时以 notify 条的显隐代替（仅 show 侧）。 */
    private void showComplete(boolean show) {
        if (show && notify_bg != null) notify_bg.setVisibility(View.VISIBLE);
    }

    /** 照抄原版 getItemUrl：lazySplit[0] 为空或非 http 时，回退条目 extra 里的 url 字段（须非空且 http 开头）。 */
    private String getItemUrl(int position, String[] lazySplit) {
        String url = lazySplit.length > 0 ? lazySplit[0] : "";
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            try {
                String extraUrl = adapter.getList().get(position).getExtra("url");
                if (!TextUtils.isEmpty(extraUrl) && extraUrl.startsWith("http")) return extraUrl;
            } catch (Exception ignored) {
            }
        }
        return url;
    }

    /** 照抄原版 showPosDetail 入口：FileDetailPopup「调试数据」（数据组装与弹窗在桥绑定阶段承接）。 */
    private void showPosDetail(int position) {
        if (detailBridge != null) {
            detailBridge.showPosDetail(getActivity(), adapter.getList().get(position), articleListRule, position);
        }
    }

    private void copyToClipboard(String text) {
        if (getContext() == null || text == null) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("", text));
    }

    /** 对应 ToastMgr.shortBottomCenter。 */
    private void toastBottom(String text) {
        if (getContext() == null) return;
        android.widget.Toast toast = android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT);
        toast.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL, 0, 0);
        toast.show();
    }

    /** 宿主高亮编辑页按原版 SettingConfig.highlightRule 模式回填的静态槽（onActivityResult 0x2382 消费后清空）。 */
    public static String pendingHighlightResult = "";

    private android.webkit.ValueCallback<android.net.Uri[]> filePathCallback;

    /** 宿主行内 WebView 文件上传时登记（对应原版 filePathCallback 字段）。 */
    public void setFilePathCallback(android.webkit.ValueCallback<android.net.Uri[]> callback) {
        this.filePathCallback = callback;
    }

    /** 照抄原版 onActivityResult：0x2382 高亮回填、0x2383 WebView 文件上传回填、0x2385 fileSelect 回流。 */
    @Override
    public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0x2382) {
            if (highlightCallback != null) {
                highlightCallback.accept(pendingHighlightResult);
                highlightCallback = null;
                pendingHighlightResult = "";
            }
            return;
        }
        if (requestCode == 0x2383) {
            if (filePathCallback != null) {
                if (resultCode == android.app.Activity.RESULT_OK) {
                    if (data != null && data.getClipData() != null) {
                        android.content.ClipData clip = data.getClipData();
                        android.net.Uri[] uris = new android.net.Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            uris[i] = clip.getItemAt(i).getUri();
                        }
                        filePathCallback.onReceiveValue(uris);
                    } else if (data != null && data.getData() != null) {
                        filePathCallback.onReceiveValue(new android.net.Uri[]{data.getData()});
                    } else {
                        filePathCallback.onReceiveValue(new android.net.Uri[]{null});
                    }
                } else if (resultCode == 0) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == 0x2385) {
            if (TextUtils.isEmpty(fileSelectJs)) return;
            if (resultCode != android.app.Activity.RESULT_OK) return;
            android.net.Uri uri = data == null ? null : data.getData();
            if (uri == null) return;
            String js = fileSelectJs;
            fileSelectJs = null;
            String fileName = queryFileName(uri);
            String dirName = "_fileSelect_" + fileName;
            showLoadingPopup("文件提取中，请稍候", false);
            Task.execute(() -> {
                try {
                    if (getContext() == null) return;
                    java.io.File root = getContext().getExternalFilesDir(null);
                    if (root == null) root = getContext().getFilesDir();
                    java.io.File dir = new java.io.File(new java.io.File(root, "cache"), dirName);
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File out = new java.io.File(dir, TextUtils.isEmpty(fileName) ? "file" : fileName);
                    copyUriToFile(uri, out);
                    onFileSelectCopied(uri, js, dirName, out.getAbsolutePath());
                } catch (Exception ignored) {
                    // 原版 failed() 为空实现：拷贝失败不关弹窗、不回流，照抄
                }
            });
        }
    }

    /** 照抄 $15.success：活动已结束则整段不执行；后台求值（MY_PATH=file uri 取其 path、否则取拷贝路径），结果回主线程关弹窗并 clickItem 回灌。 */
    private void onFileSelectCopied(android.net.Uri uri, String js, String input, String copiedPath) {
        if (getActivity() == null || getActivity().isFinishing()) return;
        HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
        if (bridge == null) return;
        String myPath = "file".equals(uri.getScheme()) ? uri.getPath() : copiedPath;
        String result = bridge.evalFileSelect(articleListRule, js, input, myUrl, myPath);
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                dismissLoadingPopup();
                if (!TextUtils.isEmpty(result) && !"undefined".equalsIgnoreCase(result)) {
                    clickItem(null, fileSelectPosition, result);
                }
            });
        }
    }

    /** 对应 UriUtils.getFileName：先查 DISPLAY_NAME，失败取路径最后一段。 */
    private String queryFileName(android.net.Uri uri) {
        String name = null;
        if (getContext() != null && "content".equals(uri.getScheme())) {
            try (android.database.Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Exception ignored) {
            }
        }
        if (TextUtils.isEmpty(name)) {
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
            }
        }
        return name == null ? "" : name;
    }

    private void copyUriToFile(android.net.Uri uri, java.io.File out) throws java.io.IOException {
        try (java.io.InputStream in = getContext().getContentResolver().openInputStream(uri);
             java.io.OutputStream os = new java.io.FileOutputStream(out)) {
            if (in == null) throw new java.io.IOException("openInputStream null");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
    }

    /** 照抄原版 lambda$initView$3（加载更多）：#autoReader# 走阅读子系统（不移植）；无 fypage 时 #autoPage# 走自动翻页、否则直接收圈；有 fypage 才真翻页。 */
    private void onLoadMore() {
        String url = articleListRuleDTO == null ? "" : articleListRuleDTO.getUrl();
        if (url.contains("#autoReader#")) {
            // 阅读子系统不移植：原版此处调 autoReader(true, true)
            return;
        }
        if (!url.contains("fypage")) {
            if (url.contains("#autoPage#")) {
                autoPage(true, true);
            } else if (smartRefreshLayout != null) {
                smartRefreshLayout.finishLoadMore(true);
            }
            return;
        }
        page++;
        if (adapter == null || adapter.getItemCount() == 0) page = 1;
        loading(true);
        loadingPage = page;
        articleListModel.params(requireContext(), page, false, articleListRuleDTO,
                        notLoadPreRule(), isFromHistory())
                .process("ARTICLE_LIST", this);
    }

    /** 对应原版 AutoPageData（宿主在 dealRule 打开 #autoPage# 子列表时构造，子页 Fragment 经 setAutoPageData 注入）。 */
    public static class AutoPageData {
        public java.util.List<HkItem> nextData;
        public String url;
        public String title;
        public int currentPos;
        public boolean noRecordHistory;
        public int chapterCount;
        public int chapterStartIndex;
    }

    private AutoPageData autoPageData;

    /** 宿主注入（对应原版 FilmListActivity.getParentData()/tempParentData）。 */
    public void setAutoPageData(AutoPageData data) {
        this.autoPageData = data;
    }

    /** 宿主记录续看位置时取条目（原版宿主与 Fragment 同进程可直接读列表，等价开放）。 */
    public HkItem getItemAt(int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return null;
        return adapter.getList().get(position);
    }

    /**
     * 照抄原版 autoPage(boolean p1, boolean recordHistory)：非列表宿主或无续页数据时只收加载圈；
     * 加载中直接返回；否则 page++ 后以 nextData[page-1] 的 url 组装 DTO 克隆（hiker://page/ 走 PageParser.getUrl、
     * 其余只换 url 的 ";" 首段并剥 @rule=），params=next 条目 extra，ARTICLE_LIST 续页；
     * 异常时 loading(false)、page-- 并 toast「出错：」+消息。
     */
    private void autoPage(boolean p1, boolean recordHistory) {
        if (!listHost) {
            if (smartRefreshLayout != null) smartRefreshLayout.finishLoadMore(true);
            return;
        }
        AutoPageData data = autoPageData;
        if (data == null || data.nextData == null || data.nextData.isEmpty()) {
            if (smartRefreshLayout != null) smartRefreshLayout.finishLoadMore(true);
            return;
        }
        if (isLoading) return;
        try {
            page++;
            if (p1) loading(true);
            else isLoading = true;
            HkRule dto = articleListRuleDTO.copyRule();
            if (page > data.nextData.size()) {
                loading(false);
                page--;
                return;
            }
            HkItem next = data.nextData.get(page - 1);
            if (next.getUrl() == null) {
                loading(false);
                page--;
                return;
            }
            if (next.getUrl().startsWith("hiker://page/")) {
                HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
                String resolved = bridge == null ? next.getUrl()
                        : bridge.pageParserGetUrl(next.getUrl(), extraJson(next));
                dto.setUrl(resolved);
            } else {
                String[] parts = dto.getUrl() == null ? new String[]{""} : dto.getUrl().split(";");
                parts[0] = next.getUrl().split("@rule=")[0].split(";")[0];
                dto.setUrl(arrayToString(parts, 0, ";"));
            }
            dto.setParams(extraJson(next));
            loadingPage = page;
            model.params(getContext(), page, false, dto, notLoadPreRule(), isFromHistory())
                    .process("ARTICLE_LIST", this);
            if (!data.noRecordHistory && recordHistory && page <= data.nextData.size()) {
                if (hostListener != null) {
                    hostListener.onAutoPageLastClick(data, data.nextData.get(page - 1), data.currentPos + page - 1);
                }
            }
        } catch (Exception e) {
            loading(false);
            page--;
            toastBottom("出错：" + e.getMessage());
            timber.log.Timber.e(e);
        }
    }

    /** 照抄原版 loadChapterCount：统计被点条目所在同 type 连续段的总长（chapterCount）与被点条目在段内的 1 起序号（chapterStartIndex），count>0 才写入。 */
    private void loadChapterCount(AutoPageData data, int position) {
        if (adapter == null || position < 0 || position >= adapter.getList().size()) return;
        java.util.List<HkItem> list = adapter.getList();
        String type = list.get(position).getType();
        int start = position;
        for (int i = position - 1; i >= 0; i--) {
            if (!TextUtils.equals(type, list.get(i).getType())) break;
            start = i;
        }
        int count = position - start;
        int startIndex = count + 1;
        for (int i = position; i < list.size(); i++) {
            if (!TextUtils.equals(type, list.get(i).getType())) break;
            count++;
        }
        if (count > 0) {
            data.chapterCount = count;
            data.chapterStartIndex = startIndex;
        }
    }

    // —— 其余 initView 挂载点（随各切片转写）——

    /** 照抄原版 hookBackJs：给 onRefreshJS 注入 back() 定义（宿主 method_back 在引擎侧承接）。 */
    private String hookBackJs() {
        return "var backTag = false;\nback = (param0) => {\n   if(!backTag) {       method_back.invoke(javaContext, param0);\n       backTag = true;\n   } else log('back 调用多次被拦截')\n}\n";
    }

    /** 照抄原版 lambda$refresh$10（refresh 的后台段）：取走并清空 onRefreshJS，拼 getMyRule+hookBackJs 后求值，再回 UI 线程走 lambda$refresh$9 重新取数。 */
    private void runOnRefreshJs() {
        String js = onRefreshJS;
        onRefreshJS = null;
        HkArticleListModel.EngineBridge bridge = HkArticleListModel.getEngineBridge();
        if (bridge != null && js != null) {
            bridge.evalWithRule(articleListRule, hookBackJs() + js, "");
        }
        if (getActivity() != null && !getActivity().isFinishing()) {
            getActivity().runOnUiThread(() -> {
                loadingPage = page;
                articleListModel.params(requireContext(), page, true, articleListRuleDTO,
                                notLoadPreRule(), isFromHistory())
                        .process("ARTICLE_LIST_NEW", this);
            });
        }
    }

    private void onFastScrollPressed(boolean pressed) {
    }

    private void onFastScrollOffset(int offset) {
    }

    private void onListScrolled(int dx, int dy) {
    }

    private void onNotifyQueueClick() {
    }

    private void onNotifyClick() {
    }

    private boolean onNotifyLongClick() {
        return false;
    }

    private int dpToPx(int dp) {
        return com.fongmi.android.tv.utils.ResUtil.dp2px(dp);
    }

    private int pxToDp(int px) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (px / density + 0.5f);
    }

    // —— 对外访问器（宿主 Activity 用，对应原版同名 getter/setter 集）——

    public List<HkItem> getArticleList() {
        return adapter == null ? new ArrayList<>() : adapter.getList();
    }

    public String getMyUrl() {
        return myUrl;
    }

    public HkWebViewHolder getWebViewHolder() {
        return webViewHolder;
    }

    public boolean isImmersiveTheme() {
        return immersiveTheme;
    }

    public void setImmersiveTheme(boolean immersiveTheme) {
        this.immersiveTheme = immersiveTheme;
        if (adapter != null) adapter.setImmersiveTheme(immersiveTheme);
    }

    public void setLoadListener(LoadListener loadListener) {
        this.loadListener = loadListener;
    }

    public LoadListener getLoadListener() {
        return loadListener;
    }

    public void setScrollListener(RecyclerView.OnScrollListener scrollListener) {
        this.scrollListener = scrollListener;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this);
    }
}
