package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 红果刷剧页（2026-10-30 从零重建，阶段1 骨架）。
 * 旧实现已整删；本类先立入口与窗口形态（竖屏 behind＋全屏黑场），
 * 竖屏流/播放/剧条/选集在阶段3 起逐件长回，横屏页阶段8 重接。
 */
public class HongguoFeedActivity extends AppCompatActivity {

    public static void start(Context context) {
        Intent intent = new Intent(context, HongguoFeedActivity.class);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
    }
}
