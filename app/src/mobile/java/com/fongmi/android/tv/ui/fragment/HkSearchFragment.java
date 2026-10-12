package com.fongmi.android.tv.ui.fragment;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkHttp;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.api.hk.SearchResult;
import com.fongmi.android.tv.databinding.FragmentHkSearchBinding;
import com.fongmi.android.tv.ui.activity.HkRuleSearchActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 星辰小程序搜索页（V_SEARCH）Fragment。
 * <p>
 * 从 HkPageActivity 拆出 V_SEARCH 相关字段与方法（行为与原文一致）：
 * openSearchWithKeyword / initSearchView / openSearch / openContentSearch /
 * searchHint / updateSearchScopeLabel / showSearchChooser /
 * doCrossSearch / doSearch / doSearchPage / saveHistory 等。
 * V_RULES（首页）逻辑保留在 Activity，不在此。
 * <p>
 * 宿主 Activity 必须实现 {@link HkPageHost}，Fragment 通过
 * {@code ((HkPageHost) requireActivity())} 调用宿主能力：
 * getHkRouter() / getCurrentRule() / hostOpenDetail() / hostNavigateBack() /
 * hostOpenSearch() / hostShowLoading() / hostHideLoading()。
 * <p>
 * 宿主集成约定：
 * 1. 调用 openSearch()/openContentSearch()/openSearchWithKeyword() 前先 show 本 Fragment；
 * view 尚未就绪时调用会自动延迟到 onViewCreated 后执行（pendingEntry）。
 * 2. hostOpenSearch("")：宿主仅负责导航显示搜索页，keyword 为空时不要重置搜索模式；
 * keyword 非空时宿主可直接调 openSearchWithKeyword(keyword)。
 * 3. 宿主的 hostOpenDetail(HkItem) 实现需包含原文搜索结果点击链：
 * 跨搜来源规则静默切换（switchRuleForDetail）→ handleActionUrl →
 * hiker://page/ 走 openPage → openDetail(fromSearch=true)。
 * 4. 布局里的 search_mode_row / rv_search_rules（搜小程序/搜内容分段）在原 Activity
 * 中未接线，此处同样保持未接线，不臆造行为。
 * 5. toggleInlineSearch/closeInlineSearch 操作的是首页（V_RULES）inline 搜索条，
 * 按"不要动 V_RULES"约束保留在 Activity，未迁移。
 */
public class HkSearchFragment extends Fragment {

    private static final String PREFS = "xingchen";
    private static final String KEY_HIST = "hk_search_history";
    /** 搜索历史分隔符（原文 "￿"）。 */
    private static final String HIST_SEP = "\u0001";

    private FragmentHkSearchBinding binding;

    // ================= 搜索状态（原 HkPageActivity 字段） =================

    private SearchVideoAdapter searchAdapter;
    private final List<SearchResult> searchResults = new ArrayList<>();
    /** 跨小程序搜索的结果来源标注（SearchResult 保持 8.83 原版纯净，来源放外部映射）。 */
    private final Map<SearchResult, String> searchSrcRules =
    Collections.synchronizedMap(new IdentityHashMap<>());
    private String keyword = "";
    private int searchPage = 1;
    private boolean searchLoading, searchNoMore;
    /** 跨小程序搜索代际：新搜索开始时自增，旧搜索的迟到结果直接丢弃。 */
    private final AtomicInteger crossSearchGen = new AtomicInteger(0);
    /** 搜索页：true = 小程序列表入口的整页内容搜索；false = 内容页内单规则搜索 */
    private boolean searchDualMode = false;
    /** 搜索页打开详情时恒为 true（原文 openDetail(item, fromSearch) 语义）。 */
    private boolean detailFromSearch;
    /** 搜索结果图 Referer：记录搜索实际抓取 URL（原 Activity.hkPageUrl，Fragment 内局部）。 */
    private String hkPageUrl;

    /** view 未就绪时的延迟入口（宿主先 show 再调入口方法的竞态保护，只保留最后一次）。 */
    private Runnable pendingEntry;

    private HkPageHost host() {
        return (HkPageHost) requireActivity();
    }

    private HkRouter getRouter() {
        Object r = host().getHkRouter();
        return r instanceof HkRouter ? (HkRouter) r : null;
    }

