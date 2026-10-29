package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityHongguoSearchBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSearchTab;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.model.HgSuggestion;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果搜索面：三态——落地/清空时显搜索历史（本地 HgStore，最近在前，
 * 块头带清除位）；输入中 300ms 防抖拉联想（search/suggest 已签名口，
 * word_type=2 的词条带剧 id 可直达进剧，其余回填再搜）；提交后走
 * search/tab 结果（offset 翻页，滑到底续拉）。
 * 2026-10-28 逐项重对补全：此前联想接口在客户端里是旧无签名死形、
 * 界面从没调用，历史与翻页同样缺失，整个搜索只有第一页结果。
 * 2026-10-28 整页形态对齐官方：页面底白、搜索栏照 cqh/c8m 原值、历史块
 * 照 as1（「搜索历史」＋右端清除位）、联想行照 b7z、结果改双列瀑布卡
 * 照 b7l 构成（详见 hongguo/search-page-align-20261028.md）。
 */
public class HongguoSearchActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoSearchActivity.class));
    }

    private static final int MODE_HISTORY = 0;
    private static final int MODE_SUGGEST = 1;
    private static final int MODE_RESULT = 2;

    private static final int TYPE_RESULT = 0;
    private static final int TYPE_TEXT = 1;
    private static final int TYPE_HEADER = 2;
    private static final int TYPE_SUG = 3;

    private ActivityHongguoSearchBinding binding;
    private HgFeedSession session;
    private LinearLayoutManager linearManager;
    private StaggeredGridLayoutManager gridManager;
    private int mode = MODE_HISTORY;
    private final List<HgSeriesItem> results = new ArrayList<>();
    private final List<HgSuggestion> suggestions = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggest;
    private int suggestSeq;
    private String currentKeyword = "";
    // 搜索结果页签（服务端 search_tabs 下发，视频面三页签）：当前页签与页签表
    private int searchTabType = 11;
    private java.util.List<HgSearchTab> searchTabs = new java.util.ArrayList<>();
    private boolean loadingMore;
    private boolean lastPageFull;
    private final List<String> cues = new ArrayList<>();
    private int cueIndex;
    private final Runnable cueTicker = new Runnable() {
        @Override
        public void run() {
            rotateCueHint();
        }
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoSearchBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        // 整页浅底（官方白底）后系统图标转深色，与官方浅面一致
        return true;
    }

    /** 结果态双列瀑布（StaggeredGrid）、其余态单列；网格时列表两侧各让 5dp 与卡 margin 拼成 10dp 边距。 */
    private void useGrid(boolean grid) {
        RecyclerView.LayoutManager want = grid ? gridManager : linearManager;
        if (binding.hgSearchList.getLayoutManager() != want) {
            binding.hgSearchList.setLayoutManager(want);
        }
        int side = grid ? Math.round(5 * getResources().getDisplayMetrics().density) : 0;
        if (binding.hgSearchList.getPaddingStart() != side) {
            binding.hgSearchList.setPadding(side, binding.hgSearchList.getPaddingTop(),
                    side, binding.hgSearchList.getPaddingBottom());
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 顶部搜索行留距按状态栏真实高度走 insets，不写死数值（与刷剧页同口径）
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            android.view.ViewGroup.MarginLayoutParams lp =
                    (android.view.ViewGroup.MarginLayoutParams) binding.hgSearchTop.getLayoutParams();
            if (lp.topMargin != top) {
                lp.topMargin = top;
                binding.hgSearchTop.setLayoutParams(lp);
            }
            return insets;
        });
        session = HgFeedSession.get();
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemViewType(int position) {
                if (mode == MODE_RESULT) return TYPE_RESULT;
                if (mode == MODE_SUGGEST) return TYPE_SUG;
                if (position == 0) return TYPE_HEADER;
                return TYPE_TEXT;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                LayoutInflater inflater = LayoutInflater.from(parent.getContext());
                if (viewType == TYPE_RESULT) {
                    return new ResultHolder(ItemHongguoSearchBinding.inflate(inflater, parent, false));
                }
                if (viewType == TYPE_HEADER) {
                    return new HeaderHolder(inflater.inflate(R.layout.item_hongguo_search_header, parent, false));
                }
                if (viewType == TYPE_SUG) {
                    return new SugHolder(inflater.inflate(R.layout.item_hongguo_search_sug, parent, false));
                }
                TextView tv = (TextView) inflater.inflate(R.layout.item_hongguo_search_text, parent, false);
                return new TextHolder(tv);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (mode == MODE_RESULT) {
                    HgSeriesItem item = results.get(position);
                    ItemHongguoSearchBinding b = ((ResultHolder) holder).binding;
                    ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(), item.getCoverUrl(), b.hgSCover);
                    b.hgSTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                    // 角标（tag_info）压封面顶部渐变带，有才显（官方 b7l TagView 缺省 gone 同口径）
                    if (item.getBadge() != null && !item.getBadge().isEmpty()) {
                        b.hgSBadge.setVisibility(View.VISIBLE);
                        b.hgSBadge.setText(item.getBadge());
                    } else {
                        b.hgSBadge.setVisibility(View.GONE);
                    }
                    // 封面左下文案位（官方双列卡 play_cnt_desc 同位）：优先热度文案，其次全X集；
                    // UGC 单集条两者皆无、官方同位是「♥N次赞」（digged_count），照此兜底
                    String overlay = item.getRecText();
                    if ((overlay == null || overlay.isEmpty()) && item.getEpisodeCount() > 0) {
                        overlay = "全" + item.getEpisodeCount() + "集";
                    }
                    if ((overlay == null || overlay.isEmpty()) && item.getDiggCount() > 0) {
                        overlay = "♥" + formatCount(item.getDiggCount()) + "次赞";
                    }
                    if (overlay != null && !overlay.isEmpty()) {
                        b.hgSOverlay.setVisibility(View.VISIBLE);
                        b.hgSOverlay.setText(overlay);
                    } else {
                        b.hgSOverlay.setVisibility(View.GONE);
                    }
                    // 作者行（12dp 圆头像＋名字）：搜索 cell 带作者才显（官方 b7l 作者位同构）；
                    // 点头像/名进作者页（与流页同一去处，uid 有才挂——没 uid 不挂假入口）
                    if (item.getAuthorName() != null && !item.getAuthorName().isEmpty()) {
                        b.hgSAuthorAvatar.setVisibility(View.VISIBLE);
                        b.hgSAuthorRing.setVisibility(View.VISIBLE);
                        b.hgSAuthorName.setVisibility(View.VISIBLE);
                        ImgUtil.load(item.getAuthorName(), item.getAuthorAvatar(), b.hgSAuthorAvatar);
                        b.hgSAuthorName.setText(item.getAuthorName());
                        if (item.getAuthorUid() != null && !item.getAuthorUid().isEmpty()) {
                            android.view.View.OnClickListener goAuthor = v ->
                                    HongguoAuthorActivity.start(HongguoSearchActivity.this,
                                            item.getAuthorUid(), item.getAuthorName(),
                                            item.getAuthorAvatar(), item.getAuthorDesc());
                            b.hgSAuthorAvatar.setOnClickListener(goAuthor);
                            b.hgSAuthorName.setOnClickListener(goAuthor);
                        } else {
                            b.hgSAuthorAvatar.setOnClickListener(null);
                            b.hgSAuthorName.setOnClickListener(null);
                        }
                    } else {
                        b.hgSAuthorAvatar.setVisibility(View.GONE);
                        b.hgSAuthorRing.setVisibility(View.GONE);
                        b.hgSAuthorName.setVisibility(View.GONE);
                    }
                    // 点赞数位（作者行右端）：有 digged_count 才显
                    if (item.getDiggCount() > 0) {
                        b.hgSDigg.setVisibility(View.VISIBLE);
                        b.hgSDigg.setText("♡" + formatCount(item.getDiggCount()));
                    } else {
                        b.hgSDigg.setVisibility(View.GONE);
                    }
                    b.getRoot().setOnClickListener(v -> onResultClick(item));
                    return;
                }
                int type = getItemViewType(position);
                if (type == TYPE_HEADER) {
                    ((HeaderHolder) holder).clear.setOnClickListener(v -> {
                        HgStore.clearSearchHistory();
                        showHistory();
                    });
                } else if (type == TYPE_SUG) {
                    HgSuggestion s = suggestions.get(position);
                    SugHolder h = (SugHolder) holder;
                    h.text.setText(s.getText());
                    h.itemView.setOnClickListener(v -> onSuggestionClick(s));
                    // 右端箭头＝只回填不搜（官方 SugItemHolder G2 口径）
                    h.arrow.setOnClickListener(v -> {
                        binding.hgSearchInput.setText(s.getText());
                        binding.hgSearchInput.setSelection(s.getText().length());
                    });
                } else {
                    TextView tv = ((TextHolder) holder).text;
                    String word = history.get(position - 1);
                    tv.setText(word);
                    tv.setTextColor(0xFF303030);
                    tv.setOnClickListener(v -> {
                        binding.hgSearchInput.setText(word);
                        binding.hgSearchInput.setSelection(word.length());
                        doSearch();
                    });
                }
            }

            @Override
            public int getItemCount() {
                if (mode == MODE_RESULT) return results.size();
                if (mode == MODE_SUGGEST) return suggestions.size();
                return history.isEmpty() ? 0 : history.size() + 1;
            }
        };
        linearManager = new LinearLayoutManager(this);
        gridManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        binding.hgSearchList.setLayoutManager(linearManager);
        binding.hgSearchList.setAdapter(adapter);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            binding.hgSearchInput.setTextCursorDrawable(R.drawable.bg_hg_search_cursor);
        }
        binding.hgSearchList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mode != MODE_RESULT || dy <= 0) return;
                int last = -1;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm instanceof StaggeredGridLayoutManager) {
                    int[] pos = ((StaggeredGridLayoutManager) lm).findLastVisibleItemPositions(null);
                    for (int p : pos) last = Math.max(last, p);
                } else if (lm instanceof LinearLayoutManager) {
                    last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                }
                if (last >= results.size() - 4) loadMore();
            }
        });
        showHistory();
        loadCues();
    }

    /** 提示词：拉一次 cue 词表，进框占位轮播（4 秒一条，只在输入为空时转）。 */
    private void loadCues() {
        session.client.searchCues(new HgCallback<List<String>>() {
            @Override
            public void onSuccess(List<String> list) {
                runOnUiThread(() -> {
                    if (list == null || list.isEmpty()) return;
                    cues.clear();
                    cues.addAll(list);
                    cueIndex = 0;
                    rotateCueHint();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 提示词拉不到不打扰：占位保持布局自带文案
            }
        });
    }

    private void rotateCueHint() {
        mainHandler.removeCallbacks(cueTicker);
        if (isFinishing() || isDestroyed() || cues.isEmpty()) return;
        Editable cur = binding.hgSearchInput.getText();
        if (cur == null || cur.toString().isEmpty()) {
            binding.hgSearchInput.setHint(cues.get(cueIndex % cues.size()));
            cueIndex++;
        }
        mainHandler.postDelayed(cueTicker, 4000);
    }

    @Override
    protected void initEvent() {
        binding.hgSearchGo.setOnClickListener(v -> doSearch());
        // 框内清除钮（官方 c8m hgs 同位）：有字才显，点按清空回落地态
        binding.hgSearchClear.setOnClickListener(v -> binding.hgSearchInput.setText(""));
        binding.hgSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        binding.hgSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.hgSearchClear.setVisibility(
                        s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
                if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
                String text = s == null ? "" : s.toString().trim();
                if (text.isEmpty()) {
                    suggestSeq++;
                    showHistory();
                    return;
                }
                pendingSuggest = () -> fetchSuggest(text);
                mainHandler.postDelayed(pendingSuggest, 300);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(cueTicker);
        if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
        super.onDestroy();
    }

    private void showHistory() {
        binding.hgSearchTabs.setVisibility(View.GONE);
        mode = MODE_HISTORY;
        useGrid(false);
        history.clear();
        history.addAll(HgStore.searchHistory());
        binding.hgSearchState.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void fetchSuggest(String prefix) {
        int seq = ++suggestSeq;
        session.client.searchSuggest(prefix, new HgCallback<List<HgSuggestion>>() {
            @Override
            public void onSuccess(List<HgSuggestion> list) {
                runOnUiThread(() -> {
                    if (seq != suggestSeq) return;
                    Editable cur = binding.hgSearchInput.getText();
                    if (cur == null || !prefix.equals(cur.toString().trim())) return;
                    suggestions.clear();
                    if (list != null) suggestions.addAll(list);
                    if (suggestions.isEmpty()) return;
                    binding.hgSearchTabs.setVisibility(View.GONE);
                    mode = MODE_SUGGEST;
                    useGrid(false);
                    binding.hgSearchState.setVisibility(View.GONE);
                    adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 联想失败不打扰：保持当前列表（历史/已打字状态），提交搜索仍可用
            }
        });
    }

    /** 计数文案：万以下原数，万起缩「X.X万」（与 rec_text 的万制文风一致）。 */
    private static String formatCount(long n) {
        if (n < 10000) return String.valueOf(n);
        long w = n / 1000;
        String s = (w / 10) + "." + (w % 10);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + "万";
    }

    /**
     * 搜索结果点击分发：普通剧/UGC 单集直进集流；合集条（content_type
     * 1003、身份是 album id）先进专辑详情拉名单再让用户选一条进——
     * 拿 album id 直当 series id 拉详情必空（账 hongguo-search-parity）。
     * 名单弹法复用集流页同款系列单（HgSeriesSheet），构成口径一致；
     * 拉空/失败给明确提示，不静默、不假跳。
     */
    private void onResultClick(HgSeriesItem item) {
        String albumId = item.getAlbumId();
        if (item.getContentType() != 1003 || albumId == null || albumId.isEmpty()) {
            HongguoFeedActivity.startSeries(this, item.getSeriesId(), item.getTitle(), item.getCoverUrl());
            return;
        }
        session.client.fetchAlbum(albumId, new HgCallback<List<HgSeriesItem>>() {
            @Override
            public void onSuccess(List<HgSeriesItem> rows) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (rows == null || rows.isEmpty()) {
                        android.widget.Toast.makeText(HongguoSearchActivity.this,
                                "合集暂时打不开，稍后再试", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    HgSeriesSheet.show(HongguoSearchActivity.this, rows, null,
                            session.interactions, picked ->
                                    HongguoFeedActivity.startSeries(HongguoSearchActivity.this,
                                            picked.getSeriesId(), picked.getTitle(), picked.getCoverUrl()));
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    android.widget.Toast.makeText(HongguoSearchActivity.this,
                            "合集暂时打不开，稍后再试", android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void onSuggestionClick(HgSuggestion s) {
        if (s.isSeriesLink()) {
            HgStore.addSearchHistory(s.getText());
            HongguoFeedActivity.startSeries(this, s.getKeyword(), s.getText(), null);
            return;
        }
        binding.hgSearchInput.setText(s.getText());
        binding.hgSearchInput.setSelection(s.getText().length());
        doSearch();
    }

    private void doSearch() {
        String keyword = binding.hgSearchInput.getText() == null
                ? "" : binding.hgSearchInput.getText().toString().trim();
        if (keyword.isEmpty()) return;
        if (pendingSuggest != null) mainHandler.removeCallbacks(pendingSuggest);
        suggestSeq++;
        HgStore.addSearchHistory(keyword);
        currentKeyword = keyword;
        mode = MODE_RESULT;
        searchTabType = 11;
        searchTabs = new java.util.ArrayList<>();
        binding.hgSearchTabs.setVisibility(View.GONE);
        loadSearchFirstPage();
    }

    /** 结果第一页（提交搜索与换页签共用）：按当前 searchTabType 重查。 */
    private void loadSearchFirstPage() {
        loadingMore = false;
        lastPageFull = false;
        useGrid(true);
        results.clear();
        adapter.notifyDataSetChanged();
        binding.hgSearchState.setVisibility(View.VISIBLE);
        binding.hgSearchState.setText("搜索中…");
        session.client.searchTab(currentKeyword, 0, searchTabType, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    if (mode != MODE_RESULT) return;
                    results.clear();
                    if (page.getItems() != null) results.addAll(page.getItems());
                    lastPageFull = page.getItems() != null && page.getItems().size() >= 20;
                    if (page.getTabs() != null && !page.getTabs().isEmpty()) {
                        searchTabs = page.getTabs();
                        renderSearchTabs();
                    }
                    adapter.notifyDataSetChanged();
                    if (results.isEmpty()) {
                        binding.hgSearchState.setVisibility(View.VISIBLE);
                        binding.hgSearchState.setText("没有找到相关短剧");
                    } else {
                        binding.hgSearchState.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> {
                    binding.hgSearchState.setVisibility(View.VISIBLE);
                    binding.hgSearchState.setText("搜索失败，稍后再试");
                });
            }
        });
    }

    /** 页签排：服务端下发几页签摆几页签（不足两页签不摆），选中态白字加粗。 */
    private void renderSearchTabs() {
        android.widget.LinearLayout strip = binding.hgSearchTabs;
        strip.removeAllViews();
        if (searchTabs.size() < 2) {
            strip.setVisibility(View.GONE);
            return;
        }
        strip.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        for (HgSearchTab tab : searchTabs) {
            TextView tv = new TextView(this);
            tv.setText(tab.title);
            tv.setTextSize(15);
            boolean on = tab.type == searchTabType;
            // 页签字色随整页浅底翻转（官方页签条材料值未抠到，此为适配值非真值）
            tv.setTextColor(on ? 0xFF000000 : 0x99000000);
            tv.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setPadding(Math.round(12 * density), Math.round(8 * density),
                    Math.round(12 * density), Math.round(8 * density));
            // 页签按压反馈走系统原生水波纹（质感层；官方页签条材料未抠到，不自创选中指示器）
            android.util.TypedValue ripple = new android.util.TypedValue();
            if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                tv.setBackgroundResource(ripple.resourceId);
            }
            tv.setOnClickListener(v -> switchSearchTab(tab.type));
            strip.addView(tv);
        }
    }

    private void switchSearchTab(int type) {
        if (type == searchTabType || mode != MODE_RESULT) return;
        searchTabType = type;
        renderSearchTabs();
        loadSearchFirstPage();
    }

    /** 结果翻页：offset＝已取条数；回包不满整页即到底，失败安静停（已显结果不丢）。 */
    private void loadMore() {
        if (loadingMore || !lastPageFull || currentKeyword.isEmpty()) return;
        loadingMore = true;
        session.client.searchTab(currentKeyword, results.size(), searchTabType, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    loadingMore = false;
                    if (mode != MODE_RESULT) return;
                    List<HgSeriesItem> more = page.getItems();
                    lastPageFull = more != null && more.size() >= 20;
                    if (more == null || more.isEmpty()) return;
                    int at = results.size();
                    results.addAll(more);
                    adapter.notifyItemRangeInserted(at, more.size());
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                runOnUiThread(() -> loadingMore = false);
            }
        });
    }

    private static final class ResultHolder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchBinding binding;

        ResultHolder(ItemHongguoSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TextHolder(TextView text) {
            super(text);
            this.text = text;
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final View clear;

        HeaderHolder(View root) {
            super(root);
            this.clear = root.findViewById(R.id.hg_hist_clear);
        }
    }

    private static final class SugHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final View arrow;

        SugHolder(View root) {
            super(root);
            this.text = root.findViewById(R.id.hg_sug_text);
            this.arrow = root.findViewById(R.id.hg_sug_arrow);
        }
    }
}
