package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMusicOptionsBinding;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;

import java.util.List;

/** 音乐通用选项底部弹窗：单列文字选项，点选即回。 */
public class MusicOptionsDialog extends BaseBottomSheetDialog {

    public interface OnPick {
        void onPick(int index);
    }

    private DialogMusicOptionsBinding binding;
    private String title;
    private List<String> options;
    private int checked = -1;
    private OnPick callback;

    public static void show(FragmentActivity activity, String title, List<String> options, int checked, OnPick callback) {
        MusicOptionsDialog d = new MusicOptionsDialog();
        d.title = title;
        d.options = options;
        d.checked = checked;
        d.callback = callback;
        // 标题／选项／勾选位走 arguments：系统重建弹窗时实例字段会丢，arguments 能原样恢复（回调无法序列化，仍走字段）。
        android.os.Bundle args = new android.os.Bundle();
        args.putString("title", title);
        args.putStringArrayList("options", options == null ? null : new java.util.ArrayList<>(options));
        args.putInt("checked", checked);
        d.setArguments(args);
        d.show(activity.getSupportFragmentManager(), "music_options");
    }

    // 内容自带浅色底（card_music 白卡）：
    // 弹窗容器/窗口必须透明，由内容自带底色铺满。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicOptionsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        android.os.Bundle args = getArguments();
        if (args != null) {
            if (args.containsKey("title")) title = args.getString("title");
            if (args.containsKey("options")) options = args.getStringArrayList("options");
            checked = args.getInt("checked", checked);
        }
        binding.tvTitle.setText(title == null ? "" : title);
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(new OptionsAdapter());
    }

    private class OptionsAdapter extends RecyclerView.Adapter<OptionsAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_option, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String text = options.get(position);
            boolean isChecked = position == checked;
            h.option.setText(text);
            h.option.setTextColor(isChecked ? 0xFFFA243C : 0xFF1C1C1E);
            h.option.getPaint().setFakeBoldText(isChecked);
            h.itemView.setOnClickListener(v -> {
                if (callback != null) callback.onPick(position);
                dismissAllowingStateLoss();
            });
        }

        @Override
        public int getItemCount() {
            return options == null ? 0 : options.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView option;

            Holder(View v) {
                super(v);
                option = v.findViewById(R.id.option);
            }
        }
    }
}
