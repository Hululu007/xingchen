package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.fongmi.android.tv.App;
import com.google.android.material.snackbar.Snackbar;

/**
 * 全应用提示统一入口：优先用实底白字的 Snackbar。
 * 他手机上系统 Toast 是浅色透底，盖在亮色画面上字看不清；自定义 Toast 视图在高版本
 * 系统上会被忽略回系统样式，改了也白改，所以不再走 Toast 自定义视图。
 * 没有可用 Activity 时才回落系统 Toast。
 */
public class ToastUtil {

    private static Snackbar current;

    public static void show(Context context, CharSequence text) {
        show(context, text, Toast.LENGTH_SHORT);
    }

    public static void show(Context context, CharSequence text, int duration) {
        if (context == null || text == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            App.post(() -> show(context, text, duration));
            return;
        }
        try {
            Activity activity = context instanceof Activity ? (Activity) context : App.activity();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                View anchor = activity.findViewById(android.R.id.content);
                if (anchor != null) {
                    if (current != null) current.dismiss();
                    int len = duration == Toast.LENGTH_LONG ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT;
                    Snackbar bar = Snackbar.make(anchor, text, len);
                    bar.getView().setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF262626")));
                    TextView tv = bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
                    if (tv != null) {
                        tv.setTextColor(Color.WHITE);
                        tv.setMaxLines(5);
                    }
                    current = bar;
                    bar.show();
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            // 兜底必须是系统 Toast 原样调用，不许再走 ToastUtil.show，否则自递归
            android.widget.Toast.makeText(context.getApplicationContext(), text, duration).show();
        } catch (Throwable ignored) {
        }
    }

    public static void dismiss() {
        try {
            if (current != null) current.dismiss();
        } catch (Throwable ignored) {
        }
        current = null;
    }
}
