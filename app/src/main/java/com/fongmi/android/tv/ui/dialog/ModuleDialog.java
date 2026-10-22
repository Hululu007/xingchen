package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogModuleBinding;
import com.fongmi.android.tv.model.ModuleViewModel;
import com.fongmi.android.tv.utils.ModuleUtil;

// 模块详情弹窗：补 ModuleActivity / ModuleFragment 调用的 ModuleDialog。
// 上游取件里没有这个文件（见交回卡点），这里按两处调用点合同实现：
// create(index).show(activity)，index 顺序与 ModuleUtil.MODULES 一致。
// 弹窗里的操作全部走 ModuleViewModel（安装／卸载／检测／取消），
// 不自己调 RuntimeInstaller，不另起下载链。
public final class ModuleDialog extends BaseBottomSheetDialog {

    private final int index;
    private DialogModuleBinding binding;
    private ModuleViewModel model;
    private ModuleViewModel.State lastState;
    private ModuleViewModel.Health lastHealth;
    // 磁力缺模块直装路用：装完（状态回到空闲且已装版本在）回调一次；
    // 没装成关掉时走 onClosed。模块页原有调用点不设这两个回调，行为不变。
    private Runnable onInstalled;
    private Runnable onClosed;
    private boolean installArmed;
    private boolean installFired;

    public static ModuleDialog create(int index) {
        if (index < 0 || index >= ModuleUtil.MODULES.length) throw new IllegalArgumentException("Invalid module index");
        return new ModuleDialog(index);
    }

    private ModuleDialog(int index) {
        this.index = index;
    }

    public ModuleDialog setOnInstalled(Runnable onInstalled) {
        this.onInstalled = onInstalled;
        return this;
    }

    public ModuleDialog setOnClosed(Runnable onClosed) {
        this.onClosed = onClosed;
        return this;
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!installFired && onClosed != null) onClosed.run();
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) if (fragment instanceof ModuleDialog) return;
        model = ModuleViewModel.get(activity, ModuleUtil.MODULES[index]);
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogModuleBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.moduleTitle.setText(model.getTitle());
        model.getState().observe(getViewLifecycleOwner(), state -> {
            lastState = state;
            if (state.phase() == ModuleViewModel.Phase.INSTALLING || state.phase() == ModuleViewModel.Phase.READY) installArmed = true;
            render();
            if (!installFired && installArmed && onInstalled != null
                    && state.phase() == ModuleViewModel.Phase.IDLE
                    && state.runtime() != null && state.runtime().installedVersion() != null) {
                installFired = true;
                onInstalled.run();
            }
        });
        model.getHealth().observe(getViewLifecycleOwner(), health -> {
            lastHealth = health;
            render();
        });
        // 重开时别停在上次的失败态：模型自己会判断要不要重新检测，
        // 检测失败的原因照样显示在弹窗里，安装钮也能再点。
        model.refresh();
    }

    @Override
    protected void initEvent() {
        binding.btnTest.setOnClickListener(v -> model.checkRuntime());
        binding.btnInstall.setOnClickListener(v -> model.install());
        binding.btnRemove.setOnClickListener(v -> model.remove());
        binding.btnCancel.setOnClickListener(v -> {
            ModuleViewModel.State state = lastState;
            if (state != null && (state.isBusy() || state.isPending())) model.cancel();
            else dismissAllowingStateLoss();
        });
    }

    private void render() {
        if (binding == null || lastState == null) return;
        binding.moduleStatus.setText(model.getStatusText(lastState));
        String error = lastState.error();
        if (error == null && lastHealth != null && lastHealth.phase() == ModuleViewModel.HealthPhase.FAILED) error = lastHealth.error();
        if (error == null) {
            binding.moduleError.setVisibility(View.GONE);
        } else {
            binding.moduleError.setVisibility(View.VISIBLE);
            binding.moduleError.setText(error);
        }
        boolean downloading = lastState.phase() == ModuleViewModel.Phase.INSTALLING && lastState.total() > 0 && lastState.downloaded() >= 0;
        binding.moduleProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        binding.moduleProgressText.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (downloading) {
            int percent = (int) (100L * lastState.downloaded() / lastState.total());
            binding.moduleProgress.setProgress(percent);
            binding.moduleProgressText.setText(percent + "%");
        }
        binding.btnTest.setEnabled(lastState.canTest());
        binding.btnInstall.setEnabled(lastState.canInstall());
        binding.btnRemove.setEnabled(lastState.canRemove());
        binding.btnCancel.setEnabled(lastState.isBusy() || lastState.isPending());
    }
}
