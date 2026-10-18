package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 漫画阅读器（阶段四实现：竖滑连读＋预加载＋章节＋进度）。
 * 阶段一先占位，保证判别层改道链编译闭环。
 */
public class ComicReaderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("漫画阅读器建设中…");
        tv.setTextSize(18);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
