package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 书架（阶段四实现：在看的书＋进度＋继续读）。
 * 阶段一先占位。
 */
public class ReadingShelfActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("书架建设中…");
        tv.setTextSize(18);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
