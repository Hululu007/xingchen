package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.clash.SourceProxyPolicy;
import com.fongmi.android.tv.databinding.ActivitySourcePolicyBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;

/** 按源定向：顶部七切换（PY / JS / 点播 / 海阔 / Emby / 书源 / 网盘），点哪个只看哪个；默认全部直连。 */
public class SourcePolicyActivity extends BaseActivity {

    private ActivitySourcePolicyBinding binding;
    private String currentTab = SourceProxyPolicy.KIND_PY;
    private int countPy;
    private int countJs;
    private int countVod;
    private int countHk;
    private int countEmby;
    private int countBook;
    private int countPan;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SourcePolicyActivity.class));
    }

    // 线路政策页底 #0B0E14 深色，白色图标（BaseActivity 默认已改深色图标，这里盖回）。
    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    protected ViewBinding getBinding() {
        binding = ActivitySourcePolicyBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.tabPy.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_PY;
            applyTab();
        });
        binding.tabJs.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_JS;
            applyTab();
        });
        binding.tabVod.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_VOD;
            applyTab();
        });
        binding.tabHk.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_HK;
            applyTab();
        });
        binding.tabEmby.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_EMBY;
            applyTab();
        });
        binding.tabBook.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_BOOK;
            applyTab();
        });
        binding.tabPan.setOnClickListener(v -> {
            currentTab = SourceProxyPolicy.KIND_PIKPAK;
            applyTab();
        });
        countHk = fillHk();
        countEmby = fillEmby();
        countBook = fillBook();
        countPan = fillPan();
        countPy = fillSites(SourceProxyPolicy.KIND_PY, binding.groupPy, binding.tvTitlePy);
        countJs = fillSites(SourceProxyPolicy.KIND_JS, binding.groupJs, binding.tvTitleJs);
        countVod = fillVod();
        // 默认看 PY；PY 一个都没有时落到第一个有货的组
        if (countPy == 0) {
            if (countJs > 0) currentTab = SourceProxyPolicy.KIND_JS;
            else if (countVod > 0) currentTab = SourceProxyPolicy.KIND_VOD;
            else if (countHk > 0) currentTab = SourceProxyPolicy.KIND_HK;
            else if (countEmby > 0) currentTab = SourceProxyPolicy.KIND_EMBY;
            else if (countBook > 0) currentTab = SourceProxyPolicy.KIND_BOOK;
            else if (countPan > 0) currentTab = SourceProxyPolicy.KIND_PIKPAK;
        }
        applyTab();
    }

    private void applyTab() {
        boolean py = SourceProxyPolicy.KIND_PY.equals(currentTab);
        boolean js = SourceProxyPolicy.KIND_JS.equals(currentTab);
        boolean vod = SourceProxyPolicy.KIND_VOD.equals(currentTab);
        boolean emby = SourceProxyPolicy.KIND_EMBY.equals(currentTab);
        boolean book = SourceProxyPolicy.KIND_BOOK.equals(currentTab);
        boolean pan = SourceProxyPolicy.KIND_PIKPAK.equals(currentTab);
        boolean hk = !py && !js && !vod && !emby && !book && !pan;
        binding.tvTitlePy.setVisibility(py ? View.VISIBLE : View.GONE);
        binding.groupPy.setVisibility(py ? View.VISIBLE : View.GONE);
        binding.tvTitleJs.setVisibility(js ? View.VISIBLE : View.GONE);
        binding.groupJs.setVisibility(js ? View.VISIBLE : View.GONE);
        binding.tvTitleVod.setVisibility(vod ? View.VISIBLE : View.GONE);
        binding.groupVod.setVisibility(vod ? View.VISIBLE : View.GONE);
        binding.tvTitleHk.setVisibility(hk ? View.VISIBLE : View.GONE);
        binding.groupHk.setVisibility(hk ? View.VISIBLE : View.GONE);
        binding.tvTitleEmby.setVisibility(emby ? View.VISIBLE : View.GONE);
        binding.groupEmby.setVisibility(emby ? View.VISIBLE : View.GONE);
        binding.tvTitleBook.setVisibility(book ? View.VISIBLE : View.GONE);
        binding.groupBook.setVisibility(book ? View.VISIBLE : View.GONE);
        binding.tvTitlePan.setVisibility(pan ? View.VISIBLE : View.GONE);
        binding.groupPan.setVisibility(pan ? View.VISIBLE : View.GONE);
        int count = py ? countPy : (js ? countJs : (vod ? countVod : (emby ? countEmby : (book ? countBook : (pan ? countPan : countHk)))));
        binding.tvNone.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        styleTab(binding.tabPy, py);
        styleTab(binding.tabJs, js);
        styleTab(binding.tabVod, vod);
        styleTab(binding.tabHk, hk);
        styleTab(binding.tabEmby, emby);
        styleTab(binding.tabBook, book);
        styleTab(binding.tabPan, pan);
    }

    /** 点播源组：配置站点里 api 不是 .py/.js 的全部（jar 蜘蛛、接口站等），id=站点 key（与打点一致）。 */
    private int fillVod() {
        List<Site> sites;
        try {
            sites = VodConfig.get().getSites();
        } catch (Throwable t) {
            sites = new ArrayList<>();
        }
        int n = 0;
        if (sites != null) {
            for (Site s : sites) {
                String api = s.getApi();
                if (api == null) continue;
                String a = api.toLowerCase();
                if (a.endsWith(".py") || a.endsWith(".js")) continue;
                String name = s.getName();
                if (name == null || name.isEmpty()) name = s.getKey();
                addRow(binding.groupVod, name, SourceProxyPolicy.KIND_VOD, s.getKey());
                n++;
            }
        }
        return n;
    }

    private void styleTab(TextView tab, boolean selected) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(10 * d);
        if (selected) {
            bg.setColor(0xFFF0A400);
            tab.setTextColor(0xFF1B2431);
        } else {
            bg.setColor(0xFF141A26);
            bg.setStroke((int) d, 0xFF2A3242);
            tab.setTextColor(0xFFD7DEE8);
        }
        tab.setBackground(bg);
    }

    private int fillHk() {
        List<HkRule> rules;
        try {
            rules = HkRuleManager.get().getRules();
        } catch (Throwable t) {
            rules = new ArrayList<>();
        }
        if (rules == null || rules.isEmpty()) return 0;
        int n = 0;
        for (HkRule rule : rules) {
            String title = rule.getTitle();
            if (title == null || title.isEmpty()) continue;
            addRow(binding.groupHk, title, SourceProxyPolicy.KIND_HK, title);
            n++;
        }
        return n;
    }

    private int fillSites(String kind, LinearLayout group, TextView title) {
        boolean isPy = SourceProxyPolicy.KIND_PY.equals(kind);
        String suffix = isPy ? ".py" : ".js";
        // 两路来源，id 口径与 SiteApi 打点一致（id=site key）：
        // 一是配置里的站点（api 以 .py/.js 结尾）；二是插件目录里的脚本文件——
        // 这类源不在配置站点表里（是动态站点，key 固定 py_/js_+文件名，见 VodFragment/CollectFragment），
        // 只读配置表会整组漏掉（2026-10-15 他真机实证：PY/JS 两组都没上）。
        java.util.LinkedHashMap<String, String> entries = new java.util.LinkedHashMap<>();
        List<Site> sites;
        try {
            sites = VodConfig.get().getSites();
        } catch (Throwable t) {
            sites = new ArrayList<>();
        }
        if (sites != null) {
            for (Site s : sites) {
                String api = s.getApi();
                if (api == null || !api.toLowerCase().endsWith(suffix)) continue;
                String name = s.getName();
                if (name == null || name.isEmpty()) name = s.getKey();
                entries.put(s.getKey(), name);
            }
        }
        try {
            java.io.File dir = new java.io.File(getFilesDir(), isPy ? "plugins/py" : "plugins/js");
            java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(suffix));
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (java.io.File f : files) {
                    String base = f.getName().substring(0, f.getName().length() - suffix.length());
                    String id = (isPy ? "py_" : "js_") + base;
                    if (!entries.containsKey(id)) entries.put(id, base);
                }
            }
        } catch (Throwable ignored) {
        }
        for (java.util.Map.Entry<String, String> e : entries.entrySet()) {
            addRow(group, e.getValue(), kind, e.getKey());
        }
        return entries.size();
    }

    /** Emby 分区：列 EmbyStore.servers()，开关读写一律走 EmbyStore（禁直写框架表）。 */
    private int fillEmby() {
        List<com.fongmi.android.tv.emby.EmbyStore.EmbyServer> servers;
        try {
            servers = com.fongmi.android.tv.emby.EmbyStore.servers();
        } catch (Throwable t) {
            servers = new ArrayList<>();
        }
        int n = 0;
        if (servers != null) {
            for (com.fongmi.android.tv.emby.EmbyStore.EmbyServer s : servers) {
                if (s == null || s.id == null) continue;
                addEmbyRow(binding.groupEmby, s.displayName(), s.id);
                n++;
            }
        }
        return n;
    }

    /**
     * 书源分区：列书源仓库里启用的书源（id=bookSourceUrl），开关走框架表 book: 前缀。
     * 阅读取数（正文/图片/音频/章节缓存/更新检查）按此归属走节点。
     */
    private int fillBook() {
        List<com.fongmi.android.tv.legado.LegadoStore.Entry> entries;
        try {
            entries = com.fongmi.android.tv.legado.LegadoStore.all();
        } catch (Throwable t) {
            entries = new ArrayList<>();
        }
        int n = 0;
        if (entries != null) {
            for (com.fongmi.android.tv.legado.LegadoStore.Entry e : entries) {
                if (e == null || !e.enabled) continue;
                String key = null;
                String name = null;
                try {
                    com.fongmi.android.tv.legado.LegadoSource src =
                            com.fongmi.android.tv.legado.LegadoStore.get(sourceKeyOf(e));
                    if (src != null) {
                        key = src.bookSourceUrl;
                        name = src.bookSourceName;
                    }
                } catch (Throwable ignored) {
                }
                if (key == null || key.isEmpty()) continue;
                if (name == null || name.isEmpty()) name = key;
                addRow(binding.groupBook, name, SourceProxyPolicy.KIND_BOOK, key);
                n++;
            }
        }
        return n;
    }

    /**
     * 网盘分区：目前只有 PikPak 一家接了节点门控（单一账号、单一条目），
     * id 固定 DrivePikPak.ID，开关走框架表 pikpak: 前缀；开了即生效、
     * 不要求节点总开关先开（SourceProxyPolicy 的 pikpak 口径）。
     */
    private int fillPan() {
        addRow(binding.groupPan, "PikPak", SourceProxyPolicy.KIND_PIKPAK,
                com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak.ID);
        return 1;
    }

    /** 书源条目的 key：先解析 json 取 bookSourceUrl（免得逐条走 get 的解析缓存）。 */
    private static String sourceKeyOf(com.fongmi.android.tv.legado.LegadoStore.Entry e) {
        try {
            com.fongmi.android.tv.legado.LegadoSource src =
                    com.fongmi.android.tv.legado.LegadoStore.parse(e.json);
            return src == null ? "" : src.bookSourceUrl;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Emby 行：开关读 EmbyStore.isProxyEnabled，写走 EmbyStore.setProxyEnabled（唯一入口）。 */
    private void addEmbyRow(LinearLayout group, String label, String serverId) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (56 * d));
        row.setBackgroundResource(R.drawable.xc_cyber_card);
        int pad = (int) (12 * d);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tlp);

        Switch sw = new Switch(this);
        sw.setThumbResource(R.drawable.xc_switch_thumb);
        try {
            sw.setChecked(com.fongmi.android.tv.emby.EmbyStore.isProxyEnabled(serverId));
        } catch (Throwable ignored) {
            sw.setChecked(false);
        }
        sw.setOnCheckedChangeListener((btn, checked) -> {
            try {
                com.fongmi.android.tv.emby.EmbyStore.setProxyEnabled(this, serverId, checked);
            } catch (Throwable ignored) {
            }
        });

        row.addView(tv);
        row.addView(sw);
        group.addView(row);
    }

    private void addRow(LinearLayout group, String label, String kind, String id) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight((int) (56 * d));
        row.setBackgroundResource(R.drawable.xc_cyber_card);
        int pad = (int) (12 * d);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * d);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(tlp);

        Switch sw = new Switch(this);
        sw.setThumbResource(R.drawable.xc_switch_thumb);
        sw.setChecked(SourceProxyPolicy.isEnabled(this, kind, id));
        sw.setOnCheckedChangeListener((btn, checked) -> SourceProxyPolicy.setEnabled(this, kind, id, checked));

        row.addView(tv);
        row.addView(sw);
        group.addView(row);
    }
}
