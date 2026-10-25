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
import com.fongmi.android.tv.ui.music.MusicGlassDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
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

    // 回调按令牌登记在进程内表里：令牌随 arguments 走，系统重建弹窗（旋转等）后
    // 实例字段丢失，凭令牌能把原回调取回；弹窗真正销毁（非配置变化）时摘除防泄漏。
    private static final java.util.Map<String, OnPick> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    public static void show(FragmentActivity activity, String title, List<String> options, int checked, OnPick callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        MusicOptionsDialog d = new MusicOptionsDialog();
        d.title = title;
        d.options = options;
        d.checked = checked;
        d.callback = callback;
        // 标题／选项／勾选位走 arguments：系统重建弹窗时实例字段会丢，arguments 能原样恢复。
        android.os.Bundle args = new android.os.Bundle();
        args.putString("title", title);
        args.putStringArrayList("options", options == null ? null : new java.util.ArrayList<>(options));
        args.putInt("checked", checked);
        String token = null;
        if (callback != null) {
            token = "mo_" + SEQ.incrementAndGet();
            PENDING.put(token, callback);
            args.putString("cb_token", token);
        }
        d.setArguments(args);
        try {
            d.show(activity.getSupportFragmentManager(), "music_options");
        } catch (Throwable t) {
            // show 没成：弹窗不会走 onDestroy 摘令牌，必须在这里摘，否则令牌只进不出
            if (token != null) PENDING.remove(token);
            throw t;
        }
    }

    private OnPick resolveCallback() {
        if (callback != null) return callback;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token == null ? null : PENDING.get(token);
    }

    // 失效自关判定：字段已丢（系统重建的新实例）＋arguments 里有令牌（原本登记过回调）
    // ＋凭令牌回表取不到。三条全中只可能是进程死亡把静态表清空，原回调已不可恢复。
    private boolean callbackLost() {
        if (callback != null) return false;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token != null && PENDING.get(token) == null;
    }

    @Override
    public void onStart() {
        super.onStart();
        // 显示后自检：不可恢复就立即自关，不留能看不能点的僵尸弹窗；用户从活页面重开即正常。
        if (callbackLost()) dismissAllowingStateLoss();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (getActivity() == null || !getActivity().isChangingConfigurations()) {
            android.os.Bundle args = getArguments();
            String token = args == null ? null : args.getString("cb_token");
            if (token != null) PENDING.remove(token);
        }
    }

    // 内容自带浅色底（card_music 白卡）：
    // 弹窗容器/窗口必须透明，由内容自带底色铺满。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        binding = DialogMusicOptionsBinding.inflate(inflater, container, false);
        // 面板底＝全 App 同源液态玻璃：仅顶角 16dp、贴底无缺口；糊底走独立窗口静态回落
        binding.getRoot().setBackground(new MusicGlassDrawable(inflater.getContext(), 16, true));
        MusicGlassWire.glassifyTree(binding.getRoot()); // 面板内件同源接线
        return binding;
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
                OnPick pick = resolveCallback();
                if (pick != null) pick.onPick(position);
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
                MusicGlassWire.glassifyTree(v); // 选项行一框：行卡同源真玻璃
                option = v.findViewById(R.id.option);
            }
        }
    }
}
