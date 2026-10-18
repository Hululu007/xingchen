package com.fongmi.android.tv.ui.hk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.api.hk.HkSearchEngine;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.fongmi.android.tv.ui.hk.view.HkCenterListPopup;

/**
 * 照 8.83 SearchActivity 的聚合搜索宿主：顶部搜索栏（无搜索按钮，回车/IME 触发 C1；
 * 空词 Toast「搜索关键词不能为空」+收键盘 C2）、分组下拉、搜索模式切换（偏好键 smode C6）、
 * ViewPager 每发动机一个 HkSearchFragment、底部状态条订阅 HkSearchStatusEvent、
 * 失败发动机汇总弹窗；返回直接 finish（C9）。
 * 单规则模式（oneRule）对应原版 SearchInOneRuleActivity：只有一个发动机且宿主报 isOneRuleHost，
 * 使 Fragment 的单结果自动打开逻辑生效。布局 activity_hk_search。
 */
public class HkSearchActivity extends AppCompatActivity implements HkSearchFragment.SearchHost {

    private EditText searchEdit;
    private View searchClear;
    private TextView groupView;
    private TextView modeView;
    private ViewPager2 pager;
    private RecyclerView bottomBar;
    private RecyclerView suggestList;

    private final List<HkSearchEngine> allEngines = new ArrayList<>();
    private final List<HkSearchEngine> engines = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final List<Integer> engineStatus = new ArrayList<>();
    private final List<HkSearchEngine> errorEngines = new ArrayList<>();
    private String currentGroup = "";
    private String keyword = "";
    private boolean oneRule;
    private List<String> restrictTitles;
    private SuggestAdapter suggestAdapter;
    private BottomAdapter bottomAdapter;
    private SearchPagerAdapter pagerAdapter;

    // ---------- 静态入口 ----------

