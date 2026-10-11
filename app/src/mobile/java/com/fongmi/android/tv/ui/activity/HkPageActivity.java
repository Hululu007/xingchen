package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.hk.HkDetail;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkJsRuntime;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.databinding.ActivityHkBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.xingchen.tv.theme.FrostedGlass;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 海阔小程序单页（M3）：V1 规则列表 / V2 分类+列表 / V3 搜索结果 / V4 详情，三视图栈式切换。
 * 点条目 → V4 海阔详情（标题/封面/简介/线路/选集）；点选集 → VideoActivity 直接播放。
 */
public class HkPageActivity extends BaseActivity {

    private static final int V_RULES = 0;
    private static final int V_CONTENT = 1;
    private static final int V_SEARCH = 2;
    private static final int V_DETAIL = 3;
    private static final int REQ_IMPORT_FILE = 0x101;
    private static final String PREFS = "xingchen";
    private static final String KEY_HIST = "hk_search_history";
    private static final String KEY_LAST_CLIP = "hk_last_clip_prompt";
    private static final String KEY_RULE_SPAN = "hk_rule_span_count";
    /** 自定义分组：有序分组名列表 / 规则标题→自定义分组（""=显式无分组）/ 自定义顺序（规则标题有序列表） */
    private static final String KEY_CUSTOM_GROUPS = "hk_custom_groups";
    private static final String KEY_RULE_GROUPS = "hk_rule_group_map";
    private static final String KEY_RULE_ORDER = "hk_rule_order";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HkPageActivity.class));
    }

    private ActivityHkBinding binding;
    private final Deque<Integer> stack = new ArrayDeque<>();

    private RuleAdapter ruleAdapter;
    private GridLayoutManager ruleGrid;
    private final List<HkRule> rules = new ArrayList<>();
    /** 小程序列表：名称排序开关 / 搜索关键字 / 管理模式（编辑按钮切换）/ 分组筛选（""=全部） */
    private boolean ruleSortByName = false;
    private String ruleKeyword = "";
    private boolean ruleManageMode = false;
    private String ruleGroup = "";
    /** 自定义分组（本地 prefs，不改 rule.json）：有序分组名 / 标题→分组覆盖 / 自定义顺序（null=未自定义） */
    private final List<String> customGroups = new ArrayList<>();
    private final Map<String, String> ruleGroupMap = new HashMap<>();
    private List<String> customOrder = null;
    private ItemTouchHelper ruleTouchHelper;
    // 编辑模式拖拽到分组 pills：拖拽状态（排序快照用于投放分组时回滚换位）
    private RecyclerView.ViewHolder ruleDragHolder;
    private int ruleDragFromPos = RecyclerView.NO_POSITION;
    private String ruleDragTitle;
    private List<String> ruleDragOrderSnapshot;
    private ChipAdapter groupChipAdapter;
    private List<String[]> groupPairs;
    private int groupDropTargetPos = -1;

    private ContentAdapter contentAdapter;
    private final List<HkItem> videos = new ArrayList<>();
    private GridLayoutManager contentGrid;
    private HkRule currentRule;
    private final List<HkRule> miniappRules = new ArrayList<>();
    private HkRouter router;
    private String cls = "", area = "", year = "", sort = "";
    private int page = 1;
    private boolean loading, noMore;
    private final Map<String, Integer> scrollMem = new HashMap<>();

    private VideoAdapter searchAdapter;
    private final List<HkItem> searchResults = new ArrayList<>();
    private String keyword = "";
    private int searchPage = 1;
    private boolean searchLoading, searchNoMore;
    /** 搜索页：true = 小程序列表入口的整页内容搜索；false = 内容页内单规则搜索 */
    private boolean searchDualMode = false;

    private HkItem detailItem;
    private boolean detailFromSearch;
    private android.widget.Toast loadingHint;
    private HkDetail currentDetail;
    private HkDetail.Line currentLine;
    private EpisodeAdapter episodeAdapter;
    private final List<HkDetail.Episode> episodes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 海阔页浅色主题：状态栏/导航栏用深色图标（BaseActivity 统一强制了白色图标，会盖掉主题的 windowLightStatusBar）
        EdgeToEdge.enable(this,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT));
    }

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHkBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        initRulesView();
        initContentView();
        initSearchView();
        initDetailView();
        stack.push(V_RULES);
        showView(V_RULES);
        refreshRules();
    }

    @Override
    protected void onBackInvoked() {
        // inline 搜索条展开时按返回：先收起搜索条，不退出页面
        if (stack.size() == 1 && stack.peek() == V_RULES
                && binding.inlineSearchBar.getVisibility() == View.VISIBLE) {
            closeInlineSearch();
            return;
        }
        // 编辑模式中按返回：先退出编辑模式（已即时保存），不退出页面
        if (stack.size() == 1 && stack.peek() == V_RULES && ruleManageMode) {
            toggleRuleManageMode();
            return;
        }
        if (stack.size() > 1) {
            // 子列表返回：先恢复父列表，不弹视图栈（官方 FilmListActivity 返回即回父列表）
            if (stack.peek() == V_CONTENT && !subStack.isEmpty()) {
                backSubList();
                return;
            }
            if (stack.peek() == V_CONTENT && currentRule != null && contentGrid != null) {
                scrollMem.put(currentRule.getTitle(), contentGrid.findFirstVisibleItemPosition());
            }
            stack.pop();
            showView(stack.peek());
        } else {
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        destroyRouterAsync();
        super.onDestroy();
    }

    /**
     * 异步销毁旧路由：HkJsRuntime.destroy() 会 submit(...).get() 等 JS 单线程池清空，
     * 前一个规则的 JS 任务若还在跑（慢网络/动态域名），同步调会冻住主线程几秒。
     * 切到后台线程销毁，点击进 V2 不再卡顿。
     */
    private void destroyRouterAsync() {
        HkRouter old = router;
        router = null;
        if (old == null) return;
        new Thread(() -> {
            try {
                old.destroy();
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 在窗口获得焦点后延迟检查剪贴板：Android 10+ 限制后台读取剪贴板，
        // onResume 时窗口焦点可能尚未就绪，onWindowFocusChanged 是可靠时机。
        if (hasFocus && binding != null && binding.getRoot() != null) {
            binding.getRoot().postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) checkClipboardForCloudCode();
            }, 200);
        }
    }

    /**
     * 进入小程序时自动识别剪贴板里的云口令（官方 App 同款行为）：
     * 复制口令后打开本页，自动弹出导入确认。同一口令只提示一次。
     */
    private void checkClipboardForCloudCode() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (TextUtils.isEmpty(text)) return;
            String code = extractCloudCode(text.toString());
            if (code == null) return;
            // 同一口令只提示一次
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (code.equals(sp.getString(KEY_LAST_CLIP, ""))) return;
            sp.edit().putString(KEY_LAST_CLIP, code).apply();
            new AlertDialog.Builder(this)
                    .setTitle("检测到云口令")
                    .setMessage("剪贴板中有海阔云口令，是否立即导入？\n\n" + code)
                    .setPositiveButton("导入", (d, w) -> importByCloudCode(code))
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 从文本中提取云口令（云1~云10 开头，取连续的一段）。 */
    private static String extractCloudCode(String text) {
        if (TextUtils.isEmpty(text)) return null;
        // 按空白切分，找第一段云口令
        for (String part : text.trim().split("\\s+")) {
            if (HkRuleManager.isCloudCode(part)) return part;
        }
        return null;
    }

    private void showView(int v) {
        binding.viewRules.setVisibility(v == V_RULES ? View.VISIBLE : View.GONE);
        binding.viewContent.setVisibility(v == V_CONTENT ? View.VISIBLE : View.GONE);
        binding.viewSearch.setVisibility(v == V_SEARCH ? View.VISIBLE : View.GONE);
        binding.viewDetail.setVisibility(v == V_DETAIL ? View.VISIBLE : View.GONE);
    }

    private void pushView(int v) {
        stack.push(v);
        showView(v);
    }

    // ================= V1 规则列表 =================

    private void initRulesView() {
        int span = getPrefs().getInt(KEY_RULE_SPAN, 3);
        ruleGrid = new GridLayoutManager(this, span);
        binding.rvRules.setLayoutManager(ruleGrid);
        binding.rvRules.addItemDecoration(new GridSpace());
        ruleAdapter = new RuleAdapter();
        binding.rvRules.setAdapter(ruleAdapter);
        binding.tvTitleRule.setOnClickListener(v -> showColumnSwitchPopup());
        // 点 🔍：首页直接 inline 过滤小程序卡片；长按 🔍：整页内容搜索（搜全部小程序的内容）
        binding.btnSearchRule.setOnClickListener(v -> toggleInlineSearch());
        binding.btnSearchRule.setOnLongClickListener(v -> {
            openContentSearch();
            return true;
        });
        binding.btnSortRule.setOnClickListener(v -> {
            ruleSortByName = !ruleSortByName;
            ruleAdapter.rebuildSections();
            ruleAdapter.notifyDataSetChanged();
        });
        binding.btnEditRule.setOnClickListener(v -> toggleRuleManageMode());
        binding.btnGroupManage.setOnClickListener(v -> showGroupManageDialog());
        binding.btnImport.setOnClickListener(v -> showImportDialog());
        binding.rvRuleGroup.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        // 悬浮磨砂顶栏：真 blur 只作用于独立背景层，内容层不被模糊
        FrostedGlass.blurBackground(binding.headerBg);
        // 首页 inline 搜索条：磨砂风格，输入实时过滤小程序卡片
        FrostedGlass.blurBackground(binding.inlineSearchBar);
        binding.etInlineSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ruleKeyword = s.toString().trim();
                ruleAdapter.rebuildSections();
                ruleAdapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.etInlineSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
        binding.btnInlineClose.setOnClickListener(v -> closeInlineSearch());
        updateRulesTopPadding();
        loadCustomRuleData();
        // 编辑模式拖拽排序：长按卡片拖动（仅编辑模式启用）
        // 拖到顶部悬浮栏的分组 pills 上松手 = 把卡片分配进该分组（换位回滚，仅做分组）
        ruleTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                if (!ruleManageMode) return 0;
                int drag = ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
                return makeMovementFlags(drag, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false;
                ruleAdapter.moveItem(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return ruleManageMode;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (!ruleManageMode) return;
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                    ruleDragHolder = vh;
                    ruleDragFromPos = vh.getBindingAdapterPosition();
                    ruleDragTitle = null;
                    if (ruleDragFromPos != RecyclerView.NO_POSITION
                            && ruleDragFromPos < ruleAdapter.items.size()) {
                        Object o = ruleAdapter.items.get(ruleDragFromPos);
                        if (o instanceof HkRule) ruleDragTitle = ((HkRule) o).getTitle();
                    }
                    ruleDragOrderSnapshot = new ArrayList<>();
                    for (HkRule r : rules) ruleDragOrderSnapshot.add(r.getTitle());
                    // 抬高被拖卡片并放开祖先裁剪，让它能浮到悬浮顶栏的分组 pills 之上
                    vh.itemView.setElevation(dp(12));
                    setRulesClipChildren(false);
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
                if (ruleManageMode && isCurrentlyActive
                        && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    updateGroupDropTarget(vh.itemView);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setElevation(0);
                setRulesClipChildren(true);
                int target = groupDropTargetPos;
                setGroupDropTarget(-1);
                if (ruleManageMode && target >= 0 && groupPairs != null
                        && target < groupPairs.size() && ruleDragTitle != null) {
                    HkRule rule = findRuleByTitle(ruleDragTitle);
                    restoreDragOrderSnapshot();
                    if (rule != null) {
                        String g = groupPairs.get(target)[1];
                        assignRuleGroup(rule, g);
                        Notify.show("已移入「" + groupPairs.get(target)[0] + "」");
                    }
                }
                ruleDragHolder = null;
                ruleDragFromPos = RecyclerView.NO_POSITION;
                ruleDragTitle = null;
                ruleDragOrderSnapshot = null;
            }
        });
        ruleTouchHelper.attachToRecyclerView(binding.rvRules);
    }

    /** 悬浮磨砂顶栏高度变化时，同步列表顶部内边距（内容滚动时从顶栏下穿过）。 */
    /** 悬浮顶栏总高度 = 顶栏56 + 间距8 + 分组行(48) + inline搜索条(56)，列表 paddingTop 跟随，避免被遮挡。 */
    private void updateRulesTopPadding() {
        boolean groups = binding.rvRuleGroup.getVisibility() == View.VISIBLE;
        boolean search = binding.inlineSearchBar.getVisibility() == View.VISIBLE;
        int top = dp(56) + dp(8) + (groups ? dp(48) : 0) + (search ? dp(56) : 0);
        binding.rvRules.setPadding(0, top, 0, dp(8));
    }

    /** 顶部「编辑」按钮：进入排列+分组编辑模式（长按拖拽排序，点卡片分配分组；按钮文字 编辑/完成 互换）。 */
    private void toggleRuleManageMode() {
        ruleManageMode = !ruleManageMode;
        binding.btnEditRule.setText(ruleManageMode ? "完成" : "编辑");
        binding.btnGroupManage.setVisibility(ruleManageMode ? View.VISIBLE : View.GONE);
        binding.btnSearchRule.setVisibility(ruleManageMode ? View.GONE : View.VISIBLE);
        binding.btnSortRule.setVisibility(ruleManageMode ? View.GONE : View.VISIBLE);
        // 编辑模式下收起 inline 搜索（搜索图标已隐藏）
        if (ruleManageMode && binding.inlineSearchBar.getVisibility() == View.VISIBLE) {
            binding.inlineSearchBar.setVisibility(View.GONE);
            binding.etInlineSearch.setText("");
            ruleKeyword = "";
            updateRulesTopPadding();
        }
        if (ruleManageMode && customOrder == null) {
            customOrder = new ArrayList<>();
            for (HkRule r : rules) customOrder.add(r.getTitle());
            saveCustomOrder();
        }
        ruleAdapter.rebuildSections();
        ruleAdapter.notifyDataSetChanged();
    }

    // ================= 编辑模式：拖拽卡片到分组 pills 分配分组 =================

    /** 放开/恢复 rvRules 祖先链的裁剪，让被拖卡片能浮到悬浮顶栏之上。 */
    private void setRulesClipChildren(boolean clip) {
        binding.rvRules.setClipChildren(clip);
        ViewParent p = binding.rvRules.getParent();
        int depth = 0;
        while (p instanceof ViewGroup && depth < 6) {
            ((ViewGroup) p).setClipChildren(clip);
            p = p.getParent();
            depth++;
        }
    }

    /** 拖拽中：被拖卡片中心落到哪个分组 pill 上就高亮哪个（-1=无）。 */
    private void updateGroupDropTarget(View dragView) {
        int pos = -1;
        if (binding.rvRuleGroup.getVisibility() == View.VISIBLE && groupChipAdapter != null) {
            int[] loc = new int[2];
            dragView.getLocationOnScreen(loc);
            float cx = loc[0] + dragView.getWidth() / 2f;
            float cy = loc[1] + dragView.getHeight() / 2f;
            int[] ploc = new int[2];
            binding.rvRuleGroup.getLocationOnScreen(ploc);
            View pill = binding.rvRuleGroup.findChildViewUnder(cx - ploc[0], cy - ploc[1]);
            if (pill != null) pos = binding.rvRuleGroup.getChildAdapterPosition(pill);
        }
        setGroupDropTarget(pos);
    }

    private void setGroupDropTarget(int pos) {
        if (groupDropTargetPos == pos) return;
        groupDropTargetPos = pos;
        if (groupChipAdapter != null) groupChipAdapter.setDropTarget(pos);
    }

    private HkRule findRuleByTitle(String title) {
        if (title == null) return null;
        for (HkRule r : rules) if (title.equals(r.getTitle())) return r;
        return null;
    }

    /** 投放分组时回滚本次拖拽造成的换位（拖拽只做分组，不做排序）。 */
    private void restoreDragOrderSnapshot() {
        if (ruleDragOrderSnapshot == null) return;
        Map<String, HkRule> byTitle = new HashMap<>();
        for (HkRule r : rules) byTitle.put(r.getTitle(), r);
        List<HkRule> restored = new ArrayList<>();
        for (String t : ruleDragOrderSnapshot) {
            HkRule r = byTitle.remove(t);
            if (r != null) restored.add(r);
        }
        restored.addAll(byTitle.values());
        rules.clear();
        rules.addAll(restored);
        customOrder = new ArrayList<>(ruleDragOrderSnapshot);
        for (HkRule r : rules) if (!customOrder.contains(r.getTitle())) customOrder.add(r.getTitle());
        saveCustomOrder();
    }

    /** 分配规则到分组 g（""=无分组/跟随文件）：拖放与分组弹窗共用同一逻辑。 */
    private void assignRuleGroup(HkRule rule, String g) {
        String fileGroup = rule.getGroup() == null ? "" : rule.getGroup();
        if (g.equals(fileGroup)) ruleGroupMap.remove(rule.getTitle());
        else ruleGroupMap.put(rule.getTitle(), g);
        saveRuleGroupMap();
        buildGroupFilterRow();
        ruleAdapter.rebuildSections();
        ruleAdapter.notifyDataSetChanged();
    }

    // ================= 小程序自定义分组（本地 prefs，不改 rule.json 原文件） =================

    /** 规则的实际分组：prefs 自定义覆盖优先，否则取 rule.json 的 group 字段。 */
    private String effectiveGroup(HkRule r) {
        if (ruleGroupMap.containsKey(r.getTitle())) {
            String g = ruleGroupMap.get(r.getTitle());
            return g == null ? "" : g;
        }
        String g = r.getGroup();
        return g == null ? "" : g;
    }

    private void loadCustomRuleData() {
        customGroups.clear();
        ruleGroupMap.clear();
        try {
            JSONArray arr = new JSONArray(getPrefs().getString(KEY_CUSTOM_GROUPS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String g = arr.optString(i);
                if (!TextUtils.isEmpty(g) && !customGroups.contains(g)) customGroups.add(g);
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject obj = new JSONObject(getPrefs().getString(KEY_RULE_GROUPS, "{}"));
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                ruleGroupMap.put(k, obj.optString(k, ""));
            }
        } catch (Exception ignored) {
        }
        customOrder = null;
        try {
            String s = getPrefs().getString(KEY_RULE_ORDER, null);
            if (s != null) {
                customOrder = new ArrayList<>();
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) customOrder.add(arr.optString(i));
            }
        } catch (Exception e) {
            customOrder = null;
        }
    }

    private void saveCustomGroups() {
        getPrefs().edit().putString(KEY_CUSTOM_GROUPS, new JSONArray(customGroups).toString()).apply();
    }

    private void saveRuleGroupMap() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : ruleGroupMap.entrySet()) obj.put(e.getKey(), e.getValue());
            getPrefs().edit().putString(KEY_RULE_GROUPS, obj.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void saveCustomOrder() {
        if (customOrder == null) return;
        getPrefs().edit().putString(KEY_RULE_ORDER, new JSONArray(customOrder).toString()).apply();
    }

    /** 刷新后同步自定义顺序：删掉已删规则，末尾追加新规则，按自定义顺序重排 rules。 */
    private void syncCustomOrder() {
        if (customOrder == null) return;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (HkRule r : rules) have.add(r.getTitle());
        for (int i = customOrder.size() - 1; i >= 0; i--) {
            if (!have.contains(customOrder.get(i))) customOrder.remove(i);
        }
        for (HkRule r : rules) {
            if (!customOrder.contains(r.getTitle())) customOrder.add(r.getTitle());
        }
        final Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < customOrder.size(); i++) idx.put(customOrder.get(i), i);
        Collections.sort(rules, (a, b) -> Integer.compare(
                idx.getOrDefault(a.getTitle(), Integer.MAX_VALUE),
                idx.getOrDefault(b.getTitle(), Integer.MAX_VALUE)));
        saveCustomOrder();
    }

    /** 点「小程序」标题：弹出 2 列 / 3 列切换小窗（独立卡片选项+间距，当前选中金色加粗）。 */
    private void showColumnSwitchPopup() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("小程序列数");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int current = getPrefs().getInt(KEY_RULE_SPAN, 3);
        String[] labels = {"2 列", "3 列"};
        int[] spans = {2, 3};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            boolean sel = spans[i] == current;
            tv.setTextColor(sel ? 0xFFB8890F : 0xFF1A1D24);
            tv.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            final int span = spans[i];
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                if (span == ruleGrid.getSpanCount()) return;
                getPrefs().edit().putInt(KEY_RULE_SPAN, span).apply();
                ruleGrid.setSpanCount(span);
                binding.rvRules.scrollToPosition(0);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 分组筛选行：全部 | 自定义分组（用户顺序）| 规则文件分组（①②序号排序），显示时去序号。 */
    private void buildGroupFilterRow() {
        List<String> groups = new ArrayList<>();
        for (String g : customGroups) {
            if (!TextUtils.isEmpty(g) && !groups.contains(g)) groups.add(g);
        }
        List<String> fileGroups = new ArrayList<>();
        for (HkRule r : rules) {
            String g = effectiveGroup(r);
            if (TextUtils.isEmpty(g) || groups.contains(g) || fileGroups.contains(g)) continue;
            fileGroups.add(g);
        }
        Collections.sort(fileGroups, (a, b) -> Integer.compare(groupSortKey(a), groupSortKey(b)));
        groups.addAll(fileGroups);
        boolean show = !groups.isEmpty();
        binding.rvRuleGroup.setVisibility(show ? View.VISIBLE : View.GONE);
        updateRulesTopPadding();
        if (!show) return;
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{"全部", ""});
        for (String g : groups) pairs.add(new String[]{stripGroupPrefix(g), g});
        int sel = 0;
        if (!ruleGroup.isEmpty()) {
            if (groups.contains(ruleGroup)) {
                sel = groups.indexOf(ruleGroup) + 1;
            } else {
                ruleGroup = ""; // 当前选中的分组已被删/改名，回退到全部
            }
        }
        binding.rvRuleGroup.setAdapter(new ChipAdapter(pairs, sel, value -> {
            ruleGroup = value;
            ruleAdapter.rebuildSections();
            ruleAdapter.notifyDataSetChanged();
        }));
        groupPairs = pairs;
        groupChipAdapter = (ChipAdapter) binding.rvRuleGroup.getAdapter();
        groupDropTargetPos = -1;
    }

    /** 分组排序键：前导①②..序号，没有则排最后。 */
    private static int groupSortKey(String g) {
        if (g == null || g.isEmpty()) return Integer.MAX_VALUE;
        int i = 0, n = 0;
        while (i < g.length()) {
            int d = circledDigit(g.codePointAt(i));
            if (d < 0) break;
            n = n * 10 + d;
            i += Character.charCount(g.codePointAt(i));
        }
        return i == 0 ? Integer.MAX_VALUE : n;
    }

    private static int circledDigit(int cp) {
        if (cp >= 0x2460 && cp <= 0x2473) return cp - 0x2460 + 1; // ①..⑳
        if (cp >= 0x3251 && cp <= 0x325F) return cp - 0x3251 + 21; // ㉑..㉟
        return -1;
    }

    /** 显示用：去掉前导①②序号。 */
    private static String stripGroupPrefix(String g) {
        if (g == null) return "";
        int i = 0;
        while (i < g.length() && circledDigit(g.codePointAt(i)) >= 0) i += Character.charCount(g.codePointAt(i));
        return i == 0 ? g : g.substring(i);
    }

    /** 编辑模式点卡片：选择分组（无分组 / 自定义分组 / 规则文件分组），即时保存。 */
    private void showGroupPicker(HkRule rule) {
        List<String> values = new ArrayList<>();
        values.add(""); // 无分组
        for (String g : customGroups) if (!values.contains(g)) values.add(g);
        for (HkRule r : rules) {
            String g = r.getGroup();
            if (!TextUtils.isEmpty(g) && !values.contains(g)) values.add(g);
        }
        String current = effectiveGroup(rule);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText(rule.getTitle());
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sub = new TextView(this);
        sub.setText("选择分组");
        sub.setTextColor(0xFF8A8F98);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        root.addView(sub, subLp);
        ScrollView sv = new ScrollView(this);
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (String g : values) {
            TextView tv = new TextView(this);
            tv.setText(TextUtils.isEmpty(g) ? "无分组" : stripGroupPrefix(g));
            boolean sel = g.equals(current);
            tv.setTextColor(sel ? 0xFFB8890F : 0xFF1A1D24);
            tv.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                assignRuleGroup(rule, g);
            });
            opts.addView(tv, lp);
        }
        sv.addView(opts);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(4);
        if (values.size() > 6) svLp.height = dp(340);
        root.addView(sv, svLp);
        dialog.show();
    }

    /** 分组管理：新建 / 改名 / 删除 / 排序自定义分组（只管自定义分组，不碰规则文件）。 */
    private void showGroupManageDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("分组管理");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sub = new TextView(this);
        sub.setText("自定义分组保存在本地，不修改规则文件");
        sub.setTextColor(0xFF8A8F98);
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        root.addView(sub, subLp);
        ScrollView sv = new ScrollView(this);
        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listBox);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(8);
        svLp.height = dp(300);
        root.addView(sv, svLp);
        TextView addBtn = new TextView(this);
        addBtn.setText("＋ 新建分组");
        addBtn.setTextColor(0xFFB8890F);
        addBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        addBtn.setTextSize(15);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackgroundResource(R.drawable.dialog_option_card_light);
        addBtn.setPadding(dp(16), dp(13), dp(16), dp(13));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = dp(10);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        addBtn.setOnClickListener(v -> showGroupNameInput(null, name -> {
            customGroups.add(name);
            saveCustomGroups();
            renderGroupRows(listBox, dialog);
            buildGroupFilterRow();
        }));
        root.addView(addBtn, addLp);
        renderGroupRows(listBox, dialog);
        dialog.setOnDismissListener(d -> {
            buildGroupFilterRow();
            ruleAdapter.rebuildSections();
            ruleAdapter.notifyDataSetChanged();
        });
        dialog.show();
    }

    /** 分组管理列表行：名称 + ▲▼排序 + 改名 + 删除。 */
    private void renderGroupRows(LinearLayout box, AlertDialog dialog) {
        box.removeAllViews();
        if (customGroups.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("暂无自定义分组，点下方新建");
            hint.setTextColor(0xFF8A8F98);
            hint.setTextSize(14);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(24), 0, dp(24));
            box.addView(hint);
            return;
        }
        for (int i = 0; i < customGroups.size(); i++) {
            final int pos = i;
            String g = customGroups.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            TextView name = new TextView(this);
            name.setText(stripGroupPrefix(g));
            name.setTextColor(0xFF1A1D24);
            name.setTextSize(15);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            row.addView(name, nameLp);
            row.addView(groupRowBtn("▲", v -> {
                if (pos <= 0) return;
                Collections.swap(customGroups, pos, pos - 1);
                saveCustomGroups();
                renderGroupRows(box, dialog);
                buildGroupFilterRow();
            }));
            row.addView(groupRowBtn("▼", v -> {
                if (pos >= customGroups.size() - 1) return;
                Collections.swap(customGroups, pos, pos + 1);
                saveCustomGroups();
                renderGroupRows(box, dialog);
                buildGroupFilterRow();
            }));
            row.addView(groupRowBtn("改名", v -> showGroupNameInput(g, name2 -> {
                customGroups.set(pos, name2);
                for (Map.Entry<String, String> e : ruleGroupMap.entrySet()) {
                    if (g.equals(e.getValue())) e.setValue(name2);
                }
                saveCustomGroups();
                saveRuleGroupMap();
                renderGroupRows(box, dialog);
                buildGroupFilterRow();
                ruleAdapter.rebuildSections();
                ruleAdapter.notifyDataSetChanged();
            })));
            row.addView(groupRowBtn("删除", v -> new AlertDialog.Builder(this)
                    .setTitle("删除分组")
                    .setMessage("删除「" + stripGroupPrefix(g) + "」？组内小程序将恢复按规则文件分组。")
                    .setPositiveButton("删除", (d, w) -> {
                        customGroups.remove(pos);
                        Iterator<Map.Entry<String, String>> it = ruleGroupMap.entrySet().iterator();
                        while (it.hasNext()) {
                            if (g.equals(it.next().getValue())) it.remove();
                        }
                        saveCustomGroups();
                        saveRuleGroupMap();
                        if (g.equals(ruleGroup)) ruleGroup = "";
                        renderGroupRows(box, dialog);
                        buildGroupFilterRow();
                        ruleAdapter.rebuildSections();
                        ruleAdapter.notifyDataSetChanged();
                    })
                    .setNegativeButton("取消", null)
                    .show()));
            box.addView(row);
            if (i < customGroups.size() - 1) {
                View div = new View(this);
                div.setBackgroundColor(0xFFE3E6EB);
                box.addView(div, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }

    private TextView groupRowBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFB8890F);
        tv.setTextSize(14);
        tv.setPadding(dp(10), dp(8), dp(10), dp(8));
        tv.setOnClickListener(l);
        return tv;
    }

    /** 分组名输入框：新建时 oldName=null，改名时带原名；重名/空名拒绝。 */
    private void showGroupNameInput(String oldName, java.util.function.Consumer<String> onOk) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        EditText et = new EditText(this);
        et.setHint(oldName == null ? "分组名，如：追剧" : "新分组名");
        if (oldName != null) et.setText(oldName);
        et.setSingleLine(true);
        et.setBackgroundResource(R.drawable.xc_input_bg);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(oldName == null ? "新建分组" : "改名")
                .setView(root)
                .setPositiveButton("确定", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Notify.show("分组名不能为空");
                        return;
                    }
                    if (!name.equals(oldName) && customGroups.contains(name)) {
                        Notify.show("分组已存在");
                        return;
                    }
                    onOk.accept(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshRules() {
        new Thread(() -> {
            List<HkRule> list = HkRuleManager.get().getRules();
            App.post(() -> {
                rules.clear();
                rules.addAll(list);
                syncCustomOrder();
                ruleAdapter.rebuildSections();
                ruleAdapter.notifyDataSetChanged();
                buildGroupFilterRow();
                boolean empty = ruleAdapter.getItemCount() == 0;
                binding.emptyRules.setVisibility(empty ? View.VISIBLE : View.GONE);
                binding.rvRules.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    /** 导入入口：三个选项各做成独立卡片框，有间距（选项类弹窗统一按此样式）。 */
    private void showImportDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        String[] labels = {"从文件导入", "从云口令导入", "粘贴规则 JSON"};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(16);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            final int which = i;
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                if (which == 0) pickRuleFile();
                else if (which == 1) showCloudCodeDialog(null);
                else showPasteDialog();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(10);
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 云口令导入对话框（支持 云1~云10 开头，如 云6oooole/apidb/xxxx）。 */
    private void showCloudCodeDialog(String preset) {
        EditText et = new EditText(this);
        et.setHint("粘贴云口令，如：云6oooole/apidb/xxxx");
        et.setSingleLine(true);
        et.setTextColor(0xFF1A1D24);
        et.setHintTextColor(0xFF9AA1B0);
        if (!TextUtils.isEmpty(preset)) et.setText(preset);
        new AlertDialog.Builder(this)
                .setTitle("从云口令导入")
                .setView(et)
                .setPositiveButton("导入", (d, w) -> importByCloudCode(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void importByCloudCode(String code) {
        if (TextUtils.isEmpty(code)) {
            Notify.show("口令为空");
            return;
        }
        Notify.show("正在从云端获取规则…");
        new Thread(() -> {
            try {
                HkRule rule = HkRuleManager.get().importByCloudCode(code);
                App.post(() -> {
                    Notify.show("导入成功：" + rule.getTitle());
                    refreshRules();
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 多文件导入的勾选项：解析结果 + 其所属 .hkzip 的附带资源（非 hkzip 时为 null）。
     * 附带资源只给本条目选中的规则落盘，避免多包混淆。
     */
    private static class PickEntry {
        final HkRuleManager.ParsedRule parsed;
        final HkRuleManager.HkZipData hkZipData;

        PickEntry(HkRuleManager.ParsedRule parsed, HkRuleManager.HkZipData hkZipData) {
            this.parsed = parsed;
            this.hkZipData = hkZipData;
        }
    }

    private void pickRuleFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/json", "application/zip", "application/javascript", "text/javascript"});
            // 支持一次选多个文件（系统文件选择器长按多选）
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_IMPORT_FILE);
        } catch (Exception e) {
            Notify.show("无法打开文件选择器");
        }
    }

    private void showPasteDialog() {
        EditText et = new EditText(this);
        et.setHint("粘贴 rule.json / 规则数组 / js: 规则内容");
        et.setMinLines(4);
        et.setTextColor(0xFF1A1D24);
        et.setHintTextColor(0xFF9AA1B0);
        new AlertDialog.Builder(this)
                .setTitle("从口令导入")
                .setView(et)
                .setPositiveButton("导入", (d, w) -> importRuleText(et.getText().toString(), null))
                .setNegativeButton("取消", null)
                .show();
    }

    private void importRuleJson(String json) {
        importRuleText(json, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_FILE && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                // 多选：ClipData 里每个 item 是一个文件
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importRuleFiles(uris);
        }
    }

    /**
     * 多文件导入：逐个解析（zip/.hkzip/JSON/JSON 数组/js:），全部合并后走统一勾选弹窗（带全选）。
     * 单个文件解析失败只跳过该文件，不影响其他文件。
     *
     * @param uris 待导入的文件 Uri 列表
     */
    private void importRuleFiles(List<Uri> uris) {
        if (uris.size() > 1) Notify.show("正在解析 " + uris.size() + " 个文件…");
        new Thread(() -> {
            List<PickEntry> entries = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            for (Uri uri : uris) {
                File tmp = null;
                String displayName = getDisplayName(uri);
                try {
                    // 先落到临时文件：需要按后缀/魔数判断是否为 zip
                    tmp = copyToTemp(uri);
                    if (isZipFile(tmp, displayName)) {
                        // 多规则包：先全部解析 → 弹窗让用户勾选 → 只导入选中的
                        boolean hkzip = displayName != null && displayName.toLowerCase().endsWith(".hkzip");
                        if (hkzip) {
                            HkRuleManager.HkZipData zipData = HkRuleManager.get().parseHkZip(tmp);
                            for (HkRuleManager.ParsedRule p : zipData.rules) entries.add(new PickEntry(p, zipData));
                        } else {
                            for (HkRuleManager.ParsedRule p : HkRuleManager.get().parseZip(tmp)) {
                                entries.add(new PickEntry(p, null));
                            }
                        }
                    } else {
                        String text = readAll(new FileInputStream(tmp));
                        if (HkRuleManager.isJsRuleText(text)) {
                            entries.add(new PickEntry(HkRuleManager.get().parseJsRule(text, displayName), null));
                        } else {
                            for (HkRuleManager.ParsedRule p : HkRuleManager.get().parseJsonList(text)) {
                                entries.add(new PickEntry(p, null));
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.add((displayName == null ? "文件" : displayName) + "：" + e.getMessage());
                } finally {
                    if (tmp != null) tmp.delete();
                }
            }
            final List<PickEntry> result = entries;
            final List<String> failList = errors;
            App.post(() -> {
                if (!failList.isEmpty()) {
                    Notify.show("跳过 " + failList.size() + " 个失败文件：" + TextUtils.join("；", failList));
                }
                if (result.isEmpty()) {
                    if (failList.isEmpty()) Notify.show("未解析到任何规则");
                } else if (result.size() == 1) {
                    // 单个规则直接导入（.hkzip 的附带资源一并落盘）
                    importPickedRules(result);
                } else {
                    showPickRulesDialog(result);
                }
            });
        }).start();
    }

    /** content uri 落盘到临时文件（便于按魔数判断 zip）。 */
    private File copyToTemp(Uri uri) throws Exception {
        File tmp = File.createTempFile("hkimport", ".tmp", getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return tmp;
    }

    /**
     * 统一文本导入路由：js: 开头 → JS 规则包装导入；否则按 JSON（单个对象或数组）导入。
     *
     * @param text     规则文本
     * @param fileName 来源文件名（可为 null），js: 规则用于取标题
     */
    private void importRuleText(String text, String fileName) {
        if (TextUtils.isEmpty(text)) {
            App.post(() -> Notify.show("内容为空"));
            return;
        }
        new Thread(() -> {
            try {
                if (HkRuleManager.isJsRuleText(text)) {
                    HkRule rule = HkRuleManager.get().importJsRule(text, fileName);
                    App.post(() -> {
                        Notify.show("导入成功：" + rule.getTitle());
                        refreshRules();
                    });
                } else {
                    // 一键导入：先全部解析，多个则弹窗让用户勾选要导入哪些（带全选）
                    List<HkRuleManager.ParsedRule> parsed = HkRuleManager.get().parseJsonList(text);
                    final List<PickEntry> entries = wrapParsed(parsed, null);
                    App.post(() -> {
                        if (entries.size() == 1) {
                            importPickedRules(entries);
                        } else {
                            showPickRulesDialog(entries);
                        }
                    });
                }
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 把解析结果包装成勾选项（附带资源统一传入，传 null 表示无）。 */
    private static List<PickEntry> wrapParsed(List<HkRuleManager.ParsedRule> parsed, HkRuleManager.HkZipData hkZipData) {
        List<PickEntry> list = new ArrayList<>(parsed.size());
        for (HkRuleManager.ParsedRule p : parsed) list.add(new PickEntry(p, hkZipData));
        return list;
    }

    /**
     * 把选中的解析结果落盘导入（各 .hkzip 的附带资源按所属包分别落盘）。
     *
     * @param picked 选中的勾选项
     */
    private void importPickedRules(List<PickEntry> picked) {
        if (picked.isEmpty()) {
            Notify.show("未选中任何规则");
            return;
        }
        new Thread(() -> {
            try {
                List<HkRule> rules = new ArrayList<>();
                List<HkRule> updated = new ArrayList<>();
                Map<HkRuleManager.HkZipData, List<HkRule>> assets = new HashMap<>();
                for (PickEntry e : picked) {
                    HkRule installed = HkRuleManager.get().getInstalledRule(e.parsed.rule.getTitle());
                    boolean isUpdate = installed != null && e.parsed.rule.getVersion() > installed.getVersion();
                    HkRuleManager.get().saveRule(e.parsed.rule, e.parsed.json);
                    rules.add(e.parsed.rule);
                    if (isUpdate) updated.add(e.parsed.rule);
                    if (e.hkZipData != null) {
                        List<HkRule> g = assets.get(e.hkZipData);
                        if (g == null) {
                            g = new ArrayList<>();
                            assets.put(e.hkZipData, g);
                        }
                        g.add(e.parsed.rule);
                    }
                }
                for (Map.Entry<HkRuleManager.HkZipData, List<HkRule>> en : assets.entrySet()) {
                    HkRuleManager.get().saveHkZipAssets(en.getKey(), en.getValue());
                }
                boolean wasUpdate = !updated.isEmpty();
                String msg = rules.size() == 1
                        ? (wasUpdate ? "更新成功：" : "导入成功：") + rules.get(0).getTitle()
                        : (wasUpdate ? "更新成功 " : "导入成功 ") + rules.size() + " 个小程序";
                App.post(() -> {
                    Notify.show(msg);
                    refreshRules();
                    // 更新/添加成功后提示"是否打开"（像海阔原版）
                    showOpenAfterImportDialog(rules);
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 导入/更新成功后提示"是否打开"（海阔原版行为）。
     * 单个规则：直接问是否打开该规则；多个：列出供选择。
     */
    private void showOpenAfterImportDialog(List<HkRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        if (rules.size() == 1) {
            HkRule rule = rules.get(0);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(rule.getTitle())
                    .setMessage("是否打开该小程序？")
                    .setPositiveButton("打开", (d, w) -> openRule(rule))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            String[] titles = new String[rules.size()];
            for (int i = 0; i < rules.size(); i++) titles[i] = rules.get(i).getTitle();
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("是否打开小程序？")
                    .setItems(titles, (d, which) -> openRule(rules.get(which)))
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /**
     * 一键导入勾选弹窗：多选列表（标题+作者）+ 顶部"全选"复选框。
     * 确定后只导入勾选中的规则。
     *
     * @param entries 解析好的全部勾选项
     */
    private void showPickRulesDialog(List<PickEntry> entries) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad / 2, pad, 0);

        CheckBox cbAll = new CheckBox(this);
        cbAll.setText("全选");
        cbAll.setChecked(true);
        cbAll.setTextColor(0xFF1A1D24);
        root.addView(cbAll);

        List<String> labels = new ArrayList<>();
        for (PickEntry e : entries) {
            HkRuleManager.ParsedRule p = e.parsed;
            String author = p.rule.getAuthor();
            labels.add(TextUtils.isEmpty(author) ? p.rule.getTitle() : p.rule.getTitle() + "（" + author + "）");
        }
        ListView listView = new ListView(this);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.item_hk_pick, labels);
        listView.setAdapter(adapter);
        for (int i = 0; i < labels.size(); i++) listView.setItemChecked(i, true);
        int rowPx = (int) (64 * density);
        int listH = Math.min(rowPx * labels.size(), rowPx * 7);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listH);
        lp.topMargin = (int) (8 * density);
        listView.setLayoutParams(lp);
        root.addView(listView);

        cbAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (int i = 0; i < labels.size(); i++) listView.setItemChecked(i, isChecked);
        });

        new AlertDialog.Builder(this)
                .setTitle("选择要导入的小程序（" + labels.size() + "）")
                .setView(root)
                .setPositiveButton("导入", (d, w) -> {
                    SparseBooleanArray checked = listView.getCheckedItemPositions();
                    List<PickEntry> picked = new ArrayList<>();
                    for (int i = 0; i < labels.size(); i++) {
                        if (checked.get(i)) picked.add(entries.get(i));
                    }
                    importPickedRules(picked);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 取 content uri 的显示文件名（可能为 null）。 */
    private String getDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 按后缀或 zip 魔数（PK\x03\x04）判断是否为 zip 包。 */
    private boolean isZipFile(File f, String displayName) {
        if (displayName != null && displayName.toLowerCase().endsWith(".zip")) return true;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** 导出规则为云口令：上传云端后显示口令，可复制分享（官方 App 也可导入）。 */
    private void exportAsCloudCode(HkRule rule) {
        Notify.show("正在上传云端…");
        new Thread(() -> {
            try {
                String code = HkRuleManager.get().exportAsCloudCode(rule.getTitle());
                App.post(() -> showExportCodeDialog(rule.getTitle(), code));
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 导出为云5短口令：走 cmd.im，生成 云5oooole/{id} 短格式。 */
    private void exportAsShortCloudCode(HkRule rule) {
        Notify.show("正在上传云端…");
        new Thread(() -> {
            try {
                String code = HkRuleManager.get().exportAsCmdImCode(rule.getTitle());
                App.post(() -> showExportCodeDialog(rule.getTitle(), code));
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    private void showExportCodeDialog(String title, String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFF1A1D24);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("「" + title + "」的云口令")
                .setMessage("口令已生成，可复制分享给他人导入：")
                .setView(tv)
                .setPositiveButton("复制", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("hk_cloud_code", code));
                        Notify.show("口令已复制");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 导出为文件：弹窗选择格式（.json / .hkzip），保存到 Download 目录并调起分享。 */
    private void exportAsFile(HkRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("选择导出格式")
                .setItems(new String[]{"导出为 .json 文件", "导出为 .hkzip 包"}, (d, which) -> {
                    if (which == 0) doExportAsJsonFile(rule);
                    else doExportAsHkZip(rule);
                })
                .show();
    }

    /** 导出规则为文件：保存到 Download 目录并调起分享。 */
    private void doExportAsJsonFile(HkRule rule) {
        new Thread(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                java.io.File out = HkRuleManager.get().exportAsFile(rule.getTitle(), dir);
                App.post(() -> {
                    Notify.show("已导出到：" + out.getAbsolutePath());
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/json");
                        share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(out));
                        startActivity(Intent.createChooser(share, "分享规则文件"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 导出规则为 .hkzip 包（含附带资源）：保存到 Download 目录并调起分享。 */
    private void doExportAsHkZip(HkRule rule) {
        new Thread(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                java.io.File out = HkRuleManager.get().exportAsHkZip(rule.getTitle(), dir);
                App.post(() -> {
                    Notify.show("已导出到：" + out.getAbsolutePath());
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/zip");
                        share.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(out));
                        startActivity(Intent.createChooser(share, "分享规则包"));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    private void showDeleteRuleConfirm(HkRule rule) {        new AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确定删除「" + rule.getTitle() + "」吗？")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    HkRuleManager.get().delete(rule.getTitle());
                    App.post(this::refreshRules);
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    private class RuleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_RULE = 1;
        private final List<Object> items = new ArrayList<>();

        /** 平铺规则：分组筛选（实际分组=自定义覆盖优先） + 关键字过滤 + 名称排序开关（编辑模式下按拖拽顺序，不做名称排序）。 */
        /** 搜索页直接设置数据（不过滤，调用方已过滤）。 */
        void setItems(List<HkRule> list) {
            items.clear();
            items.addAll(list);
        }

        void rebuildSections() {
            items.clear();
            List<HkRule> src = new ArrayList<>(rules);
            if (!TextUtils.isEmpty(ruleGroup)) {
                Iterator<HkRule> it = src.iterator();
                while (it.hasNext()) {
                    HkRule r = it.next();
                    if (!ruleGroup.equals(effectiveGroup(r))) it.remove();
                }
            }
            if (!TextUtils.isEmpty(ruleKeyword)) {
                String kw = ruleKeyword.toLowerCase(Locale.getDefault());
                Iterator<HkRule> it = src.iterator();
                while (it.hasNext()) {
                    HkRule r = it.next();
                    String t = r.getTitle() == null ? "" : r.getTitle().toLowerCase(Locale.getDefault());
                    if (!t.contains(kw)) it.remove();
                }
            }
            if (ruleSortByName && !ruleManageMode) {
                Collections.sort(src, (a, b) -> {
                    String ta = a.getTitle() == null ? "" : a.getTitle();
                    String tb = b.getTitle() == null ? "" : b.getTitle();
                    return ta.compareTo(tb);
                });
            }
            items.addAll(src);
        }

        /** 拖拽换位：同步 adapter 显示列表与底层 rules 列表，持久化自定义顺序。 */
        void moveItem(int from, int to) {
            HkRule moved = (HkRule) items.get(from);
            HkRule other = (HkRule) items.get(to);
            Collections.swap(items, from, to);
            notifyItemMoved(from, to);
            int ix = -1, iy = -1;
            for (int i = 0; i < rules.size(); i++) {
                String t = rules.get(i).getTitle();
                if (t.equals(moved.getTitle())) ix = i;
                if (t.equals(other.getTitle())) iy = i;
            }
            if (ix >= 0 && iy >= 0 && ix != iy) {
                Collections.swap(rules, ix, iy);
                customOrder = new ArrayList<>();
                for (HkRule r : rules) customOrder.add(r.getTitle());
                saveCustomOrder();
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            TextView icon, name, tag;
            SwitchCompat sw;
            ImageButton more;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.tv_icon);
                name = v.findViewById(R.id.tv_name);
                tag = v.findViewById(R.id.tv_group_tag);
                sw = v.findViewById(R.id.sw_enable);
                more = v.findViewById(R.id.btn_more);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return TYPE_RULE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_rule, parent, false);
            // 卡片磨砂：真 blur 只作用于独立背景层，内容层不被模糊
            FrostedGlass.blurBackground(v.findViewById(R.id.card_bg));
            return new Holder(v);
        }

        /** 图标底色：圆角保持（不用 setBackgroundColor，避免圆角丢失）。 */
        private void applyIconBg(TextView icon, String iconDef) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(10));
            int color = 0xFFF6EED6;
            if (!TextUtils.isEmpty(iconDef) && iconDef.startsWith("#")) {
                try {
                    color = android.graphics.Color.parseColor(iconDef);
                } catch (Exception ignored) {
                }
            }
            gd.setColor(color);
            icon.setBackground(gd);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Holder h = (Holder) vh;
            HkRule rule = (HkRule) items.get(position);
            h.name.setText(rule.getTitle());
            applyIconBg(h.icon, rule.getIcon());
            float alpha = rule.isEnabled() ? 1f : 0.5f;
            h.icon.setAlpha(alpha);
            h.name.setAlpha(alpha);
            // 管理模式：显示启用开关 + 分组标签代替 ⋮；普通模式：显示 ⋮
            h.sw.setVisibility(ruleManageMode ? View.VISIBLE : View.GONE);
            h.more.setVisibility(ruleManageMode ? View.GONE : View.VISIBLE);
            h.tag.setVisibility(ruleManageMode ? View.VISIBLE : View.GONE);
            if (ruleManageMode) {
                String g = effectiveGroup(rule);
                if (TextUtils.isEmpty(g)) {
                    h.tag.setText("未分组");
                    h.tag.setTextColor(0xFF8A8F98);
                    h.tag.setBackgroundResource(R.drawable.hk_group_tag_empty);
                } else {
                    h.tag.setText(stripGroupPrefix(g));
                    h.tag.setTextColor(0xFF1B5E20);
                    h.tag.setBackgroundResource(R.drawable.hk_group_tag);
                }
            }
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(rule.isEnabled());
            h.sw.setOnCheckedChangeListener((btn, checked) -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Object o = items.get(pos);
                if (!(o instanceof HkRule)) return;
                HkRule r = (HkRule) o;
                r.setEnabled(checked);
                HkRuleManager.get().setEnabled(r.getTitle(), checked);
                notifyItemChanged(pos);
            });
            h.more.setOnClickListener(v -> showRuleCardMenu(rule));
            // 编辑模式：点卡片分配分组（长按拖拽排序）；普通模式：点卡片打开小程序
            if (ruleManageMode) {
                h.itemView.setOnClickListener(v -> showGroupPicker(rule));
            } else {
                h.itemView.setOnClickListener(v -> openRule(rule));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 卡片 ⋮ 菜单：编辑 / 分享 / 删除（选项独立卡片框、有间距）。 */
    private void showRuleCardMenu(HkRule rule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText(rule.getTitle());
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        String[] labels = {"编辑", "分享", "删除"};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            final int which = i;
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                if (which == 0) showRuleJsonEditor(rule);
                else if (which == 1) showRuleShareOptions(rule);
                else showDeleteRuleConfirm(rule);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 分享：云口令分享 / 文件分享（独立卡片选项）。 */
    private void showRuleShareOptions(HkRule rule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        String[] labels = {"云口令分享", "文件分享"};
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(10);
            final int which = i;
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                if (which == 0) exportAsCloudCode(rule);
                else exportAsFile(rule);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 编辑规则 JSON：文本编辑后保存（importJson 会校验，失败直接提示）。 */
    private void showRuleJsonEditor(HkRule rule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        EditText et = new EditText(this);
        et.setBackgroundResource(R.drawable.xc_input_bg);
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        et.setTextSize(12);
        et.setGravity(Gravity.START | Gravity.TOP);
        try {
            String json = HkRuleManager.get().loadRuleJson(rule.getTitle());
            et.setText(json == null ? "" : json);
        } catch (Exception e) {
            et.setText("");
        }
        root.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));
        new AlertDialog.Builder(this)
                .setTitle("编辑规则")
                .setView(root)
                .setPositiveButton("保存", (d, w) -> new Thread(() -> {
                    try {
                        HkRuleManager.get().importJson(et.getText().toString());
                        App.post(this::refreshRules);
                    } catch (Exception e) {
                        App.post(() -> com.fongmi.android.tv.utils.ToastUtil.show(
                                HkPageActivity.this, "保存失败：" + e.getMessage()));
                    }
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= V2 分类+列表 =================

    private void initContentView() {
        // 官方 60 列栅格（8.83 ArticleColTypeEnum.spanCount 反编译实测）：
        // movie_3/pic_3/icon_small_3(3/行)=20；icon_4系列(4/行)=15；icon_5/text_5(5/行)=12；
        // icon_2/pic_2/text_2(2/行)=30；其余全宽=60
        contentGrid = new GridLayoutManager(this, 60);
        contentGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return contentAdapter == null ? 60 : contentAdapter.spanFor(position);
            }
        });
        binding.rvVideos.setLayoutManager(contentGrid);
        binding.rvVideos.addItemDecoration(new GridSpace());
        contentAdapter = new ContentAdapter(videos);
        binding.rvVideos.setAdapter(contentAdapter);
        binding.rvCategory.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.swipeContent.setColorSchemeColors(0xFFD4A017);
        binding.swipeContent.setOnRefreshListener(() -> loadContent(true));
        binding.btnBackContent.setOnClickListener(v -> onBackInvoked());
        binding.btnSearchContent.setOnClickListener(v -> openSearch());
        binding.btnMiniappAll.setOnClickListener(v -> showMiniappSwitchDialog());
        binding.tvContentTitle.setSelected(true);
        binding.tvContentEmpty.setOnClickListener(v -> loadContent(true));
        binding.rvVideos.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || noMore) return;
                int last = contentGrid.findLastVisibleItemPosition();
                if (last >= contentAdapter.getItemCount() - 3) loadContent(false);
            }
        });
    }

    private void openRule(HkRule rule) {
        // "我的主页"暂未实现（规则内 HikerHome 未定义，打开必报错）：顶部 tabs 不展示它；
        // 若选中的就是它，自动切到下一个可用的规则
        if (isHomePageRule(rule)) {
            HkRule next = nextAvailableRule(rule);
            if (next != null) rule = next;
        }
        destroyRouterAsync();
        subStack.clear(); // 切规则时丢弃子列表栈
        currentRule = rule;
        cls = "";
        area = "";
        year = "";
        sort = "";
        page = 1;
        noMore = false;
        loading = false; // 旧规则的在途加载作废，由新 loadContent 接管
        contentGen++; // 旧回调过期
        videos.clear();
        binding.tvContentTitle.setText(rule.getTitle());
        buildMiniappTabs();
        buildCategoryRow();
        buildFilterRows();
        buildContentSearchBar();
        pushView(V_CONTENT);
        loadContent(true);
        Integer pos = scrollMem.get(rule.getTitle());
        if (pos != null && pos > 0) contentGrid.scrollToPosition(pos);
    }

    /**
     * 是否为"我的主页"规则：顶部 tabs 暂不展示它（HikerHome 未实现）。
     */
    private static boolean isHomePageRule(HkRule r) {
        return r != null && "我的主页".equals(r.getTitle());
    }

    /**
     * 取"我的主页"之后下一个可用的规则（排除"我的主页"本身，环绕查找）；没有则返回 null。
     */
    private HkRule nextAvailableRule(HkRule home) {
        try {
            List<HkRule> rules = com.fongmi.android.tv.api.hk.HkRuleManager.get().getRules();
            if (rules == null || rules.isEmpty()) return null;
            int start = -1;
            for (int i = 0; i < rules.size(); i++) {
                HkRule r = rules.get(i);
                if (r == home || (home.getTitle() != null && home.getTitle().equals(r.getTitle()))) {
                    start = i;
                    break;
                }
            }
            for (int k = 1; k <= rules.size(); k++) {
                HkRule r = rules.get((start + k) % rules.size());
                if (r.isEnabled() && !isHomePageRule(r)) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 8.83 同款：内容页顶部小程序切换 tabs。
     * 横向显示已安装的小程序，点击切换规则。
     */
    private void buildMiniappTabs() {
        try {
            List<HkRule> rules = com.fongmi.android.tv.api.hk.HkRuleManager.get().getRules();
            List<HkRule> enabled = new ArrayList<>();
            for (HkRule r : rules) {
                // 顶部 tabs 不需要"我的主页"（HikerHome 未实现，打开必报错）
                if (r.isEnabled() && !isHomePageRule(r)) enabled.add(r);
            }
            // 当前规则若不在 tabs 列表里（未启用或刚导入），追加进去并选中，保证 tabs 跟随当前页面
            String curTitle = currentRule == null ? null : currentRule.getTitle();
            boolean hasCur = false;
            if (curTitle != null) {
                for (HkRule r : enabled) {
                    if (curTitle.equals(r.getTitle())) {
                        hasCur = true;
                        break;
                    }
                }
            }
            if (!hasCur && currentRule != null) {
                enabled.add(currentRule);
            }
            miniappRules.clear();
            miniappRules.addAll(enabled);
            if (enabled.size() <= 1) {
                binding.miniappTabsRow.setVisibility(View.GONE);
                return;
            }
            List<String[]> pairs = new ArrayList<>();
            int sel = 0;
            for (int i = 0; i < enabled.size(); i++) {
                HkRule r = enabled.get(i);
                pairs.add(new String[]{r.getTitle(), String.valueOf(i)});
                if (curTitle != null && curTitle.equals(r.getTitle())) sel = i;
            }
            final List<HkRule> finalEnabled = enabled;
            ChipAdapter adapter = new ChipAdapter(pairs, sel, idx -> {
                try {
                    int i = Integer.parseInt(idx);
                    if (i >= 0 && i < finalEnabled.size()) {
                        HkRule target = finalEnabled.get(i);
                        if (currentRule == null || !target.getTitle().equals(currentRule.getTitle())) {
                            openRule(target);
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
            binding.rvMiniappTabs.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            binding.rvMiniappTabs.setAdapter(adapter);
            // 滚动到选中项，保证当前小程序的 tab 可见
            final int finalSel = sel;
            binding.rvMiniappTabs.post(() -> {
                try {
                    ((LinearLayoutManager) binding.rvMiniappTabs.getLayoutManager())
                            .scrollToPositionWithOffset(finalSel, 0);
                } catch (Throwable ignored) {
                }
            });
            binding.miniappTabsRow.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            binding.miniappTabsRow.setVisibility(View.GONE);
        }
    }

    /**
     * 小程序切换：顶部 tabs 栏最右「全部」按钮弹窗。
     * 星辰风格：毛玻璃底 + 独立选项卡片。8.83 内容页无此功能。
     */
    private void showMiniappSwitchDialog() {
        if (miniappRules == null || miniappRules.size() <= 1) return;
        final String curTitle = currentRule == null ? null : currentRule.getTitle();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.xc_dialog_light);
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("选择小程序");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setClipToPadding(false);
        int rows = (miniappRules.size() + 1) / 2;
        int gridH = Math.min(rows * dp(66), dp(360));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, gridH);
        rlp.topMargin = dp(12);
        card.addView(rv, rlp);

        final AlertDialog[] holder = new AlertDialog[1];
        rv.setAdapter(new MiniappSwitchAdapter(miniappRules, curTitle, target -> {
            if (holder[0] != null) holder[0].dismiss();
            if (target != null && currentRule != null && !target.getTitle().equals(currentRule.getTitle())) {
                openRule(target);
            }
        }));

        FrameLayout outer = new FrameLayout(this);
        int m = dp(24);
        outer.setPadding(m, 0, m, 0);
        outer.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Translucent_NoTitleBar)
                .setView(outer).create();
        holder[0] = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
    }

    /** 「全部」弹窗的 2 列选项卡片适配器：独立卡片框、有间距，当前选中高亮。 */
    private static class MiniappSwitchAdapter extends RecyclerView.Adapter<MiniappSwitchAdapter.Holder> {
        interface OnPick {
            void onPick(HkRule rule);
        }

        private final List<HkRule> rules;
        private final String curTitle;
        private final OnPick listener;

        MiniappSwitchAdapter(List<HkRule> rules, String curTitle, OnPick listener) {
            this.rules = rules;
            this.curTitle = curTitle;
            this.listener = listener;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView name;

            Holder(View v) {
                super(v);
                name = (TextView) v;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            TextView tv = new TextView(ctx);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setTextSize(15);
            int p = (int) (14 * d + 0.5f);
            tv.setPadding(p, p, p, p);
            GridLayoutManager.LayoutParams lp = new GridLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int mg = (int) (6 * d + 0.5f);
            lp.setMargins(mg, mg, mg, mg);
            tv.setLayoutParams(lp);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            HkRule r = rules.get(position);
            h.name.setText(r.getTitle());
            boolean sel = curTitle != null && curTitle.equals(r.getTitle());
            h.name.setTextColor(sel ? 0xFFD4A017 : 0xFF1A1D24);
            h.name.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            h.name.setOnClickListener(v -> {
                if (listener != null) listener.onPick(r);
            });
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }
    }

    /**
     * 8.83 同款：内容页内嵌搜索栏。
     * 规则有搜索能力时显示，输入关键词点搜索跳搜索页。
     */
    private void buildContentSearchBar() {
        try {
            if (currentRule != null && currentRule.hasSearch()) {
                binding.searchBarContent.setVisibility(View.VISIBLE);
                binding.etSearchContent.setHint("搜索" + currentRule.getTitle() + " / 演员");
                binding.etSearchContent.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        doContentSearch();
                        return true;
                    }
                    return false;
                });
                binding.btnSearchContentGo.setOnClickListener(v -> doContentSearch());
            } else {
                binding.searchBarContent.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            binding.searchBarContent.setVisibility(View.GONE);
        }
    }

    private void doContentSearch() {
        try {
            String kw = binding.etSearchContent.getText().toString().trim();
            if (!kw.isEmpty() && currentRule != null) {
                // 复用现有搜索页逻辑
                openSearchWithKeyword(kw);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 内容页内嵌搜索栏：带关键词直接打开搜索页并执行搜索。 */
    private void openSearchWithKeyword(String kw) {
        searchDualMode = false;
        keyword = "";
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
        binding.etKeyword.setText(kw);
        binding.etKeyword.setHint("输入关键词搜索");
        binding.tvSearchEmpty.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        binding.historySection.setVisibility(View.GONE);
        pushView(V_SEARCH);
        doSearch(kw);
    }

    private void buildCategoryRow() {
        List<String[]> pairs = new ArrayList<>();
        pairs.addAll(currentRule.getClassPairs());
        // 默认选中规则的第一个分类（原版无"全部"，不自动添加）
        if (!pairs.isEmpty()) cls = pairs.get(0)[1];
        ChipAdapter adapter = new ChipAdapter(pairs, 0, value -> {
            cls = value;
            loadContent(true);
        });
        binding.rvCategory.setAdapter(adapter);
        binding.rvCategory.setVisibility(pairs.size() > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * V2 动态分类 tab：规则 find_rule 返回的 scroll_button/flex_button 条目。
     * 标题去 HTML 标签；含 #ff1493 高亮色的为当前选中。点击 tab 求值其 lazyRule
     * （putMyVar 设分类变量），再刷新列表。
     */
    private void buildDynamicTabs(List<HkItem> tabs) {
        List<String[]> pairs = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < tabs.size(); i++) {
            HkItem it = tabs.get(i);
            String raw = it.getTitle() == null ? "" : it.getTitle();
            if (raw.toLowerCase().contains("#ff1493")) sel = i;
            String name = stripHtml(raw);
            if (name.isEmpty()) name = "分类" + (i + 1);
            pairs.add(new String[]{name, it.getUrl() == null ? "" : it.getUrl()});
        }
        ChipAdapter adapter = new ChipAdapter(pairs, sel, url -> {
            if (url == null || url.isEmpty()) return;
            new Thread(() -> {
                try {
                    getRouter().evalTab(url);
                } catch (Throwable ignored) {
                }
                App.post(() -> loadContent(true));
            }).start();
        });
        binding.rvCategory.setAdapter(adapter);
        binding.rvCategory.setVisibility(View.VISIBLE);
    }

    private void buildFilterRows() {
        binding.filterContainer.removeAllViews();
        addFilterRow("地区", currentRule.getAreaPairs(), value -> {
            area = value;
            loadContent(true);
        });
        addFilterRow("年份", currentRule.getYearPairs(), value -> {
            year = value;
            loadContent(true);
        });
        addFilterRow("类型", currentRule.getSortPairs(), value -> {
            sort = value;
            loadContent(true);
        });
    }

    private void addFilterRow(String label, List<String[]> pairs, ChipAdapter.OnPick pick) {
        if (pairs == null || pairs.isEmpty()) return;
        // 8.83 同款：筛选行带 ">" 展开/收起，默认收起只显示一行
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(4), dp(8));
        
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF101216);
        tv.setTextSize(13);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        header.addView(tv, labelLp);
        
        // 已选项显示
        TextView tvSelected = new TextView(this);
        tvSelected.setText("全部");
        tvSelected.setTextColor(0xFF8A8F98);
        tvSelected.setTextSize(12);
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        header.addView(tvSelected, selLp);
        
        // ">" 展开指示器
        TextView tvArrow = new TextView(this);
        tvArrow.setText(">");
        tvArrow.setTextColor(0xFF8A8F98);
        tvArrow.setTextSize(14);
        tvArrow.setPadding(dp(8), 0, dp(8), 0);
        header.addView(tvArrow);
        
        container.addView(header);
        
        // 选项行（默认收起）
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        List<String[]> all = new ArrayList<>();
        all.add(new String[]{"全部", ""});
        all.addAll(pairs);
        final boolean[] expanded = {false};
        rv.setVisibility(View.GONE);
        
        ChipAdapter adapter = new ChipAdapter(all, 0, value -> {
            // 更新已选项显示
            for (String[] p : all) {
                if (p[1].equals(value)) {
                    tvSelected.setText(p[0]);
                    break;
                }
            }
            pick.onPick(value);
        });
        rv.setAdapter(adapter);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        rvLp.topMargin = dp(4);
        rvLp.bottomMargin = dp(4);
        container.addView(rv, rvLp);
        
        // 点击头部展开/收起
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            rv.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            tvArrow.setText(expanded[0] ? "v" : ">");
            tvArrow.setRotation(expanded[0] ? 90 : 0);
        });
        
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        binding.filterContainer.addView(container, containerLp);
    }

    private HkRouter getRouter() throws Exception {
        if (router == null) router = new HkRouter(currentRule);
        // 图片 @js= 求值依赖当前规则的 $.require/pages，登记当前 runtime（8.83 JSEngine.getInstance() 同款语义）
        com.fongmi.android.tv.api.hk.HkImage.setCurrentRuntime(router.getEngine().getJsRuntime());
        return router;
    }

    /** 当前规则页 URL（8.83 GlideUtil.getGlideUrl 的 ruleUrl 参数：自动 Referer 用）。 */
    private String hkRuleUrl() {
        return currentRule == null ? null : currentRule.getUrl();
    }

    private int contentGen = 0;

    private void loadContent(boolean reset) {
        if (loading || currentRule == null) return;
        loading = true;
        final int gen = ++contentGen;
        if (reset) {
            page = 1;
            noMore = false;
            videos.clear();
            contentAdapter.refreshGroups();
            contentAdapter.notifyDataSetChanged();
            binding.tvContentEmpty.setVisibility(View.GONE);
            binding.rvVideos.setVisibility(View.GONE);
            binding.loadingContent.setVisibility(View.VISIBLE);
        }
        updateFooter();
        final int p = page;
        final String c = cls, a = area, y = year, s = sort;
        new Thread(() -> {
            List<HkItem> list;
            try {
                list = getRouter().home(p, c, a, y, s);
            } catch (Throwable e) {
                list = new ArrayList<>();
            }
            final List<HkItem> result = list;
            App.post(() -> {
                if (gen != contentGen) return; // 规则已切换/被更新的加载覆盖，丢弃过期结果
                loading = false;
                binding.swipeContent.setRefreshing(false);
                binding.loadingContent.setVisibility(View.GONE);
                // V2：把导航类条目（scroll_button/flex_button 分类）拆出来做顶部 tab，
                // 分隔块（blank_block/line_blank/line）丢弃，其余内容条目按 col_type
                // 由 ContentAdapter 多 viewType 渲染（视频/文本/图片/输入框等）。
                List<HkItem> tabs = new ArrayList<>();
                List<HkItem> contents = new ArrayList<>();
                for (HkItem it : result) {
                    String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
                    if ("scroll_button".equals(ct) || "flex_button".equals(ct)) tabs.add(it);
                    else if ("blank_block".equals(ct) || "big_blank_block".equals(ct)
                            || "big_big_blank_block".equals(ct) || "line_blank".equals(ct) || "line".equals(ct)) continue;
                    else contents.add(it);
                }
                if (p == 1 && !tabs.isEmpty()) buildDynamicTabs(tabs);
                if (contents.isEmpty()) {
                    if (p == 1 && videos.isEmpty()) {
                        // 空态：若引擎记录了 JS 失败原因则展示，否则保持原文案。
                        // 之前任何失败都只显示"加载失败"，根因只能靠 debug 日志，用户无法反馈。
                        String hkErr = null;
                        try {
                            hkErr = getRouter().getEngine().getError();
                        } catch (Throwable ignored) {
                        }
                        if (!TextUtils.isEmpty(hkErr)) {
                            String e = hkErr.trim();
                            if (e.length() > 80) e = e.substring(0, 80);
                            binding.tvContentEmpty.setText("加载失败：" + e + "，点我重试");
                        } else {
                            binding.tvContentEmpty.setText("加载失败，点我重试");
                        }
                        binding.tvContentEmpty.setVisibility(View.VISIBLE);
                        binding.rvVideos.setVisibility(View.GONE);
                    } else {
                        noMore = true;
                    }
                } else {
                    videos.addAll(contents);
                    page = p + 1;
                    binding.rvVideos.setVisibility(View.VISIBLE);
                    contentAdapter.refreshGroups();
                    contentAdapter.notifyDataSetChanged();
                }
                updateFooter();
            });
        }).start();
    }

    private void updateFooter() {
        if (videos.isEmpty()) {
            binding.tvFooter.setVisibility(View.GONE);
            return;
        }
        binding.tvFooter.setVisibility(View.VISIBLE);
        if (loading) binding.tvFooter.setText("加载中…");
        else if (noMore) binding.tvFooter.setText("已加载全部");
        else binding.tvFooter.setText("");
    }

    // ================= V3 搜索 =================

    private void initSearchView() {
        GridLayoutManager grid = new GridLayoutManager(this, 3);
        binding.rvSearchResults.setLayoutManager(grid);
        binding.rvSearchResults.addItemDecoration(new GridSpace());
        searchAdapter = new VideoAdapter(searchResults, false);
        binding.rvSearchResults.setAdapter(searchAdapter);
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        // 搜索页磨砂风格：搜索条真 blur（API 31+，低版本半透明兜底）
        FrostedGlass.blurBackground(binding.searchBar);
        binding.btnBackSearch.setOnClickListener(v -> onBackInvoked());
        binding.btnClear.setOnClickListener(v -> binding.etKeyword.setText(""));
        binding.btnClearHistory.setOnClickListener(v -> {
            getPrefs().edit().remove(KEY_HIST).apply();
            refreshHistory();
        });
        binding.etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.btnClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String kw = v.getText().toString().trim();
                // 小程序列表入口 = 跨小程序内容搜索；内容页内 = 单规则搜索
                if (searchDualMode) doCrossSearch(kw);
                else doSearch(kw);
                return true;
            }
            return false;
        });
        binding.rvSearchResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || searchLoading || searchNoMore || keyword.isEmpty()) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= searchAdapter.getItemCount() - 3) {
                    doSearchPage();
                }
            }
        });
    }

    private void openSearch() {
        searchDualMode = false;
        keyword = "";
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
        binding.etKeyword.setText("");
        binding.etKeyword.setHint("输入关键词搜索");
        binding.tvSearchEmpty.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        refreshHistory();
        binding.historySection.setVisibility(View.VISIBLE);
        pushView(V_SEARCH);
        binding.etKeyword.requestFocus();
    }

    /**
     * 点 🔍：顶栏下方展开 inline 磨砂搜索条，输入实时过滤首页小程序卡片，不跳页；再点收起。
     */
    private void toggleInlineSearch() {
        if (binding.inlineSearchBar.getVisibility() == View.VISIBLE) {
            closeInlineSearch();
        } else {
            binding.inlineSearchBar.setVisibility(View.VISIBLE);
            updateRulesTopPadding();
            binding.etInlineSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(binding.etInlineSearch, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** 关闭 inline 搜索：清空关键词、恢复全部卡片、收起键盘。 */
    private void closeInlineSearch() {
        binding.inlineSearchBar.setVisibility(View.GONE);
        binding.etInlineSearch.setText("");
        ruleKeyword = "";
        ruleAdapter.rebuildSections();
        ruleAdapter.notifyDataSetChanged();
        updateRulesTopPadding();
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(binding.etInlineSearch.getWindowToken(), 0);
        binding.etInlineSearch.clearFocus();
    }

    /**
     * 长按 🔍：整页内容搜索（只留"搜内容"模式，分段已去掉）。
     * 正式逻辑走 HkJsRuntime.be()/batchExecute 并发扇出到各小程序；引擎未就绪前显示"引擎升级中"。
     */
    private void openContentSearch() {
        searchDualMode = true;
        binding.etKeyword.setText("");
        binding.etKeyword.setHint("搜索全部小程序的内容");
        binding.historySection.setVisibility(View.GONE);
        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
        binding.tvSearchEmpty.setText("输入关键词，搜索全部小程序的内容");
        binding.rvSearchResults.setVisibility(View.GONE);
        pushView(V_SEARCH);
        binding.etKeyword.requestFocus();
    }

    /**
     * 搜内容：跨小程序内容搜索。
     * 正式逻辑走 HkJsRuntime.be()/batchExecute 并发扇出到各小程序；引擎未就绪前显示"引擎升级中"。
     */
    private void doCrossSearch(String kw) {
        binding.rvSearchResults.setVisibility(View.GONE);
        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
        if (kw.isEmpty()) {
            binding.tvSearchEmpty.setText("输入关键词，搜索全部小程序的内容");
            return;
        }
        if (!com.fongmi.android.tv.api.hk.HkJsRuntime.BATCH_EXECUTE_READY) {
            binding.tvSearchEmpty.setText("引擎升级中，跨小程序内容搜索稍后上线");
            return;
        }
        // TODO(D 阶段)：be()/batchExecute 就绪后，在此并发调用各规则 search 并聚合结果（标注来源小程序）。
    }

    private void doSearch(String kw) {
        if (kw.isEmpty() || currentRule == null) return;
        keyword = kw;
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchAdapter.notifyDataSetChanged();
        binding.tvSearchEmpty.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        binding.historySection.setVisibility(View.GONE);
        saveHistory(kw);
        doSearchPage();
    }

    private void doSearchPage() {
        if (searchLoading || currentRule == null || keyword.isEmpty()) return;
        searchLoading = true;
        final int p = searchPage;
        final String kw = keyword;
        new Thread(() -> {
            List<HkItem> list;
            try {
                list = getRouter().search(kw, p);
            } catch (Throwable e) {
                list = new ArrayList<>();
            }
            final List<HkItem> result = list;
            App.post(() -> {
                searchLoading = false;
                if (result.isEmpty()) {
                    if (p == 1 && searchResults.isEmpty()) {
                        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
                        binding.rvSearchResults.setVisibility(View.GONE);
                    } else {
                        searchNoMore = true;
                    }
                } else {
                    searchResults.addAll(result);
                    searchPage = p + 1;
                    searchAdapter.notifyDataSetChanged();
                }
            });
        }).start();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private List<String> loadHistory() {
        List<String> out = new ArrayList<>();
        String s = getPrefs().getString(KEY_HIST, "");
        if (!s.isEmpty()) {
            for (String part : s.split("")) {
                if (!part.isEmpty()) out.add(part);
            }
        }
        return out;
    }

    private void saveHistory(String kw) {
        List<String> hist = loadHistory();
        hist.remove(kw);
        hist.add(0, kw);
        while (hist.size() > 20) hist.remove(hist.size() - 1);
        getPrefs().edit().putString(KEY_HIST, TextUtils.join("", hist)).apply();
        refreshHistory();
    }

    private void refreshHistory() {
        List<String> hist = loadHistory();
        List<String[]> pairs = new ArrayList<>();
        for (String h : hist) pairs.add(new String[]{h, h});
        binding.rvHistory.setAdapter(new ChipAdapter(pairs, -1, value -> doSearch(value)));
        binding.historySection.setVisibility(hist.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ================= V4 详情 =================

    private void initDetailView() {
        binding.btnBackDetail.setOnClickListener(v -> onBackInvoked());
        binding.tvDetailTitle.setSelected(true);
        binding.rvDetailLines.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        GridLayoutManager epGrid = new GridLayoutManager(this, 4);
        binding.rvDetailEpisodes.setLayoutManager(epGrid);
        binding.rvDetailEpisodes.setNestedScrollingEnabled(false);
        binding.rvDetailEpisodes.addItemDecoration(new EpSpace());
        episodeAdapter = new EpisodeAdapter();
        binding.rvDetailEpisodes.setAdapter(episodeAdapter);
        binding.tvDetailEmpty.setOnClickListener(v -> {
            if (detailItem != null) openDetail(detailItem, detailFromSearch);
        });
    }

    private void openDetail(HkItem item, boolean fromSearch) {
        openDetail(item, fromSearch, false);
    }

    /**
     * 点封面后的下方小字"加载中"提示（海阔原版 toast 风格，不挡封面）。
     * 进播放器/推 V4/报错时自动消失。
     */
    private void showLoadingHint() {
        hideLoadingHint();
        loadingHint = android.widget.Toast.makeText(this, "加载中…", android.widget.Toast.LENGTH_LONG);
        loadingHint.show();
    }

    private void hideLoadingHint() {
        if (loadingHint != null) {
            loadingHint.cancel();
            loadingHint = null;
        }
    }

    /**
     * V4 详情。
     *
     * @param lazyPrecheck true=条目 URL 自带 {@code @lazyRule=}（如粉嫩小BB点封面即播）：
     *                     先后台求值，不预推 V4；若判定为直接播放则根本不进 V4，
     *                     避免 V4 loading 闪一下。非直接播放时再补推 V4 展示结果。
     */
    private void openDetail(HkItem item, boolean fromSearch, boolean lazyPrecheck) {
        // 官方 clickItem：@rule= 条目进子列表页（dealRule），不进 V4；各调用方统一在此拦截。
        // 顺序与官方一致：只看 @rule= 前段是否含 @lazyRule=，含则走 lazy 预检不进子列表。
        if (item != null && !TextUtils.isEmpty(item.getUrl())) {
            String u = item.getUrl().trim();
            // P1：hiker://page/ 子页面走 openPage（官方 toNextPage），不进 V4
            if (u.startsWith("hiker://page/") && openPage(item, u)) return;
            int ar = u.indexOf("@rule=");
            if (ar >= 0 && !u.substring(0, ar).contains("@lazyRule=")) {
                if (openSubList(item, u)) return;
            }
        }
        detailItem = item;
        detailFromSearch = fromSearch;
        currentDetail = null;
        currentLine = null;
        episodes.clear();
        if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        if (!lazyPrecheck) {
            binding.tvDetailTitle.setText(item.getTitle());
            binding.detailScroll.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.GONE);
            binding.tvDetailLoading.setVisibility(View.VISIBLE);
            pushView(V_DETAIL);
        } else {
            // 直接播放预检（条目 URL 自带 @lazyRule=）：后台求值，下方小字"加载中"提示
            // （海阔原版 toast 风格，不挡封面）；求值完成后直接进播放器
            showLoadingHint();
        }
        new Thread(() -> {
            HkDetail detail;
            try {
                detail = getRouter().detail(item.getUrl(), item, fromSearch);
            } catch (Throwable e) {
                detail = null;
            }
            final HkDetail result = detail;
            App.post(() -> {
                hideLoadingHint();
                if (lazyPrecheck) binding.loadingContent.setVisibility(View.GONE);
                else binding.tvDetailLoading.setVisibility(View.GONE);
                // 纯文本结果（文本按钮的 js: 求值返回文本）：弹窗显示，不进 V4/播放
                String textResult = result == null ? "" : result.getTextResult();
                if (!TextUtils.isEmpty(textResult)) {
                    if (!lazyPrecheck) onBackInvoked(); // 弹出已推的 V4 空 loading
                    else binding.loadingContent.setVisibility(View.GONE);
                    new AlertDialog.Builder(HkPageActivity.this)
                            .setMessage(textResult)
                            .setPositiveButton("确定", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                // 分类切换（官方 refreshPage 语义：putMyVar 后重刷列表，不进 V4）
                if (result != null && result.isTabSwitch()) {
                    if (!lazyPrecheck) onBackInvoked(); // 弹出已推的 V4 空 loading
                    loadContent(true);
                    return;
                }
                // lazyRule 解析失败（@lazyRule= 求值无结果）：不推空 V4，直接报错
                // （避免"0条线路 · 共0集"的空详情页；用户点的是视频，期望播放而非空 V4）
                if (result != null && result.isLazyParseFailed()) {
                    android.widget.Toast.makeText(HkPageActivity.this, "解析失败，换一条试试", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String direct = result == null ? "" : result.getDirectPlayUrl();
                if (!TextUtils.isEmpty(direct)) {
                    // 直接播放：若已推 V4 先弹出，保证播放器返回时回到列表
                    if (!lazyPrecheck) onBackInvoked();
                    // 预热播放路由：后台提前做 QuickJS 初始化+preRule，
                    // 播放器调 playerContent 时直接命中缓存，不用再等几秒初始化
                    SiteApi.warmHkRouter(currentRule == null ? "" : currentRule.getTitle());
                    startHkDirectPlay(item, direct);
                    return;
                }
                // dealWithUrl 分流（官方：@lazyRule= 解析后不进 V4）
                String dealKind = result == null ? "" : result.getDealKind();
                if (!TextUtils.isEmpty(dealKind)) {
                    if (!lazyPrecheck) onBackInvoked();
                    else binding.loadingContent.setVisibility(View.GONE);
                    handleDealUrl(dealKind, result.getDealUrl(), item);
                    return;
                }
                if (lazyPrecheck) {
                    // 预检非直接播放：补推 V4 再展示（复用已算出的 result，不二次求值）
                    binding.tvDetailTitle.setText(item.getTitle());
                    binding.detailScroll.setVisibility(View.GONE);
                    binding.tvDetailEmpty.setVisibility(View.GONE);
                    binding.tvDetailLoading.setVisibility(View.GONE);
                    pushView(V_DETAIL);
                }
                showDetailResult(result);
            });
        }).start();
    }

    /**
     * 直接播放：只把被点击的这 1 条目作为选集传给播放器。
     * 列表页其他视频不是当前视频的选集，不能伪装成多集（否则出现 1-20/21-40 等假选集）。
     */
    private void startHkDirectPlay(HkItem item, String directUrl) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            if (item != null && !TextUtils.isEmpty(item.getUrl())) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", stripHtml(item.getTitle()));
                o.put("url", item.getUrl());
                o.put("pic", item.getPic() == null ? "" : item.getPic());
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        VideoActivity.startHkPlay(HkPageActivity.this,
                currentRule == null ? "" : currentRule.getTitle(), "默认",
                directUrl, item.getTitle(), item.getTitle(), item.getPic(),
                arr.toString(), 0);
    }

    /**
     * dealWithUrl 分流处理（官方 ArticleListFragment.dealWithUrl）：
     * pics=漫画图片列表 / x5=webview规则 / web=网页 / image=图片查看 / magnet=分享。
     */
    private void handleDealUrl(String kind, String url, HkItem item) {
        if (TextUtils.isEmpty(url)) return;
        try {
            switch (kind) {
                case "pics": {
                    // pics://url1&&url2... → 图片列表查看
                    String raw = url.replaceFirst("(?i)^pics://", "");
                    String[] parts = raw.split("&&");
                    java.util.ArrayList<String> urls = new java.util.ArrayList<>();
                    for (String p : parts) {
                        if (!TextUtils.isEmpty(p.trim())) urls.add(p.trim());
                    }
                    if (urls.isEmpty()) {
                        android.widget.Toast.makeText(this, "无图片", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    com.fongmi.android.tv.utils.ImgUtil.load(item.getTitle(), urls.get(0), iv, false, 0, 0, hkRuleUrl());
                    new AlertDialog.Builder(this)
                            .setTitle(stripHtml(item.getTitle()))
                            .setView(iv)
                            .setPositiveButton("关闭", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                case "x5": {
                    // x5://url → 内嵌 webview 规则页
                    String target = url.replaceFirst("(?i)^x5://", "").trim();
                    if (!target.isEmpty()) showWebViewDialog(target);
                    return;
                }
                case "web":
                    showWebViewDialog(url);
                    return;
                case "image": {
                    android.widget.ImageView iv = new android.widget.ImageView(this);
                    com.fongmi.android.tv.utils.ImgUtil.load(item.getTitle(), url, iv, false, 0, 0, hkRuleUrl());
                    new AlertDialog.Builder(this)
                            .setView(iv)
                            .setPositiveButton("关闭", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                case "magnet": {
                    android.content.Intent it2 = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url));
                    try {
                        startActivity(android.content.Intent.createChooser(it2, "选择应用打开"));
                    } catch (Throwable e) {
                        android.widget.Toast.makeText(this, "无应用可打开", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                default:
                    break;
            }
        } catch (Throwable e) {
            android.util.Log.d("HkPage", "handleDealUrl failed: " + e.getMessage());
        }
        // 未知种类：回退 V4
        pushView(V_DETAIL);
        showDetailResult(null);
    }

    /** V4 结果展示：无线路但有标题/封面/简介时仍渲染，不直接"加载失败"。 */
    private void showDetailResult(HkDetail result) {
        if (result == null || (result.isEmpty() && !result.hasBasicInfo())) {
            binding.detailScroll.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.VISIBLE);
            // 空态显示引擎记录的 JS 真实错误（与列表页一致），方便用户截图反馈
            try {
                String hkErr = getRouter().getEngine().getError();
                if (!TextUtils.isEmpty(hkErr)) {
                    String e = hkErr.trim();
                    if (e.length() > 80) e = e.substring(0, 80);
                    binding.tvDetailEmpty.setText("加载失败：" + e + "，点我重试");
                } else {
                    binding.tvDetailEmpty.setText("加载失败，点我重试");
                }
            } catch (Throwable ignored) {
                binding.tvDetailEmpty.setText("加载失败，点我重试");
            }
        } else {
            bindDetail(result);
        }
    }

    private void bindDetail(HkDetail detail) {
        currentDetail = detail;
        // V4 已展示：用户很可能马上点选集，提前预热播放路由，省掉播放时的初始化等待
        SiteApi.warmHkRouter(currentRule == null ? "" : currentRule.getTitle());
        binding.detailScroll.setVisibility(View.VISIBLE);
        binding.tvDetailEmpty.setVisibility(View.GONE);
        binding.tvDetailTitle.setText(detail.getTitle());
        binding.tvDetailName.setText(detail.getTitle());
        ImgUtil.load(detail.getTitle(), detail.getPic(), binding.ivDetailCover, hkRuleUrl());
        int epCount = 0;
        for (HkDetail.Line l : detail.getLines()) epCount += l.getEpisodes().size();
        String meta;
        if (detail.getLines().isEmpty()) {
            // 无线路时若引擎记录了 JS 失败原因，展示真实错误（之前只显示"0条线路"，用户无法反馈根因）
            String hkErr = null;
            try {
                hkErr = getRouter().getEngine().getError();
            } catch (Throwable ignored) {
            }
            if (!TextUtils.isEmpty(hkErr)) {
                String e = hkErr.trim();
                if (e.length() > 80) e = e.substring(0, 80);
                meta = "加载失败：" + e + "，点我重试";
            } else {
                meta = (currentRule == null ? "" : currentRule.getTitle() + " · ")
                        + "0条线路 · 共0集";
            }
        } else {
            meta = (currentRule == null ? "" : currentRule.getTitle() + " · ")
                    + detail.getLines().size() + "条线路 · 共" + epCount + "集";
        }
        binding.tvDetailMeta.setText(meta);
        // 错误时点 meta 文字可重试（与列表页"点我重试"一致）
        final boolean isDetailErr = detail.getLines().isEmpty() && meta.startsWith("加载失败");
        binding.tvDetailMeta.setOnClickListener(isDetailErr && detailItem != null
                ? v -> openDetail(detailItem, detailFromSearch) : null);
        binding.tvDetailMeta.setClickable(isDetailErr);
        String content = detail.getContent();
        boolean hasContent = !TextUtils.isEmpty(content);
        binding.tvDetailIntroLabel.setVisibility(hasContent ? View.VISIBLE : View.GONE);
        binding.tvDetailContent.setVisibility(hasContent ? View.VISIBLE : View.GONE);
        if (hasContent) binding.tvDetailContent.setText(content);
        renderCopyButtons(detail.getCopyItems());
        List<HkDetail.Line> lines = detail.getLines();
        boolean hasLines = !lines.isEmpty();
        // 无线路时隐藏线路/选集区，只展示标题/封面/简介（有基本信息才走到这里）
        binding.tvDetailLineLabel.setVisibility(View.GONE);
        binding.rvDetailLines.setVisibility(View.GONE);
        if (hasLines) {
            List<String[]> pairs = new ArrayList<>();
            for (HkDetail.Line l : lines) pairs.add(new String[]{l.getName(), l.getName()});
            binding.rvDetailLines.setAdapter(new ChipAdapter(pairs, 0, value -> {
                if (currentDetail == null) return;
                for (HkDetail.Line l : currentDetail.getLines()) {
                    if (l.getName().equals(value)) {
                        currentLine = l;
                        refreshEpisodes();
                        break;
                    }
                }
            }));
            boolean multiLine = lines.size() > 1;
            binding.tvDetailLineLabel.setVisibility(multiLine ? View.VISIBLE : View.GONE);
            binding.rvDetailLines.setVisibility(multiLine ? View.VISIBLE : View.GONE);
            currentLine = lines.get(0);
            refreshEpisodes();
        } else {
            currentLine = null;
            if (episodeAdapter != null) episodeAdapter.notifyDataSetChanged();
        }
        binding.detailScroll.scrollTo(0, 0);
    }

    /**
     * V4 复制按钮行：简介下方、选集上方横向排列，每 copy 条目一个白色描边按钮；
     * 点击复制 getText() 到剪贴板并 Toast"已复制"。无条目时整行隐藏。
     */
    private void renderCopyButtons(List<HkDetail.CopyItem> items) {
        binding.copyRow.removeAllViews();
        if (items == null || items.isEmpty()) {
            binding.copyScroll.setVisibility(View.GONE);
            return;
        }
        binding.copyScroll.setVisibility(View.VISIBLE);
        for (HkDetail.CopyItem ci : items) {
            TextView btn = new TextView(this);
            btn.setText(ci.getName());
            btn.setTextColor(0xFF1A1D24);
            btn.setTextSize(13);
            btn.setGravity(Gravity.CENTER);
            btn.setMaxLines(1);
            btn.setEllipsize(TextUtils.TruncateAt.END);
            int padH = dp(16), padV = dp(9);
            btn.setPadding(padH, padV, padH, padV);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFFFFF);
            bg.setStroke(dp(1), 0xFFE0E4EA);
            bg.setCornerRadius(dp(10));
            btn.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            binding.copyRow.addView(btn, lp);
            btn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("hk_copy", ci.getText()));
                    Notify.show("已复制");
                }
            });
        }
    }

    private void refreshEpisodes() {
        episodes.clear();
        if (currentLine != null) episodes.addAll(currentLine.getEpisodes());
        episodeAdapter.notifyDataSetChanged();
        binding.tvDetailEpLabel.setText(episodes.isEmpty() ? "选集" : "选集（" + episodes.size() + "）");
    }

    private class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            TextView tv;

            Holder(View v) {
                super(v);
                tv = v.findViewById(R.id.tv_episode);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_episode, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            HkDetail.Episode ep = episodes.get(position);
            h.tv.setText(ep.getName());
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || currentRule == null || currentLine == null) return;
                HkDetail.Episode e = episodes.get(pos);
                String title = detailItem != null && !TextUtils.isEmpty(detailItem.getTitle())
                        ? detailItem.getTitle()
                        : (currentDetail == null ? "" : currentDetail.getTitle());
                String pic = detailItem != null ? detailItem.getPic()
                        : (currentDetail == null ? "" : currentDetail.getPic());
                VideoActivity.startHkPlay(HkPageActivity.this,
                        currentRule.getTitle(), currentLine.getName(),
                        e.getUrl(), e.getName(), title, pic);
            });
        }

        @Override
        public int getItemCount() {
            return episodes.size();
        }
    }

    /** 选集 4 列网格间距。 */
    private class EpSpace extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            int col = pos % 4;
            int h = dp(8);
            int v = dp(8);
            outRect.left = col == 0 ? 0 : h / 2;
            outRect.right = col == 3 ? 0 : h / 2;
            if (pos >= 4) outRect.top = v;
        }
    }

    // ================= 通用组件 =================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 内容网格间距：标准均分公式，保证 N 列总宽严格等于 RecyclerView 宽、不溢出。
     * 每列 i（0-based）：left = i*S/N，right = S-(i+1)*S/N；列间距 S=12dp。
     * 外边距由父容器 16dp 提供，decoration 不再加外边距。
     * 兼容 60-span 内容网格与 3-span 搜索网格。
     */
    private class GridSpace extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            int total = 60, span = 60, spanIndex = 0;
            if (parent.getLayoutManager() instanceof GridLayoutManager) {
                total = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
            }
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();
            if (lp instanceof GridLayoutManager.LayoutParams) {
                GridLayoutManager.LayoutParams glp = (GridLayoutManager.LayoutParams) lp;
                span = glp.getSpanSize();
                if (glp.getSpanIndex() >= 0) spanIndex = glp.getSpanIndex();
            }
            if (span >= total) {
                outRect.left = 0;
                outRect.right = 0;
                if (pos > 0) outRect.top = dp(12);
                return;
            }
            int perRow = Math.max(1, total / Math.max(1, span));
            int col = span > 0 ? spanIndex / span : 0;
            if (col < 0) col = 0;
            if (col >= perRow) col = perRow - 1;
            int s = dp(12);
            outRect.left = col * s / perRow;
            outRect.right = s - (col + 1) * s / perRow;
            if (pos >= perRow) outRect.top = dp(12);
        }
    }

    private class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.Holder> {
        private final List<HkItem> items;
        private final boolean fromContent;
        private final int imgHeight;

        VideoAdapter(List<HkItem> items, boolean fromContent) {
            this.items = items;
            this.fromContent = fromContent;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int itemW = (dm.widthPixels - dp(16) * 2 - dp(12) * 2) / 3;
            imgHeight = itemW * 3 / 2;
        }

        class Holder extends RecyclerView.ViewHolder {
            ImageView cover;
            TextView title, desc;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.iv_cover);
                title = v.findViewById(R.id.tv_title);
                desc = v.findViewById(R.id.tv_desc);
                View container = v.findViewById(R.id.cover_container);
                if (container != null) {
                    ViewGroup.LayoutParams lp = container.getLayoutParams();
                    lp.height = imgHeight;
                    container.setLayoutParams(lp);
                } else {
                    ViewGroup.LayoutParams lp = cover.getLayoutParams();
                    lp.height = imgHeight;
                    cover.setLayoutParams(lp);
                }
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_video, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            HkItem item = items.get(position);
            h.title.setText(item.getTitle());
            h.desc.setText(item.getDesc());
            h.desc.setVisibility(TextUtils.isEmpty(item.getDesc()) ? View.GONE : View.VISIBLE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || currentRule == null) return;
                HkItem it = items.get(pos);
                String mu = it.getUrl() == null ? "" : it.getUrl();
                // 动作协议优先：@inputRule= 口令弹窗、toast:// 等；之前缺这行导致口令点了没反应
                if (handleActionUrl(mu.trim(), it)) return;
                // P1：hiker://page/ 子页面走 openPage，不进 V4
                if (mu.trim().startsWith("hiker://page/") && openPage(it, mu)) return;
                openDetail(it, !fromContent, mu.contains("@lazyRule="));
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** col_type 小写形式（js 结果里填充，未设置则空串）。 */
    private String colTypeOf(HkItem it) {
        return it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
    }

    /** 去 HTML 标签（col_type 标题里常带 <font> 等），逻辑同 buildDynamicTabs。 */
    private String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]*>", "").replace("‘", "").replace("’", "")
                .replace("“", "").replace("”", "").trim()
                // 规则里常把标题包在引号里（"""最新上传"""），去掉首尾引号
                .replaceAll("^\"+|\"+$", "").replaceAll("^'+|'+$", "").trim();
    }

    /**
     * 标题颜色标记（官方 TextViewUtils.setSpanText 逻辑）：
     * ""xxx""（U+201C×2 开 / U+201D×2 合）→ 红色 #FF0000
     * ''xxx''（U+2018×2 开 / U+2019×2 合）→ 橙色 #f0983c
     * split 内联处理，标题中间也能变色；返回带 <font> 标签的 HTML，无标记返回 null。
     */
    private String applyTitleMarkHtml(String raw) {
        if (raw == null) return null;
        String r = applyMark(raw, "““", "””", "#FF0000");
        if (r != null) return r;
        // 兼容 ASCII 三引号 """xxx"""（部分规则用直引号，如口令弹窗标题）
        r = applyMark(raw, "\"\"\"", "\"\"\"", "#FF0000");
        if (r != null) return r;
        return applyMark(raw, "‘‘", "’’", "#f0983c");
    }

    /** 对文本做开/合标记的 split 内联变色，找到完整标记返回 HTML，否则返回 null。 */
    private String applyMark(String text, String open, String close, String color) {
        if (text == null || !text.contains(open)) return null;
        String[] parts = text.split(java.util.regex.Pattern.quote(open), -1);
        if (parts.length < 2) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0]);
        boolean marked = false;
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            int idx = seg.indexOf(close);
            if (idx >= 0) {
                marked = true;
                sb.append("<font color=\"").append(color).append("\">")
                        .append(seg, 0, idx)
                        .append("</font>")
                        .append(seg.substring(idx + close.length()));
            } else {
                // 无闭合标记：把 open 还原，避免吞字
                sb.append(open).append(seg);
            }
        }
        return marked ? sb.toString() : null;
    }

    private CharSequence titleSpan(String raw) {
        if (raw == null) raw = "";
        String markedHtml = applyTitleMarkHtml(raw);
        if (markedHtml != null) {
            try {
                return Html.fromHtml(markedHtml, Html.FROM_HTML_MODE_LEGACY);
            } catch (Throwable t) {
                // fallback 到 strip
            }
        }
        return stripHtml(raw);
    }

    /** col_type → viewType：空/movie_3/未知 → 视频卡片（官方 getItemTypeByCode 未知回退 MOVIE_3）。 */
    private int contentTypeOf(HkItem it) {
        String ct = colTypeOf(it);
        switch (ct) {
            case "text_center_1":
            case "text_1":
            case "long_text":
            case "header":
            case "footer":
                return ContentAdapter.T_TEXT;
            case "text_icon":
                return ContentAdapter.T_TEXT_ICON;
            case "rich_text":
                return ContentAdapter.T_RICH;
            case "text_2":
            case "text_3":
            case "text_4":
            case "text_5":
                return ContentAdapter.T_COLS;
            case "avatar":
                return ContentAdapter.T_AVATAR;
            case "input":
                return ContentAdapter.T_INPUT;
            case "search":
                return ContentAdapter.T_SEARCH;
            case "x5_webview_single":
                return ContentAdapter.T_WEB;
            case "button_group":
                return ContentAdapter.T_BUTTONS;
            case "card_pic_1":
            case "card_pic_2":
            case "card_pic_2_2":
            case "card_pic_2_2_left":
            case "card_pic_3":
            case "card_pic_3_center":
                return ContentAdapter.T_CARD;
            case "":
            case "movie_3":
                return ContentAdapter.T_VIDEO;
            case "movie_1":
                // 官方 movie_1 span=60 整宽左图右文
                return ContentAdapter.T_MOVIE_LEFT_PIC;
            case "movie_1_left_pic":
                // 官方 movie_1_left_pic span=60 整宽左图右文（同 movie_1）
                return ContentAdapter.T_MOVIE_LEFT_PIC;
            case "movie_3_marquee":
                // 官方 movie_3_marquee span=20 三列，标题跑马灯（bindVideo 里按 col_type 开 marquee）
                return ContentAdapter.T_VIDEO;
            case "movie_2":
                // 官方 movie_2 span=30 两列（走 T_VIDEO，spanFor 按 col_type 给 30）
                return ContentAdapter.T_VIDEO;
            default:
                if (ct.startsWith("icon")) {
                    // 8.83 各 icon_* 子类型走独立布局（smali 实测 itemType→layout 映射），
                    // 之前全挤在一个通用布局里是"图标错位"的根因。
                    if ("icon_3_fill".equals(ct) || "icon_3_round_fill".equals(ct)) return ContentAdapter.T_ICON_FILL;
                    if ("icon_2_round".equals(ct)) return ContentAdapter.T_ICON_2_ROUND;
                    if ("icon_2".equals(ct)) return ContentAdapter.T_ICON_2;
                    if ("icon_4_card".equals(ct)) return ContentAdapter.T_ICON_4_CARD;
                    if ("icon_small_3".equals(ct)) return ContentAdapter.T_ICON_3_SMALL;
                    if ("icon_1_search".equals(ct)) return ContentAdapter.T_ICON_1_SEARCH;
                    if ("icon_1_left_pic".equals(ct)) return ContentAdapter.T_ICON_1_LEFT_PIC;
                    if ("icon_round_4".equals(ct)) return ContentAdapter.T_ICON_ROUND;
                    if ("icon_round_small_4".equals(ct)) return ContentAdapter.T_ICON_ROUND_SMALL;
                    if ("icon_small_4".equals(ct) || "icon_5".equals(ct) || "icon_5_no_crop".equals(ct))
                        return ContentAdapter.T_ICON_4_SMALL;
                    return ContentAdapter.T_ICON; // icon_4
                }
                if (ct.startsWith("pic") || ct.startsWith("card_pic")) return ContentAdapter.T_PIC;
                // movie_1/movie_2 等按视频卡片网格渲染（全宽横向图文待后版）
                if (ct.startsWith("movie")) return ContentAdapter.T_VIDEO;
                return ContentAdapter.T_VIDEO;
        }
    }

    /**
     * V2 非视频条目的统一点击：无 url 的纯展示行无反应；有 url 先过动作协议，
     * 未消费则走 openDetail。条目自带 {@code @lazyRule=} 时用 lazy 预检模式，
     * 直接播放就不推 V4（粉嫩小BB点封面即播，不闪 V4 loading）。
     */
    private void onContentItemClick(HkItem it) {
        if (it == null || currentRule == null || TextUtils.isEmpty(it.getUrl())) return;
        String url = it.getUrl().trim();
        if (handleActionUrl(url, it)) return;
        // P1：hiker://page/ 子页面走 openPage（官方 toNextPage），不进 V4
        if (url.startsWith("hiker://page/") && openPage(it, url)) return;
        // 官方 clickItem 顺序：@rule= 前段里的 @lazyRule= 优先（dealLazyRule），再是 @rule=（dealRule 子列表）
        int ar = url.indexOf("@rule=");
        String preRule = ar >= 0 ? url.substring(0, ar) : url;
        boolean hasLazy = preRule.contains("@lazyRule=");
        // 官方 clickItem：@rule= 条目进子列表页（dealRule 新建 ArticleListRule 打开 FilmListActivity），不进 V4
        if (ar >= 0 && !hasLazy && openSubList(it, url)) return;
        openDetail(it, true, hasLazy);
    }

    /** 子列表状态（@rule= 进子列表时压栈，返回时恢复）。 */
    private static class SubListState {
        HkRule rule;
        String title;
        int page;
        String cls, area, year, sort;
        java.util.List<HkItem> videos;
        int scrollPos;
        int fromView;
    }

    private final java.util.Deque<SubListState> subStack = new java.util.ArrayDeque<>();

    /**
     * 官方 dealRule 语义：@rule= 条目打开子列表页。
     * 解析规则：r = @rule= 后全部；nextRule = r 按 "==>" 切第一段；6 段且非 js: 时第 6 段为 col_type；
     * 新规则 url=@rule= 前的 pageUrl，find_rule=nextRule(+==>后缀)，col_type=条目指定或父规则默认；
     * 其余（ua/group/preRule/title/pages/last_chapter_rule）继承父规则。
     */
    private boolean openSubList(HkItem it, String url) {
        try {
            int ar = url.indexOf("@rule=");
            String pageUrl = url.substring(0, ar).trim();
            // 官方 StringUtil.arrayToString(urlRule, 1, "@rule=")：拼接所有后续段
            String[] parts = url.split("@rule=");
            StringBuilder rb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) rb.append("@rule=");
                rb.append(parts[i]);
            }
            String r = rb.toString().trim();
            if (r.isEmpty()) return false;
            String[] arrow = r.split("==>");
            String nextRule = arrow[0];
            StringBuilder s1 = new StringBuilder();
            for (int i = 1; i < arrow.length; i++) s1.append("==>").append(arrow[i]);
            String[] rules = nextRule.split(";");
            String colType = currentRule.getColType();
            if (rules.length == 6 && !r.startsWith("js:")) {
                colType = rules[5].trim();
                StringBuilder nb = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    if (i > 0) nb.append(";");
                    nb.append(rules[i]);
                }
                nextRule = nb.toString();
            }
            String findRule = nextRule + s1.toString();
            if (findRule.trim().isEmpty()) return false;
            // 压栈保存父列表状态
            SubListState st = new SubListState();
            st.rule = currentRule;
            st.title = binding.tvContentTitle.getText().toString();
            st.page = page;
            st.cls = cls;
            st.area = area;
            st.year = year;
            st.sort = sort;
            st.videos = new java.util.ArrayList<>(videos);
            st.scrollPos = contentGrid == null ? 0 : contentGrid.findFirstVisibleItemPosition();
            st.fromView = stack.isEmpty() ? V_CONTENT : stack.peek();
            subStack.push(st);
            // 派生子规则并加载（官方 dealRule：新规则不带分类导航字段）
            HkRule sub = currentRule.deriveSubRule(pageUrl, findRule, colType);
            sub.clearNav();
            destroyRouterAsync();
            currentRule = sub;
            cls = "";
            area = "";
            year = "";
            sort = "";
            page = 1;
            noMore = false;
            loading = false;
            contentGen++;
            videos.clear();
            String itemTitle = stripHtml(it.getTitle());
            binding.tvContentTitle.setText(itemTitle.isEmpty() ? st.title : itemTitle);
            buildCategoryRow();
            buildFilterRows();
            // 已在列表页时直接替换内容，不重复压栈；从搜索等其他视图进入时才压栈
            if (st.fromView != V_CONTENT) pushView(V_CONTENT);
            else showView(V_CONTENT);
            loadContent(true);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 子列表返回：恢复父列表状态；若从非列表视图进入则回到原视图。 */
    private boolean backSubList() {
        if (subStack.isEmpty()) return false;
        SubListState st = subStack.pop();
        destroyRouterAsync();
        currentRule = st.rule;
        page = st.page;
        cls = st.cls;
        area = st.area;
        year = st.year;
        sort = st.sort;
        noMore = false;
        loading = false;
        contentGen++;
        videos.clear();
        videos.addAll(st.videos);
        binding.tvContentTitle.setText(st.title);
        buildCategoryRow();
        buildFilterRows();
        contentAdapter.refreshGroups();
        contentAdapter.notifyDataSetChanged();
        binding.tvContentEmpty.setVisibility(View.GONE);
        binding.rvVideos.setVisibility(videos.isEmpty() ? View.GONE : View.VISIBLE);
        if (contentGrid != null && st.scrollPos > 0) contentGrid.scrollToPosition(st.scrollPos);
        updateFooter();
        if (st.fromView != V_CONTENT && stack.size() > 1) {
            stack.pop();
            showView(stack.peek());
        }
        return true;
    }

    /**
     * P1：hiker://page/ 子页面（官方 PageParser.getNextPage/toNextPage 语义）。
     * 条目 URL 如 hiker://page/erji1?page=fypage#noHistory# 时，不进 V4 详情，
     * 而是按 path 在规则 pages 里找对应页面，用其 rule/col_type 开新列表页，
     * 条目 extra 注入为新页面的 MY_PARAMS。返回 true 表示已消费。
     */
    private boolean openPage(HkItem it, String url) {
        try {
            if (it == null || currentRule == null || TextUtils.isEmpty(url)) return false;
            String u = url.trim();
            if (!u.startsWith("hiker://page/")) return false;
            // 取 path：hiker://page/erji1?page=..#.. → erji1
            String path = u.substring("hiker://page/".length());
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int h = path.indexOf('#');
            if (h >= 0) path = path.substring(0, h);
            path = path.trim();
            if (path.isEmpty()) return false;
            // 在规则 pages 里找对应页面
            String pagesJson = currentRule.getPages();
            if (TextUtils.isEmpty(pagesJson)) return false;
            org.json.JSONArray pages = new org.json.JSONArray(pagesJson);
            org.json.JSONObject pageObj = null;
            for (int i = 0; i < pages.length(); i++) {
                org.json.JSONObject p = pages.optJSONObject(i);
                if (p != null && path.equals(p.optString("path"))) {
                    pageObj = p;
                    break;
                }
            }
            if (pageObj == null) return false;
            String pageRule = pageObj.optString("rule");
            if (TextUtils.isEmpty(pageRule)) return false;
            String colType = pageObj.optString("col_type");
            if (TextUtils.isEmpty(colType)) colType = currentRule.getColType();
            // 压栈保存父列表状态（复用子列表机制，返回时恢复）
            SubListState st = new SubListState();
            st.rule = currentRule;
            st.title = binding.tvContentTitle.getText().toString();
            st.page = page;
            st.cls = cls;
            st.area = area;
            st.year = year;
            st.sort = sort;
            st.videos = new java.util.ArrayList<>(videos);
            st.scrollPos = contentGrid == null ? 0 : contentGrid.findFirstVisibleItemPosition();
            st.fromView = stack.isEmpty() ? V_CONTENT : stack.peek();
            subStack.push(st);
            // 派生子规则：find_rule 用页面 rule；extra → MY_PARAMS
            HkRule sub = currentRule.deriveSubRule(u, pageRule, colType);
            sub.clearNav();
            // D6：MY_PARAMS 唯一来源是点击条目的 extra（8.83 PageParser.getNextPage 语义：
            // 仅当 extra 非空且以 { 开头 } 结尾才 setParams）。URL query 不进 MY_PARAMS，
            // 而是随 pageRule params Map 由规则经 fetch('hiker://page/') 自取。#532 的合并做法已 revert。
            try {
                String extraJson = "";
                java.util.Map<String, String> extra = it.getExtra();
                if (extra != null && !extra.isEmpty()) {
                    org.json.JSONObject jo = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                        jo.put(e.getKey(), e.getValue());
                    }
                    extraJson = jo.toString();
                }
                sub.setPageParams(extraJson);
            } catch (Throwable ignored) {
                sub.setPageParams("");
            }
            destroyRouterAsync();
            currentRule = sub;
            cls = "";
            area = "";
            year = "";
            sort = "";
            page = 1;
            noMore = false;
            loading = false;
            contentGen++;
            videos.clear();
            String pageTitle = pageObj.optString("name");
            String itemTitle = stripHtml(it.getTitle());
            binding.tvContentTitle.setText(!pageTitle.isEmpty() ? pageTitle : (itemTitle.isEmpty() ? st.title : itemTitle));
            buildCategoryRow();
            buildFilterRows();
            if (st.fromView != V_CONTENT) pushView(V_CONTENT);
            else showView(V_CONTENT);
            loadContent(true);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * P2：条目 URL 动作协议的基本处理。返回 true 表示已消费，不再走 openDetail。
     * <ul>
     *   <li>{@code toast://文本} → Toast 显示</li>
     *   <li>{@code copy://文本} → 复制到剪贴板</li>
     *   <li>{@code input://...}/{@code select://...}/{@code confirm://...} → 对应对话框（基本处理）</li>
     *   <li>{@code msg@confirmRule=js:...} → $().confirm 序列化：弹确认框，确认后求值 JS</li>
     *   <li>{@code @inputRule=.js:...} → $().input 序列化：弹输入框，输入后求值 JS（input 为输入值）</li>
     *   <li>{@code @x5Rule=js:...} → $().x5Rule 序列化：求值取 URL，用 WebView 打开</li>
     * </ul>
     */
    private boolean handleActionUrl(String url, HkItem it) {
        try {
            if (url.startsWith("toast://")) {
                android.widget.Toast.makeText(this, url.substring(8), android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            if (url.startsWith("copy://")) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("hk", url.substring(7)));
                android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
            if (url.startsWith("confirm://")) {
                String msg = url.substring(10);
                int q = msg.indexOf('?');
                if (q >= 0) msg = msg.substring(0, q);
                new AlertDialog.Builder(this)
                        .setMessage(msg.isEmpty() ? "确认？" : msg)
                        .setPositiveButton("确定", (d, w) -> d.dismiss())
                        .setNegativeButton("取消", (d, w) -> d.dismiss())
                        .show();
                return true;
            }
            if (url.startsWith("input://") || url.startsWith("select://")) {
                boolean isInput = url.startsWith("input://");
                showSimpleInputDialog(isInput ? "输入" : "选择", "", input -> {
                    android.widget.Toast.makeText(this,
                            input.isEmpty() ? "已取消" : "输入：" + input,
                            android.widget.Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            int ci = url.indexOf("@confirmRule=");
            if (ci >= 0) {
                String msg = url.substring(0, ci);
                String js = url.substring(ci + 13).trim();
                if (js.startsWith("js:")) js = js.substring(3);
                final String code = js;
                new AlertDialog.Builder(this)
                        .setMessage(msg.isEmpty() ? "确认？" : msg)
                        .setPositiveButton("确定", (d, w) -> {
                            d.dismiss();
                            evalActionJs(code, "");
                        })
                        .setNegativeButton("取消", (d, w) -> d.dismiss())
                        .show();
                return true;
            }
            int ii = url.indexOf("@inputRule=");
            if (ii >= 0) {
                String js = url.substring(ii + 11).trim();
                if (js.startsWith(".js:")) js = js.substring(4);
                else if (js.startsWith("js:")) js = js.substring(3);
                final String code = js;
                showSimpleInputDialog(it.getTitle(), "", input -> evalActionJs(code, input));
                return true;
            }
            int xi = url.indexOf("@x5Rule=");
            if (xi >= 0) {
                String js = url.substring(xi + 8).trim();
                if (js.startsWith("js:")) js = js.substring(3);
                final String code = js;
                new Thread(() -> {
                    String target = "";
                    try {
                        target = getRouter().getEngine().getJsRuntime().evalLazy(code, "");
                    } catch (Throwable ignored) {
                    }
                    final String t = target == null ? "" : target.trim();
                    App.post(() -> {
                        if (t.startsWith("http")) showWebViewDialog(t);
                        else if (!t.isEmpty())
                            android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show();
                    });
                }).start();
                return true;
            }
        } catch (Throwable e) {
            android.util.Log.d("HkPage", "handleActionUrl failed: " + e.getMessage());
        }
        return false;
    }

    /** 在 JS 线程求值动作 JS（@confirmRule/@inputRule 回调），input 为输入值。 */
    private void evalActionJs(String code, String input) {
        new Thread(() -> {
            String errMsg = null;
            String result = "";
            boolean needRefresh = false;
            try {
                HkJsRuntime rt = getRouter().getEngine().getJsRuntime();
                // 官方语义：input 全局注入后直接求值。不用 evalLazy + 局部 var 包裹，
                // 否则规则顶层语句在 return(...) 包裹下报语法错误，异常被静默吞掉导致"点了没反应"。
                result = rt.eval(code, input);
                // 取走 refreshPage 请求标记：规则（如口令正确时 setItem 后调 refreshPage）要求刷新，
                // 必须在回调返回 toast:// 等动作后依然执行，否则页面不刷新（如星集口令正确却进不去）。
                needRefresh = rt.consumeRefreshRequest();
                String jsErr = rt.getError();
                if (jsErr != null && !jsErr.isEmpty()) errMsg = jsErr;
            } catch (Throwable e) {
                errMsg = e.getMessage() == null || e.getMessage().isEmpty() ? "执行失败" : e.getMessage();
                android.util.Log.d("HkPage", "evalActionJs failed: " + e.getMessage());
            }
            String r0 = result == null ? "" : result.trim();
            final String r = ("undefined".equals(r0) || "null".equals(r0)) ? "" : r0;
            final String em = errMsg;
            final boolean refresh = needRefresh;
            App.post(() -> {
                if (em != null && !em.isEmpty()) {
                    android.widget.Toast.makeText(HkPageActivity.this,
                            "执行失败：" + em, android.widget.Toast.LENGTH_SHORT).show();
                }
                // 回调返回可导航目标才跳转；普通成功标记字符串（如 "ok"/"1"）不是 URL，
                // 之前会被当成 URL 推开一个空白 V4，导致列表看起来"没刷新"（如口令正确却不刷新）。
                if (isNavigableResult(r)) {
                    HkItem nav = new HkItem();
                    nav.setTitle("");
                    nav.setUrl(r);
                    if (handleActionUrl(r, nav)) {
                        // toast:// 等动作已消费，但规则若请求了 refreshPage（如口令正确），仍需刷新列表
                        if (refresh) loadContent(true);
                        return;
                    }
                    onContentItemClick(nav);
                    return;
                }
                loadContent(true);
            });
        }).start();
    }

    /**
     * inputRule/confirmRule 回调返回值是否可导航：只有带 scheme 的 URL
     *（http/https/magnet/thunder/pics/x5/web/toast/copy/...）、含 @ 规则标记
     * 或 js: 前缀的才跳转；hiker://empty 与 hiker://* 为官方/内部"无跳转"标记。
     * 规则返回的普通字符串（如口令正确时的 "ok"/"1"）只是成功标记，必须走列表刷新。
     */
    private boolean isNavigableResult(String r) {
        if (r == null) return false;
        String t = r.trim();
        if (t.isEmpty()) return false;
        if ("hiker://empty".equals(t) || t.startsWith("hiker://")) return false;
        String low = t.toLowerCase();
        if (low.matches("^[a-z][a-z0-9+.-]*:.*")) return true;
        if (t.contains("@lazyRule=") || t.contains("@rule=") || t.contains("@inputRule=")
                || t.contains("@confirmRule=") || t.contains("@x5Rule=")) return true;
        return low.startsWith("js:");
    }

    /** 简单输入对话框（input:// 与 @inputRule= 共用）。 */
    private void showSimpleInputDialog(String title, String def, java.util.function.Consumer<String> cb) {
        final EditText et = new EditText(this);
        et.setHint("请输入");
        if (!TextUtils.isEmpty(def)) et.setText(def);
        et.setTextColor(0xFF1A1D24);
        int pad = dp(16);
        et.setPadding(pad, pad, pad, pad);
        // 弹窗标题也走标题颜色标记逻辑（弯引号/ASCII三引号 → 红色）
        CharSequence dialogTitle = "输入";
        if (title != null && !title.isEmpty()) {
            String markedHtml = applyTitleMarkHtml(title);
            if (markedHtml != null) {
                try {
                    dialogTitle = android.text.Html.fromHtml(markedHtml, android.text.Html.FROM_HTML_MODE_LEGACY);
                } catch (Throwable t) {
                    dialogTitle = stripHtml(title);
                }
            } else {
                dialogTitle = stripHtml(title);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    d.dismiss();
                    cb.accept(et.getText().toString().trim());
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    /** WebView 弹窗（@x5Rule= 与 x5_webview_single 共用）。 */
    private void showWebViewDialog(String url) {
        try {
            android.webkit.WebView wv = new android.webkit.WebView(this);
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setDomStorageEnabled(true);
            wv.setWebViewClient(new android.webkit.WebViewClient());
            wv.loadUrl(url);
            new AlertDialog.Builder(this)
                    .setView(wv)
                    .setPositiveButton("关闭", (d, w) -> {
                        try { wv.destroy(); } catch (Throwable ignored) {}
                        d.dismiss();
                    })
                    .show();
        } catch (Throwable e) {
            android.widget.Toast.makeText(this, "打开网页失败", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** V2 内容多 viewType 适配器：视频卡片/小图标占 1 列，其余占满 3 列。 */
    private class ContentAdapter extends RecyclerView.Adapter<ContentAdapter.Holder> {
        static final int T_VIDEO = 0;
        static final int T_TEXT = 1;
        static final int T_RICH = 2;
        static final int T_COLS = 3;
        static final int T_AVATAR = 4;
        static final int T_ICON = 5;
        static final int T_PIC = 6;
        static final int T_MOVIE = 7;
        static final int T_INPUT = 8;
        static final int T_SEARCH = 9;
        static final int T_WEB = 10;
        static final int T_BUTTONS = 11;
        static final int T_ICON_FILL = 12;
        static final int T_TEXT_ICON = 13;
        static final int T_CARD = 14;
        static final int T_MOVIE_LEFT_PIC = 15;
        static final int T_ICON_2_ROUND = 16;
        // 图标子类型（对照 8.83 ArticleListAdapter 各 icon_* 独立布局）：
        static final int T_ICON_4_SMALL = 17;   // icon_small_4/icon_5/icon_5_no_crop：32dp 图上文下
        static final int T_ICON_ROUND = 18;     // icon_round_4：42dp 图上文下 + 圆形裁剪
        static final int T_ICON_ROUND_SMALL = 19; // icon_round_small_4：32dp 图上文下 + 圆形裁剪
        static final int T_ICON_2 = 20;         // icon_2：左 28dp 图右文字横向卡片
        static final int T_ICON_4_CARD = 21;    // icon_4_card：44dp 圆角卡片图标 + 文字
        static final int T_ICON_3_SMALL = 22;   // icon_small_3：图标左文字右同行
        static final int T_ICON_1_SEARCH = 23;  // icon_1_search：搜索条
        static final int T_ICON_1_LEFT_PIC = 24; // icon_1_left_pic：左 60dp 图右文全宽行

        private final List<HkItem> source;
        /** 分组后的展示列表（groupButtons 快照；videos 变更后必须调 refreshGroups() 重算）。 */
        private List<HkItem> items;
        /** 按钮组：合成条目 → 子条目列表（连续 scroll_button/flex_button 的横向胶囊行）。 */
        private final java.util.Map<HkItem, List<HkItem>> buttonGroups = new java.util.HashMap<>();
        private int videoImgH;
        private final int picH;

        ContentAdapter(List<HkItem> items) {
            this.source = items;
            this.items = groupButtons(items);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            picH = (dm.widthPixels - dp(16) * 2) * 9 / 16;
            updateVideoImgH();
        }

        /**
         * 视频卡片图高：固定 3 列，列宽 = (屏宽-左右边距16dp*2)/3，图高 = 列宽*3/2。
         */
        void updateVideoImgH() {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int cols = 3;
            int itemW = (dm.widthPixels - dp(16) * 2) / cols;
            // 减去列间距均摊（GridSpace：12dp*(cols-1)/cols）
            itemW -= dp(12) * (cols - 1) / cols;
            videoImgH = itemW * 3 / 2;
        }

        /**
         * videos 增删后重算按钮分组。groupButtons 返回的是快照，不调此方法
         * 适配器将恒显示首次构造时的空列表（V2"分类页没数据"的根因）。
         */
        void refreshGroups() {
            buttonGroups.clear();
            this.items = groupButtons(source);
        }

        /**
         * 把连续的 scroll_button/flex_button 条目合并为一个横向胶囊行
         * （正常应已被 loadContent 拆为顶部 tab；这里是兜底）。
         */
        private List<HkItem> groupButtons(List<HkItem> src) {
            List<HkItem> out = new ArrayList<>();
            List<HkItem> buf = new ArrayList<>();
            for (HkItem it : src) {
                String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
                if ("scroll_button".equals(ct) || "flex_button".equals(ct)) {
                    buf.add(it);
                } else {
                    if (!buf.isEmpty()) {
                        out.add(makeButtonGroup(buf));
                        buf = new ArrayList<>();
                    }
                    out.add(it);
                }
            }
            if (!buf.isEmpty()) out.add(makeButtonGroup(buf));
            return out;
        }

        private HkItem makeButtonGroup(List<HkItem> subs) {
            HkItem g = new HkItem();
            g.setColType("button_group");
            g.setTitle("");
            buttonGroups.put(g, new ArrayList<>(subs));
            return g;
        }

        /**
         * 官方 60 列栅格 span（8.83 ArticleColTypeEnum.spanCount 反编译实测）：
         * text_2/pic_2/icon_2(2/行)=30；text_3/pic_3/icon_3(3/行)=20；
         * text_4/icon_4(4/行)=15；text_5/icon_5(5/行)=12；其余全宽=60。
         */
        int spanFor(int position) {
            if (position < 0 || position >= items.size()) return 60;
            HkItem it = items.get(position);
            int t = getItemViewType(position);
            String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
            switch (t) {
                case T_VIDEO:
                    // 官方：movie_2 span=30（2/行）；movie_3 span=20（3/行）
                    if ("movie_2".equals(ct)) return 30;
                    return 20;
                case T_COLS:
                    // text_2=30 text_3=20 text_4=15 text_5=12（N 个条目一行）
                    if (ct.endsWith("_2")) return 30;
                    if (ct.endsWith("_3")) return 20;
                    if (ct.endsWith("_4")) return 15;
                    if (ct.endsWith("_5")) return 12;
                    return 60;
                case T_ICON:
                case T_ICON_ROUND:
                    return 15; // icon_4 / icon_round_4：4/行（官方 spanCount=15）
                case T_ICON_4_SMALL:
                case T_ICON_ROUND_SMALL:
                    if ("icon_5".equals(ct) || "icon_5_no_crop".equals(ct)) return 12; // 官方 spanCount=12
                    return 15; // icon_small_4 / icon_round_small_4：4/行
                case T_ICON_4_CARD:
                    return 15; // icon_4_card：4/行（官方 spanCount=15）
                case T_ICON_FILL:
                    return 20; // icon_3_fill / icon_3_round_fill：3/行
                case T_ICON_2:
                case T_ICON_2_ROUND:
                    return 30; // icon_2 / icon_2_round：2/行（官方 spanCount=30）
                case T_ICON_3_SMALL:
                    return 20; // icon_small_3：3/行（官方 spanCount=20）
                case T_ICON_1_SEARCH:
                case T_ICON_1_LEFT_PIC:
                    return 60; // icon_1_search / icon_1_left_pic 全宽（官方 spanCount=60）
                case T_TEXT_ICON:
                    return 60; // text_icon 全宽
                case T_CARD:
                    if ("card_pic_2".equals(ct) || "card_pic_2_2".equals(ct)
                            || "card_pic_2_2_left".equals(ct)) return 30;
                    if ("card_pic_3".equals(ct) || "card_pic_3_center".equals(ct)) return 20;
                    return 60; // card_pic_1 全宽
                case T_MOVIE_LEFT_PIC:
                    return 60; // movie_1 左图右文全宽
                case T_PIC:
                    if (ct.startsWith("pic_2") || "card_pic_2".equals(ct)
                            || "card_pic_2_2".equals(ct) || "card_pic_2_2_left".equals(ct)) return 30;
                    if (ct.startsWith("pic_3") || "card_pic_3".equals(ct) || "card_pic_3_center".equals(ct)) return 20;
                    return 60; // pic_1 / pic_1_full / pic_1_center / pic_1_card / card_pic_1
                default:
                    return 60;
            }
        }

        class Holder extends RecyclerView.ViewHolder {
            ImageView cover;
            TextView title, desc, overlay;
            LinearLayout cols;
            EditText input;
            android.webkit.WebView web;

            Holder(View v, int type) {
                super(v);
                switch (type) {
                    case T_VIDEO:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        overlay = v.findViewById(R.id.tv_overlay);
                        View containerV = v.findViewById(R.id.cover_container);
                        if (containerV != null) {
                            ViewGroup.LayoutParams vlp = containerV.getLayoutParams();
                            vlp.height = videoImgH;
                            containerV.setLayoutParams(vlp);
                        } else {
                            ViewGroup.LayoutParams vlp = cover.getLayoutParams();
                            vlp.height = videoImgH;
                            cover.setLayoutParams(vlp);
                        }
                        break;
                    case T_TEXT:
                    case T_RICH:
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_COLS:
                    case T_BUTTONS:
                        cols = v.findViewById(R.id.cols_container);
                        break;
                    case T_AVATAR:
                        cover = v.findViewById(R.id.iv_avatar);
                        title = v.findViewById(R.id.tv_title);
                        cover.setClipToOutline(true);
                        cover.setOutlineProvider(new ViewOutlineProvider() {
                            @Override
                            public void getOutline(View view, Outline outline) {
                                outline.setOval(0, 0, view.getWidth(), view.getHeight());
                            }
                        });
                        break;
                    case T_ICON:
                    case T_ICON_FILL:
                    case T_ICON_2_ROUND:
                    case T_ICON_4_SMALL:
                    case T_ICON_ROUND:
                    case T_ICON_ROUND_SMALL:
                    case T_ICON_2:
                    case T_ICON_4_CARD:
                    case T_ICON_3_SMALL:
                    case T_ICON_1_SEARCH:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_ICON_1_LEFT_PIC:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_TEXT_ICON:
                        cover = v.findViewById(R.id.iv_icon);
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_CARD:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        overlay = v.findViewById(R.id.tv_overlay);
                        break;
                    case T_MOVIE_LEFT_PIC:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_PIC:
                        cover = v.findViewById(R.id.iv_pic);
                        ViewGroup.LayoutParams plp = cover.getLayoutParams();
                        plp.height = picH;
                        cover.setLayoutParams(plp);
                        break;
                    case T_MOVIE:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_INPUT:
                        input = v.findViewById(R.id.et_input);
                        break;
                    case T_SEARCH:
                        title = v.findViewById(R.id.tv_title);
                        break;
                    case T_WEB:
                        web = v.findViewById(R.id.wv_page);
                        web.getSettings().setJavaScriptEnabled(true);
                        web.getSettings().setDomStorageEnabled(true);
                        web.setWebViewClient(new android.webkit.WebViewClient());
                        break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) return T_VIDEO;
            return contentTypeOf(items.get(position));
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout;
            switch (viewType) {
                case T_TEXT:
                case T_RICH:
                    layout = R.layout.item_hk_text;
                    break;
                case T_COLS:
                    layout = R.layout.item_hk_cols;
                    break;
                case T_BUTTONS:
                    layout = R.layout.item_hk_cols;
                    break;
                case T_AVATAR:
                    layout = R.layout.item_hk_avatar;
                    break;
                case T_ICON:
                    layout = R.layout.item_hk_icon_four_col; // icon_4：42dp 图上文下（对照 8.83 item_icon_four_col）
                    break;
                case T_ICON_4_SMALL:
                    layout = R.layout.item_hk_icon_four_small; // icon_small_4/icon_5：32dp 图上文下
                    break;
                case T_ICON_ROUND:
                    layout = R.layout.item_hk_icon_four_col; // icon_round_4：42dp，bind 时圆形裁剪
                    break;
                case T_ICON_ROUND_SMALL:
                    layout = R.layout.item_hk_icon_four_small; // icon_round_small_4：32dp，bind 时圆形裁剪
                    break;
                case T_ICON_2:
                    layout = R.layout.item_hk_icon_two_col; // icon_2：左 28dp 图右文字横向卡片
                    break;
                case T_ICON_4_CARD:
                    layout = R.layout.item_hk_icon_four_card; // icon_4_card：44dp 圆角图标 + 文字
                    break;
                case T_ICON_3_SMALL:
                    layout = R.layout.item_hk_icon_three_small; // icon_small_3：图标左文字右同行
                    break;
                case T_ICON_1_SEARCH:
                    layout = R.layout.item_hk_icon_search_col; // icon_1_search：搜索条
                    break;
                case T_ICON_1_LEFT_PIC:
                    layout = R.layout.item_hk_icon_left_pic; // icon_1_left_pic：左 60dp 图右文全宽
                    break;
                case T_ICON_FILL:
                    layout = R.layout.item_hk_icon_3_fill;
                    break;
                case T_ICON_2_ROUND:
                    layout = R.layout.item_hk_icon_2_round;
                    break;
                case T_TEXT_ICON:
                    layout = R.layout.item_hk_text_icon;
                    break;
                case T_CARD:
                    layout = R.layout.item_hk_card_rect;
                    break;
                case T_MOVIE_LEFT_PIC:
                    layout = R.layout.item_hk_movie_left_pic;
                    break;
                case T_PIC:
                    layout = R.layout.item_hk_pic;
                    break;
                case T_MOVIE:
                    layout = R.layout.item_hk_movie;
                    break;
                case T_INPUT:
                    layout = R.layout.item_hk_input;
                    break;
                case T_SEARCH:
                    layout = R.layout.item_hk_search;
                    break;
                case T_WEB:
                    layout = R.layout.item_hk_web;
                    break;
                default:
                    layout = R.layout.item_hk_video;
                    break;
            }
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new Holder(v, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            if (position < 0 || position >= items.size()) return;
            HkItem item = items.get(position);
            // 按 Holder 创建时的 viewType 来 bind：布局过程中若 viewType 发生变化，
            // 用 position 查到的新 viewType 去 bind 会与 Holder 已初始化的 view 不一致，
            // 导致 TextView 为 null 崩溃（movie_1/movie_2 改 span 后复现）。
            switch (h.getItemViewType()) {
                case T_VIDEO:
                    bindVideo(h, item);
                    break;
                case T_TEXT:
                    bindText(h, item);
                    break;
                case T_RICH:
                    bindRich(h, item);
                    break;
                case T_COLS:
                    bindCols(h, item);
                    break;
                case T_BUTTONS:
                    bindButtons(h, item);
                    break;
                case T_AVATAR:
                    bindAvatar(h, item);
                    break;
                case T_ICON:
                case T_ICON_FILL:
                case T_ICON_2_ROUND:
                case T_ICON_4_SMALL:
                case T_ICON_ROUND:
                case T_ICON_ROUND_SMALL:
                case T_ICON_2:
                case T_ICON_4_CARD:
                case T_ICON_3_SMALL:
                case T_ICON_1_SEARCH:
                case T_ICON_1_LEFT_PIC:
                    bindIcon(h, item);
                    break;
                case T_TEXT_ICON:
                    bindTextIcon(h, item);
                    break;
                case T_CARD:
                    bindCard(h, item);
                    break;
                case T_MOVIE_LEFT_PIC:
                    bindMovie(h, item);
                    break;
                case T_PIC:
                    bindPic(h, item);
                    break;
                case T_MOVIE:
                    bindMovie(h, item);
                    break;
                case T_INPUT:
                    bindInput(h, item);
                    break;
                case T_SEARCH:
                    bindSearch(h, item);
                    break;
                case T_WEB:
                    bindWeb(h, item);
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /** 视频卡片：保持原 VideoAdapter 行为；desc 压在封面底部（原版 overlay 样式）。 */
        private void bindVideo(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            // movie_3_marquee：标题跑马灯（对照 8.83 item_movie_tools_marquee，marqueeRepeatLimit=3）；
            // 非 marquee 时复位（防复用串台）
            String ct = item.getColType() == null ? "" : item.getColType().trim().toLowerCase();
            if ("movie_3_marquee".equals(ct)) {
                h.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                h.title.setSingleLine(true);
                h.title.setMarqueeRepeatLimit(3);
                h.title.setSelected(true);
            } else {
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setSingleLine(false);
                h.title.setSelected(false);
            }
            // desc 压在封面上（原版样式），不再放标题下面
            String d = stripHtml(item.getDesc());
            if (h.overlay != null) {
                h.overlay.setText(d);
                h.overlay.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            h.desc.setVisibility(View.GONE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || currentRule == null) return;
                HkItem it = items.get(pos);
                String u = it.getUrl() == null ? "" : it.getUrl();
                // P1：hiker://page/ 子页面走 openPage，不进 V4
                if (u.trim().startsWith("hiker://page/") && openPage(it, u)) return;
                openDetail(it, true, u.contains("@lazyRule="));
            });
        }

        /** 文本行：text_center_1 居中灰字；text_1 去标签标题+描述；long_text 多行。 */
        private void bindText(Holder h, HkItem item) {
            String ct = colTypeOf(item);
            if ("text_center_1".equals(ct)) {
                h.title.setGravity(Gravity.CENTER);
                h.title.setTextColor(0xFF101216);
                h.title.setTextSize(13);
                h.title.setMaxLines(4);
                h.title.setText(titleSpan(item.getTitle()));
                h.desc.setVisibility(View.GONE);
            } else {
                boolean longText = "long_text".equals(ct);
                h.title.setGravity(Gravity.START);
                h.title.setTextColor(0xFF1A1D24);
                h.title.setTextSize(14);
                h.title.setMaxLines(longText ? 30 : 3);
                h.title.setText(titleSpan(item.getTitle()));
                String d = stripHtml(item.getDesc());
                h.desc.setText(d);
                h.desc.setMaxLines(longText ? 60 : 5);
                h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            setContentClick(h, item);
        }

        /** 富文本行：Html.fromHtml 显示，失败回退去标签；标题颜色标记（弯引号 ""红/''橙）同样生效。 */
        private void bindRich(Holder h, HkItem item) {
            h.title.setGravity(Gravity.START);
            h.title.setTextColor(0xFF1A1D24);
            h.title.setTextSize(14);
            h.title.setMaxLines(30);
            String rawTitle = item.getTitle() == null ? "" : item.getTitle();
            String markedHtml = applyTitleMarkHtml(rawTitle);
            try {
                CharSequence cs = Html.fromHtml(markedHtml != null ? markedHtml : rawTitle, Html.FROM_HTML_MODE_LEGACY);
                h.title.setText(cs);
            } catch (Throwable t) {
                h.title.setText(titleSpan(item.getTitle()));
            }
            String d = item.getDesc();
            if (TextUtils.isEmpty(d)) {
                h.desc.setVisibility(View.GONE);
            } else {
                h.desc.setVisibility(View.VISIBLE);
                try {
                    h.desc.setText(Html.fromHtml(d, Html.FROM_HTML_MODE_LEGACY));
                } catch (Throwable t) {
                    h.desc.setText(stripHtml(d));
                }
            }
            setContentClick(h, item);
        }

        /**
         * 多列文本格：官方语义是一个条目占一格，N 个条目一行（text_2=2/行 … text_5=5/行，
         * span 由 spanFor 按 60 列栅格给出）。标题居中显示一格，不再切分标题。
         */
        private void bindCols(Holder h, HkItem item) {
            h.cols.removeAllViews();
            Context ctx = h.itemView.getContext();
            TextView tv = new TextView(ctx);
            tv.setText(titleSpan(item.getTitle()));
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(15);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            int pad = dp(5);
            tv.setPadding(pad, dp(10), pad, dp(10));
            // 8.83 text_2~text_5 用 item_card_rect 系列布局，带 tab_item_default 底框（分类格子有框）
            try {
                tv.setBackgroundResource(R.drawable.tab_item_default);
            } catch (Throwable ignored) {}
            tv.setMinHeight(dp(40));
            h.cols.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setContentClick(h, item);
        }

        /** 流式胶囊按钮组：连续 scroll_button/flex_button 聚合为自动换行的流式布局（官方 flex_button）。 */
        private void bindButtons(Holder h, HkItem item) {
            h.cols.removeAllViews();
            List<HkItem> subs = buttonGroups.get(item);
            if (subs == null || subs.isEmpty()) return;
            Context ctx = h.itemView.getContext();
            int parentW = h.cols.getWidth();
            if (parentW <= 0) {
                parentW = ctx.getResources().getDisplayMetrics().widthPixels - dp(16) * 2;
            }
            LinearLayout flow = new LinearLayout(ctx);
            flow.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = newButtonRow(ctx);
            int rowW = 0;
            int padH = dp(14), padV = dp(7);
            for (HkItem sub : subs) {
                TextView tv = new TextView(ctx);
                tv.setText(titleSpan(sub.getTitle()));
                tv.setTextColor(0xFF1A1D24);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER);
                tv.setMaxLines(1);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setPadding(padH, padV, padH, padV);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFFF1F3F6);
                bg.setCornerRadius(dp(14));
                tv.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(8);
                lp.bottomMargin = dp(8);
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> onContentItemClick(sub));
                tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int w = tv.getMeasuredWidth() + dp(8);
                if (rowW + w > parentW && rowW > 0) {
                    flow.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row = newButtonRow(ctx);
                    rowW = 0;
                }
                row.addView(tv);
                rowW += w;
            }
            flow.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            h.cols.addView(flow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private LinearLayout newButtonRow(Context ctx) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            return row;
        }

        /** 头像行：圆形图 + 标题横向。 */
        private void bindAvatar(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 图标：各 icon_* 子类型按 8.83 走独立布局；round 系列图标圆形裁剪（对照 8.83 bindCircleCropImageView）。 */
        private void bindIcon(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            int vt = h.getItemViewType();
            if (vt == T_ICON_ROUND || vt == T_ICON_ROUND_SMALL || vt == T_ICON_2_ROUND) {
                ImgUtil.loadCircle(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            } else {
                ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            }
            if (vt == T_ICON_1_LEFT_PIC && h.desc != null) {
                String d = stripHtml(item.getDesc());
                h.desc.setText(d);
                h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            setContentClick(h, item);
        }

        /** 图文行：text_icon，左文字右图标（对照 8.83 item_text_icon）。 */
        private void bindTextIcon(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 卡片：card_pic_* 系列，封面+标题+底部 overlay（对照 8.83 item_card_rect）。 */
        private void bindCard(Holder h, HkItem item) {
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            if (h.overlay != null) {
                h.overlay.setText(d);
                h.overlay.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 图片：pic_1系列全宽16:9；pic_2半宽16:9；pic_3/pic_3_square按列宽正方形。 */
        private void bindPic(Holder h, HkItem item) {
            String ct = colTypeOf(item);
            ViewGroup.LayoutParams plp = h.cover.getLayoutParams();
            if (plp != null) {
                DisplayMetrics dm = h.itemView.getContext().getResources().getDisplayMetrics();
                int W = dm.widthPixels - dp(16) * 2;
                if (ct.startsWith("pic_3")) {
                    plp.height = (W - dp(12) * 2) / 3; // 3列正方形
                } else if (ct.startsWith("pic_2")) {
                    int itemW = (W - dp(12)) / 2;
                    plp.height = itemW * 9 / 16;
                } else {
                    plp.height = picH; // 16:9 全宽
                }
                h.cover.setLayoutParams(plp);
            }
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            h.cover.setContentDescription(stripHtml(item.getTitle()));
            setContentClick(h, item);
        }

        /** 横向图文：左图右文。 */
        private void bindMovie(Holder h, HkItem item) {
            if (h.title == null || h.desc == null || h.cover == null) return; // 防 ViewHolder 类型错配
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            h.desc.setText(d);
            h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            setContentClick(h, item);
        }

        /** 输入框行：hint 取标题；搜索键触发且有 url 时走点击处理。 */
        private void bindInput(Holder h, HkItem item) {
            h.input.setHint(stripHtml(item.getTitle()));
            h.input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    if (!TextUtils.isEmpty(item.getUrl())) {
                        // 官方语义（ArticleListFragment.clickItem）：input 条目把用户输入的文本
                        // 作为 input 全局变量注入后求值，而非 pageUrl。否则口令验证永远失败。
                        String key = h.input.getText() == null ? "" : h.input.getText().toString();
                        try {
                            getRouter().getEngine().getJsRuntime().setNextInputOverride(key);
                        } catch (Throwable ignored) {
                        }
                        onContentItemClick(item);
                    }
                    return true;
                }
                return false;
            });
        }

        /** 搜索框行：假输入框样式，点击进 V3 搜索。 */
        private void bindSearch(Holder h, HkItem item) {
            String t = stripHtml(item.getTitle());
            h.title.setText(TextUtils.isEmpty(t) ? "搜索…" : t);
            h.itemView.setOnClickListener(v -> openSearch());
        }

        /** P2：x5_webview_single 内嵌网页（JRKAN直播这类网页版小程序），点击整行用外部 WebView 打开。 */
        private void bindWeb(Holder h, HkItem item) {
            String url = item.getUrl() == null ? "" : item.getUrl().trim();
            // url 可能带选择器后缀，只取纯 http 部分
            int sp = url.indexOf(' ');
            if (sp > 0) url = url.substring(0, sp).trim();
            int semi = url.indexOf(';');
            if (semi > 0) url = url.substring(0, semi).trim();
            int hash = url.indexOf('#');
            if (hash > 0) url = url.substring(0, hash).trim();
            if (url.startsWith("http") && h.web != null) {
                h.web.loadUrl(url);
            }
            final String target = url;
            h.itemView.setOnClickListener(v -> {
                if (target.startsWith("http")) showWebViewDialog(target);
            });
        }

        /** 非视频条目点击：无 url 的纯展示行不设点击。 */
        private void setContentClick(Holder h, HkItem item) {
            if (TextUtils.isEmpty(item.getUrl())) {
                h.itemView.setOnClickListener(null);
                return;
            }
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || currentRule == null) return;
                onContentItemClick(items.get(pos));
            });
        }
    }

    private static class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.Holder> {
        interface OnPick {
            void onPick(String value);
        }

        private final List<String[]> pairs;
        private int selected;
        private final OnPick listener;
        private int dropTarget = -1;

        ChipAdapter(List<String[]> pairs, int selected, OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        /** 拖拽投放高亮位置（-1=无），供编辑模式拖卡片到分组 pills 用。 */
        boolean setDropTarget(int pos) {
            if (dropTarget == pos) return false;
            int old = dropTarget;
            dropTarget = pos;
            if (old >= 0 && old < pairs.size()) notifyItemChanged(old);
            if (pos >= 0 && pos < pairs.size()) notifyItemChanged(pos);
            return true;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView chip;

            Holder(View v) {
                super(v);
                chip = v.findViewById(R.id.tv_chip);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_chip, parent, false);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.rightMargin = (int) (8 * parent.getContext().getResources().getDisplayMetrics().density + 0.5f);
            v.setLayoutParams(lp);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] pair = pairs.get(position);
            h.chip.setText(pair[0]);
            boolean sel = position == selected;
            h.chip.setSelected(sel);
            boolean drop = position == dropTarget;
            if (drop) {
                // 拖拽投放目标：金色加粗 + 选中底，提示松手即进该分组
                h.chip.setTextColor(0xFFB8890F);
                h.chip.setTypeface(null, android.graphics.Typeface.BOLD);
                h.chip.setBackgroundResource(R.drawable.hk_ripple_glass_selected);
            } else {
                h.chip.setTypeface(null, android.graphics.Typeface.NORMAL);
                // 磨砂 pills：选中=淡绿磨砂+深绿字，未选中=白磨砂+深灰字（深色字在磨砂底上保持可读）
                h.chip.setTextColor(sel ? 0xFF1B5E20 : 0xFF222222);
                h.chip.setBackgroundResource(sel ? R.drawable.hk_ripple_glass_selected : R.drawable.hk_ripple_glass);
            }
            h.chip.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                selected = pos;
                notifyDataSetChanged();
                if (listener != null) listener.onPick(pairs.get(pos)[1]);
            });
        }

        @Override
        public int getItemCount() {
            return pairs.size();
        }
    }
}
