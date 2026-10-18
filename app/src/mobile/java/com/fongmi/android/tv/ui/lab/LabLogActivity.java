package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.utils.Notify;

/**
 * 运行记录页：每包一份自己的日志，留全但平时藏着（从详情页「查看运行记录」进来）。
 * 不设叉，走系统返回。
 */
public class LabLogActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "id";
    private static final String EXTRA_NAME = "name";

    private String pkgId;
    private TextView logView;
    private ScrollView scroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    public static void start(Activity activity, String pkgId, String name) {
        Intent i = new Intent(activity, LabLogActivity.class);
        i.putExtra(EXTRA_ID, pkgId);
        i.putExtra(EXTRA_NAME, name);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkgId = getIntent().getStringExtra(EXTRA_ID);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(LabSkin.pageBackground(this));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(LabSkin.dp(this, 10), LabSkin.dp(this, 14), LabSkin.dp(this, 18), LabSkin.dp(this, 6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(0xFFC9C9D2);
        back.setPadding(LabSkin.dp(this, 8), LabSkin.dp(this, 8), LabSkin.dp(this, 14), LabSkin.dp(this, 8));
        back.setOnClickListener(v -> onBackPressed());
        TextView title = new TextView(this);
        title.setText("运行记录" + (name == null ? "" : " · " + name));
        title.setTextSize(20);
        title.setTextColor(LabSkin.TEXT_MAIN);
        title.getPaint().setFakeBoldText(true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextSize(14);
        clear.setTextColor(LabSkin.TEXT_DIM);
        clear.setPadding(LabSkin.dp(this, 12), LabSkin.dp(this, 8), LabSkin.dp(this, 12), LabSkin.dp(this, 8));
        clear.setOnClickListener(v -> {
            LabRunner.clearLog(this, pkgId);
            refresh();
            Notify.show("清掉了");
        });
        nav.addView(back);
        nav.addView(title);
        nav.addView(clear);
        root.addView(nav);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(13);
        logView.setTextColor(LabSkin.TEXT_SUB);
        logView.setPadding(LabSkin.dp(this, 18), LabSkin.dp(this, 8), LabSkin.dp(this, 18), LabSkin.dp(this, 18));
        scroll.addView(logView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void refresh() {
        String log = LabRunner.getLog(this, pkgId);
        logView.setText(log.isEmpty() ? "还没有记录，启动一次就有" : log);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
