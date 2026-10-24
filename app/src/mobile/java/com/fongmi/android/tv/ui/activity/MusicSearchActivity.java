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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.ui.widget.FlowLayout;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐搜索独立页（拆页 p10）：顶栏＋搜索框行＋5 项分类（单曲／歌手／歌单／专辑／歌词）
 * ＋多源并发搜索（按源分桶、选源过滤、组钻取）＋无词落地页（最近搜索／热门搜索／猜你想搜）；
 * 行播放走 MusicController，队列 route=list、参数 mode=search＋keyword，
 * 队列面板来源跳转落回本页。
 * 旧页大标题行在 search 下恒隐藏、本页不带；无返回箭头，走系统返回／搜索框行「取消」。
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
    private TextView tvTitle;
    private View searchBar;
    private EditText etKeyword;
    private TextView searchCancel;
    private RecyclerView tabRow;
    private TextView searchCountBar;
    private TextView searchGroupBar;
    private View toolbar;
    private TextView btnSourceFilter;
    private ImageView btnSort;
    private TextView tvSortCurrent;
    private ScrollView searchLanding;
    private View recentHeader;
    private FlowLayout recentFlow;
    private TextView tvRecentClear;
    private TextView tvRecentMore;
    private View hotHeader;
    private LinearLayout hotRows;
    private TextView guessHeader;
    private LinearLayout guessRows;
    private RecyclerView recycler;
    private TextView empty;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<Object> cards = new ArrayList<>();
    private SearchAdapter adapter;
    private TabAdapter tabAdapter;
    private String keyword = "";
    private String bigTitle = "";

    // 搜索模式：5 项分类（单曲／歌手／歌单／专辑／歌词，GZ SearchTab 枚举顺序）＋合并结果
    private final List<String> searchCatNames = java.util.Arrays.asList("单曲", "歌手", "歌单", "专辑", "歌词");
    private final List<MusicTrack> allResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger searchTotalSites = new AtomicInteger(0);
    private final AtomicInteger searchFailedSites = new AtomicInteger(0);
    private int selectedCat = 0;
    private int searchSeq = 0;
    /** 搜索组内钻取：-1 不钻取，1 歌手组，3 专辑组（与分类下标对齐） */
    private int searchGroupType = -1;
    private String searchGroupKey = "";
    /** 搜索落地页数据：热门搜索（首榜前 10 首，null＝未拉过）与猜你想搜（收藏／历史歌手去重） */
    private List<MusicTrack> searchHotTerms;
    private boolean searchRecentExpanded = false;
    /** 第三波 A2：搜索按源分桶（键＝MusicSite.srcUrl，唯一键；不跨源去重，桶内保插件返回顺序）。
     * 快照 searchSitesSnap 保 sites 顺序；selectedSource＝null 表“全部”视图（allResults 保留不动），
     * 非空时存分桶键 searchBucketKey(site, index)（srcUrl 为空的源走 "site:"+index，不是裸 srcUrl），
     * 与分桶、取池、选源、标签四处同键，否则空 srcUrl 源的选源取池对不上桶。 */
    private final LinkedHashMap<String, List<MusicTrack>> searchBySite = new LinkedHashMap<>();
    private final List<MusicSite> searchSitesSnap = new ArrayList<>();
    private String selectedSource = null;
    private boolean searchAllReady = false;

    private String lastCurrentId;
    private android.os.Parcelable pendingListState;

    /** 通用排序第二段升降序弹层的 post（页销毁取消，见 onDestroy） */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

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
        // X-08（批 5 F2）：重建恢复——已输入与选择态从 savedInstanceState 回填（字段在加载之前就位，
        // 恢复路径与正常进页同一条加载链）；滚动位置存布局管理器状态、数据到位后在 onResume 一次性回滚。
        if (savedInstanceState != null) {
            String savedKw = savedInstanceState.getString("ml_keyword");
            if (savedKw != null) keyword = savedKw;
            selectedCat = savedInstanceState.getInt("ml_selectedCat", selectedCat);
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        bigTitle = getString(R.string.music_search);

        tvTitle = findViewById(R.id.tvTitle);
        searchBar = findViewById(R.id.searchBar);
        etKeyword = findViewById(R.id.etKeyword);
        searchCancel = findViewById(R.id.searchCancel);
        tabRow = findViewById(R.id.tabRow);
        searchCountBar = findViewById(R.id.searchCountBar);
        searchGroupBar = findViewById(R.id.searchGroupBar);
        toolbar = findViewById(R.id.toolbar);
        btnSourceFilter = findViewById(R.id.btnSourceFilter);
        btnSort = findViewById(R.id.btnSort);
        tvSortCurrent = findViewById(R.id.tvSortCurrent);
        searchLanding = findViewById(R.id.searchLanding);
        recentHeader = findViewById(R.id.recentHeader);
        recentFlow = findViewById(R.id.recentFlow);
        tvRecentClear = findViewById(R.id.tvRecentClear);
        tvRecentMore = findViewById(R.id.tvRecentMore);
        hotHeader = findViewById(R.id.hotHeader);
        hotRows = findViewById(R.id.hotRows);
        guessHeader = findViewById(R.id.guessHeader);
        guessRows = findViewById(R.id.guessRows);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        empty.setOnClickListener(null);

        tvTitle.setText(R.string.music_search);
        // 顶栏恒显；搜索页搜索框行恒显＋「取消」恒显；顶栏搜索钮在搜索页恒隐藏（旧 load() 终态）
        searchBar.setVisibility(View.VISIBLE);
        if (searchCancel != null) searchCancel.setVisibility(View.VISIBLE);
        View btnSearch = findViewById(R.id.btnSearch);
        if (btnSearch != null) btnSearch.setVisibility(View.GONE);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new SearchAdapter());
        tabRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        tabRow.setAdapter(tabAdapter = new TabAdapter());
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> shufflePlay());
        // 第三波 A2/R3：源选择器（搜索模式工具条 pill）＋通用“播放全部”顺序入口
        findViewById(R.id.btnPlaySeq).setOnClickListener(v -> playAllSequential());
        btnSourceFilter.setOnClickListener(v -> showSourceDialog());
        btnSort.setOnClickListener(v -> {
            if (selectedCat != 0 && searchGroupType < 0) {
                // 第 5 项：歌手／歌单／专辑分类是分组卡，排序只对歌曲列有意义，分组卡不支持排序
                Notify.show("分组不支持排序");
            } else showGenericSortDialog();
        });
        // 批 5 F1：搜索框行右「取消」＝直接退出搜索页（系统返回同路，不放返回箭头）。
        if (searchCancel != null) searchCancel.setOnClickListener(v -> finish());
        // 批 5 F1 空输入核查：搜索只由键盘搜索键／历史筹／热搜行显式触发，输入框无任何
        // TextWatcher 自动搜；此处 watcher 只做反向守卫——文字被清空时回落地页清结果，
        // 杜绝「空输入自动搜／旧结果残留」。
        etKeyword.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence cs, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e == null ? "" : e.toString().trim();
                if (q.isEmpty() && searchLanding.getVisibility() != View.VISIBLE) showSearchLanding();
            }
        });
        // 第二波：搜索框右侧「搜索」钮按瓜子去掉（瓜子 SearchTextField 无此钮，框内是平台下拉——下拉属搜索方式不抄）；
        // 提交走键盘搜索键（IME_ACTION_SEARCH／回车，见下），按钮移除不影响提交链。
        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        MusicController.get().addListener(controllerListener);
        // 进页分界（旧 load() 的 search 支）：有词即搜、无词落落地页
        if (!keyword.isEmpty()) {
            etKeyword.setText(keyword);
            doSearch();
            return;
        }
        showSearchLanding();
    }

    /** X-08（批 5 F2）：切源／旋转／主题重建回来不丢已输入与滚动位置——输入与选择态走字段回填（见 initView），
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
        // X-08 滚动回滚：等本帧布局后一次性应用（数据异步未到时布局管理器会钳到现有行数，不崩；应用后即清，不反复拽）
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
        // 旧口径：搜索回页不重拉（旧 onResume 的 switch 无 search 支）
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        // 复核必-10：第二段升降序弹层的 post 必须随页销毁取消，不许页死后还弹。
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================= 搜索 =================

    /**
     * 搜索：GZ 5 项分类（单曲／歌手／歌单／专辑／歌词，SearchTab 枚举顺序）；
     * 全站并发搜、结果合并后按分类展示；切分类只换显示、不重搜。
     * 某个源报错只影响它自己，其它源照常出结果；全部失败才显「搜索失败，请重试」。
     */
    private void doSearch() {
        keyword = etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            showSearchLanding();
            return;
        }
        bigTitle = keyword;
        MusicStore.pushRecentSearch(keyword);
        searchRecentExpanded = false;
        final int seq = ++searchSeq;
        final String kw = keyword;
        allResults.clear();
        synchronized (searchBySite) {
            searchBySite.clear();
        }
        searchSitesSnap.clear();
        selectedSource = null;
        searchAllReady = false;
        refreshSourceFilter();
        searchTotalSites.set(0);
        searchFailedSites.set(0);
        selectedCat = 0;
        searchGroupType = -1;
        searchGroupKey = "";
        tabAdapter.notifyDataSetChanged();
        searchLanding.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        // 第三波 A1：计数条／组筛选条恒占位（无内容写空字），切分类不再改布局高度
        searchCountBar.setVisibility(View.VISIBLE);
        searchCountBar.setText("");
        searchGroupBar.setVisibility(View.VISIBLE);
        searchGroupBar.setText("");
        empty.setMinHeight(dp(160));
        tabRow.setVisibility(View.VISIBLE);
        tvTitle.setText(R.string.music_search);
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
                        showCategoryResults();
                    }
                });
                return;
            }
            final AtomicInteger pending = new AtomicInteger(tabs.size());
            for (int si = 0; si < tabs.size(); si++) {
                final MusicSite site = tabs.get(si);
                final String bucketKey = searchBucketKey(site, si);
                Task.execute(() -> {
                    try {
                        List<MusicTrack> r = MusicRepository.searchSite(site, kw);
                        if (seq == searchSeq && r != null) {
                            allResults.addAll(r);
                            synchronized (searchBySite) {
                                List<MusicTrack> bucket = searchBySite.get(bucketKey);
                                if (bucket == null) {
                                    bucket = Collections.synchronizedList(new ArrayList<>());
                                    searchBySite.put(bucketKey, bucket);
                                }
                                bucket.addAll(r);
                            }
                        }
                    } catch (Throwable t) {
                        if (seq == searchSeq) searchFailedSites.incrementAndGet();
                    } finally {
                        // 旧搜索的迟到回调不许动新搜索的状态
                        if (seq == searchSeq && pending.decrementAndGet() == 0) {
                            runOnUiThread(() -> {
                                if (seq == searchSeq) {
                                    searchAllReady = true;
                                    showCategoryResults();
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void selectCategory(int idx) {
        if (idx < 0 || idx >= searchCatNames.size()) return;
        selectedCat = idx;
        searchGroupType = -1;
        searchGroupKey = "";
        tabAdapter.notifyDataSetChanged();
        tabRow.smoothScrollToPosition(idx);
        showCategoryResults();
    }

    /** 第三波 A2：当前搜索池——“全部”＝allResults；选源后＝该源桶（过滤先于分组，不跨源去重）。 */
    private List<MusicTrack> currentSearchPool() {
        if (selectedSource == null) return new ArrayList<>(allResults);
        List<MusicTrack> bucket;
        synchronized (searchBySite) {
            bucket = searchBySite.get(selectedSource);
        }
        return bucket == null ? new ArrayList<>() : new ArrayList<>(bucket);
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

    /** 第三波 A2：源选择器显隐与文案（单曲／歌手／专辑签＋非钻取时才显；歌单／歌词签下隐藏）。
     * 全部源到齐（searchAllReady）前禁用，计数边搜边变不许切源。 */
    private void refreshSourceFilter() {
        if (btnSourceFilter == null) return;
        boolean show = (selectedCat == 0 || selectedCat == 1 || selectedCat == 3)
                && searchGroupType < 0;
        btnSourceFilter.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        String label = "全部来源";
        if (selectedSource != null) {
            for (int i = 0; i < searchSitesSnap.size(); i++) {
                MusicSite site = searchSitesSnap.get(i);
                if (selectedSource.equals(searchBucketKey(site, i))) {
                    label = site.name == null || site.name.isEmpty() ? selectedSource : site.name;
                    break;
                }
            }
        }
        btnSourceFilter.setText(label + " ▾");
        btnSourceFilter.setEnabled(searchAllReady);
        btnSourceFilter.setAlpha(searchAllReady ? 1f : 0.5f);
    }

    private void showSourceDialog() {
        if (!searchAllReady) return;
        List<String> opts = new ArrayList<>();
        opts.add("全部来源");
        for (MusicSite site : searchSitesSnap) opts.add(site.name == null ? "" : site.name);
        int checked = 0;
        if (selectedSource != null) {
            for (int i = 0; i < searchSitesSnap.size(); i++) {
                if (selectedSource.equals(searchBucketKey(searchSitesSnap.get(i), i))) {
                    checked = i + 1;
                    break;
                }
            }
        }
        MusicOptionsDialog.show(this, "来源", opts, checked, idx -> {
            if (idx == 0) selectedSource = null;
            else if (idx - 1 < searchSitesSnap.size()) selectedSource = searchBucketKey(searchSitesSnap.get(idx - 1), idx - 1);
            searchGroupType = -1;
            searchGroupKey = "";
            showCategoryResults();
        });
    }

    /** 按当前分类把合并后的搜索结果显示到列表区；组内钻取时只显示该组歌曲 */
    private void showCategoryResults() {
        searchLanding.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        tabRow.setVisibility(View.VISIBLE);
        // 结果出来工具条才回来（落地页已隐藏，见 showSearchLanding）。
        toolbar.setVisibility(View.VISIBLE);
        tabAdapter.notifyDataSetChanged();
        refreshSourceFilter();
        List<MusicTrack> pool = currentSearchPool();
        if (searchGroupType == 1 || searchGroupType == 3) {
            // 组内歌曲列：按歌手／专辑过滤，筛选条点 × 回分类（筛选条是过滤控件、不是返回箭头）
            List<MusicTrack> g = new ArrayList<>();
            for (MusicTrack t : pool) {
                String k = searchGroupType == 1 ? artistOf(t) : albumOf(t);
                if (k.equals(searchGroupKey)) g.add(t);
            }
            searchCountBar.setVisibility(View.VISIBLE);
            searchCountBar.setText("");
            searchGroupBar.setVisibility(View.VISIBLE);
            searchGroupBar.setText((searchGroupType == 1 ? "歌手：" : "专辑：") + searchGroupKey + " ×");
            searchGroupBar.setOnClickListener(v -> {
                searchGroupType = -1;
                searchGroupKey = "";
                showCategoryResults();
            });
            showTracks(g);
            if (g.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                empty.setText("没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
            }
            return;
        }
        searchGroupBar.setVisibility(View.VISIBLE);
        searchGroupBar.setText("");
        boolean allFailed = searchTotalSites.get() > 0 && searchFailedSites.get() >= searchTotalSites.get();
        switch (selectedCat) {
            case 0: // 单曲：直接列表（计数条恒占位空字）
                searchCountBar.setVisibility(View.VISIBLE);
                searchCountBar.setText("");
                showTracks(new ArrayList<>(pool));
                if (tracks.isEmpty()) {
                    empty.setVisibility(View.VISIBLE);
                    empty.setText(allFailed ? "搜索失败，请重试"
                            : "没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
                }
                break;
            case 1: // 歌手：分组卡＋「N 位」（只在选中源桶内分组）
                showGroupCards(pool, 1, " 位", "没有找到『" + keyword + "』相关歌手", allFailed);
                break;
            case 2: // 歌单：本地歌单名匹配＋「N 个」（与源无关，源选择器已隐藏）
                showPlaylistCards(allFailed);
                break;
            case 3: // 专辑：分组卡＋「N 张」（只在选中源桶内分组）
                showGroupCards(pool, 3, " 张", "没有找到『" + keyword + "』相关专辑", allFailed);
                break;
            default: // 歌词：星辰插件无歌词全文搜入口，只显不支持态（不编假结果；源选择器已隐藏）
                tracks.clear();
                cards.clear();
                adapter.notifyDataSetChanged();
                searchCountBar.setVisibility(View.VISIBLE);
                searchCountBar.setText("");
                empty.setVisibility(View.VISIBLE);
                empty.setText("暂不支持歌词全文搜索");
                break;
        }
        refreshSourceFilter();
    }

    /** 显示用歌手：占位（空／<unknown>／unknown 类，见 LocalTrackMeta）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    private String artistOf(MusicTrack t) {
        return artistDisplay(t.artist);
    }

    /** 搜索分桶键：srcUrl 为空时按快照下标走 "site:"+index；分桶／取池／选源／标签四处同用。 */
    private static String searchBucketKey(MusicSite site, int index) {
        return site != null && site.srcUrl != null && !site.srcUrl.isEmpty() ? site.srcUrl : "site:" + index;
    }

    private String albumOf(MusicTrack t) {
        return t.album == null || t.album.isEmpty() ? "未知专辑" : t.album;
    }

    /** 歌手／专辑分组卡：按组名去重分组，点卡进该组歌曲列（入参已是选中源桶，不过滤后不再碰全量） */
    private void showGroupCards(List<MusicTrack> pool, int type, String unit, String emptyCopy, boolean allFailed) {
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
        searchCountBar.setVisibility(View.VISIBLE);
        searchCountBar.setText(groups.size() + unit);
        empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        if (cards.isEmpty()) empty.setText(allFailed ? "搜索失败，请重试" : emptyCopy + "\n换一个歌名、歌手或专辑试试");
    }

    /** 歌单分类：本地歌单名包含关键词即命中，曲目数逐个补齐后按原顺序刷 */
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
        tracks.clear();
        cards.clear();
        cards.addAll(list);
        adapter.notifyDataSetChanged();
        searchCountBar.setVisibility(View.VISIBLE);
        searchCountBar.setText(cards.size() + " 个");
        empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        if (cards.isEmpty()) empty.setText(allFailed ? "搜索失败，请重试" : "没有找到『" + keyword + "』相关歌单\n换一个歌名、歌手或专辑试试");
    }

    /** 第 5 项：搜索分组卡（1 歌手组／3 专辑组；点卡进该组歌曲列） */
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

    /** 第 5 项：搜索歌单卡（本地歌单；点卡进歌单详情，不挂长按改名／删单——搜索页不是歌单管理页） */
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

    // ================= 落地页 =================

    /** 搜索落地页：最近搜索胶囊＋热门搜索＋猜你想搜（无结果时显落地，不显空列表） */
    private void showSearchLanding() {
        searchLanding.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        tabRow.setVisibility(View.GONE);
        // 2026-10-22 搜索页重做：落地页不许再出现空的组筛选条（空卡片背景就是截图里那个 stray 圆圈）、
        // 空的计数条、结果工具条（随机播放／播放全部／来源／排序是结果才有的东西，落地页没有结果）。
        searchCountBar.setVisibility(View.GONE);
        searchGroupBar.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        if (hotHeader != null) hotHeader.setVisibility(View.GONE);
        refreshSourceFilter();
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        empty.setVisibility(View.GONE);
        findViewById(R.id.btnHotRefresh).setOnClickListener(v -> loadSearchHot());
        buildRecentChips();
        buildGuessRows();
        if (searchHotTerms == null) loadSearchHot();
        else buildHotRows();
    }

    /** 最近搜索胶囊：词 12sp、内边距 10/6dp、圆角 8dp；默认收起取前 10 条，点「更多」展开 */
    private void buildRecentChips() {
        List<String> loaded = MusicStore.loadRecentSearches();
        // 2026-10-22 搜索页重做：占位词（<unknown>／未知歌手等）不许进最近搜索胶囊。
        List<String> all = new ArrayList<>();
        for (String w : loaded) {
            if (w != null && !com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(w)) all.add(w);
        }
        recentFlow.removeAllViews();
        int show = searchRecentExpanded ? all.size() : Math.min(all.size(), 10);
        for (int i = 0; i < show; i++) {
            String w = all.get(i);
            TextView chip = new TextView(this);
            chip.setText(w);
            chip.setTextSize(12);
            chip.setTextColor(0xFF3A3A3C);
            chip.setBackgroundResource(R.drawable.bg_music_glass_chip);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setOnClickListener(v -> {
                etKeyword.setText(w);
                doSearch();
            });
            recentFlow.addView(chip);
        }
        recentHeader.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        recentFlow.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        tvRecentClear.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        tvRecentClear.setOnClickListener(v -> {
            MusicStore.clearRecentSearches();
            searchRecentExpanded = false;
            buildRecentChips();
        });
        tvRecentMore.setVisibility(all.size() > 10 ? View.VISIBLE : View.GONE);
        tvRecentMore.setText(searchRecentExpanded ? "收起" : "更多");
        tvRecentMore.setOnClickListener(v -> {
            searchRecentExpanded = !searchRecentExpanded;
            buildRecentChips();
        });
    }

    /**
     * 热门搜索：GZ 走自家服务端，星辰无对应接口——来源替代：取首榜曲目前 10 首的歌名顶上，
     * 副内容＝歌手；热度数值无来源，不编数（热度栏空着）。刷新即重拉。
     */
    private void loadSearchHot() {
        // 第三波 A1：落地页去掉“正在加载…”占位行，数据齐了 buildHotRows 一次渲染
        Task.execute(() -> {
            List<MusicTrack> hot = new ArrayList<>();
            try {
                List<MusicSite> sites = MusicRepository.sites();
                MusicChart first = null;
                for (MusicSite s : sites) {
                    List<MusicChart> cs = MusicRepository.charts(s);
                    if (cs != null && !cs.isEmpty()) {
                        first = cs.get(0);
                        break;
                    }
                }
                if (first != null) {
                    List<MusicTrack> r = MusicRepository.chartTracks(first);
                    if (r != null) hot.addAll(r.subList(0, Math.min(10, r.size())));
                }
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> result = hot;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                searchHotTerms = result;
                buildHotRows();
            });
        });
    }

    /** 热搜行（批 5 F1 换新）：左右两列排名卡，每行两格；前 3 名红字、其余灰字；点格即搜。 */
    private void buildHotRows() {
        hotRows.removeAllViews();
        // 热搜没数据时整段隐藏（不留空标题、不贴占位）。
        boolean hotEmpty = searchHotTerms == null || searchHotTerms.isEmpty();
        if (hotHeader != null) hotHeader.setVisibility(hotEmpty ? View.GONE : View.VISIBLE);
        hotRows.setVisibility(hotEmpty ? View.GONE : View.VISIBLE);
        if (hotEmpty) return;
        for (int i = 0; i < searchHotTerms.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(hotCell(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i + 1 < searchHotTerms.size()) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(12);
                row.addView(hotCell(i + 1), lp);
            } else {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = dp(12);
                row.addView(spacer, lp);
            }
            hotRows.addView(row);
        }
    }

    private View hotCell(int i) {
        MusicTrack t = searchHotTerms.get(i);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cell.setPadding(0, dp(6), 0, dp(6));
        TextView rank = new TextView(this);
        rank.setText(String.valueOf(i + 1));
        rank.setTextSize(15);
        rank.setTextColor(i < 3 ? 0xFFFA243C : 0xFF9AA7B8);
        if (i < 3) rank.setTypeface(null, android.graphics.Typeface.BOLD);
        rank.setGravity(android.view.Gravity.CENTER);
        cell.addView(rank, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView term = new TextView(this);
        term.setText(t.title == null ? "" : t.title);
        term.setTextSize(15);
        term.setTextColor(0xFF1A1A1A);
        term.setSingleLine(true);
        term.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(term);
        if (t.artist != null && !t.artist.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(t.artist);
            sub.setTextSize(12);
            sub.setTextColor(0xFF999999);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        LinearLayout.LayoutParams lpTexts = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpTexts.leftMargin = dp(8);
        cell.addView(texts, lpTexts);
        final String w = t.title == null ? "" : t.title;
        cell.setOnClickListener(v -> {
            etKeyword.setText(w);
            doSearch();
        });
        return cell;
    }

    /**
     * 猜你想搜：独立区头＋推荐词行。不是 GZ 的推荐算法——用本地真实数据：
     * 最近播放歌手在前、收藏歌手补齐，去重取前 8 个，点词即搜。
     */
    private void buildGuessRows() {
        // 第三波 A1：猜词把最近播放和收藏合并一份再一次渲染（favorites 回调必到，见 MusicLibrary）
        List<String> terms = new ArrayList<>();
        for (MusicTrack t : MusicStore.loadRecent()) addGuessArtist(terms, t.artist);
        MusicLibrary.favorites(list -> {
            if (isFinishing() || isDestroyed()) return;
            List<String> all = new ArrayList<>(terms);
            if (list != null) {
                for (MusicTrack t : list) addGuessArtist(all, t.artist);
            }
            runOnUiThread(() -> renderGuessRows(all));
        });
    }

    private void addGuessArtist(List<String> out, String artist) {
        if (artist == null || artist.isEmpty() || out.contains(artist)) return;
        // 2026-10-22 搜索页重做：占位歌手（<unknown>／未知歌手等）不许进猜词。
        if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(artist)) return;
        if (out.size() >= 8) return;
        out.add(artist);
    }

    private void renderGuessRows(List<String> terms) {
        guessRows.removeAllViews();
        guessHeader.setVisibility(terms.isEmpty() ? View.GONE : View.VISIBLE);
        // 2026-10-22 搜索页重做：猜词不再是裸文字行，改成带放大镜图标＋底分隔线的正经行。
        for (String w : terms) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_music_search);
            icon.setColorFilter(0xFF9AA7B8);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            iconLp.rightMargin = dp(10);
            row.addView(icon, iconLp);
            TextView tv = new TextView(this);
            tv.setText(w);
            tv.setTextSize(15);
            tv.setTextColor(0xFF1C1C1E);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                etKeyword.setText(w);
                doSearch();
            });
            guessRows.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x14000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.leftMargin = dp(30);
            guessRows.addView(div);
        }
    }

    // ================= 列表与播放 =================

    private void showLoading() {
        empty.setVisibility(View.VISIBLE);
        empty.setText("正在加载…");
    }

    private void showTracks(List<MusicTrack> list) {
        sortTracks(list);
        tracks.clear();
        tracks.addAll(list);
        cards.clear();
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            refreshSortLabel();
            // 搜索模式空文案由 showCategoryResults 一次写定，showTracks 只管显隐，不再事后覆盖
            empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
        if (MusicStore.isListSortDesc()) Collections.reverse(list);
    }

    /** 通用排序改对话框（默认／标题／歌手／时长＋升降）；第二段弹层 post 随页销毁取消（见 onDestroy） */
    private void showGenericSortDialog() {
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        MusicOptionsDialog.show(this, "排序", java.util.Arrays.asList(keys), MusicStore.getListSort(), idx -> {
            MusicStore.setListSort(idx);
            refreshSortLabel();
            showTracks(new ArrayList<>(tracks));
            sortDirRunnable = () -> {
                if (isFinishing() || isDestroyed()) return;
                MusicOptionsDialog.show(this, "升序 / 降序",
                        java.util.Arrays.asList("升序", "降序"), MusicStore.isListSortDesc() ? 1 : 0, dir -> {
                            MusicStore.setListSortDesc(dir == 1);
                            refreshSortLabel();
                            showTracks(new ArrayList<>(tracks));
                        });
            };
            sortDialogHandler.post(sortDirRunnable);
        });
    }

    /** 工具条旁常显当前通用排序档。 */
    private void refreshSortLabel() {
        if (tvSortCurrent == null) return;
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        int s = MusicStore.getListSort();
        String label = keys[s < 0 || s >= keys.length ? 0 : s] + (MusicStore.isListSortDesc() ? " ↓" : " ↑");
        tvSortCurrent.setText(label);
        tvSortCurrent.setVisibility(View.VISIBLE);
        btnSort.setContentDescription("排序：" + label);
    }

    /** 通用“播放全部”——顺序入队从头播（与“随机播放”分开）。
     * 歌曲行在场时走 tracks；搜索分组卡下无行时走当前过滤后的搜索池；其余无行时不播。 */
    private void playAllSequential() {
        if (!tracks.isEmpty()) {
            playTrack(0);
            return;
        }
        List<MusicTrack> pool = currentSearchPool();
        if (pool.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        MusicController.get().play(new ArrayList<>(pool), 0, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            // 第三波 A2：搜索分组卡下没有歌曲行，随机播按当前过滤后的池（选源后只播该源）打乱播
            List<MusicTrack> pool = currentSearchPool();
            if (pool.isEmpty()) {
                Notify.show(R.string.music_no_track);
                return;
            }
            List<MusicTrack> shuffled = new ArrayList<>(pool);
            Collections.shuffle(shuffled);
            MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
            MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
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
        new MaterialAlertDialogBuilder(this)
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
        final android.app.Dialog dialog = new MaterialAlertDialogBuilder(this)
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
     * 本页专属 adapter：歌曲行与分组／歌单卡互斥展示（showTracks 清卡、分组／歌单卡清 tracks，
     * 两者从不同时非空）——计数按在场的一族取，不沿用旧共享 adapter 只认 sites 的卡片计数口径
     * （旧口径下搜索卡在壳里计不到数、bindCard 不可达；本页按规格让卡片可达，见拆页报告）。
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
            return new TrackHolder(inf.inflate(R.layout.item_music_track, parent, false));
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
                // 第 5 项：搜索分组卡（点卡进该组歌曲列，筛选条点 × 回分类）
                SearchGroupCard g = (SearchGroupCard) o;
                h.name.setText(g.key);
                h.status.setText(g.count + " 首");
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(g.type == 1 ? 0xFF22C55E : 0xFF3B82F6));
                h.itemView.setOnClickListener(v -> {
                    searchGroupType = g.type;
                    searchGroupKey = g.key;
                    showCategoryResults();
                });
            } else if (o instanceof SearchPlaylistCard) {
                // 第 5 项：搜索歌单卡（点卡进歌单详情）
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
            // 共用行口径（批 5 F1/F2）：歌名 15sp 粗 #1A1A1A，在播行红名
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
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
            // 通用行第二行统一拼“歌手 · 专辑”（有专辑才拼）；当前行加在播前缀。
            // 搜索页重做（结果混一起）：默认「全部来源」是多源合并池，行内补来源名（按桶身份，见 searchSiteNameForTrack）；
            // 行高由封面定、第二行单行省略，补字不改行高、不跳字。
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
            if (t.cover == null || t.cover.isEmpty()) {
                // 没封面地址才上素图（素灰底＋歌名排字）；有地址的一律走加载、失败保持透明。
                // 列表是回收行，先清掉上一行可能在途的加载请求，防旧封面迟到盖掉素图
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
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
            float r = radiusDp * view.getResources().getDisplayMetrics().density;
            try {
                com.bumptech.glide.Glide.with(view).clear(view);
                com.bumptech.glide.Glide.with(view).load(ImgUtil.getUrl(url))
                        .placeholder(new com.fongmi.android.tv.utils.CoverTextDrawable(text, false, r))
                        .error(new com.fongmi.android.tv.utils.CoverTextDrawable(text, false, r))
                        .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                                new com.bumptech.glide.load.resource.bitmap.RoundedCorners(dp(radiusDp)))
                        .into(view);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 搜索页分类横排：5 项固定分类，选中红字＋圆角 20dp 红指示条，未选中灰字 */
    private class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabHolder> {

        @NonNull
        @Override
        public TabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_search_tab, parent, false);
            return new TabHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull TabHolder h, int position) {
            boolean sel = position == selectedCat;
            h.name.setText(searchCatNames.get(position));
            h.name.setTextColor(sel ? 0xFFFA243C : 0xFF9AA7B8);
            h.bar.setBackgroundResource(sel ? R.drawable.bg_tab_indicator : 0);
            h.itemView.setOnClickListener(v -> selectCategory(h.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return searchCatNames.size();
        }

        class TabHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final View bar;

            TabHolder(View v) {
                super(v);
                name = v.findViewById(R.id.tabName);
                bar = v.findViewById(R.id.tabBar);
            }
        }
    }
}
