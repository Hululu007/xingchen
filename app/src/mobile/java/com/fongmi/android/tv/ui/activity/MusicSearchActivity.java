package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.music.MusicFrostDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐搜索独立页（2026-10-25 用户定案重做）：构成只有标题「搜索」＋搜索框行（＋取消）
 * ＋分类页签（单曲／专辑／歌手／歌单，构成与次序照用户参照图）＋源横排 chips
 * （2026-10-20 定「搜索结果按源横排」：各源分开、点哪个源看哪个源的结果）
 * ＋该源的结果列表（行：歌名、歌手 - 专辑、来源标、⋮）。
 * 搜索执行仍是多源并发、按源分桶承接 chips；工具行（随机／播放全部／来源下拉／排序）、
 * 组筛选条、落地页（热门／猜你想搜）已全摘；搜索历史 2026-10-25 用户点名补回
 * （只摆历史词、点词即搜，不要热门／猜你想搜）。结果列表滑到底自动续下一页
 * （按桶分页，页码透传到源；旧实现全链写死第一页）。
 * 行播放走 MusicController，队列 route=list、参数 mode=search＋keyword，队列面板来源跳转落回本页。
 * 无返回箭头，走系统返回／搜索框行「取消」。
 *
 * 2026-10-27 恢复说明（潇洒哥改令「恢复星辰旧版搜索页」）：本页 2026-10-25 曾被 608b4c51
 * 摘掉页签＋源 chips 改成全源聚合混排（后又叠 6aeadb44 分段拼接），他真机定罪「全部堆在
 * 一个列表里」后令整套恢复。本文件取自 608b4c51^ 的旧实现，前移了拆除之后落的改进：
 * 末页信号（searchSitePage 消费 end）、分桶失败标记（选中源失败单独给文案、不冒充无结果）、
 * 失败留痕、酷狗封面／咪咕时长／115 标签三路后补监听回填、封面三态（在途玻璃空位／
 * 后补淡入一次／直出）、历史胶囊玻璃化、状态层淡入淡出、滚底「没有更多了」一次提示。
 * 源 chips 名单＝MusicRepository.searchableSites() 快照：壳子五家（落雪合成项）、油管
 * （落雪 yt 项）、115 网盘（pan://115，登录才出）、FreeJS／Py 插件源都自动进片，点片只看该源。
 */
public class MusicSearchActivity extends BaseActivity {

    /** 布局壳（BaseActivity 要求 getBinding；本页不用生成 Binding，视图一律 findViewById）。 */
    private static final class SimpleBinding implements ViewBinding {
        private final View root;

        SimpleBinding(View root) {
            this.root = root;
        }

        @Override
        public View getRoot() {
            return root;
        }
    }

    private View rootView;
    private EditText etKeyword;
    private LinearLayout tabRow;
    private RecyclerView sourceRow;
    private RecyclerView recycler;
    private TextView empty;
    private View historyBox;
    private com.fongmi.android.tv.ui.widget.FlowLayout historyFlow;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<Object> cards = new ArrayList<>();
    private SearchAdapter adapter;
    private ChipAdapter chipAdapter;
    private final List<TextView> tabNames = new ArrayList<>();
    private final List<View> tabBars = new ArrayList<>();
    private String keyword = "";
    private String bigTitle = "";

    // 分类页签（构成与次序照参照图：单曲／专辑／歌手／歌单）
    private final List<String> searchCatNames = java.util.Arrays.asList("单曲", "专辑", "歌手", "歌单");
    private final List<MusicTrack> allResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger searchTotalSites = new AtomicInteger(0);
    private final AtomicInteger searchFailedSites = new AtomicInteger(0);
    private int selectedCat = 0;
    private int searchSeq = 0;
    /** 组内钻取：-1 不钻取，1 歌手组，3 专辑组（与分组类型对齐，与页签下标无关） */
    private int searchGroupType = -1;
    private String searchGroupKey = "";
    /** 搜索按源分桶（键＝searchBucketKey：srcUrl 非空用 srcUrl、空走 "site:"+index；
     * 快照 searchSitesSnap 保 sites 顺序，与 chips 同序同键）。
     * selectedSource＝当前 chip 选中源的桶键；chips 无「全部」项，默认选中第一个源。 */
    private final LinkedHashMap<String, List<MusicTrack>> searchBySite = new LinkedHashMap<>();
    private final List<MusicSite> searchSitesSnap = new ArrayList<>();
    private String selectedSource = null;
    private int selectedChip = -1;
    private boolean searchAllReady = false;
    /** 分页态（按桶键记；三表都只在 synchronized(searchBySite) 内读写）：
     * bucketPages＝已到齐的最大页码、bucketEnded＝没有更多（空页／全重复／失败）、
     * bucketLoading＝该桶有请求在途（第一页或续页）。旧实现全链写死第一页、没有这三表。 */
    private final java.util.Map<String, Integer> bucketPages = new java.util.HashMap<>();
    private final java.util.Set<String> bucketEnded = new java.util.HashSet<>();
    private final java.util.Set<String> bucketLoading = new java.util.HashSet<>();
    /** 第一页失败的桶（与 bucketEnded 分开记：终局≠失败，选中源失败要单独给文案） */
    private final java.util.Set<String> bucketFailed = new java.util.HashSet<>();
    /** 第一页回空但源自报了失败归纳的桶（现只油管：LxPlatformSearch.YT_FAIL_* 码）：
     * 与 bucketFailed 分开——那是抛错失败，这是「没抛错但全灭」，空态按死因给明示文案。 */
    private final java.util.Map<String, Integer> bucketFailCause = new java.util.HashMap<>();
    /** 续页终局提示只报一次（每次搜索）：滚底反复触发不许反复弹 */
    private boolean loadMoreEndNotified = false;

