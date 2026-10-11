package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.fongmi.android.tv.R;

/**
 * 深色毛玻璃 Toast：替代系统浅色 Toast，解决浅色底看不清的问题。
 * API 31+ 叠加 RenderEffect 真模糊，低版本用深色半透明底。
 */
public class ToastUtil {

    public static void show(Context context, CharSequence text) {
        show(context, text, Toast.LENGTH_SHORT);
    }

    public static void show(Context context, CharSequence text, int duration) {
        if (context == null || text == null) return;
        try {
            Context ctx = context.getApplicationContext();
            Toast toast = new Toast(ctx);
            View view = LayoutInflater.from(ctx).inflate(R.layout.view_dark_toast, null);
            TextView tv = view.findViewById(R.id.toast_text);
            tv.setText(text);
            // 真模糊（API 31+）：毛玻璃质感
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.CLAMP));
                } catch (Throwable ignored) {
                }
            }
            toast.setView(view);
            toast.setDuration(duration);
            toast.show();
        } catch (Throwable e) {
            // 兜底：系统 Toast
            try {
                Toast.makeText(context.getApplicationContext(), text, duration).show();
            } catch (Throwable ignored) {
            }
        }
    }
}
