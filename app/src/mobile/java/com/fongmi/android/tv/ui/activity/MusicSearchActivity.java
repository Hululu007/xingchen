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
import com.fongmi.android.tv.ui.music.MusicGlassDrawable;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 音乐搜索独立页（2026-10-25 用户定案）：构成只有标题「搜索」＋搜索框行（＋取消）
 * ＋搜索历史（仅初始态有存量时摆）＋结果列表＋状态层。没有分类页签、没有源
 * 横排 chips、没有工具行／组筛选条／落地页热门与猜你想搜（定案摘除，不许回潮）。
 * 搜索执行是多源并发、按源分桶；展示按源分段（2026-10-26 他亲点）：同源一段、
 * 段序＝源快照序（旧源横排 chips 的次序）、段内＝该源回包序，续页新结果归入
 * 各自源的段里续排。结果行：封面／歌名＋音质标／「歌手 · 专辑 · 来源」／时长／⋮。
 * 滑到底自动续下一页（多桶并行推进，页码透传到源，源的末页信号判桶终局）。
 * 部分源失败与真无果分开呈现（失败桶有标记，见 updateEmptyState 与完成提示）。
 * 行播放走 MusicController，队列 route=list、参数 mode=search＋keyword，队列面板来源跳转落回本页。
 * 无返回箭头，走系统返回／搜索框行「取消」。
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
    private RecyclerView recycler;
    private TextView empty;
    private View historyBox;
    private com.fongmi.android.tv.ui.widget.FlowLayout historyFlow;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private SearchAdapter adapter;
    private String keyword = "";
    private String bigTitle = "";

    // 搜索页构成定案（2026-10-25）：无分类页签、无源 chips——恒走单曲＋全源分段展示。
    private final List<MusicTrack> allResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger searchTotalSites = new AtomicInteger(0);
    private final AtomicInteger searchFailedSites = new AtomicInteger(0);
    private int searchSeq = 0;
    /** 搜索按源分桶（键＝searchBucketKey：srcUrl 非空用 srcUrl、空走 "site:"+index；
     * 快照 searchSitesSnap 保 sites 顺序）。分桶只服务两件事：行来源名按桶反查、
     * 续页按桶推进；展示恒为全源聚合（见 currentSearchPool），不再有选中源概念。
     * selectedSource 仅作历史字段保留（恒为第一个源的桶键，无消费点依赖其语义）。 */
    private final LinkedHashMap<String, List<MusicTrack>> searchBySite = new LinkedHashMap<>();
    private final List<MusicSite> searchSitesSnap = new ArrayList<>();
    private String selectedSource = null;
    private boolean searchAllReady = false;
    /** 分页态（按桶键记；三表都只在 synchronized(searchBySite) 内读写）：
     * bucketPages＝已到齐的最大页码、bucketEnded＝没有更多（空页／全重复／失败）、
     * bucketLoading＝该桶有请求在途（第一页或续页）。旧实现全链写死第一页、没有这三表。 */
    private final java.util.Map<String, Integer> bucketPages = new java.util.HashMap<>();
    private final java.util.Set<String> bucketEnded = new java.util.HashSet<>();
    private final java.util.Set<String> bucketLoading = new java.util.HashSet<>();
    /** 失败桶（第一页抛错／无源可路由／续页失败）：与「真无结果」分开呈现的凭据，
     * 只记桶键、与 bucketEnded 并行（失败桶同时判终局防反复重试，性质靠本表区分）。 */
    private final java.util.Set<String> bucketFailed = new java.util.HashSet<>();
    /** 续页终局提示只报一次（每次搜索）：滚底反复触发不许反复弹 */
    private boolean loadMoreEndNotified = false;
    /** 部分源失败的完成提示只报一次（每次搜索） */
    private boolean partialFailNotified = false;

    /** 酷狗封面后补回调（源层回填同一批曲目对象后通知）：只重绑被回填的那几行 */
    private final com.fongmi.android.tv.api.music.LxPlatformSearch.KgCoverListener kgCoverListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));
    /** 咪咕时长后补回调：同上，只重绑被回填时长的行 */
    private final com.fongmi.android.tv.api.music.LxPlatformSearch.MgDurationListener mgDurationListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));
    /** 115 标签后补回调（内嵌封面／时长／标签歌名歌手回填）：同上，只重绑被回填的行 */
    private final com.fongmi.android.tv.cloud.drive115.Pan115Tags.FillListener panTagsListener =
            filled -> runOnUiThread(() -> rebindTracks(filled));

    /** 后补回填后按曲目对象身份找当前展示下标、逐行重绑（不在当前分段池里的忽略） */
    private void rebindTracks(List<MusicTrack> filled) {
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
            // 页签已摘除：旧存档里的页签值一律不回填（防升级后残值把展示带偏）
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        bigTitle = getString(R.string.music_search);

        etKeyword = findViewById(R.id.etKeyword);
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
        // 滑到底部附近自动续下一页（逐桶推进，见 tryLoadMore）
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
        // 源层后补回填（酷狗封面／咪咕时长／115 标签）通知：本页在前台时才接，销毁注销（onDestroy）
        com.fongmi.android.tv.api.music.LxPlatformSearch.setKgCoverListener(kgCoverListener);
        com.fongmi.android.tv.api.music.LxPlatformSearch.setMgDurationListener(mgDurationListener);
        com.fongmi.android.tv.cloud.drive115.Pan115Tags.addFillListener(panTagsListener);

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
        MusicController.get().addListener(controllerListener);
        // 进页分界：有词即搜、无词停在初始态（标题＋搜索框＋有历史时摆历史记录）
        if (!keyword.isEmpty()) {
            etKeyword.setText(keyword);
            doSearch();
        } else {
            refreshHistory();
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
            // 扫描 T3：历史胶囊是代码动态建的、不在布局树里，glassifyTree 够不着——
            // 此前只有原始半透底＋深字（深字压玻璃底看不清）；建后单独过玻璃翻字。
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

    // ================= 搜索 =================

    /**
     * 搜索：多源并发搜、各源结果按源分桶（桶只供行来源名反查与按桶续页），
     * 展示恒为全源聚合的单曲列表（定案构成：无页签、无源 chips）。
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
        if (historyBox != null) XcFadeTransition.fadeOutThenGone(historyBox);
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
        }
        searchSitesSnap.clear();
        selectedSource = null;
        searchAllReady = false;
        searchTotalSites.set(0);
        searchFailedSites.set(0);
        loadMoreEndNotified = false;
        partialFailNotified = false;
        tracks.clear();
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
            // 快照到位先记第一个源的桶键（历史字段，无展示语义；展示走全源聚合）
            runOnUiThread(() -> {
                if (seq != searchSeq) return;
                selectedSource = searchBucketKey(searchSitesSnap.get(0), 0);
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
                        // 带末页信号版：源明示末页（FreeJS isEnd 等）第一页就判桶终局，
                        // 不再多发一页靠回空兜底；无源可路由在此抛错走失败桶
                        com.fongmi.android.tv.music.MusicSearchPage page =
                                MusicRepository.searchSitePage(site, kw, 1);
                        List<MusicTrack> r = page.tracks;
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
                                if (r.isEmpty() || page.end) bucketEnded.add(bucketKey);
                            }
                        }
                    } catch (Throwable t) {
                        // 失败留痕：此前只计数不记日志，某家源整家消失时无从定罪
                        // （2026-10-26「只剩 QQ」一案）；真因细节在仓库/源层同 tag 日志
                        SpiderDebug.log("music", "search fanout fail site=%s bucket=%s %s",
                                site.name, bucketKey, t.getClass().getSimpleName());
                        if (seq == searchSeq) {
                            searchFailedSites.incrementAndGet();
                            synchronized (searchBySite) {
                                bucketFailed.add(bucketKey);
                            }
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
                            // 全源分段展示：任一源到齐就刷一次（结果流入式变多），
                            // 最后一源到齐置 ready 后总刷一次
                            runOnUiThread(() -> {
                                if (seq == searchSeq) {
                                    if (last) {
                                        searchAllReady = true;
                                        // 部分失败要让用户看见（与全失败/真无果分开）：
                                        // 有结果时完成点补一条提示，无结果时状态层文案区分
                                        if (!partialFailNotified
                                                && searchFailedSites.get() > 0
                                                && searchFailedSites.get() < searchTotalSites.get()
                                                && !currentSearchPool().isEmpty()) {
                                            partialFailNotified = true;
                                            Notify.show("部分来源搜索失败，已显示其余来源结果");
                                        }
                                    }
                                    showSearchResults();
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    /**
     * 续下一页：多桶并行推进（F1 低）——旧实现一次滚底只续第一个未终局桶一页，
     * 多源要反复滚底、且全程无任何状态提示。现一次滚底把所有未终局、不在途的桶
     * 各续一页（各自独立任务、独立收口），新结果按 track id 去重后并入各自的桶
     * （分段展示下自然归入本源段内续排）；源明示末页（end 信号）／空页／全重复
     * 判终局。状态提示走既有 Notify 口径：任一桶续页失败提示「部分来源加载失败」
     * （每次搜索至多一次）、全部桶终局后首次滚底提示「没有更多了」（每次搜索至多一次）。
     */
    private void tryLoadMore() {
        if (keyword == null || keyword.isEmpty()) return;
        final int seq = searchSeq;
        final String kw = keyword;
        List<String> targets = new ArrayList<>();
        synchronized (searchBySite) {
            for (String k : searchBySite.keySet()) {
                if (bucketEnded.contains(k) || bucketLoading.contains(k)) continue;
                if (!bucketPages.containsKey(k)) continue;
                targets.add(k);
            }
        }
        if (targets.isEmpty()) {
            // 无桶可续：搜索已到齐且每桶都终局时，首次滚底给终局提示（与云盘页同文案口径）
            synchronized (searchBySite) {
                if (searchAllReady && !searchBySite.isEmpty()
                        && bucketEnded.containsAll(searchBySite.keySet())
                        && bucketLoading.isEmpty() && !loadMoreEndNotified) {
                    loadMoreEndNotified = true;
                    Notify.show("没有更多了");
                }
            }
            return;
        }
        for (String bk : targets) loadMoreBucket(bk, seq, kw);
    }

    /** 单桶续一页：独立任务、独立收口（一个桶失败不拖其它桶，失败记桶＋判终局） */
    private void loadMoreBucket(final String bucketKey, final int seq, final String kw) {
        final MusicSite site;
        final int nextPage;
        synchronized (searchBySite) {
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
            com.fongmi.android.tv.music.MusicSearchPage result = null;
            boolean failed = false;
            try {
                result = MusicRepository.searchSitePage(site, kw, nextPage);
            } catch (Throwable t) {
                failed = true;
                SpiderDebug.log("music", "search loadmore fail bucket=%s page=%d %s",
                        bucketKey, nextPage, t.getClass().getSimpleName());
            }
            final com.fongmi.android.tv.music.MusicSearchPage pageResult = result;
            final boolean pageFailed = failed;
            runOnUiThread(() -> {
                int added = 0;
                boolean notifyFail = false;
                synchronized (searchBySite) {
                    bucketLoading.remove(bucketKey);
                    if (seq != searchSeq) return;
                    List<MusicTrack> page = pageResult == null ? null : pageResult.tracks;
                    if (page != null && !page.isEmpty()) {
                        List<MusicTrack> bucket = searchBySite.get(bucketKey);
                        if (bucket != null) {
                            java.util.Set<String> ids = new java.util.HashSet<>();
                            synchronized (bucket) {
                                for (MusicTrack t : bucket) {
                                    if (t != null && t.id != null && !t.id.isEmpty()) ids.add(t.id);
                                }
                            }
                            List<MusicTrack> fresh = new ArrayList<>();
                            for (MusicTrack t : page) {
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
                    if (pageFailed) {
                        bucketFailed.add(bucketKey);
                        bucketEnded.add(bucketKey);
                        if (!partialFailNotified) {
                            partialFailNotified = true;
                            notifyFail = true;
                        }
                    } else if (added > 0) {
                        bucketPages.put(bucketKey, nextPage);
                        // 源明示末页：本页是最后一页，不再多发一页靠回空兜底
                        if (pageResult != null && pageResult.end) bucketEnded.add(bucketKey);
                    } else {
                        bucketEnded.add(bucketKey);
                    }
                }
                if (seq != searchSeq) return;
                if (notifyFail) Notify.show("部分来源加载失败");
                if (added > 0) showSearchResults();
            });
        });
    }

    /** 回初始态：清结果、状态层收起，只剩标题＋搜索框（＋历史）。 */
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
        }
        searchSitesSnap.clear();
        selectedSource = null;
        searchAllReady = false;
        tracks.clear();
        adapter.notifyDataSetChanged();
        XcFadeTransition.fadeOutThenGone(empty);
        refreshHistory();
    }

    /**
     * 展示池：按源分段（2026-10-26 他亲点）——同源一段，段序＝搜索快照站点序
     * （旧源横排 chips 的次序），段内＝该源回包序；续页结果先进各自源的桶、
     * 展示时归入本源段内续排，不混进别段。桶是 synchronizedList，拷贝持桶锁
     * （在途源还在 addAll 时裸拷会撕裂）；快照里站点对应的桶不存在（未搜出/
     * 失败未建桶）时该段跳过。allResults 仍按到达序并行维护，只作队列等
     * 非展示用途的兜底池，不再决定展示顺序。
     */
    private List<MusicTrack> currentSearchPool() {
        List<MusicTrack> out = new ArrayList<>();
        List<MusicSite> snap;
        synchronized (searchBySite) {
            snap = new ArrayList<>(searchSitesSnap);
        }
        for (int i = 0; i < snap.size(); i++) {
            List<MusicTrack> bucket;
            synchronized (searchBySite) {
                bucket = searchBySite.get(searchBucketKey(snap.get(i), i));
            }
            if (bucket == null) continue;
            synchronized (bucket) {
                out.addAll(bucket);
            }
        }
        return out;
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

    /** 把搜索结果显示到列表区：分段池（按源分段）直接成列 */
    private void showSearchResults() {
        if (keyword == null || keyword.isEmpty()) return;
        boolean allFailed = searchTotalSites.get() > 0 && searchFailedSites.get() >= searchTotalSites.get();
        List<MusicTrack> pool = currentSearchPool();
        showTracks(pool);
        updateEmptyState(pool.isEmpty(), allFailed,
                "没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
    }

    /**
     * 状态层统一出口：有结果收起；无结果按「全失败／未到齐／部分失败／真无果」
     * 四态给文案（F1：部分失败不许再被「没有找到」吞掉——失败与没搜到是两回事）。
     */
    private void updateEmptyState(boolean isEmpty, boolean allFailed, String notFoundCopy) {
        if (!isEmpty) {
            XcFadeTransition.fadeOutThenGone(empty);
            return;
        }
        XcFadeTransition.fadeIn(empty);
        boolean partialFailed;
        synchronized (searchBySite) {
            partialFailed = !bucketFailed.isEmpty();
        }
        if (allFailed) empty.setText("搜索失败，请重试");
        else if (!searchAllReady) empty.setText("正在加载…");
        else if (partialFailed) empty.setText("部分来源搜索失败\n" + notFoundCopy);
        else empty.setText(notFoundCopy);
    }

    /** 显示用歌手：占位（空／<unknown>／unknown 类，见 LocalTrackMeta）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    // ================= 列表与播放 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void showTracks(List<MusicTrack> list) {
        tracks.clear();
        tracks.addAll(list);
        runOnUiThread(() -> adapter.notifyDataSetChanged());
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

    /** 本页专属 adapter：只有歌曲行一种（item_music_search_result），分段展示下
     *  tracks 就是按源分段排好的展示池拷贝（见 currentSearchPool）。 */
    private class SearchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            View rowView = inf.inflate(R.layout.item_music_search_result, parent, false);
            // 行框在建行时换成液态玻璃一次到位；bind 只切 tone，绝不再 setBackgroundResource
            // 把未玻璃化的白/粉资源盖回来（队列同病、已在那边治过）
            MusicGlassWire.glassify(rowView);
            return new TrackHolder(rowView);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            bindTrack((TrackHolder) h, position);
        }

        @Override
        public int getItemCount() {
            return tracks.size();
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

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 在播行红名＋玻璃挂红淡染；普通行白名清 tone（与播放列表同一口径）
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFFF1F4F5);
            if (h.itemView.getBackground() instanceof MusicGlassDrawable) {
                MusicGlassDrawable rowGlass = (MusicGlassDrawable) h.itemView.getBackground();
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
                // 封面后补在途（酷狗提速①）：留玻璃空位——清图、透明、不上素图、
                // 不上糊图；图到了回填回调重绑本行、走下面的淡入支。
                // 口径是「先出列表不等封面」，不是「先糊后清」。
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

}
