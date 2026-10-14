package com.fongmi.android.tv.ui.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkErrorUtil;
import com.fongmi.android.tv.api.hk.HkHttp;
import com.fongmi.android.tv.api.hk.HkImage;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.databinding.FragmentHkContentBinding;
import com.fongmi.android.tv.ui.base.BaseFragment;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 星辰小程序内容页（V_CONTENT）Fragment。
 *
 * <p>自 HkPageActivity 提取的内容页 UI 与页面状态：列表网格、顶部小程序 tabs、
 * 静态分类 tab 行（class/area/year/sort）、JS 动态 tab 行、内嵌搜索条、筛选行、
 * 下拉刷新/滚动翻页、底部 footer、空态/loading 视图。</p>
 *
 * <p>职责划分：<br>
 * - Fragment 拥有内容页全部状态（videos、cls/area/year/sort、page、loading/noMore、
 * contentGen、contentTitle、hkPageUrl）并实现 {@link #loadContent(boolean)}；<br>
 * - 需要 Activity 协作的一律走 {@link HkPageHost}：
 * {@code getHkRouter()}/{@code getCurrentRule()} 取规则与路由，
 * {@code hostOpenSearch(kw)} 打开搜索页，
 * {@code hostShowLoading()}/{@code hostHideLoading()} 管 preRule loading 弹窗，
 * 条目点击/直接播放由 Activity 的 ContentAdapter 经
 * {@code hostOnContentItemClick}/{@code hostPlayContentDirect} 处理；<br>
 * - 视图栈（pushView/showView）、router 生命周期、小程序切换的规则装配
 * （openRule）、子列表压栈（openSubList/backSubList 的栈操作）仍在 Activity，
 * Fragment 只暴露 {@link #onRuleChanged()}、{@link #onEnterSubList(String)}、
 * restoreContentState(...) 等状态方法供 Activity 调用。</p>
 *
 * <p>Activity 接线清单：<br>
 * 1. {@code fragment.setContentAdapter(new ContentAdapter(...))} 注入列表适配器
 * （ContentAdapter 独立提取后请实现 {@link ContentListAdapter}，
 * 以便 Fragment 调用 refreshGroups()/spanFor()）；<br>
 * 2. {@code fragment.setOnRulePickListener(activity::openRule)} 接小程序 tabs/全部弹窗的切换；<br>
 * 3. 规则切换后调 {@code fragment.onRuleChanged()}；<br>
 * 4. {@code hostLoadContent(reset)} 实现为 {@code fragment.loadContent(reset)} 的转发，
 * 供规则页/其它 Fragment 触发内容加载；<br>
 * 5. 子列表：进子列表时先保存 {@link #getVideos()} 等状态再调
 * {@code fragment.onEnterSubList(title)}；返回时调
 * {@link #restoreContentState(...)}。</p>
 *
 * <p>未迁移（仍在 Activity / 随 ContentAdapter 独立提取）：contentTypeOf、
 * setContentClick、showContentLongMenu（ContentAdapter 内部逻辑），
 * openContentSearch（规则页 🔍 长按的跨搜入口），fromContent（搜索页 VideoAdapter 字段）。</p>
 */
public class HkContentFragment extends BaseFragment {

    /**
     * 内容列表适配器契约。Activity 注入的 ContentAdapter 实现此接口后，
     * Fragment 才能在数据变更时刷新 poly 分组、在 SpanSizeLookup 里按 col_type 取 span。
     * 未实现时退化为普通 notifyDataSetChanged（span 统一 60 全宽）。
     */
    public interface ContentListAdapter {
        void refreshGroups();
        int spanFor(int position);
    }

    /** 小程序 tabs /「全部」弹窗选中小程序时的回调，由 Activity 实现（调 openRule）。 */
    public interface OnRulePickListener {
        void onPickRule(HkRule rule);
    }

    // ================= 状态字段（自 HkPageActivity 提取） =================

    private FragmentHkContentBinding binding;
    /** 由 Activity 注入的 ContentAdapter（提取为独立类之前为 HkPageActivity 的内部类）。 */
    private RecyclerView.Adapter<?> contentAdapter;
    private final List<HkItem> videos = new ArrayList<>();
    private GridLayoutManager contentGrid;
    /** 内容页标题（顶栏已删除，用字段保存，子列表栈恢复用）。 */
    private String contentTitle = "";
    private int contentGen = 0;
    /** onRuleChanged 在 Fragment 未就绪时不丢弃：置位，就绪后在 onViewCreated 末尾重放 */
    private boolean pendingRuleChanged;
    private final List<HkRule> miniappRules = new ArrayList<>();
    /** 实际抓取的页面 URL（图片自动 Referer 用）。volatile：后台线程写、UI 线程读。 */
    private volatile String hkPageUrl;
    private String cls = "", area = "", year = "", sort = "";
    /** 每行分类 tab 当前选中的名字（8.83 ArticleList.setPic 等价，用于选中高亮）。 */
    private String clsName = "", areaName = "", yearName = "", sortName = "";
    private int page = 1;
    private boolean loading, noMore;
    private OnRulePickListener rulePickListener;
    private boolean viewInited = false;

    // ================= 生命周期 =================

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding = FragmentHkContentBinding.inflate(inflater, container, false);
        return binding;
    }

    @Override
    protected void initView() {
        initContentView();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    public void onDestroyView() {
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable ignored) {
        }
        super.onDestroyView();
        binding = null;
        contentGrid = null;
        viewInited = false;
    }

    /**
     * 8.83 refreshPage(true) 对齐：内容列表回到顶部。
     * 供 HkPageActivity 在消费 refreshPage 请求且 isRefreshToTop() 为 true 时调用。
     */
    public void scrollContentToTop() {
        try {
            if (binding != null && binding.rvVideos != null) {
                binding.rvVideos.scrollToPosition(0);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 8.83 ArticleListFragment.onItemModify(26267) 对齐：JS 的 updateItem/deleteItem/
     * deleteItemByCls 经 HkJsRuntime post ItemModifyEvent，内容页收到后就地更新已展示列表。
     * （refreshPage 走 flag 整页重刷；addItem 系列走快照合并；此处补 UPDATE/DELETE 缺口。）
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onItemModify(com.fongmi.android.tv.api.hk.event.rule.ItemModifyEvent event) {
        if (event == null || videos == null || videos.isEmpty()) return;
        try {
            com.fongmi.android.tv.api.hk.event.rule.ItemModifyEvent.Action action = event.getAction();
            if (action == com.fongmi.android.tv.api.hk.event.rule.ItemModifyEvent.Action.DELETE) {
                boolean changed = false;
                // 按 cls 删除（deleteItemByCls）
                String cls = event.getCls();
                if (!TextUtils.isEmpty(cls)) {
                    for (int i = videos.size() - 1; i >= 0; i--) {
                        HkItem it = videos.get(i);
                        if (it != null && cls.equals(it.getColType())) {
                            videos.remove(i);
                            changed = true;
                        }
                    }
                } else {
                    // 按 id（url）删除（deleteItem）
                    Object o = event.getArticleList();
                    String id = itemIdOf(o);
                    if (!TextUtils.isEmpty(id)) {
                        for (int i = videos.size() - 1; i >= 0; i--) {
                            HkItem it = videos.get(i);
                            if (it != null && id.equals(it.getUrl())) {
                                videos.remove(i);
                                changed = true;
                                break;
                            }
                        }
                    }
                }
                if (changed && contentAdapter != null) contentAdapter.notifyDataSetChanged();
            } else if (action == com.fongmi.android.tv.api.hk.event.rule.ItemModifyEvent.Action.UPDATE) {
                // 按 id（url）就地更新（updateItem）
                Object o = event.getArticleList();
                String id = itemIdOf(o);
                if (!TextUtils.isEmpty(id) && o instanceof HkItem) {
                    HkItem src = (HkItem) o;
                    for (int i = 0; i < videos.size(); i++) {
                        HkItem it = videos.get(i);
                        if (it != null && id.equals(it.getUrl())) {
                            copyItemFields(it, src);
                            if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
                            break;
                        }
                    }
                }
            }
            // ADD 走 HkPageActivity 的快照合并（mergeActionItems），此处不处理，避免重复。
        } catch (Throwable ignored) {
        }
    }

    /** 取条目的 id（url 优先，与 HkJsRuntime.findResultIndex 一致）。 */
    private static String itemIdOf(Object o) {
        if (o instanceof HkItem) {
            String u = ((HkItem) o).getUrl();
            return u == null ? "" : u;
        }
        return "";
    }

    /** 把 src 的展示字段拷贝到 dst（就地更新，不替换对象引用）。 */
    private static void copyItemFields(HkItem dst, HkItem src) {
        if (dst == null || src == null) return;
        if (src.getTitle() != null) dst.setTitle(src.getTitle());
        if (src.getUrl() != null) dst.setUrl(src.getUrl());
        if (src.getPic() != null) dst.setPic(src.getPic());
        if (src.getDesc() != null) dst.setDesc(src.getDesc());
        if (src.getColType() != null) dst.setColType(src.getColType());
    }

    // ================= Host 协作 =================

    private HkPageHost host() {
        try {
            return (HkPageHost) requireActivity();
        } catch (Throwable t) {
            return null;
        }
    }

    private HkRule currentRule() {
        try {
            HkPageHost h = host();
            return h == null ? null : h.getCurrentRule();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private HkRouter getRouter() throws Exception {
        HkPageHost h = host();
        Object o = h == null ? null : h.getHkRouter();
        if (!(o instanceof HkRouter)) throw new Exception("HkRouter unavailable");
        HkRouter router = (HkRouter) o;
        // 图片 @js= 求值依赖当前规则的 $.require/pages，登记当前 runtime（8.83 JSEngine.getInstance() 同款语义）
        HkImage.setCurrentRuntime(router.getEngine().getJsRuntime());
        return router;
    }

    private void hideHkLoading() {
        try {
            HkPageHost h = host();
            if (h != null) h.hostHideLoading();
        } catch (Throwable ignored) {
        }
    }

    /** 页面是否正在加载（供 Activity 做转圈互斥判断） */
    public boolean isPageLoading() {
        return loading;
    }

    // ===== 转圈单一状态机：同一时刻整个页面只许一个转圈 =====
    // 三个候选：下拉刷新圈（用户下拉触发的加载用它）、顶部进度线（程序触发的整页加载用它，8.83 原版样式）、
    // 弹窗转圈（规则脚本 showLoading 时用它，在 Activity 手里，本类只记状态）。
    // 以前三者各行其是，用户实测同屏出现两个甚至三个转圈。此后所有开关都过 reconcile。
    private boolean pullRefreshInFlight;
    private boolean inlineSpinnerWanted;
    private boolean dialogSpinnerUp;

    /** 弹窗转圈状态变化（Activity 调用）：弹窗在时顶部进度线让位 */
    public void onDialogSpinnerChanged(boolean showing) {
        dialogSpinnerUp = showing;
        reconcileLoadingUi();
    }

    private void reconcileLoadingUi() {
        try {
            if (binding == null) return;
            if (!loading) {
                binding.swipeContent.setRefreshing(false);
                binding.progressTop.setVisibility(View.GONE);
                return;
            }
            if (pullRefreshInFlight) {
                binding.swipeContent.setRefreshing(true);
                binding.progressTop.setVisibility(View.GONE);
            } else {
                binding.swipeContent.setRefreshing(false);
                binding.progressTop.setVisibility(
                        inlineSpinnerWanted && !dialogSpinnerUp ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 对外 API（Activity 调用） =================

    /**
     * 由 Activity 注入内容列表适配器（ContentAdapter 提取为独立类后实现 ContentListAdapter）。
     * 若视图已创建则立即挂载。
     */
    public void setContentAdapter(RecyclerView.Adapter<?> adapter) {
        this.contentAdapter = adapter;
        try {
            if (binding != null) binding.rvVideos.setAdapter(adapter);
        } catch (Throwable ignored) {
        }
    }

    public RecyclerView.Adapter<?> getContentAdapter() {
        return contentAdapter;
    }

    public void setOnRulePickListener(OnRulePickListener listener) {
        this.rulePickListener = listener;
    }

    public List<HkItem> getVideos() {
        return videos;
    }

    public String getHkPageUrl() {
        return hkPageUrl;
    }

    public int getPage() {
        return page;
    }

    public String getCls() {
        return cls;
    }

    public String getArea() {
        return area;
    }

    public String getYear() {
        return year;
    }

    public String getSort() {
        return sort;
    }

    public String getClsName() {
        return clsName;
    }

    public String getAreaName() {
        return areaName;
    }

    public String getYearName() {
        return yearName;
    }

    public String getSortName() {
        return sortName;
    }

    public String getContentTitle() {
        return contentTitle;
    }

    public int getFirstVisiblePosition() {
        try {
            return contentGrid == null ? 0 : contentGrid.findFirstVisibleItemPosition();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public void scrollToPosition(int pos) {
        try {
            if (contentGrid != null && pos > 0) contentGrid.scrollToPosition(pos);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 规则切换后重置内容页状态并重载（Activity 的 openRule 中，装配好新规则后调用）。
     * 对应原 openRule 的内容页部分：清 hkPageUrl/筛选/分页/列表 → 重建 tabs/分类/搜索条 → loadContent(true)。
     * scrollMem 恢复由 Activity 在调用后用 {@link #scrollToPosition(int)} 完成。
     */
    public void onRuleChanged() {
        if (!isAdded() || binding == null) {
            // 未就绪不丢弃（第一次进入时调用方与 attach 存在竞态，丢了就永远不加载）
            pendingRuleChanged = true;
            return;
        }
        hkPageUrl = null; // 切规则：实际抓取页 URL 失效，下次 loadContent 重记
        // preRule loading 桥：切规则时旧弹窗先关，避免残留
        hideHkLoading();
        cls = "";
        area = "";
        year = "";
        sort = "";
        clsName = "";
        areaName = "";
        yearName = "";
        sortName = "";
        page = 1;
        noMore = false;
        loading = false; // 旧规则的在途加载作废，由新 loadContent 接管
        contentGen++; // 旧回调过期
        videos.clear();
        refreshAdapterGroups();
        notifyContentChanged();
        HkRule rule = currentRule();
        contentTitle = rule == null ? "" : rule.getTitle();
        buildMiniappTabs();
        buildCategoryRow();
        buildFilterRows();
        buildContentSearchBar();
        loadContent(true);
    }

    /**
     * 进入子列表/子页面：重置筛选与列表状态（Activity 的 openSubList/openPage
     * 在派生新规则、destroyRouter 后调用；调用后再执行视图栈切换）。
     */
    public void onEnterSubList(String title) {
        cls = "";
        area = "";
        year = "";
        sort = "";
        clsName = "";
        areaName = "";
        yearName = "";
        sortName = "";
        page = 1;
        noMore = false;
        loading = false;
        contentGen++;
        videos.clear();
        if (title != null && !title.isEmpty()) contentTitle = title;
        buildCategoryRow();
        buildFilterRows();
        loadContent(true);
    }

    /**
     * 子列表返回：恢复父列表状态（Activity 的 backSubList 从 subStack 弹出后调用）。
     * 不触发网络加载，直接恢复缓存的 videos。
     */
    public void restoreContentState(int page, String cls, String area, String year, String sort,
                                   String clsName, String areaName, String yearName, String sortName,
                                   List<HkItem> cachedVideos, String title, int scrollPos) {
        this.page = page;
        this.cls = cls == null ? "" : cls;
        this.area = area == null ? "" : area;
        this.year = year == null ? "" : year;
        this.sort = sort == null ? "" : sort;
        this.clsName = clsName == null ? "" : clsName;
        this.areaName = areaName == null ? "" : areaName;
        this.yearName = yearName == null ? "" : yearName;
        this.sortName = sortName == null ? "" : sortName;
        this.noMore = false;
        this.loading = false;
        this.contentGen++;
        this.videos.clear();
        if (cachedVideos != null) this.videos.addAll(cachedVideos);
        this.contentTitle = title == null ? "" : title;
        buildCategoryRow();
        buildFilterRows();
        refreshAdapterGroups();
        notifyContentChanged();
        updateContentSearchBarVisibility();
        if (binding != null) {
            binding.tvContentEmpty.setVisibility(View.GONE);
            binding.rvVideos.setVisibility(this.videos.isEmpty() ? View.GONE : View.VISIBLE);
        }
        scrollToPosition(scrollPos);
        updateFooter();
    }

    /**
     * 规则自绘"加载更多"按钮的就地更新（8.83 addItemBefore/deleteItemByCls 同款语义）：
     * 把 JS 追加的新条目（按 url+title 去重）插到被点按钮之前，按钮保留以便继续翻页。
     * Activity 的 @rule=js: 点击链路在拿到 action 结果后调用。
     */
    public void mergeActionItems(HkItem button, List<HkItem> after) {
        if (after == null || after.isEmpty() || contentAdapter == null) return;
        Set<String> seen = new HashSet<>();
        for (HkItem v : videos) seen.add(itemKey(v));
        List<HkItem> delta = new ArrayList<>();
        for (HkItem it : after) {
            if (seen.add(itemKey(it))) delta.add(it);
        }
        if (delta.isEmpty()) return;
        int at = -1;
        if (button != null) {
            String bk = itemKey(button);
            for (int i = 0; i < videos.size(); i++) {
                if (bk.equals(itemKey(videos.get(i)))) {
                    at = i;
                    break;
                }
            }
        }
        if (at < 0) videos.addAll(delta);
        else videos.addAll(at, delta);
        refreshAdapterGroups();
        notifyContentChanged();
        updateFooter();
    }

    private static String itemKey(HkItem it) {
        if (it == null) return "";
        String u = it.getUrl() == null ? "" : it.getUrl();
        String t = it.getTitle() == null ? "" : it.getTitle();
        return u + "‖" + t;
    }

    // ================= 内容页视图初始化 =================

    private void initContentView() {
        if (viewInited || binding == null) return;
        viewInited = true;
        // 官方 60 列栅格（8.83 ArticleColTypeEnum.spanCount 反编译实测）：
        // movie_3/pic_3/icon_small_3(3/行)=20；icon_4系列(4/行)=15；icon_5/text_5(5/行)=12；
        // icon_2/pic_2/text_2(2/行)=30；其余全宽=60
        contentGrid = new GridLayoutManager(requireContext(), 60);
        contentGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return spanForPosition(position);
            }
        });
        RecyclerView rv = binding.rvVideos;
        rv.setLayoutManager(contentGrid);
        // 网页级顺滑优化：固定尺寸 + 20 个离屏缓存 + 绘图缓存 + 回弹 over-scroll
        rv.setHasFixedSize(true);
        rv.setItemViewCacheSize(20);
        rv.setDrawingCacheEnabled(true);
        rv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        rv.addItemDecoration(new GridSpace());
        if (contentAdapter != null) rv.setAdapter(contentAdapter);
        binding.swipeContent.setColorSchemeColors(0xFFD4A017);
        binding.swipeContent.setOnRefreshListener(() -> {
            // 下拉触发的加载只用下拉圈当指示器，不再叠加页面金圈
            pullRefreshInFlight = true;
            loadContent(true);
        });
        binding.btnMiniappAll.setOnClickListener(v -> showMiniappSwitchDialog());
        binding.tvContentEmpty.setOnClickListener(v -> loadContent(true));
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || noMore) return;
                int last = contentGrid.findLastVisibleItemPosition();
                int count = contentAdapter == null ? 0 : contentAdapter.getItemCount();
                if (last >= count - 3) loadContent(false);
            }
        });
        // 初始构建各行（规则已就绪时直接可用；Activity 切换规则会调 onRuleChanged 重建）
        buildMiniappTabs();
        buildCategoryRow();
        buildFilterRows();
        buildContentSearchBar();
        // 重放未就绪时被暂存的切规则请求（第一次进入小程序时与 attach 竞态的那次）
        if (pendingRuleChanged) {
            pendingRuleChanged = false;
            onRuleChanged();
        }
    }

    // ================= 内容加载 =================

    /**
     * 内容页数据加载（自 HkPageActivity.loadContent 提取，逻辑逐行一致）。
     * Activity 的 {@code hostLoadContent(reset)} 应直接转发到本方法。
     */
    public void loadContent(boolean reset) {
        HkRule rule = currentRule();
        if (loading || rule == null) {
            // 早退不产生完成回调：按当前真实状态对一次账，保证在途加载有且只有一个转圈、
            // 没有在途加载时一个都不剩（以前这里只关下拉圈，页面金圈会残留成双圈同屏）。
            if (rule == null) pullRefreshInFlight = false;
            reconcileLoadingUi();
            return;
        }
        if (!reset && !canAutoPage()) {
            noMore = true;
            updateFooter();
            return;
        }
        loading = true;
        inlineSpinnerWanted = reset;
        final int gen = ++contentGen;
        if (reset) {
            page = 1;
            noMore = false;
            videos.clear();
            refreshAdapterGroups();
            notifyContentChanged();
            if (binding != null) {
                binding.tvContentEmpty.setVisibility(View.GONE);
                binding.rvVideos.setVisibility(View.GONE);
                reconcileLoadingUi();
                // 页面转圈接管：关掉弹窗转圈，一个页面只许一个转圈
                hideHkLoading();
            }
        }
        updateFooter();
        final int p = page;
        final String c = cls, a = area, y = year, s = sort;
        new Thread(() -> {
            List<HkItem> list;
            try {
                // 入口留痕：点开小程序必有此行，缺了它就是没走到加载（列表/tab 层的问题）
                com.github.catvod.crawler.SpiderDebug.log("hk", "load start rule=%s page=%d", rule.getTitle(), p);
                list = getRouter().home(p, c, a, y, s);
                // 记录实际抓取页 URL：图片自动 Referer 用（8.83 GlideUtil.getGlideUrl 同款语义）
                recordHkPageUrl(p, c, a, y, s);
            } catch (Throwable e) {
                // 以前这里全吞：引擎建不起来/路由异常都无声变空列表，日志里查不到
                com.github.catvod.crawler.SpiderDebug.log("hk", "load threw rule=%s err=%s",
                        rule.getTitle(), String.valueOf(e));
                list = new ArrayList<>();
            }
            final List<HkItem> result = list;
            App.post(() -> {
                // 先释放 loading：过期回调也不能把标记卡死，否则滚动翻页/下拉刷新永久失效
                //（翻页请求在途时用户点了别的导致 contentGen 自增，就会走到这里）。
                loading = false;
                pullRefreshInFlight = false;
                // 加载视觉的清理必须排在过期检查前面：以前过期回调直接 return，
                // 全屏转圈/下拉刷新/preRule 弹窗全没人关——页面已经显示"加载失败"，
                // 底部还在转圈（用户实测）。reconcile 按当前真实状态对账（若已有新加载
                // 在途，它会给新加载留对的那个转圈），过期回调调它也安全。
                reconcileLoadingUi();
                // preRule 的 showLoading 兜底关闭：规则若没调 hideLoading，内容加载完也不残留弹窗。
                hideHkLoading();
                if (gen != contentGen) return; // 规则已切换/被更新的加载覆盖，丢弃过期结果
                if (!isAdded() || binding == null) return;
                // V2：把导航类条目（scroll_button/flex_button 分类）拆出来做顶部 tab，
                // 分隔块（blank_block/line_blank/line）丢弃，其余内容条目按 col_type
                // 由 ContentAdapter 多 viewType 渲染（视频/文本/图片/输入框等）。
                List<HkItem> tabs = new ArrayList<>();
                List<HkItem> contents = new ArrayList<>();
                for (HkItem it : result) {
                    String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
                    if ("scroll_button".equals(ct) || "flex_button".equals(ct)) tabs.add(it);
                    else if ("blank_block".equals(ct) || "big_blank_block".equals(ct)
                            || "big_big_blank_block".equals(ct) || "line_blank".equals(ct) || "line".equals(ct))
                        continue;
                    else contents.add(it);
                }
                if (p == 1) buildDynamicTabs(tabs);
                if (contents.isEmpty()) {
                    if (p == 1 && videos.isEmpty()) {
                        // 空态：8.83 式规则错误弹窗（HkErrorUtil），对等 DebugUtil.showErrorMsg。
                        String hkErr = null;
                        try {
                            hkErr = getRouter().getEngine().getError();
                        } catch (Throwable ignored) {
                        }
                        HkRule r = currentRule();
                        String ruleTitle = r == null ? "" : r.getTitle();
                        com.github.catvod.crawler.SpiderDebug.log("hk", "page empty rule=%s err=%s fetch=%s",
                                ruleTitle, hkErr, com.fongmi.android.tv.api.hk.HkJsRuntime.getLastFetchDiag());
                        HkErrorUtil.showErrorMsg(requireActivity(), "规则执行过程中出现错误", ruleTitle, hkErr, null);
                        binding.tvContentEmpty.setText("加载失败，点我重试");
                        binding.tvContentEmpty.setVisibility(View.VISIBLE);
                        binding.rvVideos.setVisibility(View.GONE);
                    } else {
                        noMore = true;
                    }
                } else {
                    videos.addAll(contents);
                    page = p + 1;
                    binding.rvVideos.setVisibility(View.VISIBLE);
                    refreshAdapterGroups();
                    notifyContentChanged();
                }
                updateFooter();
            });
        }).start();
    }

    /**
     * 8.83 对齐（ArticleListFragment.lambda$initView$3）：滚动自动翻页仅当规则支持分页。
     * url 含 fypage（或 #autoPage#/#autoReader#）→ 支持；js: 规则靠 MY_PAGE 分页
     *（url 常为 hiker://empty## 不带 fypage 标记，如 find_rule 为 js:$.require("engine").home()）。
     * 不支持时直接标到底，不发无效请求（8.83 finishLoadMore 同款，避免把第 1 页重复追加）。
     */
    private boolean canAutoPage() {
        HkRule rule = currentRule();
        if (rule == null) return false;
        String u = rule.getUrl();
        if (u != null && (u.contains("fypage") || u.contains("#autoPage#") || u.contains("#autoReader#"))) return true;
        String fr = rule.getFindRule();
        return fr != null && fr.trim().startsWith("js:");
    }

    /** 记录实际抓取的页面 URL（图片 Referer 用）。JS 规则取引擎最后一次 fetch 的真实 URL；非 JS 规则用展开后的模板 URL。 */
    private void recordHkPageUrl(int p, String c, String a, String y, String s) {
        try {
            String u = getRouter().getEngine().getJsRuntime().getLastUrl();
            if (!TextUtils.isEmpty(u) && (u.startsWith("http://") || u.startsWith("https://"))) {
                hkPageUrl = u;
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            HkRule rule = currentRule();
            if (rule != null) {
                hkPageUrl = HkHttp.expandUrl(rule.getUrl(), c, a, y, s, p);
            }
        } catch (Throwable ignored) {
        }
    }

    public void updateFooter() {
        if (binding == null) return;
        if (videos.isEmpty()) {
            binding.tvFooter.setVisibility(View.GONE);
            return;
        }
        binding.tvFooter.setVisibility(View.VISIBLE);
        if (loading) binding.tvFooter.setText("加载中…");
        else if (noMore) binding.tvFooter.setText("已加载全部");
        else binding.tvFooter.setText("");
    }

    // ================= 适配器协作 =================

    private void refreshAdapterGroups() {
        try {
            if (contentAdapter instanceof ContentListAdapter) {
                ((ContentListAdapter) contentAdapter).refreshGroups();
            }
        } catch (Throwable ignored) {
        }
    }

    private void notifyContentChanged() {
        try {
            if (contentAdapter != null) contentAdapter.notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
    }

    private int spanForPosition(int position) {
        try {
            if (contentAdapter instanceof ContentListAdapter) {
                return ((ContentListAdapter) contentAdapter).spanFor(position);
            }
        } catch (Throwable ignored) {
        }
        return 60;
    }

    // ================= 顶部小程序 tabs =================

    /**
     * 8.83 同款：内容页顶部小程序切换 tabs。
     * 横向显示已安装的小程序，点击切换规则（经 OnRulePickListener 交给 Activity 的 openRule）。
     */
    private void buildMiniappTabs() {
        if (binding == null) return;
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return;
        }
        try {
            List<HkRule> rules = HkRuleManager.get().getRules();
            List<HkRule> enabled = new ArrayList<>();
            for (HkRule r : rules) {
                // 顶部 tabs 不需要"我的主页"（HikerHome 未实现，打开必报错）
                // 标题为空（脏数据已在 getTitle 清洗）的规则不展示，避免空白 tab
                if (r.isEnabled() && !isHomePageRule(r) && !r.getTitle().isEmpty()) enabled.add(r);
            }
            // 当前规则若不在 tabs 列表里（未启用或刚导入），追加进去并选中，保证 tabs 跟随当前页面
            HkRule cur = currentRule();
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
                binding.miniappTabsRow.setVisibility(View.GONE);
                return;
            }
            List<String[]> pairs = new ArrayList<>();
            int sel = 0;
            for (int i = 0; i < enabled.size(); i++) {
                HkRule r = enabled.get(i);
                pairs.add(new String[]{r.getTitle(), String.valueOf(i)});
                if (curTitle != null && curTitle.equals(r.getTitle())) sel = i;
            }
            final List<HkRule> finalEnabled = enabled;
            ChipAdapter adapter = new ChipAdapter(pairs, sel, idx -> {
                try {
                    int i = Integer.parseInt(idx);
                    if (i >= 0 && i < finalEnabled.size()) {
                        HkRule target = finalEnabled.get(i);
                        HkRule c = currentRule();
                        if ((c == null || !target.getTitle().equals(c.getTitle())) && rulePickListener != null) {
                            rulePickListener.onPickRule(target);
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
            adapter.setGoldMode(true); // 金色主题：与分类 pills 颜色区分
            RecyclerView tabsRv = binding.rvMiniappTabs;
            tabsRv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
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
            binding.miniappTabsRow.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            binding.miniappTabsRow.setVisibility(View.GONE);
        }
    }

    /** 是否为"我的主页"规则：顶部 tabs 暂不展示它（HikerHome 未实现）。 */
    private static boolean isHomePageRule(HkRule r) {
        return r != null && "我的主页".equals(r.getTitle());
    }

    /**
     * 小程序切换：顶部 tabs 栏最右「全部」按钮弹窗。
     * 星辰风格：毛玻璃底 + 独立选项卡片。8.83 内容页无此功能。
     */
    private void showMiniappSwitchDialog() {
        if (miniappRules == null || miniappRules.size() <= 1) return;
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return;
        }
        HkRule cur = currentRule();
        final String curTitle = cur == null ? null : cur.getTitle();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.xc_dialog_light);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("选择小程序");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new GridLayoutManager(ctx, 2));
        rv.setClipToPadding(false);
        rv.setHasFixedSize(true);
        rv.setNestedScrollingEnabled(false);
        int rows = (miniappRules.size() + 1) / 2;
        int gridH = Math.min(rows * dp(66), dp(360));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, gridH);
        rlp.topMargin = dp(12);
        card.addView(rv, rlp);

        final AlertDialog[] holder = new AlertDialog[1];
        rv.setAdapter(new MiniappSwitchAdapter(miniappRules, curTitle, target -> {
            if (holder[0] != null) holder[0].dismiss();
            if (target != null && rulePickListener != null) {
                HkRule c = currentRule();
                if (c == null || !target.getTitle().equals(c.getTitle())) {
                    rulePickListener.onPickRule(target);
                }
            }
        }));

        FrameLayout outer = new FrameLayout(ctx);
        int m = dp(24);
        outer.setPadding(m, 0, m, 0);
        outer.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(outer).create();
        holder[0] = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    // ================= 内嵌搜索条 =================

    /**
     * 8.83 同款：内容页内嵌搜索栏。
     * 规则有搜索能力时显示，输入关键词点搜索跳搜索页（经 HkPageHost.hostOpenSearch）。
     */
    private void buildContentSearchBar() {
        if (binding == null) return;
        try {
            HkRule rule = currentRule();
            if (rule != null && rule.hasSearch()) {
                binding.etSearchContent.setHint("搜索" + rule.getTitle() + " / 演员");
                binding.etSearchContent.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        doContentSearch();
                        return true;
                    }
                    return false;
                });
                binding.btnSearchContentGo.setOnClickListener(v -> doContentSearch());
            } else {
                binding.searchBarContent.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            binding.searchBarContent.setVisibility(View.GONE);
        }
        updateContentSearchBarVisibility();
    }

    /** 规则内容里是否自带 input 搜索框（col_type="input"）。 */
    private boolean hasRuleInputBox() {
        try {
            for (HkItem it : videos) {
                String ct = it.getColType();
                if (ct != null && "input".equals(ct.trim().toLowerCase())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 内嵌搜索条显隐：规则有搜索能力且内容里没有自带 input 框时才显示。 */
    private void updateContentSearchBarVisibility() {
        if (binding == null) return;
        try {
            HkRule rule = currentRule();
            boolean show = rule != null && rule.hasSearch() && !hasRuleInputBox();
            binding.searchBarContent.setVisibility(show ? View.VISIBLE : View.GONE);
        } catch (Throwable t) {
            binding.searchBarContent.setVisibility(View.GONE);
        }
    }

    /** 内容页内嵌搜索：带关键词打开搜索页并执行搜索（Activity 经 hostOpenSearch 接管搜索页）。 */
    private void doContentSearch() {
        if (binding == null) return;
        try {
            String kw = binding.etSearchContent.getText().toString().trim();
            if (!kw.isEmpty() && currentRule() != null) {
                HkPageHost h = host();
                if (h != null) h.hostOpenSearch(kw);
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= 静态分类 tab 行 =================

    /**
     * 8.83 同款静态分类 tab 行。
     * 原文（ArticleListFragment.smali .line 1272-1290）：class_name/area_name/year_name/sort_name
     * 非空即注入一行 header（type="header"，desc="class"/"area"/"year"/"sort"），title/url 按 "&" 切分；
     * ArticleListAdapter.bindForHeaderHolder（smali:2492，.line 1133-1242）渲染：横向 tab + 右侧 ">"，
     * ">" 点开"请选择分类" 3 列弹窗（lambda$bindForHeaderHolder$11，smali:10064）。
     * 点击 tab：onClassClick（ArticleListFragment$5.smali:306，.line 1434-1457）按 desc 把该 tab 的
     * url 值写入对应变量、page=1 重载；选中名记 pic 用于高亮（此处记 *Name 字段）。
     * 原版无"全部"tab，不自动添加。
     */
    private void buildCategoryRow() {
        if (binding == null) return;
        binding.categoryRow.removeAllViews();
        HkRule rule = currentRule();
        if (rule == null) {
            binding.categoryRow.setVisibility(View.GONE);
            return;
        }
        int rows = 0;
        rows += addCategoryTabRow(rule.getClassPairs(), "class");
        rows += addCategoryTabRow(rule.getAreaPairs(), "area");
        rows += addCategoryTabRow(rule.getYearPairs(), "year");
        rows += addCategoryTabRow(rule.getSortPairs(), "sort");
        // 8.83 同款：整行按是否有分类显示/隐藏
        binding.categoryRow.setVisibility(rows > 0 ? View.VISIBLE : View.GONE);
    }

    /** 建一行分类 tab（横向 RecyclerView + 右侧 ">"），无数据返回 0。 */
    private int addCategoryTabRow(List<String[]> pairs, String kind) {
        if (pairs == null || pairs.isEmpty() || binding == null) return 0;
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return 0;
        }
        // 8.83 默认：pic 为空时选中第一个 title；instance() 把 *_url 归一化为第一个值（smali .line 434-444）
        String curName = nameOf(kind);
        if (curName.isEmpty()) {
            setNameOf(kind, pairs.get(0)[0]);
            if (valOf(kind).isEmpty()) setValOf(kind, pairs.get(0)[1]);
            curName = pairs.get(0)[0];
        }
        int sel = 0;
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i)[0].equals(curName)) {
                sel = i;
                break;
            }
        }
        final List<String[]> finalPairs = pairs;
        final int finalSel = sel;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        rowLp.topMargin = dp(8);
        binding.categoryRow.addView(row, rowLp);

        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipToPadding(false);
        // 横向行不抢垂直滚动：避免嵌套滚动冲突
        rv.setHasFixedSize(true);
        rv.setNestedScrollingEnabled(false);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        row.addView(rv, rvLp);
        rv.setAdapter(new ChipAdapter(pairs, sel, url -> onCategoryTabPick(kind, finalPairs, url)));

        TextView btnExpand = new TextView(ctx);
        btnExpand.setText(">");
        btnExpand.setTextSize(16);
        btnExpand.setTextColor(0xFF666666);
        btnExpand.setGravity(android.view.Gravity.CENTER);
        btnExpand.setBackgroundResource(R.drawable.tab_item_default);
        btnExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnExpand.setContentDescription("展开");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(3);
        btnLp.rightMargin = dp(5);
        row.addView(btnExpand, btnLp);
        // 8.83 同款：">" 点开"请选择分类"弹窗
        btnExpand.setOnClickListener(v ->
                showCategoryPopup(finalPairs, finalSel, url -> onCategoryTabPick(kind, finalPairs, url)));
        return 1;
    }

    /**
     * 8.83 onClassClick 等价：按行 kind 把选中 tab 的 url 值写入对应变量
     * （fyclass/fyarea/fyyear/fysort 替换用），选中名记入 *Name（8.83 setPic 等价），
     * 重建 tab 行刷新高亮（8.83 updateHeaderHolder 等价），page=1 重载。
     */
    private void onCategoryTabPick(String kind, List<String[]> pairs, String url) {
        String name = "";
        for (String[] p : pairs) {
            if (p[1].equals(url)) {
                name = p[0];
                break;
            }
        }
        if (name.isEmpty() && !pairs.isEmpty()) name = pairs.get(0)[0];
        setValOf(kind, url);
        setNameOf(kind, name);
        loadContent(true);
        buildCategoryRow();
    }

    private String valOf(String kind) {
        switch (kind) {
            case "area":
                return area;
            case "year":
                return year;
            case "sort":
                return sort;
            default:
                return cls;
        }
    }

    private String nameOf(String kind) {
        switch (kind) {
            case "area":
                return areaName;
            case "year":
                return yearName;
            case "sort":
                return sortName;
            default:
                return clsName;
        }
    }

    private void setValOf(String kind, String v) {
        switch (kind) {
            case "area":
                area = v;
                break;
            case "year":
                year = v;
                break;
            case "sort":
                sort = v;
                break;
            default:
                cls = v;
                break;
        }
    }

    private void setNameOf(String kind, String v) {
        switch (kind) {
            case "area":
                areaName = v;
                break;
            case "year":
                yearName = v;
                break;
            case "sort":
                sortName = v;
                break;
            default:
                clsName = v;
                break;
        }
    }

    // ================= JS 动态 tab 行 =================

    /**
     * V2 动态分类 tab：规则 find_rule 返回的 scroll_button/flex_button 条目。
     * 8.83 原文行为（ArticleListAdapter.bindForHeaderHolder）：
     * 每条目独立成一行，title/url 按 "&" 切分为行内 tabs，每行右侧带 ">" 按钮，
     * 点击 ">" 弹出"请选择分类"（3 列竖向网格）。
     * 标题去 HTML 标签；含高亮色（#ff1493/#FF5D50 等）的为当前选中。
     * 点击 tab 求值其 lazyRule（putMyVar 设分类变量），再刷新列表。
     */
    public void buildDynamicTabs(List<HkItem> tabs) {
        if (binding == null) return;
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return;
        }
        binding.llTabRows.removeAllViews();
        if (tabs == null || tabs.isEmpty()) {
            binding.llTabRows.setVisibility(View.GONE);
            return;
        }
        binding.llTabRows.setVisibility(View.VISIBLE);
        // 8.83 updatePolyList（smali ArticleListAdapter:12152）：连续同 col_type 的条目自动合并成一组
        List<List<HkItem>> groups = new ArrayList<>();
        for (HkItem it : tabs) {
            String t = it.getColType();
            if (!groups.isEmpty()) {
                List<HkItem> last = groups.get(groups.size() - 1);
                if (last.get(0).getColType().equals(t)) {
                    last.add(it);
                    continue;
                }
            }
            List<HkItem> g = new ArrayList<>();
            g.add(it);
            groups.add(g);
        }
        for (List<HkItem> group : groups) {
            List<String[]> pairs = new ArrayList<>();
            int sel = 0;
            for (HkItem it : group) {
                String rawTitle = it.getTitle() == null ? "" : it.getTitle();
                String rawUrl = it.getUrl() == null ? "" : it.getUrl();
                // 8.83：title/url 按 "&" 切分（bindForHeaderHolder 第 1140 行）
                String[] names = rawTitle.split("&", -1);
                String[] urls = rawUrl.split("&", -1);
                for (int i = 0; i < names.length; i++) {
                    String raw = names[i];
                    if (isTabSelected(raw)) sel = pairs.size();
                    String name = stripHtml(raw);
                    if (name.isEmpty()) name = "分类" + (pairs.size() + 1);
                    String u = i < urls.length ? urls[i] : "";
                    pairs.add(new String[]{name, u, raw});
                }
            }
            if (pairs.isEmpty()) continue;
            // 行容器：横向 RecyclerView + 右侧 ">" 展开按钮（8.83 view_article_list_header.xml 同款）
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            rowLp.topMargin = dp(8);
            binding.llTabRows.addView(row, rowLp);

            RecyclerView rv = new RecyclerView(ctx);
            rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
            rv.setClipToPadding(false);
            // 横向行不抢垂直滚动：避免嵌套滚动冲突
            rv.setHasFixedSize(true);
            rv.setNestedScrollingEnabled(false);
            LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            row.addView(rv, rvLp);

            final List<String[]> finalPairs = pairs;
            final int finalSel = sel;
            ChipAdapter adapter = new ChipAdapter(pairs, sel, url -> {
                if (url == null || url.isEmpty()) return;
                new Thread(() -> {
                    try {
                        getRouter().evalTab(url);
                    } catch (Throwable ignored) {
                    }
                    App.post(() -> {
                        if (isAdded()) loadContent(true);
                    });
                }).start();
            });
            rv.setAdapter(adapter);

            TextView btnExpand = new TextView(ctx);
            btnExpand.setText(">");
            btnExpand.setTextSize(16);
            btnExpand.setTextColor(0xFF666666);
            btnExpand.setGravity(android.view.Gravity.CENTER);
            btnExpand.setBackgroundResource(R.drawable.tab_item_default);
            btnExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
            btnExpand.setContentDescription("展开");
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.leftMargin = dp(3);
            btnLp.rightMargin = dp(5);
            row.addView(btnExpand, btnLp);
            btnExpand.setOnClickListener(v -> showCategoryPopup(finalPairs, finalSel, url -> {
                if (url == null || url.isEmpty()) return;
                new Thread(() -> {
                    try {
                        getRouter().evalTab(url);
                    } catch (Throwable ignored) {
                    }
                    App.post(() -> {
                        if (isAdded()) loadContent(true);
                    });
                }).start();
            }));
        }
    }

    /** tab 是否为选中态：标题含高亮色标记（8.83 规则常用 #ff1493/#FF5D50/#ff0000 等）。 */
    private boolean isTabSelected(String rawTitle) {
        if (rawTitle == null) return false;
        String l = rawTitle.toLowerCase();
        return l.contains("#ff1493") || l.contains("#ff5d50") || l.contains("#ff0000")
                || l.contains("color=\"red\"") || l.contains("color='red'");
    }

    /**
     * 8.83 同款"请选择分类"弹窗：居中，3 列竖向网格。
     * （ArticleListAdapter.lambda$bindForHeaderHolder$11，CustomCenterRecyclerViewPopup，
     * item_rect_radius.xml center=true 浅色模式）
     */
    private void showCategoryPopup(List<String[]> pairs, int selected, ChipAdapter.OnPick listener) {
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return;
        }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(ctx);
        title.setText("请选择分类");
        title.setTextColor(0xFF000000);
        title.setTextSize(20);
        title.setMaxLines(1);
        title.setGravity(android.view.Gravity.CENTER);
        int tp = dp(15);
        title.setPadding(tp, tp, tp, tp);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new GridLayoutManager(ctx, 3));
        rv.setHasFixedSize(true);
        rv.setNestedScrollingEnabled(false);
        int rp = dp(10);
        rv.setPadding(rp, rp, rp, rp);
        rv.setClipToPadding(false);
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 选项卡片风格弹窗（与现有选项弹窗统一：独立卡片+间距）
        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(root)
                .create();
        // 点选项后关闭弹窗
        rv.setAdapter(new CategoryAdapter(pairs, selected, url -> {
            dialog.dismiss();
            listener.onPick(url);
        }));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.hk_category_popup_bg);
        }
        dialog.show();
    }

    // ================= 筛选行（8.83 无筛选行，保留空实现） =================

    /**
     * 8.83 原文没有筛选行：地区/年份/类型就是分类 tab 行（class/area/year/sort 四行 header，
     * 见 buildCategoryRow）。此方法保留空实现（调用点不动），避免与 tab 行重复显示。
     */
    private void buildFilterRows() {
        if (binding == null) return;
        binding.filterContainer.removeAllViews();
        binding.filterContainer.setVisibility(View.GONE);
    }

    private void addFilterRow(String label, List<String[]> pairs, ChipAdapter.OnPick pick) {
        if (pairs == null || pairs.isEmpty() || binding == null) return;
        Context ctx;
        try {
            ctx = requireContext();
        } catch (Throwable t) {
            return;
        }
        // 8.83 同款：筛选行带 ">" 展开/收起，默认收起只显示一行
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(4), dp(8));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(0xFF101216);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        header.addView(tv, labelLp);

        // 已选项显示
        TextView tvSelected = new TextView(ctx);
        tvSelected.setText("全部");
        tvSelected.setTextColor(0xFF8A8F98);
        tvSelected.setTextSize(12);
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(tvSelected, selLp);

        // ">" 展开指示器
        TextView tvArrow = new TextView(ctx);
        tvArrow.setText(">");
        tvArrow.setTextColor(0xFF8A8F98);
        tvArrow.setTextSize(14);
        tvArrow.setPadding(dp(8), 0, dp(8), 0);
        header.addView(tvArrow);

        container.addView(header);

        // 选项行（默认收起）
        RecyclerView rv = new RecyclerView(ctx);
        rv.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
        rv.setHasFixedSize(true);
        rv.setNestedScrollingEnabled(false);
        List<String[]> all = new ArrayList<>();
        all.add(new String[]{"全部", ""});
        all.addAll(pairs);
        final boolean[] expanded = {false};
        rv.setVisibility(View.GONE);

        ChipAdapter adapter = new ChipAdapter(all, 0, value -> {
            // 更新已选项显示
            for (String[] p : all) {
                if (p[1].equals(value)) {
                    tvSelected.setText(p[0]);
                    break;
                }
            }
            pick.onPick(value);
        });
        rv.setAdapter(adapter);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        rvLp.topMargin = dp(4);
        rvLp.bottomMargin = dp(4);
        container.addView(rv, rvLp);

        // 点击头部展开/收起
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            rv.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            tvArrow.setText(expanded[0] ? "v" : ">");
            tvArrow.setRotation(expanded[0] ? 90 : 0);
        });

        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        binding.filterContainer.addView(container, containerLp);
    }

    // ================= 工具 =================

    private int dp(int v) {
        try {
            float d = requireContext().getResources().getDisplayMetrics().density;
            return (int) (v * d + 0.5f);
        } catch (Throwable ignored) {
            return v;
        }
    }

    /** 去 HTML 标签（col_type 标题里常带 <font> 等），逻辑同 buildDynamicTabs。 */
    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").replace("‘", "").replace("’", "")
                .replace("“", "").replace("”", "").trim()
                // 规则里常把标题包在引号里（"""最新上传"""），去掉首尾引号
                .replaceAll("^\"+|\"+$", "").replaceAll("^'+|'+$", "").trim();
    }

    // ================= 嵌套组件（自 HkPageActivity 提取） =================

    /**
     * 内容网格间距：标准均分公式，保证 N 列总宽严格等于 RecyclerView 宽、不溢出。
     * 每列 i（0-based）：left = i*S/N，right = S-(i+1)*S/N；列间距 S=12dp。
     * 外边距由父容器 16dp 提供，decoration 不再加外边距。
     * 兼容 60-span 内容网格与 3-span 搜索网格。
     */
    private static class GridSpace extends RecyclerView.ItemDecoration {
        private static int dpOf(Context ctx, int v) {
            try {
                float d = ctx.getResources().getDisplayMetrics().density;
                return (int) (v * d + 0.5f);
            } catch (Throwable ignored) {
                return v;
            }
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            int total = 60, span = 60, spanIndex = 0;
            if (parent.getLayoutManager() instanceof GridLayoutManager) {
                total = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
            }
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();
            if (lp instanceof GridLayoutManager.LayoutParams) {
                GridLayoutManager.LayoutParams glp = (GridLayoutManager.LayoutParams) lp;
                span = glp.getSpanSize();
                if (glp.getSpanIndex() >= 0) spanIndex = glp.getSpanIndex();
            }
            if (span >= total) {
                outRect.left = 0;
                outRect.right = 0;
                if (pos > 0) outRect.top = dpOf(parent.getContext(), 12);
                return;
            }
            int perRow = Math.max(1, total / Math.max(1, span));
            int col = span > 0 ? spanIndex / span : 0;
            if (col < 0) col = 0;
            if (col >= perRow) col = perRow - 1;
            int s = dpOf(parent.getContext(), 12);
            outRect.left = col * s / perRow;
            outRect.right = s - (col + 1) * s / perRow;
            if (pos >= perRow) outRect.top = dpOf(parent.getContext(), 12);
        }
    }

    /** 分类 tab / 小程序 tabs 的横向 pill 适配器（自 HkPageActivity.ChipAdapter 提取，逻辑一致）。 */
    private static class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.Holder> {
        interface OnPick {
            void onPick(String value);
        }

        private final List<String[]> pairs;
        private int selected;
        private final OnPick listener;
        private int dropTarget = -1;
        private boolean goldMode = false;

        ChipAdapter(List<String[]> pairs, int selected, OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        /** 金色主题模式：小程序切换 tabs 用淡金底，与分类 pills（白底/绿选中）颜色区分，一眼看出是两排不同的东西。 */
        void setGoldMode(boolean goldMode) {
            this.goldMode = goldMode;
        }

        /** 拖拽投放高亮位置（-1=无），供编辑模式拖卡片到分组 pills 用。 */
        boolean setDropTarget(int pos) {
            if (dropTarget == pos) return false;
            int old = dropTarget;
            dropTarget = pos;
            if (old >= 0 && old < pairs.size()) notifyItemChanged(old);
            if (pos >= 0 && pos < pairs.size()) notifyItemChanged(pos);
            return true;
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
            boolean drop = position == dropTarget;
            if (goldMode) {
                // 金色主题：小程序切换 tabs 在圆角框内，透明底 + 金色系文字，与分类 pills 区分
                if (h.card != null) {
                    h.card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                    h.card.setStrokeWidth(0);
                }
                h.chip.setBackgroundResource(0);
                h.chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                h.chip.setTextColor(sel ? 0xFF8A6D00 : 0xFF8A8F98);
            } else if (drop) {
                // 拖拽投放目标：金色加粗 + 选中底，提示松手即进该分组
                if (h.card != null) {
                    h.card.setCardBackgroundColor(0xC2FFFFFF);
                    float d2 = h.itemView.getContext().getResources().getDisplayMetrics().density;
                    h.card.setStrokeWidth((int) (1 * d2 + 0.5f));
                    h.card.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFE1E5EB));
                }
                h.chip.setTextColor(0xFFB8890F);
                h.chip.setTypeface(null, android.graphics.Typeface.BOLD);
                h.chip.setBackgroundResource(R.drawable.hk_ripple_glass_selected);
            } else {
                // 恢复卡片默认样式（防止从 goldMode 复用过来的 view 残留透明底）
                if (h.card != null) {
                    h.card.setCardBackgroundColor(0xC2FFFFFF);
                    float d = h.itemView.getContext().getResources().getDisplayMetrics().density;
                    h.card.setStrokeWidth((int) (1 * d + 0.5f));
                    h.card.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFE1E5EB));
                }
                h.chip.setTypeface(null, android.graphics.Typeface.NORMAL);
                // 磨砂 pills：选中=淡绿磨砂+深绿字，未选中=白磨砂+深灰字（深色字在磨砂底上保持可读）
                h.chip.setTextColor(sel ? 0xFF1B5E20 : 0xFF222222);
                h.chip.setBackgroundResource(sel ? R.drawable.hk_ripple_glass_selected : R.drawable.hk_ripple_glass);
            }
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

    /** 8.83 同款分类弹窗按钮适配器（对照 item_rect_radius.xml）。 */
    private static class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        private final List<String[]> pairs;
        private final int selected;
        private final ChipAdapter.OnPick listener;

        CategoryAdapter(List<String[]> pairs, int selected, ChipAdapter.OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView text;

            Holder(View v) {
                super(v);
                text = v.findViewById(R.id.tv_category);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_category, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] pair = pairs.get(position);
            h.text.setText(pair[0]);
            // 8.83：选中项文字变红（redAction #ffed424b）
            h.text.setTextColor(position == selected ? 0xFFED424B : 0xFF222222);
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getAdapterPosition();
                    if (pos >= 0 && listener != null) {
                        listener.onPick(pairs.get(pos)[1]);
                    }
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "CategoryAdapter click failed", e);
                }
            });
        }

        @Override
        public int getItemCount() {
            return pairs.size();
        }
    }

    /** 「全部」弹窗的 2 列选项卡片适配器：独立卡片框、有间距，当前选中高亮。 */
    private static class MiniappSwitchAdapter extends RecyclerView.Adapter<MiniappSwitchAdapter.Holder> {
        interface OnPick {
            void onPick(HkRule rule);
        }

        private final List<HkRule> rules;
        private final String curTitle;
        private final OnPick listener;

        MiniappSwitchAdapter(List<HkRule> rules, String curTitle, OnPick listener) {
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
            Context ctx = parent.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            TextView tv = new TextView(ctx);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
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
            h.name.setTextColor(sel ? 0xFFD4A017 : 0xFF1A1D24);
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
