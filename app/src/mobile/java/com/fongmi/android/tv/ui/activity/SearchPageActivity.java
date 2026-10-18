package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.legado.LegadoPipeline;
import com.fongmi.android.tv.legado.LegadoSource;
import com.fongmi.android.tv.read.LegadoSearchFlow;
import com.fongmi.android.tv.read.ReadDialogs;
import com.fongmi.android.tv.read.SearchOrchestrator;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜书页（1.3.50 起搜书整条链的唯一呈现，不许再套弹窗）：
 * 顶上一个搜框（输入框＋搜索钮，回车也触发，进来聚焦弹键盘），作用域跟进来时的页签走；
 * 结果就在本页列表里，一行一本「书名 · 作者（源名）」，并行搜、谁先回来谁先添；
 * 搜索中状态只在列表顶上一行字，不转圈弹窗、不 Toast；
 * 全部回来或 25 秒总时限到点收尾，失败的源在列表末尾点名一行，一条没有就一行「没有搜到」。
 * 点一本进目录页（SearchTocActivity）。返回走系统返回。
 */
public class SearchPageActivity extends AppCompatActivity {

    private static final String EXTRA_TYPE = "type";

    public static void start(Activity activity, int bookSourceType) {
        Intent i = new Intent(activity, SearchPageActivity.class);
        i.putExtra(EXTRA_TYPE, bookSourceType);
        activity.startActivity(i);
    }

    private int type;
    private EditText et;
    private TextView status;
    private LinearLayout resultsBox;

    /** 页已关：所有后台回调一律丢弃，不许往已关的页里塞东西。 */
    private volatile boolean destroyed;
    /** 搜索代数：重开一轮就加一，旧轮的回调一律丢弃。 */
    private volatile int searchGen;

    private SearchOrchestrator orchestrator;
    private int bookCount;
    private int total;
    private int returned;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        type = getIntent().getIntExtra(EXTRA_TYPE, LegadoSource.TYPE_TEXT);
        EdgeToEdge.enable(this);
        buildUi();
        et.requestFocus();
        et.post(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopOrchestrator();
        super.onDestroy();
    }

    private int dp(int v) {
        return ReadingSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ReadingSkin.pageBackground(this));

