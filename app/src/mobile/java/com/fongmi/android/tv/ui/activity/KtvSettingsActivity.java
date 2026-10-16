package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityKtvSettingsBinding;
import com.fongmi.android.tv.ktv.KtvStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * KTV 设置页：点歌服务器、返听音量、歌词字号、默认伴奏、消音强度，外加我的录音（回放/删除）。
 */
public class KtvSettingsActivity extends BaseActivity {

    private static final int BG = 0xFF0B0D12;
    private static final int CARD = 0xFF1D2027;
    private static final int ACCENT = 0xFFF5C518;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SUB = 0xFFC9CDD4;
    private static final int DIM = 0xFF9499A3;

    private ActivityKtvSettingsBinding binding;
    private LinearLayout col;
    private TextView serverValue;
    private TextView gainValue;
    private LinearLayout recBox;
    private MediaPlayer mp;
    private File playing;

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, KtvSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityKtvSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        binding.ktvSettingsRoot.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(24));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("KTV 设置", 24, WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        section("点歌服务器");
        serverValue = text("", 14, SUB);
        serverValue.setPadding(dp(14), dp(12), dp(14), dp(12));
        serverValue.setBackground(round(CARD, 12));
        serverValue.setOnClickListener(v -> editServer());
        col.addView(serverValue, lp(8));
        addChips(new String[]{"歌源：点播源", "歌源：点歌服务器"}, KtvStore.useServer() ? 1 : 0, idx -> {
            KtvStore.setUseServer(idx == 1);
            refreshServer();
        });

        section("麦克风返听音量");
        gainValue = text("", 14, SUB);
        col.addView(gainValue, lp(4));
        SeekBar gain = new SeekBar(this);
        gain.setMax(100);
        gain.setProgress(Math.round((KtvStore.micGain() - 0.2f) / 1.8f * 100));
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float g = 0.2f + progress / 100f * 1.8f;
                KtvStore.setMicGain(g);
                gainValue.setText("现在是 " + Math.round(g * 100) + "%（下次开麦克风生效）");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        col.addView(gain, lp(2));
        gainValue.setText("现在是 " + Math.round(KtvStore.micGain() * 100) + "%（下次开麦克风生效）");

        section("歌词字号");
        addChips(new String[]{"标准", "大", "特大"}, KtvStore.lyricSize(), KtvStore::setLyricSize);

        section("点开一首歌先放哪个");
        addChips(new String[]{"原唱", "伴奏（有伴奏时）"}, KtvStore.defaultAcc() ? 1 : 0, idx -> KtvStore.setDefaultAcc(idx == 1));

        section("消音强度");
        addChips(new String[]{"轻", "标准", "重"}, KtvStore.cancelStrength(), KtvStore::setCancelStrength);

        section("自动配 MV");
        addChips(new String[]{"开", "关"}, KtvStore.pairMv() ? 0 : 1, idx -> KtvStore.setPairMv(idx == 0));

        section("我的录音");
        recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(recBox, lp(6));
        refreshRecordings();
        refreshServer();
    }

    @Override
    protected void onDestroy() {
        stopPlay();
        super.onDestroy();
    }

    private interface ChipPick {
        void pick(int idx);
    }

    private void addChips(String[] labels, int selected, ChipPick pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = text(labels[i], 14, i == selected ? 0xFF131418 : WHITE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(8), dp(9), dp(8), dp(9));
            c.setBackground(round(i == selected ? ACCENT : CARD, 10));
            c.setOnClickListener(v -> {
                pick.pick(idx);
                for (int k = 0; k < chips.size(); k++) {
                    boolean on = k == idx;
                    chips.get(k).setBackground(round(on ? ACCENT : CARD, 10));
                    chips.get(k).setTextColor(on ? 0xFF131418 : WHITE);
                }
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) clp.leftMargin = dp(8);
            row.addView(c, clp);
            chips.add(c);
        }
        col.addView(row, lp(8));
    }

    private void section(String label) {
        TextView t = text(label, 15, WHITE);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams slp = lp(0);
        slp.topMargin = dp(22);
        col.addView(t, slp);
    }

    private void refreshServer() {
        String u = KtvStore.baseUrl();
        serverValue.setText("地址：" + (u.isEmpty() ? "未设置（点这里填）" : u));
    }

    private void editServer() {
        EditText input = new EditText(this);
        input.setHint("http://地址:端口");
        input.setText(KtvStore.baseUrl());
        input.setSingleLine(true);
        new MaterialAlertDialogBuilder(this)
                .setTitle("点歌服务器地址")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    KtvStore.setBaseUrl(input.getText().toString().trim());
                    refreshServer();
                    Notify.show(KtvStore.baseUrl().isEmpty() ? "已清空" : "存好了，点歌台切到点歌服务器就能用");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- 录音回放 ----------

    private File recDir() {
        return new File(getCacheDir(), "ktv");
    }

    private void refreshRecordings() {
        recBox.removeAllViews();
        File[] files = recDir().listFiles((d, name) -> name.endsWith(".wav"));
        if (files == null || files.length == 0) {
            TextView empty = text("还没有录音。在唱歌页开麦克风唱一首，唱完的录音就存到这里。", 13, DIM);
            recBox.addView(empty, lp(2));
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT);
        for (File f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(10), dp(10));
            row.setBackground(round(CARD, 12));
            TextView name = text(f.getName().replace(".wav", "") + "\n" + sdf.format(new Date(f.lastModified())) + " · " + Math.max(1, f.length() / 1024) + "KB", 13, SUB);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView play = text(playing != null && playing.equals(f) ? "■ 停" : "▶ 播", 14, ACCENT);
            play.setPadding(dp(10), dp(6), dp(10), dp(6));
            play.setOnClickListener(v -> togglePlay(f));
            row.addView(play);
            TextView del = text("删除", 13, 0xFFFF7A7A);
            del.setPadding(dp(10), dp(6), dp(4), dp(6));
            del.setOnClickListener(v -> {
                if (playing != null && playing.equals(f)) stopPlay();
                if (f.delete()) Notify.show("删了");
                refreshRecordings();
            });
            row.addView(del);
            recBox.addView(row, lp(8));
        }
    }

    private void togglePlay(File f) {
        if (playing != null && playing.equals(f)) {
            stopPlay();
            refreshRecordings();
            return;
        }
        stopPlay();
        try {
            mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(x -> {
                stopPlay();
                refreshRecordings();
            });
            mp.prepare();
            mp.start();
            playing = f;
        } catch (Throwable t) {
            Notify.show("这段录音放不了");
            stopPlay();
        }
        refreshRecordings();
    }

    private void stopPlay() {
        if (mp != null) {
            try {
                mp.stop();
            } catch (Throwable ignored) {
            }
            mp.release();
            mp = null;
        }
        playing = null;
    }

    // ---------- 小工具 ----------

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
