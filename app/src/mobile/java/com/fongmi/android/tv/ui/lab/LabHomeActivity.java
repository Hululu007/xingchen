package com.fongmi.android.tv.ui.lab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import com.fongmi.android.tv.R;

/**
 * 实验室首页（定稿 lab-design-sets2/a2/home）：
 * 标题行＝「实验室」＋右端「自动启动」小开关；
 * 第二行＝「全部停止」「运行中 N ›」两颗深胶囊；
 * 下面＝两列宫格（深玻璃格：环境名＋圆点＋「X 个服务」），点格进环境页。
 * 右上不放任何东西（无＋无设置入口）；顶栏只留标题，走系统返回。
 */
public class LabHomeActivity extends AppCompatActivity {

    private LinearLayout gridBox;
    private LinearLayout headBox;
    private View masterSw;
    private TextView runningPill;
    private TextView runningNum;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LabHomeActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabSkin.edgeToEdge(this);
        buildUi();

        // 页面进出统一渐入渐出（与 BaseActivity 一致）
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
}

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }


    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private int dp(int v) {
        return LabSkin.dp(this, v);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题行：实验室＋右端 自动启动 开关（字直接落在壁纸上，带衬影）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(9) + LabSkin.statusBarHeight(this), dp(18), dp(14));
        TextView h1 = new TextView(this);
        h1.setText("实验室");
        h1.setTextSize(24);
        h1.setTextColor(LabSkin.WALL_TITLE);
        h1.getPaint().setFakeBoldText(true);
        LabSkin.halo(h1);
        h1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        h1.setOnClickListener(v -> LabSettingActivity.start(this));
        head.addView(h1);
        TextView arLab = new TextView(this);
        arLab.setText("自动启动");
        arLab.setTextSize(12);
        arLab.setTextColor(LabSkin.WALL_DIM);
        LabSkin.halo(arLab);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.rightMargin = dp(8);
        arLab.setLayoutParams(alp);
        head.addView(arLab);
        masterSw = LabSkin.miniSwitch(this, false, on -> LabRegistry.get(this).setMasterAutoStart(this, on));
        head.addView(masterSw);
        headBox = head;
        root.addView(head);

        // 胶囊行：全部停止 / 运行中 N ›
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(18), 0, dp(18), dp(14));
        TextView stopAll = pill("全部停止");
        stopAll.setOnClickListener(v -> {
            LabRunner.stopAll(this);
            reload();
            com.fongmi.android.tv.utils.Notify.show("都停了");
        });
        controls.addView(stopAll);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = dp(9);
        LinearLayout runWrap = new LinearLayout(this);
        runWrap.setOrientation(LinearLayout.HORIZONTAL);
        runWrap.setGravity(Gravity.CENTER_VERTICAL);
        runWrap.setBackground(LabSkin.pillDark(this));
        runWrap.setPadding(dp(14), dp(8), dp(14), dp(8));
        runWrap.setLayoutParams(rlp);
        runningPill = new TextView(this);
        runningPill.setText("运行中");
        runningPill.setTextSize(12);
        runningPill.getPaint().setFakeBoldText(true);
        runningPill.setTextColor(LabSkin.TEXT_ON_DARK);
        runWrap.addView(runningPill);
        runningNum = new TextView(this);
        runningNum.setTextSize(12);
        runningNum.getPaint().setFakeBoldText(true);
        runningNum.setTextColor(LabSkin.COUNT_YELLOW);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(5);
        runningNum.setLayoutParams(nlp);
        runWrap.addView(runningNum);
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(12);
        chev.setTextColor(LabSkin.TEXT_ON_DARK);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(5);
        chev.setLayoutParams(clp);
        runWrap.addView(chev);
        runWrap.setOnClickListener(v -> LabRunningDialog.show(this));
        controls.addView(runWrap);
        root.addView(controls);

        // 宫格
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(0, 0, 0, dp(46) + LabSkin.navBarHeight(this));
        scroll.addView(gridBox, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private TextView pill(String text) {
        TextView p = new TextView(this);
        p.setText(text);
        p.setTextSize(12);
        p.getPaint().setFakeBoldText(true);
        p.setTextColor(LabSkin.TEXT_ON_DARK);
        p.setBackground(LabSkin.pillDark(this));
        p.setPadding(dp(14), dp(8), dp(14), dp(8));
        p.setClickable(true);
        p.setFocusable(true);
        return p;
    }

    private void reload() {
        gridBox.removeAllViews();
        // 开关重建（miniSwitch 是自绘 View，无 setChecked 口）
        headBox.removeView(masterSw);
        masterSw = LabSkin.miniSwitch(this, LabRegistry.get(this).masterAutoStart,
                on -> LabRegistry.get(this).setMasterAutoStart(this, on));
        headBox.addView(masterSw);
        int n = LabRunner.runningCount(this);
        runningNum.setText(String.valueOf(n));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setPadding(dp(18), 0, dp(18), 0);
        for (String env : LabRegistry.ENVS) {
            grid.addView(envTile(env));
        }
        gridBox.addView(grid);
    }

    /** 环境格：深玻璃＋环境名（行右端 已安装/未安装）＋圆点＋「X 个服务」（稿 .tile） */
    private View envTile(String env) {
        List<LabRegistry.Svc> svcs = LabRegistry.get(this).services(env);
        int running = 0;
        for (LabRegistry.Svc s : svcs) {
            if (LabRunner.isRunning(this, s.id)) running++;
        }
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(LabSkin.glassDark(this));
        tile.setPadding(dp(14), dp(17), dp(14), dp(17));
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        int gap = dp(5);
        lp.setMargins(gap, 0, gap, dp(9));
        tile.setLayoutParams(lp);
        tile.setMinimumHeight(dp(114));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setOnClickListener(v -> LabEnvActivity.start(this, env));

        boolean installed = !"tool".equals(env) && LabRuntimeManager.isInstalled(this, env);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(7));
        TextView name = new TextView(this);
        name.setText(LabRegistry.envLabel(env));
        name.setTextSize(15);
        name.setTextColor(LabSkin.TEXT_ON_DARK);
        name.getPaint().setFakeBoldText(true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(name);
        TextView state = new TextView(this);
        state.setText(installed ? "已安装" : "未安装");
        state.setTextSize(11);
        state.setTextColor(installed ? LabSkin.GREEN_ON_DARK : LabSkin.HINT_ON_DARK);
        titleRow.addView(state);
        tile.addView(titleRow);

        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        ImageView dot = new ImageView(this);
        dot.setBackground(LabSkin.dot(this, running > 0 ? LabSkin.GREEN_DOT : 0xFF8A93A3));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(5);
        dot.setLayoutParams(dlp);
        st.addView(dot);
        TextView cnt = new TextView(this);
        cnt.setText(svcs.size() + " 个服务");
        cnt.setTextSize(11);
        cnt.setTextColor(LabSkin.TEXT_ON_DARK_DIM);
        st.addView(cnt);
        tile.addView(st);
        return tile;
    }
}
