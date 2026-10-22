package com.fongmi.android.tv.ui.dialog;

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

    public static ModuleDialog create(int index) {
        if (index < 0 || index >= ModuleUtil.MODULES.length) throw new IllegalArgumentException("Invalid module index");
        return new ModuleDialog(index);
    }

    private ModuleDialog(int index) {
        this.index = index;
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
            render();
        });
        model.getHealth().observe(getViewLifecycleOwner(), health -> {
            lastHealth = health;
            render();
        });
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
        binding.btnTest.setEnabled(lastState.canTest());
        binding.btnInstall.setEnabled(lastState.canInstall());
        binding.btnRemove.setEnabled(lastState.canRemove());
        binding.btnCancel.setEnabled(lastState.isBusy() || lastState.isPending());
    }
}
