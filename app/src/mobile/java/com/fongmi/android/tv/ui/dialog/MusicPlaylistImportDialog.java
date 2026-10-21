package com.fongmi.android.tv.ui.dialog;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogMusicPlaylistImportBinding;

/**
 * 第 11 项：GZ 导入歌单大面板（底部大白板形态）。
 * 平台格只是支持平台指示（GZ 图标为各平台 logo，星辰无 logo 素材，格内用平台名文字代替，见对照表）；
 * 登录账号导入行只放开这一处（网易云／QQ音乐），点按回调宿主走 WebView 登录页；
 * 宿主读到 Cookie 后直接调平台公开接口拉“我的歌单”列表，点某张走 PlaylistImporter 现有导入链。
 */
public class MusicPlaylistImportDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onImportText(String text, MusicPlaylistImportDialog dialog);

        void onLxmc();

        void onAccountImport(String platform);
    }

    private static final String[] PLATFORMS_ROW1 = {"网易云", "QQ音乐", "酷狗", "酷我"};
    private static final String[] PLATFORMS_ROW2 = {"汽水", "Spotify", "Apple Music"};

    private DialogMusicPlaylistImportBinding binding;
    private Listener listener;

    public static void show(FragmentActivity activity, Listener listener) {
        if (activity == null || activity.isFinishing()) return;
        MusicPlaylistImportDialog d = new MusicPlaylistImportDialog();
        d.listener = listener;
        d.show(activity.getSupportFragmentManager(), "music_playlist_import");
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @NonNull
    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicPlaylistImportBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        buildPlatformRow(binding.platformRow1, PLATFORMS_ROW1);
        buildPlatformRow(binding.platformRow2, PLATFORMS_ROW2);
        binding.loginRow.setOnClickListener(v -> {
            if (listener == null) return;
            FragmentActivity host = getActivity();
            dismissAllowingStateLoss();
            if (host == null) return;
            MusicOptionsDialog.show(host, "登录账号导入",
                    java.util.Arrays.asList("网易云", "QQ音乐"), -1, idx -> {
                        if (listener != null) listener.onAccountImport(idx == 0 ? "netease" : "qq");
                    });
        });
        binding.lxRow.setOnClickListener(v -> {
            dismissAllowingStateLoss();
            if (listener != null) listener.onLxmc();
        });
        binding.btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());
        binding.btnImport.setOnClickListener(v -> {
            if (listener != null) listener.onImportText(
                    binding.etPaste.getText() == null ? "" : binding.etPaste.getText().toString(), this);
        });
        binding.etPaste.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshImportEnabled();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        refreshImportEnabled();
    }

    private void buildPlatformRow(LinearLayout row, String[] names) {
        row.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            TextView cell = new TextView(getContext());
            cell.setText(names[i]);
            cell.setTextSize(11);
            cell.setTextColor(0xFF1C1C1E);
            cell.setGravity(android.view.Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.bg_platform_cell);
            cell.setMaxLines(1);
            cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.height = dp(40);
            if (i > 0) lp.setMarginStart(dp(8));
            cell.setLayoutParams(lp);
            row.addView(cell);
        }
    }

    private void refreshImportEnabled() {
        boolean empty = binding.etPaste.getText() == null
                || binding.etPaste.getText().toString().trim().isEmpty();
        // GZ：粘贴框空时大导入钮透明度 0.38（截图粉色即禁用态）
        binding.btnImport.setAlpha(empty ? 0.38f : 1f);
        binding.btnImport.setEnabled(!empty);
    }

    public void setBusy(boolean busy) {
        if (binding == null) return;
        binding.etPaste.setEnabled(!busy);
        binding.btnImport.setEnabled(!busy);
        if (busy) {
            binding.tvStatus.setVisibility(View.VISIBLE);
            binding.tvStatus.setTextColor(0xFF9AA7B8);
            binding.tvStatus.setText(R.string.music_importing);
        }
    }

    public void setError(int resId) {
        if (binding == null) return;
        binding.tvStatus.setVisibility(View.VISIBLE);
        binding.tvStatus.setTextColor(0xFFFF8A80);
        binding.tvStatus.setText(resId);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
