package com.fongmi.android.tv.ui.hk;

import android.content.Context;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;

/**
 * 海阔弹窗统一入口：浅色玻璃主题 + 统一淡入淡出。
 * 用法：把 new AlertDialog.Builder(...) 换成 new HkDialogs(...)，其余链式调用一字不动。
 * 只换弹窗的皮（主题背景/圆角/按钮与列表字色/淡入动画）；文案、按钮个数与顺序、点击回调逻辑一律不动。
 */
public class HkDialogs extends AlertDialog.Builder {

    public HkDialogs(@NonNull Context context) {
        super(context, R.style.ThemeOverlay_XingChen_GlassDialog_HkLight);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        Window window = dialog.getWindow();
        if (window != null) window.setWindowAnimations(R.style.HkDialogFade);
        return dialog;
    }
}