        // 顶栏：只留标题，不设叉、不放返回箭头，走系统返回
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(4));
        ReadingSkin.applyTopInset(topBar, this, 8);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.leftMargin = dp(4);
        titles.setLayoutParams(tlp);
        TextView title = new TextView(this);
        title.setText("搜书");
        title.setTextSize(20);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(ReadingSkin.TEXT);
        titles.addView(title);
        TextView sub = new TextView(this);
        sub.setText(type == LegadoSource.TYPE_IMAGE ? "搜漫画源"
                : type == LegadoSource.TYPE_AUDIO ? "搜听书源" : "搜小说源");
        sub.setTextSize(12);
        sub.setTextColor(ReadingSkin.SUB);
        sub.setPadding(0, dp(2), 0, 0);
        titles.addView(sub);
        topBar.addView(titles);
        root.addView(topBar);

        // 搜框行：输入框＋搜索钮
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(16);
        rlp.rightMargin = dp(16);
        rlp.topMargin = dp(6);
        searchRow.setLayoutParams(rlp);
        et = ReadDialogs.input(this, "输入书名/作者", false);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        elp.rightMargin = dp(10);
        et.setLayoutParams(elp);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(et);
        TextView go = new TextView(this);
        go.setText("搜索");
        go.setTextSize(15);
        go.getPaint().setFakeBoldText(true);
        go.setTextColor(0xFF0A2115);
        go.setGravity(Gravity.CENTER);
        go.setBackground(ReadingSkin.goldCapsule(this));
        go.setPadding(dp(22), dp(12), dp(22), dp(12));
        go.setOnClickListener(v -> doSearch());
        searchRow.addView(go);
        root.addView(searchRow);

        // 状态行：搜索中/共几本/没有搜到，只在这一行字里说
        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(ReadingSkin.SUB);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(20);
        slp.rightMargin = dp(20);
        slp.topMargin = dp(12);
        slp.bottomMargin = dp(6);
        status.setLayoutParams(slp);
        root.addView(status);

        // 结果列表
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setPadding(dp(16), 0, dp(16), dp(24));
        sv.addView(resultsBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv);

        setContentView(root);
    }

    /** 开搜：上一轮没跑完先停掉，结果区清掉重来。 */
    private void doSearch() {
        String key = et.getText().toString().trim();
        if (key.isEmpty()) return;
        hideKeyboard();
        stopOrchestrator();
        final int gen = ++searchGen;

        List<LegadoSearchFlow.Src> srcs = LegadoSearchFlow.enabledSources(type);
        total = srcs.size();
        returned = 0;
        bookCount = 0;
        resultsBox.removeAllViews();
        if (total == 0) {
            status.setText("还没装这种源");
            return;
        }
        status.setText("搜索中…（0/" + total + "）");

        orchestrator = new SearchOrchestrator(srcs, key, new SearchOrchestrator.Listener() {
            @Override
            public void onSourceOk(int index, LegadoSource source,
                                   List<LegadoPipeline.Book> books) {
                runOnUiThread(() -> {
                    if (destroyed || gen != searchGen) return;
                    returned++;
                    if (books != null) {
                        for (LegadoPipeline.Book b : books) {
                            if (b == null) continue;
                            addBookRow(srcs.get(index), b);
                            bookCount++;
                        }
                    }
                    refreshStatus();
                });
            }

            @Override
            public void onSourceFail(int index, LegadoSource source, boolean timeout) {
                runOnUiThread(() -> {
                    if (destroyed || gen != searchGen) return;
                    returned++;
                    refreshStatus();
                });
            }

            @Override
            public void onSettled() {
                runOnUiThread(() -> {
                    if (destroyed || gen != searchGen) return;
                    List<String> fails = orchestrator == null
                            ? new ArrayList<>() : orchestrator.failNames();
                    if (!fails.isEmpty()) addFailRow(fails);
                    if (bookCount == 0 && fails.isEmpty()) {
                        status.setText("没有搜到，换个词试试。");
                    } else {
                        status.setText("共 " + bookCount + " 本"
                                + (fails.isEmpty() ? "" : "，" + fails.size() + " 个源失败"));
                    }
                });
            }
        });
        orchestrator.start();
    }

    private void refreshStatus() {
        status.setText("搜索中…（" + returned + "/" + total + "）");
    }

    private void stopOrchestrator() {
        if (orchestrator != null) {
            orchestrator.stop();
            orchestrator = null;
        }
    }

    /** 一行一本：书名 · 作者（源名）。 */
    private void addBookRow(LegadoSearchFlow.Src src, LegadoPipeline.Book b) {
        String name = b.name == null ? "" : b.name;
        String author = (b.author == null || b.author.isEmpty()) ? "" : " · " + b.author;
        TextView row = new TextView(this);
        row.setText(name + author + "（" + src.source.tag() + "）");
        row.setTextSize(16);
        row.setTextColor(ReadingSkin.TEXT);
        row.setPadding(dp(4), dp(13), dp(4), dp(13));
        row.setOnClickListener(v -> {
            if (destroyed) return;
            SearchTocActivity.start(this, type, src.json, new Gson().toJson(b));
        });
        resultsBox.addView(row);
        View div = new View(this);
        div.setBackgroundColor(0x14FFFFFF);
        resultsBox.addView(div, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    /** 失败点名行：统一坠在列表末尾。 */
    private void addFailRow(List<String> failNames) {
        String msg = failNames.size() == 1
                ? "「" + failNames.get(0) + "」搜书失败，换个源试试。"
                : failNames.size() + " 个源搜书失败（"
                + android.text.TextUtils.join("、", failNames) + "），换个源试试。";
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(13);
        t.setTextColor(ReadingSkin.SUB);
        t.setPadding(dp(4), dp(14), dp(4), dp(14));
        resultsBox.addView(t);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && et != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
    }
}
