package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoSearchBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSearchBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgCellPage;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果搜索面：走一阶段搜索接口（search/tab，字段未定版按容忍解析），
 * 结果点中以该剧起刷剧流（HongguoFeedActivity.startSeries，展开后续播）。
 */
public class HongguoSearchActivity extends BaseActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HongguoSearchActivity.class));
    }

    private ActivityHongguoSearchBinding binding;
    private HgFeedSession session;
    private final List<HgSeriesItem> results = new ArrayList<>();
    private RecyclerView.Adapter<Holder> adapter;

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
        adapter = new RecyclerView.Adapter<Holder>() {
            @NonNull
            @Override
            public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new Holder(ItemHongguoSearchBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull Holder holder, int position) {
                HgSeriesItem item = results.get(position);
                ItemHongguoSearchBinding b = holder.binding;
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
            }

            @Override
            public int getItemCount() {
                return results.size();
            }
        };
        binding.hgSearchList.setLayoutManager(new LinearLayoutManager(this));
        binding.hgSearchList.setAdapter(adapter);
    }

    @Override
    protected void initEvent() {
        binding.hgSearchBack.setOnClickListener(v -> finish());
        binding.hgSearchGo.setOnClickListener(v -> doSearch());
        binding.hgSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
    }

    private void doSearch() {
        String keyword = binding.hgSearchInput.getText() == null
                ? "" : binding.hgSearchInput.getText().toString().trim();
        if (keyword.isEmpty()) return;
        binding.hgSearchState.setVisibility(View.VISIBLE);
        binding.hgSearchState.setText("搜索中…");
        session.client.searchTab(keyword, new HgCallback<HgCellPage>() {
            @Override
            public void onSuccess(HgCellPage page) {
                runOnUiThread(() -> {
                    results.clear();
                    if (page.getItems() != null) results.addAll(page.getItems());
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

    private static final class Holder extends RecyclerView.ViewHolder {
        final ItemHongguoSearchBinding binding;

        Holder(ItemHongguoSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
