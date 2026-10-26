package com.fongmi.android.tv.ui.music;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 音乐弹窗统一玻璃壳的构造器（2026-10-26「全换玻璃」）：与 MaterialAlertDialogBuilder
 * 同 API，create() 出来的弹窗统一经 MusicGlassDialogs.attach 换玻璃底＋翻白字。
 * 音乐内的 Material 弹窗构造点一律走本类，不再逐个自创材料。
 * 注意：站点在 create() 之后自设 setOnShowListener 会覆盖壳的显示回调，
 * 那种站点要在自己的监听里手动补 MusicGlassDialogs.apply(dialog)。
 */
public class MusicGlassDialogBuilder extends MaterialAlertDialogBuilder {

    public MusicGlassDialogBuilder(@NonNull Context context) {
        super(context);
    }

    public MusicGlassDialogBuilder(@NonNull Context context, @StyleRes int themeResId) {
        super(context, themeResId);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        MusicGlassDialogs.attach(dialog);
        return dialog;
    }
}
