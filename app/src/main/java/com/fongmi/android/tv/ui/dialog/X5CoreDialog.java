package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogX5CoreBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.X5Manager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * X5 内核下载管理：状态显示（未下载/下载中xx%/已下载xxMB）+ 下载/卸载。
 * 未下载时 App 内 WebView 自动用系统 WebView；下载后自动切 X5，无需重启。
 */
public class X5CoreDialog extends BaseAlertDialog {

    private DialogX5CoreBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private X5Manager.DownloadListener listener;
    private boolean destroyed = false;

    public static X5CoreDialog create() {
        return new X5CoreDialog();
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof X5CoreDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        binding = DialogX5CoreBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.CENTER);
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        float density = getResources().getDisplayMetrics().density;
        params.width = Math.min((int) (screenWidth * 0.94), (int) (700 * density));
        window.setAttributes(params);
    }

    @Override
    protected void initView() {
        binding.btnX5Close.setOnClickListener(v -> dismiss());
        binding.btnX5Download.setOnClickListener(v -> onDownloadClick());
        binding.btnX5Uninstall.setOnClickListener(v -> onUninstallClick());
        listener = new X5Manager.DownloadListener() {
            @Override
            public void onProgress(int percent) {
                handler.post(() -> { if (!destroyed) refresh(); });
            }
            @Override
            public void onFinish(boolean success) {
                handler.post(() -> {
                    if (destroyed) return;
                    refresh();
                    Notify.show(success ? "X5 内核下载完成" : "X5 内核下载失败");
                });
            }
        };
        X5Manager.setDownloadListener(listener);
        refresh();
    }

    private void onDownloadClick() {
        if (X5Manager.getStatus() == X5Manager.STATUS_DOWNLOADING) {
            Notify.show("正在下载中…");
            return;
        }
        X5Manager.startDownload();
        refresh();
    }

    private void onUninstallClick() {
        X5Manager.uninstallCore();
        Notify.show("X5 内核已卸载，已切回系统 WebView");
        refresh();
    }

    private void refresh() {
        if (binding == null || getActivity() == null) return;
        int status = X5Manager.getStatus();
        if (status == X5Manager.STATUS_DOWNLOADED) {
            binding.tvX5Status.setText("已下载 " + X5Manager.getCoreSizeText());
            binding.pbX5.setVisibility(View.GONE);
            binding.btnX5Download.setVisibility(View.GONE);
            binding.btnX5Uninstall.setVisibility(View.VISIBLE);
        } else if (status == X5Manager.STATUS_DOWNLOADING) {
            int p = X5Manager.getLastProgress();
            binding.tvX5Status.setText("下载中 " + p + "%");
            binding.pbX5.setVisibility(View.VISIBLE);
            binding.pbX5.setProgress(p);
            binding.btnX5Download.setVisibility(View.VISIBLE);
            binding.btnX5Download.setText("下载中…");
            binding.btnX5Uninstall.setVisibility(View.GONE);
        } else {
            binding.tvX5Status.setText("未下载");
            binding.pbX5.setVisibility(View.GONE);
            binding.btnX5Download.setVisibility(View.VISIBLE);
            binding.btnX5Download.setText("下载");
            binding.btnX5Uninstall.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        X5Manager.setDownloadListener(null);
        super.onDestroyView();
    }
}
