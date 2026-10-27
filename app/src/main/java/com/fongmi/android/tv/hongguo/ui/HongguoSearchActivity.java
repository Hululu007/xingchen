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
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityHongguoSearchBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.hongguo.model.HgSuggestion;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果搜索面：三态一列表——落地/清空时显搜索历史（本地 HgStore，最近在前，
 * 末尾带清除行）；输入中 300ms 防抖拉联想（search/suggest 已签名口，
 * word_type=2 的词条带剧 id 可直达进剧，其余回填再搜）；提交后走
 * search/tab 结果（offset 翻页，滑到底续拉）。
 * 2026-10-28 逐项重对补全：此前联想接口在客户端里是旧无签名死形、
 * 界面从没调用，历史与翻页同样缺失，整个搜索只有第一页结果。
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
    private static final int TYPE_CLEAR = 3;

    private ActivityHongguoSearchBinding binding;
    private HgFeedSession session;
    private int mode = MODE_HISTORY;
    private final List<HgSeriesItem> results = new ArrayList<>();
    private final List<HgSuggestion> suggestions = new ArrayList<>();
    private final List<String> history = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggest;
    private int suggestSeq;
    private String currentKeyword = "";
    private boolean loadingMore;
    private boolean lastPageFull;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoSearchBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
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
                if (mode == MODE_HISTORY) {
                    if (position == 0) return TYPE_HEADER;
                    if (position == history.size() + 1) return TYPE_CLEAR;
                }
                return TYPE_TEXT;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                if (viewType == TYPE_RESULT) {
                    return new ResultHolder(ItemHongguoSearchBinding.inflate(
                            LayoutInflater.from(parent.getContext()), parent, false));
                }
                TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_hongguo_search_text, parent, false);
                return new TextHolder(tv);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (mode == MODE_RESULT) {
                    HgSeriesItem item = results.get(position);
                    ItemHongguoSearchBinding b = ((ResultHolder) holder).binding;
                    ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(), item.getCoverUrl(), b.hgSCover);
                    b.hgSTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                    StringBuilder meta = new StringBuilder();
                    if (item.getEpisodeCount() > 0) meta.append("全").append(item.getEpisodeCount()).append("集");
                    if (item.getDesc() != null && !item.getDesc().isEmpty()) {
                        if (meta.length() > 0) meta.append(" · ");
                        meta.append(item.getDesc());
                    }
                    b.hgSMeta.setText(meta.toString());
                    b.getRoot().setOnClickListener(v ->
                            HongguoFeedActivity.startSeries(HongguoSearchActivity.this,
                                    item.getSeriesId(), item.getTitle(), item.getCoverUrl()));
                    return;
                }
                TextView tv = ((TextHolder) holder).text;
                int type = getItemViewType(position);
                if (type == TYPE_HEADER) {
                    tv.setText("历史搜索");
                    tv.setTextColor(0x99FFFFFF);
                    tv.setOnClickListener(null);
                    tv.setClickable(false);
                } else if (type == TYPE_CLEAR) {
                    tv.setText("清除历史记录");
                    tv.setTextColor(0x99FFFFFF);
                    tv.setOnClickListener(v -> {
                        HgStore.clearSearchHistory();
                        showHistory();
                    });
                } else if (mode == MODE_HISTORY) {
                    String word = history.get(position - 1);
                    tv.setText(word);
                    tv.setTextColor(0xF2FFFFFF);
                    tv.setOnClickListener(v -> {
                        binding.hgSearchInput.setText(word);
                        binding.hgSearchInput.setSelection(word.length());
                        doSearch();
                    });
                } else {
                    HgSuggestion s = suggestions.get(position);
                    tv.setText(s.getText());
                    tv.setTextColor(0xF2FFFFFF);
                    tv.setOnClickListener(v -> onSuggestionClick(s));
                }
            }

            @Override
            public int getItemCount() {
                if (mode == MODE_RESULT) return results.size();
                if (mode == MODE_SUGGEST) return suggestions.size();
                return history.isEmpty() ? 0 : history.size() + 2;
            }
        };
        binding.hgSearchList.setLayoutManager(new LinearLayoutManager(this));
        binding.hgSearchList.setAdapter(adapter);
        binding.hgSearchList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (mode != MODE_RESULT || dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= results.size() - 4) loadMore();
            }
        });
        showHistory();
    }

    @Override
    protected void initEvent() {
        binding.hgSearchGo.setOnClickListener(v -> doSearch());
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

    private void showHistory() {
        mode = MODE_HISTORY;
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
                    mode = MODE_SUGGEST;
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
        loadingMore = false;
        lastPageFull = false;
        results.clear();
        adapter.notifyDataSetChanged();
        binding.hgSearchState.setVisibility(View.VISIBLE);
        binding.hgSearchState.setText("搜索中…");
        session.client.searchTab(keyword, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    results.clear();
                    if (page.getItems() != null) results.addAll(page.getItems());
                    lastPageFull = page.getItems() != null && page.getItems().size() >= 20;
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

    /** 结果翻页：offset＝已取条数；回包不满整页即到底，失败安静停（已显结果不丢）。 */
    private void loadMore() {
        if (loadingMore || !lastPageFull || currentKeyword.isEmpty()) return;
        loadingMore = true;
        session.client.searchTab(currentKeyword, results.size(), new HgCallback<HgCellPage>() {
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
}
