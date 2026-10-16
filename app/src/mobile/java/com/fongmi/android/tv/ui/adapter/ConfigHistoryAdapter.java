package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ItemConfigHistoryBinding;
import com.fongmi.android.tv.databinding.ItemEmbyLineBinding;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.ui.view.EmbyHomeView;

import java.util.List;

public class ConfigHistoryAdapter extends RecyclerView.Adapter<ConfigHistoryAdapter.ViewHolder> {

    private final List<Config> items;
    private final OnItemClickListener listener;
    private OnManageListener manageListener;
    private String currentUrl;

    public interface OnItemClickListener {
        void onUse(Config config);
    }

    public interface OnManageListener {
        void onEdit(Config config);
        void onDelete(Config config);
    }

    public ConfigHistoryAdapter(List<Config> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
        refreshCurrentUrl();
    }

    public void setOnManageListener(OnManageListener manageListener) {
        this.manageListener = manageListener;
    }

    private void refreshCurrentUrl() {
        try {
            currentUrl = com.fongmi.android.tv.api.config.VodConfig.get().getConfig().getUrl();
        } catch (Exception e) {
            currentUrl = "";
        }
    }

    public void updateItems(List<Config> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        refreshCurrentUrl();
        notifyDataSetChanged();
    }

    public boolean isActive(Config config) {
        // 专属首页展示中时，点播行一律不标"使用中"（与 Emby 行互斥）
        if (EmbyHomeView.isShowing()) return false;
        return config.getUrl() != null && config.getUrl().equals(currentUrl);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemConfigHistoryBinding binding = ItemConfigHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config config = items.get(position);
        String name = config.getName();
        holder.binding.name.setText(name == null || name.isEmpty() ? "未命名" : name);
        holder.binding.url.setText(config.getUrl() == null ? "" : config.getUrl());
        // Highlight the currently active config
        boolean active = isActive(config);
        holder.binding.use.setText(active ? "使用中" : "使用");
        holder.binding.use.setEnabled(!active);
        holder.binding.use.setAlpha(active ? 0.5f : 1.0f);
        holder.binding.use.setOnClickListener(v -> {
            if (listener != null) listener.onUse(config);
        });
        // Manage buttons only in line-management mode
        int manageVisibility = manageListener != null ? View.VISIBLE : View.GONE;
        holder.binding.edit.setVisibility(manageVisibility);
        holder.binding.delete.setVisibility(manageVisibility);
        holder.binding.edit.setOnClickListener(v -> {
            if (manageListener != null) manageListener.onEdit(config);
        });
        holder.binding.delete.setOnClickListener(v -> {
            if (manageListener != null) manageListener.onDelete(config);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public Config getItem(int position) {
        return items.get(position);
    }

    public void remove(int position) {
        items.remove(position);
        notifyItemRemoved(position);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemConfigHistoryBinding binding;
        ViewHolder(ItemConfigHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ---------- 线路管理弹窗里的 Emby 独立分区 ----------

    public interface OnEmbyClickListener {
        void onUse(EmbyServer server);
    }

    public static class EmbyAdapter extends RecyclerView.Adapter<EmbyAdapter.Holder> {

        private final List<EmbyServer> servers;
        private final OnEmbyClickListener listener;

        public EmbyAdapter(List<EmbyServer> servers, OnEmbyClickListener listener) {
            this.servers = servers;
            this.listener = listener;
        }

        private boolean isActive(EmbyServer s) {
            if (!EmbyHomeView.isShowing() || s == null || s.id == null) return false;
            try {
                EmbyServer act = EmbyStore.active();
                return act != null && s.id.equals(act.id);
            } catch (Throwable t) {
                return false;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemEmbyLineBinding binding = ItemEmbyLineBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new Holder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            EmbyServer s = servers.get(position);
            String alias = s.alias;
            holder.binding.name.setText(alias == null || alias.isEmpty() ? "未命名" : alias);
            holder.binding.url.setText(s.baseUrl == null ? "" : s.baseUrl);
            boolean active = isActive(s);
            holder.binding.use.setText(active ? R.string.emby_in_use : R.string.emby_use);
            holder.binding.use.setEnabled(!active);
            holder.binding.use.setAlpha(active ? 0.5f : 1.0f);
            holder.binding.use.setOnClickListener(v -> {
                if (listener != null) listener.onUse(s);
            });
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onUse(s);
            });
        }

        @Override
        public int getItemCount() {
            return servers == null ? 0 : servers.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ItemEmbyLineBinding binding;
            Holder(ItemEmbyLineBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}