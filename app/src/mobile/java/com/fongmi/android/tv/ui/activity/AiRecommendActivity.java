package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ai.AiRecommendHelper;
import com.fongmi.android.tv.ai.AiStore;
import com.fongmi.android.tv.databinding.ActivityAiRecommendBinding;
import com.fongmi.android.tv.databinding.ItemAiRecommendBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 推荐页：根据观看历史生成个性化推荐，一次调用 + 1 小时缓存。
 * 未配置 AI 时不显示入口（见 FeaturesActivity）。
 */
public class AiRecommendActivity extends BaseActivity {

    private ActivityAiRecommendBinding binding;
    private RecommendAdapter adapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AiRecommendActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityAiRecommendBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(binding.toolbar);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new RecommendAdapter());
        load(false);
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    /** 加载推荐；force=true 时清缓存重新生成 */
    private void load(boolean force) {
        if (!AiStore.hasAny(this)) {
            binding.progressLayout.showEmpty();
            Notify.show("请先在 AI 设置中配置服务商");
            return;
        }
        if (force) AiRecommendHelper.clearCache(this);
        binding.progressLayout.showProgress();
        AiRecommendHelper.recommend(this, new AiRecommendHelper.RecommendCallback() {
            @Override
            public void onResult(List<AiRecommendHelper.RecommendItem> items) {
                adapter.setItems(items);
                if (items.isEmpty()) binding.progressLayout.showEmpty();
                else binding.progressLayout.showContent();
            }

            @Override
            public void onError(String msg) {
                binding.progressLayout.showEmpty();
                Notify.show(msg);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(com.fongmi.android.tv.R.menu.menu_ai_recommend, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            onBackInvoked();
            return true;
        }
        if (id == com.fongmi.android.tv.R.id.refresh) {
            Notify.show("正在重新生成推荐…");
            load(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class RecommendAdapter extends RecyclerView.Adapter<RecommendAdapter.Holder> {

        private final List<AiRecommendHelper.RecommendItem> items = new ArrayList<>();

        void setItems(List<AiRecommendHelper.RecommendItem> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemAiRecommendBinding ib = ItemAiRecommendBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(ib);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            AiRecommendHelper.RecommendItem item = items.get(position);
            holder.binding.tvTitle.setText(item.title);
            holder.binding.tvReason.setText(item.reason);
            holder.itemView.setOnClickListener(v ->
                    SearchActivity.start(AiRecommendActivity.this, item.title));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ItemAiRecommendBinding binding;

            Holder(ItemAiRecommendBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
