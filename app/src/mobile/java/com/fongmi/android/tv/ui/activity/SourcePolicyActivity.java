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

/** 按源定向：三组开关（海阔规则 / PY 源 / JS 源），默认全部直连。枚举不到的组整组不显示。 */
public class SourcePolicyActivity extends BaseActivity {

    private ActivitySourcePolicyBinding binding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SourcePolicyActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivitySourcePolicyBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        int shown = 0;
        shown += fillHk();
        shown += fillSites(SourceProxyPolicy.KIND_PY, binding.groupPy, binding.tvTitlePy);
        shown += fillSites(SourceProxyPolicy.KIND_JS, binding.groupJs, binding.tvTitleJs);
        binding.tvNone.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private int fillHk() {
        List<HkRule> rules;
        try {
            rules = HkRuleManager.get().getRules();
        } catch (Throwable t) {
            rules = new ArrayList<>();
        }
        if (rules == null || rules.isEmpty()) {
            binding.tvTitleHk.setVisibility(View.GONE);
            binding.groupHk.setVisibility(View.GONE);
            return 0;
        }
        for (HkRule rule : rules) {
            String title = rule.getTitle();
            if (title == null || title.isEmpty()) continue;
            addRow(binding.groupHk, title, SourceProxyPolicy.KIND_HK, title);
        }
        return 1;
    }

    private int fillSites(String kind, LinearLayout group, TextView title) {
        List<Site> sites;
        try {
            sites = VodConfig.get().getSites();
        } catch (Throwable t) {
            sites = new ArrayList<>();
        }
        String suffix = SourceProxyPolicy.KIND_PY.equals(kind) ? ".py" : ".js";
        List<Site> match = new ArrayList<>();
        if (sites != null) {
            for (Site s : sites) {
                String api = s.getApi();
                if (api != null && api.toLowerCase().endsWith(suffix)) match.add(s);
            }
        }
        if (match.isEmpty()) {
            title.setVisibility(View.GONE);
            group.setVisibility(View.GONE);
            return 0;
        }
        for (Site s : match) {
            String name = s.getName();
            if (name == null || name.isEmpty()) name = s.getKey();
            addRow(group, name, kind, s.getKey());
        }
        return 1;
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
