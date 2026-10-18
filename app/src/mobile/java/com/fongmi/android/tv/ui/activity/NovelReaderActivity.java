package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 小说阅读器（阶段四实现：纵滑＋字号＋主题＋进度）。
 * 阶段一先占位，保证判别层改道链编译闭环。
 */
public class NovelReaderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("小说阅读器建设中…");
        tv.setTextSize(18);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