    public static void startGlobalSearch(Context context, String keyword) {
        Intent intent = new Intent(context, HkSearchActivity.class);
        if (keyword != null) intent.putExtra("wd", keyword);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void startSearchInOneRule(Context context, HkRule rule, String keyword) {
        Intent intent = new Intent(context, HkSearchActivity.class);
        intent.putExtra("oneRule", true);
        if (rule != null) {
            ArrayList<String> titles = new ArrayList<>();
            titles.add(rule.getTitle());
            intent.putStringArrayListExtra("engineTitles", titles);
        }
        if (keyword != null) intent.putExtra("wd", keyword);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void startWithEngines(Context context, List<String> engineTitles, String keyword, boolean oneRuleMode) {
        Intent intent = new Intent(context, HkSearchActivity.class);
        if (engineTitles != null) intent.putStringArrayListExtra("engineTitles", new ArrayList<>(engineTitles));
        if (keyword != null) intent.putExtra("wd", keyword);
        intent.putExtra("oneRule", oneRuleMode);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** RouteBlocker 钩子统一装配入口（搜索 + 目标页全量，幂等）：逻辑在 HkRouteBlocker.wireAllHooks。 */
    public static void wireRouteHooks() {
        HkRouteBlocker.wireAllHooks();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HkListActivity.ensureStatics();
        wireRouteHooks();
        setContentView(R.layout.activity_hk_search);
        // 液态玻璃动态背景（LinearLayout 根走 decor 注入，视觉等同 XML 首孩子）
        HkDynamicBgView.attachTo(this);
        // 海阔浅色页状态栏：透明＋深色图标，动态背景（decor 注入）画到顶，融成一片
        EdgeToEdge.enable(this,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT));

        Intent intent = getIntent();
        oneRule = intent != null && intent.getBooleanExtra("oneRule", false);
        restrictTitles = intent == null ? null : intent.getStringArrayListExtra("engineTitles");
        String wd = intent == null ? null : intent.getStringExtra("wd");
        String groupExtra = intent == null ? null : intent.getStringExtra("group");
        if (groupExtra != null) currentGroup = groupExtra;

        buildEngines();
        initViews();
        if (wd != null && !wd.isEmpty()) {
            searchEdit.setText(wd);
            searchEdit.setSelection(wd.length());
            submitSearch(wd);
        }
        // 精修 P4：与主 App 同一套渐入渐出（不改继承，只加两行）
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.xc_fade_in, R.anim.xc_fade_out);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    @Override
    protected void onStop() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        super.onStop();
    }

    /** 照原版 getEngines：规则库中带搜索的规则 → 发动机；分组取规则 group 去重。 */
    private void buildEngines() {
        allEngines.clear();
        LinkedHashSet<String> groupSet = new LinkedHashSet<>();
        List<HkRule> rules = HkRuleManager.get().getRules();
        if (rules != null) {
            for (HkRule rule : rules) {
                if (rule == null || !rule.hasSearch()) continue;
                HkSearchEngine engine = HkSearchEngine.fromRule(rule);
                if (restrictTitles != null && !restrictTitles.isEmpty()
                        && !restrictTitles.contains(engine.getTitle())) {
                    continue;
                }
                allEngines.add(engine);
                String g = engine.getGroup() == null ? "" : engine.getGroup();
                if (!g.isEmpty()) groupSet.add(g);
            }
        }
        groups.clear();
        groups.add("");
        groups.addAll(groupSet);
        if (!groups.contains(currentGroup)) currentGroup = "";
        applyGroupFilter();
    }

    private void applyGroupFilter() {
        engines.clear();
        for (HkSearchEngine engine : allEngines) {
            String g = engine.getGroup() == null ? "" : engine.getGroup();
            if (currentGroup.isEmpty() || currentGroup.equals(g)) engines.add(engine);
        }
        engineStatus.clear();
        for (int i = 0; i < engines.size(); i++) engineStatus.add(0);
        errorEngines.clear();
    }

    private void initViews() {
        searchEdit = findViewById(R.id.search_edit);
        searchClear = findViewById(R.id.search_clear);
        groupView = findViewById(R.id.hk_search_group);
        modeView = findViewById(R.id.hk_search_mode);
        pager = findViewById(R.id.hk_search_pager);
        bottomBar = findViewById(R.id.hk_search_bottom);
        suggestList = findViewById(R.id.hk_search_suggest);

        findViewById(R.id.hk_search_back).setOnClickListener(v -> finish());

        searchClear.setOnClickListener(v -> {
            searchEdit.setText("");
            hideSuggest();
        });

        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (s.length() > 0) showSuggest(s.toString());
                else hideSuggest();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        // C1：无搜索按钮，回车/IME 搜索动作触发。
        searchEdit.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enterKey) {
                String text = searchEdit.getText() == null ? "" : searchEdit.getText().toString();
                submitSearch(text);
                return true;
            }
            return false;
        });

        updateGroupView();
        groupView.setOnClickListener(v -> showGroupMenu());
        updateModeView();
        modeView.setOnClickListener(v -> toggleMode());

        pagerAdapter = new SearchPagerAdapter();
        pager.setAdapter(pagerAdapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (bottomAdapter != null) {
                    bottomAdapter.setSelected(position);
                    bottomBar.scrollToPosition(position);
                }
            }
        });

        bottomBar.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        bottomAdapter = new BottomAdapter();
        bottomBar.setAdapter(bottomAdapter);

        suggestList.setLayoutManager(new LinearLayoutManager(this));
        suggestAdapter = new SuggestAdapter();
        suggestList.setAdapter(suggestAdapter);
    }

    private void updateGroupView() {
        groupView.setText(currentGroup.isEmpty() ? "全部分组" : currentGroup);
        groupView.setVisibility(groups.size() > 2 ? View.VISIBLE : View.GONE);
    }

    private void showGroupMenu() {
        List<String> labels = new ArrayList<>();
        for (String g : groups) labels.add(g.isEmpty() ? "全部" : g);
        new HkCenterListPopup(this)
                .withTitle("选择分组")
                .with(labels, 3, new HkCenterListPopup.ClickListener() {
                    @Override
                    public void click(String text, int pos) {
                        if (pos < 0 || pos >= groups.size()) return;
                        currentGroup = groups.get(pos);
                        updateGroupView();
                        applyGroupFilter();
                        refreshPager();
                        if (!keyword.isEmpty()) submitSearch(keyword);
                    }

                    @Override
                    public void onLongClick(String text, int pos) {
                    }
                })
                .show();
    }

    private int getSmode() {
        return PreferenceManager.getDefaultSharedPreferences(this).getInt("smode", 0);
    }

    private void updateModeView() {
        modeView.setText(getSmode() == 1 ? "精准" : "默认");
    }

    /** C6：模式切换写偏好键 smode（0 默认 / 1 精准），已有关键词时重新搜索使过滤立即生效。 */
    private void toggleMode() {
        int next = getSmode() == 1 ? 0 : 1;
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("smode", next).apply();
        updateModeView();
        HkRouteBlocker.toastShortBottom(this, next == 1
                ? "已切换为精准搜索模式" : "已切换为默认搜索模式");
        if (!keyword.isEmpty()) submitSearch(keyword);
    }

    /** C2：空词 Toast「搜索关键词不能为空」并收起键盘，不发起搜索。 */
    private void submitSearch(String text) {
        String kw = text == null ? "" : text.trim();
        if (kw.isEmpty()) {
            HkRouteBlocker.toastShortBottom(this, "搜索关键词不能为空");
            hideKeyboard();
            return;
        }
        keyword = kw;
        hideSuggest();
        hideKeyboard();
        for (int i = 0; i < engineStatus.size(); i++) engineStatus.set(i, 0);
        errorEngines.clear();
        if (bottomAdapter != null) bottomAdapter.notifyDataSetChanged();
        refreshPager();
    }

    private void refreshPager() {
        pagerAdapter = new SearchPagerAdapter();
        pager.setAdapter(pagerAdapter);
        if (bottomAdapter != null) {
            bottomAdapter.setSelected(0);
            bottomAdapter.notifyDataSetChanged();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchEdit != null) {
            imm.hideSoftInputFromWindow(searchEdit.getWindowToken(), 0);
        }
    }

    // ---------- 建议下拉（远程一路，与文章页共用 HkSuggestModel） ----------

    private void showSuggest(String query) {
        HkSuggestModel.loadFromRemote(query, list -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            String current = searchEdit.getText() == null ? "" : searchEdit.getText().toString();
            if (!query.equals(current)) return;
            suggestAdapter.setData(list);
            suggestList.setVisibility(list == null || list.isEmpty() ? View.GONE : View.VISIBLE);
        }));
    }

    private void hideSuggest() {
        if (suggestList != null) suggestList.setVisibility(View.GONE);
    }

    // ---------- 状态事件（底部状态条） ----------

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStatusEvent(HkSearchStatusEvent event) {        if (event == null || event.engine == null) return;
        int pos = -1;
        for (int i = 0; i < engines.size(); i++) {
            HkSearchEngine e = engines.get(i);
            if (e == event.engine || (e.getTitle() != null && e.getTitle().equals(event.engine.getTitle())
                    && String.valueOf(e.getGroup()).equals(String.valueOf(event.engine.getGroup())))) {
                pos = i;
                break;
            }
        }
        if (pos < 0) return;
        int status = event.engine.getStatus();
        engineStatus.set(pos, status);
        if (status == 3) {
            if (!errorEngines.contains(event.engine)) errorEngines.add(event.engine);
        } else if (status == 2 || status == 4) {
            errorEngines.remove(event.engine);
        }
        if (bottomAdapter != null) bottomAdapter.notifyItemChanged(pos);
    }

    /** backToHome：8.83 BackMainEvent 清栈。搜索页不是首页，收到直接退出。 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onBackMainEvent(com.fongmi.android.tv.api.hk.event.video.BackMainEvent event) {
        finish();
    }

    /** 点失败汇总时弹错误发动机列表，选中切到对应页（原版 errorPopup 语义）。 */
    private void showErrorEngines() {
        if (errorEngines.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        for (HkSearchEngine e : errorEngines) labels.add(e.getDisplayTitle());
        new HkCenterListPopup(this)
                .withTitle("加载失败的小程序")
                .with(labels, 2, new HkCenterListPopup.ClickListener() {
                    @Override
                    public void click(String text, int pos) {
                        if (pos < 0 || pos >= errorEngines.size()) return;
                        int idx = engines.indexOf(errorEngines.get(pos));
                        if (idx >= 0) pager.setCurrentItem(idx, true);
                    }

                    @Override
                    public void onLongClick(String text, int pos) {
                    }
                })
                .show();
    }

    /** 当前页发动机标题（播放交接取 siteKey 用）。 */
    public String currentEngineTitle() {
        if (pager == null || engines.isEmpty()) return "";
        int pos = pager.getCurrentItem();
        if (pos < 0 || pos >= engines.size()) return "";
        String t = engines.get(pos).getTitle();
        return t == null ? "" : t;
    }

    // ---------- HkSearchFragment.SearchHost ----------

    @Override
    public void openSearchRulePage(HkRule rule, String title) {
        HkListActivity.startRule(this, rule, title);
    }

    @Override
    public boolean isOneRuleHost() {
        return oneRule;
    }

    // ---------- 适配器 ----------

    private class SearchPagerAdapter extends FragmentStateAdapter {

        SearchPagerAdapter() {
            super(HkSearchActivity.this);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            HkSearchFragment fragment = new HkSearchFragment()
                    .newInstance(keyword, engines.get(position), 0, currentGroup);
            fragment.setSearchHost(HkSearchActivity.this);
            return fragment;
        }

        @Override
        public int getItemCount() {
            return engines.size();
        }

        @Override
        public long getItemId(int position) {
            HkSearchEngine e = engines.get(position);
            return (String.valueOf(e.getTitle()) + "|" + keyword + "|" + currentGroup).hashCode();
        }

        @Override
        public boolean containsItem(long itemId) {
            for (int i = 0; i < engines.size(); i++) {
                if (getItemId(i) == itemId) return true;
            }
            return false;
        }
    }

    private class BottomAdapter extends RecyclerView.Adapter<BottomAdapter.Holder> {

        private int selected;

        void setSelected(int pos) {
            int old = selected;
            selected = pos;
            if (old != pos) {
                notifyItemChanged(old);
                notifyItemChanged(pos);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(22, 0, 22, 0);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextSize(13);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HkSearchEngine engine = engines.get(position);
            int status = position < engineStatus.size() ? engineStatus.get(position) : 0;
            String suffix;
            switch (status) {
                case 1:
                    suffix = " · 搜索中";
                    break;
                case 2:
                    suffix = " · 已完成";
                    break;
                case 3:
                    suffix = " · 失败";
                    break;
                case 4:
                    suffix = " · 无结果";
                    break;
                default:
                    suffix = "";
                    break;
            }
            holder.tv.setText(engine.getDisplayTitle() + suffix);
            if (status == 3) holder.tv.setTextColor(0xFFED424B);
            else if (status == 2) holder.tv.setTextColor(0xFF8A6D00);
            else holder.tv.setTextColor(0xFF666666);
            holder.tv.setTypeface(null, position == selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            holder.tv.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                if (engineStatus.get(pos) == 3 && !errorEngines.isEmpty()
                        && errorEngines.size() > 1 && pos == engines.indexOf(errorEngines.get(0))) {
                    // 首个失败项聚合入口：多失败时先弹汇总，单失败直接切页
                    showErrorEngines();
                    return;
                }
                pager.setCurrentItem(pos, true);
            });
        }

        @Override
        public int getItemCount() {
            return engines.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tv;

            Holder(TextView itemView) {
                super(itemView);
                tv = itemView;
            }
        }
    }

    private class SuggestAdapter extends RecyclerView.Adapter<SuggestAdapter.Holder> {

        private final List<String> data = new ArrayList<>();

        void setData(List<String> list) {
            data.clear();
            if (list != null) data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            tv.setTextColor(0xFF222222);
            tv.setTextSize(15);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.tv.setText(data.get(position));
            holder.tv.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                searchEdit.setText(data.get(pos));
                searchEdit.setSelection(data.get(pos).length());
                submitSearch(data.get(pos));
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tv;

            Holder(TextView itemView) {
                super(itemView);
                tv = itemView;
            }
        }
    }
}
