package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.AdapterQualityBinding;

public class QualityAdapter extends RecyclerView.Adapter<QualityAdapter.ViewHolder> {

    private final OnClickListener listener;
    private Result result;
    private int position;
    /** 简易模式（Emby 画质行用）：直接给标签列表，不走 Result。 */
    private java.util.List<String> simpleLabels;
    private int simplePosition;
    private SimpleClickListener simpleListener;

    public QualityAdapter(OnClickListener listener) {
        this.listener = listener;
        this.result = Result.empty();
    }

    public interface OnClickListener {

        void onItemClick(Result result);
    }

    public interface SimpleClickListener {

        void onItemClick(int position);
    }

    public void addAll(Result result) {
        this.result = result;
        this.simpleLabels = null;
        this.simpleListener = null;
        notifyDataSetChanged();
    }

    /** 简易模式：labels 为 null/空 = 退出简易模式回到 Result 模式。 */
    public void setSimpleItems(java.util.List<String> labels, int selected, SimpleClickListener l) {
        this.simpleLabels = labels;
        this.simplePosition = selected;
        this.simpleListener = l;
        notifyDataSetChanged();
    }

    public boolean isSimpleMode() {
        return simpleLabels != null && !simpleLabels.isEmpty();
    }

    public int getPosition() {
        return position;
    }

    @Override
    public int getItemCount() {
        if (isSimpleMode()) return simpleLabels.size();
        return result.getUrl().getValues().size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterQualityBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (isSimpleMode()) {
            holder.binding.text.setText(simpleLabels.get(position));
            holder.binding.text.setOnClickListener(v -> onSimpleClick(position));
            holder.binding.text.setSelected(simplePosition == position);
            return;
        }
        holder.binding.text.setText(result.getUrl().n(position));
        holder.binding.text.setOnClickListener(v -> onItemClick(position));
        holder.binding.text.setSelected(result.getUrl().getPosition() == position);
    }

    private void onSimpleClick(int position) {
        this.simplePosition = position;
        if (simpleListener != null) simpleListener.onItemClick(position);
        notifyItemRangeChanged(0, getItemCount());
    }

    private void onItemClick(int position) {
        this.position = position;
        result.getUrl().set(position);
        listener.onItemClick(result);
        notifyItemRangeChanged(0, getItemCount());
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterQualityBinding binding;

        ViewHolder(@NonNull AdapterQualityBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}