    /** 酷狗封面后补回调（源层回填同一批曲目对象后通知）：只重绑被回填的那几行 */
    private final com.fongmi.android.tv.api.music.LxPlatformSearch.KgCoverListener kgCoverListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));
    /** 咪咕时长后补回调：同上，只重绑被回填时长的行 */
    private final com.fongmi.android.tv.api.music.LxPlatformSearch.MgDurationListener mgDurationListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));
    /** 115 标签后补回调（内嵌封面／时长／标签歌名歌手回填）：同上，只重绑被回填的行 */
    private final com.fongmi.android.tv.cloud.drive115.Pan115Tags.FillListener panTagsListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));

    /** 后补回填后按曲目对象身份找当前展示下标、逐行重绑（不在当前列表里的忽略） */
    private void rebindTracks(java.util.List<MusicTrack> filled) {
        if (adapter == null || filled == null || filled.isEmpty()) return;
        for (MusicTrack t : filled) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i) == t) {
                    adapter.notifyItemChanged(i);
                    break;
                }
            }
        }
    }

    private String lastCurrentId;
    private android.os.Parcelable pendingListState;

    public static void startSearch(Activity activity, String keyword) {
        Intent i = new Intent(activity, MusicSearchActivity.class);
        i.putExtra("keyword", keyword == null ? "" : keyword);
        activity.startActivity(i);
    }

    private final MusicController.Listener controllerListener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            refreshCurrentRow();
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            refreshCurrentRow();
        }
    };

    @Override
    protected ViewBinding getBinding() {
        rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_search, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 本页 Manifest 没声明 windowSoftInputMode，默认键盘一弹窗口就 resize：
        // 内容区被压到键盘上方，状态文字（覆盖层里的 empty）会被带着乱跑。
        // 锁成 adjustPan：窗口不缩放、不重排；本页唯一输入框在顶部，键盘遮不到它，平移量恒为 0，
        // 状态文字位置只跟页面有关、跟键盘无关；搜索框可见可用、列表滚动行为不变。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        Intent i = getIntent();
        keyword = i.getStringExtra("keyword");
        if (keyword == null) keyword = "";
        // 重建恢复：已输入与页签选择从 savedInstanceState 回填；滚动位置存布局管理器状态、
        // 数据到位后在 onResume 一次性回滚（恢复的页签只作显示态回填，结果仍走进页加载链）。
        if (savedInstanceState != null) {
            String savedKw = savedInstanceState.getString("ml_keyword");
            if (savedKw != null) keyword = savedKw;
            selectedCat = savedInstanceState.getInt("ml_selectedCat", selectedCat);
            if (selectedCat < 0 || selectedCat >= searchCatNames.size()) selectedCat = 0;
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        bigTitle = getString(R.string.music_search);

        etKeyword = findViewById(R.id.etKeyword);
        tabRow = findViewById(R.id.tabRow);
        sourceRow = findViewById(R.id.sourceRow);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        empty.setOnClickListener(null);
        historyBox = findViewById(R.id.historyBox);
        historyFlow = findViewById(R.id.historyFlow);
        findViewById(R.id.historyClear).setOnClickListener(v -> {
            MusicStore.clearRecentSearches();
            refreshHistory();
        });

        recycler.setLayoutManager(new LinearLayoutManager(this));
        // 滑到底部附近自动续下一页（只续当前选中源的桶，见 tryLoadMore）
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || adapter == null) return;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (!(lm instanceof LinearLayoutManager)) return;
                int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 4) tryLoadMore();
            }
        });
        MusicGlassWire.glassifyTree(rootView);
        recycler.setAdapter(adapter = new SearchAdapter());
        sourceRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        sourceRow.setAdapter(chipAdapter = new ChipAdapter());
        buildTabs();

        findViewById(R.id.searchCancel).setOnClickListener(v -> finish());
        // 搜索只由键盘搜索键显式触发，输入框无任何 TextWatcher 自动搜；
        // watcher 只做反向守卫——文字被清空时清掉结果回初始态，杜绝空输入自动搜／旧结果残留。
        etKeyword.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e == null ? "" : e.toString().trim();
                if (q.isEmpty()) resetToIdle();
            }
        });
        // 提交走键盘搜索键（IME_ACTION_SEARCH／回车，见下）。
        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        com.fongmi.android.tv.api.music.LxPlatformSearch.setKgCoverListener(kgCoverListener);
        com.fongmi.android.tv.api.music.LxPlatformSearch.setMgDurationListener(mgDurationListener);
        com.fongmi.android.tv.cloud.drive115.Pan115Tags.addFillListener(panTagsListener);
        MusicController.get().addListener(controllerListener);
        // 进页分界：有词即搜、无词停在初始态（标题＋搜索框＋有历史时摆历史记录）
        if (!keyword.isEmpty()) {
            etKeyword.setText(keyword);
            doSearch();
        } else {
            refreshHistory();
            // 首次进页（非重建恢复）：光标直接进框、键盘直接起——首页点搜索框进来一步就能打字，
            // 不许让他对着第二个框再点一次。
            if (savedInstanceState == null) {
                etKeyword.post(() -> {
                    etKeyword.requestFocus();
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(etKeyword,
                                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    }
                });
            }
        }
    }

    /** 搜索历史：只在初始态（无已提交关键词）且存量非空时摆出；点词回填输入框并直搜。 */
    private void refreshHistory() {
        if (historyBox == null || historyFlow == null) return;
        if (keyword != null && !keyword.isEmpty()) {
            XcFadeTransition.fadeOutThenGone(historyBox);
            return;
        }
        List<String> words = MusicStore.loadRecentSearches();
        if (words == null || words.isEmpty()) {
            XcFadeTransition.fadeOutThenGone(historyBox);
            return;
        }
        historyFlow.removeAllViews();
        for (final String w : words) {
            TextView chip = new TextView(this);
            chip.setText(w);
            chip.setTextSize(13);
            chip.setTextColor(0xFF3A3A3C);
            chip.setBackgroundResource(R.drawable.bg_music_glass_chip);
            // 历史胶囊是代码动态建的、不在布局树里，glassifyTree 够不着——建后单独过玻璃翻字
            MusicGlassWire.glassify(chip);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setSingleLine(true);
            chip.setMaxWidth(dp(220));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setOnClickListener(v -> {
                etKeyword.setText(w);
                etKeyword.setSelection(w.length());
                doSearch();
            });
            historyFlow.addView(chip);
        }
        XcFadeTransition.fadeIn(historyBox);
    }

    /** 切源／旋转／主题重建回来不丢已输入与滚动位置——输入与选择态走字段回填（见 initView），
     * 滚动位置存布局管理器状态；保存的只是本页 UI 态，不碰任何用户数据与队列。 */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("ml_keyword", etKeyword != null
                    && etKeyword.getText() != null
                    ? etKeyword.getText().toString() : keyword);
            outState.putInt("ml_selectedCat", selectedCat);
            if (recycler != null && recycler.getLayoutManager() != null) {
                outState.putParcelable("ml_listState",
                        recycler.getLayoutManager().onSaveInstanceState());
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 滚动回滚：等本帧布局后一次性应用（数据异步未到时布局管理器会钳到现有行数，不崩；应用后即清，不反复拽）
        if (pendingListState != null && recycler != null) {
            final android.os.Parcelable state = pendingListState;
            pendingListState = null;
            recycler.post(() -> {
                try {
                    if (recycler != null && recycler.getLayoutManager() != null) {
                        recycler.getLayoutManager().onRestoreInstanceState(state);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
        // 搜索回页不重拉
    }

    @Override
    protected void onDestroy() {
        com.fongmi.android.tv.api.music.LxPlatformSearch.setKgCoverListener(null);
        com.fongmi.android.tv.api.music.LxPlatformSearch.setMgDurationListener(null);
        com.fongmi.android.tv.cloud.drive115.Pan115Tags.removeFillListener(panTagsListener);
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 页签与源 chips =================

    /** 分类页签：子项 inflate item_music_search_tab、四等分（weight 1）横排，摆法照参照图。 */
    private void buildTabs() {
        tabRow.removeAllViews();
        tabNames.clear();
        tabBars.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int idx = 0; idx < searchCatNames.size(); idx++) {
            final int cat = idx;
            View v = inf.inflate(R.layout.item_music_search_tab, tabRow, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            v.setLayoutParams(lp);
            TextView name = v.findViewById(R.id.tabName);
            name.setText(searchCatNames.get(idx));
            tabNames.add(name);
            tabBars.add(v.findViewById(R.id.tabBar));
            v.setOnClickListener(view -> selectCategory(cat));
            tabRow.addView(v);
        }
        refreshTabs();
    }

    private void refreshTabs() {
        for (int idx = 0; idx < tabNames.size(); idx++) {
            boolean sel = idx == selectedCat;
            tabNames.get(idx).setTextColor(sel ? 0xFFFA243C : 0xFF9AA7B8);
            tabBars.get(idx).setBackgroundResource(sel ? R.drawable.bg_tab_indicator : 0);
        }
    }

    private void selectCategory(int idx) {
        if (idx < 0 || idx >= searchCatNames.size()) return;
        selectedCat = idx;
        // 重新点页签即退出组内钻取（组筛选条已摘，钻取的退出走页签本身）
        searchGroupType = -1;
        searchGroupKey = "";
        refreshTabs();
        showSearchResults();
    }

    private void selectSource(int idx) {
        if (idx < 0 || idx >= searchSitesSnap.size()) return;
        selectedChip = idx;
        selectedSource = searchBucketKey(searchSitesSnap.get(idx), idx);
        chipAdapter.notifyDataSetChanged();
        sourceRow.smoothScrollToPosition(idx);
        showSearchResults();
    }

    // ================= 搜索 =================

    /**
     * 搜索：多源并发搜、各源结果按源分桶；chips 与分桶同序，默认选中第一个源，
     * 展示永远只摆当前选中源在当前页签下的结果。切页签／切源只换显示、不重搜。
     * 某个源报错只影响它自己，其它源照常出结果；全部失败才显「搜索失败，请重试」。
     */
    private void doSearch() {
        keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            resetToIdle();
            return;
        }
        // 搜索历史落记录（存取在 MusicStore 早已备好、此前全树无人调用，历史恒空）
        MusicStore.pushRecentSearch(keyword);
        if (historyBox != null) historyBox.setVisibility(View.GONE);
        bigTitle = keyword;
        final int seq = ++searchSeq;
        final String kw = keyword;
        allResults.clear();
        synchronized (searchBySite) {
            searchBySite.clear();
            bucketPages.clear();
            bucketEnded.clear();
            bucketLoading.clear();
            bucketFailed.clear();
            bucketFailCause.clear();
        }
        loadMoreEndNotified = false;
        searchSitesSnap.clear();
        selectedSource = null;
        selectedChip = -1;
        searchAllReady = false;
        searchTotalSites.set(0);
        searchFailedSites.set(0);
        selectedCat = 0;
        searchGroupType = -1;
        searchGroupKey = "";
        refreshTabs();
        chipAdapter.notifyDataSetChanged();
        tabRow.setVisibility(View.VISIBLE);
        sourceRow.setVisibility(View.VISIBLE);
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        showLoading();
        Task.execute(() -> {
            List<MusicSite> tabs = MusicRepository.searchableSites();
            synchronized (searchBySite) {
                searchSitesSnap.clear();
                searchSitesSnap.addAll(tabs);
            }
            searchTotalSites.set(tabs.size());
            if (tabs.isEmpty()) {
                runOnUiThread(() -> {
                    if (seq == searchSeq) {
                        searchAllReady = true;
                        showSearchResults();
                    }
                });
                return;
            }
            // 快照到位先落 chips 并默认选中第一个源（不等全部源搜完）
            runOnUiThread(() -> {
                if (seq != searchSeq) return;
                selectedChip = 0;
                selectedSource = searchBucketKey(searchSitesSnap.get(0), 0);
                chipAdapter.notifyDataSetChanged();
            });
            final AtomicInteger pending = new AtomicInteger(tabs.size());
            for (int si = 0; si < tabs.size(); si++) {
                final MusicSite site = tabs.get(si);
                final String bucketKey = searchBucketKey(site, si);
                // 分源任务走大池（大扫查 B-1 移植）：共享 5 线程池装不下全源并发，
                // 后排源只能排队等前排慢源跑完，整页长挂「正在加载」
                Task.largeExecutor().execute(() -> {
                    synchronized (searchBySite) {
                        bucketLoading.add(bucketKey);
                    }
                    try {
                        com.fongmi.android.tv.music.MusicSearchPage pg =
                                MusicRepository.searchSitePage(site, kw, 1);
                        List<MusicTrack> r = pg == null ? null : pg.tracks;
                        if (seq == searchSeq && r != null) {
                            allResults.addAll(r);
                            synchronized (searchBySite) {
                                List<MusicTrack> bucket = searchBySite.get(bucketKey);
                                if (bucket == null) {
                                    bucket = Collections.synchronizedList(new ArrayList<>());
                                    searchBySite.put(bucketKey, bucket);
                                }
                                bucket.addAll(r);
                                bucketPages.put(bucketKey, 1);
                                if (pg.failCause != 0) bucketFailCause.put(bucketKey, pg.failCause);
                                if (r.isEmpty() || pg.end) bucketEnded.add(bucketKey);
                            }
                        }
                    } catch (Throwable t) {
                        if (seq == searchSeq) {
                            searchFailedSites.incrementAndGet();
                            synchronized (searchBySite) {
                                bucketFailed.add(bucketKey);
                            }
                            SpiderDebug.log("music", "search bucket fail site=%s %s",
                                    site.name, t.getMessage());
                        }
                    } finally {
                        // 在途标记无条件清：迟到任务若按 seq 跳过清除，会把新搜索同桶的分页永久堵死；
                        // 终局标记仍按 seq 守卫（新搜索的桶态不归旧任务判）
                        synchronized (searchBySite) {
                            bucketLoading.remove(bucketKey);
                        }
                        if (seq == searchSeq) {
                            synchronized (searchBySite) {
                                // 第一页没到齐（失败）也判终局，防滚动反复重试同一坏源
                                if (!bucketPages.containsKey(bucketKey)) bucketEnded.add(bucketKey);
                            }
                        }
                        // 旧搜索的迟到回调不许动新搜索的状态
                        if (seq == searchSeq) {
                            boolean last = pending.decrementAndGet() == 0;
                            // 当前选中源先到齐就先刷，不等全部源；最后一源到齐置 ready 后总刷一次
                            if (last || bucketKey.equals(selectedSource)) {
                                runOnUiThread(() -> {
                                    if (seq == searchSeq) {
                                        if (last) searchAllReady = true;
                                        showSearchResults();
                                    }
                                });
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * 续下一页：只续当前选中源的桶（展示永远是单源）。歌单签是本地数据无源分页、
     * 组内钻取态不续（钻取数据随桶长大自然变多，退出钻取后可继续续）。
     * 续页结果按 track id 去重后并入桶——源不认页码回了重复页时全重复即判终局，
     * 不会无限续同一页；空页／失败同样判终局。
     */
    private void tryLoadMore() {
        if (keyword == null || keyword.isEmpty()) return;
        if (selectedCat == 3 || searchGroupType >= 0) return;
        final String bucketKey = selectedSource;
        if (bucketKey == null) return;
        final int seq = searchSeq;
        final String kw = keyword;
        final MusicSite site;
        final int nextPage;
        synchronized (searchBySite) {
            // 选中源已到终局：滚底只提示一次「没有更多了」，不再发请求
            if (bucketEnded.contains(bucketKey) && bucketPages.containsKey(bucketKey)) {
                if (!loadMoreEndNotified) {
                    loadMoreEndNotified = true;
                    Notify.show("没有更多了");
                }
                return;
            }
            if (bucketEnded.contains(bucketKey) || bucketLoading.contains(bucketKey)) return;
            Integer cur = bucketPages.get(bucketKey);
            if (cur == null) return;
            nextPage = cur + 1;
            MusicSite found = null;
            for (int i = 0; i < searchSitesSnap.size(); i++) {
                if (bucketKey.equals(searchBucketKey(searchSitesSnap.get(i), i))) {
                    found = searchSitesSnap.get(i);
                    break;
                }
            }
            site = found;
            if (site == null) return;
            bucketLoading.add(bucketKey);
        }
        Task.largeExecutor().execute(() -> {
            List<MusicTrack> page = null;
            boolean pageEnd = false;
            Throwable err = null;
            try {
                com.fongmi.android.tv.music.MusicSearchPage pg =
                        MusicRepository.searchSitePage(site, kw, nextPage);
                if (pg != null) {
                    page = pg.tracks;
                    pageEnd = pg.end;
                }
            } catch (Throwable t) {
                err = t;
                SpiderDebug.log("music", "search loadmore fail site=%s page=%d %s",
                        site.name, nextPage, t.getMessage());
            }
            final List<MusicTrack> pageF = page;
            final boolean pageEndF = pageEnd;
            final boolean failedF = err != null;
            runOnUiThread(() -> {
                int added = 0;
                synchronized (searchBySite) {
                    bucketLoading.remove(bucketKey);
                    if (seq != searchSeq) return;
                    if (pageF != null && !pageF.isEmpty()) {
                        List<MusicTrack> bucket = searchBySite.get(bucketKey);
                        if (bucket != null) {
                            java.util.Set<String> ids = new java.util.HashSet<>();
                            synchronized (bucket) {
                                for (MusicTrack t : bucket) {
                                    if (t != null && t.id != null && !t.id.isEmpty()) ids.add(t.id);
                                }
                            }
                            List<MusicTrack> fresh = new ArrayList<>();
                            for (MusicTrack t : pageF) {
                                if (t == null) continue;
                                if (t.id != null && !t.id.isEmpty() && !ids.add(t.id)) continue;
                                fresh.add(t);
                            }
                            if (!fresh.isEmpty()) {
                                bucket.addAll(fresh);
                                allResults.addAll(fresh);
                                added = fresh.size();
                            }
                        }
                    }
                    if (added > 0) bucketPages.put(bucketKey, nextPage);
                    // 末页信号／空页／全重复／失败都判终局：失败不许无限重试同一坏源，
                    // 失败本身已在源层与本层留痕，页面文案由 bucketFailed 兜（第一页失败才记）
                    if (added == 0 || pageEndF || failedF) bucketEnded.add(bucketKey);
                }
                if (added > 0 && seq == searchSeq && bucketKey.equals(selectedSource)) {
                    showSearchResults();
                }
            });
        });
    }

    /** 回初始态：清结果与选择、页签与源 chips 收起、状态层收起，只剩标题＋搜索框（＋历史）。 */
    private void resetToIdle() {
        searchSeq++;
        keyword = "";
        allResults.clear();
        synchronized (searchBySite) {
            searchBySite.clear();
            bucketPages.clear();
            bucketEnded.clear();
            bucketLoading.clear();
            bucketFailed.clear();
            bucketFailCause.clear();
        }
        loadMoreEndNotified = false;
        searchSitesSnap.clear();
        selectedSource = null;
        selectedChip = -1;
        searchAllReady = false;
        selectedCat = 0;
        searchGroupType = -1;
        searchGroupKey = "";
        refreshTabs();
        chipAdapter.notifyDataSetChanged();
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        tabRow.setVisibility(View.GONE);
        sourceRow.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        refreshHistory();
    }

    /** 当前选中源的桶（chips 无「全部」视图，展示永远是单源的）。 */
    private List<MusicTrack> currentSearchPool() {
        if (selectedSource == null) return new ArrayList<>();
        List<MusicTrack> bucket;
        synchronized (searchBySite) {
            bucket = searchBySite.get(selectedSource);
        }
        if (bucket == null) return new ArrayList<>();
        // synchronizedList 的拷贝构造会迭代本体，必须持桶锁拷，在途源还在 addAll 时裸拷会撕裂
        synchronized (bucket) {
            return new ArrayList<>(bucket);
        }
    }

    /**
     * 搜索行来源名：按桶身份绑定，不按 platform 取第一个（FreeJS 各插件共用 "freejs"、
     * 落雪各平台共用 "lx"，按 platform 取首个会把同格式各源的行全标成第一个源名）。
     * 搜索执行时同一曲目对象同时进 allResults 与所属源桶（searchBySite），故按对象身份
     * 在桶里定位该行，再由桶键反查快照站点名；定位不到返回空（调用方不补字），
     * 不回落 platform 首个——错名比无名更误导。
     */
    private String searchSiteNameForTrack(MusicTrack track) {
        if (track == null) return "";
        String bucketKey = null;
        synchronized (searchBySite) {
            for (Map.Entry<String, List<MusicTrack>> e : searchBySite.entrySet()) {
                List<MusicTrack> bucket = e.getValue();
                if (bucket == null) continue;
                synchronized (bucket) {
                    for (MusicTrack t : bucket) {
                        if (t == track) {
                            bucketKey = e.getKey();
                            break;
                        }
                    }
                }
                if (bucketKey != null) break;
            }
        }
        if (bucketKey == null) return "";
        synchronized (searchBySite) {
            for (int i = 0; i < searchSitesSnap.size(); i++) {
                MusicSite s = searchSitesSnap.get(i);
                if (s != null && bucketKey.equals(searchBucketKey(s, i)) && s.name != null && !s.name.isEmpty()) return s.name;
            }
        }
        return "";
    }

    /** 搜索分桶键：srcUrl 为空时按快照下标走 "site:"+index；分桶／取池／选源／chips 四处同用。 */
    private static String searchBucketKey(MusicSite site, int index) {
        return site != null && site.srcUrl != null && !site.srcUrl.isEmpty() ? site.srcUrl : "site:" + index;
    }

    /** 按当前页签把当前选中源的搜索结果显示到列表区；组内钻取时只显示该组歌曲 */
    private void showSearchResults() {
        if (keyword == null || keyword.isEmpty()) return;
        boolean allFailed = searchTotalSites.get() > 0 && searchFailedSites.get() >= searchTotalSites.get();
        if (searchGroupType == 1 || searchGroupType == 3) {
            // 组内歌曲列：按歌手／专辑在当前源桶内过滤；退出钻取走重新点页签（见 selectCategory）
            List<MusicTrack> pool = currentSearchPool();
            List<MusicTrack> g = new ArrayList<>();
            for (MusicTrack t : pool) {
                String k = searchGroupType == 1 ? artistOf(t) : albumOf(t);
                if (k.equals(searchGroupKey)) g.add(t);
            }
            showTracks(g);
            updateEmptyState(g.isEmpty(), allFailed,
                    "没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
            return;
        }
        switch (selectedCat) {
            case 0: { // 单曲：当前源的歌曲直接列表
                List<MusicTrack> pool = currentSearchPool();
                showTracks(pool);
                updateEmptyState(pool.isEmpty(), allFailed,
                        "没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
                break;
            }
            case 1: // 专辑：当前源桶内分组卡
                showGroupCards(currentSearchPool(), 3,
                        "没有找到『" + keyword + "』相关专辑", allFailed);
                break;
            case 2: // 歌手：当前源桶内分组卡
                showGroupCards(currentSearchPool(), 1,
                        "没有找到『" + keyword + "』相关歌手", allFailed);
                break;
            default: // 歌单：本地歌单名匹配（与源无关，chips 不影响本签）
                showPlaylistCards(allFailed);
                break;
        }
    }

    /**
     * 状态层统一出口：有结果收起；无结果按「全失败／选中源失败／未到齐／真无果」
     * 四态给文案——选中源失败不许再被「没有找到」吞掉，失败与没搜到是两回事。
     * 油管桶另有一态：它不抛错、六家轮试全灭只回空，源自报了归纳死因时按死因明示
     * （连不上／出口被拦／没回结果），不再跟真无果共用一句「没有找到」。
     */
    private void updateEmptyState(boolean isEmpty, boolean allFailed, String notFoundCopy) {
        if (!isEmpty) {
            XcFadeTransition.fadeOutThenGone(empty);
            return;
        }
        XcFadeTransition.fadeIn(empty);
        boolean selFailed;
        String ytCopy = null;
        synchronized (searchBySite) {
            selFailed = selectedSource != null && bucketFailed.contains(selectedSource);
            if (selectedSource != null && isYtBucket(selectedSource)) {
                Integer c = bucketFailCause.get(selectedSource);
                if (c != null) ytCopy = ytFailCopy(c);
            }
        }
        if (allFailed) empty.setText("搜索失败，请重试");
        else if (selFailed) empty.setText("该来源搜索失败，请重试或换个来源");
        else if (ytCopy != null) empty.setText(ytCopy);
        else if (!searchAllReady) empty.setText("正在加载…");
        else empty.setText(notFoundCopy);
    }

    /** 桶键反查快照站点、认是不是油管（落雪合成项 lx://yt）；只许在 synchronized(searchBySite) 内调 */
    private boolean isYtBucket(String bucketKey) {
        for (int i = 0; i < searchSitesSnap.size(); i++) {
            MusicSite s = searchSitesSnap.get(i);
            if (s != null && bucketKey.equals(searchBucketKey(s, i))) {
                return s.srcUrl != null && s.srcUrl.equals("lx://" + LxPlatformSearch.YT);
            }
        }
        return false;
    }

    /** 油管全灭归纳死因 → 明示文案（归纳口径与判定依据在 LxPlatformSearch，推断不出的已归「没回结果」，这里不二次推断） */
    private static String ytFailCopy(int cause) {
        switch (cause) {
            case LxPlatformSearch.YT_FAIL_CONNECT:
                return "油管连不上（出口不通）";
            case LxPlatformSearch.YT_FAIL_BLOCKED:
                return "油管把这个出口拦了，换个节点试试";
            case LxPlatformSearch.YT_FAIL_NO_RESULT:
                return "油管没回结果";
            default:
                return null;
        }
    }

    /** 显示用歌手：占位（空／<unknown>／unknown 类，见 LocalTrackMeta）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    private String artistOf(MusicTrack t) {
        return artistDisplay(t.artist);
    }

    private String albumOf(MusicTrack t) {
        return t.album == null || t.album.isEmpty() ? "未知专辑" : t.album;
    }

    /** 歌手／专辑分组卡：按组名去重分组，点卡进该组歌曲列（入参已是当前源桶） */
    private void showGroupCards(List<MusicTrack> pool, int type, String emptyCopy, boolean allFailed) {
        LinkedHashMap<String, List<MusicTrack>> groups = new LinkedHashMap<>();
        for (MusicTrack t : pool) {
            String k = type == 1 ? artistOf(t) : albumOf(t);
            List<MusicTrack> g = groups.get(k);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(k, g);
            }
            g.add(t);
        }
        tracks.clear();
        cards.clear();
        for (Map.Entry<String, List<MusicTrack>> e : groups.entrySet()) {
            cards.add(new SearchGroupCard(type, e.getKey(), e.getValue().size()));
        }
        adapter.notifyDataSetChanged();
        updateEmptyState(cards.isEmpty(), allFailed, emptyCopy + "\n换一个歌名、歌手或专辑试试");
    }

    /** 歌单页签：本地歌单名包含关键词即命中，曲目数逐个补齐后按原顺序刷 */
    private void showPlaylistCards(boolean allFailed) {
        String kw = keyword == null ? "" : keyword.toLowerCase(java.util.Locale.getDefault());
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        MusicLibrary.playlists(list -> {
            List<MusicPlaylist> hit = new ArrayList<>();
            if (list != null) {
                for (MusicPlaylist p : list) {
                    if (p.getName() != null && p.getName().toLowerCase(java.util.Locale.getDefault()).contains(kw)) {
                        hit.add(p);
                    }
                }
            }
            if (hit.isEmpty()) {
                runOnUiThread(() -> finishPlaylistCards(new ArrayList<>(), allFailed));
                return;
            }
            final SearchPlaylistCard[] arr = new SearchPlaylistCard[hit.size()];
            final AtomicInteger left = new AtomicInteger(hit.size());
            for (int i = 0; i < hit.size(); i++) {
                final int idx = i;
                final MusicPlaylist p = hit.get(i);
                MusicLibrary.playlistTrackCount(p.getId(), n -> {
                    arr[idx] = new SearchPlaylistCard(p.getId(), p.getName(), n);
                    if (left.decrementAndGet() == 0) {
                        List<SearchPlaylistCard> out = new ArrayList<>();
                        for (SearchPlaylistCard c : arr) out.add(c);
                        runOnUiThread(() -> finishPlaylistCards(out, allFailed));
                    }
                });
            }
        });
    }

    private void finishPlaylistCards(List<SearchPlaylistCard> list, boolean allFailed) {
        // 歌单签是异步补数：回来时若已切走页签／清了词，旧结果不许盖新页签
        if (selectedCat != 3 || searchGroupType >= 0 || keyword == null || keyword.isEmpty()) return;
        tracks.clear();
        cards.clear();
        cards.addAll(list);
        adapter.notifyDataSetChanged();
        updateEmptyState(cards.isEmpty(), allFailed,
                "没有找到『" + keyword + "』相关歌单\n换一个歌名、歌手或专辑试试");
    }

    /** 搜索分组卡（1 歌手组／3 专辑组；点卡进该组歌曲列） */
    private static class SearchGroupCard {
        final int type;
        final String key;
        final int count;

        SearchGroupCard(int type, String key, int count) {
            this.type = type;
            this.key = key;
            this.count = count;
        }
    }

    /** 搜索歌单卡（本地歌单；点卡进歌单详情，不挂长按改名／删单——搜索页不是歌单管理页） */
    private static class SearchPlaylistCard {
        final long id;
        final String name;
        final int count;

        SearchPlaylistCard(long id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    // ================= 列表与播放 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void showTracks(List<MusicTrack> list) {
        tracks.clear();
        tracks.addAll(list);
        cards.clear();
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
    }

    /** list 路由参数照旧 search 取值（mode=search＋keyword＋sitePlatform 空＋playlistId=-1＋title=bigTitle，可重建本页） */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "search");
        params.put("keyword", keyword == null ? "" : keyword);
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    /** 切歌只刷旧在播行＋新在播行，不全量重绑。 */
    private void refreshCurrentRow() {
        if (adapter == null) return;
        MusicTrack cur = MusicController.get().current();
        String nowId = cur == null ? null : cur.id;
        int oldPos = adapterPositionForTrack(indexOfTrackId(lastCurrentId));
        int newPos = adapterPositionForTrack(indexOfTrackId(nowId));
        if (oldPos >= 0) adapter.notifyItemChanged(oldPos);
        if (newPos >= 0 && newPos != oldPos) adapter.notifyItemChanged(newPos);
        lastCurrentId = nowId;
    }

    /** tracks 下标 → adapter 位置：本页无列表内头行，下标即位置（卡片展示时 tracks 为空、无行可刷） */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        return trackIdx;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= ⋮ 菜单 =================

    /** 下载（每次询问档时先选档，否则直接下载） */
    private void downloadTrack(MusicTrack track) {
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            final int checkedFinal = checked;
            // 选档前实测文件大小（取链＋HEAD；实测不到的档不显示大小、不估算）；探查回来页已死则不弹选档窗
            Notify.show("正在查询大小…");
            MusicDownloadStore.probeQualitySizes(track, values, sizes -> {
                if (isFinishing() || isDestroyed()) return;
                List<String> qopts = new ArrayList<>();
                for (int i = 0; i < values.length; i++) {
                    String name = MusicStore.qualityName(values[i]);
                    qopts.add(sizes[i] > 0 ? name + " · " + com.fongmi.android.tv.music.MusicCache.formatSize(sizes[i]) : name);
                }
                MusicOptionsDialog.show(this, getString(R.string.music_set_download_quality), qopts, checkedFinal,
                        qidx -> MusicDownloadStore.downloadWithQuality(track, values[qidx]));
            });
        } else {
            MusicDownloadStore.download(track);
        }
    }

    private void onMore(MusicTrack track, int position) {
        FragmentActivity act = this;
        boolean local = MusicDownloadStore.isLocal(track);
        boolean device = com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform);
        List<String> opts = new ArrayList<>();
        opts.add("播放");
        opts.add(getString(R.string.music_play_next));
        opts.add(getString(R.string.music_add_to_playlist));
        MusicLibrary.isFavorite(track, fav -> {
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            int downloadIdx = -1;
            int deleteLocalIdx = -1;
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
            } else if (device) {
                // 设备歌曲：本来就在手机里，不给"下载"
            } else {
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int delIdx = deleteLocalIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == dIdx) downloadTrack(track);
                else if (idx == delIdx) confirmDeleteLocal(track);
            });
        });
    }

    private void confirmDeleteLocal(MusicTrack track) {
        new MusicGlassDialogBuilder(this)
                .setTitle(track.displayTitle())
                .setMessage(getString(R.string.music_download_delete_confirm))
                .setPositiveButton(getString(R.string.music_download_delete), (d, w) ->
                        MusicDownloadStore.deleteLocal(track, () -> {
                            Notify.show(R.string.music_deleted);
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleFavorite(MusicTrack track, String label) {
        boolean toFav = label.equals(getString(R.string.music_favorite));
        MusicLibrary.setFavorite(track, toFav, () ->
                Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
    }

    private void pickPlaylist(MusicTrack track) {
        MusicLibrary.playlists(list -> {
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, p ->
                            MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                    Notify.show(added ? R.string.music_added : R.string.music_already_in))));
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        // 照 GZ CreatePlaylistDialog：红图标＋标题＋副标题＋圆角输入框＋大创建钮
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, dp(8), 0, dp(8));
        TextView icon = new TextView(this);
        icon.setText("≡+");
        icon.setTextSize(22);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(android.view.Gravity.CENTER);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setColor(0xFFFA243C);
        iconBg.setCornerRadius(dp(16));
        icon.setBackground(iconBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        iconLp.bottomMargin = dp(12);
        root.addView(icon, iconLp);
        TextView title = new TextView(this);
        title.setText(R.string.music_new_playlist);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF1C1C1E);
        title.setGravity(android.view.Gravity.CENTER);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("给自建歌单起个名字");
        sub.setTextSize(12);
        sub.setTextColor(0xFF8E8E93);
        sub.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(16);
        root.addView(sub, subLp);
        EditText et = new EditText(this);
        et.setHint("请输入歌单名称");
        et.setTextColor(0xFF1C1C1E);
        et.setHintTextColor(0xFF8E8E93);
        et.setSingleLine(true);
        et.setTextSize(15);
        et.setBackgroundResource(R.drawable.home_bg_search);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView create = new TextView(this);
        create.setText("创建");
        create.setTextSize(16);
        create.setTextColor(0xFFFFFFFF);
        create.setGravity(android.view.Gravity.CENTER);
        GradientDrawable createBg = new GradientDrawable();
        createBg.setCornerRadius(dp(24));
        create.setBackground(createBg);
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        createLp.topMargin = dp(16);
        root.addView(create, createLp);
        TextView cancel = new TextView(this);
        cancel.setText(android.R.string.cancel);
        cancel.setTextSize(14);
        cancel.setTextColor(0xFF8E8E93);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, dp(14), 0, 0);
        root.addView(cancel);
        final android.app.Dialog dialog = new MusicGlassDialogBuilder(this)
                .setView(root)
                .setCancelable(true)
                .create();
        Runnable refreshCreate = () -> {
            boolean hasName = et.getText() != null && !et.getText().toString().trim().isEmpty();
            createBg.setColor(hasName ? 0xFFFA243C : 0xFFF5A8B0);
            create.setEnabled(hasName);
        };
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshCreate.run();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        refreshCreate.run();
        create.setOnClickListener(v -> {
            String name = et.getText() == null ? "" : et.getText().toString();
            dialog.dismiss();
            cb.onResult(name);
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    // ================= 列表 =================

    /**
     * 本页专属 adapter：歌曲行（item_music_search_result）与分组／歌单卡（item_music_nav）互斥展示，
     * showTracks 清卡、分组／歌单卡清 tracks，两者从不同时非空，计数按在场的一族取。
     */
    private class SearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_CARD = 1;

        @Override
        public int getItemViewType(int position) {
            return cards.isEmpty() ? TYPE_TRACK : TYPE_CARD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_CARD) {
                return new CardHolder(inf.inflate(R.layout.item_music_nav, parent, false));
            }
            View rowView = inf.inflate(R.layout.item_music_search_result, parent, false);
            // 行框在建行时换成液态玻璃一次到位；bind 只切 tone，绝不再 setBackgroundResource
            // 把未玻璃化的白/粉资源盖回来（队列同病、已在那边治过）
            MusicGlassWire.glassify(rowView);
            return new TrackHolder(rowView);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof CardHolder) bindCard((CardHolder) h, position);
            else bindTrack((TrackHolder) h, position);
        }

        @Override
        public int getItemCount() {
            return cards.isEmpty() ? tracks.size() : cards.size();
        }

        class TrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView badge;
            final TextView artist;
            final TextView path;
            final TextView duration;
            final ImageView more;

            TrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                badge = v.findViewById(R.id.badge);
                artist = v.findViewById(R.id.artist);
                path = v.findViewById(R.id.path);
                duration = v.findViewById(R.id.duration);
                more = v.findViewById(R.id.more);
            }
        }

        class CardHolder extends RecyclerView.ViewHolder {
            final View coverBox;
            final ImageView icon;
            final ImageView mosaicSingle;
            final View mosaicGrid;
            final ImageView mosaic1;
            final ImageView mosaic2;
            final ImageView mosaic3;
            final ImageView mosaic4;
            final TextView name;
            final TextView status;

            CardHolder(View v) {
                super(v);
                coverBox = v.findViewById(R.id.coverBox);
                icon = v.findViewById(R.id.icon);
                mosaicSingle = v.findViewById(R.id.mosaicSingle);
                mosaicGrid = v.findViewById(R.id.mosaicGrid);
                mosaic1 = v.findViewById(R.id.mosaic1);
                mosaic2 = v.findViewById(R.id.mosaic2);
                mosaic3 = v.findViewById(R.id.mosaic3);
                mosaic4 = v.findViewById(R.id.mosaic4);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }

        private void bindCard(CardHolder h, int position) {
            // 回收行先复位（图标显、马赛克位藏），防旧图残留到别的卡。
            h.icon.setVisibility(View.VISIBLE);
            h.mosaicSingle.setVisibility(View.GONE);
            h.mosaicGrid.setVisibility(View.GONE);
            h.coverBox.setBackgroundResource(0);
            h.coverBox.setClipToOutline(false);
            Object o = cards.get(position);
            if (o instanceof SearchGroupCard) {
                // 搜索分组卡（点卡进该组歌曲列；退出钻取走重新点当前页签）
                SearchGroupCard g = (SearchGroupCard) o;
                h.name.setText(g.key);
                h.status.setText(g.count + " 首");
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(g.type == 1 ? 0xFF22C55E : 0xFF3B82F6));
                h.itemView.setOnClickListener(v -> {
                    searchGroupType = g.type;
                    searchGroupKey = g.key;
                    showSearchResults();
                });
            } else if (o instanceof SearchPlaylistCard) {
                // 搜索歌单卡（点卡进歌单详情）
                SearchPlaylistCard g = (SearchPlaylistCard) o;
                h.name.setText(g.name);
                h.status.setText(g.count + " 首");
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(0xFF3B82F6));
                h.itemView.setOnClickListener(v -> MusicPlaylistActivity.startSheet(MusicSearchActivity.this, g.id, g.name));
            }
            h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }

        private GradientDrawable circle(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            return d;
        }

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 在播行红名＋玻璃挂红淡染；普通行白名清 tone（与播放列表同一口径）
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFFF1F4F5);
            if (h.itemView.getBackground() instanceof MusicFrostDrawable) {
                MusicFrostDrawable rowGlass = (MusicFrostDrawable) h.itemView.getBackground();
                if (isCurrent) rowGlass.setToneColor(0xFFFA243C, 0.14f);
                else rowGlass.setToneColor(0, 0f);
            }
            if (t.qualityTag != null && !t.qualityTag.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(t.qualityTag);
                h.badge.setTextSize(11);
                int p6 = dp(6);
                int p2 = dp(2);
                h.badge.setPadding(p6, p2, p6, p2);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            // 第二行统一拼“歌手 · 专辑 · 来源”（当前行加在播前缀）：来源按桶身份反查
            // （见 searchSiteNameForTrack）；行高由封面定、第二行单行省略，补字不改行高、不跳字。
            StringBuilder second = new StringBuilder();
            if (isCurrent) second.append(MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
            second.append(artistDisplay(t.displayArtist()));
            if (t.album != null && !t.album.trim().isEmpty()) second.append(" · ").append(t.album.trim());
            String src = searchSiteNameForTrack(t);
            if (!src.isEmpty()) second.append(" · ").append(src);
            h.artist.setText(second.toString());
            if (h.path != null) {
                h.path.setVisibility(View.GONE);
                h.path.setText("");
            }
            h.duration.setText(fmt(t.duration));
            if (t.coverPending && (t.cover == null || t.cover.isEmpty())) {
                // 封面后补在途（酷狗／115）：留玻璃空位——清图、透明、不上素图、不上糊图；
                // 图到了回填回调重绑本行、走下面的淡入支。
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(null);
                h.cover.setBackgroundResource(0);
            } else if (t.cover == null || t.cover.isEmpty()) {
                // 没封面地址才上素图（素灰底＋歌名排字）；有地址的一律走加载、失败保持透明。
                // 列表是回收行，先清掉上一行可能在途的加载请求，防旧封面迟到盖掉素图
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else if (t.coverBackfilled) {
                // 后补图首次到行：只淡入一次（清标记，之后重绑/回收重绑都走直出不闪）
                t.coverBackfilled = false;
                loadCoverRoundedFade(t.displayTitle(), t.cover, h.cover);
            } else {
                // 本地已有缓存/首屏直出的封面：直接出，不走空位、不淡入、不闪
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
            // 回收行复位：多选圆圈换回 ⋮ 图标
            h.more.setImageResource(R.drawable.ic_music_more);
            int p12 = dp(12);
            h.more.setPadding(p12, p12, p12, p12);
            h.more.setOnClickListener(v -> onMore(t, position));
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        /** 指定圆角加载（ImgUtil.loadRounded 写死 8dp；本页共用行走 10dp helper，口径照旧） */
        private void loadCoverRounded(String text, String url, ImageView view, int radiusDp) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                com.bumptech.glide.Glide.with(view).clear(view);
                com.bumptech.glide.Glide.with(view).load(com.fongmi.android.tv.utils.ImgUtil.getUrl(url))
                        // 封面占位定案（扫描 M22）：有地址永远透明——加载中/失败都不许变素图，
                        // 只有真没地址才上素图（那条路在绑定处直设 CoverTextDrawable，不走本 helper）
                        .placeholder(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        .error(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                                new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(radiusDp)))
                        .into(view);
            } catch (Throwable ignored) {
            }
        }

        /** 后补封面淡入一次：与直出同口径（透明占位/失败透明），只多交叉淡入 */
        private void loadCoverRoundedFade(String text, String url, ImageView view) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                com.bumptech.glide.Glide.with(view).clear(view);
                com.bumptech.glide.Glide.with(view).load(com.fongmi.android.tv.utils.ImgUtil.getUrl(url))
                        .placeholder(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        .error(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(220))
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                                new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(10)))
                        .into(view);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 源横排 chips：chips 是本 App 自己的可搜源、与分桶同序；选中态红字＋红淡玻璃底 */
    private class ChipAdapter extends RecyclerView.Adapter<ChipAdapter.ChipHolder> {

        @NonNull
        @Override
        public ChipHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_search_chip, parent, false);
            MusicGlassWire.glassify(v);
            return new ChipHolder((TextView) v);
        }

        @Override
        public void onBindViewHolder(@NonNull ChipHolder h, int position) {
            MusicSite site = searchSitesSnap.get(position);
            boolean sel = position == selectedChip;
            h.chip.setText(site == null || site.name == null ? "" : site.name);
            // 选中＝样张口径的带色玻璃（青 tone）＋白字；bind 只切 tone 不换资源（防粉块盖回）
            if (h.chip.getBackground() instanceof MusicFrostDrawable) {
                MusicFrostDrawable chipGlass = (MusicFrostDrawable) h.chip.getBackground();
                if (sel) chipGlass.setToneColor(0xFF305F67, 0.55f);
                else chipGlass.setToneColor(0, 0f);
            }
            h.chip.setTextColor(sel ? 0xFFF1F4F5 : 0x99FFFFFF);
            h.chip.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            h.chip.setOnClickListener(v -> selectSource(h.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return searchSitesSnap.size();
        }

        class ChipHolder extends RecyclerView.ViewHolder {
            final TextView chip;

            ChipHolder(TextView v) {
                super(v);
                chip = v;
            }
        }
    }
}
