package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
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
import android.widget.ProgressBar;
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
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.hk.HkDetail;
import com.fongmi.android.tv.api.hk.HkDetailItem;
import com.fongmi.android.tv.api.hk.HkErrorUtil;
import com.fongmi.android.tv.api.hk.HkHttp;
import com.fongmi.android.tv.api.hk.HkItem;
import com.fongmi.android.tv.api.hk.HkJsRuntime;
import com.fongmi.android.tv.api.hk.HkLoading;
import com.fongmi.android.tv.api.hk.HkPlay;
import com.fongmi.android.tv.api.hk.HkRouter;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.api.hk.HkShareHelper;
import com.fongmi.android.tv.api.hk.SearchResult;
import com.fongmi.android.tv.databinding.ActivityHkBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.gson.Gson;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
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
import java.util.function.BiConsumer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final int REQ_RULE_SEARCH = 0x102;
    private static final String PREFS = "xingchen";
    private static final String KEY_HIST = "hk_search_history";
    private static final String KEY_SMODE = "smode";
    private static final String KEY_LAST_CLIP = "hk_last_clip_prompt";
    private static final String KEY_RULE_SPAN = "hk_rule_span_count";
    /** 规则详情用：标题→导入时间戳 / 标题→最近使用时间戳 */
    private static final String KEY_RULE_IMPORT_TIME = "hk_rule_import_time";
    private static final String KEY_RULE_LAST_USE = "hk_rule_last_use";
    /** 图标设置：0=圆形 / 1=圆角矩形 / 2=不显示（默认 1，保持现有样式） */
    private static final String KEY_RULE_ICON_SHAPE = "hk_rule_icon_shape";
    /** 新窗口打开设置 */
    private static final String KEY_RULE_NEW_WINDOW = "hk_rule_new_window";
    /** 自定义分组：有序分组名列表 / 规则标题→自定义分组（""=显式无分组）/ 自定义顺序（规则标题有序列表） */
    private static final String KEY_CUSTOM_GROUPS = "hk_custom_groups";
    private static final String KEY_RULE_GROUPS = "hk_rule_group_map";
    private static final String KEY_RULE_ORDER = "hk_rule_order";
    /** 详情页：收藏列表 JSON / 足迹 key 前缀 / 跳过片头片尾 key 前缀 / 单页播放器 key 前缀 */
    private static final String KEY_DETAIL_FAVS = "hk_detail_favs";
    private static final String KEY_DETAIL_FOOT = "hk_detail_foot_";
    private static final String KEY_DETAIL_SKIP = "hk_detail_skip_";
    private static final String KEY_DETAIL_PLAYER = "hk_detail_player_";
    /** 导入时分组选择器的标准分组（8.83 默认分组名，去掉①②序号）。 */
    private static final String[] STD_GROUPS = {"聚合", "视频", "资讯", "图集", "漫画", "其它", "工具", "音频"};
    /** 8.83 ArticleColTypeEnum 的全部 col_type（表单编辑显示样式选择器用）。 */
    private static final String[] COL_TYPE_OPTIONS = {
            "movie_3", "movie_2", "movie_1", "movie_1_left_pic", "movie_1_vertical_pic",
            "movie_1_vertical_pic_blur", "movie_3_marquee",
            "pic_3", "pic_2", "pic_1", "pic_1_full", "pic_1_center", "pic_3_square", "pic_1_card",
            "card_pic_3", "card_pic_2", "card_pic_2_2", "card_pic_2_2_left", "card_pic_3_center", "card_pic_1",
            "text_3", "text_2", "text_1", "text_4", "text_5", "text_center_1", "text_icon",
            "icon_4", "icon_3_fill", "icon_3_round_fill", "icon_2", "icon_2_round", "icon_5",
            "icon_4_card", "icon_5_no_crop", "icon_round_4", "icon_round_small_4",
            "icon_small_3", "icon_small_4", "icon_1_left_pic", "icon_1_search",
            "long_text", "rich_text", "line", "line_blank", "blank_block", "big_blank_block",
            "big_big_blank_block", "header", "footer", "input", "scroll_button", "flex_button", "avatar"
    };

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, HkPageActivity.class));
    }

    private ActivityHkBinding binding;
    private final Deque<Integer> stack = new ArrayDeque<>();

    private RulePagerAdapter rulePagerAdapter;
    private final List<HkRule> rules = new ArrayList<>();
    /** 分组 tab 原始值列表（""=全部，首项固定"全部"）；当前选中的 tab 位置 */
    private final List<String> groupTabs = new ArrayList<>();
    private int currentGroupPos = 0;
    private ChipAdapter groupChipAdapter;
    /** 小程序列表：名称排序开关 / 搜索关键字（表单编辑器内规则选择器复用，列表页已去掉排序与 inline 搜索） */
    private boolean ruleSortByName = false;
    private String ruleKeyword = "";
    /** 管理模式（编辑按钮切换）/ 批量删除模式 */
    private boolean ruleManageMode = false;
    private boolean batchDeleteMode = false;
    private final java.util.Set<String> batchDeleteSet = new java.util.HashSet<>();
    /** 自定义分组（本地 prefs，不改 rule.json）：有序分组名 / 标题→分组覆盖 / 自定义顺序（null=未自定义） */
    private final List<String> customGroups = new ArrayList<>();
    private final Map<String, String> ruleGroupMap = new HashMap<>();
    private List<String> customOrder = null;
    /** 规则详情用：导入时间 / 最近使用（标题→时间戳） */
    private final Map<String, Long> ruleImportTime = new HashMap<>();
    private final Map<String, Long> ruleLastUse = new HashMap<>();
    // 编辑模式拖拽到分组 tab：拖拽状态（排序快照用于投放分组时回滚换位）
    private RecyclerView.ViewHolder ruleDragHolder;
    private int ruleDragFromPos = RecyclerView.NO_POSITION;
    private String ruleDragTitle;
    private List<String> ruleDragOrderSnapshot;
    private int groupDropTargetPos = -1;

    private ContentAdapter contentAdapter;
    private final List<HkItem> videos = new ArrayList<>();
    private GridLayoutManager contentGrid;
    private HkRule currentRule;
    /** 内容页标题（顶栏已删除，用字段保存子列表栈恢复用）。 */
    private String contentTitle = "";
    private final List<HkRule> miniappRules = new ArrayList<>();
    private HkRouter router;
    /** 实际抓取的页面 URL（图片自动 Referer 用，对齐 8.83 GlideUtil.getGlideUrl 语义）。
     * 规则静态 URL 常为 hiker://empty## 模板，与图片域名对不上会导致防盗链 403；
     * loadContent/detail 后取引擎最后一次 fetch 的真实 URL。volatile：后台线程写、UI 线程读。 */
    private volatile String hkPageUrl;
    /** preRule loading 弹窗（8.83 showLoading/hideLoading 等价）：规则 JS 调 showLoading("正在获取域名，请稍候") 时弹出。 */
    private android.app.Dialog hkLoadingDialog;
    private TextView hkLoadingText;
    /** 8.83 MainActivity.isOnPause 等价：onLoading 开头 isOnPause 直接 return，后台时不弹框。 */
    private boolean hkPaused;
    /** 8.83 canTouchUI 等价：只响应当前规则的 showLoading，避免切规则后旧规则 JS 弹框。 */
    private final HkLoading.Listener hkLoadingListener = new HkLoading.Listener() {
        @Override
        public void onShowLoading(String ruleTitle, String text) {
            if (hkPaused) return;
            if (currentRule == null || ruleTitle == null || !ruleTitle.equals(currentRule.getTitle())) return;
            showHkLoading(text);
        }

        @Override
        public void onHideLoading(String ruleTitle) {
            if (hkPaused) return;
            if (currentRule == null || ruleTitle == null || !ruleTitle.equals(currentRule.getTitle())) return;
            hideHkLoading();
        }
    };
    private String cls = "", area = "", year = "", sort = "";
    /** 每行分类 tab 当前选中的名字（8.83 ArticleList.setPic 等价，用于选中高亮）。 */
    private String clsName = "", areaName = "", yearName = "", sortName = "";
    private int page = 1;
    private boolean loading, noMore;
    private final Map<String, Integer> scrollMem = new HashMap<>();

    private SearchCardAdapter searchAdapter;
    private final List<SearchResult> searchResults = new ArrayList<>();
    /** 跨小程序搜索的结果来源标注（SearchResult 保持 8.83 原版纯净，来源放外部映射）。 */
    private final Map<SearchResult, String> searchSrcRules =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private String keyword = "";
    private int searchPage = 1;
    private boolean searchLoading, searchNoMore;
    /** 搜索结果是否支持分页（规则 searchUrl 含 fypage）：控制滚动到底部时是否触发 doSearchPage。 */
    private boolean searchCanPage = true;
    /** 跨小程序搜索代际：新搜索开始时自增，旧搜索的迟到结果直接丢弃。 */
    private final AtomicInteger crossSearchGen = new AtomicInteger(0);
    /** 搜索页：true = 小程序列表入口的整页内容搜索；false = 内容页内单规则搜索 */
    private boolean searchDualMode = false;

    private HkItem detailItem;
    private boolean detailFromSearch;
    private android.widget.Toast loadingHint;
    private HkDetail currentDetail;
    // 8.83 原版：详情页走通用列表渲染器，不再用专用线路/选集模型
    private final List<HkItem> detailItems = new ArrayList<>();
    private ContentAdapter detailAdapter;
    private GridLayoutManager detailGrid;
    /** 详情足迹：上次点击的选集 URL（规则 URL 含 #noHistory# 的不记），返回时滚动定位+高亮 */
    private String detailHighlightUrl = "";
    /** 切集判断：本详情页已调起播放的选集 URL */
    private String detailLastPlayedUrl = "";
    /** 详情倒序开关（⋮ 菜单"切换倒序"） */
    private boolean detailReversed = false;

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
        handleOpenFileIntent(getIntent());
    }

    /**
     * 从文件管理器"打开方式"进入：ACTION_VIEW 携带规则文件（.json/.hkzip/.zip）时走导入流程。
     * 复用 importRuleFiles：按魔数识别 zip（.hkzip/.zip 同一套 parseHkZip 解析），单规则直接导入+弹"是否打开"。
     */
    private void handleOpenFileIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        setIntent(new Intent()); // 消费掉，避免旋转重建时重复导入
        List<Uri> uris = new ArrayList<>();
        uris.add(uri);
        importRuleFiles(uris);
    }

    @Override
    protected void onBackInvoked() {
        // 批量删除模式中按返回：先退出勾选模式，不退出页面
        if (stack.size() == 1 && stack.peek() == V_RULES && batchDeleteMode) {
            exitBatchDeleteMode();
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
        HkLoading.clearListener(hkLoadingListener);
        hideHkLoading();
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

    /**
     * 跨小程序搜索结果点击：静默切换到来源小程序（只换 currentRule + 重建 router，
     * 不切视图不 reload 内容页），供后续 openDetail 用来源规则的引擎解析详情。
     */
    private void switchRuleForDetail(com.fongmi.android.tv.api.hk.HkRule target) {
        if (target == null) return;
        if (currentRule != null && target.getTitle().equals(currentRule.getTitle())) return;
        destroyRouterAsync();
        currentRule = target;
        hkPageUrl = null; // 切规则：实际抓取页 URL 失效
        // 预热新规则的 runtime（图片 @js= 求值等依赖当前 runtime 登记）
        try {
            getRouter();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hkPaused = false;
        // 播放器返回详情页：滚动到足迹选集并高亮（8.83 refreshLastClick 等价）
        if (!stack.isEmpty() && stack.peek() == V_DETAIL && detailAdapter != null
                && binding.rvDetailContent.getVisibility() == View.VISIBLE) {
            int footPos = findFootprintPos();
            if (footPos >= 0) {
                detailAdapter.setHighlightUrl(detailHighlightUrl);
                detailAdapter.notifyDataSetChanged();
                binding.rvDetailContent.scrollToPosition(detailAdapter.groupFirstPosition(footPos));
            }
        }
    }

    @Override
    protected void onPause() {
        hkPaused = true;
        super.onPause();
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
        // 顶栏：左侧[小程序 20sp][☰菜单]，右侧[编辑][新增][🔍搜索]（搜索放最右）
        binding.btnMenuRule.setOnClickListener(v -> showHkMainMenu());
        binding.btnAddRule.setOnClickListener(v -> {
            if (batchDeleteMode) doBatchDelete();
            else showNewRuleEditor();
        });
        binding.btnEditRule.setOnClickListener(v -> {
            if (batchDeleteMode) exitBatchDeleteMode();
            else toggleRuleManageMode();
        });
        // 点 🔍 进独立规则搜索页（搜已安装小程序）
        binding.btnSearchRule.setOnClickListener(v -> {
            Intent it = new Intent(this, HkRuleSearchActivity.class);
            startActivityForResult(it, REQ_RULE_SEARCH);
        });
        // 分组 tab：横向滚动，吸附居中，首项"全部"；长按 tab 进分组管理
        binding.rvRuleGroup.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        new LinearSnapHelper().attachToRecyclerView(binding.rvRuleGroup);
        // 分组分页：每页一个分组，"全部"页带 TYPE 头卡
        rulePagerAdapter = new RulePagerAdapter();
        binding.vpRules.setAdapter(rulePagerAdapter);
        binding.vpRules.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                selectGroupTab(position, false);
            }
        });
        // 右下悬浮按钮：点开快速切换分组弹窗，长按隐藏（5 秒后恢复）
        binding.floatBtn.setOnClickListener(v -> showGroupSwitchPopup());
        binding.floatBtn.setOnLongClickListener(v -> {
            binding.floatBtn.setVisibility(View.INVISIBLE);
            Notify.show("已临时隐藏悬浮按钮");
            binding.floatBtn.postDelayed(() -> {
                if (!isFinishing()) binding.floatBtn.setVisibility(View.VISIBLE);
            }, 5000);
            return true;
        });
        loadCustomRuleData();
    }

    /** ☰菜单：排序方式、列数切换。 */
    private void showHkMainMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, binding.btnMenuRule);
        android.view.Menu m = menu.getMenu();
        m.add(0, 1, 0, ruleSortByName ? "按添加顺序排序" : "按名称排序");
        m.add(0, 2, 0, "切换列数");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                ruleSortByName = !ruleSortByName;
                afterRulesChanged();
            } else if (id == 2) {
                showColumnSwitchPopup();
            }
            return true;
        });
        menu.show();
    }

    /** 记录规则导入（打点占位）。 */
    private void noteRuleImported(String title) {
    }

    /** 记录规则使用（打点占位）。 */
    private void noteRuleUsed(String title) {
    }

    /** 选中分组 tab：高亮 + 居中吸附 + ViewPager 跳页。 */
    private void selectGroupTab(int pos, boolean fromTab) {
        if (groupTabs.isEmpty()) return;
        pos = Math.max(0, Math.min(pos, groupTabs.size() - 1));
        currentGroupPos = pos;
        if (groupChipAdapter != null) groupChipAdapter.setSelected(pos);
        if (!fromTab && binding.vpRules.getCurrentItem() != pos) binding.vpRules.setCurrentItem(pos, true);
        binding.rvRuleGroup.smoothScrollToPosition(pos);
    }

    /** 分组 tab：全部（首项）| 自定义分组（用户顺序）| 规则文件分组（①②序号排序），显示时去序号。 */
    private void buildGroupTabs() {
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
        groupTabs.clear();
        groupTabs.add(""); // 首项"全部"
        groupTabs.addAll(groups);
        if (currentGroupPos >= groupTabs.size()) currentGroupPos = 0;
        List<String[]> pairs = new ArrayList<>();
        for (String g : groupTabs) pairs.add(new String[]{TextUtils.isEmpty(g) ? "全部" : stripGroupPrefix(g), g});
        groupChipAdapter = new ChipAdapter(pairs, currentGroupPos, value -> {
            int pos = groupTabs.indexOf(value);
            if (pos >= 0) selectGroupTab(pos, true);
        });
        groupChipAdapter.setOnLongPick(value -> showGroupManageDialog());
        binding.rvRuleGroup.setAdapter(groupChipAdapter);
        groupDropTargetPos = -1;
        rulePagerAdapter.setGroups(groupTabs);
        rulePagerAdapter.notifyDataSetChanged();
        selectGroupTab(currentGroupPos, false);
    }

    private void updateEmptyState() {
        boolean empty = rules.isEmpty();
        binding.emptyRules.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.vpRules.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.rvRuleGroup.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** 规则集合变化后：同步自定义顺序 → 重建分组 tab 与分页 → 空态。 */
    private void afterRulesChanged() {
        syncCustomOrder();
        buildGroupTabs();
        updateEmptyState();
    }

    /** 刷新全部页面的数据（模式切换/设置变更后）。 */
    private void refreshPageAdapters() {
        if (rulePagerAdapter != null) rulePagerAdapter.refreshAllPages();
    }

    /** 顶部「编辑」按钮：进入拖拽排序模式（按钮文字 编辑/保存 互换，长按卡片拖拽排序，可拖到分组 tab 上分配分组）。 */
    private void toggleRuleManageMode() {
        ruleManageMode = !ruleManageMode;
        binding.btnEditRule.setText(ruleManageMode ? "保存" : "编辑");
        binding.btnSearchRule.setVisibility(ruleManageMode ? View.GONE : View.VISIBLE);
        // 编辑模式禁用 ViewPager 横滑，避免与卡片横向拖拽冲突
        binding.vpRules.setUserInputEnabled(!ruleManageMode);
        if (ruleManageMode && customOrder == null) {
            customOrder = new ArrayList<>();
            for (HkRule r : rules) customOrder.add(r.getTitle());
            saveCustomOrder();
        }
        refreshPageAdapters();
    }

    /** 长按菜单 → 批量删除：进入勾选模式（选中项删除线+灰字），「新增」变「删除选中」。 */
    private void enterBatchDeleteMode() {
        if (ruleManageMode) toggleRuleManageMode();
        batchDeleteMode = true;
        batchDeleteSet.clear();
        binding.btnAddRule.setText("删除选中");
        binding.btnEditRule.setText("取消");
        refreshPageAdapters();
        Notify.show("请点击勾选要删除的规则");
    }

    private void exitBatchDeleteMode() {
        batchDeleteMode = false;
        batchDeleteSet.clear();
        binding.btnAddRule.setText("新增");
        binding.btnEditRule.setText(ruleManageMode ? "保存" : "编辑");
        refreshPageAdapters();
    }

    private void doBatchDelete() {
        if (batchDeleteSet.isEmpty()) {
            Notify.show("请先勾选要删除的规则");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定删除选中的 " + batchDeleteSet.size() + " 条规则吗？删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> new Thread(() -> {
                    for (String t : new ArrayList<>(batchDeleteSet)) HkRuleManager.get().delete(t);
                    App.post(() -> {
                        exitBatchDeleteMode();
                        refreshRules();
                    });
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「新增」：新建规则进表单编辑器。 */
    private void showNewRuleEditor() {
        HkRule r = new HkRule();
        r.setTitle("");
        showRuleFormEditor(r);
    }

    /** 右下悬浮按钮：快速切换分组弹窗。 */
    private void showGroupSwitchPopup() {
        if (groupTabs.isEmpty()) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("切换分组");
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView sv = new ScrollView(this);
        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root).create();
        for (int i = 0; i < groupTabs.size(); i++) {
            final int pos = i;
            String g = groupTabs.get(i);
            TextView tv = new TextView(this);
            tv.setText(TextUtils.isEmpty(g) ? "全部" : stripGroupPrefix(g));
            boolean sel = pos == currentGroupPos;
            tv.setTextColor(sel ? 0xFFB8890F : 0xFF1A1D24);
            tv.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(13), dp(16), dp(13));
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                selectGroupTab(pos, false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            opts.addView(tv, lp);
        }
        sv.addView(opts);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(6);
        if (groupTabs.size() > 6) svLp.height = dp(360);
        root.addView(sv, svLp);
        dialog.show();
    }


    // ================= 编辑模式：拖拽卡片到分组 pills 分配分组 =================

    /** 放开/恢复被拖卡片所在页 RecyclerView 祖先链的裁剪，让被拖卡片能浮到分组 tab 之上。 */
    private void setRulesClipChildren(RecyclerView rv, boolean clip) {
        rv.setClipChildren(clip);
        ViewParent p = rv.getParent();
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
        buildGroupTabs();
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
        int current = getPrefs().getInt(KEY_RULE_SPAN, 2);
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
                if (span == current) return;
                getPrefs().edit().putInt(KEY_RULE_SPAN, span).apply();
                if (rulePagerAdapter != null) rulePagerAdapter.setSpanCount(span);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 分组筛选行：全部 | 自定义分组（用户顺序）| 规则文件分组（①②序号排序），显示时去序号。 */

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

    /** 导入时分组选择回调：group 为 "" 表示不分组（归"全部"）。 */
    private interface OnImportGroupPicked {
        void onPicked(String group);
    }

    /**
     * 导入时弹分组选择器：解析完成后、落盘前让用户手动选分组。
     * 选项：全部（默认选中）| 标准分组 | 用户自定义分组。忽略规则文件自带的野分组名。
     * 用户点选项或取消/点外部，均视为选"全部"（""）。
     * UI 线程调用。
     */
    private void showImportGroupPicker(String titleText, OnImportGroupPicked cb) {
        List<String> values = new ArrayList<>();
        values.add(""); // 全部（默认）
        for (String g : STD_GROUPS) if (!values.contains(g)) values.add(g);
        for (String g : customGroups) {
            if (!TextUtils.isEmpty(g) && !values.contains(g)) values.add(g);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(0xFF1A1D24);
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView sub = new TextView(this);
        sub.setText("选择分组（忽略规则自带的分组名）");
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
        dialog.setOnCancelListener(d -> cb.onPicked(""));
        for (String g : values) {
            TextView tv = new TextView(this);
            tv.setText(TextUtils.isEmpty(g) ? "全部" : g);
            boolean sel = TextUtils.isEmpty(g); // 默认选中"全部"
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
                cb.onPicked(g);
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

    /**
     * 导入落盘后应用用户选择的分组：走 hk_rule_group_map prefs 覆盖，不改 rule.json 原文件。
     * 只对新导入的规则生效（已安装的更新保持原有分组映射不动）。
     * UI 线程调用。
     */
    private void applyImportGroup(List<HkRule> saved, List<Boolean> isNew, String group) {
        if (saved == null || saved.isEmpty()) return;
        String g = group == null ? "" : group;
        for (int i = 0; i < saved.size(); i++) {
            if (isNew != null && i < isNew.size() && !isNew.get(i)) continue;
            HkRule r = saved.get(i);
            noteRuleImported(r.getTitle());
            String fileGroup = r.getGroup() == null ? "" : r.getGroup();
            if (g.equals(fileGroup)) ruleGroupMap.remove(r.getTitle());
            else ruleGroupMap.put(r.getTitle(), g);
        }
        saveRuleGroupMap();
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
            buildGroupTabs();
        }));
        root.addView(addBtn, addLp);
        renderGroupRows(listBox, dialog);
        dialog.setOnDismissListener(d -> {
            buildGroupTabs();
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
                buildGroupTabs();
            }));
            row.addView(groupRowBtn("▼", v -> {
                if (pos >= customGroups.size() - 1) return;
                Collections.swap(customGroups, pos, pos + 1);
                saveCustomGroups();
                renderGroupRows(box, dialog);
                buildGroupTabs();
            }));
            row.addView(groupRowBtn("改名", v -> showGroupNameInput(g, name2 -> {
                customGroups.set(pos, name2);
                for (Map.Entry<String, String> e : ruleGroupMap.entrySet()) {
                    if (g.equals(e.getValue())) e.setValue(name2);
                }
                saveCustomGroups();
                saveRuleGroupMap();
                renderGroupRows(box, dialog);
                buildGroupTabs();
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
                        if (groupTabs.indexOf(g) == currentGroupPos) currentGroupPos = 0;
                        renderGroupRows(box, dialog);
                        buildGroupTabs();
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
                afterRulesChanged();
            });
        }).start();
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

    /** 云口令导入：云端解析 → 弹分组选择器 → 落盘（忽略规则自带的野分组名）。 */
    private void importByCloudCode(String code) {
        if (TextUtils.isEmpty(code)) {
            Notify.show("口令为空");
            return;
        }
        Notify.show("正在从云端获取规则…");
        new Thread(() -> {
            try {
                HkRuleManager.ParsedRule p = HkRuleManager.get().parseByCloudCode(code);
                App.post(() -> checkSingleRuleUpdate(p,
                        keepOld -> finishCloudImport(p, "", keepOld),
                        () -> showImportGroupPicker(p.rule.getTitle(), group -> finishCloudImport(p, group, false))));
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    /**
     * 单规则导入的版本更新检查：导入版本 &gt; 已安装版本时底部滑出"温馨提示"
     * （对等 8.83 RuleUpdateCheckPopup，底部滑出版本 + 复选框）。
     * 是更新：走 onUpdate(keepOldAttrs)；不是更新：走 orElse。
     */
    private void checkSingleRuleUpdate(HkRuleManager.ParsedRule p,
                                       java.util.function.Consumer<Boolean> onUpdate, Runnable orElse) {
        HkRule installed = HkRuleManager.get().getInstalledRule(p.rule.getTitle());
        if (installed != null && p.rule.getVersion() > installed.getVersion()) {
            HkUpdateCheck u = new HkUpdateCheck();
            u.entry = new PickEntry(p, null);
            u.installed = installed;
            showRuleUpdatePrompt(Collections.singletonList(u), (confirmed, keepOld) -> {
                if (!confirmed) {
                    Notify.show("已跳过更新");
                    return;
                }
                onUpdate.accept(keepOld);
            });
            return;
        }
        orElse.run();
    }

    /**
     * 单规则导入落盘后的 UI 刷新（云口令/JS 共用）：
     * 分组合并 + 列表刷新 + "是否打开"底部 snackbar。
     */
    private void refreshAfterSingleImport(HkRuleManager.ParsedRule p, String group,
                                          boolean isNew, boolean wasUpdate, boolean updateGroupToRule) {
        List<HkRule> one = Collections.singletonList(p.rule);
        applyImportGroup(one, Collections.singletonList(isNew), group);
        if (updateGroupToRule) assignRuleGroup(p.rule, p.rule.getGroup());
        Notify.show(wasUpdate ? "更新成功：" + p.rule.getTitle() : "导入成功：" + p.rule.getTitle());
        boolean exists = false;
        for (HkRule r : HkPageActivity.this.rules) {
            if (r.getTitle().equals(p.rule.getTitle())) { exists = true; break; }
        }
        if (!exists) HkPageActivity.this.rules.add(p.rule);
        afterRulesChanged();
        // 导入成功后提示"是否打开"（像海阔原版）
        showOpenAfterImportDialog(one);
    }

    /** 云口令导入：落盘（后台线程）。keepOldAttrs=更新提示复选框"不更新分组、图标和字体颜色"。 */
    private void finishCloudImport(HkRuleManager.ParsedRule p, String group, boolean keepOldAttrs) {
        new Thread(() -> {
            try {
                HkRule installed = HkRuleManager.get().getInstalledRule(p.rule.getTitle());
                boolean isNew = installed == null;
                boolean wasUpdate = !isNew && p.rule.getVersion() > installed.getVersion();
                String json = p.json;
                if (wasUpdate && keepOldAttrs) json = keepOldRuleAttrsJson(json, installed);
                HkRuleManager.get().saveRule(p.rule, json);
                final boolean updateGroup = wasUpdate && !keepOldAttrs;
                App.post(() -> refreshAfterSingleImport(p, group, isNew, wasUpdate, updateGroup));
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
        // 独立规则搜索页返回：打开选中的小程序
        if (requestCode == REQ_RULE_SEARCH && resultCode == RESULT_OK && data != null) {
            String title = data.getStringExtra("title");
            HkRule r = findRuleByTitle(title);
            if (r != null) {
                noteRuleUsed(title);
                openRule(r);
            } else if (!TextUtils.isEmpty(title)) {
                Notify.show("规则不存在：" + title);
            }
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
                        // .hkzip 与 .zip 走同一套解析（parseHkZip：多 rule.json + require.json/libs.zip 附带资源）
                        String lowerName = displayName == null ? "" : displayName.toLowerCase();
                        boolean pkg = lowerName.endsWith(".hkzip") || lowerName.endsWith(".zip");
                        if (pkg) {
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
                    HkRuleManager.ParsedRule p = HkRuleManager.get().parseJsRule(text, fileName);
                    App.post(() -> checkSingleRuleUpdate(p,
                            keepOld -> finishJsImport(p, "", keepOld),
                            () -> showImportGroupPicker(p.rule.getTitle(), group -> finishJsImport(p, group, false))));
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

    /** js: 规则导入：落盘（后台线程）。keepOldAttrs=更新提示复选框"不更新分组、图标和字体颜色"。 */
    private void finishJsImport(HkRuleManager.ParsedRule p, String group, boolean keepOldAttrs) {
        new Thread(() -> {
            try {
                HkRule installed = HkRuleManager.get().getInstalledRule(p.rule.getTitle());
                boolean isNew = installed == null;
                boolean wasUpdate = !isNew && p.rule.getVersion() > installed.getVersion();
                String json = p.json;
                if (wasUpdate && keepOldAttrs) json = keepOldRuleAttrsJson(json, installed);
                HkRuleManager.get().saveRule(p.rule, json);
                final boolean updateGroup = wasUpdate && !keepOldAttrs;
                App.post(() -> refreshAfterSingleImport(p, group, isNew, wasUpdate, updateGroup));
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
     * <p>
     * D4 规则更新检查：先做版本号比对——
     * <ul>
     * <li>导入版本 &gt; 已安装版本：直接更新（成功提示带 v旧→v新）；</li>
     * <li>导入版本 &le; 已安装版本：弹确认框问是否覆盖，避免静默降级。</li>
     * </ul>
     *
     * @param picked 选中的勾选项（UI 线程调用）
     */
    private void importPickedRules(List<PickEntry> picked) {
        if (picked.isEmpty()) {
            Notify.show("未选中任何规则");
            return;
        }
        final List<PickEntry> downgrade = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        for (PickEntry e : picked) {
            HkRule installed = HkRuleManager.get().getInstalledRule(e.parsed.rule.getTitle());
            if (installed != null && e.parsed.rule.getVersion() <= installed.getVersion()) {
                downgrade.add(e);
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.parsed.rule.getTitle())
                        .append("（已安装 v").append(installed.getVersion())
                        .append("，导入 v").append(e.parsed.rule.getVersion()).append("）");
            }
        }
        if (!downgrade.isEmpty()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("版本未更新，是否覆盖？")
                    .setMessage("以下小程序的导入版本不高于已安装版本：\n" + sb + "\n\n覆盖可能导致降级。")
                    .setPositiveButton("全部覆盖", (d, w) -> pickGroupThenImport(picked, false))
                    .setNegativeButton("跳过这些", (d, w) -> {
                        List<PickEntry> rest = new ArrayList<>(picked);
                        rest.removeAll(downgrade);
                        if (rest.isEmpty()) {
                            Notify.show("已跳过，无需导入");
                            return;
                        }
                        pickGroupThenImport(rest, false);
                    })
                    .setNeutralButton("取消", null)
                    .show();
            return;
        }
        // 规则版本更新提示：导入版本 > 已安装版本时底部滑出"温馨提示"确认框
        // （对等 8.83 RuleUpdateCheckPopup，底部滑出版本 + 复选框）。
        final List<HkUpdateCheck> updates = new ArrayList<>();
        for (PickEntry e : picked) {
            HkRule installed = HkRuleManager.get().getInstalledRule(e.parsed.rule.getTitle());
            if (installed != null && e.parsed.rule.getVersion() > installed.getVersion()) {
                HkUpdateCheck u = new HkUpdateCheck();
                u.entry = e;
                u.installed = installed;
                updates.add(u);
            }
        }
        if (!updates.isEmpty()) {
            showRuleUpdatePrompt(updates, (confirmed, keepOldAttrs) -> {
                if (!confirmed) {
                    List<PickEntry> rest = new ArrayList<>(picked);
                    for (HkUpdateCheck u : updates) rest.remove(u.entry);
                    if (rest.isEmpty()) {
                        Notify.show("已跳过更新");
                        return;
                    }
                    pickGroupThenImport(rest, false);
                    return;
                }
                pickGroupThenImport(picked, keepOldAttrs);
            });
            return;
        }
        pickGroupThenImport(picked, false);
    }

    /**
     * 导入前选分组（忽略规则自带的野分组名），再落盘。UI 线程调用。
     * 多个规则共用一次选择，统一应用。
     *
     * @param keepOldAttrs 更新时是否保留旧分组/图标（更新提示复选框"不更新分组、图标和字体颜色"）
     */
    private void pickGroupThenImport(List<PickEntry> picked, boolean keepOldAttrs) {
        String title = picked.size() == 1
                ? picked.get(0).parsed.rule.getTitle()
                : "导入 " + picked.size() + " 个小程序";
        showImportGroupPicker(title, group -> doImportPickedRules(picked, group, keepOldAttrs));
    }

    /** importPickedRules 的落盘实现（后台线程）。 */
    private void doImportPickedRules(List<PickEntry> picked, String group, boolean keepOldAttrs) {
        new Thread(() -> {
            try {
                List<HkRule> rules = new ArrayList<>();
                List<Boolean> isNewList = new ArrayList<>();
                List<String> updatedVer = new ArrayList<>();
                List<HkRule> updatedWithNewAttrs = new ArrayList<>();
                Map<HkRuleManager.HkZipData, List<HkRule>> assets = new HashMap<>();
                for (PickEntry e : picked) {
                    HkRule installed = HkRuleManager.get().getInstalledRule(e.parsed.rule.getTitle());
                    boolean isUpdate = installed != null && e.parsed.rule.getVersion() > installed.getVersion();
                    isNewList.add(installed == null);
                    String json = e.parsed.json;
                    if (isUpdate && keepOldAttrs) json = keepOldRuleAttrsJson(json, installed);
                    HkRuleManager.get().saveRule(e.parsed.rule, json);
                    rules.add(e.parsed.rule);
                    if (isUpdate) {
                        updatedVer.add(e.parsed.rule.getTitle() + " v" + installed.getVersionRaw() + "→v" + e.parsed.rule.getVersionRaw());
                        if (!keepOldAttrs) updatedWithNewAttrs.add(e.parsed.rule);
                    }
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
                boolean wasUpdate = !updatedVer.isEmpty();
                String msg = rules.size() == 1
                        ? (wasUpdate ? "更新成功：" + updatedVer.get(0) : "导入成功：" + rules.get(0).getTitle())
                        : (wasUpdate ? "更新成功 " : "导入成功 ") + rules.size() + " 个小程序";
                final String pickedGroup = group == null ? "" : group;
                App.post(() -> {
                    // 只对新导入的规则应用分组选择；已安装的更新保持原有分组映射不动
                    applyImportGroup(rules, isNewList, pickedGroup);
                    // 复选框未勾选（更新分组、图标）时：更新分组映射为新规则自带分组
                    for (HkRule r : updatedWithNewAttrs) assignRuleGroup(r, r.getGroup());
                    Notify.show(msg);
                    // 直接合并内存中的规则进列表（不走 refreshRules 磁盘重读：
                    // getRules() 在某些机型/场景下返回空会导致导入后列表空，
                    // 内存中的 rules 是刚解析成功的，直接用最可靠）
                    java.util.Set<String> have = new java.util.HashSet<>();
                    for (HkRule r : HkPageActivity.this.rules) have.add(r.getTitle());
                    for (HkRule r : rules) {
                        if (!have.contains(r.getTitle())) {
                            HkPageActivity.this.rules.add(r);
                            have.add(r.getTitle());
                        }
                    }
                    syncCustomOrder();
                    buildGroupTabs();
                updateEmptyState();
                    // 更新/添加成功后提示"是否打开"（像海阔原版）
                    showOpenAfterImportDialog(rules);
                });
            } catch (Exception e) {
                App.post(() -> Notify.show("导入失败：" + e.getMessage()));
            }
        }).start();
    }

    // ================= 海阔底部滑出弹窗（对齐 8.83 XPopup BottomPopupView） =================

    /**
     * 底部滑出 Dialog 基础：底部对齐 + 滑入滑出动画 + 透明背景。
     * 对等 8.83 的 XPopup BottomPopupView（CustomBottomPopup）。
     *
     * @param content   内容 View
     * @param dimAmount 背景变暗量（0f=不变暗）
     */
    private Dialog newHkBottomDialog(View content, float dimAmount) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = dimAmount;
            window.setAttributes(params);
            window.setWindowAnimations(R.style.HkBottomDialogAnim);
        }
        return dialog;
    }

    /**
     * 8.83 式底部确认卡片构建器（对等 pop_rule_update_check.xml / pop_import_rules.xml）：
     * 白底卡片 + 标题 + 内容 + 可选 CheckBox + 底部"取消|确认"分栏（50dp 高、0.5dp 分隔线）。
     * 回调参数：confirmed（点了右按钮），checked（CheckBox 状态，无复选框时为 false）。
     */
    private final class HkBottomCard {
        final Dialog dialog;
        final LinearLayout root;
        CheckBox checkBox;

        HkBottomCard() {
            root = new LinearLayout(HkPageActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.hk_bottom_card);
            root.setPadding(dp(20), dp(18), dp(20), dp(12));
            dialog = newHkBottomDialog(root, 0.45f);
        }

        HkBottomCard title(String t) {
            TextView tv = new TextView(HkPageActivity.this);
            tv.setText(t);
            tv.setTextSize(18);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setTextColor(0xFF1A1D24);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return this;
        }

        HkBottomCard content(CharSequence c) {
            TextView tv = new TextView(HkPageActivity.this);
            tv.setText(c);
            tv.setTextSize(16);
            tv.setTextColor(0xFF1A1D24);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            lp.bottomMargin = dp(12);
            root.addView(tv, lp);
            return this;
        }

        HkBottomCard check(String text, boolean def) {
            checkBox = new CheckBox(HkPageActivity.this);
            checkBox.setText(text);
            checkBox.setChecked(def);
            checkBox.setTextSize(16);
            checkBox.setTextColor(0xFF1A1D24);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            root.addView(checkBox, lp);
            return this;
        }

        HkBottomCard buttons(String left, String right, BiConsumer<Boolean, Boolean> cb) {
            View divider = new View(HkPageActivity.this);
            divider.setBackgroundColor(0xFFEEEEEE);
            root.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            LinearLayout row = new LinearLayout(HkPageActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            TextView tvLeft = bottomRowButton(left);
            TextView tvRight = bottomRowButton(right);
            row.addView(tvLeft, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            View vdiv = new View(HkPageActivity.this);
            vdiv.setBackgroundColor(0xFFEEEEEE);
            row.addView(vdiv, new LinearLayout.LayoutParams(1,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            row.addView(tvRight, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            root.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
            final Dialog d = dialog;
            tvLeft.setOnClickListener(v -> {
                d.dismiss();
                cb.accept(false, checkBox != null && checkBox.isChecked());
            });
            tvRight.setOnClickListener(v -> {
                d.dismiss();
                cb.accept(true, checkBox != null && checkBox.isChecked());
            });
            return this;
        }

        private TextView bottomRowButton(String text) {
            TextView tv = new TextView(HkPageActivity.this);
            tv.setText(text);
            tv.setTextSize(16);
            tv.setTextColor(0xFF666666);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.attr.selectableItemBackground);
            tv.setClickable(true);
            tv.setFocusable(true);
            return tv;
        }

        void show() {
            if (!isFinishing() && !isDestroyed()) dialog.show();
        }
    }

    /**
     * 导入/更新成功后提示"是否打开"（海阔原版行为）。
     * 底部深色 snackbar 横条："是否打开刚导入/更新的规则"，右侧"打开"强调色/"忽略"灰色，
     * 6 秒自动消失，从底部滑出。
     */
    private void showOpenAfterImportDialog(List<HkRule> rules) {
        if (rules == null || rules.isEmpty()) return;
        if (isFinishing() || isDestroyed()) return;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.hk_dark_snackbar);
        bar.setPadding(dp(16), dp(14), dp(8), dp(14));
        TextView msg = new TextView(this);
        msg.setText(rules.size() == 1 ? "是否打开刚导入/更新的规则"
                : "是否打开刚导入/更新的规则（" + rules.size() + " 个）");
        msg.setTextColor(0xFFFFFFFF);
        msg.setTextSize(14);
        bar.addView(msg, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView btnOpen = new TextView(this);
        btnOpen.setText("打开");
        btnOpen.setTextColor(0xFFFFC94D);
        btnOpen.setTextSize(14);
        btnOpen.setTypeface(null, android.graphics.Typeface.BOLD);
        btnOpen.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView btnIgnore = new TextView(this);
        btnIgnore.setText("忽略");
        btnIgnore.setTextColor(0xFF9AA1B0);
        btnIgnore.setTextSize(14);
        btnIgnore.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.addView(btnOpen);
        bar.addView(btnIgnore);
        FrameLayout wrap = new FrameLayout(this);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(16);
        barLp.setMargins(m, m, m, m);
        wrap.addView(bar, barLp);
        final Dialog dialog = newHkBottomDialog(wrap, 0f);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable autoDismiss = () -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {
            }
        };
        dialog.setOnDismissListener(d -> handler.removeCallbacks(autoDismiss));
        btnOpen.setOnClickListener(v -> {
            dialog.dismiss();
            if (rules.size() == 1) openRule(rules.get(0));
            else showOpenRuleListDialog(rules);
        });
        btnIgnore.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        handler.postDelayed(autoDismiss, 6000);
    }

    /** 多个规则时"打开"后的底部选择列表（独立卡片框、有间距）。 */
    private void showOpenRuleListDialog(List<HkRule> rules) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.hk_bottom_card);
        list.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("选择要打开的小程序");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1A1D24);
        title.setGravity(Gravity.CENTER);
        list.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final Dialog dialog = newHkBottomDialog(list, 0.45f);
        for (HkRule r : rules) {
            TextView tv = new TextView(this);
            tv.setText(r.getTitle());
            tv.setTextColor(0xFF1A1D24);
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(R.drawable.dialog_option_card_light);
            tv.setPadding(dp(16), dp(13), dp(16), dp(13));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            final HkRule rule = r;
            tv.setOnClickListener(v -> {
                dialog.dismiss();
                openRule(rule);
            });
            list.addView(tv, lp);
        }
        if (!isFinishing() && !isDestroyed()) dialog.show();
    }

    /** 规则版本更新检查项：待导入条目 + 已安装旧规则。 */
    private static class HkUpdateCheck {
        PickEntry entry;
        HkRule installed;
    }

    /**
     * 规则版本更新提示（对等 8.83 RuleUpdateCheckPopup，底部滑出版本）：
     * 标题"温馨提示" + 更新内容 + CheckBox"不更新分组、图标和字体颜色" + 底部"取消|确认"。
     * 回调参数：confirmed（点了确认），keepOldAttrs（复选框勾选=保留旧分组/图标）。
     */
    private void showRuleUpdatePrompt(List<HkUpdateCheck> updates, BiConsumer<Boolean, Boolean> cb) {
        StringBuilder sb = new StringBuilder("发现以下小程序有新版本：\n");
        for (HkUpdateCheck u : updates) {
            sb.append('\n').append(u.entry.parsed.rule.getTitle())
                    .append("：v").append(u.installed.getVersionRaw())
                    .append(" → v").append(u.entry.parsed.rule.getVersionRaw());
        }
        new HkBottomCard()
                .title("温馨提示")
                .content(sb.toString())
                .check("不更新分组、图标和字体颜色", true)
                .buttons("取消", "确认", cb)
                .show();
    }

    /** 更新时保留旧规则属性的 JSON：checked=不更新分组/图标时，把旧图标写回新 JSON（分组走 ruleGroupMap 本就不动）。 */
    private static String keepOldRuleAttrsJson(String json, HkRule installed) {
        try {
            JSONObject jo = new JSONObject(json);
            jo.put("icon", installed.getIcon());
            return jo.toString();
        } catch (Exception e) {
            return json;
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

        // 对等 8.83 pop_import_rules.xml：导入模式复选框"自动备份所有规则"
        CheckBox cbBackup = new CheckBox(this);
        cbBackup.setText("自动备份所有规则");
        cbBackup.setChecked(false);
        cbBackup.setTextSize(16);
        cbBackup.setTextColor(0xFF1A1D24);
        LinearLayout.LayoutParams backupLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        backupLp.topMargin = (int) (8 * density);
        root.addView(cbBackup, backupLp);
        TextView backupTip = new TextView(this);
        backupTip.setText("勾选后将在导入前把已安装规则备份到 backup 文件夹");
        backupTip.setTextSize(12);
        backupTip.setTextColor(0xFF8A8F98);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = (int) (2 * density);
        tipLp.bottomMargin = (int) (4 * density);
        root.addView(backupTip, tipLp);

        new AlertDialog.Builder(this)
                .setTitle("选择要导入的小程序（" + labels.size() + "）")
                .setView(root)
                .setPositiveButton("导入", (d, w) -> {
                    SparseBooleanArray checked = listView.getCheckedItemPositions();
                    List<PickEntry> picked = new ArrayList<>();
                    for (int i = 0; i < labels.size(); i++) {
                        if (checked.get(i)) picked.add(entries.get(i));
                    }
                    if (cbBackup.isChecked()) backupAllRules();
                    importPickedRules(picked);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 导入前自动备份所有已安装规则（对等 8.83 导入模式"自动备份所有规则"）：
     * 把 plugins/hk 下的规则 json 拷到 filesDir/backup/时间戳/。
     */
    private void backupAllRules() {
        try {
            File srcDir = HkRuleManager.get().getDir();
            File[] files = srcDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null || files.length == 0) {
                Notify.show("没有可备份的规则");
                return;
            }
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            File dstDir = new File(getFilesDir(), "backup/" + ts);
            if (!dstDir.mkdirs() && !dstDir.isDirectory()) {
                Notify.show("备份失败：无法创建目录");
                return;
            }
            int ok = 0;
            byte[] buf = new byte[8192];
            for (File f : files) {
                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(new File(dstDir, f.getName()))) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok++;
                } catch (Exception ignored) {
                }
            }
            Notify.show(ok > 0 ? "已备份 " + ok + " 个规则到 backup/" + ts : "备份失败");
        } catch (Exception e) {
            Notify.show("备份失败：" + e.getMessage());
        }
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
        String full = code + "\n\n小程序：" + title;
        TextView tv = new TextView(this);
        tv.setText(full);
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
                        cm.setPrimaryClip(ClipData.newPlainText("hk_cloud_code", full));
                        Notify.show("口令已复制");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 导出为文件：弹窗选择格式（.json / .hkzip / .zip），保存到 Download 目录并调起分享。 */
    private void exportAsFile(HkRule rule) {
        new AlertDialog.Builder(this)
                .setTitle("选择导出格式")
                .setItems(new String[]{"导出为 .json 文件", "导出为 .hkzip 包", "导出为 .zip 包"}, (d, which) -> {
                    if (which == 0) doExportAsJsonFile(rule);
                    else if (which == 1) doExportAsHkZip(rule);
                    else doExportAsZip(rule);
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

    /** 导出规则为 .zip 包（内容同 .hkzip）：保存到 Download 目录并调起分享。 */
    private void doExportAsZip(HkRule rule) {
        new Thread(() -> {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                java.io.File out = HkRuleManager.get().exportAsZip(rule.getTitle(), dir);
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

    private void showDeleteRuleConfirm(HkRule rule) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("确定删除「").append(rule.getTitle()).append("」吗？\n");
        int warnStart = sb.length();
        sb.append("注意删除后无法恢复！");
        sb.setSpan(new ForegroundColorSpan(0xFFE53935), warnStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), warnStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        new HkBottomCard()
                .title("删除规则")
                .content(sb)
                .buttons("取消", "确定", (confirmed, checked) -> {
                    if (!confirmed) return;
                    new Thread(() -> {
                        HkRuleManager.get().delete(rule.getTitle());
                        App.post(this::refreshRules);
                    }).start();
                })
                .show();
    }

    /** "全部"页 TYPE 头卡数据（对齐 8.83 TYPE_CODE 伪规则）。 */
    private static class TypeHeader {
        final String title;
        final int count;

        TypeHeader(String title, int count) {
            this.title = title;
            this.count = count;
        }
    }

    /** 8.83 RuleType 标题与顺序：聚合 / 视频 / 音频 / 直播 / 漫画 / 阅读 / 图集 / 资讯 / 工具 / 其它 */
    private static final String[] TYPE_ORDER = {"聚合", "视频", "音频", "直播", "漫画", "阅读", "图集", "资讯", "工具", "其它"};

    private static String typeTitleOf(String type) {
        if (type == null) type = "";
        switch (type) {
            case "video": return "视频";
            case "picture": return "图集";
            case "audio":
            case "music": return "音频";
            case "live": return "直播";
            case "cartoon":
            case "comic": return "漫画";
            case "novel":
            case "read":
            case "book": return "阅读";
            case "news": return "资讯";
            case "tool": return "工具";
            case "aggregate": return "聚合";
            default: return "其它";
        }
    }

    /** ViewPager2 分页适配器：每页一个分组（"全部"+各分组），"全部"页带 TYPE 头卡。 */
    private class RulePagerAdapter extends RecyclerView.Adapter<RulePagerAdapter.PageHolder> {
        private final List<String> groups = new ArrayList<>();
        private final List<RulePageAdapter> pages = new ArrayList<>();
        private final List<GridLayoutManager> layouts = new ArrayList<>();

        void setGroups(List<String> g) {
            groups.clear();
            groups.addAll(g);
        }

        void refreshAllPages() {
            for (RulePageAdapter p : pages) p.refresh();
        }

        /** 显示列数变更：更新所有页的列数。 */
        void setSpanCount(int span) {
            for (GridLayoutManager glm : layouts) glm.setSpanCount(span);
            refreshAllPages();
        }

        class PageHolder extends RecyclerView.ViewHolder {
            final RulePageAdapter adapter;

            PageHolder(View itemView, RulePageAdapter adapter) {
                super(itemView);
                this.adapter = adapter;
            }
        }

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            final RulePageAdapter adapter = new RulePageAdapter();
            final GridLayoutManager glm = new GridLayoutManager(ctx, getPrefs().getInt(KEY_RULE_SPAN, 2));
            glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return adapter.isHeader(position) ? glm.getSpanCount() : 1;
                }
            });
            final RecyclerView rv = new RecyclerView(ctx);
            rv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            rv.setLayoutManager(glm);
            rv.addItemDecoration(new GridSpace());
            rv.setClipToPadding(false);
            int pad = dp(7);
            rv.setPadding(pad, dp(2), pad, dp(96));
            rv.setAdapter(adapter);
            // 编辑模式拖拽排序：长按卡片拖动（仅编辑模式启用，TYPE 头卡不可拖）
            // 拖到分组 tab 上松手 = 把卡片分配进该分组（换位回滚，仅做分组）
            ItemTouchHelper th = new ItemTouchHelper(new ItemTouchHelper.Callback() {
                @Override
                public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                    if (!ruleManageMode || adapter.isHeader(vh.getBindingAdapterPosition())) return 0;
                    int drag = ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
                    return makeMovementFlags(drag, 0);
                }

                @Override
                public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                      @NonNull RecyclerView.ViewHolder target) {
                    int from = vh.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false;
                    if (adapter.isHeader(to)) return false;
                    adapter.moveItem(from, to);
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
                        Object o = adapter.getItem(ruleDragFromPos);
                        if (o instanceof HkRule) ruleDragTitle = ((HkRule) o).getTitle();
                        ruleDragOrderSnapshot = new ArrayList<>();
                        for (HkRule r : rules) ruleDragOrderSnapshot.add(r.getTitle());
                        // 抬高被拖卡片并放开祖先裁剪，让它能浮到分组 tab 之上
                        vh.itemView.setElevation(dp(12));
                        setRulesClipChildren(rv, false);
                    }
                }

                @Override
                public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView r,
                                        @NonNull RecyclerView.ViewHolder vh,
                                        float dX, float dY, int actionState, boolean isCurrentlyActive) {
                    super.onChildDraw(c, r, vh, dX, dY, actionState, isCurrentlyActive);
                    if (ruleManageMode && isCurrentlyActive
                            && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        updateGroupDropTarget(vh.itemView);
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                    super.clearView(r, vh);
                    vh.itemView.setElevation(0);
                    setRulesClipChildren(rv, true);
                    int target = groupDropTargetPos;
                    setGroupDropTarget(-1);
                    if (ruleManageMode && target >= 0 && target < groupTabs.size() && ruleDragTitle != null) {
                        HkRule rule = findRuleByTitle(ruleDragTitle);
                        restoreDragOrderSnapshot();
                        if (rule != null) {
                            String g = groupTabs.get(target);
                            assignRuleGroup(rule, g);
                            Notify.show("已移入「" + (TextUtils.isEmpty(g) ? "全部" : stripGroupPrefix(g)) + "」");
                        }
                    }
                    ruleDragHolder = null;
                    ruleDragFromPos = RecyclerView.NO_POSITION;
                    ruleDragTitle = null;
                    ruleDragOrderSnapshot = null;
                }
            });
            th.attachToRecyclerView(rv);
            pages.add(adapter);
            layouts.add(glm);
            return new PageHolder(rv, adapter);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.adapter.setGroup(groups.get(position));
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }
    }

    /** 单页规则列表："全部"页 = TYPE 头卡 + 全部规则；分组页 = 该组规则。 */
    private class RulePageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_RULE = 1;
        private static final int TYPE_HEAD = 2;
        private final List<Object> items = new ArrayList<>();
        private String group = "";

        void setGroup(String g) {
            group = g == null ? "" : g;
            refresh();
        }

        void refresh() {
            items.clear();
            List<HkRule> src = new ArrayList<>();
            for (HkRule r : rules) {
                if (group.isEmpty() || group.equals(effectiveGroup(r))) src.add(r);
            }
            if (group.isEmpty()) {
                // "全部"页：按类型计数，TYPE 头卡排在前面（对齐 8.83 addTypeItems）
                Map<String, Integer> count = new HashMap<>();
                for (HkRule r : src) {
                    String t = typeTitleOf(r.getType());
                    count.put(t, count.containsKey(t) ? count.get(t) + 1 : 1);
                }
                for (String t : TYPE_ORDER) {
                    if (count.containsKey(t)) items.add(new TypeHeader(t, count.get(t)));
                }
            }
            items.addAll(src);
            notifyDataSetChanged();
        }

        boolean isHeader(int pos) {
            return pos >= 0 && pos < items.size() && items.get(pos) instanceof TypeHeader;
        }

        Object getItem(int pos) {
            return (pos >= 0 && pos < items.size()) ? items.get(pos) : null;
        }

        /** 拖拽换位：同步页内显示与全局 rules，持久化自定义顺序。 */
        void moveItem(int from, int to) {
            Object mo = items.get(from);
            Object oo = items.get(to);
            if (!(mo instanceof HkRule) || !(oo instanceof HkRule)) return;
            HkRule moved = (HkRule) mo;
            HkRule other = (HkRule) oo;
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

        class RuleHolder extends RecyclerView.ViewHolder {
            TextView icon, name, tag;
            android.widget.ImageView ivIcon;
            SwitchCompat sw;
            ImageButton more;

            RuleHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.tv_icon);
                ivIcon = v.findViewById(R.id.iv_icon);
                name = v.findViewById(R.id.tv_name);
                tag = v.findViewById(R.id.tv_group_tag);
                sw = v.findViewById(R.id.sw_enable);
                more = v.findViewById(R.id.btn_more);
            }
        }

        class HeadHolder extends RecyclerView.ViewHolder {
            TextView title, desc;

            HeadHolder(View v) {
                super(v);
                title = v.findViewById(R.id.tv_type_title);
                desc = v.findViewById(R.id.tv_type_desc);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof TypeHeader ? TYPE_HEAD : TYPE_RULE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEAD) {
                return new HeadHolder(inf.inflate(R.layout.item_hk_rule_type, parent, false));
            }
            return new RuleHolder(inf.inflate(R.layout.item_hk_rule, parent, false));
        }

        /** 图标：URL 则加载图片（失败用 8.83 同款默认图标占位，可重试），# 开头则设背景色，否则默认"海"字；形状跟随"图标设置"。 */
        private void applyIconBg(RuleHolder h, String iconDef, String title) {
            TextView icon = h.icon;
            android.widget.ImageView ivIcon = h.ivIcon;
            int shape = getPrefs().getInt(KEY_RULE_ICON_SHAPE, 1);
            if (shape == 2) { // 不显示
                icon.setVisibility(View.GONE);
                ivIcon.setVisibility(View.GONE);
                return;
            }
            boolean isUrl = !TextUtils.isEmpty(iconDef)
                    && (iconDef.startsWith("http://") || iconDef.startsWith("https://"));
            if (isUrl) {
                icon.setVisibility(View.GONE);
                ivIcon.setVisibility(View.VISIBLE);
                ivIcon.setClipToOutline(true);
                ivIcon.setBackgroundResource(R.drawable.hk_miniapp_icon_bg);
                try {
                    ImgUtil.loadRuleIcon(title, iconDef, ivIcon);
                } catch (Exception ignored) {
                    ivIcon.setVisibility(View.GONE);
                    icon.setVisibility(View.VISIBLE);
                }
                return;
            }
            ivIcon.setVisibility(View.GONE);
            icon.setVisibility(View.VISIBLE);
            GradientDrawable gd = new GradientDrawable();
            if (shape == 0) gd.setShape(GradientDrawable.OVAL);
            else gd.setCornerRadius(dp(10));
            int color = 0xFFF6EED6;
            if (!TextUtils.isEmpty(iconDef) && iconDef.startsWith("#")) {
                try {
                    color = Color.parseColor(iconDef);
                } catch (Exception ignored) {
                }
            }
            gd.setColor(color);
            icon.setBackground(gd);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            Object o = items.get(position);
            if (o instanceof TypeHeader) {
                TypeHeader th = (TypeHeader) o;
                HeadHolder h = (HeadHolder) vh;
                h.title.setText(th.title);
                h.desc.setText("共 " + th.count + " 个");
                h.itemView.setOnClickListener(v -> {
                    // TYPE 名与分组名一致时跳到该分组页
                    int pos = groupTabs.indexOf(th.title);
                    if (pos > 0) selectGroupTab(pos, false);
                });
                return;
            }
            HkRule rule = (HkRule) o;
            RuleHolder h = (RuleHolder) vh;
            String title = rule.getTitle();
            h.name.setText(TextUtils.isEmpty(title) ? "未命名规则" : title);
            applyIconBg(h, rule.getIcon(), title);
            float alpha = rule.isEnabled() ? 1f : 0.5f;
            h.icon.setAlpha(alpha);
            if (h.ivIcon != null) h.ivIcon.setAlpha(alpha);
            boolean selected = batchDeleteMode && batchDeleteSet.contains(title);
            if (selected) {
                // 批量删除勾中：删除线 + 灰字
                h.name.setPaintFlags(h.name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                h.name.setTextColor(0xFF8A8F98);
                h.name.setAlpha(1f);
            } else {
                h.name.setPaintFlags(h.name.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                h.name.setTextColor(0xFF101216);
                // 禁用态：图标半透示意，文字保持可读（0.75f），深色字必须看得清
                h.name.setAlpha(rule.isEnabled() ? 1f : 0.75f);
            }
            // 管理模式：显示启用开关 + 分组标签；批量删除模式都隐藏
            h.sw.setVisibility(ruleManageMode && !batchDeleteMode ? View.VISIBLE : View.GONE);
            h.more.setVisibility(View.GONE);
            h.tag.setVisibility(ruleManageMode && !batchDeleteMode ? View.VISIBLE : View.GONE);
            if (ruleManageMode && !batchDeleteMode) {
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
                Object oo = getItem(pos);
                if (!(oo instanceof HkRule)) return;
                HkRule r = (HkRule) oo;
                r.setEnabled(checked);
                HkRuleManager.get().setEnabled(r.getTitle(), checked);
                notifyItemChanged(pos);
            });
            if (batchDeleteMode) {
                // 批量删除：点卡片勾选/取消勾选
                h.itemView.setOnClickListener(v -> {
                    int pos = h.getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    Object oo = getItem(pos);
                    if (!(oo instanceof HkRule)) return;
                    String t = ((HkRule) oo).getTitle();
                    if (batchDeleteSet.contains(t)) batchDeleteSet.remove(t);
                    else batchDeleteSet.add(t);
                    notifyItemChanged(pos);
                });
                h.itemView.setOnLongClickListener(null);
            } else if (ruleManageMode) {
                // 编辑模式：点卡片分配分组（长按拖拽排序）
                h.itemView.setOnClickListener(v -> showGroupPicker(rule));
                h.itemView.setOnLongClickListener(null);
            } else {
                // 普通模式：点卡片打开小程序，长按弹出菜单
                h.itemView.setOnClickListener(v -> {
                    noteRuleUsed(rule.getTitle());
                    openRule(rule);
                });
                h.itemView.setOnLongClickListener(v -> {
                    showRuleCardMenu(rule);
                    return true;
                });
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    /** 卡片长按菜单：编辑 / 置顶 / 置底 / 分享 / 批量删除 / 规则详情 / 删除（选项独立卡片框、有间距）。 */
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
        String[] labels = {"编辑", "置顶", "置底", "分享", "批量删除", "规则详情", "删除"};
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
                if (which == 0) showRuleFormEditor(rule);
                else if (which == 1) pinRule(rule, true);
                else if (which == 2) pinRule(rule, false);
                else if (which == 3) showRuleShareOptions(rule);
                else if (which == 4) enterBatchDeleteMode();
                else if (which == 5) showRuleDetail(rule);
                else showDeleteRuleConfirm(rule);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 置顶 / 置底：调整全局顺序并持久化。 */
    private void pinRule(HkRule rule, boolean top) {
        if (customOrder == null) {
            customOrder = new ArrayList<>();
            for (HkRule r : rules) customOrder.add(r.getTitle());
        }
        rules.remove(rule);
        if (top) rules.add(0, rule);
        else rules.add(rule);
        customOrder.remove(rule.getTitle());
        if (top) customOrder.add(0, rule.getTitle());
        else customOrder.add(rule.getTitle());
        saveCustomOrder();
        buildGroupTabs();
        Notify.show("已" + (top ? "置顶" : "置底") + "：" + rule.getTitle());
    }

    /** 规则详情：导入时间 / 最近使用 / 最后更新 / 错误信息（对齐 8.83 FileDetailPopup）。 */
    private void showRuleDetail(HkRule rule) {
        String title = rule.getTitle();
        String[][] rows = {
                {"标题", TextUtils.isEmpty(title) ? "未命名规则" : title},
                {"导入时间", formatRuleTime(ruleImportTime.get(title))},
                {"最近使用", formatRuleTime(ruleLastUse.get(title))},
                {"最后更新", formatRuleTime(HkRuleManager.get().ruleModified(title))},
                {"错误信息", findRuleError(title)},
        };
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView tvTitle = new TextView(this);
        tvTitle.setText("规则详情");
        tvTitle.setTextColor(0xFF1A1D24);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        root.addView(tvTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, dp(10), 0, dp(10));
            TextView k = new TextView(this);
            k.setText(row[0]);
            k.setTextColor(0xFF8A8F98);
            k.setTextSize(14);
            k.setMinWidth(dp(72));
            TextView v = new TextView(this);
            v.setText(row[1]);
            v.setTextColor(0xFF1A1D24);
            v.setTextSize(14);
            line.addView(k);
            line.addView(v, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(line);
            if (!row[0].equals("错误信息")) {
                View div = new View(this);
                div.setBackgroundColor(0xFFE3E6EB);
                root.addView(div, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        new AlertDialog.Builder(this)
                .setView(root)
                .setPositiveButton("确定", null)
                .show();
    }

    private static String formatRuleTime(Long ts) {
        if (ts == null || ts <= 0) return "无";
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new java.util.Date(ts));
        } catch (Exception e) {
            return "无";
        }
    }

    /** 规则解析失败日志里找该规则最近一条错误（hk_rule_error.log）。 */
    private String findRuleError(String title) {
        try {
            java.io.File log = new java.io.File(getFilesDir(), "hk_rule_error.log");
            if (!log.exists() || TextUtils.isEmpty(title)) return "无";
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(log));
            String line, last = null;
            while ((line = br.readLine()) != null) {
                if (line.contains(title)) last = line;
            }
            br.close();
            if (last == null) return "无";
            int i = last.indexOf('|');
            return i >= 0 && i + 1 < last.length() ? last.substring(i + 1).trim() : last;
        } catch (Exception e) {
            return "无";
        }
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
                if (which == 0) showCloudChannelPicker(rule);
                else exportAsFile(rule);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /** 云口令分享：先选择云通道（对照 8.83 的 7 个云剪贴板），再走对应上传流程。 */
    private void showCloudChannelPicker(HkRule rule) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("选择云口令通道");
        title.setTextColor(0xFF6B7280);
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 8.83 原文的 7 个云剪贴板：云1 pastebin、云2 textdb、云5 cmd.im、云6 pasteme、云7 note.ms、云9 txtpad、云10 hastebin
        String[] labels = {"云1 · pastebin", "云2 · textdb（官方通用）", "云5 · cmd.im（短口令）",
                "云6 · pasteme（白阑）", "云7 · note.ms", "云9 · txtpad", "云10 · hastebin"};
        int[] clipNums = {1, 2, 5, 6, 7, 9, 10};
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
                int clipNum = clipNums[which];
                if (clipNum == 2) exportAsCloudCode(rule);
                else if (clipNum == 5) exportAsShortCloudCode(rule);
                else exportAsCloudClipCode(rule, clipNum);
            });
            root.addView(tv, lp);
        }
        dialog.show();
    }

    /**
     * 导出为云口令（云1/6/7/9/10）：走 HkCloudClip 上传，生成 8.83 格式口令，
     * 8.83 官方 App 可导入。
     */
    private void exportAsCloudClipCode(HkRule rule, int clipNum) {
        Notify.show("正在上传云端…");
        new Thread(() -> {
            try {
                String json = HkRuleManager.get().loadRuleJson(rule.getTitle());
                if (json == null || json.trim().isEmpty())
                    throw new IllegalArgumentException("规则不存在：" + rule.getTitle());
                String url = com.fongmi.android.tv.api.hk.HkShareHelper.shareToCloudClip(json, clipNum);
                String code = buildCloudCode(clipNum, url);
                App.post(() -> showExportCodeDialog(rule.getTitle(), code));
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 按 8.83 分享格式，由上传返回的 URL 生成云口令（8.83 parse 原文的逆操作）。 */
    private String buildCloudCode(int clipNum, String url) {
        String id = url.substring(url.lastIndexOf('/') + 1).split("[\\s?#]")[0];
        switch (clipNum) {
            case 1: return "云1oooole/raw/" + id;         // 8.83 PastebinImporter.parse: 云1oooole→https://pastebin.com，要求 https://pastebin.com/raw/ 开头
            case 6: return "云6oooole/xxxxxx/" + id;       // 8.83 BailanImporter: 云6oooole/xxxxxx/{path}(@{pwd})
            case 7: return "云7oooole/" + id;              // 8.83 NotesImporter.share: https://note.ms/→云7oooole
            case 9: return "云9oooole/" + id;              // 8.83 TxtPadImporter.parse: 云9oooole→https://txtpbbd.cn
            case 10: return "云10oooole/share/a/b/" + id; // 8.83 HastebinImporter: 云10oooole/share/a/b/
            default: return url;
        }
    }

    /** 表单式编辑小程序（按 8.83 ArticleListRuleEditActivity）：四个可折叠分区，保存写回 rule.json。 */
    private void showRuleFormEditor(HkRule rule) {
        final String oldTitle = rule.getTitle() == null ? "" : rule.getTitle();
        final boolean isNew = TextUtils.isEmpty(oldTitle);
        final JSONObject obj;
        try {
            String raw = isNew ? null : HkRuleManager.get().loadRuleJson(oldTitle);
            obj = new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw);
        } catch (Exception e) {
            com.fongmi.android.tv.utils.ToastUtil.show(this, "规则读取失败：" + e.getMessage());
            return;
        }
        final Map<String, EditText> fields = new HashMap<>();

        final Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F3F5);

        // ---- 顶栏：取消 / 编辑小程序 / 保存 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFFFFFFFF);
        bar.setPadding(dp(6), dp(8), dp(6), dp(8));
        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xFF8A8F98);
        btnCancel.setTextSize(16);
        btnCancel.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(isNew ? "新建小程序" : "编辑小程序");
        tvTitle.setTextColor(0xFF1A1D24);
        tvTitle.setTextSize(17);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        TextView btnSave = new TextView(this);
        btnSave.setText("保存");
        btnSave.setTextColor(0xFFB8890F);
        btnSave.setTextSize(16);
        btnSave.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSave.setPadding(dp(14), dp(10), dp(14), dp(10));
        bar.addView(btnCancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btnSave, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE3E6EB);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // ---- 滚动表单 ----
        ScrollView sv = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(8), dp(4), dp(32));
        sv.addView(form, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int text = EditorInfo.TYPE_CLASS_TEXT;
        int ml = EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;

        // 基础规则
        LinearLayout secBasic = addFormSection(form, "基础规则");
        addFormField(secBasic, fields, "title", "小程序名称", "小程序名称（title）", obj, null, false, text);
        addFormPicker(secBasic, fields, "type", "小程序类别", "小程序类别（type）", obj, "其它",
                new String[]{"视频", "音乐", "漫画", "小说", "图片", "工具", "其它"});
        addFormField(secBasic, fields, "author", "小程序作者", "小程序作者（author）", obj, null, false, text);
        addFormField(secBasic, fields, "version", "小程序版本", "小程序版本（version）", obj, "1", false, EditorInfo.TYPE_CLASS_NUMBER);
        addFormField(secBasic, fields, "group", "小程序分组", "小程序分组（名称前加①、②等序号可排序）", obj, null, false, text);
        addFormField(secBasic, fields, "icon", "小程序图标地址", "小程序图标地址（支持色值）", obj, null, false, text);
        addFormField(secBasic, fields, "url", "小程序链接", "小程序链接（可不填，可只填搜索相关内容）", obj, null, false, text);
        addFormPicker(secBasic, fields, "col_type", "显示样式", "显示样式（col_type）", obj, "movie_3", COL_TYPE_OPTIONS);

        // 分类规则
        LinearLayout secClass = addFormSection(form, "分类规则");
        addFormField(secClass, fields, "class_name", "分类名称", "分类名称（class_name）", obj, null, false, text);
        addFormField(secClass, fields, "class_url", "分类替换词", "分类替换词（class_url）", obj, null, false, text);
        addFormField(secClass, fields, "area_name", "地区名称", "地区名称（area_name）", obj, null, false, text);
        addFormField(secClass, fields, "area_url", "地区替换词", "地区替换词（area_url）", obj, null, false, text);
        addFormField(secClass, fields, "year_name", "年代名称", "年代名称（year_name）", obj, null, false, text);
        addFormField(secClass, fields, "year_url", "年代替换词", "年代替换词（year_url）", obj, null, false, text);
        addFormField(secClass, fields, "sort_name", "排序名称", "排序名称（sort_name）", obj, null, false, text);
        addFormField(secClass, fields, "sort_url", "排序替换词", "排序替换词（sort_url）", obj, null, false, text);

        // 解析规则
        LinearLayout secJiexi = addFormSection(form, "解析规则");
        addFormField(secJiexi, fields, "find_rule", "解析规则", "解析规则（列表;标题;图片;描述;链接）", obj, null, true, ml);
        addFormField(secJiexi, fields, "search_url", "搜索链接", "搜索链接（**作为占位符，支持/开头不写域名）", obj, null, false, text);
        addFormField(secJiexi, fields, "searchFind", "搜索解析规则", "搜索解析规则（列表;标题;链接;描述;详情;图片）", obj, null, true, ml);
        addFormPicker(secJiexi, fields, "detail_col_type", "二级列表显示样式", "二级列表显示样式（detail_col_type）", obj, "movie_1", COL_TYPE_OPTIONS);
        addFormField(secJiexi, fields, "detail_find_rule", "二级列表解析规则", "二级列表解析规则（列表;标题;图片;描述;链接）", obj, null, true, ml);
        addFormPicker(secJiexi, fields, "sdetail_col_type", "搜索二级显示样式", "搜索二级列表显示样式（sdetail_col_type）", obj, "movie_1", COL_TYPE_OPTIONS);
        addFormField(secJiexi, fields, "sdetail_find_rule", "搜索二级解析规则", "搜索二级列表解析规则（列表;标题;图片;描述;链接）", obj, null, true, ml);
        addFormField(secJiexi, fields, "last_chapter_rule", "最新章节解析规则", "最新章节解析规则", obj, null, true, ml);

        // 其它规则
        LinearLayout secOther = addFormSection(form, "其它规则");
        addFormPicker(secOther, fields, "ua", "全局UA", "全局UA（以url里面的ua优先）", obj, "auto",
                new String[]{"auto", "mobile", "pc"});
        addFormField(secOther, fields, "preRule", "JS预处理", "JS预处理（在解析规则和搜索解析规则执行前执行）", obj, null, true, ml);
        addFormField(secOther, fields, "proxy", "网络请求代理", "网络请求代理（一行一个，替换词=>代理）", obj, null, true, ml);

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> saveRuleForm(dialog, obj, fields, oldTitle));

        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFFF2F3F5));
            w.getDecorView().setPadding(0, 0, 0, 0);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    /** 表单分区（8.83 风格）：灰色标题可折叠 + 白色圆角卡片。 */
    private LinearLayout addFormSection(LinearLayout parent, String title) {
        final TextView header = new TextView(this);
        header.setTextColor(0xFF666666);
        header.setTextSize(14);
        header.setPadding(dp(16), dp(14), dp(16), dp(6));
        parent.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.dialog_option_card_light);
        card.setPadding(dp(14), dp(4), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        parent.addView(card, lp);
        final boolean[] expanded = {true};
        header.setText(title + "  ▲");
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            card.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            header.setText(title + (expanded[0] ? "  ▲" : "  ▼"));
        });
        return card;
    }

    /** 表单字段：小标签 + 输入框（值缺失时用 defVal）。 */
    private EditText addFormField(LinearLayout parent, Map<String, EditText> fields, String key,
                                  String label, String hint, JSONObject obj, String defVal,
                                  boolean multiLine, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF8A8F98);
        tv.setTextSize(12);
        tv.setPadding(0, dp(10), 0, dp(4));
        parent.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText et = new EditText(this);
        et.setBackgroundResource(R.drawable.bg_input_light);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setTextSize(14);
        et.setTextColor(0xFF1A1D24);
        et.setHint(hint);
        et.setHintTextColor(0xFFB0B6BE);
        et.setInputType(inputType);
        if (multiLine) {
            et.setSingleLine(false);
            et.setMinLines(3);
            et.setGravity(Gravity.START | Gravity.TOP);
        }
        String val = obj.has(key) ? obj.optString(key, "") : (defVal == null ? "" : defVal);
        et.setText(val);
        parent.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        fields.put(key, et);
        return et;
    }

    /** 表单选择器：不可编辑的输入框，点击弹出选项卡片（独立卡片+间距，当前选中金色加粗）。 */
    private void addFormPicker(LinearLayout parent, Map<String, EditText> fields, String key,
                               String label, String hint, JSONObject obj, String defVal, String[] options) {
        final EditText et = addFormField(parent, fields, key, label, hint, obj, defVal,
                false, EditorInfo.TYPE_CLASS_TEXT);
        et.setFocusable(false);
        et.setFocusableInTouchMode(false);
        et.setClickable(true);
        et.setOnClickListener(v -> {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(16), dp(20), dp(16));
            TextView tvTitle = new TextView(this);
            tvTitle.setText(label);
            tvTitle.setTextColor(0xFF1A1D24);
            tvTitle.setTextSize(16);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            root.addView(tvTitle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
            ScrollView sv = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(420));
            svLp.topMargin = dp(10);
            root.addView(sv, svLp);
            String cur = et.getText().toString();
            for (String op : options) {
                TextView tv = new TextView(this);
                tv.setText(op);
                boolean sel = op.equals(cur);
                tv.setTextColor(sel ? 0xFFB8890F : 0xFF1A1D24);
                tv.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tv.setTextSize(15);
                tv.setGravity(Gravity.CENTER);
                tv.setBackgroundResource(R.drawable.dialog_option_card_light);
                tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                tv.setOnClickListener(v2 -> {
                    dlg.dismiss();
                    et.setText(op);
                });
                list.addView(tv, lp);
            }
            dlg.show();
        });
    }

    /** 表单保存：写回 JSON → importJson 校验落盘 → 改名则删旧文件并迁移分组/排序/开关 → 刷新列表。 */
    private void saveRuleForm(Dialog dialog, JSONObject obj, Map<String, EditText> fields, String oldTitle) {
        String newTitle = fields.get("title").getText().toString().trim();
        if (newTitle.isEmpty()) {
            com.fongmi.android.tv.utils.ToastUtil.show(this, "请完善规则，title不能为空");
            return;
        }
        if (!newTitle.matches("[^/:*?\"<>|]+")) {
            com.fongmi.android.tv.utils.ToastUtil.show(this, "规则名不能包含/:*?\"<>|等特殊字符");
            return;
        }
        try {
            for (Map.Entry<String, EditText> e : fields.entrySet()) {
                String k = e.getKey();
                String val = e.getValue().getText().toString();
                if ("version".equals(k)) {
                    int vv = obj.optInt("version", 1);
                    try {
                        vv = Integer.parseInt(val.trim());
                    } catch (Exception ignored) {
                    }
                    obj.put(k, vv);
                } else {
                    obj.put(k, val);
                }
            }
        } catch (Exception e) {
            com.fongmi.android.tv.utils.ToastUtil.show(this, "保存失败：" + e.getMessage());
            return;
        }
        final String json = obj.toString();
        final boolean wasEnabled = HkRuleManager.get().isEnabled(oldTitle);
        new Thread(() -> {
            try {
                HkRuleManager.get().importJson(json);
                if (!oldTitle.equals(newTitle)) {
                    HkRuleManager.get().delete(oldTitle);
                    HkRuleManager.get().setEnabled(newTitle, wasEnabled);
                    App.post(() -> migrateRuleTitle(oldTitle, newTitle));
                }
                App.post(() -> {
                    dialog.dismiss();
                    refreshRules();
                    com.fongmi.android.tv.utils.ToastUtil.show(HkPageActivity.this, "已保存");
                });
            } catch (Exception e) {
                App.post(() -> com.fongmi.android.tv.utils.ToastUtil.show(
                        HkPageActivity.this, "保存失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 表单编辑改名后：迁移分组映射与自定义排序。 */
    private void migrateRuleTitle(String oldTitle, String newTitle) {
        if (ruleGroupMap.containsKey(oldTitle)) {
            ruleGroupMap.put(newTitle, ruleGroupMap.remove(oldTitle));
            saveRuleGroupMap();
        }
        if (customOrder != null) {
            int i = customOrder.indexOf(oldTitle);
            if (i >= 0) {
                customOrder.set(i, newTitle);
                saveCustomOrder();
            }
        }
        buildGroupTabs();
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
        contentAdapter = new ContentAdapter(videos, true);
        binding.rvVideos.setAdapter(contentAdapter);
        // 下拉刷新（SwipeRefreshLayout；原 SmartRefreshLayout 已从 Maven 下架）
        binding.refreshContent.setOnRefreshListener(() -> loadContent(true));
        binding.fastScroller.attach(binding.rvVideos);
        binding.btnMiniappAll.setOnClickListener(v -> showMiniappSwitchDialog());
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

    /**
     * 8.83 LoadingEvent 弹窗等价实现（XPopup asLoading 同款）：
     * 小居中盒（#212121 实底 + 16dp 圆角 + 22dp 内边距 + 28dp 菊花 + 14sp #EEEEEE 单行字）
     * + 全屏 50% 黑阴影（XPopup 默认 shadowBgColor #7F000000）。
     * 之前把深色底设在了全屏 root 上，导致整屏近黑——这就是"满屏"bug 的根因。
     * 对齐 8.83 MainActivity.showLoading 的 XPopup 默认行为：
     * 1) 未调 dismissOnTouchOutside/dismissOnBackPressed → 默认都为 true，点外部/返回键可关闭；
     * 2) asLoading(title) 里 title 为空时只显示菊花、标题 TextView gone，不补默认文案；
     * 3) 已显示时只 setTitle 更新文字（此处复用 dialog + setText 等价）。
     * 规则 preRule 里调 showLoading(text) 时弹出，hideLoading() 时关闭。
     */
    private void showHkLoading(String text) {
        try {
            if (hkLoadingDialog == null) {
                // 外层：透明全屏，只做居中
                LinearLayout root = new LinearLayout(this);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setGravity(Gravity.CENTER);
                // 内层小盒：wrap_content，深色底只画在这个小盒上
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                int pad = dp(22);
                box.setPadding(pad, pad, pad, pad);
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.RECTANGLE);
                bg.setColor(0xFF212121);
                bg.setCornerRadius(dp(16));
                box.setBackground(bg);

                ProgressBar bar = new ProgressBar(this);
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(28), dp(28));
                barLp.gravity = Gravity.CENTER_HORIZONTAL;
                box.addView(bar, barLp);

                hkLoadingText = new TextView(this);
                hkLoadingText.setTextColor(0xFFEEEEEE);
                hkLoadingText.setTextSize(14);
                hkLoadingText.setGravity(Gravity.CENTER);
                hkLoadingText.setMaxLines(1);
                hkLoadingText.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tvLp.gravity = Gravity.CENTER_HORIZONTAL;
                tvLp.topMargin = dp(8);
                box.addView(hkLoadingText, tvLp);

                root.addView(box, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                hkLoadingDialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
                hkLoadingDialog.setContentView(root);
                // 对齐 XPopup 默认：dismissOnTouchOutside/dismissOnBackPressed 均为 true
                hkLoadingDialog.setCancelable(true);
                hkLoadingDialog.setCanceledOnTouchOutside(true);
                if (hkLoadingDialog.getWindow() != null) {
                    hkLoadingDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    hkLoadingDialog.getWindow().setDimAmount(0.5f);
                }
            }
            // 对齐 XPopup LoadingPopupView.setup()：title 为空时标题 gone 只显示菊花，不补默认文案
            if (TextUtils.isEmpty(text)) {
                hkLoadingText.setVisibility(View.GONE);
            } else {
                hkLoadingText.setVisibility(View.VISIBLE);
                hkLoadingText.setText(text);
            }
            if (!hkLoadingDialog.isShowing() && !isFinishing()) hkLoadingDialog.show();
        } catch (Throwable ignored) {
        }
    }

    private void hideHkLoading() {
        try {
            if (hkLoadingDialog != null && hkLoadingDialog.isShowing()) hkLoadingDialog.dismiss();
        } catch (Throwable ignored) {
        }
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
        hkPageUrl = null; // 切规则：实际抓取页 URL 失效，下次 loadContent 重记
        // preRule loading 桥：先注册再 loadContent（引擎 init 里跑 preRule，规则调 showLoading 时弹窗）。
        // 切规则时旧弹窗先关，避免残留。
        hideHkLoading();
        HkLoading.setListener(hkLoadingListener);
        cls = "";
        area = "";
        year = "";
        sort = "";
        clsName = "";
        areaName = "";
        yearName = "";
        sortName = "";
        page = 1;
        noMore = false;
        loading = false; // 旧规则的在途加载作废，由新 loadContent 接管
        contentGen++; // 旧回调过期
        videos.clear();
        contentTitle = rule.getTitle();
        buildMiniappTabs();
        buildCategoryRow();
        buildFilterRows();
        buildContentSearchBar();
        // 内容页内切换小程序（顶部 tabs / 全部弹窗）时栈顶已是 V_CONTENT：只做替换不再重复 push，
        // 否则返回键第一次 pop 后仍停留在 V_CONTENT，视觉上毫无变化，看起来"没反应"（时好时坏的根因）
        if (stack.isEmpty() || stack.peek() != V_CONTENT) {
            pushView(V_CONTENT);
        } else {
            showView(V_CONTENT);
        }
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
            adapter.setGoldMode(true); // 金色主题：与分类 pills 颜色区分
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(outer).create();
        holder[0] = dialog;
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
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
     * 规则内容自带 input 搜索框时隐藏，避免两个搜索框重复。
     */
    private void buildContentSearchBar() {
        try {
            if (currentRule != null && currentRule.hasSearch()) {
                binding.etSearchContent.setHint("搜索" + currentRule.getTitle() + " / 演员");
                binding.etSearchContent.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        doContentSearch();
                        return true;
                    }
                    return false;
                });
                binding.btnSearchContentGo.setOnClickListener(v -> doContentSearch());
            }
        } catch (Throwable ignored) {
        }
        updateContentSearchBarVisibility();
    }

    /** 规则内容里是否自带 input 搜索框（col_type="input"）。 */
    private boolean hasRuleInputBox() {
        try {
            for (HkItem it : videos) {
                String ct = it.getColType();
                if (ct != null && "input".equals(ct.trim().toLowerCase())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 内嵌搜索条显隐：规则有搜索能力且内容里没有自带 input 框时才显示。 */
    private void updateContentSearchBarVisibility() {
        try {
            boolean show = currentRule != null && currentRule.hasSearch() && !hasRuleInputBox();
            binding.searchBarContent.setVisibility(show ? View.VISIBLE : View.GONE);
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
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        binding.etKeyword.setText(kw);
        binding.etKeyword.setHint(searchHint());
        updateSearchScopeLabel();
        binding.tvSearchEmpty.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        binding.historySection.setVisibility(View.GONE);
        pushView(V_SEARCH);
        doSearch(kw);
    }

    /**
     * 8.83 同款静态分类 tab 行。
     * 原文（ArticleListFragment.smali .line 1272-1290）：class_name/area_name/year_name/sort_name
     * 非空即注入一行 header（type="header"，desc="class"/"area"/"year"/"sort"），title/url 按 "&" 切分；
     * ArticleListAdapter.bindForHeaderHolder（smali:2492，.line 1133-1242）渲染：横向 tab + 右侧 ">"，
     * ">" 点开"请选择分类" 3 列弹窗（lambda$bindForHeaderHolder$11，smali:10064）。
     * 点击 tab：onClassClick（ArticleListFragment$5.smali:306，.line 1434-1457）按 desc 把该 tab 的
     * url 值写入对应变量、page=1 重载；选中名记 pic 用于高亮（此处记 *Name 字段）。
     * 原版无"全部"tab，不自动添加。
     */
    private void buildCategoryRow() {
        binding.categoryRow.removeAllViews();
        if (currentRule == null) {
            binding.categoryRow.setVisibility(View.GONE);
            return;
        }
        int rows = 0;
        rows += addCategoryTabRow(currentRule.getClassPairs(), "class");
        rows += addCategoryTabRow(currentRule.getAreaPairs(), "area");
        rows += addCategoryTabRow(currentRule.getYearPairs(), "year");
        rows += addCategoryTabRow(currentRule.getSortPairs(), "sort");
        // 8.83 同款：整行按是否有分类显示/隐藏
        binding.categoryRow.setVisibility(rows > 0 ? View.VISIBLE : View.GONE);
    }

    /** 建一行分类 tab（横向 RecyclerView + 右侧 ">"），无数据返回 0。 */
    private int addCategoryTabRow(List<String[]> pairs, String kind) {
        if (pairs == null || pairs.isEmpty()) return 0;
        // 8.83 默认：pic 为空时选中第一个 title；instance() 把 *_url 归一化为第一个值（smali .line 434-444）
        String curName = nameOf(kind);
        if (curName.isEmpty()) {
            setNameOf(kind, pairs.get(0)[0]);
            if (valOf(kind).isEmpty()) setValOf(kind, pairs.get(0)[1]);
            curName = pairs.get(0)[0];
        }
        int sel = 0;
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i)[0].equals(curName)) {
                sel = i;
                break;
            }
        }
        final List<String[]> finalPairs = pairs;
        final int finalSel = sel;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        rowLp.topMargin = dp(8);
        binding.categoryRow.addView(row, rowLp);

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rv.setClipToPadding(false);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        row.addView(rv, rvLp);
        rv.setAdapter(new ChipAdapter(pairs, sel, url -> onCategoryTabPick(kind, finalPairs, url)));

        TextView btnExpand = new TextView(this);
        btnExpand.setText(">");
        btnExpand.setTextSize(16);
        btnExpand.setTextColor(0xFF666666);
        btnExpand.setGravity(android.view.Gravity.CENTER);
        btnExpand.setBackgroundResource(R.drawable.tab_item_default);
        btnExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnExpand.setContentDescription("展开");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(3);
        btnLp.rightMargin = dp(5);
        row.addView(btnExpand, btnLp);
        // 8.83 同款：">" 点开"请选择分类"弹窗
        btnExpand.setOnClickListener(v ->
                showCategoryPopup(finalPairs, finalSel, url -> onCategoryTabPick(kind, finalPairs, url)));
        return 1;
    }

    /**
     * 8.83 onClassClick 等价：按行 kind 把选中 tab 的 url 值写入对应变量
     * （fyclass/fyarea/fyyear/fysort 替换用），选中名记入 *Name（8.83 setPic 等价），
     * 重建 tab 行刷新高亮（8.83 updateHeaderHolder 等价），page=1 重载。
     */
    private void onCategoryTabPick(String kind, List<String[]> pairs, String url) {
        String name = "";
        for (String[] p : pairs) {
            if (p[1].equals(url)) {
                name = p[0];
                break;
            }
        }
        if (name.isEmpty() && !pairs.isEmpty()) name = pairs.get(0)[0];
        setValOf(kind, url);
        setNameOf(kind, name);
        loadContent(true);
        buildCategoryRow();
    }

    private String valOf(String kind) {
        switch (kind) {
            case "area": return area;
            case "year": return year;
            case "sort": return sort;
            default: return cls;
        }
    }

    private String nameOf(String kind) {
        switch (kind) {
            case "area": return areaName;
            case "year": return yearName;
            case "sort": return sortName;
            default: return clsName;
        }
    }

    private void setValOf(String kind, String v) {
        switch (kind) {
            case "area": area = v; break;
            case "year": year = v; break;
            case "sort": sort = v; break;
            default: cls = v; break;
        }
    }

    private void setNameOf(String kind, String v) {
        switch (kind) {
            case "area": areaName = v; break;
            case "year": yearName = v; break;
            case "sort": sortName = v; break;
            default: clsName = v; break;
        }
    }

    /**
     * V2 动态分类 tab：规则 find_rule 返回的 scroll_button/flex_button 条目。
     * 8.83 原文行为（ArticleListAdapter.bindForHeaderHolder）：
     * 每条目独立成一行，title/url 按 "&" 切分为行内 tabs，每行右侧带 ">" 按钮，
     * 点击 ">" 弹出"请选择分类"（3 列竖向网格）。
     * 标题去 HTML 标签；含高亮色（#ff1493/#FF5D50 等）的为当前选中。
     * 点击 tab 求值其 lazyRule（putMyVar 设分类变量），再刷新列表。
     */
    private void buildDynamicTabs(List<HkItem> tabs) {
        binding.llTabRows.removeAllViews();
        if (tabs == null || tabs.isEmpty()) {
            binding.llTabRows.setVisibility(View.GONE);
            return;
        }
        binding.llTabRows.setVisibility(View.VISIBLE);
        // 8.83 updatePolyList（smali ArticleListAdapter:12152）：连续同 col_type 的条目自动合并成一组
        List<List<HkItem>> groups = new ArrayList<>();
        for (HkItem it : tabs) {
            String t = it.getColType();
            if (!groups.isEmpty()) {
                List<HkItem> last = groups.get(groups.size() - 1);
                if (last.get(0).getColType().equals(t)) {
                    last.add(it);
                    continue;
                }
            }
            List<HkItem> g = new ArrayList<>();
            g.add(it);
            groups.add(g);
        }
        for (List<HkItem> group : groups) {
            List<String[]> pairs = new ArrayList<>();
            int sel = 0;
            for (HkItem it : group) {
                String rawTitle = it.getTitle() == null ? "" : it.getTitle();
                String rawUrl = it.getUrl() == null ? "" : it.getUrl();
                // 8.83：title/url 按 "&" 切分（bindForHeaderHolder 第 1140 行）
                String[] names = rawTitle.split("&", -1);
                String[] urls = rawUrl.split("&", -1);
                for (int i = 0; i < names.length; i++) {
                    String raw = names[i];
                    if (isTabSelected(raw)) sel = pairs.size();
                    String name = stripHtml(raw);
                    if (name.isEmpty()) name = "分类" + (pairs.size() + 1);
                    String u = i < urls.length ? urls[i] : "";
                    pairs.add(new String[]{name, u, raw});
                }
            }
            if (pairs.isEmpty()) continue;
            // 行容器：横向 RecyclerView + 右侧 ">" 展开按钮（8.83 view_article_list_header.xml 同款）
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
            rowLp.topMargin = dp(8);
            binding.llTabRows.addView(row, rowLp);

            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rv.setClipToPadding(false);
            LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            row.addView(rv, rvLp);

            final List<String[]> finalPairs = pairs;
            final int finalSel = sel;
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
            rv.setAdapter(adapter);

            TextView btnExpand = new TextView(this);
            btnExpand.setText(">");
            btnExpand.setTextSize(16);
            btnExpand.setTextColor(0xFF666666);
            btnExpand.setGravity(android.view.Gravity.CENTER);
            btnExpand.setBackgroundResource(R.drawable.tab_item_default);
            btnExpand.setPadding(dp(10), dp(4), dp(10), dp(4));
            btnExpand.setContentDescription("展开");
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.leftMargin = dp(3);
            btnLp.rightMargin = dp(5);
            row.addView(btnExpand, btnLp);
            btnExpand.setOnClickListener(v -> showCategoryPopup(finalPairs, finalSel, url -> {
                if (url == null || url.isEmpty()) return;
                new Thread(() -> {
                    try {
                        getRouter().evalTab(url);
                    } catch (Throwable ignored) {
                    }
                    App.post(() -> loadContent(true));
                }).start();
            }));
        }
    }

    /** tab 是否为选中态：标题含高亮色标记（8.83 规则常用 #ff1493/#FF5D50/#ff0000 等）。 */
    private boolean isTabSelected(String rawTitle) {
        if (rawTitle == null) return false;
        String l = rawTitle.toLowerCase();
        return l.contains("#ff1493") || l.contains("#ff5d50") || l.contains("#ff0000")
                || l.contains("color=\"red\"") || l.contains("color='red'");
    }

    /**
     * 8.83 同款"请选择分类"弹窗：居中，3 列竖向网格。
     * （ArticleListAdapter.lambda$bindForHeaderHolder$11，CustomCenterRecyclerViewPopup，
     *  item_rect_radius.xml center=true 浅色模式）
     */
    private void showCategoryPopup(List<String[]> pairs, int selected, ChipAdapter.OnPick listener) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("请选择分类");
        title.setTextColor(0xFF000000);
        title.setTextSize(20);
        title.setMaxLines(1);
        title.setGravity(android.view.Gravity.CENTER);
        int tp = dp(15);
        title.setPadding(tp, tp, tp, tp);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        int rp = dp(10);
        rv.setPadding(rp, rp, rp, rp);
        rv.setClipToPadding(false);
        root.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 选项卡片风格弹窗（与现有选项弹窗统一：独立卡片+间距）
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(root)
                .create();
        // 点选项后关闭弹窗
        rv.setAdapter(new CategoryAdapter(pairs, selected, url -> {
            dialog.dismiss();
            listener.onPick(url);
        }));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.hk_category_popup_bg);
        }
        dialog.show();
    }

    /** 8.83 同款分类弹窗按钮适配器（对照 item_rect_radius.xml）。 */
    private static class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        private final List<String[]> pairs;
        private final int selected;
        private final ChipAdapter.OnPick listener;

        CategoryAdapter(List<String[]> pairs, int selected, ChipAdapter.OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        static class Holder extends RecyclerView.ViewHolder {
            TextView text;
            Holder(View v) {
                super(v);
                text = v.findViewById(R.id.tv_category);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_category, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] pair = pairs.get(position);
            h.text.setText(pair[0]);
            // 8.83：选中项文字变红（redAction #ffed424b）
            h.text.setTextColor(position == selected ? 0xFFED424B : 0xFF222222);
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getAdapterPosition();
                    if (pos >= 0 && listener != null) {
                        listener.onPick(pairs.get(pos)[1]);
                    }
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "CategoryAdapter click failed", e);
                }
            });
        }

        @Override
        public int getItemCount() {
            return pairs.size();
        }
    }

    /**
     * 8.83 原文没有筛选行：地区/年份/类型就是分类 tab 行（class/area/year/sort 四行 header，
     * 见 buildCategoryRow）。此方法保留空实现（调用点不动），避免与 tab 行重复显示。
     */
    private void buildFilterRows() {
        binding.filterContainer.removeAllViews();
        binding.filterContainer.setVisibility(View.GONE);
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

    /** 当前规则页 URL（8.83 GlideUtil.getGlideUrl 的 ruleUrl 参数：自动 Referer 用）。
     * 优先返回 loadContent/detail 时实际抓取的页面 URL（与图片同域时自动带 Referer，防盗链不 403）；
     * 拿不到时回退规则静态 URL。 */
    private String hkRuleUrl() {
        if (!TextUtils.isEmpty(hkPageUrl)) return hkPageUrl;
        return currentRule == null ? null : currentRule.getUrl();
    }

    /** 记录实际抓取的页面 URL（图片 Referer 用）。JS 规则取引擎最后一次 fetch 的真实 URL；非 JS 规则用展开后的模板 URL。 */
    private void recordHkPageUrl(int p, String c, String a, String y, String s) {
        try {
            String u = getRouter().getEngine().getJsRuntime().getLastUrl();
            if (!TextUtils.isEmpty(u) && (u.startsWith("http://") || u.startsWith("https://"))) {
                hkPageUrl = u;
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (currentRule != null) {
                hkPageUrl = com.fongmi.android.tv.api.hk.HkHttp.expandUrl(currentRule.getUrl(), c, a, y, s, p);
            }
        } catch (Throwable ignored) {
        }
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
                // 记录实际抓取页 URL：图片自动 Referer 用（8.83 GlideUtil.getGlideUrl 同款语义）
                recordHkPageUrl(p, c, a, y, s);
            } catch (Throwable e) {
                list = new ArrayList<>();
            }
            final List<HkItem> result = list;
            App.post(() -> {
                if (gen != contentGen) return; // 规则已切换/被更新的加载覆盖，丢弃过期结果
                loading = false;
                binding.refreshContent.setRefreshing(false);
                binding.loadingContent.setVisibility(View.GONE);
                // preRule 的 showLoading 兜底关闭：规则若没调 hideLoading，内容加载完也不残留弹窗。
                hideHkLoading();
                // V2：把导航类条目（scroll_button/flex_button 分类）拆出来做顶部 tab，
                // 分隔块（blank_block/line_blank/line）丢弃，其余内容条目按 col_type
                // 由 ContentAdapter 多 viewType 渲染（视频/文本/图片/输入框等）。
                List<HkItem> tabs = new ArrayList<>();
                List<HkItem> contents = new ArrayList<>();
                for (HkItem it : result) {
                    String ct = it.getColType() == null ? "" : it.getColType().trim().toLowerCase();
                    if ("scroll_button".equals(ct) || "flex_button".equals(ct)) tabs.add(it);
                    else if ("blank_block".equals(ct) || "big_blank_block".equals(ct)
                            || "big_big_blank_block".equals(ct) || "line_blank".equals(ct) || "line".equals(ct)
                            || "header".equals(ct)) continue;
                    else contents.add(it);
                }
                if (p == 1) buildDynamicTabs(tabs);
                if (contents.isEmpty()) {
                    if (p == 1 && videos.isEmpty()) {
                        // 空态：8.83 式规则错误弹窗（HkErrorUtil），对等 DebugUtil.showErrorMsg。
                        String hkErr = null;
                        try {
                            hkErr = getRouter().getEngine().getError();
                        } catch (Throwable ignored) {
                        }
                        String ruleTitle = currentRule == null ? "" : currentRule.getTitle();
                        HkErrorUtil.showErrorMsg(HkPageActivity.this, "规则执行过程中出现错误", ruleTitle, hkErr, null);
                        binding.tvContentEmpty.setText("加载失败，点我重试");
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
                    // 内容里自带 input 搜索框时，隐藏星辰内嵌搜索条，避免两个搜索框重复
                    updateContentSearchBarVisibility();
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
        binding.rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        searchAdapter = new SearchCardAdapter(searchResults);
        binding.rvSearchResults.setAdapter(searchAdapter);
        binding.searchRefresh.setOnRefreshListener(() -> {
            if (keyword.isEmpty()) {
                binding.searchRefresh.setRefreshing(false);
                return;
            }
            if (searchDualMode) doCrossSearch(keyword);
            else doSearch(keyword);
        });
        // 上拉加载更多：原 SmartRefreshLayout 已从 Maven 下架，改用滚动监听（内容页 rvVideos 同款）
        binding.rvSearchResults.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || searchLoading || searchNoMore || !searchCanPage) return;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                    if (last >= searchAdapter.getItemCount() - 3) doSearchPage();
                }
            }
        });
        binding.rvHistory.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        // 搜索页搜索条用实色白底（用户反馈透明看不清，已去磨砂）
        binding.btnBackSearch.setOnClickListener(v -> onBackInvoked());
        binding.btnClear.setOnClickListener(v -> binding.etKeyword.setText(""));
        binding.btnClearHistory.setOnClickListener(v -> {
            getPrefs().edit().remove(histKey()).apply();
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
        binding.btnSearchGo.setOnClickListener(v -> {
            String kw = binding.etKeyword.getText().toString().trim();
            if (searchDualMode) doCrossSearch(kw);
            else doSearch(kw);
        });
        binding.tvSearchMode.setOnClickListener(v -> {
            boolean precise = !isPreciseMode();
            getPrefs().edit().putInt(KEY_SMODE, precise ? 1 : 0).apply();
            updateSearchModeText();
            Notify.show(precise ? "已设置为精准模式" : "已设置为默认模式");
            if (!keyword.isEmpty()) {
                if (searchDualMode) doCrossSearch(keyword);
                else doSearch(keyword);
            }
        });
        updateSearchModeText();
    }

    private boolean isPreciseMode() {
        return getPrefs().getInt(KEY_SMODE, 0) == 1;
    }

    private void updateSearchModeText() {
        binding.tvSearchMode.setText(isPreciseMode() ? "搜索模式：精准" : "搜索模式：默认");
    }

    private void setSearchProgress(boolean on) {
        try {
            binding.searchProgress.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            android.graphics.drawable.Drawable d = binding.searchProgress.getDrawable();
            if (d instanceof android.graphics.drawable.Animatable) {
                if (on) ((android.graphics.drawable.Animatable) d).start();
                else ((android.graphics.drawable.Animatable) d).stop();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 8.83 SearchFragment.filterData：精准模式过滤（smode=1 时） */
    private void filterPrecise(List<SearchResult> list) {
        if (!isPreciseMode() || list == null || list.isEmpty()) return;
        // 单条占位结果（验证码/聚合搜索等提示条）直接保留，不进通用过滤
        if (list.size() == 1) {
            SearchResult one = list.get(0);
            String t = one.getTitle();
            String u = one.getUrl();
            if (t != null && (t.contains("验证码") || t.contains("聚合搜索") || t.contains("聚搜")
                    || t.contains("点我") || t.contains("加载中") || t.contains("加载"))) {
                return;
            }
            if (u != null && "hiker://empty".equals(u)) {
                return;
            }
        }
        java.util.Iterator<SearchResult> it = list.iterator();
        while (it.hasNext()) {
            SearchResult r = it.next();
            String url = r.getUrl();
            boolean keep = false;
            if (url != null && !url.isEmpty()
                    && (url.startsWith("hiker://search") || url.startsWith("海阔视界"))) {
                keep = true;
            }
            if (!keep && !searchContains(r.getTitle(), keyword, false)) {
                it.remove();
            }
        }
    }

    /** 8.83 StringUtil.searchContains：空标题 false；空关键词 true；多词按序包含 */
    private static boolean searchContains(String title, String key, boolean ignoreCase) {
        if (title == null || title.isEmpty()) return false;
        if (key == null || key.isEmpty()) return true;
        if (ignoreCase) {
            title = title.toLowerCase();
            key = key.toLowerCase();
        }
        String[] words = key.split(" ");
        if (words.length >= 2) {
            for (String w : words) {
                int idx = title.indexOf(w);
                if (idx < 0) return false;
                title = title.substring(idx + w.length());
            }
            return true;
        }
        return title.contains(key);
    }

    private void configSearchRefresh() {
        searchCanPage = currentRule != null && currentRule.getSearchUrl() != null
                && currentRule.getSearchUrl().contains("fypage");
        binding.searchRefresh.setEnabled(true);
    }

    private String emptySearchTitle() {
        return "没有找到你想要的噢-_-" + (isPreciseMode() ? "（当前为精准搜索模式）" : "");
    }

    private void openSearch() {
        searchDualMode = false;
        keyword = "";
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        binding.etKeyword.setText("");
        // 小程序内搜索：hint 带上规则名，明确作用域（之前通用 hint 像全局搜索）
        binding.etKeyword.setHint(searchHint());
        updateSearchScopeLabel();
        binding.tvSearchEmpty.setVisibility(View.GONE);
        binding.rvSearchResults.setVisibility(View.VISIBLE);
        refreshHistory();
        binding.historySection.setVisibility(View.VISIBLE);
        pushView(V_SEARCH);
        binding.etKeyword.requestFocus();
    }

    /** 小程序内搜索的输入框 hint：带上规则名，如"搜索一个APP"。 */
    private String searchHint() {
        if (!searchDualMode && currentRule != null && currentRule.getTitle() != null) {
            return "搜索" + currentRule.getTitle();
        }
        return "输入关键词搜索";
    }

    /** V_SEARCH 作用域标识：单规则模式显示"在「X」内搜索"，跨搜模式隐藏。 */
    private void updateSearchScopeLabel() {
        try {
            if (!searchDualMode && currentRule != null && currentRule.getTitle() != null
                    && !currentRule.getTitle().isEmpty()) {
                binding.tvSearchScope.setText("在「" + currentRule.getTitle() + "」内搜索");
                binding.tvSearchScope.setVisibility(View.VISIBLE);
            } else {
                binding.tvSearchScope.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 搜索历史按作用域隔离：小程序内搜索用各规则独立的 key，跨搜用全局 key。 */
    private String histKey() {
        if (!searchDualMode && currentRule != null && currentRule.getTitle() != null
                && !currentRule.getTitle().isEmpty()) {
            return KEY_HIST + "_" + currentRule.getTitle();
        }
        return KEY_HIST;
    }


    /**
     * 搜内容：跨小程序内容搜索。对所有启用的小程序并发调用 search(keyword, 1)，
     * 结果按小程序分组展示（来源标注在条目 desc 前缀），点击条目自动切到对应小程序再进详情。
     */
    private void doCrossSearch(String kw) {
        final int gen = crossSearchGen.incrementAndGet();
        binding.rvSearchResults.setVisibility(View.GONE);
        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
        searchResults.clear();
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        if (kw.isEmpty()) {
            binding.tvSearchEmpty.setText("输入关键词，搜索全部小程序的内容");
            return;
        }
        List<com.fongmi.android.tv.api.hk.HkRule> enabled;
        try {
            enabled = com.fongmi.android.tv.api.hk.HkRuleManager.get().getEnabledRules();
        } catch (Throwable e) {
            enabled = new ArrayList<>();
        }
        if (enabled.isEmpty()) {
            binding.tvSearchEmpty.setText("没有启用的小程序");
            return;
        }
        binding.tvSearchEmpty.setText("正在搜索 " + enabled.size() + " 个小程序…");
        saveHistory(kw);
        final List<com.fongmi.android.tv.api.hk.HkRule> rules = enabled;
        final String keywordFinal = kw;
        new Thread(() -> {
            int poolSize = Math.min(rules.size(), 8);
            ThreadPoolExecutor pool = new ThreadPoolExecutor(poolSize, poolSize, 10L,
                    TimeUnit.SECONDS, new LinkedBlockingQueue<>(256));
            CountDownLatch latch = new CountDownLatch(rules.size());
            List<SearchResult> aggregated = Collections.synchronizedList(new ArrayList<>());
            for (com.fongmi.android.tv.api.hk.HkRule rule : rules) {
                final com.fongmi.android.tv.api.hk.HkRule r = rule;
                pool.execute(() -> {
                    try {
                        // 每个规则独立 router，避免与当前页面的 router 互相干扰
                        com.fongmi.android.tv.api.hk.HkRouter router =
                                new com.fongmi.android.tv.api.hk.HkRouter(r);
                        List<SearchResult> list = router.search(keywordFinal, 1);
                        if (list != null) {
                            for (SearchResult sr : list) {
                                if (sr == null) continue;
                                // 标注来源小程序，供展示分组与点击切换（SearchResult 保持 8.83 原版纯净，来源放外部映射）
                                if (gen == crossSearchGen.get()) searchSrcRules.put(sr, r.getTitle());
                                aggregated.add(sr);
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                // 整体超时 25 秒，避免某个规则卡死拖住全部
                latch.await(25, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                if (!pool.isShutdown()) pool.shutdownNow();
            } catch (Throwable ignored) {
            }
            final List<SearchResult> result = new ArrayList<>(aggregated);
            App.post(() -> {
                if (gen != crossSearchGen.get()) return; // 已被新搜索取代
                binding.searchRefresh.setRefreshing(false);
                searchResults.clear();
                // 注意：不要在这里清 searchSrcRules——worker 线程刚把来源标注写进去，
                // 清掉会导致"【来源】"后缀和点击切换丢失。搜索开始时已清过。
                filterPrecise(result);
                searchResults.addAll(result);
                searchAdapter.notifyDataSetChanged();
                if (result.isEmpty()) {
                    binding.tvSearchEmpty.setVisibility(View.VISIBLE);
                    binding.rvSearchResults.setVisibility(View.GONE);
                    binding.tvSearchEmpty.setText("无相关结果");
                } else {
                    binding.tvSearchEmpty.setVisibility(View.GONE);
                    binding.rvSearchResults.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void doSearch(String kw) {
        if (kw.isEmpty() || currentRule == null) return;
        keyword = kw;
        searchPage = 1;
        searchNoMore = false;
        searchResults.clear();
        searchSrcRules.clear();
        searchAdapter.notifyDataSetChanged();
        binding.tvSearchEmpty.setVisibility(View.GONE);
        // 空态文案带上作用域，避免"无相关结果"被误读为全局无结果
        binding.tvSearchEmpty.setText("在「" + currentRule.getTitle() + "」中无相关结果");
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
            List<SearchResult> list;
            try {
                list = getRouter().search(kw, p);
                // 记录搜索实际抓取 URL：搜索结果图 Referer 用
                try {
                    String su = getRouter().getEngine().getJsRuntime().getLastUrl();
                    if (!TextUtils.isEmpty(su) && (su.startsWith("http://") || su.startsWith("https://"))) {
                        hkPageUrl = su;
                    } else if (currentRule != null) {
                        hkPageUrl = com.fongmi.android.tv.api.hk.HkHttp.expandSearchUrl(currentRule.getSearchUrl(), kw, p);
                    }
                } catch (Throwable ignored) {
                }
            } catch (Throwable e) {
                list = new ArrayList<>();
            }
            final List<SearchResult> result = list;
            App.post(() -> {
                searchLoading = false;
                binding.searchRefresh.setRefreshing(false);
                if (result.isEmpty()) {
                    if (p == 1 && searchResults.isEmpty()) {
                        binding.tvSearchEmpty.setVisibility(View.VISIBLE);
                        binding.rvSearchResults.setVisibility(View.GONE);
                    } else {
                        searchNoMore = true;
                    }
                } else {
                    filterPrecise(result);
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
        String s = getPrefs().getString(histKey(), "");
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
        getPrefs().edit().putString(histKey(), TextUtils.join("", hist)).apply();
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
        binding.btnFavDetail.setOnClickListener(v -> toggleDetailFavorite());
        binding.btnMenuDetail.setOnClickListener(v -> showDetailMenu());
        binding.swipeDetail.setColorSchemeColors(0xFFD4A017);
        binding.swipeDetail.setOnRefreshListener(() -> {
            if (detailItem != null) openDetail(detailItem, detailFromSearch, false, false);
            else binding.swipeDetail.setRefreshing(false);
        });
        // 8.83 原版：详情页走通用列表渲染器（60 列栅格 + ContentAdapter），不用专用布局
        detailGrid = new GridLayoutManager(this, 60);
        detailGrid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return detailAdapter == null ? 60 : detailAdapter.spanFor(position);
            }
        });
        binding.rvDetailContent.setLayoutManager(detailGrid);
        binding.rvDetailContent.addItemDecoration(new GridSpace());
        detailAdapter = new ContentAdapter(detailItems);
        detailAdapter.setDetailMode(true);
        binding.rvDetailContent.setAdapter(detailAdapter);
        binding.tvDetailEmpty.setOnClickListener(v -> {
            if (detailItem != null) openDetail(detailItem, detailFromSearch);
        });
    }

    private void openDetail(HkItem item, boolean fromSearch) {
        openDetail(item, fromSearch, false, true);
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
        openDetail(item, fromSearch, lazyPrecheck, true);
    }

    /**
     * @param push false=下拉刷新重载：不重复压栈，结束后也不弹栈
     */
    private void openDetail(HkItem item, boolean fromSearch, boolean lazyPrecheck, boolean push) {
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
        detailItems.clear();
        if (detailAdapter != null) detailAdapter.notifyDataSetChanged();
        // 足迹预载：本详情页上次点击的选集 URL（#noHistory# 规则不记，见 playDetailEpisode）
        detailHighlightUrl = getPrefs().getString(detailFootKey(), "");
        detailReversed = false;
        if (!lazyPrecheck) {
            binding.tvDetailTitle.setText(item.getTitle());
            binding.rvDetailContent.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.GONE);
            binding.tvDetailLoading.setVisibility(View.VISIBLE);
            updateDetailFavIcon();
            if (push) pushView(V_DETAIL);
        } else {
            // 直接播放预检（条目 URL 自带 @lazyRule=）：后台求值，下方小字"加载中"提示
            // （海阔原版 toast 风格，不挡封面）；求值完成后直接进播放器
            showLoadingHint();
        }
        new Thread(() -> {
            HkDetail detail;
            try {
                detail = getRouter().detail(item.getUrl(), item, fromSearch);
                // 记录详情页实际抓取 URL：详情封面/推荐图 Referer 用
                try {
                    String du = getRouter().getEngine().getJsRuntime().getLastUrl();
                    if (!TextUtils.isEmpty(du) && (du.startsWith("http://") || du.startsWith("https://"))) {
                        hkPageUrl = du;
                    }
                } catch (Throwable ignored) {
                }
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
                    if (lazyPrecheck) binding.loadingContent.setVisibility(View.GONE);
                    else if (push) onBackInvoked(); // 弹出已推的 V4 空 loading
                    else {
                        binding.tvDetailLoading.setVisibility(View.GONE);
                        binding.swipeDetail.setRefreshing(false);
                    }
                    new AlertDialog.Builder(HkPageActivity.this)
                            .setMessage(textResult)
                            .setPositiveButton("确定", (d, w) -> d.dismiss())
                            .show();
                    return;
                }
                // 分类切换（官方 refreshPage 语义：putMyVar 后重刷列表，不进 V4）
                if (result != null && result.isTabSwitch()) {
                    if (!lazyPrecheck && push) onBackInvoked(); // 弹出已推的 V4 空 loading
                    if (!lazyPrecheck && !push) {
                        binding.tvDetailLoading.setVisibility(View.GONE);
                        binding.swipeDetail.setRefreshing(false);
                    }
                    loadContent(true);
                    return;
                }
                // lazyRule 解析失败（@lazyRule= 求值无结果）：不推空 V4，直接报错
                // （避免"0条线路 · 共0集"的空详情页；用户点的是视频，期望播放而非空 V4）
                if (result != null && result.isLazyParseFailed()) {
                    android.widget.Toast.makeText(HkPageActivity.this, "解析失败，换一条试试", android.widget.Toast.LENGTH_SHORT).show();
                    if (!push) {
                        binding.tvDetailLoading.setVisibility(View.GONE);
                        binding.swipeDetail.setRefreshing(false);
                    }
                    return;
                }
                String direct = result == null ? "" : result.getDirectPlayUrl();
                // 动作 URL（如 toast://）：走 handleActionUrl，不进播放器/V4
                // （8.83 动作 URL 表同款；旧逻辑误判为直接播放地址导致进播放器）
                String actionUrl = result == null ? "" : result.getActionUrl();
                if (!TextUtils.isEmpty(actionUrl)) {
                    if (lazyPrecheck) binding.loadingContent.setVisibility(View.GONE);
                    else if (push) onBackInvoked(); // 弹出已推的 V4 空 loading
                    else {
                        binding.tvDetailLoading.setVisibility(View.GONE);
                        binding.swipeDetail.setRefreshing(false);
                    }
                    handleActionUrl(actionUrl, item);
                    return;
                }
                if (!TextUtils.isEmpty(direct)) {
                    // 直接播放：若已推 V4 先弹出，保证播放器返回时回到列表
                    if (!lazyPrecheck && push) onBackInvoked();
                    // 预热播放路由：后台提前做 QuickJS 初始化+preRule，
                    // 播放器调 playerContent 时直接命中缓存，不用再等几秒初始化
                    SiteApi.warmHkRouter(currentRule == null ? "" : currentRule.getTitle());
                    startHkDirectPlay(item, direct);
                    return;
                }
                // dealWithUrl 分流（官方：@lazyRule= 解析后不进 V4）
                String dealKind = result == null ? "" : result.getDealKind();
                if (!TextUtils.isEmpty(dealKind)) {
                    if (!lazyPrecheck && push) onBackInvoked();
                    else if (lazyPrecheck) binding.loadingContent.setVisibility(View.GONE);
                    handleDealUrl(dealKind, result.getDealUrl(), item);
                    return;
                }
                if (lazyPrecheck) {
                    // 预检非直接播放：补推 V4 再展示（复用已算出的 result，不二次求值）
                    binding.tvDetailTitle.setText(item.getTitle());
                    binding.rvDetailContent.setVisibility(View.GONE);
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
        // 未知种类：回退 V4（已在 V4 栈顶时不重复压栈）
        if (stack.isEmpty() || stack.peek() != V_DETAIL) pushView(V_DETAIL);
        showDetailResult(null);
    }

    /**
     * V4 结果展示：8.83 原版走通用列表渲染器（FilmListActivity + ArticleListAdapter），
     * 详情条目按 col_type 直接渲染，不做线路/选集分类。
     */
    private void showDetailResult(HkDetail result) {
        binding.swipeDetail.setRefreshing(false);
        if (result == null || (result.getRawItems().isEmpty() && !result.hasBasicInfo())) {
            binding.rvDetailContent.setVisibility(View.GONE);
            binding.tvDetailEmpty.setVisibility(View.VISIBLE);
            // 空态：8.83 式规则错误弹窗（HkErrorUtil），对等 DebugUtil.showErrorMsg。
            try {
                String hkErr = getRouter().getEngine().getError();
                String ruleTitle = currentRule == null ? "" : currentRule.getTitle();
                HkErrorUtil.showErrorMsg(HkPageActivity.this, "规则执行过程中出现错误", ruleTitle, hkErr, null);
                binding.tvDetailEmpty.setText("加载失败，点我重试");
            } catch (Throwable ignored) {
                binding.tvDetailEmpty.setText("加载失败，点我重试");
            }
        } else {
            bindDetailItems(result);
        }
    }

    /**
     * 8.83 原版：详情原始条目直接丢进通用 ContentAdapter 按 col_type 渲染。
     * - 头部：首条不是 header 型时，用点击条目的标题/封面合成 detail_header（标题左+封面右）
     * - 简介：无文本条目但有 content 时，补 long_text
     * - 播放按钮：原始条目原样渲染，点击在详情页内直接播放（不套娃进详情）
     */
    private void bindDetailItems(HkDetail detail) {
        currentDetail = detail;
        // V4 已展示：用户很可能马上点选集，提前预热播放路由，省掉播放时的初始化等待
        SiteApi.warmHkRouter(currentRule == null ? "" : currentRule.getTitle());
        List<HkItem> items = buildDetailHkItems(detail);
        // 切换倒序：头部保持首位，其余反转（8.83 reverseArticleList 等价）
        if (detailReversed && items.size() > 1) {
            int head = "detail_header".equals(items.get(0).getColType()) ? 1 : 0;
            List<HkItem> tail = new ArrayList<>(items.subList(head, items.size()));
            Collections.reverse(tail);
            items = new ArrayList<>(items.subList(0, head));
            items.addAll(tail);
        }
        detailItems.clear();
        detailItems.addAll(items);
        detailAdapter.refreshGroups();
        detailAdapter.setHighlightUrl(detailHighlightUrl);
        detailAdapter.notifyDataSetChanged();
        binding.swipeDetail.setRefreshing(false);
        binding.rvDetailContent.setVisibility(View.VISIBLE);
        binding.tvDetailEmpty.setVisibility(View.GONE);
        binding.tvDetailTitle.setText(detail.getTitle());
        updateDetailFavIcon();
        // 足迹：有记录时滚动到上次点击的选集分组并高亮，否则回顶部
        int footPos = findFootprintPos();
        binding.rvDetailContent.scrollToPosition(footPos >= 0 ? detailAdapter.groupFirstPosition(footPos) : 0);
    }

    /** 足迹 key：规则标题 + 详情条目 URL（8.83 ViewCollection 按标题+规则 URL 存 lastClick 等价）。 */
    private String detailFootKey() {
        String r = currentRule == null ? "" : currentRule.getTitle();
        String u = detailItem == null || detailItem.getUrl() == null ? "" : detailItem.getUrl();
        return KEY_DETAIL_FOOT + r + "_" + u.hashCode();
    }

    /** 足迹位置：detailHighlightUrl 在当前详情条目中的下标，无则 -1。 */
    private int findFootprintPos() {
        if (TextUtils.isEmpty(detailHighlightUrl) || detailItems.isEmpty()) return -1;
        for (int i = 0; i < detailItems.size(); i++) {
            HkItem it = detailItems.get(i);
            if (it != null && detailHighlightUrl.equals(it.getUrl())) return i;
        }
        return -1;
    }

    /** 详情页唯一标识：规则标题 + 详情条目 URL hash（收藏/足迹/跳过设置 key 用）。 */
    private String detailPageId() {
        String r = currentRule == null ? "" : currentRule.getTitle();
        String u = detailItem == null || detailItem.getUrl() == null ? "" : detailItem.getUrl();
        return r + "_" + u.hashCode();
    }

    /** 8.83 film_list_options.xml：详情顶栏 ⋮ 菜单。 */
    private void showDetailMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, binding.btnMenuDetail);
        android.view.Menu m = menu.getMenu();
        android.view.SubMenu share = m.addSubMenu("分享");
        share.add(0, 101, 0, "分享链接");
        share.add(0, 102, 0, "完整口令");
        share.add(0, 103, 0, "云口令1");
        share.add(0, 104, 0, "云口令2");
        share.add(0, 105, 0, "云口令5");
        share.add(0, 106, 0, "云口令6");
        m.add(0, 201, 0, "跳过片头片尾设置");
        m.add(0, 202, 0, "播放器设置");
        m.add(0, 203, 0, "查看页面规则");
        m.add(0, 204, 0, "查看页面地址");
        m.add(0, 205, 0, "切换倒序");
        m.add(0, 206, 0, "跳转翻页");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 101) shareDetailLink();
            else if (id == 102) shareDetailCommand();
            else if (id == 103) shareDetailCloudCode(1);
            else if (id == 104) shareDetailCloudCode(2);
            else if (id == 105) shareDetailCloudCode(5);
            else if (id == 106) shareDetailCloudCode(6);
            else if (id == 201) showDetailSkipDialog();
            else if (id == 202) showDetailPlayerDialog();
            else if (id == 203) showDetailRuleDialog();
            else if (id == 204) showDetailUrlDialog();
            else if (id == 205) showDetailReverseDialog();
            else if (id == 206) showDetailGoPageDialog();
            return true;
        });
        menu.show();
    }

    /** 8.83 collect：加入收藏（本地 prefs 存标题/封面/链接/规则），图标状态同步。 */
    private void toggleDetailFavorite() {
        if (currentDetail == null || detailItem == null) return;
        try {
            JSONArray arr = new JSONArray(getPrefs().getString(KEY_DETAIL_FAVS, "[]"));
            String url = detailItem.getUrl() == null ? "" : detailItem.getUrl();
            int found = -1;
            for (int i = 0; i < arr.length(); i++) {
                if (url.equals(arr.getJSONObject(i).optString("url"))) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                JSONArray next = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    if (i != found) next.put(arr.get(i));
                }
                getPrefs().edit().putString(KEY_DETAIL_FAVS, next.toString()).apply();
                Notify.show("已取消收藏");
            } else {
                JSONObject o = new JSONObject();
                o.put("title", currentDetail.getTitle());
                o.put("pic", currentDetail.getPic() == null ? "" : currentDetail.getPic());
                o.put("url", url);
                o.put("rule", currentRule == null ? "" : currentRule.getTitle());
                arr.put(o);
                getPrefs().edit().putString(KEY_DETAIL_FAVS, arr.toString()).apply();
                Notify.show("已加入收藏");
            }
        } catch (Throwable e) {
            Notify.show("收藏失败");
        }
        updateDetailFavIcon();
    }

    private boolean isDetailFavorite() {
        if (detailItem == null || detailItem.getUrl() == null) return false;
        try {
            JSONArray arr = new JSONArray(getPrefs().getString(KEY_DETAIL_FAVS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (detailItem.getUrl().equals(arr.getJSONObject(i).optString("url"))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void updateDetailFavIcon() {
        if (binding == null || binding.btnFavDetail == null) return;
        binding.btnFavDetail.setImageResource(isDetailFavorite()
                ? R.drawable.ic_hk_favorite_on : R.drawable.ic_hk_favorite);
    }

    /** 8.83 share_url：分享页面链接（实际抓取页 URL）。 */
    private void shareDetailLink() {
        String url = hkPageUrl == null ? "" : hkPageUrl;
        if (url.isEmpty()) {
            Notify.show("链接为空，无法分享");
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(it, "分享链接"));
        } catch (Throwable e) {
            Notify.show("分享失败");
        }
    }

    private String detailRuleJson() {
        if (currentRule == null) return "";
        try {
            return new Gson().toJson(currentRule);
        } catch (Throwable e) {
            return "";
        }
    }

    /** 8.83 share_rule：完整口令（海阔明文口令，可被官方识别导入）。 */
    private void shareDetailCommand() {
        String json = detailRuleJson();
        if (json.isEmpty()) {
            Notify.show("规则为空，无法分享");
            return;
        }
        String cmd = HkRuleManager.buildShareCommand(json);
        final String text = cmd == null ? "" : cmd;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFF1A1D24);
        tv.setTextSize(12);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("完整口令")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> {
                    copyToClipboard(text);
                    d.dismiss();
                })
                .setNegativeButton("关闭", (d, w) -> d.dismiss())
                .show();
    }

    /** 8.83 share_remote_rule*：云口令 1/2/5/6（HkCloudClip 通道）。 */
    private void shareDetailCloudCode(int clipNum) {
        String json = detailRuleJson();
        if (json.isEmpty()) {
            Notify.show("规则为空，无法分享");
            return;
        }
        String title = currentRule == null ? "" : currentRule.getTitle();
        Notify.show("正在上传云端…");
        new Thread(() -> {
            try {
                String code = HkShareHelper.shareToCloudClip(json, clipNum);
                App.post(() -> showExportCodeDialog(title, code));
            } catch (Exception e) {
                App.post(() -> Notify.show("导出失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 8.83 collection_jump：跳过片头片尾设置（按详情页存秒数）。 */
    private void showDetailSkipDialog() {
        String key = KEY_DETAIL_SKIP + detailPageId();
        String[] saved = getPrefs().getString(key, "0,0").split(",");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad / 2, pad, pad / 2);
        EditText etHead = new EditText(this);
        etHead.setHint("跳过片头（秒）");
        etHead.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHead.setTextColor(0xFF1A1D24);
        EditText etTail = new EditText(this);
        etTail.setHint("跳过片尾（秒）");
        etTail.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etTail.setTextColor(0xFF1A1D24);
        try {
            etHead.setText(saved[0].trim());
            if (saved.length > 1) etTail.setText(saved[1].trim());
        } catch (Throwable ignored) {
        }
        box.addView(etHead);
        box.addView(etTail);
        new AlertDialog.Builder(this)
                .setTitle("跳过片头片尾设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String h = etHead.getText().toString().trim();
                    String t = etTail.getText().toString().trim();
                    getPrefs().edit().putString(key,
                            (h.isEmpty() ? "0" : h) + "," + (t.isEmpty() ? "0" : t)).apply();
                    Notify.show("已保存");
                    d.dismiss();
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    /** 8.83 collection_player：播放器设置（须先收藏）。 */
    private void showDetailPlayerDialog() {
        if (!isDetailFavorite()) {
            Notify.show("请先加入收藏");
            return;
        }
        String key = KEY_DETAIL_PLAYER + detailPageId();
        int cur = getPrefs().getInt(key, 0);
        String[] opts = {"跟随系统", "ExoPlayer", "MPV"};
        new AlertDialog.Builder(this)
                .setTitle("播放器设置")
                .setSingleChoiceItems(opts, cur, (d, w) -> {
                    getPrefs().edit().putInt(key, w).apply();
                    d.dismiss();
                    Notify.show("已设为" + opts[w]);
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    /** 8.83 showRule：查看页面规则（格式化 JSON，可复制）。 */
    private void showDetailRuleDialog() {
        String json = detailRuleJson();
        if (json.isEmpty()) {
            Notify.show("规则为空");
            return;
        }
        try {
            json = new JSONObject(json).toString(2);
        } catch (Throwable ignored) {
        }
        final String text = json;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFF1A1D24);
        tv.setTextSize(12);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("页面规则")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> {
                    copyToClipboard(text);
                    d.dismiss();
                })
                .setNegativeButton("关闭", (d, w) -> d.dismiss())
                .show();
    }

    /** 8.83 showUrl：查看页面地址（可复制）。 */
    private void showDetailUrlDialog() {
        String url = hkPageUrl == null ? "" : hkPageUrl;
        final String text = url;
        TextView tv = new TextView(this);
        tv.setText(url.isEmpty() ? "（空）" : url);
        tv.setTextIsSelectable(true);
        tv.setTextColor(0xFF1A1D24);
        tv.setTextSize(13);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        new AlertDialog.Builder(this)
                .setTitle("页面地址")
                .setView(tv)
                .setPositiveButton("复制", (d, w) -> {
                    if (!text.isEmpty()) copyToClipboard(text);
                    d.dismiss();
                })
                .setNegativeButton("关闭", (d, w) -> d.dismiss())
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("hk_detail", text));
                Notify.show("已复制");
            }
        } catch (Throwable e) {
            Notify.show("复制失败");
        }
    }

    /** 8.83 reverse：切换倒序（有翻页机制的页面不支持）。 */
    private void showDetailReverseDialog() {
        String ruleUrl = currentRule == null || currentRule.getUrl() == null ? "" : currentRule.getUrl();
        if (ruleUrl.contains("fypage")) {
            Notify.show("当前页面有翻页机制，不支持倒序");
            return;
        }
        String[] opts = {"顺序显示", "倒序显示"};
        new AlertDialog.Builder(this)
                .setTitle("切换倒序")
                .setSingleChoiceItems(opts, detailReversed ? 1 : 0, (d, w) -> {
                    d.dismiss();
                    boolean rev = w == 1;
                    if (rev != detailReversed && currentDetail != null) {
                        detailReversed = rev;
                        bindDetailItems(currentDetail);
                    }
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .show();
    }

    /** 8.83 gopage：跳转翻页（无翻页机制的页面提示）。 */
    private void showDetailGoPageDialog() {
        String ruleUrl = currentRule == null || currentRule.getUrl() == null ? "" : currentRule.getUrl();
        if (!ruleUrl.contains("fypage")) {
            Notify.show("当前页面没有翻页机制哦");
            return;
        }
        showSimpleInputDialog("跳转翻页", "1", pageStr -> {
            try {
                int p = Integer.parseInt(pageStr.trim());
                if (p < 1) p = 1;
                int target = Math.min((p - 1) * 20, Math.max(0, detailItems.size() - 1));
                binding.rvDetailContent.scrollToPosition(detailAdapter.groupFirstPosition(target));
            } catch (Throwable ignored) {
                Notify.show("页码无效");
            }
        });
    }

    /** 详情原始条目 → 通用列表条目（8.83 原版：不做线路/选集分类，原样渲染）。 */
    private List<HkItem> buildDetailHkItems(HkDetail detail) {
        List<HkItem> out = new ArrayList<>();
        List<HkDetailItem> raw = detail.getRawItems();
        // 1. 头部：首条不是 header 型时合成
        boolean hasHeader = !raw.isEmpty() && isHeaderColType(raw.get(0).getColType());
        if (!hasHeader && (!TextUtils.isEmpty(detail.getTitle()) || !TextUtils.isEmpty(detail.getPic()))) {
            HkItem header = new HkItem();
            header.setColType("detail_header");
            header.setTitle(detail.getTitle());
            header.setPic(detail.getPic());
            out.add(header);
        }
        // 2. 原始条目原样转换
        boolean hasText = false;
        for (HkDetailItem di : raw) {
            HkItem hi = new HkItem();
            hi.setTitle(di.getTitle());
            hi.setUrl(di.getUrl());
            hi.setPic(di.getPic());
            hi.setDesc(di.getDesc());
            hi.setColType(di.getColType());
            hi.setLine(di.getLine());
            out.add(hi);
            String ct = di.getColType() == null ? "" : di.getColType().toLowerCase();
            if (ct.contains("long_text") || ct.startsWith("text_") || ct.contains("rich_text")) hasText = true;
        }
        // 3. 简介回退：无文本条目但有 content 时补 long_text
        if (!hasText && !TextUtils.isEmpty(detail.getContent())) {
            HkItem syn = new HkItem();
            syn.setColType("long_text");
            syn.setDesc(detail.getContent());
            out.add(out.isEmpty() ? 0 : 1, syn);
        }
        return out;
    }

    /** 详情头型 col_type：已有头部条目时不再合成。 */
    private boolean isHeaderColType(String colType) {
        if (colType == null) return false;
        String c = colType.trim().toLowerCase();
        return c.equals("detail_header") || c.contains("movie_1_vertical_pic")
                || c.equals("movie_1") || c.equals("movie_1_left_pic");
    }

    /**
     * 8.83 原版 dealWithUrl：详情页内点可播 URL 直接播放，不套娃进详情。
     * 先过 HkRouter.play() 解析（处理 @lazyRule=/@rule=js: 等），再进播放器；
     * 选集列表取当前详情所有可播条目，支持播放器内切集。
     */
    // 8.83 同款"动态解析规则中" loading 视图（转圈染白，对齐 8.83 LoadingPopupView 白色风格）
    private android.view.View getParsingView() {
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setGravity(android.view.Gravity.CENTER);
        int pad = dp(28);
        layout.setPadding(pad, dp(24), pad, dp(24));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        try {
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        } catch (Throwable ignored) {
        }
        android.widget.LinearLayout.LayoutParams bp =
                new android.widget.LinearLayout.LayoutParams(dp(36), dp(36));
        bp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        layout.addView(bar, bp);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("动态解析规则中，请稍候");
        tv.setTextSize(15);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(12);
        tp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        layout.addView(tv, tp);
        return layout;
    }

    private void playDetailEpisode(HkItem it, String url) {
        boolean needsParse = url.contains("@lazyRule=") || url.contains("@rule=") || url.startsWith("js:");
        boolean noLoading = url.contains("#noLoading#");
        // 切集：本详情页之前已播过别的选集（8.83 VideoPlayerActivity.playByPos 切集 toast 等价）
        boolean isSwitch = !TextUtils.isEmpty(detailLastPlayedUrl) && !detailLastPlayedUrl.equals(url);
        detailLastPlayedUrl = url;
        // 足迹：记本次点击的选集位置（8.83 memoryPosition 等价；#noHistory# 规则不记）
        String ruleUrl = currentRule == null || currentRule.getUrl() == null ? "" : currentRule.getUrl();
        if (!ruleUrl.contains("#noHistory#")) {
            detailHighlightUrl = url;
            try {
                getPrefs().edit().putString(detailFootKey(), url).apply();
            } catch (Throwable ignored) {
            }
            if (detailAdapter != null) {
                detailAdapter.setHighlightUrl(url);
                detailAdapter.notifyDataSetChanged();
            }
        }
        // 8.83 同款：详情页弹"动态解析规则中" loading，解析完再进播放器，不直接跳页。
        // 直链不弹；规则 URL 含 #noLoading# 不弹；切集时改弹 toast（播放器自带 loading）。
        android.app.AlertDialog loading = null;
        if (needsParse && !noLoading && !isSwitch) {
            loading = new android.app.AlertDialog.Builder(HkPageActivity.this)
                    .setView(getParsingView())
                    .setCancelable(false)
                    .create();
            try {
                android.view.Window w = loading.getWindow();
                if (w != null) {
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0xE6333333, 0xE6333333});
                    bg.setCornerRadius(dp(16));
                    w.setBackgroundDrawable(bg);
                }
            } catch (Throwable ignored) {
            }
            loading.show();
        } else if (needsParse && !noLoading && isSwitch) {
            Notify.show("动态解析规则中，请稍候");
        }
        final android.app.AlertDialog loadingDialog = loading;
        new Thread(() -> {
            String playUrl = url;
            try {
                if (needsParse) {
                    HkPlay play = getRouter().play(url);
                    if (!play.getUrl().isEmpty()) playUrl = play.getUrl();
                }
            } catch (Throwable ignored) {
            }
            // 选集：当前详情所有可播条目（保持顺序），当前点中的为选中项
            org.json.JSONArray arr = new org.json.JSONArray();
            int selIdx = 0;
            int idx = 0;
            for (HkItem di : detailItems) {
                String du = di.getUrl() == null ? "" : di.getUrl().trim();
                if (du.isEmpty()) continue;
                String dl = du.toLowerCase();
                if (dl.startsWith("javascript:") || dl.equals("hiker://empty")) continue;
                // 只收可播条目：http 链接或含 @lazyRule=/@rule=/js: 的解析型地址
                boolean playable = dl.startsWith("http")
                        || du.contains("@lazyRule=") || du.contains("@rule=") || du.startsWith("js:");
                if (!playable) continue;
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("name", stripHtml(di.getTitle()));
                    o.put("url", du);
                    o.put("pic", di.getPic() == null ? "" : di.getPic());
                    arr.put(o);
                    if (di == it) selIdx = idx;
                    idx++;
                } catch (Throwable ignored) {
                }
            }
            final String pu = playUrl;
            final String eps = arr.toString();
            final int sel = selIdx;
            App.post(() -> {
                try {
                    if (loadingDialog != null) loadingDialog.dismiss();
                } catch (Throwable ignored) {
                }
                if (TextUtils.isEmpty(pu)) {
                    android.widget.Toast.makeText(HkPageActivity.this, "解析失败，换一条试试",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                String title = detailItem != null && !TextUtils.isEmpty(detailItem.getTitle())
                        ? detailItem.getTitle() : "";
                String pic = detailItem != null ? detailItem.getPic() : "";
                String lineName = it.getLine() == null || it.getLine().isEmpty() ? "默认" : it.getLine();
                VideoActivity.startHkPlay(HkPageActivity.this,
                        currentRule == null ? "" : currentRule.getTitle(), lineName,
                        pu, it.getTitle(), title, pic, eps, sel);
            });
        }).start();
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

    /** 海阔搜索结果竖向全宽卡片：对照 8.83 item_search_all。 */
    private class SearchCardAdapter extends RecyclerView.Adapter<SearchCardAdapter.Holder> {
        private final List<SearchResult> items;

        SearchCardAdapter(List<SearchResult> items) {
            this.items = items;
        }

        class Holder extends RecyclerView.ViewHolder {
            View imgBg;
            ImageView img;
            TextView title, content, desc, notice, descMore;

            Holder(View v) {
                super(v);
                imgBg = v.findViewById(R.id.img_bg);
                img = v.findViewById(R.id.img);
                title = v.findViewById(R.id.title);
                content = v.findViewById(R.id.content);
                desc = v.findViewById(R.id.desc);
                notice = v.findViewById(R.id.notice);
                descMore = v.findViewById(R.id.desc_more);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_search, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            SearchResult sr = items.get(position);
            h.title.setText(sr.getTitle() == null ? "" : sr.getTitle());
            String content = sr.getContent() == null ? "" : sr.getContent();
            h.content.setText(content);
            h.content.setVisibility(TextUtils.isEmpty(content) ? View.GONE : View.VISIBLE);
            String srcRule = searchSrcRules.get(sr);
            String desc = sr.getDesc() == null ? "" : sr.getDesc();
            if (!TextUtils.isEmpty(srcRule)) {
                desc = ("".equals(desc) ? "" : desc + "  ") + "【" + srcRule + "】";
            }
            h.desc.setText(desc);
            h.desc.setVisibility(TextUtils.isEmpty(desc) ? View.GONE : View.VISIBLE);
            String descMore = sr.getDescMore() == null ? "" : sr.getDescMore();
            h.descMore.setText(descMore);
            h.descMore.setVisibility(TextUtils.isEmpty(descMore) ? View.GONE : View.VISIBLE);
            h.notice.setVisibility(View.GONE);
            String imgUrl = sr.getImg();
            if (!TextUtils.isEmpty(imgUrl)) {
                h.imgBg.setVisibility(View.VISIBLE);
                ImgUtil.load(sr.getTitle(), imgUrl, h.img, hkRuleUrl());
            } else {
                h.imgBg.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                SearchResult s = items.get(pos);
                if (TextUtils.isEmpty(s.getUrl())) return;
                HkItem it = HkItem.fromSearchResult(s);
                String itSrc = searchSrcRules.get(s);
                if (!TextUtils.isEmpty(itSrc)
                        && (currentRule == null || !itSrc.equals(currentRule.getTitle()))) {
                    com.fongmi.android.tv.api.hk.HkRule target =
                            com.fongmi.android.tv.api.hk.HkRuleManager.get().getRule(itSrc);
                    if (target != null) {
                        switchRuleForDetail(target);
                    } else {
                        return;
                    }
                }
                if (currentRule == null) return;
                String mu = it.getUrl() == null ? "" : it.getUrl();
                if (handleActionUrl(mu.trim(), it)) return;
                if (mu.trim().startsWith("hiker://page/") && openPage(it, mu)) return;
                openDetail(it, true, mu.contains("@lazyRule="));
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

    /** col_type → viewType：空/movie_3/未知 → 视频卡片（官方 getItemTypeByCode 未知回退 MOVIE_3）。
     * 8.83 原版：每个 col_type 独立 viewType，不合并（对照 ArticleColTypeEnum）。 */
    private int contentTypeOf(HkItem it) {
        String ct = colTypeOf(it);
        switch (ct) {
            case "text_1":
                return ContentAdapter.T_TEXT_1;
            case "text_center_1":
                return ContentAdapter.T_TEXT_CENTER_1;
            case "long_text":
                return ContentAdapter.T_LONG_TEXT;
            case "detail_header":
                // 8.83 原版详情页头部（item_movie_one_col）：标题左 + 封面右
                return ContentAdapter.T_DETAIL_HEADER;
            case "header":
                return ContentAdapter.T_HEADER;
            case "footer":
                return ContentAdapter.T_FOOTER;
            case "text_icon":
                return ContentAdapter.T_TEXT_ICON;
            case "rich_text":
                return ContentAdapter.T_RICH;
            case "text_2":
                return ContentAdapter.T_TEXT_2;
            case "text_3":
                return ContentAdapter.T_TEXT_3;
            case "text_4":
                return ContentAdapter.T_TEXT_4;
            case "text_5":
                return ContentAdapter.T_TEXT_5;
            case "avatar":
                return ContentAdapter.T_AVATAR;
            case "input":
                return ContentAdapter.T_INPUT;
            case "x5_webview_single":
                return ContentAdapter.T_WEB;
            case "scroll_button":
            case "flex_button":
                // 8.83 原版：连续同类型条目由 updatePolyList 自动合并，首个渲染整组
                return ContentAdapter.T_BUTTONS;
            case "card_pic_1":
                return ContentAdapter.T_CARD_PIC_1;
            case "card_pic_2":
                return ContentAdapter.T_CARD_PIC_2;
            case "card_pic_2_2":
                return ContentAdapter.T_CARD_PIC_2_2;
            case "card_pic_2_2_left":
                return ContentAdapter.T_CARD_PIC_2_2_LEFT;
            case "card_pic_3":
                return ContentAdapter.T_CARD_PIC_3;
            case "card_pic_3_center":
                return ContentAdapter.T_CARD_PIC_3_CENTER;
            case "":
            case "movie_3":
                return ContentAdapter.T_VIDEO;
            case "movie_1":
                // 官方 movie_1 span=60 整宽左图右文
                return ContentAdapter.T_MOVIE_LEFT_PIC;
            case "movie_1_left_pic":
                // 官方 movie_1_left_pic span=60 整宽左图右文（同 movie_1）
                return ContentAdapter.T_MOVIE_LEFT_PIC;
            case "movie_1_vertical_pic":
                // 官方 movie_1_vertical_pic span=60 全宽左竖海报右文（D3）
                return ContentAdapter.T_MOVIE_VERTICAL;
            case "movie_1_vertical_pic_blur":
                // 官方 movie_1_vertical_pic_blur span=60 毛玻璃背景 + 竖海报（D3）
                return ContentAdapter.T_MOVIE_VERTICAL_BLUR;
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
                // 8.83 原版：pic_* 各子类型独立 viewType
                if ("pic_1".equals(ct)) return ContentAdapter.T_PIC_1;
                if ("pic_1_card".equals(ct)) return ContentAdapter.T_PIC_1_CARD;
                if ("pic_1_center".equals(ct)) return ContentAdapter.T_PIC_1_CENTER;
                if ("pic_1_full".equals(ct)) return ContentAdapter.T_PIC_1_FULL;
                if ("pic_2".equals(ct)) return ContentAdapter.T_PIC_2;
                if ("pic_2_card".equals(ct)) return ContentAdapter.T_PIC_2_CARD;
                if ("pic_3".equals(ct)) return ContentAdapter.T_PIC_3;
                if ("pic_3_square".equals(ct)) return ContentAdapter.T_PIC_3_SQUARE;
                if (ct.startsWith("pic")) return ContentAdapter.T_PIC_1; // 未知 pic 回退
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
        if (it == null || currentRule == null) return;
        String url = it.getUrl() == null ? "" : it.getUrl().trim();
        // 8.83 ArticleListFragment.clickItem(src 2917)：空链接 toast"链接为空，规则有误！"，不静默吞掉
        if (url.isEmpty()) {
            android.widget.Toast.makeText(this, "链接为空，规则有误！", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        // 8.83 clickItem(src 2940)：base64:// 去前缀解码后再走分流
        if (url.startsWith("base64://")) {
            try {
                String decoded = new String(android.util.Base64.decode(
                        url.substring("base64://".length()).trim(), 2)).trim();
                url = decoded;
                it.setUrl(decoded);
            } catch (Throwable ignored) {
            }
        }
        if (handleActionUrl(url, it)) return;
        // P1：hiker://page/ 子页面走 openPage（官方 toNextPage），不进 V4
        if (url.startsWith("hiker://page/") && openPage(it, url)) return;
        // 官方 clickItem 顺序：@rule= 前段里的 @lazyRule= 优先（dealLazyRule），再是 @rule=（dealRule 子列表）
        int ar = url.indexOf("@rule=");
        String preRule = ar >= 0 ? url.substring(0, ar) : url;
        boolean hasLazy = preRule.contains("@lazyRule=");
        // 官方 clickItem：@rule= 条目进子列表页（dealRule 新建 ArticleListRule 打开 FilmListActivity），不进 V4
        if (ar >= 0 && !hasLazy && openSubList(it, url)) return;
        // 8.83 原版 dealWithUrl：详情页内可播 URL 直接播放，不套娃进详情
        if (!stack.isEmpty() && stack.peek() == V_DETAIL) {
            String kind = HkRouter.dealKind(url);
            if ("video".equals(kind)) {
                playDetailEpisode(it, url);
                return;
            }
            if (!kind.isEmpty()) {
                handleDealUrl(kind, url, it);
                return;
            }
        }
        openDetail(it, true, hasLazy);
    }

    /** 子列表状态（@rule= 进子列表时压栈，返回时恢复）。 */
    private static class SubListState {
        HkRule rule;
        String title;
        int page;
        String cls, area, year, sort;
        String clsName, areaName, yearName, sortName;
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
            // goTo 预检：@rule=js: 可能是导航动作（如 xhotview 点封面调 goTo('hiker://page/detail...') 跳 V4），
            // 不是子列表。先在后台求值，若规则调了 goTo，按目标 URL 导航；否则走子列表流程。
            // 8.83 语义：js: 分支仅当实际调了 goTo() 才走导航，否则直接建子列表规则。
            String frTrim = findRule.trim();
            if (frTrim.startsWith("js:")) {
                final String jsCode = frTrim;
                final String pUrl = pageUrl;
                final HkItem item = it;
                final String fRule = findRule;
                final String cType = colType;
                // 点击即反馈：预检（HkHttp.get + evalLazy）全程在后台线程，先弹 loading，
                // 对齐 8.83 点击即有反馈（之前静默，用户感知为"点了没反应"）。
                showHkLoading("加载中…");
                new Thread(() -> {
                    String target = "";
                    boolean needRefresh = false;
                    try {
                        HkJsRuntime rt = getRouter().getEngine().getJsRuntime();
                        // @rule= 相关流程：JS 求值前先 HkHttp.get(baseUrl) 取正文，供 getResCode() 返回
                        // （8.83 getResCode 返回 String 正文，不是 int 状态码）
                        // 预检加 10s 超时，避免长时间无响应
                        try {
                            if (pUrl != null && (pUrl.startsWith("http://") || pUrl.startsWith("https://"))) {
                                String body = HkHttp.get(pUrl, null, 10000);
                                rt.setEvalResCodeBody(body);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            rt.evalLazy(jsCode, pUrl);
                            target = rt.consumeGoToRequest();
                            // 动作回调（如同意按钮 putMyVar+refreshPage）：无 goTo 但请求了刷新，
                            // 必须取走 refreshPage 标记直接重刷当前列表，不进子列表流程
                            //（否则 flag 被丢弃、router 被销毁、myVars 丢失，表现为"点了没反应"）。
                            needRefresh = rt.consumeRefreshRequest();
                        } finally {
                            rt.setEvalResCodeBody(null);
                        }
                    } catch (Throwable ignored) {
                    }
                    final String t = target == null ? "" : target.trim();
                    final boolean refresh = needRefresh;
                    App.post(() -> {
                        hideHkLoading();
                        if (!t.isEmpty()) {
                            handleGoToUrl(t, item);
                        } else if (refresh) {
                            // 有 refresh 无 goTo：动作回调，直接刷新当前列表
                            //（对齐 8.83 refreshPage EventBus 机制，跟走哪条点击链路无关）
                            loadContent(true);
                        } else {
                            // 无 goTo：走子列表流程（deriveSubRule + loadContent），不对 openDetail 退化
                            doOpenSubListFlow(item, pUrl, fRule, cType);
                        }
                    });
                }).start();
                return true;
            }
            return doOpenSubListFlow(it, pageUrl, findRule, colType);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * @rule= 子列表流程（官方 dealRule）：压栈保存父列表状态，派生子规则并加载。
     * js: 分支无 goTo 时也走这里，不退化为 openDetail。
     */
    private boolean doOpenSubListFlow(HkItem it, String pageUrl, String findRule, String colType) {
        try {
            // 压栈保存父列表状态
            SubListState st = new SubListState();
            st.rule = currentRule;
            st.title = contentTitle;
            st.page = page;
            st.cls = cls;
            st.area = area;
            st.year = year;
            st.sort = sort;
            st.clsName = clsName;
            st.areaName = areaName;
            st.yearName = yearName;
            st.sortName = sortName;
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
            clsName = "";
            areaName = "";
            yearName = "";
            sortName = "";
            page = 1;
            noMore = false;
            loading = false;
            contentGen++;
            videos.clear();
            String itemTitle = stripHtml(it.getTitle());
            contentTitle = itemTitle.isEmpty() ? st.title : itemTitle;
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
    /**
     * 处理 goTo(url) 导航请求（规则在 @rule=js: 回调里调 goTo 跳转）。
     * hiker://page/detail?rule=X&u=BASE64 → 解析出详情 URL，打开 V4 详情页；
     * 其他 URL 按普通条目点击处理。
     */
    private void handleGoToUrl(String url, HkItem srcItem) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty()) return;
        try {
            if (u.startsWith("hiker://page/detail")) {
                // 解析 u= 参数（base64 编码的详情页 URL）
                String detailUrl = "";
                int qi = u.indexOf('?');
                if (qi >= 0) {
                    String query = u.substring(qi + 1);
                    for (String kv : query.split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0 && "u".equals(kv.substring(0, eq).trim())) {
                            String b64 = kv.substring(eq + 1).trim();
                            try {
                                // URL 安全 base64 → 标准 base64
                                b64 = b64.replace('-', '+').replace('_', '/');
                                while (b64.length() % 4 != 0) b64 += "=";
                                detailUrl = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8").trim();
                            } catch (Throwable ignored) {
                            }
                            break;
                        }
                    }
                }
                if (!detailUrl.isEmpty()) {
                    // 用详情 URL 构造条目，走 V4 详情页
                    HkItem di = new HkItem();
                    di.setTitle(srcItem != null ? srcItem.getTitle() : "");
                    di.setPic(srcItem != null ? srcItem.getPic() : "");
                    di.setUrl(detailUrl);
                    openDetail(di, false);
                } else if (srcItem != null) {
                    // 解析失败时退化为直接打开源条目详情
                    openDetail(srcItem, true);
                }
                return;
            }
            // hiker://page/ 其他子页面
            if (u.startsWith("hiker://page/") && srcItem != null && openPage(srcItem, u)) return;
            // 其他：按 URL 分流
            if (srcItem != null) {
                HkItem di = new HkItem();
                di.setTitle(srcItem.getTitle());
                di.setPic(srcItem.getPic());
                di.setUrl(u);
                if (!handleActionUrl(u, di)) openDetail(di, false);
            }
        } catch (Throwable e) {
            android.util.Log.d("HkPage", "handleGoToUrl failed: " + e.getMessage());
        }
    }

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
        clsName = st.clsName == null ? "" : st.clsName;
        areaName = st.areaName == null ? "" : st.areaName;
        yearName = st.yearName == null ? "" : st.yearName;
        sortName = st.sortName == null ? "" : st.sortName;
        noMore = false;
        loading = false;
        contentGen++;
        videos.clear();
        videos.addAll(st.videos);
        contentTitle = st.title;
        buildCategoryRow();
        buildFilterRows();
        contentAdapter.refreshGroups();
        contentAdapter.notifyDataSetChanged();
        updateContentSearchBarVisibility();
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
            // 与 V4 流程对齐（3899 行）：子页面也要设置 detailItem，
            // 否则 playDetailEpisode 拿到的 title 为空，播放页标题丢失
            detailItem = it;
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
            // R4：8.83 PageParser.parsePageRule：?rule= 跨规则跳转，按标题查库定位另一条规则
            com.fongmi.android.tv.api.hk.HkRule pageOwnerRule = currentRule;
            try {
                int qi2 = u.indexOf('?');
                if (qi2 >= 0) {
                    String query = u.substring(qi2 + 1);
                    int hi2 = query.indexOf('#');
                    if (hi2 >= 0) query = query.substring(0, hi2);
                    for (String kv : query.split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0 && "rule".equals(kv.substring(0, eq).trim())) {
                            String rt = kv.substring(eq + 1).trim();
                            try {
                                rt = java.net.URLDecoder.decode(rt, "UTF-8");
                            } catch (Throwable ignored) {
                            }
                            if (!rt.isEmpty()) {
                                com.fongmi.android.tv.api.hk.HkRule found =
                                        com.fongmi.android.tv.api.hk.HkRuleManager.get().getInstalledRule(rt);
                                if (found != null) pageOwnerRule = found;
                            }
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // 在规则 pages 里找对应页面
            String pagesJson = pageOwnerRule.getPages();
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
            if (TextUtils.isEmpty(colType)) colType = pageOwnerRule.getColType();
            // 压栈保存父列表状态（复用子列表机制，返回时恢复）
            SubListState st = new SubListState();
            st.rule = currentRule;
            st.title = contentTitle;
            st.page = page;
            st.cls = cls;
            st.area = area;
            st.year = year;
            st.sort = sort;
            st.clsName = clsName;
            st.areaName = areaName;
            st.yearName = yearName;
            st.sortName = sortName;
            st.videos = new java.util.ArrayList<>(videos);
            st.scrollPos = contentGrid == null ? 0 : contentGrid.findFirstVisibleItemPosition();
            st.fromView = stack.isEmpty() ? V_CONTENT : stack.peek();
            subStack.push(st);
            // 派生子规则：find_rule 用页面 rule；extra → MY_PARAMS
            HkRule sub = pageOwnerRule.deriveSubRule(u, pageRule, colType);
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
            clsName = "";
            areaName = "";
            yearName = "";
            sortName = "";
            page = 1;
            noMore = false;
            loading = false;
            contentGen++;
            videos.clear();
            String pageTitle = pageObj.optString("name");
            String itemTitle = stripHtml(it.getTitle());
            contentTitle = !pageTitle.isEmpty() ? pageTitle : (itemTitle.isEmpty() ? st.title : itemTitle);
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
            if (url.startsWith("download://")) {
                try {
                    String realUrl = url.substring(11).trim();
                    android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(android.net.Uri.parse(realUrl));
                    req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setTitle(it != null && !TextUtils.isEmpty(it.getTitle()) ? stripHtml(it.getTitle()) : "下载");
                    dm.enqueue(req);
                    android.widget.Toast.makeText(this, "开始下载", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(this, "下载失败", android.widget.Toast.LENGTH_SHORT).show();
                }
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.hk_bottom_card);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(dialogTitle);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFF1A1D24);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(12);
        root.addView(tvTitle, titleLp);
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("请输入");
        til.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText et = new TextInputEditText(til.getContext());
        if (!TextUtils.isEmpty(def)) et.setText(def);
        et.setTextColor(0xFF1A1D24);
        et.setSingleLine(true);
        til.addView(et);
        LinearLayout.LayoutParams tilLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tilLp.bottomMargin = dp(12);
        root.addView(til, tilLp);
        View divider = new View(this);
        divider.setBackgroundColor(0xFFEEEEEE);
        root.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnCancel = new TextView(this);
        btnCancel.setText("取消");
        btnCancel.setTextSize(16);
        btnCancel.setTextColor(0xFF666666);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setBackgroundResource(android.R.attr.selectableItemBackground);
        TextView btnOk = new TextView(this);
        btnOk.setText("确认");
        btnOk.setTextSize(16);
        btnOk.setTextColor(0xFF666666);
        btnOk.setGravity(Gravity.CENTER);
        btnOk.setBackgroundResource(android.R.attr.selectableItemBackground);
        row.addView(btnCancel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        View vdiv = new View(this);
        vdiv.setBackgroundColor(0xFFEEEEEE);
        row.addView(vdiv, new LinearLayout.LayoutParams(1,
                LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(btnOk, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));
        final Dialog dialog = newHkBottomDialog(root, 0.45f);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnOk.setOnClickListener(v -> {
            dialog.dismiss();
            cb.accept(et.getText() == null ? "" : et.getText().toString().trim());
        });
        if (!isFinishing() && !isDestroyed()) dialog.show();
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
        static final int T_MOVIE_VERTICAL = 25; // movie_1_vertical_pic：全宽左竖海报右文（8.83 item_movie_one_col_vertical_pic）
        static final int T_MOVIE_VERTICAL_BLUR = 26; // movie_1_vertical_pic_blur：毛玻璃背景 + 竖海报
        static final int T_EMPTY = 27; // 8.83 原版：poly 分组内非首个条目占位的空行（getItemViewType 返回 -1）
        // 8.83 原版：以下条目类型各自独立 viewType（对照 ArticleColTypeEnum），不合并
        static final int T_TEXT_1 = 28;
        static final int T_TEXT_CENTER_1 = 29;
        static final int T_LONG_TEXT = 30;
        static final int T_HEADER = 31;
        static final int T_FOOTER = 32;
        static final int T_TEXT_2 = 33;
        static final int T_TEXT_3 = 34;
        static final int T_TEXT_4 = 35;
        static final int T_TEXT_5 = 36;
        static final int T_CARD_PIC_1 = 37;
        static final int T_CARD_PIC_2 = 38;
        static final int T_CARD_PIC_2_2 = 39;
        static final int T_CARD_PIC_2_2_LEFT = 40;
        static final int T_CARD_PIC_3 = 41;
        static final int T_CARD_PIC_3_CENTER = 42;
        static final int T_PIC_1 = 43;
        static final int T_PIC_1_CARD = 44;
        static final int T_PIC_1_CENTER = 45;
        static final int T_PIC_1_FULL = 46;
        static final int T_PIC_2 = 47;
        static final int T_PIC_2_CARD = 48;
        static final int T_PIC_3 = 49;
        static final int T_PIC_3_SQUARE = 50;
        static final int T_DETAIL_HEADER = 51; // 8.83 原版详情页头部：movie_one_col（标题左+封面右）
        static final int T_LOAD_MORE = 52; // 内容列表末尾"下一页"按钮（对照 8.83 view_load_more_footer）

        private final List<HkItem> source;
        /** 展示列表（与 source 同一引用；8.83 原版 getItemCount 直接返回 list.size()，不做快照）。 */
        private List<HkItem> items;
        /** 8.83 updatePolyList：连续相同 col_type 条目的分组。 */
        private final java.util.List<java.util.List<HkItem>> polyList = new java.util.ArrayList<>();
        /** 8.83 polyPositionMap：条目下标 → 所属分组下标。 */
        private final java.util.Map<Integer, Integer> polyPositionMap = new java.util.HashMap<>();
        /** 内容页列表末尾是否追加"下一页"按钮（详情页复用本 adapter 时关闭）。 */
        private final boolean footerMore;
        /** 详情页模式：scroll_button/flex_button 按 8.83 区分渲染（内容页保持旧灰胶囊）。 */
        private boolean detailMode;
        /** 足迹高亮的选集 URL（详情页）。 */
        private String highlightUrl = "";
        /** scroll_button 已展开的分组下标集合（详情页）。 */
        private final java.util.Set<Integer> expandedBtnGroups = new java.util.HashSet<>();
        private final int picH;

        ContentAdapter(List<HkItem> items) {
            this(items, false);
        }

        ContentAdapter(List<HkItem> items, boolean footerMore) {
            this.source = items;
            this.items = items;
            this.footerMore = footerMore;
            updatePolyList();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            picH = (dm.widthPixels - dp(16) * 2) * 9 / 16;
        }

        /** 是否显示"下一页" footer：内容页有数据且还有下一页时显示。 */
        private boolean showFooter() {
            return footerMore && items != null && !items.isEmpty() && !noMore;
        }

        /**
         * videos 增删后重算 poly 分组（对照 8.83 updatePolyList 调用点）。
         */
        void refreshGroups() {
            updatePolyList();
        }

        void setDetailMode(boolean m) {
            detailMode = m;
        }

        void setHighlightUrl(String url) {
            highlightUrl = url == null ? "" : url;
        }

        /**
         * 条目下标 → 其 poly 分组首个条目下标（足迹滚动定位用；按钮组渲染为单个 ViewHolder）。
         */
        int groupFirstPosition(int position) {
            Integer gi = polyPositionMap.get(position);
            if (gi == null || gi < 0 || gi >= polyList.size()) return position;
            List<HkItem> group = polyList.get(gi);
            if (group == null || group.isEmpty()) return position;
            HkItem first = group.get(0);
            for (int i = 0; i < source.size(); i++) {
                if (source.get(i) == first) return i;
            }
            return position;
        }

        /**
         * 对照 8.83 ArticleListAdapter.updatePolyList()（反编译第 190-213 行）：
         * 连续相同 col_type 的条目自动合并为一组，polyPositionMap 记录条目下标→分组下标。
         * 不造假 col_type，不合成条目。
         */
        private void updatePolyList() {
            polyList.clear();
            polyPositionMap.clear();
            if (source == null || source.isEmpty()) return;
            for (int i = 0; i < source.size(); i++) {
                if (polyList.isEmpty()) polyList.add(new ArrayList<>());
                List<HkItem> last = polyList.get(polyList.size() - 1);
                if (last.isEmpty()) {
                    last.add(source.get(i));
                    polyPositionMap.put(i, 0);
                } else if (colTypeOf(last.get(0)).equals(colTypeOf(source.get(i)))) {
                    last.add(source.get(i));
                    polyPositionMap.put(i, polyList.size() - 1);
                } else {
                    List<HkItem> g = new ArrayList<>();
                    g.add(source.get(i));
                    polyList.add(g);
                    polyPositionMap.put(i, polyList.size() - 1);
                }
            }
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
                case T_TEXT_2:
                    // text_2=30（2/行）
                    if (ct.endsWith("_2")) return 30;
                    if (ct.endsWith("_3")) return 20;
                    if (ct.endsWith("_4")) return 15;
                    if (ct.endsWith("_5")) return 12;
                    return 30;
                case T_TEXT_3:
                    return 20; // text_3=20（3/行）
                case T_TEXT_4:
                    return 15; // text_4=15（4/行）
                case T_TEXT_5:
                    return 12; // text_5=12（5/行）
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
                case T_CARD_PIC_1:
                    return 60; // card_pic_1 全宽
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                    return 30; // 2/行
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    return 20; // 3/行
                case T_MOVIE_LEFT_PIC:
                    return 60; // movie_1 左图右文全宽
                case T_MOVIE_VERTICAL:
                case T_MOVIE_VERTICAL_BLUR:
                    return 60; // movie_1_vertical_pic(_blur) 全宽（D3）
                case T_PIC:
                    if (ct.startsWith("pic_2") || "card_pic_2".equals(ct)
                            || "card_pic_2_2".equals(ct) || "card_pic_2_2_left".equals(ct)) return 30;
                    if (ct.startsWith("pic_3") || "card_pic_3".equals(ct) || "card_pic_3_center".equals(ct)) return 20;
                    return 60; // pic_1 / pic_1_full / pic_1_center / pic_1_card / card_pic_1
                case T_PIC_1:
                case T_PIC_1_CARD:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                    return 60; // pic_1 系列全宽
                case T_PIC_2:
                case T_PIC_2_CARD:
                    return 30; // pic_2 系列 2/行
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    return 20; // pic_3 系列 3/行
                case T_DETAIL_HEADER:
                    return 60; // 详情页头部全宽
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
                        // 封面高度由 bindVideo 按列数动态算（高=宽×17/12），此处不再固定
                        break;
                    case T_TEXT:
                    case T_TEXT_1:
                    case T_TEXT_CENTER_1:
                    case T_LONG_TEXT:
                    case T_HEADER:
                    case T_FOOTER:
                    case T_RICH:
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_COLS:
                    case T_TEXT_2:
                    case T_TEXT_3:
                    case T_TEXT_4:
                    case T_TEXT_5:
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
                    case T_CARD_PIC_1:
                    case T_CARD_PIC_2:
                    case T_CARD_PIC_2_2:
                    case T_CARD_PIC_2_2_LEFT:
                    case T_CARD_PIC_3:
                    case T_CARD_PIC_3_CENTER:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        overlay = v.findViewById(R.id.tv_overlay);
                        break;
                    case T_MOVIE_LEFT_PIC:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_MOVIE_VERTICAL:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_MOVIE_VERTICAL_BLUR:
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_desc);
                        break;
                    case T_PIC_1:
                    case T_PIC_1_CARD:
                        // 图文：对照 8.83 item_pic_one_col / item_pic_one_card_col
                        cover = v.findViewById(R.id.iv_cover);
                        title = v.findViewById(R.id.tv_title);
                        desc = v.findViewById(R.id.tv_overlay);
                        break;
                    case T_PIC:
                    case T_PIC_1_CENTER:
                    case T_PIC_1_FULL:
                    case T_PIC_2:
                    case T_PIC_2_CARD:
                    case T_PIC_3:
                    case T_PIC_3_SQUARE:
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
                    case T_WEB:
                        web = v.findViewById(R.id.wv_page);
                        web.getSettings().setJavaScriptEnabled(true);
                        web.getSettings().setDomStorageEnabled(true);
                        web.setWebViewClient(new android.webkit.WebViewClient());
                        break;
                    case T_DETAIL_HEADER:
                        // 8.83 原版详情页头部（item_movie_one_col）：标题左 + 封面右
                        cover = v.findViewById(R.id.dh_cover);
                        title = v.findViewById(R.id.dh_title);
                        desc = v.findViewById(R.id.dh_desc);
                        break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (showFooter() && items != null && position == items.size()) return T_LOAD_MORE;
            if (position < 0 || items == null || position >= items.size()) return T_VIDEO;
            int t = contentTypeOf(items.get(position));
            // 对照 8.83 getItemViewType（反编译第 309-321 行）：
            // scroll_button/flex_button 连续组内非首个条目 → 空行（8.83 返回 -1）
            if (t == T_BUTTONS) {
                Integer gi = polyPositionMap.get(position);
                if (gi != null && gi >= 0 && gi < polyList.size()) {
                    List<HkItem> g = polyList.get(gi);
                    if (g != null && !g.isEmpty() && g.get(0) != items.get(position)) {
                        return T_EMPTY;
                    }
                }
            }
            return t;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 8.83 原版：poly 分组内非首个条目返回 -1，对应空行占位
            if (viewType == T_EMPTY) {
                android.widget.Space v = new android.widget.Space(parent.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0));
                return new Holder(v, viewType);
            }
            int layout;
            switch (viewType) {
                case T_TEXT:
                case T_TEXT_1:
                case T_TEXT_CENTER_1:
                case T_LONG_TEXT:
                case T_HEADER:
                case T_FOOTER:
                case T_RICH:
                    layout = R.layout.item_hk_text;
                    break;
                case T_COLS:
                case T_TEXT_2:
                case T_TEXT_3:
                case T_TEXT_4:
                case T_TEXT_5:
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
                case T_CARD_PIC_1:
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    layout = R.layout.item_hk_card_rect;
                    break;
                case T_MOVIE_LEFT_PIC:
                    layout = R.layout.item_hk_movie_left_pic;
                    break;
                case T_MOVIE_VERTICAL:
                    layout = R.layout.item_hk_movie_vertical;
                    break;
                case T_MOVIE_VERTICAL_BLUR:
                    layout = R.layout.item_hk_movie_vertical_blur;
                    break;
                case T_PIC_1:
                    layout = R.layout.item_hk_pic_one_col; // pic_1 图文（对照 8.83 item_pic_one_col）
                    break;
                case T_PIC_1_CARD:
                    layout = R.layout.item_hk_pic_one_card; // pic_1_card 图文卡片（对照 8.83 item_pic_one_card_col）
                    break;
                case T_PIC:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                case T_PIC_2:
                case T_PIC_2_CARD:
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    layout = R.layout.item_hk_pic;
                    break;
                case T_MOVIE:
                    layout = R.layout.item_hk_movie;
                    break;
                case T_INPUT:
                    layout = R.layout.item_hk_input;
                    break;
                case T_WEB:
                    layout = R.layout.item_hk_web;
                    break;
                case T_DETAIL_HEADER:
                    layout = R.layout.item_hk_detail_header;
                    break;
                case T_LOAD_MORE:
                    layout = R.layout.item_hk_load_more_footer;
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
            // 列表末尾"下一页"按钮：点后加载下一页（loadContent 自带 loading 守卫）
            if (h.getItemViewType() == T_LOAD_MORE) {
                h.itemView.setOnClickListener(v -> loadContent(false));
                return;
            }
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
                case T_TEXT_1:
                case T_TEXT_CENTER_1:
                case T_LONG_TEXT:
                case T_HEADER:
                case T_FOOTER:
                    bindText(h, item);
                    break;
                case T_RICH:
                    bindRich(h, item);
                    break;
                case T_COLS:
                case T_TEXT_2:
                case T_TEXT_3:
                case T_TEXT_4:
                case T_TEXT_5:
                    bindCols(h, item);
                    break;
                case T_BUTTONS:
                    bindButtons(h, position);
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
                case T_CARD_PIC_1:
                case T_CARD_PIC_2:
                case T_CARD_PIC_2_2:
                case T_CARD_PIC_2_2_LEFT:
                case T_CARD_PIC_3:
                case T_CARD_PIC_3_CENTER:
                    bindCard(h, item);
                    break;
                case T_MOVIE_LEFT_PIC:
                    bindMovie(h, item);
                    break;
                case T_MOVIE_VERTICAL:
                    bindMovieVertical(h, item, false);
                    break;
                case T_MOVIE_VERTICAL_BLUR:
                    bindMovieVertical(h, item, true);
                    break;
                case T_PIC:
                case T_PIC_1:
                case T_PIC_1_CARD:
                case T_PIC_1_CENTER:
                case T_PIC_1_FULL:
                case T_PIC_2:
                case T_PIC_2_CARD:
                case T_PIC_3:
                case T_PIC_3_SQUARE:
                    bindPic(h, item);
                    break;
                case T_MOVIE:
                    bindMovie(h, item);
                    break;
                case T_INPUT:
                    bindInput(h, item);
                    break;
                case T_EMPTY:
                    // poly 分组内非首个条目的空行占位，无需绑定
                    break;
                case T_WEB:
                    bindWeb(h, item);
                    break;
                case T_DETAIL_HEADER:
                    bindDetailHeader(h, item);
                    break;
            }
        }

        @Override
        public int getItemCount() {
            int n = items == null ? 0 : items.size();
            return showFooter() ? n + 1 : n;
        }

        /** 视频卡片：保持原 VideoAdapter 行为；desc 压在封面底部（原版 overlay 样式）。 */
        private void bindVideo(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
            // movie_3 卡片封面高度动态算：高=宽×17/12，宽=(屏宽-12dp×(列数+1))/列数；
            // 条目上下 margin 各 6dp
            String ct = item.getColType() == null ? "" : item.getColType().trim().toLowerCase();
            int cols = "movie_2".equals(ct) ? 2 : 3;
            DisplayMetrics dmv = h.itemView.getContext().getResources().getDisplayMetrics();
            int cardW = (dmv.widthPixels - dp(12) * (cols + 1)) / cols;
            int cardH = cardW * 17 / 12;
            View coverBox = h.itemView.findViewById(R.id.cover_container);
            View coverTarget = coverBox != null ? coverBox : h.cover;
            ViewGroup.LayoutParams vlp = coverTarget.getLayoutParams();
            if (vlp.height != cardH) {
                vlp.height = cardH;
                coverTarget.setLayoutParams(vlp);
            }
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) h.itemView.getLayoutParams();
            int m6 = dp(6);
            if (mlp.topMargin != m6 || mlp.bottomMargin != m6) {
                mlp.topMargin = m6;
                mlp.bottomMargin = m6;
                h.itemView.setLayoutParams(mlp);
            }
            h.title.setText(titleSpan(item.getTitle()));
            // movie_3_marquee：标题跑马灯（对照 8.83 item_movie_tools_marquee，marqueeRepeatLimit=3）；
            // 非 marquee 时复位（防复用串台）
            if ("movie_3_marquee".equals(ct)) {
                h.title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
                h.title.setSingleLine(true);
                h.title.setMarqueeRepeatLimit(3);
                h.title.setSelected(true);
            } else {
                // 8.83 原版：标题单行截断（item_movie_tools reult_tools_title maxLines=1）
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setSingleLine(true);
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
            // 8.83：所有条目类型走同一条 onClick→clickItem→dealWithUrl 链，
            // video 也不单开直调详情（否则动作协议/@rule=分流/详情内直播等都被跳过）
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0 && pos < items.size() && currentRule != null) {
                        onContentItemClick(items.get(pos));
                    }
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "bindVideo click failed", e);
                }
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

        /**
         * 按钮组：8.83 区分 scroll_button / flex_button（详情页）。
         * - flex_button：无底色 16sp 纯文字按钮，换行流式（min 40×30dp）
         * - scroll_button：横向滚动行 + 右侧 ">" 展开按钮
         * 内容页保持旧灰底圆角胶囊不变。
         */
        private void bindButtons(Holder h, int position) {
            h.cols.removeAllViews();
            Integer gi = polyPositionMap.get(position);
            List<HkItem> subs = (gi == null || gi < 0 || gi >= polyList.size()) ? null : polyList.get(gi);
            if (subs == null || subs.isEmpty()) return;
            String ct = colTypeOf(subs.get(0));
            if (detailMode && "flex_button".equals(ct)) {
                bindFlexButtons(h, subs);
                return;
            }
            if (detailMode && "scroll_button".equals(ct)) {
                bindScrollButtons(h, position, gi, subs);
                return;
            }
            bindCapsuleButtons(h, subs);
        }

        /** 旧灰底圆角胶囊（内容页保持）。 */
        private void bindCapsuleButtons(Holder h, List<HkItem> subs) {
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

        /**
         * 8.83 flex_button（item_article_flex_btn_item）：无底色 16sp 纯文字按钮，
         * 换行流式，min 40×30dp；足迹选中项文字染绿（8.83 yellowAction #109d58）。
         */
        private void bindFlexButtons(Holder h, List<HkItem> subs) {
            Context ctx = h.itemView.getContext();
            int parentW = h.cols.getWidth();
            if (parentW <= 0) {
                parentW = ctx.getResources().getDisplayMetrics().widthPixels - dp(16) * 2;
            }
            LinearLayout flow = new LinearLayout(ctx);
            flow.setOrientation(LinearLayout.VERTICAL);
            LinearLayout row = newButtonRow(ctx);
            int rowW = 0;
            for (HkItem sub : subs) {
                TextView tv = newFlexButtonView(ctx, sub);
                tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int w = tv.getMeasuredWidth() + dp(4);
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

        private TextView newFlexButtonView(Context ctx, HkItem sub) {
            TextView tv = new TextView(ctx);
            tv.setText(titleSpan(sub.getTitle()));
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMinWidth(dp(40));
            tv.setMinHeight(dp(30));
            tv.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(2);
            lp.setMargins(m, m, m, m);
            tv.setLayoutParams(lp);
            boolean sel = !highlightUrl.isEmpty() && highlightUrl.equals(sub.getUrl());
            tv.setTextColor(sel ? 0xFF109D58 : 0xFF444444);
            tv.setOnClickListener(v -> onContentItemClick(sub));
            return tv;
        }

        /**
         * 8.83 scroll_button：横向滚动行 + 右侧 ">" 展开按钮（item_article_header_item 风格：
         * 16sp 白底圆角，选中项浅黄底 + 绿字）。点 ">" 在单行滚动 / 换行展开间切换。
         */
        private void bindScrollButtons(Holder h, int position, int gi, List<HkItem> subs) {
            Context ctx = h.itemView.getContext();
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            boolean expanded = expandedBtnGroups.contains(gi);
            if (expanded) {
                int parentW = h.cols.getWidth();
                if (parentW <= 0) {
                    parentW = ctx.getResources().getDisplayMetrics().widthPixels - dp(16) * 2;
                }
                LinearLayout flow = new LinearLayout(ctx);
                flow.setOrientation(LinearLayout.VERTICAL);
                LinearLayout row = newButtonRow(ctx);
                int rowW = 0;
                for (HkItem sub : subs) {
                    TextView tv = newScrollButtonView(ctx, sub);
                    tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    int w = tv.getMeasuredWidth() + dp(10);
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
                root.addView(flow, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else {
                android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(ctx);
                sv.setHorizontalScrollBarEnabled(false);
                LinearLayout row = newButtonRow(ctx);
                for (HkItem sub : subs) row.addView(newScrollButtonView(ctx, sub));
                sv.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                root.addView(sv, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            TextView expand = new TextView(ctx);
            expand.setText(expanded ? "∧" : ">");
            expand.setTextSize(16);
            expand.setTextColor(0xFF222222);
            expand.setGravity(Gravity.CENTER);
            expand.setMinHeight(dp(30));
            expand.setPadding(dp(10), dp(4), dp(10), dp(4));
            expand.setContentDescription("展开");
            expand.setOnClickListener(v -> {
                if (expandedBtnGroups.contains(gi)) expandedBtnGroups.remove(gi);
                else expandedBtnGroups.add(gi);
                notifyItemChanged(position);
            });
            root.addView(expand, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            h.cols.addView(root, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private TextView newScrollButtonView(Context ctx, HkItem sub) {
            TextView tv = new TextView(ctx);
            tv.setText(titleSpan(sub.getTitle()));
            tv.setTextSize(16);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMinHeight(dp(30));
            tv.setPadding(dp(5), dp(4), dp(5), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = dp(5);
            lp.setMargins(m, 0, m, 0);
            tv.setLayoutParams(lp);
            boolean sel = !highlightUrl.isEmpty() && highlightUrl.equals(sub.getUrl());
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(sel ? 0xFFE6F3E6 : 0xFFFFFFFF);
            if (!sel) {
                bg.setStroke(dp(1), 0xFFE0E0E0);
            }
            tv.setBackground(bg);
            tv.setTextColor(sel ? 0xFF109D58 : 0xFF1A1D24);
            tv.setOnClickListener(v -> onContentItemClick(sub));
            return tv;
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
            int vt = h.getItemViewType();
            if (vt == T_PIC_1 || vt == T_PIC_1_CARD) {
                // 图文：对照 8.83 item_pic_one_col / item_pic_one_card_col
                if (h.title == null || h.cover == null) return; // 防 ViewHolder 类型错配
                h.title.setText(titleSpan(item.getTitle()));
                String pd = stripHtml(item.getDesc());
                if (h.desc != null) {
                    h.desc.setText(pd);
                    h.desc.setVisibility(TextUtils.isEmpty(pd) ? View.GONE : View.VISIBLE);
                }
                ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
                h.cover.setContentDescription(stripHtml(item.getTitle()));
                setContentClick(h, item);
                return;
            }
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

        /**
         * 竖海报行（D3）：对照 8.83 item_movie_one_col_vertical_pic(_blur)。
         * 全宽左 95x130dp 竖海报右文；blur=true 时背景加毛玻璃（8.83 的 blur 头图）。
         */
        private void bindMovieVertical(Holder h, HkItem item, boolean blur) {
            if (h.title == null || h.desc == null || h.cover == null) return;
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            h.desc.setText(d);
            h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            ImgUtil.load(item.getTitle(), item.getPic(), h.cover, hkRuleUrl());
            if (blur) {
                View bg = h.itemView.findViewById(R.id.iv_blur_bg);
                if (bg instanceof android.widget.ImageView) {
                    ImgUtil.load(item.getTitle(), item.getPic(), (android.widget.ImageView) bg, hkRuleUrl());
                    try {
                        com.xingchen.tv.theme.FrostedGlass.blurBackground(bg);
                    } catch (Throwable ignored) {
                    }
                }
            }
            setContentClick(h, item);
        }

        /**
         * 8.83 原版详情页头部（item_movie_one_col）：标题左 + 封面右（130×90dp，圆角 8dp）。
         * 标题 16sp 最多 2 行省略，描述最多 2 行底部对齐。点击走通用条目点击。
         */
        private void bindDetailHeader(Holder h, HkItem item) {
            if (h.title == null || h.cover == null) return;
            h.title.setText(titleSpan(item.getTitle()));
            String d = stripHtml(item.getDesc());
            if (h.desc != null) {
                h.desc.setText(d);
                h.desc.setVisibility(TextUtils.isEmpty(d) ? View.GONE : View.VISIBLE);
            }
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

        /**
         * 8.83 ArticleListAdapter.bindClick(src 1974)：所有条目都绑点击（含空链接——
         * 空链接由分流处 toast"链接为空，规则有误！"，不在 bind 时静默去掉）。
         * 点击时按 position 重取条目（8.83 clickItem src 2859 同款，防复用错位）；
         * 越界检查照抄 8.83 clickItem(src 2914)：position<0 或 >=size 直接 return。
         */
        private void setContentClick(Holder h, HkItem item) {
            h.itemView.setOnClickListener(v -> {
                try {
                    int pos = h.getBindingAdapterPosition();
                    if (pos < 0 || pos >= items.size() || currentRule == null) return;
                    onContentItemClick(items.get(pos));
                } catch (Exception e) {
                    android.util.Log.e("HkPage", "setContentClick failed", e);
                }
            });
        }
    }

    private static class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.Holder> {
        interface OnPick {
            void onPick(String value);
        }

        interface OnLongPick {
            void onLongPick(String value);
        }

        private final List<String[]> pairs;
        private int selected;
        private final OnPick listener;
        private OnLongPick longPickListener;
        private int dropTarget = -1;
        private boolean goldMode = false;

        ChipAdapter(List<String[]> pairs, int selected, OnPick listener) {
            this.pairs = pairs;
            this.selected = selected;
            this.listener = listener;
        }

        /** 编程选中（ViewPager 翻页时同步 tab 高亮）。 */
        void setSelected(int pos) {
            if (selected == pos) return;
            selected = pos;
            notifyDataSetChanged();
        }

        void setOnLongPick(OnLongPick l) {
            longPickListener = l;
        }

        /** 金色主题模式：小程序切换 tabs 用淡金底，与分类 pills（白底/绿选中）颜色区分，一眼看出是两排不同的东西。 */
        void setGoldMode(boolean goldMode) {
            this.goldMode = goldMode;
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
            com.google.android.material.card.MaterialCardView card;

            Holder(View v) {
                super(v);
                chip = v.findViewById(R.id.tv_chip);
                android.view.View p = (android.view.View) chip.getParent();
                card = p instanceof com.google.android.material.card.MaterialCardView
                        ? (com.google.android.material.card.MaterialCardView) p : null;
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_hk_chip, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            String[] pair = pairs.get(position);
            h.chip.setText(pair[0]);
            boolean sel = position == selected;
            h.chip.setSelected(sel);
            boolean drop = position == dropTarget;
            if (goldMode) {
                // 金色主题：小程序切换 tabs 在圆角框内，透明底 + 金色系文字，与分类 pills 区分
                if (h.card != null) {
                    h.card.setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
                    h.card.setStrokeWidth(0);
                }
                h.chip.setBackgroundResource(0);
                h.chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                h.chip.setTextColor(sel ? 0xFF8A6D00 : 0xFF8A8F98);
            } else if (drop) {
                // 拖拽投放目标：金色加粗 + 选中底，提示松手即进该分组
                if (h.card != null) {
                    h.card.setCardBackgroundColor(0xFFFFFFFF);
                    h.card.setStrokeWidth(0);
                }
                h.chip.setTextColor(0xFFB8890F);
                h.chip.setTypeface(null, android.graphics.Typeface.BOLD);
                h.chip.setBackgroundResource(R.drawable.hk_ripple_glass_selected);
            } else {
                // 恢复卡片默认样式（防止从 goldMode 复用过来的 view 残留透明底）
                if (h.card != null) {
                    h.card.setCardBackgroundColor(0xFFFFFFFF);
                    h.card.setStrokeWidth(0);
                }
                h.chip.setTypeface(null, android.graphics.Typeface.NORMAL);
                h.chip.setTextColor(sel ? 0xFF1B5E20 : 0xFF666666);
                h.chip.setBackgroundResource(sel ? R.drawable.hk_ripple_glass_selected : R.drawable.hk_ripple_glass);
            }
            h.chip.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                selected = pos;
                notifyDataSetChanged();
                if (listener != null) listener.onPick(pairs.get(pos)[1]);
            });
            h.itemView.setOnLongClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && longPickListener != null) {
                    longPickListener.onLongPick(pairs.get(pos)[1]);
                    return true;
                }
                return false;
            });
        }

        @Override
        public int getItemCount() {
            return pairs.size();
        }
    }
}