    private SharedPreferences getPrefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) (v * requireContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 搜索结果图 Referer：优先用搜索实际抓取 URL，否则用当前规则 URL。 */
    private String hkRuleUrl() {
        if (!TextUtils.isEmpty(hkPageUrl)) return hkPageUrl;
        HkRule rule = host().getCurrentRule();
        return rule == null ? null : rule.getUrl();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHkSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initSearchView();
        if (pendingEntry != null) {
            Runnable r = pendingEntry;
            pendingEntry = null;
            r.run();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void runWhenReady(Runnable r) {
        if (binding != null && isAdded()) r.run();
        else pendingEntry = r;
    }

    // ================= 对外入口（宿主先 show 本 Fragment 再调用） =================

    /** 内容页搜索按钮：单规则搜索入口。 */
    public void openSearch() {
        runWhenReady(() -> {
            searchDualMode = false;
            keyword = "";
            searchPage = 1;
            searchNoMore = false;
            searchResults.clear();
            searchSrcRules.clear();
            searchAdapter.notifyDataSetChanged();
            binding.etKeyword.setText("");
            // 小程序内搜索：hint 带上规则名，明确作用域（之前通用 hint 像全局搜索）
            binding.etKeyword.setHint(searchHint());
            updateSearchScopeLabel();
            binding.tvSearchEmpty.setVisibility(View.GONE);
            binding.rvSearchResults.setVisibility(View.VISIBLE);
            refreshHistory();
            binding.historySection.setVisibility(View.VISIBLE);
            binding.etKeyword.requestFocus();
        });
    }

    /**
     * 长按搜索 → 搜内容：跨小程序内容搜索入口。
     */
    public void openContentSearch() {
        runWhenReady(() -> {
            searchDualMode = true;
            binding.etKeyword.setText("");
            binding.etKeyword.setHint("搜索全部小程序的内容");
            updateSearchScopeLabel();
            binding.historySection.setVisibility(View.GONE);
            binding.tvSearchEmpty.setVisibility(View.VISIBLE);
            binding.tvSearchEmpty.setText("输入关键词，搜索全部小程序的内容");
            binding.rvSearchResults.setVisibility(View.GONE);
            binding.etKeyword.requestFocus();
        });
    }

    /** 内容页内嵌搜索栏：带关键词直接打开搜索页并执行搜索。 */
    public void openSearchWithKeyword(String kw) {
        runWhenReady(() -> {
            searchDualMode = false;
            keyword = "";
            searchPage = 1;
            searchNoMore = false;
            searchResults.clear();
            searchSrcRules.clear();
            searchAdapter.notifyDataSetChanged();
            binding.etKeyword.setText(kw);
            binding.etKeyword.setHint(searchHint());
            updateSearchScopeLabel();
            binding.tvSearchEmpty.setVisibility(View.GONE);
            binding.rvSearchResults.setVisibility(View.VISIBLE);
            binding.historySection.setVisibility(View.GONE);
            doSearch(kw);
        });
    }

    /**
     * H3: 长按搜索按钮弹出选择：搜内容 / 搜规则（已安装小程序）。
     * 由宿主从首页长按调用；"搜内容"分支先设置跨搜模式（view 未就绪则延迟），
     * 再请宿主导航显示搜索页（hostOpenSearch("") 约定：宿主仅显示，不重置模式）。
     */
    public void showSearchChooser() {
        new AlertDialog.Builder(requireContext())
        .setTitle("搜索")
        .setItems(new String[]{"搜内容", "搜规则"}, (d, which) -> {
            if (which == 0) {
                openContentSearch();
                host().hostOpenSearch("");
            } else {
                HkRuleSearchActivity.start(requireContext());
            }
        })
        .show();
    }

    /** 搜索页打开详情时恒为 true，供宿主/详情页重试逻辑沿用原语义。 */
    public boolean isDetailFromSearch() {
        return detailFromSearch;
    }

    // ================= V3 搜索 =================

    private void initSearchView() {
        GridLayoutManager grid = new GridLayoutManager(requireContext(), 3);
        binding.rvSearchResults.setLayoutManager(grid);
        binding.rvSearchResults.addItemDecoration(new GridSpace(requireContext()));
        // 网页级顺滑：固定尺寸 + 扩大缓存
        binding.rvSearchResults.setHasFixedSize(true);
        binding.rvSearchResults.setItemViewCacheSize(20);
        searchAdapter = new SearchVideoAdapter();
        binding.rvSearchResults.setAdapter(searchAdapter);
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        binding.rvHistory.setHasFixedSize(true);
        binding.rvHistory.setItemViewCacheSize(20);
        // 搜小程序列表同样优化（当前未接线，先备好）
        binding.rvSearchRules.setHasFixedSize(true);
        binding.rvSearchRules.setItemViewCacheSize(20);
        // 搜索页搜索条用实色白底（用户反馈透明看不清，已去磨砂）
        binding.btnBackSearch.setOnClickListener(v -> host().hostNavigateBack());
        binding.btnClear.setOnClickListener(v -> binding.etKeyword.setText(""));
        binding.btnClearHistory.setOnClickListener(v -> {
            getPrefs().edit().remove(histKey()).apply();
            refreshHistory();
        });
        binding.etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String kw = v.getText().toString().trim();
                // 小程序列表入口 = 跨小程序内容搜索；内容页内 = 单规则搜索
                if (searchDualMode) doCrossSearch(kw);
                else doSearch(kw);
                return true;
            }
            return false;
        });
        binding.btnSearchGo.setOnClickListener(v -> {
            String kw = binding.etKeyword.getText().toString().trim();
            if (searchDualMode) doCrossSearch(kw);
            else doSearch(kw);
        });
        binding.rvSearchResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || searchLoading || searchNoMore || keyword.isEmpty()) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= searchAdapter.getItemCount() - 3) {
                    doSearchPage();
                }
            }
        });
    }

    /** 小程序内搜索的输入框 hint：带上规则名，如"搜索一个APP"。 */
    private String searchHint() {
        HkRule rule = host().getCurrentRule();
        if (!searchDualMode && rule != null && rule.getTitle() != null) {
            return "搜索" + rule.getTitle();
        }
        return "输入关键词搜索";
    }

    /** V_SEARCH 作用域标识：单规则模式显示"在「X」内搜索"，跨搜模式隐藏。 */
    private void updateSearchScopeLabel() {
        try {
            HkRule rule = host().getCurrentRule();
            if (!searchDualMode && rule != null && rule.getTitle() != null
            &&!rule.getTitle().isEmpty()) {
                binding.tvSearchScope.setText("在「" + rule.getTitle() + "」内搜索");
                binding.tvSearchScope.setVisibility(View.VISIBLE);
            } else {
                binding.tvSearchScope.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 搜索历史按作用域隔离：小程序内搜索用各规则独立的 key，跨搜用全局 key。 */
    private String histKey() {
        HkRule rule = host().getCurrentRule();
        if (!searchDualMode && rule != null && rule.getTitle() != null
        &&!rule.getTitle().isEmpty()) {
            return KEY_HIST + "_" + rule.getTitle();
        }
        return KEY_HIST;
    }

    /**
     * 搜内容：跨小程序内容搜索。对所有启用的小程序并发调用 search(keyword, 1)，
     * 结果按小程序分组展示（来源标注在条目 desc 前缀），点击条目自动切到对应小程序再进详情。
     */
    private void doCrossSearch(String kw) {
        final int gen = crossSearchGen.incrementAndGet();
        binding.rvSearchResults.setVisibility(View.GONE);
        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
        searchResults.clear();
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        if (kw.isEmpty()) {
            binding.tvSearchEmpty.setText("输入关键词，搜索全部小程序的内容");
            return;
        }
        List<HkRule> enabled;
        try {
            enabled = HkRuleManager.get().getEnabledRules();
        } catch (Throwable e) {
            enabled = new ArrayList<>();
        }
        if (enabled.isEmpty()) {
            binding.tvSearchEmpty.setText("没有启用的小程序");
            return;
        }
        binding.tvSearchEmpty.setText("正在搜索 " + enabled.size() + " 个小程序…");
        saveHistory(kw);
        final List<HkRule> rules = enabled;
        final String keywordFinal = kw;
        new Thread(() -> {
            int poolSize = Math.min(rules.size(), 8);
            ThreadPoolExecutor pool = new ThreadPoolExecutor(poolSize, poolSize, 10L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(256));
            CountDownLatch latch = new CountDownLatch(rules.size());
            List<SearchResult> aggregated = Collections.synchronizedList(new ArrayList<>());
            for (HkRule rule : rules) {
                final HkRule r = rule;
                pool.execute(() -> {
                    try {
                        // 每个规则独立 router，避免与当前页面的 router 互相干扰
                        HkRouter router = new HkRouter(r);
                        List<SearchResult> list = router.search(keywordFinal, 1);
                        if (list != null) {
                            for (SearchResult sr : list) {
                                if (sr == null) continue;
                                // 标注来源小程序，供展示分组与点击切换（SearchResult 保持 8.83 原版纯净，来源放外部映射）
                                if (gen == crossSearchGen.get()) searchSrcRules.put(sr, r.getTitle());
                                aggregated.add(sr);
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                // 整体超时 25 秒，避免某个规则卡死拖住全部
                latch.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                if (!pool.isShutdown()) pool.shutdownNow();
            } catch (Throwable ignored) {
            }
            final List<SearchResult> result = new ArrayList<>(aggregated);
            App.post(() -> {
                if (!isAdded() || binding == null) return;
                if (gen != crossSearchGen.get()) return; // 已被新搜索取代
                searchResults.clear();
                // 注意：不要在这里清 searchSrcRules——worker 线程刚把来源标注写进去，
                // 清掉会导致"【来源】"后缀和点击切换丢失。搜索开始时已清过。
                searchResults.addAll(result);
                searchAdapter.notifyDataSetChanged();
                if (result.isEmpty()) {
                    binding.tvSearchEmpty.setVisibility(View.VISIBLE);
                    binding.rvSearchResults.setVisibility(View.GONE);
                    binding.tvSearchEmpty.setText("无相关结果");
                } else {
                    binding.tvSearchEmpty.setVisibility(View.GONE);
                    binding.rvSearchResults.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void doSearch(String kw) {
        HkRule rule = host().getCurrentRule();
        if (kw.isEmpty() || rule == null) return;
        keyword = kw;
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        binding.tvSearchEmpty.setVisibility(View.GONE);
        // 空态文案带上作用域，避免"无相关结果"被误读为全局无结果
        binding.tvSearchEmpty.setText("在「" + rule.getTitle() + "」中无相关结果");
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        binding.historySection.setVisibility(View.GONE);
        saveHistory(kw);
        doSearchPage();
    }

    private void doSearchPage() {
        HkRule rule = host().getCurrentRule();
        HkRouter router = getRouter();
        if (searchLoading || rule == null || router == null || keyword.isEmpty()) return;
        searchLoading = true;
        final int p = searchPage;
        final String kw = keyword;
        new Thread(() -> {
            List<SearchResult> list;
            try {
                list = router.search(kw, p);
                // 记录搜索实际抓取 URL：搜索结果图 Referer 用
                try {
                    String su = router.getEngine().getJsRuntime().getLastUrl();
                    if (!TextUtils.isEmpty(su) && (su.startsWith("http://") || su.startsWith("https://"))) {
                        hkPageUrl = su;
                    } else {
                        hkPageUrl = HkHttp.expandSearchUrl(rule.getSearchUrl(), kw, p);
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable e) {
                list = new ArrayList<>();
            }
            final List<SearchResult> result = list;
            App.post(() -> {
                if (!isAdded() || binding == null) return;
                searchLoading = false;
                if (result.isEmpty()) {
                    if (p == 1 && searchResults.isEmpty()) {
                        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
                        binding.rvSearchResults.setVisibility(View.GONE);
                    } else {
                        searchNoMore = true;
                    }
                } else {
                    searchResults.addAll(result);
                    searchPage = p + 1;
                    searchAdapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    private List<String> loadHistory() {
        List<String> out = new ArrayList<>();
        String s = getPrefs().getString(histKey(), "");
        if (!s.isEmpty()) {
            for (String part : s.split(HIST_SEP)) {
                if (!part.isEmpty()) out.add(part);
            }
        }
        return out;
    }

    private void saveHistory(String kw) {
        List<String> hist = loadHistory();
        hist.remove(kw);
        hist.add(0, kw);
        while (hist.size() > 20) hist.remove(hist.size() - 1);
        getPrefs().edit().putString(histKey(), TextUtils.join(HIST_SEP, hist)).apply();
        refreshHistory();
    }

    private void refreshHistory() {
        List<String> hist = loadHistory();
        List<String[]> pairs = new ArrayList<>();
        for (String h : hist) pairs.add(new String[]{h, h});
        binding.rvHistory.setAdapter(new ChipAdapter(pairs, -1, value -> doSearch(value)));
        binding.historySection.setVisibility(hist.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * 搜索结果点击：原文点击链（跨搜来源规则静默切换 switchRuleForDetail、
     * handleActionUrl、hiker://page/、openDetail(fromSearch=true)）由宿主的
     * hostOpenDetail(HkItem) 实现，此处只负责组装 HkItem 并委托。
     */
    private void onSearchResultClick(SearchResult s) {
        detailFromSearch = true;
        host().hostOpenDetail(HkItem.fromSearchResult(s));
    }

    // ================= 搜索结果列表 =================

    /** 原 HkPageActivity.VideoAdapter（搜索结果专用），Fragment 内局部。 */
    private class SearchVideoAdapter extends RecyclerView.Adapter<SearchVideoAdapter.Holder> {
        private final int imgHeight;

        SearchVideoAdapter() {
            // 8.83 item_movie_tools：封面容器固定 150dp
            imgHeight = dp(150);
        }

        class Holder extends RecyclerView.ViewHolder {
            ImageView cover;
            TextView title, desc;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.iv_cover);
                title = v.findViewById(R.id.tv_title);
                desc = v.findViewById(R.id.tv_desc);
                View container = v.findViewById(R.id.cover_container);
                if (container != null) {
                    ViewGroup.LayoutParams lp = container.getLayoutParams();
                    lp.height = imgHeight;
                    container.setLayoutParams(lp);
                } else {
                    ViewGroup.LayoutParams lp = cover.getLayoutParams();
                    lp.height = imgHeight;
                    cover.setLayoutParams(lp);
                }
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_video, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            SearchResult sr = searchResults.get(position);
            h.title.setText(sr.getTitle());
            // 跨小程序搜索结果：在 desc 前缀标注来源小程序
            String srcRule = searchSrcRules.get(sr);
            String desc = sr.getDesc() == null ? "" : sr.getDesc();
            if (!TextUtils.isEmpty(srcRule)) {
                desc = ("".equals(desc) ? "" : desc + "  ") + "【" + srcRule + "】";
            }
            h.desc.setText(desc);
            h.desc.setVisibility(TextUtils.isEmpty(desc) ? View.GONE : View.VISIBLE);
            ImgUtil.load(sr.getTitle(), sr.getImg(), h.cover, hkRuleUrl());
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                SearchResult s = searchResults.get(pos);
                onSearchResultClick(s);
            });
        }

        @Override
        public int getItemCount() {
            return searchResults.size();
        }
    }

    // ================= 网格间距（原 HkPageActivity.GridSpace） =================

    /**
     * 搜索网格列间距：列 i（0-based）：left = i*S/N，right = S-(i+1)*S/N；列间距 S=12dp。
     * 外边距由父容器 16dp 提供，decoration 不再加外边距。
     */
    private static class GridSpace extends RecyclerView.ItemDecoration {
        private final int s;

        GridSpace(Context context) {
            s = (int) (12 * context.getResources().getDisplayMetrics().density + 0.5f);
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
                if (pos > 0) outRect.top = s;
                return;
            }
            int perRow = Math.max(1, total / Math.max(1, span));
            int col = span > 0 ? spanIndex / span : 0;
            if (col < 0) col = 0;
            if (col >= perRow) col = perRow - 1;
            outRect.left = col * s / perRow;
            outRect.right = s - (col + 1) * s / perRow;
            if (pos >= perRow) outRect.top = s;
        }
    }

    // ================= 历史 chips（原 HkPageActivity.ChipAdapter，原样迁移） =================

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
}
