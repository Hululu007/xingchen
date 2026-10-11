package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.databinding.ActivityHkRuleSearchBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 独立规则搜索页：搜已安装小程序（对齐 8.83 ArticleListRuleSearchActivity）。 */
public class HkRuleSearchActivity extends BaseActivity {

    private static final String PREFS = "xingchen";
    private static final String KEY_HIST = "hk_rule_search_history";

    private ActivityHkRuleSearchBinding binding;
    private final List<HkRule> allRules = new ArrayList<>();
    private final List<HkRule> results = new ArrayList<>();
    private ResultAdapter resultAdapter;
    private final List<String> history = new ArrayList<>();
    private HistAdapter histAdapter;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHkRuleSearchBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        loadHistory();
        binding.rvResults.setLayoutManager(new GridLayoutManager(this, 2));
        resultAdapter = new ResultAdapter();
        binding.rvResults.setAdapter(resultAdapter);
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        histAdapter = new HistAdapter();
        binding.rvHistory.setAdapter(histAdapter);
        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnClear.setOnClickListener(v -> binding.etKeyword.setText(""));
        binding.etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                doSearch(s.toString().trim());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String kw = binding.etKeyword.getText().toString().trim();
                if (!kw.isEmpty()) pushHistory(kw);
                hideKeyboard();
                return true;
            }
            return false;
        });
        binding.etKeyword.requestFocus();
        new Thread(() -> {
            List<HkRule> list = HkRuleManager.get().getRules();
            App.post(() -> {
                allRules.clear();
                allRules.addAll(list);
                doSearch(binding.etKeyword.getText().toString().trim());
            });
        }).start();
    }

    /** 8.83：搜小程序名称、链接、规则。 */
    private void doSearch(String kw) {
        results.clear();
        if (!kw.isEmpty()) {
            String k = kw.toLowerCase(Locale.getDefault());
            for (HkRule r : allRules) {
                String t = r.getTitle() == null ? "" : r.getTitle().toLowerCase(Locale.getDefault());
                String u = r.getUrl() == null ? "" : r.getUrl().toLowerCase(Locale.getDefault());
                if (t.contains(k) || u.contains(k)) results.add(r);
            }
        }
        resultAdapter.notifyDataSetChanged();
        boolean empty = !kw.isEmpty() && results.isEmpty();
        binding.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvResults.setVisibility(kw.isEmpty() || !results.isEmpty() ? View.VISIBLE : View.GONE);
        boolean showHist = kw.isEmpty() && !history.isEmpty();
        binding.rvHistory.setVisibility(showHist ? View.VISIBLE : View.GONE);
        binding.tvHistoryLabel.setVisibility(showHist ? View.VISIBLE : View.GONE);
    }

    private void pushHistory(String kw) {
        history.remove(kw);
        history.add(0, kw);
        while (history.size() > 12) history.remove(history.size() - 1);
        getPrefs().edit().putString(KEY_HIST, joinHist()).apply();
        histAdapter.notifyDataSetChanged();
    }

    private String joinHist() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append("\u0001");
            sb.append(history.get(i));
        }
        return sb.toString();
    }

    private void loadHistory() {
        history.clear();
        String s = getPrefs().getString(KEY_HIST, "");
        if (!s.isEmpty()) {
            for (String part : s.split("\u0001")) {
                if (!part.isEmpty() && !history.contains(part)) history.add(part);
            }
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(binding.etKeyword.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            TextView icon, name;
            ImageView ivIcon;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.tv_icon);
                ivIcon = v.findViewById(R.id.iv_icon);
                name = v.findViewById(R.id.tv_name);
                v.findViewById(R.id.sw_enable).setVisibility(View.GONE);
                v.findViewById(R.id.tv_group_tag).setVisibility(View.GONE);
                v.findViewById(R.id.btn_more).setVisibility(View.GONE);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hk_rule, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            HkRule r = results.get(position);
            String title = r.getTitle();
            h.name.setText(TextUtils.isEmpty(title) ? "未命名规则" : title);
            h.ivIcon.setVisibility(View.GONE);
            h.icon.setVisibility(View.VISIBLE);
            h.icon.setText(!TextUtils.isEmpty(title) ? title.substring(0, 1) : "海");
            h.icon.setBackgroundResource(R.drawable.hk_miniapp_icon_bg);
            h.itemView.setOnClickListener(v -> {
                pushHistory(title);
                Intent data = new Intent();
                data.putExtra("title", title);
                setResult(Activity.RESULT_OK, data);
                finish();
            });
        }

        @Override
        public int getItemCount() {
            return results.size();
        }
    }

    private class HistAdapter extends RecyclerView.Adapter<HistAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            TextView tv;

            Holder(View v) {
                super(v);
                tv = (TextView) v;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(13);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            int p = (int) (12 * parent.getContext().getResources().getDisplayMetrics().density);
            tv.setPadding(p, p / 2, p, p / 2);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT);
            int m = (int) (6 * parent.getContext().getResources().getDisplayMetrics().density);
            lp.rightMargin = m;
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String kw = history.get(position);
            h.tv.setText(kw);
            h.tv.setOnClickListener(v -> binding.etKeyword.setText(kw));
        }

        @Override
        public int getItemCount() {
            return history.size();
        }
    }
}
