package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogEmbyQualityBinding;
import com.fongmi.android.tv.emby.EmbyApi;
import com.fongmi.android.tv.emby.EmbyStore.EmbyServer;
import com.fongmi.android.tv.emby.EmbyStore;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/**
 * Emby 播前画质/转码选择弹窗：深色毛玻璃底，选项为独立卡片、有间距。
 * 集成层在播片前调 show()，拿到地址后自行走主播放链。
 */
public class EmbyQualityDialog extends DialogFragment {

    public interface OnPickListener {
        void onPick(EmbyApi.PlayOption option);
    }

    /** 直接给选项列表（调用方已拉好）。 */
    public static void show(FragmentActivity activity, List<EmbyApi.PlayOption> options, OnPickListener listener) {
        show(activity, options, null, listener);
    }

    private static boolean canShow(FragmentActivity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !activity.getSupportFragmentManager().isStateSaved();
    }

    /** 直接给选项列表，可覆盖标题（多版本选择用）。 */
    public static void show(FragmentActivity activity, List<EmbyApi.PlayOption> options, String title, OnPickListener listener) {
        if (!canShow(activity)) return;
        EmbyQualityDialog d = new EmbyQualityDialog();
        d.options = options;
        d.title = title;
        d.listener = listener;
        d.show(activity.getSupportFragmentManager(), "emby_quality");
    }

    /** 给条目，弹窗自己问服务器要 playbackOptions。 */
    public static void showForItem(FragmentActivity activity, String serverId, String itemId, String name, OnPickListener listener) {
        if (!canShow(activity)) return;
        EmbyQualityDialog d = new EmbyQualityDialog();
        d.serverId = serverId;
        d.itemId = itemId;
        d.itemName = name;
        d.listener = listener;
        d.show(activity.getSupportFragmentManager(), "emby_quality");
    }

    private DialogEmbyQualityBinding binding;
    private List<EmbyApi.PlayOption> options;
    private OnPickListener listener;
    private String title;
    private String serverId;
    private String itemId;
    private String itemName;
    private boolean loading;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.ThemeOverlay_XingChen_GlassDialog_Dark);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogEmbyQualityBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.btnCancel.setOnClickListener(v -> dismiss());
        if (!TextUtils.isEmpty(title)) binding.tvTitle.setText(title);
        if (options != null) {
            render(options);
        } else {
            loadOptions();
        }
    }

    private void loadOptions() {
        if (loading) return;
        loading = true;
        binding.tvLoading.setVisibility(View.VISIBLE);
        binding.tvEmpty.setVisibility(View.GONE);
        new Thread(() -> {
            List<EmbyApi.PlayOption> list = null;
            String err = null;
            try {
                EmbyServer s = findServer();
                if (s == null) throw new Exception("服务器找不到了");
                EmbyApi api = new EmbyApi(s.baseUrl, s.token, s.userId, s.id);
                list = api.playbackOptions(itemId, itemName);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<EmbyApi.PlayOption> data = list;
            final String msg = err;
            App.post(() -> {
                loading = false;
                if (binding == null) return;
                binding.tvLoading.setVisibility(View.GONE);
                if (data == null) {
                    binding.tvEmpty.setVisibility(View.VISIBLE);
                    if (msg != null && !msg.isEmpty()) binding.tvEmpty.setText(msg);
                    return;
                }
                render(data);
            });
        }).start();
    }

    private EmbyServer findServer() {
        try {
            List<EmbyServer> all = EmbyStore.servers();
            if (all != null) for (EmbyServer s : all) {
                if (s != null && serverId != null && serverId.equals(s.id)) return s;
            }
            return EmbyStore.active();
        } catch (Throwable t) {
            return null;
        }
    }

    private void render(List<EmbyApi.PlayOption> list) {
        binding.tvLoading.setVisibility(View.GONE);
        binding.container.removeAllViews();
        if (list == null || list.isEmpty()) {
            binding.tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvEmpty.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        boolean first = true;
        for (EmbyApi.PlayOption opt : list) {
            View card = inflater.inflate(R.layout.item_emby_quality, binding.container, false);
            TextView tvLabel = card.findViewById(R.id.tv_label);
            TextView tvSub = card.findViewById(R.id.tv_sub);
            TextView tvTag = card.findViewById(R.id.tv_tag);
            tvLabel.setText(opt.label == null ? "" : opt.label);
            tvSub.setText(subOf(opt));
            if (first) {
                tvTag.setVisibility(View.VISIBLE);
                tvTag.setText(opt.direct ? R.string.emby_quality_direct : R.string.emby_quality_transcode);
                first = false;
            }
            card.setOnClickListener(v -> {
                dismiss();
                if (listener != null) {
                    try {
                        listener.onPick(opt);
                    } catch (Throwable t) {
                        Notify.show("播放失败");
                    }
                }
            });
            binding.container.addView(card);
        }
    }

    private String subOf(EmbyApi.PlayOption opt) {
        StringBuilder sb = new StringBuilder();
        sb.append(opt.direct ? "不转码，原画直出" : "服务器转码，更省流量");
        if (opt.bitrate > 0) sb.append(" · ").append(fmtBitrate(opt.bitrate));
        return sb.toString();
    }

    private String fmtBitrate(long b) {
        if (b >= 1_000_000) return String.format("%.1f Mbps", b / 1_000_000.0);
        if (b >= 1_000) return (b / 1_000) + " Kbps";
        return b + " bps";
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            WindowManager.LayoutParams params = window.getAttributes();
            boolean land = ResUtil.isLand(requireContext());
            int width = Math.min(Math.round(ResUtil.getScreenWidth(requireContext()) * (land ? 0.58f : 0.92f)), ResUtil.dp2px(560));
            params.width = Math.max(width, ResUtil.dp2px(320));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.gravity = Gravity.CENTER;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setAttributes(params);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
