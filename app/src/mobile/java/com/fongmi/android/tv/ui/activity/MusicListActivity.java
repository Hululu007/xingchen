package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.fongmi.android.tv.bean.music.MusicPluginTest;
import com.fongmi.android.tv.bean.music.MusicRuntimeStatus;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.LxMusicSource;
import com.fongmi.android.tv.api.music.KwChartSource;
import com.fongmi.android.tv.api.music.LxPlatformSearch;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.api.music.PlaylistImporter;
import com.fongmi.android.tv.api.music.AccountPlaylistFetcher;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicListBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.dialog.AccountPlaylistDialog;
import com.fongmi.android.tv.ui.dialog.MusicPlaylistImportDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 歌曲列表页：大标题＋工具条，行＝封面左＋两行字＋徽章＋时长/⋮，
 * 当前播行高亮＋歌名变金。兼做搜索、我的歌单、榜单列表。
 */
public class MusicListActivity extends BaseActivity {

    private ActivityMusicListBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<Object> cards = new ArrayList<>();
    private ListAdapter adapter;
    // 第 11 项歌单页态：多选批量删除＋歌单名搜索过滤（星辰歌单全是本地自建，无平台属性，筛选只有“全部”）
    private boolean sheetsMulti;
    private final Set<Long> sheetsSelected = new java.util.HashSet<>();
    private final List<MusicPlaylist> allSheets = new ArrayList<>();

    /** 歌单页重做：歌单 id→前 4 张不重复曲目封面（马赛克用；loadSheets 时随曲目数一并取，行绑只读缓存，滚动不重复查）。 */
    private final java.util.Map<Long, java.util.List<String>> sheetCovers = new java.util.HashMap<>();

    // 第 7 项尾巴下载页态：进行中任务行＋已完成多选（全选／删除所选）＋下载监听
    private final List<MusicDownloadStore.LiveTask> dlTasks = new ArrayList<>();
    /** 下载已完成全量（不受搜索过滤影响；显示列表 tracks 是过滤后的） */
    private final List<MusicTrack> dlAllTracks = new ArrayList<>();
    private String dlSearchKw = "";
    private boolean dlSearchWired;

    // 第 8 项：本地音乐视图／排序／来源／组筛选（GZ：歌曲／专辑／歌手／文件夹 4 视图＋排序弹层＋来源弹层）
    private static final int DEVICE_VIEW_SONGS = 0;
    private static final int DEVICE_VIEW_ALBUMS = 1;
    private static final int DEVICE_VIEW_ARTISTS = 2;
    private static final int DEVICE_VIEW_FOLDERS = 3;
    private int deviceView = DEVICE_VIEW_SONGS;
    private int deviceSort = 0; // 0 默认 1 最近添加 2 时长 3 文件大小
    private boolean deviceAsc = false; // 默认降序（新→旧／大→小；升降序在分组区块头右侧切换）
    private int deviceSource = 0; // 0 全部 1 设备媒体库 2 导入目录（id 含 :lib: 即导入目录）
    private int deviceGroupType = -1; // -1 无组筛选 0 专辑 1 歌手 2 文件夹
    private String deviceGroupKey = "";
    // 第三波 R6：本地页内搜索词（只滤本地库；组视图同样先滤 base 再分组，不跳全局搜索）
    private String deviceSearchKw = "";
    private boolean deviceSearchWired;
    private final List<MusicTrack> deviceAll = new ArrayList<>();
    private final List<Integer> deviceSectionPos = new ArrayList<>();
    private final List<String> deviceSectionTitles = new ArrayList<>();
    private final List<Integer> deviceSectionCounts = new ArrayList<>();

    // 第 6 项：收藏／历史英雄卡（GZ：拼贴 76dp＋大标题 28sp＋N 首 12sp＋播放全部／随机播放＋搜索框＋排序＋续播卡＋多选）
    private final List<MusicTrack> favHistAll = new ArrayList<>();
    private String favHistSearchKw = "";
    private int favHistSort = 0; // 收藏：0 最近收藏 1 最早收藏 2 歌曲名称；历史：0 最近播放 1 最早播放 2 歌曲名称
    private boolean favHistMulti;
    private final Set<String> favHistSelected = new java.util.HashSet<>();
    private MusicTrack favHistResume;
    private boolean favHistSearchWired;

    // 第 1 项：歌单详情页（GZ：英雄卡＋操作行＋区头＋歌曲行＋排序弹层＋更多表；同步到云端需账号，排除只列不建）
    private static final int REQ_SHEET_COVER = 4314;
    private final List<MusicTrack> sheetAll = new ArrayList<>();
    private String sheetSearchKw = "";
    private int sheetSort = 0; // 0 歌单原顺序 1 加入时间从新到旧 2 加入时间从旧到新 3 按歌曲名 4 按专辑名 5 按歌手名
    private boolean sheetMulti;
    private final Set<String> sheetSelected = new java.util.HashSet<>();
    private boolean sheetSearchWired;
    private boolean sheetSearchShown;

    // 第 3 项：榜单列表页（焦点卡＋普通卡＋分区头）与榜单详情页（英雄卡＋排名行）
    private final java.util.Map<String, List<MusicTrack>> chartPreviews = new java.util.HashMap<>();
    private String chartPlatform = "";
    private String chartId = "";
    private String chartCover = "";
    private final Set<String> dlSelected = new java.util.HashSet<>();
    private boolean dlListenerOn;
    private String mode = "tracks";
    /** onResume 首过标记（中-03）：首次由 initView 的 load() 负责，之后回页才重拉。 */
    private boolean resumedOnce = false;
    private final com.fongmi.android.tv.utils.download.DownloadManager.Listener dlListener = task -> {
        if (!mode.equals("download")) return;
        runOnUiThread(() -> {
            if (task.getStatus() == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_COMPLETED) {
                loadDownloads();
            } else {
                refreshDlTasks();
            }
        });
    };

    private String keyword = "";
    private String bigTitle = "";
    /** 对照表 P2：插件导入进行中（头卡标题变「正在导入插件」、本地按钮标题变「导入中…」） */
    private boolean pluginImporting;
    private String sitePlatform = "";
    private long playlistId = -1;

    // 搜索模式：5 项分类（单曲／歌手／歌单／专辑／歌词，GZ SearchTab 枚举顺序）＋合并结果
    private final List<String> searchCatNames = java.util.Arrays.asList("单曲", "歌手", "歌单", "专辑", "歌词");
    private final List<MusicTrack> allResults = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger searchTotalSites = new AtomicInteger(0);
    private final AtomicInteger searchFailedSites = new AtomicInteger(0);
    private TabAdapter tabAdapter;
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
    private final java.util.LinkedHashMap<String, java.util.List<MusicTrack>> searchBySite =
            new java.util.LinkedHashMap<>();
    private final java.util.List<MusicSite> searchSitesSnap = new java.util.ArrayList<>();
    private String selectedSource = null;
    private boolean searchAllReady = false;

    public static void startSites(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sites");
        activity.startActivity(i);
    }

    public static void startDownload(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "download");
        activity.startActivity(i);
    }

    public static void startDevice(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "device");
        activity.startActivity(i);
    }

    public static void startSearch(Activity activity, String keyword) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "search");
        i.putExtra("keyword", keyword == null ? "" : keyword);
        activity.startActivity(i);
    }

    public static void startSheets(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sheets");
        activity.startActivity(i);
    }

    public static void startFavorite(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "favorite");
        activity.startActivity(i);
    }

    public static void startHistory(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "history");
        activity.startActivity(i);
    }

    public static void startCharts(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "charts");
        activity.startActivity(i);
    }

    public static void startGuess(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "guess");
        activity.startActivity(i);
    }

    public static void startSite(Activity activity, MusicSite site) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "site");
        i.putExtra("sitePlatform", site.platform);
        i.putExtra("title", site.name);
        activity.startActivity(i);
    }

    public static void startSheet(Activity activity, long playlistId, String name) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "sheet");
        i.putExtra("playlistId", playlistId);
        i.putExtra("title", name);
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
        binding = ActivityMusicListBinding.inflate(getLayoutInflater());
        return binding;
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
        mode = i.getStringExtra("mode");
        if (mode == null) mode = "tracks";
        keyword = i.getStringExtra("keyword");
        if (keyword == null) keyword = "";
        sitePlatform = i.getStringExtra("sitePlatform");
        if (sitePlatform == null) sitePlatform = "";
        playlistId = i.getLongExtra("playlistId", -1);
        chartPlatform = i.getStringExtra("chartPlatform");
        chartId = i.getStringExtra("chartId");
        chartCover = i.getStringExtra("chartCover");
        if (chartPlatform == null) chartPlatform = "";
        if (chartId == null) chartId = "";
        if (chartCover == null) chartCover = "";
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new ListAdapter());
        // 插件页单滚动流：插件行与下方专区同页滚动，行再多也不会被专区盖住、不用在列表内盲滑找行。
        // 仅 sites 模式（mode 本页实例内只由 intent 定一次，不会中途切模式，其它模式不受影响）；
        // 框架 ScrollView 足够——列表已关嵌套滚动、一次性量出全部行，不需要 NestedScrollView 的嵌套协调。
        if (mode.equals("sites") && binding.recycler.getParent() instanceof LinearLayout) {
            LinearLayout content = (LinearLayout) binding.recycler.getParent();
            ViewGroup root = (ViewGroup) binding.getRoot();
            root.removeView(content);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.setLayoutParams(new android.widget.ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.addView(content);
            // 2026-10-22 插件管理打开是空的根因：极光背景是不透明底色（BASE 0xFFFAFAFC＋drawColor），
            // 原来插 0 号位把滚动层垫到极光背景底下，整页被盖住。改插到极光背景后面一位，恢复原来层级（内容在极光上、空态覆盖层仍在最上）。
            int auroraIndex = binding.auroraBg == null ? -1 : root.indexOfChild(binding.auroraBg);
            root.addView(scroll, auroraIndex >= 0 ? auroraIndex + 1 : 0);
            binding.recycler.setNestedScrollingEnabled(false);
            ViewGroup.LayoutParams rlp = binding.recycler.getLayoutParams();
            rlp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            binding.recycler.setLayoutParams(rlp);
            binding.recycler.setPadding(binding.recycler.getPaddingLeft(), binding.recycler.getPaddingTop(),
                    binding.recycler.getPaddingRight(), 0);
        }
        // 第 6 项：收藏／历史行间分隔线（GZ：0.5dp 厚、起始偏移 76dp、末端 14dp，与 76dp 拼贴对齐；只在收藏／历史模式画）
        binding.recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void onDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
                if (!mode.equals("favorite") && !mode.equals("history")) return;
                float density = parent.getResources().getDisplayMetrics().density;
                float left = 76 * density;
                float right = parent.getWidth() - 14 * density;
                float thickness = Math.max(1, 0.5f * density);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(0x14000000);
                paint.setStrokeWidth(thickness);
                int count = parent.getChildCount();
                for (int i = 0; i < count - 1; i++) {
                    android.view.View child = parent.getChildAt(i);
                    float y = child.getBottom();
                    c.drawLine(left, y, right, y, paint);
                }
            }
        });
        binding.tabRow.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        binding.tabRow.setAdapter(tabAdapter = new TabAdapter());
        binding.btnPlayAll.setOnClickListener(v -> shufflePlay());
        // 第三波 A2/R3：源选择器（搜索模式工具条 pill）＋通用“播放全部”顺序入口
        binding.btnPlaySeq.setOnClickListener(v -> playAllSequential());
        binding.btnSourceFilter.setOnClickListener(v -> showSourceDialog());
        if (mode.equals("search")) {
            // 第三波 A1：大标题行定宽，tvBigTitle 才能 maxLines=1+ellipsize=END 单行截断
            android.view.ViewGroup.LayoutParams rlp = binding.bigTitleRow.getLayoutParams();
            rlp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            binding.bigTitleRow.setLayoutParams(rlp);
            android.widget.LinearLayout.LayoutParams tlp =
                    (android.widget.LinearLayout.LayoutParams) binding.tvBigTitle.getLayoutParams();
            tlp.width = 0;
            tlp.weight = 1f;
            binding.tvBigTitle.setLayoutParams(tlp);
        }
        binding.btnChartsRefresh.setOnClickListener(v -> loadCharts());
        binding.btnSort.setOnClickListener(v -> {
            if (mode.equals("device")) showDeviceSortDialog();
            else if (mode.equals("favorite") || mode.equals("history")) showFavHistSortDialog();
            else if (mode.equals("search") && selectedCat != 0 && searchGroupType < 0) {
                // 第 5 项：歌手／歌单／专辑分类是分组卡，排序只对歌曲列有意义，分组卡不支持排序
                Notify.show("分组不支持排序");
            } else showGenericSortDialog();
        });
        // 第 11 项歌单页头／操作行（仅 sheets 模式可见，其余模式布局里 GONE）
        binding.btnSheetsNew.setOnClickListener(v -> inputPlaylistName(name ->
                MusicLibrary.createPlaylist(name, p -> loadSheets())));
        binding.btnSheetsImport.setOnClickListener(v -> showImportPlaylistDialog());
        binding.btnSheetsSearch.setOnClickListener(v -> {
            boolean show = binding.searchBar.getVisibility() != View.VISIBLE;
            binding.searchBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) applySheetsFilter();
        });
        binding.btnSheetsMulti.setOnClickListener(v -> setSheetsMulti(!sheetsMulti));
        binding.btnSheetsFilter.setOnClickListener(v ->
                MusicOptionsDialog.show(this, "歌单筛选",
                        java.util.Collections.singletonList("全部"), 0, idx -> applySheetsFilter()));
        // 对照表 P4：页内两入口按钮直连现有 pickLocalFile／inputSiteUrl，不再走二选一弹窗
        binding.btnPluginLocal.setOnClickListener(v -> pickLocalFile());
        binding.btnPluginOnline.setOnClickListener(v -> inputSiteUrl());
        binding.btnPluginSort.setOnClickListener(v -> showPluginSortDialog());
        // 第 7 项尾巴：下载批量管理条（随机播放走通用工具条的 shuffle，不在这里重复造入口）
        binding.btnDlSelect.setOnClickListener(v -> toggleDlSelect());
        binding.btnDlPauseAll.setOnClickListener(v -> {
            MusicDownloadStore.pauseAllLive();
            refreshDlTasks();
        });
        binding.btnDlResumeAll.setOnClickListener(v -> {
            MusicDownloadStore.resumeAllLive();
            refreshDlTasks();
        });
        binding.btnDlCancelAll.setOnClickListener(v -> {
            // GZ 批量取消确认文案（单任务版在任务行取消钮）
            if (dlTasks.isEmpty()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("取消下载")
                    .setMessage("确定取消正在进行的 " + dlTasks.size() + " 个下载任务吗？已下载的临时内容将被删除，无法继续恢复。")
                    .setPositiveButton("取消下载", (d, which) -> {
                        MusicDownloadStore.cancelAllLive();
                        refreshDlTasks();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        binding.btnDlClearFailed.setOnClickListener(v -> {
            MusicDownloadStore.clearFailedLive();
            refreshDlTasks();
        });
        binding.btnDlPlayAll.setOnClickListener(v -> {
            if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
            else playTrack(0);
        });
        // 第 8 项：本地音乐视图切换＋来源＋组筛选清除
        binding.tvDeviceViewSongs.setOnClickListener(v -> setDeviceView(DEVICE_VIEW_SONGS));
        binding.tvDeviceViewAlbums.setOnClickListener(v -> setDeviceView(DEVICE_VIEW_ALBUMS));
        binding.tvDeviceViewArtists.setOnClickListener(v -> setDeviceView(DEVICE_VIEW_ARTISTS));
        binding.tvDeviceViewFolders.setOnClickListener(v -> setDeviceView(DEVICE_VIEW_FOLDERS));
        binding.tvDeviceSource.setOnClickListener(v -> showDeviceSourceDialog());
        // 第二波：本地扫描设置／移除恢复入口（仅 device 模式工具条内，见 refreshDeviceDisplay 更新显隐与计数）
        if (binding.tvDeviceScanSettings != null) binding.tvDeviceScanSettings.setOnClickListener(v -> showDeviceScanDialog());
        if (binding.tvDeviceRemoved != null) binding.tvDeviceRemoved.setOnClickListener(v -> showDeviceRemovedDialog());
        binding.deviceFilterBar.setOnClickListener(v -> {
            deviceGroupType = -1;
            deviceGroupKey = "";
            refreshDeviceDisplay();
        });
        // 第 6 项：收藏／历史英雄卡操作行
        binding.btnFavHistPlayAll.setOnClickListener(v -> {
            if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
            else playTrack(0);
        });
        binding.btnFavHistShuffle.setOnClickListener(v -> shufflePlay());
        binding.btnFavHistMulti.setOnClickListener(v -> setFavHistMulti(!favHistMulti));
        binding.btnFavHistClear.setOnClickListener(v -> {
            // 历史页清空（GZ：历史页有清除；收藏页无清空全部按钮，故本钮只在历史模式可见）
            new MaterialAlertDialogBuilder(this)
                    .setTitle("清空播放历史")
                    .setMessage("确定清空全部播放历史吗？")
                    .setPositiveButton("清空", (d, w) -> {
                        com.fongmi.android.tv.music.MusicStore.clearRecent();
                        loadHistory();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        binding.favHistResume.setOnClickListener(v -> {
            if (favHistResume == null) return;
            int idx = -1;
            for (int j = 0; j < favHistAll.size(); j++) {
                if (favHistResume.id != null && favHistResume.id.equals(favHistAll.get(j).id)) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) return;
            MusicController.get().play(new ArrayList<>(favHistAll), idx, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
        });
        binding.btnFavHistClearSearch.setOnClickListener(v -> binding.etFavHistSearch.setText(""));
        // 第 1 项：歌单详情页顶栏 ⋮＋页内搜索
        binding.btnSheetMore.setOnClickListener(v -> showSheetTopMenu());
        if (!sheetSearchWired) {
            sheetSearchWired = true;
            binding.etSheetSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    sheetSearchKw = s == null ? "" : s.toString().trim();
                    refreshSheetDisplay();
                }
            });
        }
        if (!favHistSearchWired) {
            favHistSearchWired = true;
            binding.etFavHistSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    favHistSearchKw = s == null ? "" : s.toString().trim();
                    binding.btnFavHistClearSearch.setVisibility(
                            favHistSearchKw.isEmpty() ? View.GONE : View.VISIBLE);
                    refreshFavHistDisplay();
                }
            });
        }
        binding.btnSearch.setOnClickListener(v -> startSearch(this, ""));
        // 批 5 F1：搜索框行右「取消」＝直接退出搜索页（系统返回同路，不放返回箭头）。
        if (binding.searchCancel != null) binding.searchCancel.setOnClickListener(v -> finish());
        // 批 5 F1 空输入核查：搜索只由键盘搜索键／历史筹／热搜行显式触发，输入框无任何
        // TextWatcher 自动搜；此处 watcher 只做反向守卫——文字被清空时回落地页清结果，
        // 杜绝「空输入自动搜／旧结果残留」。仅 search 模式挂（etKeyword 与歌单页共用，勿扰 sheets）。
        if (mode.equals("search")) {
            binding.etKeyword.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence cs, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence cs, int a, int b, int c) { }
                @Override public void afterTextChanged(android.text.Editable e) {
                    String q = e == null ? "" : e.toString().trim();
                    if (q.isEmpty() && binding.searchLanding.getVisibility() != View.VISIBLE) showSearchLanding();
                }
            });
        }
        // 第二波：搜索框右侧「搜索」钮按瓜子去掉（瓜子 SearchTextField 无此钮，框内是平台下拉——下拉属搜索方式不抄）；
        // 提交走键盘搜索键（IME_ACTION_SEARCH／回车，见下），歌单页搜索同样走键盘键，按钮移除不影响提交链。
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (mode.equals("sheets")) applySheetsFilter();
                else doSearch();
                return true;
            }
            return false;
        });
        MusicController.get().addListener(controllerListener);
        load();
    }

    /**
     * 回页刷新本地数据（中-03）：此前本页没有 onResume，从播放页/别页改了收藏、
     * 删了下载、移了歌单回来都还是旧列表，只能杀页重进。只重拉本地数据模式，
     * 网络模式（榜单/站点/搜索/猜你喜欢）不回页重拉，免得白耗流量与接口。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        switch (mode) {
            case "sheets":
                loadSheets();
                break;
            case "favorite":
                loadFavorites();
                break;
            case "history":
                loadHistory();
                break;
            case "sheet":
                loadSheetTracks();
                break;
            case "download":
                loadDownloads();
                break;
            case "device":
                loadDevice();
                break;
            default:
                break;
        }
    }

    @Override
    protected void onDestroy() {
        if (dlListenerOn) {
            try {
                com.fongmi.android.tv.utils.download.DownloadManager.get().removeListener(dlListener);
            } catch (Throwable ignored) {
            }
        }
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        // 复核必-10：第二段升降序弹层的 post 必须随页销毁取消，不许页死后还弹。
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private boolean isCardMode() {
        return mode.equals("sheets") || mode.equals("charts") || mode.equals("sites");
    }

    private void load() {
        // 本页对照修：空态点击监听是共享 overlay 的，换模式先清掉，防本地页的重扫监听漏到别页
        binding.empty.setOnClickListener(null);
        if (binding.pluginSections != null) binding.pluginSections.setVisibility(View.GONE);
        // 对照表 P2/P4/P7/P22：sites 专属视图默认 GONE，非 sites 模式必须保持不可见
        if (binding.pluginHeaderCard != null) binding.pluginHeaderCard.setVisibility(View.GONE);
        if (binding.pluginAddRow != null) binding.pluginAddRow.setVisibility(View.GONE);
        if (binding.pluginInstalledHeader != null) binding.pluginInstalledHeader.setVisibility(View.GONE);
        if (binding.pluginEmptyCard != null) binding.pluginEmptyCard.setVisibility(View.GONE);
        // 第 7 项尾巴：下载专属视图默认 GONE，非 download 模式必须保持不可见
        if (binding.downloadStatsCard != null) binding.downloadStatsCard.setVisibility(View.GONE);
        if (binding.downloadBatchBar != null) binding.downloadBatchBar.setVisibility(View.GONE);
        if (binding.tvDlLiveHeader != null) binding.tvDlLiveHeader.setVisibility(View.GONE);
        if (binding.etDlSearch != null) binding.etDlSearch.setVisibility(View.GONE);
        // 第 8 项：本地音乐视图条／组筛选条默认 GONE，非 device 模式必须保持不可见
        if (binding.deviceViewBar != null) binding.deviceViewBar.setVisibility(View.GONE);
        if (binding.deviceFilterBar != null) binding.deviceFilterBar.setVisibility(View.GONE);
        // 第二波：本地扫描工具条默认 GONE，非 device 模式必须保持不可见
        if (binding.deviceToolsBar != null) binding.deviceToolsBar.setVisibility(View.GONE);
        // 第 6 项：收藏／历史英雄卡默认 GONE，非 favorite/history 模式必须保持不可见；多选态复位
        if (binding.favHistHero != null) binding.favHistHero.setVisibility(View.GONE);
        favHistMulti = false;
        favHistSelected.clear();
        // 第 1 项：歌单详情页内搜索框／顶栏 ⋮ 默认 GONE，非 sheet 模式必须保持不可见；多选态复位
        if (binding.etSheetSearch != null) binding.etSheetSearch.setVisibility(View.GONE);
        if (binding.btnSheetMore != null) binding.btnSheetMore.setVisibility(View.GONE);
        sheetMulti = false;
        sheetSelected.clear();
        sheetSearchShown = false;
        sheetSearchKw = "";
        binding.tvTitle.setText(titleForMode());
        // 第 11 项：sheets 模式用 GZ 页头（YOUR PLAYLISTS＋大标题＋操作行），顶栏居中标题与大标题行、随机播放行都隐藏
        // 第 6 项：收藏／历史模式同样用自己的英雄卡当页头，顶栏与大标题行隐藏；工具条只留排序（播放／随机播在英雄卡操作行）
        boolean sheets = mode.equals("sheets");
        boolean favHist = mode.equals("favorite") || mode.equals("history");
        boolean sheet = mode.equals("sheet");
        boolean chartDetail = mode.equals("chartDetail");
        boolean guess = mode.equals("guess");
        binding.headerBar.setVisibility((sheets || favHist || guess) ? View.GONE : View.VISIBLE);
        binding.sheetsHeader.setVisibility(sheets ? View.VISIBLE : View.GONE);
        binding.sheetsOpRow.setVisibility(sheets ? View.VISIBLE : View.GONE);
        // 歌单页极光背景只跟 sheets 模式走（其它模式 GONE）；非 sheets 时空态块也复位藏起，空态显隐由 refreshSheetsEmpty 管
        // 第三波 B⑧：插件页同样用极光底（近白 #FFFAFAFC＋柔光），不再 GONE 了事
        binding.auroraBg.setVisibility((sheets || mode.equals("sites")) ? View.VISIBLE : View.GONE);
        // 批 5 F1：搜索模式背景换全域统一玻璃背景（与首页同一份实现）；其它模式维持原底不动。
        if (binding.musicGlassBg != null) binding.musicGlassBg.setVisibility(mode.equals("search") ? View.VISIBLE : View.GONE);
        if (!sheets) binding.sheetsEmpty.setVisibility(View.GONE);
        // 第 1 项：歌单详情页用英雄卡当页头，大标题行隐藏（顶栏标题＝歌单名，见 loadSheetTracks）
        // 第 3 项：榜单详情页同样用英雄卡当页头，大标题行与工具条隐藏（排名不参与列表排序）
        // 第 9 项：猜你喜欢页同样用自己的标题＋英雄卡当页头，大标题行与工具条隐藏（播放／随机播／保存在英雄卡操作行）
        // 2026-10-22 搜索页重做：搜索模式大标题行隐藏——顶栏居中标题已是“搜索”，再留左侧大“搜索”就是截图里的双标题。
        binding.bigTitleRow.setVisibility((sheets || favHist || sheet || chartDetail || guess || mode.equals("search")) ? View.GONE : View.VISIBLE);
        binding.toolbar.setVisibility((mode.equals("sites") || sheets || chartDetail || guess) ? View.GONE : View.VISIBLE);
        if (favHist) {
            binding.btnPlayAll.setVisibility(View.GONE);
            binding.btnPlaySeq.setVisibility(View.GONE);
            binding.btnSort.setVisibility(View.VISIBLE);
        }
        // M12：榜单模式只有榜卡、没有歌曲行，顺序播放钮点了只弹空提示，直接隐藏（不补池）。
        if (mode.equals("charts")) binding.btnPlaySeq.setVisibility(View.GONE);
        if (mode.equals("charts")) binding.btnPlayAll.setVisibility(View.GONE);
        binding.searchBar.setVisibility(mode.equals("search") ? View.VISIBLE : View.GONE);
        if (binding.searchCancel != null) binding.searchCancel.setVisibility(mode.equals("search") ? View.VISIBLE : View.GONE);
        binding.btnSearch.setVisibility((mode.equals("search") || mode.equals("sites") || sheets) ? View.GONE : View.VISIBLE);
        if (!keyword.isEmpty()) {
            binding.etKeyword.setText(keyword);
            doSearch();
            return;
        }
        switch (mode) {
            case "sheets":
                bigTitle = getString(R.string.music_playlist);
                loadSheets();
                break;
            case "charts":
                bigTitle = getString(R.string.music_charts);
                binding.btnChartsRefresh.setVisibility(View.VISIBLE);
                loadCharts();
                break;
            case "chartDetail":
                loadChartDetail();
                break;
            case "favorite":
                bigTitle = getString(R.string.music_favorite);
                loadFavorites();
                break;
            case "history":
                bigTitle = getString(R.string.music_history);
                loadHistory();
                break;
            case "guess":
                bigTitle = getString(R.string.music_guess);
                loadGuess();
                break;
            case "site":
                loadSiteTracks();
                break;
            case "sheet":
                loadSheetTracks();
                break;
            case "download":
                bigTitle = getString(R.string.music_downloaded);
                loadDownloads();
                break;
            case "device":
                loadDevice();
                break;
            case "sites":
                bigTitle = getString(R.string.music_plugin_manage);
                loadSites();
                break;
            case "search":
                bigTitle = getString(R.string.music_search);
                showSearchLanding();
                break;
            default:
                showTracks(new ArrayList<>());
                break;
        }
    }

    private String titleForMode() {
        switch (mode) {
            case "search":
                return getString(R.string.music_search);
            case "sheets":
                return getString(R.string.music_playlist);
            case "favorite":
                return getString(R.string.music_favorite);
            case "charts":
                return getString(R.string.music_charts);
            case "download":
                return getString(R.string.wave2_download_manage);
            case "device":
                return getString(R.string.music_device);
            case "sites":
                return getString(R.string.music_plugin_manage);
            case "history":
                return getString(R.string.music_history);
            case "guess":
                return getString(R.string.music_guess);
            default:
                return getString(R.string.music_playlist);
        }
    }

    /**
     * 搜索：GZ 5 项分类（单曲／歌手／歌单／专辑／歌词，SearchTab 枚举顺序）；
     * 全站并发搜、结果合并后按分类展示；切分类只换显示、不重搜。
     * 某个源报错只影响它自己，其它源照常出结果；全部失败才显「搜索失败，请重试」。
     */
    private void doSearch() {
        keyword = binding.etKeyword.getText().toString().trim();
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
        binding.searchLanding.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.VISIBLE);
        // 第三波 A1：计数条／组筛选条恒占位（无内容写空字），切分类不再改布局高度
        binding.searchCountBar.setVisibility(View.VISIBLE);
        binding.searchCountBar.setText("");
        binding.searchGroupBar.setVisibility(View.VISIBLE);
        binding.searchGroupBar.setText("");
        binding.empty.setMinHeight(dp(160));
        binding.tabRow.setVisibility(View.VISIBLE);
        binding.tvTitle.setText(titleForMode());
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
                                java.util.List<MusicTrack> bucket = searchBySite.get(bucketKey);
                                if (bucket == null) {
                                    bucket = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
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
        binding.tabRow.smoothScrollToPosition(idx);
        showCategoryResults();
    }

    /** 第三波 A2：当前搜索池——“全部”＝allResults；选源后＝该源桶（过滤先于分组，不跨源去重）。 */
    private List<MusicTrack> currentSearchPool() {
        if (selectedSource == null) return new ArrayList<>(allResults);
        java.util.List<MusicTrack> bucket;
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
            for (java.util.Map.Entry<String, java.util.List<MusicTrack>> e : searchBySite.entrySet()) {
                java.util.List<MusicTrack> bucket = e.getValue();
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

    /** 第三波 A2：源选择器显隐与文案（搜索模式＋单曲／歌手／专辑签＋非钻取时才显；歌单／歌词签下隐藏）。
     * 全部源到齐（searchAllReady）前禁用，计数边搜边变不许切源。 */
    private void refreshSourceFilter() {
        if (binding.btnSourceFilter == null) return;
        boolean show = mode.equals("search")
                && (selectedCat == 0 || selectedCat == 1 || selectedCat == 3)
                && searchGroupType < 0;
        binding.btnSourceFilter.setVisibility(show ? View.VISIBLE : View.GONE);
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
        binding.btnSourceFilter.setText(label + " ▾");
        binding.btnSourceFilter.setEnabled(searchAllReady);
        binding.btnSourceFilter.setAlpha(searchAllReady ? 1f : 0.5f);
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
        binding.searchLanding.setVisibility(View.GONE);
        binding.recycler.setVisibility(View.VISIBLE);
        binding.tabRow.setVisibility(View.VISIBLE);
        // 结果出来工具条才回来（落地页已隐藏，见 showSearchLanding）。
        binding.toolbar.setVisibility(View.VISIBLE);
        tabAdapter.notifyDataSetChanged();
        binding.tvBigTitle.setText(bigTitle);
        refreshSourceFilter();
        List<MusicTrack> pool = currentSearchPool();
        if (searchGroupType == 1 || searchGroupType == 3) {
            // 组内歌曲列：按歌手／专辑过滤，筛选条点 × 回分类（筛选条是过滤控件、不是返回箭头）
            List<MusicTrack> g = new ArrayList<>();
            for (MusicTrack t : pool) {
                String k = searchGroupType == 1 ? artistOf(t) : albumOf(t);
                if (k.equals(searchGroupKey)) g.add(t);
            }
            binding.searchCountBar.setVisibility(View.VISIBLE);
            binding.searchCountBar.setText("");
            binding.searchGroupBar.setVisibility(View.VISIBLE);
            binding.searchGroupBar.setText((searchGroupType == 1 ? "歌手：" : "专辑：") + searchGroupKey + " ×");
            binding.searchGroupBar.setOnClickListener(v -> {
                searchGroupType = -1;
                searchGroupKey = "";
                showCategoryResults();
            });
            showTracks(g);
            if (g.isEmpty()) {
                binding.empty.setVisibility(View.VISIBLE);
                binding.empty.setText("没有找到『" + keyword + "』相关歌曲\n换一个歌名、歌手或专辑试试");
            }
            return;
        }
        binding.searchGroupBar.setVisibility(View.VISIBLE);
        binding.searchGroupBar.setText("");
        boolean allFailed = searchTotalSites.get() > 0 && searchFailedSites.get() >= searchTotalSites.get();
        switch (selectedCat) {
            case 0: // 单曲：直接列表（计数并入标题行 tvBigCount，计数条恒占位空字）
                binding.searchCountBar.setVisibility(View.VISIBLE);
                binding.searchCountBar.setText("");
                showTracks(new ArrayList<>(pool));
                if (tracks.isEmpty()) {
                    binding.empty.setVisibility(View.VISIBLE);
                    binding.empty.setText(allFailed ? "搜索失败，请重试"
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
                binding.searchCountBar.setVisibility(View.VISIBLE);
                binding.searchCountBar.setText("");
                binding.tvBigCount.setText("");
                binding.empty.setVisibility(View.VISIBLE);
                binding.empty.setText("暂不支持歌词全文搜索");
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

    /** 搜索分桶键：srcUrl 为空时按快照下标走 "site:"+index；分桶／取池／选源三处同用。 */
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
        binding.tvBigCount.setText("");
        binding.searchCountBar.setVisibility(View.VISIBLE);
        binding.searchCountBar.setText(groups.size() + unit);
        binding.empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        if (cards.isEmpty()) binding.empty.setText(allFailed ? "搜索失败，请重试" : emptyCopy + "\n换一个歌名、歌手或专辑试试");
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
        binding.tvBigCount.setText("");
        binding.searchCountBar.setVisibility(View.VISIBLE);
        binding.searchCountBar.setText(cards.size() + " 个");
        binding.empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
        if (cards.isEmpty()) binding.empty.setText(allFailed ? "搜索失败，请重试" : "没有找到『" + keyword + "』相关歌单\n换一个歌名、歌手或专辑试试");
    }

    /** 搜索落地页：最近搜索胶囊＋热门搜索＋猜你想搜（无结果时显落地，不显空列表） */
    private void showSearchLanding() {
        binding.searchLanding.setVisibility(View.VISIBLE);
        binding.recycler.setVisibility(View.GONE);
        binding.tabRow.setVisibility(View.GONE);
        // 2026-10-22 搜索页重做：落地页不许再出现空的组筛选条（空卡片背景就是截图里那个 stray 圆圈）、
        // 空的计数条、结果工具条（随机播放／播放全部／来源／排序是结果才有的东西，落地页没有结果）。
        binding.searchCountBar.setVisibility(View.GONE);
        binding.searchGroupBar.setVisibility(View.GONE);
        binding.toolbar.setVisibility(View.GONE);
        if (binding.hotHeader != null) binding.hotHeader.setVisibility(View.GONE);
        refreshSourceFilter();
        tracks.clear();
        cards.clear();
        adapter.notifyDataSetChanged();
        binding.empty.setVisibility(View.GONE);
        binding.tvBigTitle.setText(bigTitle);
        binding.tvBigCount.setText("");
        binding.btnHotRefresh.setOnClickListener(v -> loadSearchHot());
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
        binding.recentFlow.removeAllViews();
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
                binding.etKeyword.setText(w);
                doSearch();
            });
            binding.recentFlow.addView(chip);
        }
        binding.recentHeader.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        binding.recentFlow.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        binding.tvRecentClear.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        binding.tvRecentClear.setOnClickListener(v -> {
            MusicStore.clearRecentSearches();
            searchRecentExpanded = false;
            buildRecentChips();
        });
        binding.tvRecentMore.setVisibility(all.size() > 10 ? View.VISIBLE : View.GONE);
        binding.tvRecentMore.setText(searchRecentExpanded ? "收起" : "更多");
        binding.tvRecentMore.setOnClickListener(v -> {
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
                if (!mode.equals("search")) return;
                searchHotTerms = result;
                buildHotRows();
            });
        });
    }

    /** 热搜行（批 5 F1 换新）：左右两列排名卡，每行两格；前 3 名红字、其余灰字；点格即搜。 */
    private void buildHotRows() {
        binding.hotRows.removeAllViews();
        // 热搜没数据时整段隐藏（不留空标题、不贴占位）。
        boolean hotEmpty = searchHotTerms == null || searchHotTerms.isEmpty();
        if (binding.hotHeader != null) binding.hotHeader.setVisibility(hotEmpty ? View.GONE : View.VISIBLE);
        binding.hotRows.setVisibility(hotEmpty ? View.GONE : View.VISIBLE);
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
            binding.hotRows.addView(row);
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
            binding.etKeyword.setText(w);
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
            if (!mode.equals("search")) return;
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
        binding.guessRows.removeAllViews();
        binding.guessHeader.setVisibility(terms.isEmpty() ? View.GONE : View.VISIBLE);
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
                binding.etKeyword.setText(w);
                doSearch();
            });
            binding.guessRows.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x14000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divLp.leftMargin = dp(30);
            binding.guessRows.addView(div);
        }
    }

    private void loadSheets() {
        showLoading();
        MusicLibrary.playlists(list -> {
            allSheets.clear();
            sheetCovers.clear();
            allSheets.addAll(list);
            runOnUiThread(() -> {
                binding.sheetsTitle.setText(bigTitle);
                applySheetsFilter();
                refreshSheetsEmpty();
                // 异步补每个歌单的曲目数（按 id 在当前过滤后列表里定位，不依赖加载时下标）
                for (MusicPlaylist p : list) {
                    MusicLibrary.playlistTrackCount(p.getId(), n -> {
                        p.trackCount = n;
                        for (int i = 0; i < cards.size(); i++) {
                            if (cards.get(i) instanceof MusicPlaylist
                                    && ((MusicPlaylist) cards.get(i)).getId() == p.getId()) {
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    });
                    // 歌单页重做：同循环取前 4 张封面进马赛克缓存（与曲目数同定位口径；空歌单记空表，行绑回落图标）
                    MusicLibrary.playlistTracks(p.getId(), tracks -> {
                        java.util.List<String> covers = new java.util.ArrayList<>();
                        if (tracks != null) {
                            for (MusicTrack t : tracks) {
                                if (t != null && t.cover != null && !t.cover.isEmpty() && !covers.contains(t.cover)) {
                                    covers.add(t.cover);
                                    if (covers.size() >= 4) break;
                                }
                            }
                        }
                        sheetCovers.put(p.getId(), covers);
                        runOnUiThread(() -> {
                            for (int i = 0; i < cards.size(); i++) {
                                if (cards.get(i) instanceof MusicPlaylist
                                        && ((MusicPlaylist) cards.get(i)).getId() == p.getId()) {
                                    adapter.notifyItemChanged(i);
                                    break;
                                }
                            }
                        });
                    });
                }
            });
        });
    }

    /** 第 11 项：歌单名过滤（操作行搜索钮切出的搜索框内关键字，空则全量；新建／导入卡已由操作行替代，不再进列表） */
    private void applySheetsFilter() {
        String q = binding.etKeyword.getText() == null ? ""
                : binding.etKeyword.getText().toString().trim();
        cards.clear();
        for (MusicPlaylist p : allSheets) {
            if (q.isEmpty() || (p.getName() != null && p.getName().contains(q))) cards.add(p);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshSheetsEmpty();
    }

    /** 歌单页空态（瓜子 EmptyPlaylistsHint 口径）：歌单总数为 0 时显图标＋两行空态；
     * 搜索过滤无匹配时同块改纯文本态（无图标、无副标题，文案“没找到匹配的歌单”）；
     * 有歌单时整块藏。旧代码 loadSheets 里无条件 empty GONE 是空态全空的根因，不许再写死。
     * 瓜子多选 pill 空列表时置灰禁用（enabled＝过滤后列表非空，禁用 alpha 0.4），同这里一并管；
     * 非 sheets 模式只复位藏空态块，不动其它模式的 empty（各模式自己管）。 */
    private void refreshSheetsEmpty() {
        binding.sheetsEmpty.setVisibility(View.GONE);
        if (!mode.equals("sheets")) return;
        binding.empty.setVisibility(View.GONE);
        String q = binding.etKeyword.getText() == null ? ""
                : binding.etKeyword.getText().toString().trim();
        boolean noSheets = allSheets.isEmpty() && q.isEmpty();
        if (noSheets) {
            binding.sheetsEmptyIcon.setVisibility(View.VISIBLE);
            binding.sheetsEmptyTitle.setText(R.string.music_no_playlist);
            binding.sheetsEmptySub.setVisibility(View.VISIBLE);
            binding.sheetsEmpty.setVisibility(View.VISIBLE);
        } else if (cards.isEmpty()) {
            binding.sheetsEmptyIcon.setVisibility(View.GONE);
            binding.sheetsEmptyTitle.setText(R.string.music_no_match_playlist);
            // 第二波：搜索无匹配补引导半句（瓜子 EmptyPlaylistsHint 后半句「或切到其他平台」是瓜子跨平台歌单搜索口径，
            // 星辰歌单搜索只搜本地歌单、无平台可切，故只取前半句，不编平台切换）
            binding.sheetsEmptySub.setVisibility(View.VISIBLE);
            binding.sheetsEmptySub.setText("换个关键词试试");
            binding.sheetsEmpty.setVisibility(View.VISIBLE);
        }
        boolean multiEnabled = !cards.isEmpty();
        binding.btnSheetsMulti.setEnabled(multiEnabled);
        binding.btnSheetsMulti.setAlpha(multiEnabled ? 1f : 0.4f);
    }

    /** 第 11 项：多选开关——开则行点按变勾选、工具条变“删除所选 (N)”；关则清空勾选恢复正常 */
    private void setSheetsMulti(boolean on) {
        sheetsMulti = on;
        if (!on) sheetsSelected.clear();
        binding.btnSheetsMulti.setText(on ? "取消多选" : "多选");
        binding.btnSheetsMulti.setTextColor(on ? 0xFFFA243C : 0xFF1C1C1E);
        binding.toolbar.setVisibility(on ? View.VISIBLE : View.GONE);
        binding.btnSort.setVisibility(on ? View.GONE : View.VISIBLE);
        binding.tvPlayAll.setText("删除所选 (" + sheetsSelected.size() + ")");
        binding.ivPlayAllIcon.setImageResource(on ? R.drawable.ic_music_delete
                : R.drawable.ic_music_shuffle);
        binding.btnPlayAll.setOnClickListener(on ? v -> deleteSheetsSelected()
                : v -> shufflePlay());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void deleteSheetsSelected() {
        if (sheetsSelected.isEmpty()) {
            Notify.show("请先选歌单");
            return;
        }
        List<Long> ids = new ArrayList<>(sheetsSelected);
        sheetsSelected.clear();
        for (Long id : ids) MusicLibrary.deletePlaylist(id, null);
        setSheetsMulti(false);
        loadSheets();
    }

    /** 第 9 项：猜你喜欢线索数（文案「来自收藏与最近播放的 N 条线索」里的 N，收藏＋最近播放去重后条数） */
    private int guessClueCount = 0;

    /**
     * 第 9 项：猜你喜欢。推荐算法待他定——先用本地已有数据简单轮换顶上，不假装是 GZ 的推荐算法：
     * 收藏／最近播放／已下载三路轮流取、按 id 去重、上限 50 首。
     * 设备媒体库扫描要权限流程，不在猜你喜欢页内触发（“本地”口径这里＝已下载本地文件）。
     * 空态用 GZ 空文案；“推荐获取失败／重试”是拉取失败态，本地数据没有失败触发点，只显空态。
     */
    private void loadGuess() {
        showLoading();
        MusicLibrary.favorites(favs -> {
            if (!mode.equals("guess")) return;
            MusicDownloadStore.all(dls -> {
                if (!mode.equals("guess")) return;
                List<MusicTrack> recents = MusicStore.loadRecent();
                List<MusicTrack> favList = favs == null ? new ArrayList<>() : favs;
                List<MusicTrack> dlList = dls == null ? new ArrayList<>() : dls;
                java.util.Set<String> clueSeen = new java.util.HashSet<>();
                int clues = 0;
                for (MusicTrack t : favList) if (t.id == null || clueSeen.add(t.id)) clues++;
                for (MusicTrack t : recents) if (t.id == null || clueSeen.add(t.id)) clues++;
                guessClueCount = clues;
                final List<MusicTrack> pool = buildGuessPool(favList, recents, dlList);
                runOnUiThread(() -> {
                    if (!mode.equals("guess")) return;
                    tracks.clear();
                    tracks.addAll(pool);
                    adapter.notifyDataSetChanged();
                    binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
                    if (tracks.isEmpty()) binding.empty.setText("熟悉的偏爱，也有意外的新发现\n多听几首，让推荐慢慢懂你");
                });
            });
        });
    }

    /** 三路轮流取、按 id 去重、上限 50 首（收藏→最近播放→已下载，每轮各取 1 首） */
    private List<MusicTrack> buildGuessPool(List<MusicTrack> favs, List<MusicTrack> recents, List<MusicTrack> dls) {
        List<List<MusicTrack>> srcs = java.util.Arrays.asList(favs, recents, dls);
        List<MusicTrack> pool = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int max = Math.max(favs.size(), Math.max(recents.size(), dls.size()));
        for (int i = 0; i < max && pool.size() < 50; i++) {
            for (List<MusicTrack> s : srcs) {
                if (pool.size() >= 50) break;
                if (i >= s.size()) continue;
                MusicTrack t = s.get(i);
                if (t.id == null || seen.add(t.id)) pool.add(t);
            }
        }
        return pool;
    }

    /** 榜单只留酷我＋QQ（他定）：其它源（哔哩哔哩等）的榜一律不列，与首页榜单卡 QQ＞酷我口径一致。 */
    private static boolean isChartSite(MusicSite s) {
        if (s == null) return false;
        String tag = ((s.platform == null ? "" : s.platform) + (s.name == null ? "" : s.name)).toLowerCase();
        return tag.contains("qq") || tag.contains("tx") || tag.contains("kw") || tag.contains("酷我");
    }

    private void loadCharts() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicRepository.sites();
            List<MusicChart> charts = new ArrayList<>();
            for (MusicSite s : sites) {
                if (!isChartSite(s)) continue;
                charts.addAll(MusicRepository.charts(s));
            }
            runOnUiThread(() -> {
                cards.clear();
                cards.addAll(charts);
                chartPreviews.clear();
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                // 第二波：榜单列表双标题去重（瓜子单顶栏口径）：顶栏 26sp「榜单」已是标题，大标题行 30sp 同名隐藏，
                // 只留「N 个榜单」计数＋刷新钮作副标题／工具行（本页实例模式固定，不影响其它模式）
                binding.tvBigTitle.setVisibility(View.GONE);
                binding.tvBigCount.setText(charts.size() + " 个榜单");
                binding.empty.setVisibility(charts.isEmpty() ? View.VISIBLE : View.GONE);
                if (charts.isEmpty()) {
                    binding.empty.setText(R.string.wave2_chart_empty);
                    binding.empty.setOnClickListener(v -> loadCharts());
                }
                // 第二波：榜单条目 rawJson 同名字段映射（有源给值、无源留空；详情曲目数不冒充榜总曲数）
                for (MusicChart c : charts) ListAdapter.fillChartMetaFromRaw(c);
                // 详情图库预热：列表页一露面就从手机侧发请求把各酷我榜的详情图库热好（必须手机侧发才热到用户那个 colo）；
                // 预热不阻塞、不解析结果，点进详情时多半已经热了；非酷我榜在路由层直接跳过
                for (int i = 0; i < charts.size(); i++) {
                    MusicRepository.prewarmKwChart(charts.get(i));
                }
                // 逐榜拉曲目当卡内预览（焦点 3 条、普通 2 条），到一条刷一条；失败的卡预览区留空，不编假数据
                for (int i = 0; i < charts.size(); i++) {
                    final MusicChart c = charts.get(i);
                    final int idx = i;
                    Task.execute(() -> {
                        List<MusicTrack> r = MusicRepository.chartTracks(c);
                        runOnUiThread(() -> {
                            if (!mode.equals("charts")) return;
                            chartPreviews.put(c.platform + ":" + c.id, r == null ? new ArrayList<>() : r);
                            adapter.notifyItemChanged(idx == 0 ? 0 : idx + 1);
                        });
                    });
                }
            });
        });
    }

    /** 第 3 项：榜单详情开新页（系统返回回榜单列表；本页不放自己的返回箭头） */
    private void openChartDetail(MusicChart chart) {
        Intent i = new Intent(this, MusicListActivity.class);
        i.putExtra("mode", "chartDetail");
        i.putExtra("title", chart.name);
        i.putExtra("chartPlatform", chart.platform);
        i.putExtra("chartId", chart.id);
        i.putExtra("chartCover", chart.cover);
        // 第二波：英雄卡描述／更新频率透传（源没给则空，详情页不编）
        i.putExtra("chartDesc", chart.description == null ? "" : chart.description);
        i.putExtra("chartFreq", chart.updateFrequency == null ? "" : chart.updateFrequency);
        startActivity(i);
    }

    private void loadChartDetail() {
        showLoading();
        binding.tvTitle.setText(bigTitle);
        MusicChart chart = new MusicChart(chartPlatform, chartId, bigTitle);
        chart.cover = chartCover;
        if (KwChartSource.PLATFORM.equals(chartPlatform)) {
            loadKwChartDetail(chart);
            return;
        }
        Task.execute(() -> {
            List<MusicTrack> r = MusicRepository.chartTracks(chart);
            runOnUiThread(() -> {
                tracks.clear();
                if (r != null) tracks.addAll(r);
                adapter.notifyDataSetChanged();
                binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
                if (tracks.isEmpty()) {
                    // 第二波：榜单详情空态改榜单口径（此前套搜索口径「没搜到，去换个词试试」）；点空态重试（失败与空未区分，源不给失败态，见交卷）
                    binding.empty.setText(R.string.wave2_chart_empty);
                    binding.empty.setOnClickListener(v -> loadChartDetail());
                }
            });
        });
    }

    /** 酷我详情首屏不再等整榜：先直连快出文字＋时长（这部分快），封面按片陆续补（先回的片先上图，不等另一片）。
     * 直连都空时走旧路（Worker 分片＋整榜直连回落），行为与改动前一致；QQ 等其它源走上面旧路不动。 */
    private void loadKwChartDetail(MusicChart chart) {
        final String detailId = chartId;
        Task.execute(() -> {
            MusicRepository.startKwChartSlices(chart);
            List<MusicTrack> direct = MusicRepository.chartTracksDirect(chart);
            if (direct == null || direct.isEmpty()) {
                List<MusicTrack> r = MusicRepository.chartTracks(chart);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                    tracks.clear();
                    if (r != null) tracks.addAll(r);
                    adapter.notifyDataSetChanged();
                    binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
                    if (tracks.isEmpty()) {
                        binding.empty.setText(R.string.wave2_chart_empty);
                        binding.empty.setOnClickListener(v -> loadChartDetail());
                    }
                });
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                tracks.clear();
                tracks.addAll(direct);
                adapter.notifyDataSetChanged();
                binding.empty.setVisibility(View.GONE);
            });
            MusicRepository.fetchKwChartCovers(chart, new KwChartSource.CoverListener() {
                @Override
                public void onShard(int pn, List<MusicTrack> shard) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || !detailId.equals(chartId)) return;
                        applyShardCovers(shard);
                    });
                }
            });
        });
    }

    /** 按 rid 把一片 Worker 封面贴到已显示的直连列表上，只刷封面变了的行（先回的片先上图，不等另一片）；
     * 封面为空的片内曲目留素图（与旧按片回落一致）；chartDetail 行 0 号是头，曲目行下标＝曲目号＋1 */
    private void applyShardCovers(List<MusicTrack> shard) {
        if (shard == null || shard.isEmpty() || tracks.isEmpty()) return;
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < tracks.size(); i++) {
            String id = tracks.get(i).id;
            if (id != null && !id.isEmpty()) index.put(id, i);
        }
        for (MusicTrack s : shard) {
            if (s == null || s.id == null || s.cover == null || s.cover.isEmpty()) continue;
            Integer idx = index.get(s.id);
            if (idx == null) continue;
            MusicTrack cur = tracks.get(idx);
            if (s.cover.equals(cur.cover)) continue;
            cur.cover = s.cover;
            adapter.notifyItemChanged(idx + 1);
        }
    }

    /** 第 3 项：榜单卡玻璃色调（跟随封面取色：主色向白混 85% 成淡染底；取色失败保持默认白卡） */
    private void tintChartCard(View cardRoot, MusicChart chart, int cornerDp) {
        String key = chart.platform + ":" + chart.id;
        cardRoot.setTag(key);
        if (chart.cover == null || chart.cover.isEmpty()) return;
        ImgUtil.load(chart.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                if (!key.equals(cardRoot.getTag())) return;
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    int r = (int) (android.graphics.Color.red(dom) * 0.15f + 255 * 0.85f);
                    int g = (int) (android.graphics.Color.green(dom) * 0.15f + 255 * 0.85f);
                    int b = (int) (android.graphics.Color.blue(dom) * 0.15f + 255 * 0.85f);
                    int tinted = android.graphics.Color.rgb(r, g, b);
                    runOnUiThread(() -> {
                        if (!key.equals(cardRoot.getTag())) return;
                        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                        d.setColor(tinted);
                        d.setStroke(dp(1), 0x14000000);
                        d.setCornerRadius(dp(cornerDp));
                        cardRoot.setBackground(d);
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 第 9 项：猜你喜欢英雄卡跟随首张封面取色淡染（榜单 tintChartCard 同款口径，圆角 20dp，失败回落白卡） */
    private void tintGuessHero(View cardRoot) {
        String key = "guess";
        cardRoot.setTag(key);
        if (tracks.isEmpty() || tracks.get(0).cover == null || tracks.get(0).cover.isEmpty()) return;
        ImgUtil.load(tracks.get(0).cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                if (!key.equals(cardRoot.getTag())) return;
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    int r = (int) (android.graphics.Color.red(dom) * 0.15f + 255 * 0.85f);
                    int g = (int) (android.graphics.Color.green(dom) * 0.15f + 255 * 0.85f);
                    int b = (int) (android.graphics.Color.blue(dom) * 0.15f + 255 * 0.85f);
                    int tinted = android.graphics.Color.rgb(r, g, b);
                    runOnUiThread(() -> {
                        if (!key.equals(cardRoot.getTag())) return;
                        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                        d.setColor(tinted);
                        d.setStroke(dp(1), 0x14000000);
                        d.setCornerRadius(dp(20));
                        cardRoot.setBackground(d);
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    private void loadFavorites() {
        showLoading();
        MusicLibrary.favorites(list -> {
            favHistAll.clear();
            if (list != null) favHistAll.addAll(list);
            refreshFavHistDisplay();
        });
    }

    private void loadHistory() {
        showLoading();
        favHistAll.clear();
        try {
            favHistAll.addAll(com.fongmi.android.tv.music.MusicStore.loadRecent());
        } catch (Throwable ignored) {
        }
        refreshFavHistDisplay();
    }

    /** 第 6 项：收藏／历史显示刷新（英雄卡＋搜索过滤＋排序＋续播卡；空态用 GZ 文案） */
    private void refreshFavHistDisplay() {
        boolean fav = mode.equals("favorite");
        binding.favHistHero.setVisibility(View.VISIBLE);
        binding.tvFavHistLabel.setVisibility(fav ? View.VISIBLE : View.GONE);
        // 本页对照修：GZ 收藏英雄卡标题是「我喜欢的音乐」，不是导航用的「收藏」
        binding.tvFavHistTitle.setText(fav ? "我喜欢的音乐" : bigTitle);
        binding.tvFavHistCount.setText(favHistAll.size() + " 首");
        binding.btnFavHistClear.setVisibility(fav ? View.GONE : View.VISIBLE);
        binding.btnFavHistMulti.setText(favHistMulti ? "取消多选" : "多选");
        binding.etFavHistSearch.setHint(fav ? "搜索喜欢的歌曲" : "搜索历史歌曲");
        // 拼贴：前 4 首的封面（没封面的格子留灰底）
        ImageView[] cells = {binding.ivMosaic0, binding.ivMosaic1, binding.ivMosaic2, binding.ivMosaic3};
        for (int i = 0; i < cells.length; i++) {
            ImageView iv = cells[i];
            String cover = i < favHistAll.size() ? favHistAll.get(i).cover : null;
            if (cover == null || cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(iv).clear(iv);
                } catch (Throwable ignored) {
                }
                iv.setImageDrawable(null);
                iv.setBackgroundColor(0xFFE5E5EA);
            } else {
                iv.setBackgroundColor(0x00000000);
                ImgUtil.loadRounded(favHistAll.get(i).displayTitle(), cover, iv);
            }
        }
        // 续播卡：收藏页＝最近播放里第一首在收藏里的；历史页＝最近播放第一首（无则不显）
        favHistResume = null;
        try {
            List<MusicTrack> recent = com.fongmi.android.tv.music.MusicStore.loadRecent();
            if (fav) {
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (MusicTrack t : favHistAll) if (t.id != null) ids.add(t.id);
                for (MusicTrack r : recent) {
                    if (r.id != null && ids.contains(r.id)) {
                        favHistResume = r;
                        break;
                    }
                }
            } else if (!recent.isEmpty()) {
                favHistResume = recent.get(0);
            }
        } catch (Throwable ignored) {
        }
        if (favHistResume != null) {
            binding.favHistResume.setVisibility(View.VISIBLE);
            MusicTrack cur = MusicController.get().current();
            boolean isCur = cur != null && cur.id != null && cur.id.equals(favHistResume.id);
            // 本页对照修：在播/暂停前缀只在续播曲就是当前曲时加；不是当前曲时
            // 原先一律写「已暂停 ·」是错的（没在播也称暂停）
            String prefix = !isCur ? ""
                    : (MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
            binding.tvFavHistResumeTitle.setText(prefix + favHistResume.displayTitle());
            binding.tvFavHistResumeState.setText("上次听到这首"
                    + (favHistResume.displayArtist().isEmpty() ? "" : " · " + favHistResume.displayArtist()));
        } else {
            binding.favHistResume.setVisibility(View.GONE);
        }
        // 搜索过滤＋排序
        List<MusicTrack> show = new ArrayList<>();
        for (MusicTrack t : favHistAll) {
            if (!favHistSearchKw.isEmpty()) {
                String kw = favHistSearchKw.toLowerCase(java.util.Locale.getDefault());
                String title = t.title == null ? "" : t.title.toLowerCase(java.util.Locale.getDefault());
                String artist = t.artist == null ? "" : t.artist.toLowerCase(java.util.Locale.getDefault());
                if (!title.contains(kw) && !artist.contains(kw)) continue;
            }
            show.add(t);
        }
        if (favHistSort == 2) {
            Collections.sort(show, java.util.Comparator.comparing(t -> t.title == null ? "" : t.title));
        } else if (fav) {
            if (favHistSort == 0) Collections.sort(show, (a, b) -> Long.compare(b.favoriteAddedTime, a.favoriteAddedTime));
            else Collections.sort(show, (a, b) -> Long.compare(a.favoriteAddedTime, b.favoriteAddedTime));
        } else {
            // 历史：0 最近播放＝loadRecent 顺序（已是最近在前），1 最早播放＝倒序
            if (favHistSort == 1) Collections.reverse(show);
        }
        tracks.clear();
        tracks.addAll(show);
        adapter.notifyDataSetChanged();
        // 第二波：列表区头（瓜子 SectionHeader：历史「全部记录」／搜时「搜索结果」，收藏「全部歌曲」／搜时「搜索结果」；
        // 区头放英雄卡块内静态行，不进 adapter——fav/hist 列表无区头项，trackIndex 不动，干净）
        if (binding.tvFavHistSection != null) {
            if (tracks.isEmpty()) {
                binding.tvFavHistSection.setVisibility(View.GONE);
            } else {
                binding.tvFavHistSection.setVisibility(View.VISIBLE);
                binding.tvFavHistSection.setText(fav
                        ? (favHistSearchKw.isEmpty() ? getString(R.string.wave2_section_all_songs) : getString(R.string.wave2_section_search_result))
                        : (favHistSearchKw.isEmpty() ? getString(R.string.wave2_section_all_records) : getString(R.string.wave2_section_search_result)));
            }
        }
        if (tracks.isEmpty()) {
            binding.empty.setVisibility(View.VISIBLE);
            // 本页对照修：搜不到时显 GZ 搜不到文案，不许再套用「还没有喜欢的歌曲」空态
            if (!favHistSearchKw.isEmpty()) {
                // 第二波：历史搜不到文案抠到瓜子 HistoryScreenKt EmptyHistoryHint，与收藏同款两行（此前历史搜不到错套空态）
                binding.empty.setText("未找到匹配歌曲\n换个关键词试试歌名、歌手或专辑");
            } else {
                binding.empty.setText(fav
                        ? "还没有喜欢的歌曲\n播放时点亮爱心，它们会出现在这里"
                        : "还没有播放记录\n听歌后这里会记录你的播放历史");
            }
        } else {
            binding.empty.setVisibility(View.GONE);
        }
        refreshFavHistMultiText();
    }

    /** 第 6 项：收藏／历史排序弹层（GZ：收藏 3 项／历史 3 项＋当前项文案「当前：…」） */
    private void showFavHistSortDialog() {
        boolean fav = mode.equals("favorite");
        String[] items = fav
                ? new String[]{"最近收藏", "最早收藏", "歌曲名称"}
                : new String[]{"最近播放", "最早播放", "歌曲名称"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(fav ? "收藏排序" : "历史排序")
                .setMessage("当前：" + items[favHistSort])
                .setSingleChoiceItems(items, favHistSort, (d, which) -> {
                    favHistSort = which;
                    d.dismiss();
                    refreshFavHistDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 第 6 项：收藏／历史多选态切换（GZ：选中圆圈 24dp＋「已选 N 首」；工具条复用歌单页口径：删除所选／取消） */
    private void setFavHistMulti(boolean on) {
        favHistMulti = on;
        if (!on) favHistSelected.clear();
        binding.btnFavHistMulti.setText(on ? "取消多选" : "多选");
        if (on) {
            binding.btnPlayAll.setVisibility(View.VISIBLE);
            binding.btnSort.setVisibility(View.GONE);
            binding.tvPlayAll.setText("删除所选 (" + favHistSelected.size() + ")");
            binding.btnPlayAll.setOnClickListener(v -> deleteFavHistSelected());
        } else {
            binding.btnPlayAll.setVisibility(View.GONE);
            binding.btnSort.setVisibility(View.VISIBLE);
            binding.btnPlayAll.setOnClickListener(v -> shufflePlay());
        }
        refreshFavHistMultiText();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshFavHistMultiText() {
        if (favHistMulti) binding.tvPlayAll.setText("删除所选 (" + favHistSelected.size() + ")");
    }

    private void deleteFavHistSelected() {
        if (favHistSelected.isEmpty()) {
            setFavHistMulti(false);
            return;
        }
        List<MusicTrack> sel = new ArrayList<>();
        for (MusicTrack t : favHistAll) if (t.id != null && favHistSelected.contains(t.id)) sel.add(t);
        if (mode.equals("favorite")) {
            final int[] left = {sel.size()};
            for (MusicTrack t : sel) {
                MusicLibrary.setFavorite(t, false, () -> {
                    if (--left[0] <= 0) {
                        setFavHistMulti(false);
                        loadFavorites();
                    }
                });
            }
        } else {
            com.fongmi.android.tv.music.MusicStore.removeRecentByIds(new java.util.HashSet<>(favHistSelected));
            setFavHistMulti(false);
            loadHistory();
        }
    }

    private void loadSiteTracks() {
        showLoading();
        Task.execute(() -> {
            MusicSite target = null;
            for (MusicSite s : MusicRepository.sites()) {
                if (s.platform.equals(sitePlatform)) {
                    target = s;
                    break;
                }
            }
            List<MusicTrack> r = target == null ? new ArrayList<>() : MusicRepository.tracks(target);
            runOnUiThread(() -> showTracks(r));
        });
    }

    private void loadSheetTracks() {
        showLoading();
        // 歌单详情页顶栏标题＝歌单名（英雄卡里也有名；大标题行在 sheet 模式隐藏，见 load()）
        binding.tvTitle.setText(bigTitle);
        binding.btnSheetMore.setVisibility(View.VISIBLE);
        MusicLibrary.playlistTracks(playlistId, list -> {
            sheetAll.clear();
            if (list != null) sheetAll.addAll(list);
            refreshSheetDisplay();
        });
    }

    /** 第 1 项：歌单详情显示刷新（搜索过滤＋排序；区头 N 首＝当前显示条数） */
    private void refreshSheetDisplay() {
        List<MusicTrack> show = new ArrayList<>();
        for (MusicTrack t : sheetAll) {
            if (!sheetSearchKw.isEmpty()) {
                String kw = sheetSearchKw.toLowerCase(java.util.Locale.getDefault());
                String title = t.title == null ? "" : t.title.toLowerCase(java.util.Locale.getDefault());
                String artist = t.artist == null ? "" : t.artist.toLowerCase(java.util.Locale.getDefault());
                if (!title.contains(kw) && !artist.contains(kw)) continue;
            }
            show.add(t);
        }
        if (sheetSort == 1) Collections.sort(show, (a, b) -> Long.compare(b.playlistAddedTime, a.playlistAddedTime));
        else if (sheetSort == 2) Collections.sort(show, (a, b) -> Long.compare(a.playlistAddedTime, b.playlistAddedTime));
        else if (sheetSort == 3) Collections.sort(show, java.util.Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sheetSort == 4) Collections.sort(show, java.util.Comparator.comparing(t -> t.album == null ? "" : t.album));
        else if (sheetSort == 5) Collections.sort(show, java.util.Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        // 原顺序＝findByPlaylist 的 position 顺序，即 sheetAll 本身的顺序，不动
        tracks.clear();
        tracks.addAll(show);
        adapter.notifyDataSetChanged();
        binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        if (tracks.isEmpty()) {
            // 第二波：歌单专属空／搜不到文案（瓜子 PlaylistDetailScreenKt：空「歌单中暂时没有歌曲／这里还很安静」，
            // 搜不到「没有找到「x」／换一个歌名、歌手或专辑试试」；加载态仍走 showLoading 转圈，瓜子加载文案无星辰侧落点，见交卷）
            if (!sheetSearchKw.isEmpty()) {
                binding.empty.setText("没有找到「" + sheetSearchKw + "」\n换一个歌名、歌手或专辑试试");
            } else {
                binding.empty.setText("歌单中暂时没有歌曲\n这里还很安静");
            }
            binding.empty.setOnClickListener(v -> loadSheetTracks());
        } else {
            binding.empty.setOnClickListener(null);
        }
    }

    /** 第 1 项：歌单详情页顶栏 ⋮ 菜单（GZ PlaylistMoreButton：刷新歌单／随机播放／搜索歌曲／排序歌曲／分享歌单；多选是星辰侧入口） */
    private void showSheetTopMenu() {
        List<String> opts = new ArrayList<>();
        opts.add("刷新歌单");
        opts.add("随机播放");
        opts.add("搜索歌曲");
        opts.add("排序歌曲");
        opts.add("分享歌单");
        opts.add(sheetMulti ? "取消多选" : "多选");
        MusicOptionsDialog.show(this, bigTitle, opts, -1, idx -> {
            if (idx == 0) loadSheetTracks();
            else if (idx == 1) shufflePlay();
            else if (idx == 2) {
                sheetSearchShown = !sheetSearchShown;
                binding.etSheetSearch.setVisibility(sheetSearchShown ? View.VISIBLE : View.GONE);
                if (!sheetSearchShown) {
                    sheetSearchKw = "";
                    binding.etSheetSearch.setText("");
                    refreshSheetDisplay();
                } else {
                    binding.etSheetSearch.requestFocus();
                }
            } else if (idx == 3) showSheetSortDialog();
            else if (idx == 4) shareSheet();
            else if (idx == 5) setSheetMulti(!sheetMulti);
        });
    }

    /** 第 1 项：歌单排序弹层（GZ：首项固定「歌单原顺序」，其后 5 项按 p66 枚举顺序） */
    private void showSheetSortDialog() {
        String[] items = {"歌单原顺序", "加入歌单时间从新到旧", "加入歌单时间从旧到新",
                "按歌曲名排序", "按专辑名排序", "按歌手名排序"};
        MusicOptionsDialog.show(this, "排序方式", java.util.Arrays.asList(items), sheetSort, idx -> {
            sheetSort = idx;
            refreshSheetDisplay();
        });
    }

    /** 第 1 项：分享歌单（GZ 分享歌单项；星辰侧用系统分享发歌单名＋曲目文本，无云端） */
    private void shareSheet() {
        if (sheetAll.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bigTitle).append("\n");
        for (MusicTrack t : sheetAll) {
            sb.append(t.displayTitle());
            if (!t.displayArtist().isEmpty()) sb.append(" - ").append(t.displayArtist());
            sb.append("\n");
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "分享歌单"));
    }

    /** 第 1 项：改封面（GZ 封面表：修改封面／恢复默认封面；「仅本机 · 赞助用户可同步到云端」与同步到云端需账号，星辰侧不建） */
    private void showSheetCoverDialog() {
        MusicOptionsDialog.show(this, "封面", java.util.Arrays.asList("修改封面", "恢复默认封面"), -1, idx -> {
            if (idx == 0) {
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "选择封面"), REQ_SHEET_COVER);
            } else {
                com.fongmi.android.tv.music.MusicStore.setSheetCover(playlistId, null);
                adapter.notifyItemChanged(0);
            }
        });
    }

    /** 第 1 项：歌单多选态切换（GZ 批量态：移除；行尾 24dp 选中圆圈＋已选 N 首口径与收藏／历史多选一致） */
    private void setSheetMulti(boolean on) {
        sheetMulti = on;
        if (!on) sheetSelected.clear();
        adapter.notifyDataSetChanged();
    }

    private void deleteSheetSelected() {
        if (sheetSelected.isEmpty()) {
            setSheetMulti(false);
            return;
        }
        java.util.Set<String> ids = new java.util.HashSet<>(sheetSelected);
        Task.execute(() -> {
            try {
                for (String id : ids) {
                    com.fongmi.android.tv.db.AppDatabase.get().getMusicPlaylistItemDao().delete(playlistId, id);
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                setSheetMulti(false);
                loadSheetTracks();
            });
        });
    }

    private void loadDownloads() {
        showLoading();
        registerDlListener();
        if (binding.downloadStatsCard != null) binding.downloadStatsCard.setVisibility(View.VISIBLE);
        if (binding.downloadBatchBar != null) binding.downloadBatchBar.setVisibility(View.VISIBLE);
        if (binding.tvDlLiveHeader != null) binding.tvDlLiveHeader.setVisibility(View.VISIBLE);
        if (binding.etDlSearch != null) {
            binding.etDlSearch.setVisibility(View.VISIBLE);
            if (!dlSearchWired) {
                dlSearchWired = true;
                binding.etDlSearch.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        dlSearchKw = s == null ? "" : s.toString().trim();
                        refreshDlDisplay();
                    }
                });
            }
        }
        MusicDownloadStore.all(list -> {
            refreshDlTasksData();
            dlAllTracks.clear();
            dlAllTracks.addAll(list);
            refreshDlDisplay();
            refreshDlStats();
        });
    }

    /** 下载已完成列表按搜索词过滤（GZ「搜索已下载歌曲」；进行中任务行不受过滤影响） */
    private void refreshDlDisplay() {
        List<MusicTrack> show = new ArrayList<>();
        for (MusicTrack t : dlAllTracks) {
            if (dlSearchKw.isEmpty()) {
                show.add(t);
                continue;
            }
            String kw = dlSearchKw.toLowerCase(java.util.Locale.getDefault());
            String title = t.title == null ? "" : t.title.toLowerCase(java.util.Locale.getDefault());
            String artist = t.artist == null ? "" : t.artist.toLowerCase(java.util.Locale.getDefault());
            if (title.contains(kw) || artist.contains(kw)) show.add(t);
        }
        showTracks(show);
    }

    private void registerDlListener() {
        if (dlListenerOn) return;
        dlListenerOn = true;
        try {
            com.fongmi.android.tv.utils.download.DownloadManager.get().addListener(dlListener);
        } catch (Throwable ignored) {
        }
    }

    /** 只重建进行中数据（不刷 UI；给 loadDownloads 里 showTracks 之前的空态判断用） */
    private void refreshDlTasksData() {
        dlTasks.clear();
        try {
            dlTasks.addAll(MusicDownloadStore.liveTasks());
        } catch (Throwable ignored) {
        }
    }

    /** 重建进行中数据＋刷列表＋刷统计卡 */
    private void refreshDlTasks() {
        refreshDlTasksData();
        refreshDlStats();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** 下载概览统计卡（GZ：大数字 34sp＋首已下载、副行占用 X 11sp、失败红色 N 首下载失败 11sp；
     * 进行中区标题三态：N 首正在下载／N 首等待继续／暂无进行中的下载） */
    private void refreshDlStats() {
        long size = 0;
        for (MusicTrack t : dlAllTracks) {
            try {
                if (t.localPath != null && !t.localPath.isEmpty()) size += new java.io.File(t.localPath).length();
            } catch (Throwable ignored) {
            }
        }
        if (binding.tvDlBigNumber != null) binding.tvDlBigNumber.setText(String.valueOf(dlAllTracks.size()));
        if (binding.tvDlSub != null) {
            binding.tvDlSub.setText("占用 " + com.fongmi.android.tv.music.MusicCache.formatSize(size));
        }
        int failed = 0;
        int downloading = 0;
        int waiting = 0;
        for (MusicDownloadStore.LiveTask t : dlTasks) {
            if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_ERROR) failed++;
            else if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_DOWNLOADING) downloading++;
            else if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_WAITING
                    || t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_PAUSED) waiting++;
        }
        if (binding.tvDlFailed != null) {
            if (failed > 0) {
                binding.tvDlFailed.setVisibility(View.VISIBLE);
                binding.tvDlFailed.setText(failed + " 首下载失败");
            } else binding.tvDlFailed.setVisibility(View.GONE);
        }
        if (binding.tvDlLiveHeader != null) {
            // 本页对照修：GZ 进行中区三态分开；原先 waiting>0 时一律拼「0 首正在下载 · N 首等待继续」
            if (dlTasks.isEmpty()) binding.tvDlLiveHeader.setText("暂无进行中的下载");
            else if (downloading == 0) binding.tvDlLiveHeader.setText(waiting + " 首等待继续");
            else if (waiting > 0) binding.tvDlLiveHeader.setText(downloading + " 首正在下载 · " + waiting + " 首等待继续");
            else binding.tvDlLiveHeader.setText(downloading + " 首正在下载");
        }
    }

    /** 全选钮文案：无选中＝全选，有选中＝删除所选 (N)（与歌单页多选的工具条互换口径一致） */
    private void refreshDlSelectText() {
        if (binding.btnDlSelect == null) return;
        binding.btnDlSelect.setText(dlSelected.isEmpty() ? "全选" : "删除所选 (" + dlSelected.size() + ")");
    }

    private void toggleDlSelect() {
        if (!dlSelected.isEmpty()) {
            // 已有选中：按删除所选走删除二选一（记录／记录＋文件二选一，整批同选）
            List<MusicTrack> sel = new ArrayList<>();
            for (MusicTrack t : tracks) if (t.id != null && dlSelected.contains(t.id)) sel.add(t);
            if (sel.isEmpty()) {
                dlSelected.clear();
                refreshDlSelectText();
                if (adapter != null) adapter.notifyDataSetChanged();
                return;
            }
            deleteDownloadBatch(sel);
            return;
        }
        for (MusicTrack t : tracks) if (t.id != null) dlSelected.add(t.id);
        refreshDlSelectText();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** 批量删除二选一（GZ 弹层标题「删除下载」＋两项文案：删除本地文件／仅移出列表；
     * 含义与旧的两项一致：删除本地文件＝记录＋文件一并删，仅移出列表＝只删记录、文件留着仍可被本地库扫描） */
    private void deleteDownloadBatch(List<MusicTrack> sel) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除下载")
                .setMessage("删除本地文件：释放存储空间，音频、封面、歌词一并删除\n仅移出列表：保留本地文件，仍可被本地音乐库扫描播放")
                .setItems(new String[]{"删除本地文件", "仅移出列表"}, (d, which) -> {
                    List<MusicTrack> copy = new ArrayList<>(sel);
                    Runnable done = new Runnable() {
                        int left = copy.size();

                        @Override
                        public void run() {
                            if (--left <= 0) {
                                dlSelected.clear();
                                refreshDlSelectText();
                                loadDownloads();
                            }
                        }
                    };
                    for (MusicTrack t : copy) {
                        if (which == 1) MusicDownloadStore.deleteRecordOnly(t, done);
                        else MusicDownloadStore.deleteLocal(t, done);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 单首删除二选一（GZ 弹层标题「删除下载」＋两项文案；仅 download 模式的 ⋮ 菜单走这里；其它模式仍走旧的 confirmDeleteLocal） */
    private void deleteDownloadTwoChoice(MusicTrack track) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除下载")
                .setMessage("删除本地文件：释放存储空间，音频、封面、歌词一并删除\n仅移出列表：保留本地文件，仍可被本地音乐库扫描播放")
                .setItems(new String[]{"删除本地文件", "仅移出列表"}, (d, which) -> {
                    Runnable done = () -> {
                        Notify.show(R.string.music_deleted);
                        if (mode.equals("download")) loadDownloads();
                    };
                    if (which == 1) MusicDownloadStore.deleteRecordOnly(track, done);
                    else MusicDownloadStore.deleteLocal(track, done);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 外层时长格式化（与 adapter 内 fmt 同款，供详情弹层用） */
    private static String fmtMs(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    /** 下载详情弹层（GZ 字段顺序：文件名／保存位置／格式／下载音质／文件大小／时长／音频规格／
     * 下载时间／原始来源／附加文件／文件 URI；附加文件行值为「封面／歌词／无旁路文件」拼接；
     * 原始来源无平台记录，只显「本地下载」不冒充；下载音质无档位记录，显实测码率） */
    private void showDownloadDetail(MusicTrack track) {
        Task.execute(() -> {
            String createTime = "";
            try {
                com.fongmi.android.tv.bean.MusicDownload d = com.fongmi.android.tv.db.AppDatabase.get()
                        .getMusicDownloadDao().findByTrackId(track.id);
                if (d != null && d.getCreateTime() > 0) {
                    createTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(new java.util.Date(d.getCreateTime()));
                }
            } catch (Throwable ignored) {
            }
            String path = track.localPath == null ? "" : track.localPath;
            java.io.File f = path.isEmpty() ? null : new java.io.File(path);
            String name = f == null ? track.displayTitle() : f.getName();
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) ext = name.substring(dot + 1).toUpperCase(java.util.Locale.US);
            String bitrate = "未知";
            try {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                mmr.setDataSource(path);
                String br = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (br != null && !br.isEmpty()) {
                    long bps = Long.parseLong(br);
                    if (bps > 0) bitrate = (bps / 1000) + "kbps";
                }
                try {
                    mmr.release();
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
            String size = f == null ? "未知"
                    : com.fongmi.android.tv.music.MusicCache.formatSize(f.length());
            String base = dot > 0 ? name.substring(0, dot) : name;
            String parent = f == null ? "" : f.getParent();
            boolean hasCoverSidecar = false;
            boolean hasLrc = false;
            if (parent != null) {
                for (String e : new String[]{"jpg", "png", "jpeg"}) {
                    if (new java.io.File(parent, base + "." + e).exists()) {
                        hasCoverSidecar = true;
                        break;
                    }
                }
                hasLrc = new java.io.File(parent, base + ".lrc").exists();
            }
            // GZ 附加文件行值：封面／歌词／无旁路文件 三者拼接，不是独立的封面、歌词两行
            java.util.List<String> extraParts = new java.util.ArrayList<>();
            if (hasCoverSidecar) extraParts.add("封面");
            if (hasLrc) extraParts.add("歌词");
            if (extraParts.isEmpty()) extraParts.add("无旁路文件");
            String fCreateTime = createTime;
            String fExt = ext.isEmpty() ? "未知" : ext;
            String fBitrate = bitrate;
            String fSize = size;
            // 音频规格：扩展名＋实测码率；都拿不到时按 GZ 空值口径显「设备未提供」
            String fSpec = ext.isEmpty() && bitrate.equals("未知")
                    ? "设备未提供"
                    : (ext.isEmpty() ? "" : ext) + (bitrate.equals("未知") ? "" : " · " + bitrate);
            String fExtras = android.text.TextUtils.join("／", extraParts);
            String fPath = path.isEmpty() ? "未知" : path;
            runOnUiThread(() -> {
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(20);
                box.setPadding(pad, pad, pad, pad);
                String[][] rows = {
                        {"文件名", name},
                        {"保存位置", fPath},
                        {"格式", fExt},
                        {"下载音质", fBitrate},
                        {"文件大小", fSize},
                        {"时长", fmtMs(track.duration)},
                        {"音频规格", fSpec},
                        {"下载时间", fCreateTime.isEmpty() ? "未知" : fCreateTime},
                        {"原始来源", "本地下载"},
                        {"附加文件", fExtras},
                        {"文件 URI", fPath.isEmpty() ? "未知" : "file://" + fPath},
                };
                for (String[] r : rows) {
                    TextView tv = new TextView(this);
                    tv.setText(r[0] + "：" + r[1]);
                    tv.setTextSize(14);
                    tv.setTextColor(0xFF1C1C1E);
                    tv.setPadding(0, dp(3), 0, dp(3));
                    box.addView(tv);
                }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(track.displayTitle())
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    // ================= 本地音乐（设备音频） =================

    private static final int REQ_AUDIO_PERMISSION = 4312;
    private static final int REQ_IMPORT_MUSIC = 4313;
    /** 自动弹授权框只弹一次：永久拒绝后系统会立即回 denied，不护栏就会回调套回调死循环 */
    private boolean permAutoAsked;

    private boolean deviceHeader() {
        return mode.equals("device");
    }

    private String audioPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void loadDevice() {
        bigTitle = getString(R.string.music_device);
        binding.tvTitle.setText(titleForMode());
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, audioPermission())
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // 没权限：不白屏不崩，给提示行＋点一行重新授权，第一次直接弹系统授权框
            tracks.clear();
            cards.clear();
            adapter.notifyDataSetChanged();
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText("");
            binding.empty.setVisibility(View.VISIBLE);
            binding.empty.setText(R.string.music_device_need_permission);
            binding.empty.setOnClickListener(v ->
                    requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION));
            if (!permAutoAsked) {
                permAutoAsked = true;
                requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
            }
            return;
        }
        binding.empty.setOnClickListener(null);
        showLoading();
        Task.execute(() -> {
            List<MusicTrack> list = com.fongmi.android.tv.music.DeviceMusicStore.scan(this);
            runOnUiThread(() -> {
                // 忽略掉的歌（行内「移除」）不进列表，重启仍生效
                java.util.Set<String> ignored = com.fongmi.android.tv.music.MusicStore.loadIgnoredDeviceIds();
                deviceAll.clear();
                for (MusicTrack t : list) {
                    if (t != null && t.id != null && !ignored.contains(t.id)) deviceAll.add(t);
                }
                refreshDeviceDisplay();
            });
        });
    }

    /** 第 8 项：本地音乐视图切换（切视图清掉组筛选） */
    private void setDeviceView(int view) {
        deviceView = view;
        deviceGroupType = -1;
        deviceGroupKey = "";
        refreshDeviceDisplay();
    }

    /** 第 8 项：本地音乐排序弹层（GZ 标题「选择排序方式」＋4 项副文案；升/降序不在弹层内，只在分组区块头右侧切换） */
    private void showDeviceSortDialog() {
        String[] items = {"默认", "最近添加", "时长", "文件大小"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择排序方式")
                .setMessage("切换后列表将按该方式分组展示，升/降序在区块头右侧切换\n\n"
                        + "默认：按添加时间新→旧平铺\n"
                        + "最近添加：按 今天/昨天/本周/更早 分组\n"
                        + "时长：按 短/中/长/超长 分组\n"
                        + "文件大小：按 小/中/大/超大 分组")
                .setSingleChoiceItems(items, deviceSort, (d, which) -> {
                    deviceSort = which;
                    d.dismiss();
                    refreshDeviceDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 第 8 项：本地音乐来源弹层（GZ 4 源里星辰只有本地音乐是真实来源；按扫描的两路来源分：媒体库／导入目录） */
    private void showDeviceSourceDialog() {
        String[] items = {"全部来源", "设备媒体库", "导入目录"};
        MusicOptionsDialog.show(this, "选择音乐库来源", java.util.Arrays.asList(items), deviceSource, idx -> {
            deviceSource = idx;
            binding.tvDeviceSource.setText("来源：" + items[idx].replace("设备媒体库", "媒体库"));
            refreshDeviceDisplay();
        });
    }

    /** 第二波：本地扫描设置入口（瓜子 LibraryScreen：扫描过滤＋文件夹排除管理；
     * 扫描目录单目录模式星辰没有——星辰扫描＝整台设备媒体库＋导入目录，目录控制即排除管理，见交卷差异） */
    private void showDeviceScanDialog() {
        long min = com.fongmi.android.tv.music.MusicStore.getDeviceMinDuration();
        String filterLabel = min == 30000L ? "30 秒" : min == 60000L ? "1 分钟" : min == 120000L ? "2 分钟" : "不过滤";
        int excludedN = com.fongmi.android.tv.music.MusicStore.getDeviceExcludedFolders().size();
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.wave2_device_scan_filter) + "：当前" + filterLabel);
        opts.add(getString(R.string.wave2_device_exclude) + (excludedN > 0 ? "（" + excludedN + " 个）" : ""));
        MusicOptionsDialog.show(this, getString(R.string.wave2_device_scan_settings), opts, -1, idx -> {
            if (idx == 0) showDeviceFilterDialog();
            else if (idx == 1) showDeviceExcludeDialog();
        });
    }

    /** 第二波：扫描过滤（瓜子档：不过滤／30 秒／1 分钟／2 分钟；星辰默认不过滤，改过滤后重扫生效；时长未知（0）的歌不过滤） */
    private void showDeviceFilterDialog() {
        String[] items = {"不过滤", "30 秒", "1 分钟", "2 分钟"};
        long[] values = {0L, 30000L, 60000L, 120000L};
        long cur = com.fongmi.android.tv.music.MusicStore.getDeviceMinDuration();
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == cur) checked = i;
        MusicOptionsDialog.show(this, getString(R.string.wave2_device_scan_filter),
                java.util.Arrays.asList(items), checked, idx -> {
                    com.fongmi.android.tv.music.MusicStore.setDeviceMinDuration(values[idx]);
                    loadDevice();
                });
    }

    /** 第二波：文件夹排除管理（文件夹取自当前库去重；勾选即排除该目录及其子目录，文件不动；保存后重扫生效） */
    private void showDeviceExcludeDialog() {
        java.util.TreeSet<String> folders = new java.util.TreeSet<>();
        for (MusicTrack t : deviceAll) {
            if (t == null || t.localPath == null || t.localPath.isEmpty()) continue;
            String dir = new java.io.File(t.localPath).getParent();
            if (dir != null && !dir.isEmpty()) folders.add(dir);
        }
        if (folders.isEmpty()) {
            Notify.show("暂无文件夹");
            return;
        }
        List<String> list = new ArrayList<>(folders);
        java.util.Set<String> excluded = new java.util.HashSet<>(
                com.fongmi.android.tv.music.MusicStore.getDeviceExcludedFolders());
        boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) checked[i] = excluded.contains(list.get(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wave2_device_exclude)
                .setMultiChoiceItems(list.toArray(new String[0]), checked, (d, which, isChecked) -> {
                    if (isChecked) excluded.add(list.get(which));
                    else excluded.remove(list.get(which));
                })
                .setPositiveButton("保存", (d, w) -> {
                    com.fongmi.android.tv.music.MusicStore.setDeviceExcludedFolders(excluded);
                    loadDevice();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 第二波：本地「移除」恢复入口（T6）：忽略集非空时才显 pill；恢复只清忽略集，文件不动，重扫后回列表 */
    private void showDeviceRemovedDialog() {
        int n = com.fongmi.android.tv.music.MusicStore.loadIgnoredDeviceIds().size();
        if (n <= 0) {
            Notify.show("没有已移除的歌曲");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("已移除歌曲")
                .setMessage("共 " + n + " 首被移除（文件未删），恢复后重新出现在本地音乐里。")
                .setPositiveButton(R.string.wave2_device_restore_all, (d, w) -> {
                    com.fongmi.android.tv.music.MusicStore.clearIgnoredDeviceIds();
                    Notify.show(R.string.wave2_device_restored);
                    loadDevice();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean isLibTrack(MusicTrack t) {
        return t != null && t.id != null && t.id.contains(":lib:");
    }

    private static long fileMtime(MusicTrack t) {
        try {
            if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                return new java.io.File(t.localPath).lastModified();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static long fileSizeOf(MusicTrack t) {
        try {
            if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                return new java.io.File(t.localPath).length();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static String groupVal(String s) {
        return s == null || s.trim().isEmpty() ? "" : s.trim();
    }

    private static String folderOf(MusicTrack t) {
        try {
            if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                java.io.File parent = new java.io.File(t.localPath).getParentFile();
                if (parent != null && parent.getName() != null && !parent.getName().isEmpty()) {
                    return parent.getName();
                }
            }
        } catch (Throwable ignored) {
        }
        return "未知文件夹";
    }

    /** 第 8 项：本地行尾徽标文案（真实格式：只显编码名，flac＝FLAC，其余显扩展名大写；取不到扩展名则不显） */
    private static String deviceBadge(MusicTrack t) {
        String path = t == null ? null : t.localPath;
        if (path == null || path.isEmpty()) path = t == null ? null : t.title;
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot >= path.length() - 1) return "";
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.US);
        if (ext.equals("flac")) return "FLAC";
        return ext.toUpperCase(java.util.Locale.US);
    }

    /** 第 8 项：本地音乐显示刷新（歌曲视图：平铺／分组；组视图：专辑／歌手／文件夹卡） */
    private void refreshDeviceDisplay() {
        if (binding.deviceViewBar != null) binding.deviceViewBar.setVisibility(View.VISIBLE);
        // 第三波 R6：本地页内搜索框显隐＋监听（只滤本地库；TextWatcher 只改词并重刷，不触发全局搜索）
        if (binding.etDeviceSearch != null) {
            binding.etDeviceSearch.setVisibility(View.VISIBLE);
            if (!deviceSearchWired) {
                deviceSearchWired = true;
                binding.etDeviceSearch.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        deviceSearchKw = s == null ? "" : s.toString().trim();
                        refreshDeviceDisplay();
                    }
                });
            }
        }
        // 第二波：本地扫描工具条（扫描设置常显；已移除 pill 仅忽略集非空时显，计数实时取）
        if (binding.deviceToolsBar != null) binding.deviceToolsBar.setVisibility(View.VISIBLE);
        if (binding.tvDeviceRemoved != null) {
            int removed = com.fongmi.android.tv.music.MusicStore.loadIgnoredDeviceIds().size();
            binding.tvDeviceRemoved.setVisibility(removed > 0 ? View.VISIBLE : View.GONE);
            if (removed > 0) binding.tvDeviceRemoved.setText(getString(R.string.wave2_device_removed, removed));
        }
        // 视图 chips 选中态（选中红字加粗，其余默认色）
        TextView[] chips = {binding.tvDeviceViewSongs, binding.tvDeviceViewAlbums,
                binding.tvDeviceViewArtists, binding.tvDeviceViewFolders};
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            boolean sel = i == deviceView;
            chips[i].setTextColor(sel ? 0xFFFA243C : 0xFF1C1C1E);
            chips[i].getPaint().setFakeBoldText(sel);
        }
        if (deviceGroupType >= 0) {
            binding.deviceFilterBar.setVisibility(View.VISIBLE);
            String label = deviceGroupType == 0 ? "专辑：" : deviceGroupType == 1 ? "歌手：" : "文件夹：";
            binding.deviceFilterBar.setText(label + deviceGroupKey + " ×");
        } else {
            binding.deviceFilterBar.setVisibility(View.GONE);
        }
        List<MusicTrack> base = new ArrayList<>();
        // 建议项：本地搜索转小写用 Locale.ROOT，土耳其语 locale 下 I 转小写会变 ı、对不上。
        String dkw = deviceSearchKw.toLowerCase(java.util.Locale.ROOT);
        for (MusicTrack t : deviceAll) {
            if (deviceSource == 1 && isLibTrack(t)) continue;
            if (deviceSource == 2 && !isLibTrack(t)) continue;
            if (deviceGroupType == 0 && !groupVal(t.album).equals(deviceGroupKey)) continue;
            if (deviceGroupType == 1 && !artistDisplay(groupVal(t.artist)).equals(deviceGroupKey)) continue;
            if (deviceGroupType == 2 && !folderOf(t).equals(deviceGroupKey)) continue;
            if (!dkw.isEmpty()) {
                String hay = ((t.title == null ? "" : t.title) + " " + (t.artist == null ? "" : t.artist)
                        + " " + (t.album == null ? "" : t.album)).toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(dkw)) continue;
            }
            base.add(t);
        }
        deviceSectionPos.clear();
        deviceSectionTitles.clear();
        deviceSectionCounts.clear();
        cards.clear();
        if (deviceView == DEVICE_VIEW_SONGS) {
            List<MusicTrack> ordered;
            if (deviceGroupType >= 0 || deviceSort == 0) ordered = sortDeviceFlat(base);
            else ordered = groupDevice(base);
            tracks.clear();
            tracks.addAll(ordered);
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
            binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
            // 本页对照修：本地空态用 GZ 两行文案，点空态重扫（对应 GZ「重新扫描」）；
            // 第三波 R6：页内搜索滤空时不套“尚未添加”文案，改写搜不到。
            if (tracks.isEmpty()) {
                if (!deviceSearchKw.isEmpty()) {
                    binding.empty.setText("没有找到本地音乐");
                    binding.empty.setOnClickListener(null);
                } else {
                    binding.empty.setText("尚未添加本地音乐\n选择音乐文件夹 / 扫描整台设备");
                    binding.empty.setOnClickListener(v -> loadDevice());
                }
            }
        } else {
            java.util.Map<String, List<MusicTrack>> groups = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (MusicTrack t : base) {
                String key = deviceView == DEVICE_VIEW_ALBUMS
                        ? (groupVal(t.album).isEmpty() ? "未知专辑" : groupVal(t.album))
                        : deviceView == DEVICE_VIEW_ARTISTS
                        ? artistDisplay(groupVal(t.artist))
                        : folderOf(t);
                List<MusicTrack> g = groups.get(key);
                if (g == null) {
                    g = new ArrayList<>();
                    groups.put(key, g);
                }
                g.add(t);
            }
            for (java.util.Map.Entry<String, List<MusicTrack>> e : groups.entrySet()) {
                // 本地页回炉：组卡马赛克封面＝组内成员前 4 张不重复封面（本地扫描已带封面，无需另查）。
                java.util.List<String> covers = new java.util.ArrayList<>();
                for (MusicTrack t : e.getValue()) {
                    if (t != null && t.cover != null && !t.cover.isEmpty() && !covers.contains(t.cover)) {
                        covers.add(t.cover);
                        if (covers.size() >= 4) break;
                    }
                }
                cards.add(new DeviceGroupCard(deviceView, e.getKey(), e.getValue().size(), covers));
            }
            tracks.clear();
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText(cards.size() + " 组");
            binding.empty.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
            // 复核必-11：组视图空态照歌曲视图分支写——搜词非空时是搜不到，不挂重扫点击。
            if (cards.isEmpty()) {
                if (!deviceSearchKw.isEmpty()) {
                    binding.empty.setText("没有找到本地音乐");
                    binding.empty.setOnClickListener(null);
                } else {
                    binding.empty.setText("尚未添加本地音乐\n选择音乐文件夹 / 扫描整台设备");
                    binding.empty.setOnClickListener(v -> loadDevice());
                }
            }
        }
        // M12：本地组视图（专辑／歌手／文件夹卡）下没有歌曲行，顺序播放钮同理隐藏；歌曲视图恢复显示。
        // 随机播在组视图有专路（shufflePlay 按全库打乱），不在此动。
        binding.btnPlaySeq.setVisibility(deviceView == DEVICE_VIEW_SONGS ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** 第 8 项：本地歌曲平铺排序（默认／组筛选内：按排序键＋当前方向；默认＝添加时间新→旧） */
    private List<MusicTrack> sortDeviceFlat(List<MusicTrack> base) {
        List<MusicTrack> out = new ArrayList<>(base);
        java.util.Comparator<MusicTrack> cmp;
        if (deviceSort == 2) cmp = java.util.Comparator.comparingLong(t -> t.duration);
        else if (deviceSort == 3) cmp = java.util.Comparator.comparingLong(MusicListActivity::fileSizeOf);
        else cmp = java.util.Comparator.comparingLong(MusicListActivity::fileMtime);
        if (!deviceAsc) cmp = cmp.reversed();
        Collections.sort(out, cmp);
        return out;
    }

    /** 第 8 项：本地歌曲分组（最近添加／时长／文件大小；组顺序跟方向走；同时填区块头位置表） */
    private List<MusicTrack> groupDevice(List<MusicTrack> base) {
        java.util.Map<String, List<MusicTrack>> groups = new java.util.LinkedHashMap<>();
        for (MusicTrack t : base) {
            String key;
            if (deviceSort == 1) key = recentBucket(fileMtime(t));
            else if (deviceSort == 2) key = durationBucket(t.duration);
            else key = sizeBucket(fileSizeOf(t));
            List<MusicTrack> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(t);
        }
        List<String> order;
        if (deviceSort == 1) order = java.util.Arrays.asList("今天", "昨天", "本周", "更早");
        else if (deviceSort == 2) order = java.util.Arrays.asList("短", "中", "长", "超长");
        else order = java.util.Arrays.asList("小", "中", "大", "超大");
        if (!deviceAsc) Collections.reverse(order);
        List<MusicTrack> flat = new ArrayList<>();
        int pos = 1; // 设备头占 1 位（导入行）
        for (String key : order) {
            List<MusicTrack> g = groups.get(key);
            if (g == null || g.isEmpty()) continue;
            java.util.Comparator<MusicTrack> cmp;
            if (deviceSort == 1) cmp = java.util.Comparator.comparingLong(MusicListActivity::fileMtime);
            else if (deviceSort == 2) cmp = java.util.Comparator.comparingLong(t -> t.duration);
            else cmp = java.util.Comparator.comparingLong(MusicListActivity::fileSizeOf);
            if (!deviceAsc) cmp = cmp.reversed();
            Collections.sort(g, cmp);
            deviceSectionPos.add(pos);
            deviceSectionTitles.add(key);
            deviceSectionCounts.add(g.size());
            pos += 1 + g.size();
            flat.addAll(g);
        }
        return flat;
    }

    /** 最近添加分组桶（GZ 只给分组名未给边界；星辰侧自定：今天／昨天／过去 7 天内／更早） */
    private static String recentBucket(long mtime) {
        if (mtime <= 0) return "更早";
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(mtime);
        boolean sameDay = now.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                && now.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR);
        if (sameDay) return "今天";
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        boolean isYesterday = yesterday.get(java.util.Calendar.YEAR) == day.get(java.util.Calendar.YEAR)
                && yesterday.get(java.util.Calendar.DAY_OF_YEAR) == day.get(java.util.Calendar.DAY_OF_YEAR);
        if (isYesterday) return "昨天";
        long diff = System.currentTimeMillis() - mtime;
        if (diff < 7L * 24 * 60 * 60 * 1000) return "本周";
        return "更早";
    }

    /** 时长分组桶（GZ 阈值未抠到；星辰侧自定：短＜3 分钟／中 3–6 分钟／长 6–10 分钟／超长＞10 分钟） */
    private static String durationBucket(long ms) {
        if (ms < 180 * 1000) return "短";
        if (ms < 360 * 1000) return "中";
        if (ms < 600 * 1000) return "长";
        return "超长";
    }

    /** 文件大小分组桶（GZ 阈值未抠到；星辰侧自定：小＜5MB／中 5–20MB／大 20–100MB／超大＞100MB） */
    private static String sizeBucket(long bytes) {
        if (bytes < 5L * 1024 * 1024) return "小";
        if (bytes < 20L * 1024 * 1024) return "中";
        if (bytes < 100L * 1024 * 1024) return "大";
        return "超大";
    }

    /** 第 8 项：本地组卡（专辑／歌手／文件夹；点卡进该组的歌曲列） */
    private static class DeviceGroupCard {
        final int view;
        final String key;
        final int count;
        /** 组内成员前 4 张不重复封面（本地页回炉：组卡马赛克用；空表＝无封面回落图标）。 */
        final java.util.List<String> covers;

        DeviceGroupCard(int view, String key, int count, java.util.List<String> covers) {
            this.view = view;
            this.key = key;
            this.count = count;
            this.covers = covers == null ? new java.util.ArrayList<>() : covers;
        }
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION && deviceHeader()) {
            // 无论授权与否都刷新：给了就加载，拒了就留提示行（不许静默）
            loadDevice();
        }
    }

    /** 导入加密歌曲：系统文件选择器多选（QQ 的 mflac 系、酷狗 kgm 系），解密落 music_library/ */
    private void pickImportFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, getString(R.string.music_import_encrypted)), REQ_IMPORT_MUSIC);
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        String p = uri.getPath();
        if (p == null) return "未知文件";
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private void runImport(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        Notify.show(R.string.music_device_importing);
        Task.execute(() -> {
            java.io.File dir = com.fongmi.android.tv.music.DeviceMusicStore.libraryDir(this);
            dir.mkdirs();
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (Uri u : uris) {
                String name = displayName(u);
                try {
                    byte[] bytes = readAllCapped(u, 200L * 1024 * 1024);
                    com.fongmi.android.tv.music.MusicDecrypt.Result r =
                            com.fongmi.android.tv.music.MusicDecrypt.decrypt(name, bytes);
                    java.io.File out = uniqueFile(dir, safeName(name) + "." + r.ext);
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        fos.write(r.data);
                    }
                    ok++;
                } catch (Exception e) {
                    failed.add(name + "：" + (e.getMessage() == null ? "失败" : e.getMessage()));
                }
            }
            final int fOk = ok;
            final List<String> fFailed = failed;
            runOnUiThread(() -> {
                if (!fFailed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    if (fOk > 0) sb.append("成功 ").append(fOk).append(" 首\n\n");
                    for (String f : fFailed) sb.append(f).append("\n");
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.music_import_encrypted)
                            .setMessage(sb.toString().trim())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    Notify.show("导入成功 " + fOk + " 首");
                }
                loadDevice();
            });
        });
    }

    private static String safeName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return base.isEmpty() ? "imported" : base;
    }

    private static java.io.File uniqueFile(java.io.File dir, String name) {
        java.io.File f = new java.io.File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            java.io.File c = new java.io.File(dir, base + "_" + i + ext);
            if (!c.exists()) return c;
        }
    }

    private void bindImportRow(ListAdapter.CardHolder h) {
        // 回收行复位（与 bindCard 同口径）：导入行只用图标，马赛克位一律藏。
        h.icon.setVisibility(View.VISIBLE);
        h.mosaicSingle.setVisibility(View.GONE);
        h.mosaicGrid.setVisibility(View.GONE);
        h.coverBox.setBackgroundResource(0);
        h.coverBox.setClipToOutline(false);
        h.name.setText(R.string.music_import_encrypted);
        h.status.setText("");
        h.icon.setImageResource(R.drawable.ic_music_download);
        h.icon.setBackground(circle(0xFF06B6D4));
        h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        h.itemView.setOnClickListener(v -> pickImportFiles());
    }

    private GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 插件管理：全量列表（停用的也得在，不然关了就找不回来）＋头卡／两按钮／已安装头／排序后列表 */
    /** 第二波：插件分组头占位（adapter 内插项，非插件行；标题取 wave2 串） */
    private static class PluginGroup {
        final String title;

        PluginGroup(String title) {
            this.title = title;
        }
    }

    /** 歌词型判定：复用头卡现有歌词三档（检测证实纯歌词／搜歌不支持／内置歌词预置——预置另归内置组，此处只判前两档） */
    private boolean isLyricPluginSite(MusicSite s) {
        if (s == null) return false;
        MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
        return (t != null && t.lyricOk && !t.audioOk) || s.musicSearch == 2;
    }

    private void loadSites() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            List<MusicSite> ordered = sortSites(sites);
            runOnUiThread(() -> {
                pluginImporting = false;
                cards.clear();
                // 第二波：插件分组头（瓜子 LxPluginScreen 三组，空组不渲染；判定复用头卡现有歌词三档）：
                // 内置歌词＝应用自带歌词预置；其他插件＝歌词型但非预置；其余＝音源插件。组内保持排序后顺序。
                List<MusicSite> audio = new ArrayList<>();
                List<MusicSite> other = new ArrayList<>();
                List<MusicSite> builtinLyric = new ArrayList<>();
                for (MusicSite s : ordered) {
                    if (MusicSourceManager.isBuiltinLyricPreset(s.srcUrl)) builtinLyric.add(s);
                    else if (isLyricPluginSite(s)) other.add(s);
                    else audio.add(s);
                }
                if (!audio.isEmpty()) {
                    cards.add(new PluginGroup(getString(R.string.wave2_plugin_group_audio)));
                    cards.addAll(audio);
                }
                if (!other.isEmpty()) {
                    cards.add(new PluginGroup(getString(R.string.wave2_plugin_group_other)));
                    cards.addAll(other);
                }
                if (!builtinLyric.isEmpty()) {
                    cards.add(new PluginGroup(getString(R.string.wave2_plugin_group_lyric)));
                    cards.addAll(builtinLyric);
                }
                adapter.notifyDataSetChanged();
                // 第 8 项去双标题：sites 模式只留顶部居中“插件管理”，下方大标题行整行隐藏
                // （GZ 页内只有头卡标题“ N 个插件”，没有第二个“插件管理 N 个插件”大标题）
                binding.bigTitleRow.setVisibility(View.GONE);
                refreshPluginHeader(sites);
                refreshPluginSections();
                // P22：sites 模式空态改为空态卡，替代原 empty 文本；非空时两者都隐藏
                binding.empty.setVisibility(View.GONE);
                binding.pluginEmptyCard.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 第三波 B⑦：头卡图标导入中旋转指示（导入结束即停并归零）。 */
    private android.animation.ObjectAnimator pluginHeaderSpin;

    private void refreshPluginHeaderSpin() {
        try {
            if (binding.pluginHeaderIcon == null) return;
            if (pluginImporting) {
                if (pluginHeaderSpin == null) {
                    pluginHeaderSpin = android.animation.ObjectAnimator.ofFloat(
                            binding.pluginHeaderIcon, "rotation", 0f, 360f);
                    pluginHeaderSpin.setDuration(1000);
                    pluginHeaderSpin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    pluginHeaderSpin.start();
                }
            } else if (pluginHeaderSpin != null) {
                pluginHeaderSpin.cancel();
                pluginHeaderSpin = null;
                binding.pluginHeaderIcon.setRotation(0f);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 第三波 B③：运行状态面板（徽章点开；与 C10 卡同源数据：已加载／已启用／当前单选状态／异常名表／进行中态）。
     * 分平台检测行不在此：检测层 MusicPluginTest 未存分平台结果（见交卷），不编分平台行。 */
    private void showRuntimeStatusPanel() {
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        List<MusicSite> sites = MusicSourceManager.get().listSites();
        boolean hasUntested = false;
        for (MusicSite site : sites) {
            if (site.enabled && MusicSourceManager.get().loadPluginTest(site.srcUrl) == null) {
                hasUntested = true;
                break;
            }
        }
        String state;
        if (st.quarantinedRunners > 0) state = "运行异常";
        else if (st.failedPlugins > 0) state = "启动失败";
        else if (st.initializing) state = "启动中";
        else if (st.enabledLocalPlugins == 0) state = "无本地插件";
        else if (hasUntested) state = "未检测";
        else state = "运行正常";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        box.addView(runtimePanelLine("当前状态：" + state));
        box.addView(runtimePanelLine("已加载 " + st.liveRunners + " 个 · 已启用 "
                + st.enabledLocalPlugins + " 个"));
        box.addView(runtimePanelLine(st.initializing ? "正在启动…"
                : st.reloading ? "正在重新加载…" : "进行中：无"));
        if (!st.quarantinedNames.isEmpty()) {
            box.addView(runtimePanelLine("暂时停用 " + st.quarantinedRunners + " 个："
                    + android.text.TextUtils.join("、", st.quarantinedNames)));
        }
        if (!st.failedNames.isEmpty()) {
            box.addView(runtimePanelLine("启动失败 " + st.failedPlugins + " 个："
                    + android.text.TextUtils.join("、", st.failedNames)));
        }
        TextView reload = new TextView(this);
        reload.setText("重新加载插件");
        reload.setTextSize(14);
        reload.setTextColor(0xFFFA243C);
        reload.setPadding(dp(12), dp(8), dp(12), dp(8));
        reload.setOnClickListener(v -> {
            MusicSourceManager.get().reloadBridges();
            refreshPluginSections();
            if (adapter != null) adapter.notifyDataSetChanged();
            Notify.show("已重新加载");
        });
        box.addView(reload);
        new MaterialAlertDialogBuilder(this)
                .setTitle("插件运行状态")
                .setView(box)
                .setPositiveButton("知道了", null)
                .show();
    }

    private TextView runtimePanelLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF1C1C1E);
        return tv;
    }

    /** 对照表 P2/P3/P23：头卡标题／副标题／徽章（仅 sites 模式；图标用现有 ic_music_note 近似，星辰无拼图图标） */
    private void refreshPluginHeader(List<MusicSite> sites) {
        if (!mode.equals("sites")) return;
        binding.pluginHeaderCard.setVisibility(View.VISIBLE);
        binding.pluginAddRow.setVisibility(View.VISIBLE);
        binding.pluginInstalledHeader.setVisibility(View.VISIBLE);
        int total = sites.size();
        int enabled = 0;
        int lyricCount = 0;
        int tested = 0;
        for (MusicSite s : sites) {
            if (s.enabled) enabled++;
            MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (t != null) tested++;
            // 歌词归类三档：只看检测结果会把未检测的歌词插件全算成音源（实拍“歌词：0”的根因）。
            // ① 检测已证实纯歌词；② 搜歌流已判据该插件不支持搜歌（musicSearch=2，桥声明能力，非空结果推断）；
            // ③ 应用自带歌词预置（歌词网／歌词千寻，种子身份即歌词型）。
            // 用户自加的歌词插件在未检测、未搜歌探查前仍暂计音源——那是未知，不是误标。
            if ((t != null && t.lyricOk && !t.audioOk)
                    || s.musicSearch == 2
                    || MusicSourceManager.isBuiltinLyricPreset(s.srcUrl)) lyricCount++;
        }
        int audioCount = total - lyricCount;
        if (pluginImporting) {
            binding.pluginHeaderTitle.setText(R.string.music_plugin_importing);
        } else if (total == 0) {
            binding.pluginHeaderTitle.setText(R.string.music_plugin_no_plugin);
        } else {
            binding.pluginHeaderTitle.setText(total + " " + getString(R.string.music_plugins));
        }
        if (total == 0) {
            binding.pluginHeaderSub.setText(R.string.music_plugin_import_desc);
        } else if (enabled == 0) {
            binding.pluginHeaderSub.setText(R.string.music_plugin_all_disabled);
        } else {
            binding.pluginHeaderSub.setText(getString(R.string.music_plugin_running) + "：" + enabled
                    + " 音源：" + audioCount + " 歌词：" + lyricCount);
        }
        binding.pluginLocalTitle.setText(pluginImporting ? "导入中…" : getString(R.string.music_plugin_local_title));
        // P7 已安装头计数：N / M 正在工作（M=启用且检测态非 FAILED；未检测计入工作数）
        int working = 0;
        for (MusicSite s : sites) {
            if (!s.enabled) continue;
            MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (t == null || t.state != MusicPluginTest.State.FAILED) working++;
        }
        // GZ 计数顺序（复核纠偏）：字面是“工作数 / 总数 正在工作”，不是总数在前。
        // 未全检测时后缀检测覆盖率——行全是“未检测”时头再称“正在工作”而不提覆盖率，就是实拍的自相矛盾。
        String installed = working + " / " + total + " " + getString(R.string.music_plugin_working);
        if (tested < total) installed += " · 已检测 " + tested + " 个";
        binding.pluginInstalledCount.setText(installed);
        binding.btnPluginSort.setText(pluginSortLabel());
        // 第三波 B⑦：排序钮补 16dp 下拉箭头（drawableEnd，不再用文案里的 ▾ 充数）
        try {
            android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_chev_v2);
            if (arrow != null) {
                arrow.setBounds(0, 0, dp(16), dp(16));
                binding.btnPluginSort.setCompoundDrawables(null, null, arrow, null);
                binding.btnPluginSort.setCompoundDrawablePadding(dp(4));
            }
        } catch (Throwable ignored) {
        }
        // 第三波 B③：运行状态徽章可点，出状态面板
        binding.pluginHeaderBadge.setOnClickListener(v -> showRuntimeStatusPanel());
        refreshPluginHeaderSpin();
        // P23 头卡徽章（仅展示，不弹面板）：优先级 隔离＞失败＞启动中＞无本地＞未检测＞正常。
        // “未检测”指已有启用插件但一个检测结果都没有——此时称“运行正常”与行态打架，改称未检测。
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        boolean hasUntested = false;
        for (MusicSite s : sites) {
            if (s.enabled && MusicSourceManager.get().loadPluginTest(s.srcUrl) == null) {
                hasUntested = true;
                break;
            }
        }
        String badge;
        int badgeColor;
        if (st.quarantinedRunners > 0) {
            badge = "运行异常";
            badgeColor = 0xFFFF3B30;
        } else if (st.failedPlugins > 0) {
            badge = "启动失败";
            badgeColor = 0xFFFF3B30;
        } else if (st.initializing) {
            badge = "启动中";
            badgeColor = 0xFF8E8E93;
        } else if (st.enabledLocalPlugins == 0) {
            badge = "无本地插件";
            badgeColor = 0xFF8E8E93;
        } else if (hasUntested) {
            badge = "未检测";
            badgeColor = 0xFF8E8E93;
        } else {
            badge = "运行正常";
            badgeColor = 0xFF34C759;
        }
        binding.pluginHeaderBadge.setText(badge);
        binding.pluginHeaderBadge.setTextColor(badgeColor);
        // 瓜子头卡徽章＝同色淡底药丸（非描边框）：底色＝徽章色 12% 透明度、满圆角
        android.graphics.drawable.GradientDrawable badgeBg =
                new android.graphics.drawable.GradientDrawable();
        badgeBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(dp(100));
        badgeBg.setColor((badgeColor & 0x00FFFFFF) | 0x1F000000);
        binding.pluginHeaderBadge.setBackground(badgeBg);
    }

    /** 第三波 B⑤：平台排序真做——按 site.platform 分组排序（同平台内保原顺序，稳定排序），
     * 不再是点了没反应的假选项。 */
    // （文案口径见 pluginSortLabel）
    private String pluginSortLabel() {
        switch (MusicStore.getPluginSort()) {
            case MusicStore.PLUGIN_SORT_PLATFORM_COUNT:
                return getString(R.string.music_plugin_sort_platform);
            case MusicStore.PLUGIN_SORT_SPEED:
                return getString(R.string.music_plugin_sort_speed);
            case MusicStore.PLUGIN_SORT_AVAILABLE:
                return getString(R.string.music_plugin_sort_available);
            default:
                return getString(R.string.music_plugin_sort_manual);
        }
    }

    private List<MusicSite> sortSites(List<MusicSite> sites) {
        List<MusicSite> out = new ArrayList<>(sites);
        String sort = MusicStore.getPluginSort();
        if (MusicStore.PLUGIN_SORT_AVAILABLE.equals(sort)) {
            Collections.sort(out, (a, b) -> {
                MusicPluginTest ta = MusicSourceManager.get().loadPluginTest(a.srcUrl);
                MusicPluginTest tb = MusicSourceManager.get().loadPluginTest(b.srcUrl);
                int va = (ta != null && ta.state == MusicPluginTest.State.AVAILABLE) ? 0 : 1;
                int vb = (tb != null && tb.state == MusicPluginTest.State.AVAILABLE) ? 0 : 1;
                return va - vb;
            });
        } else if (MusicStore.PLUGIN_SORT_SPEED.equals(sort)) {
            Collections.sort(out, (a, b) -> Long.compare(pluginSpeedMs(a), pluginSpeedMs(b)));
        }
        if (MusicStore.PLUGIN_SORT_PLATFORM_COUNT.equals(sort)) {
            Collections.sort(out, (a, b) -> {
                String pa = a.platform == null ? "" : a.platform;
                String pb = b.platform == null ? "" : b.platform;
                return pa.compareTo(pb);
            });
        }
        // MANUAL 保持存盘顺序
        return out;
    }

    /** SPEED 拟映射：检测取链耗时升序，未检测（无表或耗时 ≤0）排后 */
    private long pluginSpeedMs(MusicSite s) {
        MusicPluginTest t = MusicSourceManager.get().loadPluginTest(s.srcUrl);
        if (t == null || t.latencyMs <= 0) return Long.MAX_VALUE;
        return t.latencyMs;
    }

    private void showPluginSortDialog() {
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.music_plugin_sort_manual));
        opts.add(getString(R.string.music_plugin_sort_platform));
        opts.add(getString(R.string.music_plugin_sort_speed));
        opts.add(getString(R.string.music_plugin_sort_available));
        String cur = MusicStore.getPluginSort();
        int checked = MusicStore.PLUGIN_SORT_PLATFORM_COUNT.equals(cur) ? 1
                : MusicStore.PLUGIN_SORT_SPEED.equals(cur) ? 2
                : MusicStore.PLUGIN_SORT_AVAILABLE.equals(cur) ? 3 : 0;
        String[] values = {MusicStore.PLUGIN_SORT_MANUAL, MusicStore.PLUGIN_SORT_PLATFORM_COUNT,
                MusicStore.PLUGIN_SORT_SPEED, MusicStore.PLUGIN_SORT_AVAILABLE};
        MusicOptionsDialog.show(this, getString(R.string.music_plugin_installed), opts, checked, idx -> {
            MusicStore.setPluginSort(values[idx]);
            loadSites();
        });
    }

    /** 对照表 P14：手动排序模式下相邻换位＋落盘＋刷新（仅 sites 模式内，存盘顺序即现序） */
    private void movePlugin(MusicSite site, int delta) {
        List<MusicSite> sites = MusicSourceManager.get().listSites();
        int idx = -1;
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i) != null && site.srcUrl != null && site.srcUrl.equals(sites.get(i).srcUrl)) {
                idx = i;
                break;
            }
        }
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= sites.size()) return;
        Collections.swap(sites, idx, to);
        MusicSourceManager.get().saveSites(sites);
        loadSites();
    }

    private static final int REQ_PICK_PLUGIN = 4213;
    private static final int REQ_PICK_LXMC = 4214;

    private void pickLocalFile() {
        // 第三波 B①现状：单文件选择（未开 EXTRA_ALLOW_MULTIPLE），一次只装一个插件文件
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/javascript", "text/javascript", "text/plain"});
        startActivityForResult(Intent.createChooser(i, getString(R.string.music_plugin_local)), REQ_PICK_PLUGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_MUSIC) {
            if (resultCode == RESULT_OK && data != null) runImport(data);
            return;
        }
        if (requestCode == REQ_SHEET_COVER) {
            // 第 1 项：改封面＝把选的图拷到应用文件目录，按歌单 id 索引（不改 Room schema）
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Task.execute(() -> {
                    try {
                        java.io.File dir = new java.io.File(getFilesDir(), "sheet_covers");
                        if (!dir.exists()) dir.mkdirs();
                        java.io.File out = new java.io.File(dir, playlistId + ".jpg");
                        java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                        java.io.OutputStream os = new java.io.FileOutputStream(out);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                        in.close();
                        os.close();
                        com.fongmi.android.tv.music.MusicStore.setSheetCover(playlistId, out.getAbsolutePath());
                        runOnUiThread(() -> adapter.notifyItemChanged(0));
                    } catch (Throwable t) {
                        runOnUiThread(() -> Notify.show("封面设置失败"));
                    }
                });
            }
            return;
        }
        if (requestCode == REQ_PICK_LXMC) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                runLxmcImport(data.getData());
            }
            return;
        }
        if (requestCode != REQ_PICK_PLUGIN || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Task.execute(() -> {
            try {
                byte[] bytes = readAll(uri);
                runOnUiThread(() -> confirmPluginImport(() -> {
                    pluginImporting = true;
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    runPluginImportWithProgress("正在安装（1 个文件）…",
                            () -> MusicSourceManager.get().addLocalSite(bytes));
                }));
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show(e.getMessage() == null || e.getMessage().isEmpty()
                        ? getString(R.string.music_import_failed_retry)
                        : "导入失败：" + e.getMessage()));
            }
        });
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** 带上限的整读（加密导入用）：边读边判，超上限立刻放弃，不许把大文件全灌进内存 */
    private byte[] readAllCapped(Uri uri, long max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > max) throw new Exception("文件太大（超 200MB），跳过");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }


    // ================= C4/C5/C10 插件管理专区 =================

    /** C4：行内钮启动检测（唯一检测入口）；回调回主线程后刷新行态与专区，完成后出分检测面板 */
    private void startPluginTest(MusicSite site) {
        startPluginTest(site, true);
    }

    private void startPluginTest(MusicSite site, boolean showDetail) {
        MusicSourceManager.get().testPlugin(site, new MusicSourceManager.PluginTestCallback() {
            @Override
            public void onTesting() {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }

            @Override
            public void onResult(MusicPluginTest result) {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    refreshPluginSections();
                    if (showDetail) showTestDetail(site, result);
                });
            }
        });
    }

    /** C4 分检测面板：标题“音源检测”／“歌词检测”（18sp），结果分两行出 */
    private void showTestDetail(MusicSite site, MusicPluginTest result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        TextView audioTitle = new TextView(this);
        audioTitle.setText("音源检测");
        audioTitle.setTextSize(18);
        audioTitle.setTextColor(0xFFFFFFFF);
        audioTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(audioTitle);
        TextView audioLine = new TextView(this);
        audioLine.setText(result.audioOk
                ? "可用（取链耗时 " + result.latencyMs + "ms）"
                : "失败" + (result.message == null || result.message.isEmpty() ? "" : "：" + result.message));
        audioLine.setTextSize(14);
        audioLine.setTextColor(result.audioOk ? 0xFF34C759 : 0xFFFF3B30);
        box.addView(audioLine);
        TextView lyricTitle = new TextView(this);
        lyricTitle.setText("歌词检测");
        lyricTitle.setTextSize(18);
        lyricTitle.setTextColor(0xFFFFFFFF);
        lyricTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(lyricTitle, lp);
        TextView lyricLine = new TextView(this);
        if (!result.lyricCapable) {
            lyricLine.setText("该插件无歌词能力，只看音源");
            lyricLine.setTextColor(0xFF8E8E93);
        } else if (result.lyricOk) {
            lyricLine.setText("可用" + (result.preview == null || result.preview.isEmpty() ? "" : "：" + result.preview));
            lyricLine.setTextColor(0xFF34C759);
        } else {
            lyricLine.setText("失败" + (result.message == null || result.message.isEmpty() ? "" : "：" + result.message));
            lyricLine.setTextColor(0xFFFF3B30);
        }
        lyricLine.setTextSize(14);
        box.addView(lyricLine);
        new MaterialAlertDialogBuilder(this)
                .setTitle(site.name == null ? "" : site.name)
                .setView(box)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** C5 开关行＋C4 自动屏蔽行＋C10 运行时状态区（仅插件管理页 sites 模式） */
    private void refreshPluginSections() {
        if (binding.pluginSections == null) return;
        if (!mode.equals("sites")) {
            binding.pluginSections.setVisibility(View.GONE);
            return;
        }
        binding.pluginSections.setVisibility(View.VISIBLE);
        binding.pluginSections.removeAllViews();
        // ---- 第二波：播放路由卡（瓜子 LxPluginScreen：区头「播放路由／高级设置」18sp＋取链策略 15sp／副文 12sp＋指定平台插件 15sp／副文 12sp；
        // 取链策略两档照瓜子 LxPluginPriority：fallback 自动切换／pluginOnly 仅插件；指定平台插件行点进平台-插件指派表。
        // 注意：策略与指派只做存取＋UI，取链解析侧消费不在本批文件内（MusicRepository 禁动），见交卷待办。 ----
        LinearLayout routeCard = sectionCard();
        LinearLayout routeHead = new LinearLayout(this);
        routeHead.setOrientation(LinearLayout.HORIZONTAL);
        routeHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView routeTitle = new TextView(this);
        routeTitle.setText(R.string.wave2_route_title);
        routeTitle.setTextSize(18);
        routeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        routeTitle.setLetterSpacing(-0.017f);
        routeTitle.setTextColor(0xFF1C1C1E);
        routeHead.addView(routeTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView routeSub = new TextView(this);
        routeSub.setText(R.string.wave2_route_sub);
        routeSub.setTextSize(10);
        routeSub.setTextColor(0xFF8E8E93);
        routeHead.addView(routeSub);
        routeCard.addView(routeHead);
        boolean audioExists = false;
        for (MusicSite s : MusicSourceManager.get().listSites()) {
            if (s != null && !MusicSourceManager.isBuiltinLyricPreset(s.srcUrl) && !isLyricPluginSite(s)) {
                audioExists = true;
                break;
            }
        }
        final boolean fAudioExists = audioExists;
        String policy = MusicStore.getLinkPolicy();
        String policySub = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(policy)
                ? (fAudioExists ? getString(R.string.wave2_route_policy_only_desc) : getString(R.string.wave2_route_policy_no_plugin_only))
                : (fAudioExists ? getString(R.string.wave2_route_policy_auto_desc) : getString(R.string.wave2_route_policy_no_plugin));
        LinearLayout policyRow = sectionTextRow(getString(R.string.wave2_route_policy), policySub, v -> {
            List<String> opts = new ArrayList<>();
            opts.add(getString(R.string.wave2_route_policy_auto));
            opts.add(getString(R.string.wave2_route_policy_only));
            int checked = MusicStore.LINK_POLICY_PLUGIN_ONLY.equals(MusicStore.getLinkPolicy()) ? 1 : 0;
            MusicOptionsDialog.show(this, getString(R.string.wave2_route_policy), opts, checked, idx -> {
                MusicStore.setLinkPolicy(idx == 1 ? MusicStore.LINK_POLICY_PLUGIN_ONLY : MusicStore.LINK_POLICY_FALLBACK);
                refreshPluginSections();
            });
        });
        routeCard.addView(policyRow);
        View routeDivider = new View(this);
        routeDivider.setBackgroundColor(0xFFE5E5EA);
        LinearLayout.LayoutParams rdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        rdlp.topMargin = dp(10);
        rdlp.bottomMargin = dp(10);
        routeCard.addView(routeDivider, rdlp);
        int assignedCount = MusicStore.getAssignedPlatformCount();
        String assignSub = assignedCount > 0
                ? getString(R.string.wave2_route_assign_some, assignedCount)
                : getString(R.string.wave2_route_assign_none);
        LinearLayout assignRow = sectionTextRow(getString(R.string.wave2_route_assign), assignSub,
                v -> showPlatformAssignDialog());
        routeCard.addView(assignRow);
        binding.pluginSections.addView(routeCard);
        // ---- C5 多插件智能切换行（标题 15sp；说明句按忌介绍文字铁律去掉，只留名字＋开关，功能不动。
        // GZ 本体该行有 12sp 副文，用户令高于瓜子值，此处不照抄） ----
        // 两行开关并进一张卡（原先每行一张卡，底下卡堆是实拍“一堆卡”的来源之一；功能与缩放区分不动）
        boolean smartOn = MusicStore.isSmartSwitch();
        LinearLayout switchCard = sectionCard();
        LinearLayout smartRow = sectionSwitchRow("多插件智能切换", smartOn, 0.75f,
                (sw, on) -> {
                    if (!on) {
                        MusicStore.setSmartSwitch(false);
                        refreshPluginSections();
                        return;
                    }
                    if (MusicSourceManager.get().tryEnableSmartSwitch()) {
                        refreshPluginSections();
                    } else {
                        sw.setOnCheckedChangeListener(null);
                        sw.setChecked(false);
                        sw.setOnCheckedChangeListener((v, c) -> {
                        });
                        int n = MusicSourceManager.get().testedPluginCount();
                        new MaterialAlertDialogBuilder(MusicListActivity.this)
                                .setTitle("暂时无法开启")
                                .setMessage("多插件智能切换需要至少 2 个音源插件完成过「检测」（且至少一个平台可用）。当前已检测 "
                                        + n + " 个，请先在插件上执行「检测」后再开启。")
                                .setPositiveButton("知道了", null)
                                .show();
                        refreshPluginSections();
                    }
                });
        switchCard.addView(smartRow);
        View switchDivider = new View(this);
        switchDivider.setBackgroundColor(0xFFE5E5EA);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(10);
        switchCard.addView(switchDivider, dlp);
        // ---- C4 自动屏蔽行（另一类行，开关缩放 0.72，与插件行 0.75 区分；说明句同上按铁律去掉。
        // 注意 GZ 没有页级全局自动屏蔽行，只有单插件屏蔽面板内开关——星辰这行是自有功能，只动呈现不删） ----
        boolean blockOn = MusicStore.isAutoBlockInvalid();
        LinearLayout blockRow = sectionSwitchRow("自动屏蔽无效平台", blockOn, 0.72f,
                (sw, on) -> {
                    MusicStore.setAutoBlockInvalid(on);
                    if (on) {
                        for (MusicSite site : MusicSourceManager.get().enabledSites()) {
                            startPluginTest(site, false);
                        }
                    }
                    refreshPluginSections();
                });
        switchCard.addView(blockRow);
        binding.pluginSections.addView(switchCard);
        // ---- C10 运行时状态区（异常项＋重新加载钮；总数／启用数与头卡重复，已删） ----
        MusicRuntimeStatus st = MusicSourceManager.get().runtimeStatus();
        LinearLayout card = sectionCard();
        TextView title = new TextView(this);
        // GZ 面板标题“插件运行状态”18sp；星辰是页内卡不是独立面板，标题照 GZ 取名，说明段按铁律全删、只留名字＋状态行
        title.setText("插件运行状态");
        title.setTextSize(18);
        title.setTextColor(0xFF1C1C1E);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);
        // 第三波 B③：C10 卡补行——副文（已加载／已启用）＋当前单选状态（徽章同口径五选一取一）＋进行中态
        card.addView(sectionLine("已加载 " + st.liveRunners + " 个 · 已启用 "
                + st.enabledLocalPlugins + " 个"));
        {
            boolean hasUntested = false;
            for (MusicSite site : MusicSourceManager.get().listSites()) {
                if (site.enabled && MusicSourceManager.get().loadPluginTest(site.srcUrl) == null) {
                    hasUntested = true;
                    break;
                }
            }
            String state;
            if (st.quarantinedRunners > 0) state = "运行异常";
            else if (st.failedPlugins > 0) state = "启动失败";
            else if (st.initializing) state = "启动中";
            else if (st.enabledLocalPlugins == 0) state = "无本地插件";
            else if (hasUntested) state = "未检测";
            else state = "运行正常";
            card.addView(sectionLine("当前状态：" + state));
        }
        card.addView(sectionLine(st.initializing ? "正在启动…"
                : st.reloading ? "正在重新加载…" : "进行中：无"));
        // P24：C10 卡只留头卡没有的异常项——已加载总数／已启用数与头卡重复，删掉，不再两处对不上
        card.addView(sectionLine("暂时停用 " + st.quarantinedRunners + " 个"
                + (st.quarantinedNames.isEmpty() ? "" : "：" + android.text.TextUtils.join("、", st.quarantinedNames))));
        card.addView(sectionLine("启动失败 " + st.failedPlugins + " 个"
                + (st.failedNames.isEmpty() ? "" : "：" + android.text.TextUtils.join("、", st.failedNames))));
        TextView reloadBtn = new TextView(this);
        reloadBtn.setText("重新加载插件");
        reloadBtn.setTextSize(14);
        reloadBtn.setTextColor(0xFFFA243C);
        reloadBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        reloadBtn.setOnClickListener(v -> {
            MusicSourceManager.get().reloadBridges();
            refreshPluginSections();
            if (adapter != null) adapter.notifyDataSetChanged();
            Notify.show("已重新加载");
        });
        card.addView(reloadBtn);
        binding.pluginSections.addView(card);
    }

    private LinearLayout sectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_music_glass_24);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionLine(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF1C1C1E);
        return tv;
    }

    private interface SectionSwitchListener {
        void onChanged(com.google.android.material.switchmaterial.SwitchMaterial sw, boolean on);
    }

    private LinearLayout sectionSwitchRow(String titleText, boolean checked,
                                          float scale, SectionSwitchListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(0xFF1C1C1E);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(title, tlp);
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setScaleX(scale);
        sw.setScaleY(scale);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, on) -> listener.onChanged(sw, on));
        row.addView(sw);
        return row;
    }

    /** 第二波：路由区文字行（标题 15sp 半粗＋副文 12sp 灰；瓜子取链策略／指定平台插件行口径） */
    private LinearLayout sectionTextRow(String titleText, String subText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(15);
        title.setTextColor(0xFF1C1C1E);
        setSemiBold(title);
        row.addView(title);
        TextView sub = new TextView(this);
        sub.setText(subText);
        sub.setTextSize(12);
        sub.setTextColor(0xFF8E8E93);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        row.addView(sub, slp);
        row.setOnClickListener(listener);
        return row;
    }

    /** 半粗近似：瓜子 sq2.p（半粗）确切字重未解码，API 28+ 用 600，低版本退伪粗体 */
    private static void setSemiBold(TextView tv) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            tv.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 600, false));
        } else {
            tv.getPaint().setFakeBoldText(true);
        }
    }

    /** 第二波：指定平台插件指派表（瓜子 AssignOptionRow 口径：默认（自动选择）＋各启用插件；
     * 平台全集＝全部站点 platform 去重＋已指定残留平台；已停用插件不进选项，当前指派已不可用时顶部贴回落提示） */
    private void showPlatformAssignDialog() {
        List<String> plats = new ArrayList<>();
        for (MusicSite s : MusicRepository.sites()) {
            if (s != null && s.platform != null && !s.platform.isEmpty() && !plats.contains(s.platform)) plats.add(s.platform);
        }
        for (String p : MusicStore.getAssignedPlatforms()) {
            if (!plats.contains(p)) plats.add(p);
        }
        if (plats.isEmpty()) {
            Notify.show("暂无平台");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String p : plats) labels.add(LxPlatformSearch.platformName(p));
        MusicOptionsDialog.show(this, getString(R.string.wave2_route_assign), labels, -1, idx -> {
            if (idx < 0 || idx >= plats.size()) return;
            showSinglePlatformAssign(plats.get(idx));
        });
    }

    private void showSinglePlatformAssign(String platform) {
        List<MusicSite> enabled = new ArrayList<>();
        for (MusicSite s : MusicSourceManager.get().listSites()) {
            if (s != null && s.enabled) enabled.add(s);
        }
        List<String> opts = new ArrayList<>();
        opts.add(getString(R.string.wave2_route_assign_default));
        for (MusicSite s : enabled) opts.add(s.name == null ? "" : s.name);
        String cur = MusicStore.getPlatformPlugin(platform);
        int checked = 0;
        boolean curMissing = false;
        if (cur != null) {
            checked = -1;
            for (int i = 0; i < enabled.size(); i++) {
                if (cur.equals(enabled.get(i).srcUrl)) {
                    checked = i + 1;
                    break;
                }
            }
            if (checked < 0) curMissing = true;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(LxPlatformSearch.platformName(platform) + " · 取链插件")
                .setSingleChoiceItems(opts.toArray(new String[0]), checked, (d, which) -> {
                    if (which == 0) MusicStore.setPlatformPlugin(platform, null);
                    else if (which - 1 < enabled.size()) MusicStore.setPlatformPlugin(platform, enabled.get(which - 1).srcUrl);
                    d.dismiss();
                    refreshPluginSections();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (curMissing) builder.setMessage(R.string.wave2_route_assign_fallback_note);
        builder.show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** GZ 按压缩放（与音乐首页同款：按下缩、抬起／取消回弹；返回 false 不吞点击）。下载页重做补齐：进行中任务行两圆钮此前无按压反馈。 */
    private void pressScale(android.view.View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                view.setScaleX(scale);
                view.setScaleY(scale);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
            return false;
        });
    }

    /** 插件行 ⋮ 菜单：在线装的才有「更新」；手动排序模式下加「上移」「下移」；删除连插件文件一起删 */
    private void pluginMenu(MusicSite site) {
        boolean online = !MusicSourceManager.isLocal(site);
        List<String> opts = new ArrayList<>();
        int updateIdx = -1;
        int upIdx = -1;
        int downIdx = -1;
        if (online) {
            updateIdx = opts.size();
            opts.add(getString(R.string.music_plugin_update));
        }
        // P14：仅手动排序模式给上移／下移（首尾不给对应方向）
        if (MusicStore.PLUGIN_SORT_MANUAL.equals(MusicStore.getPluginSort())) {
            List<MusicSite> all = MusicSourceManager.get().listSites();
            int pos = -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i) != null && site.srcUrl != null && site.srcUrl.equals(all.get(i).srcUrl)) {
                    pos = i;
                    break;
                }
            }
            if (pos > 0) {
                upIdx = opts.size();
                opts.add(getString(R.string.music_plugin_move_up));
            }
            if (pos >= 0 && pos < all.size() - 1) {
                downIdx = opts.size();
                opts.add(getString(R.string.music_plugin_move_down));
            }
        }
        int blockIdx = opts.size();
        opts.add("屏蔽平台（" + MusicStore.getBlockedPlatforms(site.srcUrl).size() + "）");
        int deleteIdx = opts.size();
        opts.add(getString(R.string.music_delete));
        final int fUpdateIdx = updateIdx;
        final int fUpIdx = upIdx;
        final int fDownIdx = downIdx;
        final int fBlockIdx = blockIdx;
        final int fDeleteIdx = deleteIdx;
        MusicOptionsDialog.show(this, site.name == null ? "" : site.name, opts, -1, idx -> {
            if (idx == fUpdateIdx) {
                Task.execute(() -> {
                    String v = MusicSourceManager.get().checkUpdate(site);
                    runOnUiThread(() -> {
                        if (v == null) {
                            Notify.show(R.string.music_plugin_latest);
                            return;
                        }
                        // 瓜子更新弹窗：标题「音源插件发现新版本」＋「立即更新」／「忽略」＋P20 副文
                        new MaterialAlertDialogBuilder(MusicListActivity.this)
                                .setTitle("音源插件发现新版本")
                                .setMessage((site.name == null ? "" : site.name) + "：" + v
                                        + "\n\n" + getString(R.string.music_plugin_update_desc))
                                .setPositiveButton("立即更新", (d, w) -> {
                                    MusicSourceManager.get().applyUpdate(site, v);
                                    MusicActivity.clearHomeChartCache();
                                    Notify.show(R.string.music_plugin_updated);
                                    loadSites();
                                })
                                .setNegativeButton("忽略", (d, w) -> {
                                    MusicSourceManager.get().ignoreUpdate(site.srcUrl, v);
                                    Notify.show("已忽略这个版本");
                                })
                                .show();
                    });
                });
            } else if (idx == fUpIdx) {
                movePlugin(site, -1);
            } else if (idx == fDownIdx) {
                movePlugin(site, 1);
            } else if (idx == fBlockIdx) {
                showPlatformBlockSheet(site);
            } else if (idx == fDeleteIdx) {
                // 瓜子移除弹窗（仅本机文案；云端备份选项属排除项，不出）
                new MaterialAlertDialogBuilder(this)
                        .setTitle("移除「" + (site.name == null ? "" : site.name) + "」")
                        .setMessage("该插件只存在于本机，移除后需要重新导入脚本才能恢复。")
                        .setPositiveButton("移除", (d, w) -> Task.execute(() -> {
                            MusicSourceManager.get().removeSite(site.srcUrl);
                            runOnUiThread(() -> {
                                MusicActivity.clearHomeChartCache();
                                Notify.show(R.string.music_deleted);
                                loadSites();
                            });
                        }))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
    }

    /** 第 11 项②：单插件屏蔽平台弹层（GZ PlatformBlockSheet：标题 18sp、说明 12sp、平台行 15sp＋行尾已屏蔽态、
     * 自动屏蔽开关行 14sp；单插件屏蔽＝改该插件自身的 blockedPlatforms，不动其他插件；
     * 平台全集＝内容平台五家（kw/tx/wy/kg/mg）＋该插件已屏蔽的存量项（仍可见可恢复）；
     * 行点按即切换并立即写 prefs，无单独保存钮；自动屏蔽开关复用全局偏好，与页级开关同一实现） */
    private void showPlatformBlockSheet(MusicSite site) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(16), pad, dp(8));
        TextView title = new TextView(this);
        title.setText("屏蔽平台");
        title.setTextSize(18);
        title.getPaint().setFakeBoldText(true);
        title.setTextColor(0xFF1C1C1E);
        root.addView(title);
        TextView desc = new TextView(this);
        desc.setText("对『" + (site.name == null ? "" : site.name) + "』屏蔽的平台，将不再用于该插件的取链／换源／内容与歌词");
        desc.setTextSize(12);
        desc.setTextColor(0xFF9AA7B8);
        desc.setPadding(0, dp(8), 0, dp(12));
        root.addView(desc);
        // 平台全集＝内容平台五家（kw/tx/wy/kg/mg，与落雪/聚合插件的内容平台同层）＋
        // 该插件已屏蔽但不在五家里的存量项（仍可见可取消）。旧口径摆的是源层 platform
        // （freejs/lx/py…）：与消费端的内容平台判据对不上，屏蔽了也拦不住、拦住的又不是想屏的
        List<String> plats = new ArrayList<>();
        for (String p : new String[]{LxPlatformSearch.KW, LxPlatformSearch.QQ, LxPlatformSearch.WY, LxPlatformSearch.KG, LxPlatformSearch.MG}) {
            if (!plats.contains(p)) plats.add(p);
        }
        for (String p : MusicStore.getBlockedPlatforms(site.srcUrl)) {
            if (!plats.contains(p)) plats.add(p);
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (String p : plats) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            TextView name = new TextView(this);
            name.setText(LxPlatformSearch.platformName(p));
            name.setTextSize(15);
            name.setTextColor(0xFF1C1C1E);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = new TextView(this);
            state.setTextSize(14);
            state.setTextColor(0xFFFA243C);
            row.addView(state);
            Runnable refresh = () -> state.setText(
                    MusicStore.getBlockedPlatforms(site.srcUrl).contains(p) ? "已屏蔽" : "");
            refresh.run();
            row.setOnClickListener(v -> {
                java.util.Set<String> cur = MusicStore.getBlockedPlatforms(site.srcUrl);
                if (cur.contains(p)) cur.remove(p);
                else cur.add(p);
                MusicStore.setBlockedPlatforms(site.srcUrl, cur);
                refresh.run();
            });
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        root.addView(scroll);
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(12), 0, dp(4));
        TextView autoName = new TextView(this);
        autoName.setText("自动屏蔽无效平台");
        autoName.setTextSize(14);
        autoName.setTextColor(0xFF1C1C1E);
        autoRow.addView(autoName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        com.google.android.material.switchmaterial.SwitchMaterial sw =
                new com.google.android.material.switchmaterial.SwitchMaterial(this);
        sw.setChecked(MusicStore.isAutoBlockInvalid());
        sw.setOnCheckedChangeListener((v, on) -> {
            MusicStore.setAutoBlockInvalid(on);
            if (on) {
                for (MusicSite s : MusicSourceManager.get().enabledSites()) {
                    startPluginTest(s, false);
                }
            }
        });
        autoRow.addView(sw);
        root.addView(autoRow);
        new MaterialAlertDialogBuilder(this).setView(root)
                .setPositiveButton("完成", null)
                .show();
    }

    /**
     * 插件导入确认（瓜子「第三方插件导入确认」：标题＋导语＋前两条照瓜子；
     * P26：第 3 条改写为星辰版——原「先试运行一次」句按对照表落码口径替换，
     * probe 先行再落盘仍是安装链行为，只是对话框文案不再单列）。
     */
    /** 第三波 B①：导入进度弹窗。阶段／完成态／失败重试都在此；
     * 弹窗可关（离开不中断）：后台安装任务与弹窗无关，完成后若弹窗还在才刷新它，否则只发提示＋刷新列表。
     * 本地导入现状：单文件选择（ACTION_GET_CONTENT 未开多选），一次只装一个。 */
    private interface PluginInstaller {
        void install() throws Exception;
    }

    private void runPluginImportWithProgress(String stageLabel, PluginInstaller installer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        TextView stage = new TextView(this);
        stage.setText(stageLabel);
        stage.setTextSize(14);
        stage.setTextColor(0xFF1C1C1E);
        box.addView(stage);
        TextView retry = new TextView(this);
        retry.setText("重试");
        retry.setTextSize(14);
        retry.setTextColor(0xFFFA243C);
        retry.setPadding(0, dp(12), 0, 0);
        retry.setVisibility(View.GONE);
        box.addView(retry);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("导入插件")
                .setView(box)
                .setPositiveButton("关闭", null)
                .setCancelable(true)
                .create();
        retry.setOnClickListener(v -> {
            retry.setVisibility(View.GONE);
            stage.setText(stageLabel);
            runImportTask(dialog, stage, retry, installer);
        });
        dialog.show();
        runImportTask(dialog, stage, retry, installer);
    }

    private void runImportTask(AlertDialog dialog, TextView stage, TextView retry, PluginInstaller installer) {
        Task.execute(() -> {
            try {
                installer.install();
                runOnUiThread(() -> {
                    if (dialog.isShowing()) stage.setText("已完成");
                    MusicActivity.clearHomeChartCache();
                    Notify.show(R.string.music_site_added);
                    loadSites();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pluginImporting = false;
                    refreshPluginHeader(MusicSourceManager.get().listSites());
                    String msg = e.getMessage() == null || e.getMessage().isEmpty()
                            ? getString(R.string.music_import_failed_retry)
                            : "导入失败：" + e.getMessage();
                    if (dialog.isShowing()) {
                        stage.setText(msg);
                        retry.setVisibility(View.VISIBLE);
                    } else {
                        Notify.show(msg);
                    }
                });
            }
        });
    }

    private void confirmPluginImport(Runnable onAgree) {
        String msg = "请仔细阅读以下提示，点击「同意并导入」即代表你已同意\n\n"
                + "1. 该脚本来自第三方，非星辰提供。请自行判断其用途与潜在风险（内容、播放链接等），相关责任由脚本作者和使用者自行承担；\n"
                + "2. 脚本会在本应用内执行代码，并可发起网络请求，请仅导入你信任的来源；\n"
                + "3. 星辰不发布、不内置、不推荐任何第三方插件，在线播放与歌词由用户自行导入的脚本提供。\n"
                + "4. 安装前会先试运行一次，确认能正常提供音源才会保留。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("第三方插件导入确认")
                .setMessage(msg)
                .setPositiveButton("同意并导入", (d, w) -> onAgree.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void inputSiteUrl() {
        // P29：标题改为「从链接导入」＋说明行「粘贴脚本地址，支持 raw / latest.js」；空值直接返回保留
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        TextView desc = new TextView(this);
        desc.setText(R.string.music_plugin_import_url_desc);
        desc.setTextSize(13);
        desc.setTextColor(0xFF8E8E93);
        box.addView(desc);
        EditText et = new EditText(this);
        et.setHint(R.string.music_site_url_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = pad / 2;
        box.addView(et, etLp);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_plugin_import_url)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    confirmPluginImport(() -> {
                        pluginImporting = true;
                        refreshPluginHeader(MusicSourceManager.get().listSites());
                        runPluginImportWithProgress("正在下载并安装…",
                                () -> MusicSourceManager.get().addSite(url));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLoading() {
        binding.empty.setVisibility(View.VISIBLE);
        binding.empty.setText("正在加载…");
    }

    private void showTracks(List<MusicTrack> list) {
        sortTracks(list);
        tracks.clear();
        tracks.addAll(list);
        cards.clear();
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            binding.tvBigTitle.setText(bigTitle);
            binding.tvBigCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
            refreshSortLabel();
            // 第 7 项尾巴：download 模式有进行中任务时不显空态（已完成为空不等于没内容）
            boolean showEmpty = tracks.isEmpty()
                    && !(mode.equals("download") && !dlTasks.isEmpty());
            binding.empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            // 本页对照修：下载空态用 GZ 文案，不许再套通用「还没有在播的歌」；
            // 搜已下载搜不到时走通用搜不到文案
            // 第三波 A1：搜索模式空文案由 showCategoryResults 一次写定，showTracks 只管显隐，不再事后覆盖
            if (tracks.isEmpty() && mode.equals("download")) {
                binding.empty.setText(dlSearchKw.isEmpty()
                        ? "还没有下载的歌曲\n搜索并下载你喜欢的音乐"
                        : getString(R.string.music_no_result));
            } else if (tracks.isEmpty() && !mode.equals("search")) binding.empty.setText(R.string.music_no_track);
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
        if (MusicStore.isListSortDesc()) Collections.reverse(list);
    }

    /** 复核必-10：第二段升降序弹层的 post 用字段 Handler＋字段 Runnable，onDestroy 取消，
     * 不许匿名 Handler＋匿名 Runnable 丢引用、页死后还弹。 */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

    /** 第三波 R4：通用排序改对话框（默认／标题／歌手／时长＋升降），不再盲切 cycleSort；
     * 当前档常显在工具条 tvSortCurrent 与 btnSort 无障碍文案上（见 refreshSortLabel）。
     * device／收藏／历史走各自对话框，不进这里。 */
    private void showGenericSortDialog() {
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        MusicOptionsDialog.show(this, "排序", java.util.Arrays.asList(keys), MusicStore.getListSort(), idx -> {
            MusicStore.setListSort(idx);
            // 复核必-10：第一段落点即刷新档位文案并按新档重排，不等第二段升降序选完才动；
            // 第二段弹窗延后一帧弹（第一段 dismiss 在回调返回后才走，同帧同 tag 再 show 会撞 FragmentManager）。
            refreshSortLabel();
            if (isCardMode()) load();
            else showTracks(new ArrayList<>(tracks));
            sortDirRunnable = () -> {
                if (isFinishing() || isDestroyed()) return;
                MusicOptionsDialog.show(this, "升序 / 降序",
                        java.util.Arrays.asList("升序", "降序"), MusicStore.isListSortDesc() ? 1 : 0, dir -> {
                            MusicStore.setListSortDesc(dir == 1);
                            refreshSortLabel();
                            if (isCardMode()) load();
                            else showTracks(new ArrayList<>(tracks));
                        });
            };
            sortDialogHandler.post(sortDirRunnable);
        });
    }

    /** 第三波 R4：工具条旁常显当前通用排序档（device／收藏／历史有各自对话框时隐藏）。 */
    private void refreshSortLabel() {
        if (binding == null || binding.tvSortCurrent == null) return;
        if (mode.equals("device") || mode.equals("favorite") || mode.equals("history")) {
            binding.tvSortCurrent.setVisibility(View.GONE);
            return;
        }
        String[] keys = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        int s = MusicStore.getListSort();
        String label = keys[s < 0 || s >= keys.length ? 0 : s] + (MusicStore.isListSortDesc() ? " ↓" : " ↑");
        binding.tvSortCurrent.setText(label);
        binding.tvSortCurrent.setVisibility(View.VISIBLE);
        binding.btnSort.setContentDescription("排序：" + label);
    }

    /** 第三波 R3：通用“播放全部”——顺序入队从头播（与“随机播放”分开）。
     * 歌曲行在场时走 tracks；搜索分组卡下无行时走当前过滤后的搜索池；其余无行时不播。 */
    private void playAllSequential() {
        if (!tracks.isEmpty()) {
            playTrack(0);
            return;
        }
        if (mode.equals("search")) {
            List<MusicTrack> pool = currentSearchPool();
            if (pool.isEmpty()) {
                Notify.show(R.string.music_no_track);
                return;
            }
            MusicController.get().play(new ArrayList<>(pool), 0, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
            return;
        }
        Notify.show(R.string.music_no_track);
    }

    private void shufflePlay() {
        // 第 8 项：本地页组视图（卡）下没有歌曲行，随机播按当前来源过滤的全库打乱播
        if (mode.equals("device") && deviceView != DEVICE_VIEW_SONGS) {
            List<MusicTrack> pool = new ArrayList<>();
            for (MusicTrack t : deviceAll) {
                if (deviceSource == 1 && isLibTrack(t)) continue;
                if (deviceSource == 2 && !isLibTrack(t)) continue;
                pool.add(t);
            }
            if (pool.isEmpty()) {
                Notify.show(R.string.music_no_track);
                return;
            }
            Collections.shuffle(pool);
            MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
            MusicController.get().play(pool, 0, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
            return;
        }
        if (mode.equals("search") && tracks.isEmpty()) {
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
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        // B7：入队时写队列级来源＝本页标题、route＝list（参数可重建本页，落点＝本列表页）
        MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    private void playTrack(int position) {
        // B7：入队时写队列级来源＝本页标题、route＝list（参数可重建本页，落点＝本列表页）
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    /** B7：list 路由参数——与本页 onCreate 读的 intent extras 同名同值，可重建本页 */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", mode == null ? "" : mode);
        params.put("keyword", keyword == null ? "" : keyword);
        params.put("sitePlatform", sitePlatform == null ? "" : sitePlatform);
        params.put("playlistId", String.valueOf(playlistId));
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    /** 上次在播曲目 id（定点刷行用：切歌只刷旧行＋新行，不全量重绑）。 */
    private String lastCurrentId = null;

    /**
     * 搜索页重做（字乱动根因之一）：此前切歌全量 notifyDataSetChanged，所有可见行重绑、
     * 封面重载，整列表闪一下。改只刷旧在播行＋新在播行；两首都不在本页时无行可刷，
     * 直接跳过（旧高亮只存在于本页行内，不在本页就无残留可清）。
     */
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

    /**
     * tracks 下标 → adapter 位置（trackIndex 的逆映射，见 ListAdapter.trackIndex）：
     * 歌单详情＋3、榜单详情＋1、猜你喜欢＋3、下载＝任务数＋已完成区头 1＋下标、
     * 本地歌曲分组视图＝按 adapter 位置逐个跳过区块头数到第 trackIdx 个歌曲行、
     * 其余模式＝下标＋设备头（本地页 1、其它 0）。卡片视图（本地分组卡／歌单／榜单列表）
     * 下没有歌曲行，返回 -1 不刷行。
     */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        if (mode.equals("sheet")) return trackIdx + 3;
        if (mode.equals("chartDetail")) return trackIdx + 1;
        if (mode.equals("guess")) return trackIdx + 3;
        if (mode.equals("download")) return dlTasks.size() + 1 + trackIdx;
        if (mode.equals("device")) {
            if (deviceView != DEVICE_VIEW_SONGS) return -1;
            int seen = -1;
            for (int p = 1; p < adapter.getItemCount(); p++) {
                if (deviceSectionPos.contains(p)) continue;
                if (++seen == trackIdx) return p;
            }
            return -1;
        }
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

    private static class NewPlaylistCard {
    }

    private static class ImportPlaylistCard {
    }

    // ================= ⋮ 菜单 =================

    /** 第 1 项：下载（从通用 ⋮ 菜单抽出来的同一段逻辑：每次询问档时先选档，否则直接下载；原在 ListAdapter 内，onMore 菜单调不到，挪到外层） */
    private void downloadTrack(MusicTrack track) {
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            final int checkedFinal = checked;
            // 第三波 S-A2：选档前实测文件大小（取链＋HEAD；实测不到的档不显示大小、不估算）。
            // 复核堵-1：探查期间先给现成 Notify 提示，探查回来了页已死则不弹选档窗。
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
            int removeIdx = -1;
            int deleteLocalIdx = -1;
            int detailIdx = -1;
            int removeDeviceIdx = -1;
            int deleteFileIdx = -1;
            int removeHistIdx = -1;
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
                // 第 7 项尾巴：仅 download 模式给详情弹层入口
                if (mode.equals("download")) {
                    detailIdx = opts.size();
                    opts.add("详情");
                }
            } else if (device) {
                // 设备歌曲：本来就在手机里，不给"下载"
                if (mode.equals("sheet")) {
                    removeIdx = opts.size();
                    opts.add(getString(R.string.music_remove));
                } else if (mode.equals("device")) {
                    // 第 8 项：本地页行内移除（移出曲库，重启仍生效）／删文件（真删文件＋同名旁路）
                    // GZ 本地库行内未抠到删除入口（只有文件夹排除管理＋下载页删除二选一），这两项是任务要求的星辰侧实现
                    removeDeviceIdx = opts.size();
                    opts.add("移除");
                    deleteFileIdx = opts.size();
                    opts.add("删除文件");
                }
            } else {
                if (mode.equals("sheet")) {
                    removeIdx = opts.size();
                    opts.add(getString(R.string.music_remove));
                }
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int rIdx = removeIdx;
            int delIdx = deleteLocalIdx;
            int detIdx = detailIdx;
            int rmDevIdx = removeDeviceIdx;
            int delFileIdx = deleteFileIdx;
            // 第 6 项：历史页行级移除（GZ：历史页有「移除／移除历史」行级操作）
            if (mode.equals("history")) {
                removeHistIdx = opts.size();
                opts.add("移除历史");
            }
            int rmHistIdx = removeHistIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == dIdx) downloadTrack(track);
                else if (idx == rIdx) removeFromSheet(track);
                else if (idx == rmDevIdx) {
                    // 第 8 项：行内移除＝记入忽略集，列表立即刷新，重启仍生效（恢复入口待定，见对照表）
                    com.fongmi.android.tv.music.MusicStore.ignoreDeviceId(track.id);
                    Notify.show("已移出本地库");
                    loadDevice();
                } else if (idx == delFileIdx) {
                    new MaterialAlertDialogBuilder(act)
                            .setTitle(track.displayTitle())
                            .setMessage("确定删除这个文件吗？文件将从手机中彻底删除，同名歌词与封面文件一并删除。")
                            .setPositiveButton("删除", (d, w) -> {
                                try {
                                    if (track.localPath != null && !track.localPath.isEmpty()) {
                                        java.io.File audio = new java.io.File(track.localPath);
                                        String name = audio.getName();
                                        int dot = name.lastIndexOf('.');
                                        audio.delete();
                                        if (dot > 0) {
                                            String base = name.substring(0, dot);
                                            java.io.File parent = audio.getParentFile();
                                            if (parent != null) {
                                                for (String e : new String[]{"lrc", "jpg", "png", "jpeg"}) {
                                                    new java.io.File(parent, base + "." + e).delete();
                                                }
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                Notify.show(R.string.music_deleted);
                                loadDevice();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
                else if (idx == rmHistIdx) {
                    com.fongmi.android.tv.music.MusicStore.removeRecentByIds(
                            java.util.Collections.singleton(track.id));
                    loadHistory();
                }
                else if (idx == detIdx) showDownloadDetail(track);
                else if (idx == delIdx) {
                    if (mode.equals("download")) deleteDownloadTwoChoice(track);
                    else confirmDeleteLocal(track);
                }
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
                            if (mode.equals("download")) loadDownloads();
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleFavorite(MusicTrack track, String label) {
        boolean toFav = label.equals(getString(R.string.music_favorite));
        MusicLibrary.setFavorite(track, toFav, () ->
                Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
        if (mode.equals("favorite") && !toFav) loadFavorites();
    }

    private void removeFromSheet(MusicTrack track) {
        Task.execute(() -> {
            // 按 trackId 删
            try {
                com.fongmi.android.tv.db.AppDatabase.get().getMusicPlaylistItemDao()
                        .delete(playlistId, track.id);
            } catch (Throwable ignored) {
            }
            runOnUiThread(this::loadSheetTracks);
        });
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
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, p -> {
                        MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                Notify.show(added ? R.string.music_added : R.string.music_already_in));
                        if (mode.equals("sheets")) loadSheets();
                    }));
                }
            });
        });
    }

    private void inputPlaylistName(NameInput cb) {
        // 2026-10-22 歌单页对照 GZ CreatePlaylistDialog 重做：旧 dialog 是下划线裸输入框＋黄字取消／确定，
        // 且输入字是白色、在浅底上打出来看不见；新版照 GZ 结构（红图标＋标题＋副标题＋圆角输入框＋大创建钮）。
        // 封面选择与开通赞助不做——星辰歌单无封面字段、不要账号与付费项，不硬做空壳。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(0, dp(8), 0, dp(8));
        TextView icon = new TextView(this);
        icon.setText("≡+");
        icon.setTextSize(22);
        icon.setTextColor(0xFFFFFFFF);
        icon.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
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
        android.graphics.drawable.GradientDrawable createBg = new android.graphics.drawable.GradientDrawable();
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

    // ================= 导入歌单 =================

    /** 粘贴弹窗：点导入后不关窗，后台拉取，失败原因就显示在输入框下方 */
    /** 第 11 项：GZ 导入歌单大面板（替代旧小 dialog；分享链接导入与 .lxmc 导入复用现有逻辑） */
    private void showImportPlaylistDialog() {
        MusicPlaylistImportDialog.show(this, new MusicPlaylistImportDialog.Listener() {
            @Override
            public void onImportText(String text, MusicPlaylistImportDialog dialog) {
                runPlaylistImportNew(text, dialog);
            }

            @Override
            public void onLxmc() {
                pickLxmcFile();
            }

            @Override
            public void onAccountImport(String platform) {
                showAccountLoginDialog(platform);
            }
        });
    }

    private void runPlaylistImportNew(String text, MusicPlaylistImportDialog dialog) {
        String input = text == null ? "" : text.trim();
        if (input.isEmpty()) {
            dialog.setError(R.string.music_import_err_paste_empty);
            return;
        }
        dialog.setBusy(true);
        Task.execute(() -> {
            PlaylistImporter.Result result = null;
            PlaylistImporter.Fail fail = null;
            boolean lxOk = false;
            try {
                result = PlaylistImporter.importFromText(input);
                lxOk = PlaylistImporter.hasLxSupport(result.platformKey);
            } catch (PlaylistImporter.ImportException e) {
                fail = e.fail;
            } catch (Throwable t) {
                fail = PlaylistImporter.Fail.NETWORK;
            }
            PlaylistImporter.Result r = result;
            PlaylistImporter.Fail f = fail;
            boolean lx = lxOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f != null || r == null) {
                    dialog.setBusy(false);
                    dialog.setError(importErrorText(f == null ? PlaylistImporter.Fail.NETWORK : f));
                    return;
                }
                // 第三波 G-B5：无落雪支撑时导入前二次确认（原先只在提示尾巴追加，歌单仍照常落库）
                Runnable doImport = () -> MusicLibrary.importPlaylist(r.name, r.tracks, res -> {
                    if (res.inserted <= 0) {
                        if (res.playlist != null) MusicLibrary.deletePlaylist(res.playlist.getId(), null);
                        dialog.setBusy(false);
                        dialog.setError(R.string.music_import_err_empty);
                        return;
                    }
                    dialog.dismissAllowingStateLoss();
                    loadSheets();
                    String msg;
                    if (r.total > res.inserted) {
                        msg = getString(R.string.music_import_partial, res.inserted, r.total - res.inserted);
                    } else {
                        msg = getString(R.string.music_imported, res.playlist.getName(), res.inserted);
                    }
                    if (!lx) {
                        msg += "，" + getString(R.string.music_import_need_lx,
                                LxPlatformSearch.platformName(r.platformKey));
                    }
                    Notify.show(msg);
                });
                if (!lx) {
                    dialog.setBusy(false);
                    new MaterialAlertDialogBuilder(MusicListActivity.this)
                            .setTitle("需要落雪脚本")
                            .setMessage("这些歌需要装支持"
                                    + LxPlatformSearch.platformName(r.platformKey)
                                    + "的落雪源才能播，当前没装，仍要导入吗？")
                            .setPositiveButton("仍要导入", (d, w) -> doImport.run())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                } else {
                    doImport.run();
                }
            });
        });
    }

    /**
     * 第 11 项：登录账号导入——只放开这一处。登录页照 GZ：WebView 载平台站、开第三方 Cookie、
     * GZ 提示原文、“完成，获取Cookie”钮。Cookie 读到后：手机直接调平台公开接口拉“我的歌单”列表
     * （AccountPlaylistFetcher，Cookie 不出手机、不走任何第三方服务器），点某张走 PlaylistImporter
     * 现有导入链进歌单页；失败报真实原因，不编假导入。
     */
    /** 第三波 G-B3：已登录账号行＋退出入口（登录入口即管理入口，树内无独立账号页）。
     * CookieManager 是 WebView 的持久 Cookie 存贮：此前登过且 Cookie 还在时，不再弹
     * WebView，直接给“已登录”态＋[拉取歌单／退出账号]；退出只清对应域 Cookie（按
     * Cookie 串里的名逐个写过期值，不动另一平台），随后 flush 落盘。 */
    private void showAccountLoginDialog(String platform) {
        boolean netease = "netease".equals(platform);
        String url = netease ? "https://music.163.com/" : "https://y.qq.com/";
        String title = "登录 " + (netease ? "网易云音乐" : "QQ音乐");
        String existing = null;
        try {
            existing = android.webkit.CookieManager.getInstance().getCookie(url);
        } catch (Throwable ignored) {
        }
        if (existing != null && !existing.trim().isEmpty()) {
            String cookie = existing;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("已登录账号（" + (netease ? "网易云音乐" : "QQ音乐") + "）")
                    .setMessage("登录 Cookie 已保存在本机，可直接拉取歌单；退出将只清该平台的 Cookie。")
                    .setPositiveButton("拉取歌单", (d, w) -> fetchAccountSheets(platform, cookie))
                    .setNeutralButton("退出账号", (d, w) -> {
                        try {
                            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                            for (String part : cookie.split(";")) {
                                String name = part.trim().split("=", 2)[0].trim();
                                if (!name.isEmpty()) cm.setCookie(url, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
                            }
                            cm.flush();
                        } catch (Throwable ignored) {
                        }
                        Notify.show("已退出" + (netease ? "网易云音乐" : "QQ音乐") + "账号");
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        android.webkit.WebView web = new android.webkit.WebView(this);
        android.webkit.WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.loadUrl(url);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView tip = new TextView(this);
        tip.setText("请在网页中使用桌面版登录（可用手机端 App 扫码，或账号密码登录），登录完成后点下方按钮");
        tip.setTextSize(13);
        tip.setTextColor(0xFF8E8E93);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        box.addView(tip);
        box.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("完成，获取Cookie", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                    if (cookie == null || cookie.isEmpty()) {
                        Notify.show("未读到登录 Cookie，请先在网页里登录");
                        return;
                    }
                    try {
                        android.webkit.CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                    }
                    dialog.dismiss();
                    fetchAccountSheets(platform, cookie);
                }));
        dialog.show();
    }

    /** 登录账号导入：Cookie 读到后，手机直接调平台公开接口拉“我的歌单”列表，点某张走现有导入链 */
    private void fetchAccountSheets(String platform, String cookie) {
        boolean netease = "netease".equals(platform);
        String platformKey = netease ? LxPlatformSearch.WY : LxPlatformSearch.QQ;
        String platformName = netease ? "网易云音乐" : "QQ音乐";
        Notify.show("正在拉取" + platformName + "歌单…");
        Task.execute(() -> {
            List<AccountPlaylistFetcher.Sheet> sheets = null;
            String reason = null;
            try {
                sheets = netease ? AccountPlaylistFetcher.fetchNetease(cookie) : AccountPlaylistFetcher.fetchQq(cookie);
            } catch (AccountPlaylistFetcher.FetchException e) {
                reason = e.getMessage();
            } catch (Throwable t) {
                reason = "网络错误";
            }
            List<AccountPlaylistFetcher.Sheet> result = sheets;
            String failReason = reason;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failReason != null) {
                    Notify.show(failReason);
                    return;
                }
                if (result == null || result.isEmpty()) {
                    Notify.show("该账号下没有歌单");
                    return;
                }
                AccountPlaylistDialog.show(this, platformName + "歌单（" + result.size() + "）", result,
                        sheet -> importAccountSheet(platformKey, sheet));
            });
        });
    }

    /** 登录账号导入：点某张“我的歌单”，走 PlaylistImporter 现有导入链进歌单页 */
    private void importAccountSheet(String platformKey, AccountPlaylistFetcher.Sheet sheet) {
        String displayName = sheet.name == null || sheet.name.isEmpty() ? sheet.id : sheet.name;
        Notify.show("正在导入“" + displayName + "”…");
        Task.execute(() -> {
            PlaylistImporter.Result r = null;
            PlaylistImporter.Fail f = null;
            boolean lxOk = false;
            try {
                r = PlaylistImporter.importFromId(platformKey, sheet.id);
                lxOk = PlaylistImporter.hasLxSupport(r.platformKey);
            } catch (PlaylistImporter.ImportException e) {
                f = e.fail;
            } catch (Throwable t) {
                f = PlaylistImporter.Fail.NETWORK;
            }
            PlaylistImporter.Result result = r;
            PlaylistImporter.Fail fail = f;
            boolean lx = lxOk;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fail != null || result == null) {
                    Notify.show(importErrorText(fail == null ? PlaylistImporter.Fail.NETWORK : fail));
                    return;
                }
                MusicLibrary.importPlaylist(result.name, result.tracks, res -> {
                    if (res.inserted <= 0) {
                        if (res.playlist != null) MusicLibrary.deletePlaylist(res.playlist.getId(), null);
                        Notify.show(importErrorText(PlaylistImporter.Fail.EMPTY));
                        return;
                    }
                    loadSheets();
                    String baseMsg;
                    if (result.total > res.inserted) {
                        baseMsg = getString(R.string.music_import_partial, res.inserted, result.total - res.inserted);
                    } else {
                        baseMsg = getString(R.string.music_imported, res.playlist.getName(), res.inserted);
                    }
                    if (!lx) {
                        baseMsg += "，" + getString(R.string.music_import_need_lx,
                                LxPlatformSearch.platformName(result.platformKey));
                    }
                    // 第三波 G-B2：收藏音乐一步——AccountPlaylistFetcher 没有“我喜欢”
                    // 独立接口，收藏只能落在已拉到的歌单里：当导入的正是平台惯用的
                    // 喜欢歌单（网易云“我喜欢的音乐”／QQ“我喜欢”）时，把同批曲目
                    // 按收藏落库口径（MusicLibrary.setFavorite，与行内收藏同一 dao）
                    // 批量写入收藏；其它歌单不触发本步。喜欢歌单若不在拉到的列表里，
                    // 本步无从触发，见交回（不编独立喜欢接口）。
                    String likedName = sheet.name == null ? "" : sheet.name.trim();
                    boolean likedSheet = "我喜欢的音乐".equals(likedName) || "我喜欢".equals(likedName);
                    if (!likedSheet || result.tracks == null || result.tracks.isEmpty()) {
                        Notify.show(baseMsg);
                        return;
                    }
                    String finalMsg = baseMsg;
                    java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
                    int total = result.tracks.size();
                    for (MusicTrack favTrack : result.tracks) {
                        MusicLibrary.setFavorite(favTrack, true, () -> {
                            // 复核必-13：total 是导入源全量，去重后真实入库数可能更少，不许拿全量冒充入库数；
                            // 不按 insert 返回值重算（不改 dao 口径），提示去掉数字。
                            if (done.incrementAndGet() >= total) {
                                Notify.show(finalMsg + "，已同步收藏");
                            }
                        });
                    }
                });
            });
        });
    }

    private int importErrorText(PlaylistImporter.Fail fail) {
        switch (fail) {
            case UNRECOGNIZED:
                return R.string.music_import_err_unrecognized;
            case NOT_FOUND:
                return R.string.music_import_err_notfound;
            case EMPTY:
                return R.string.music_import_err_empty;
            default:
                return R.string.music_import_err_network;
        }
    }

    // ================= 洛雪 .lxmc 文件导入 =================

    /** .lxmc 解析出的一个歌单（瓜子 LxPlaylistParser 的两种结构：备份多歌单／单歌单） */
    private static class LxmcPlaylist {
        final String name;
        final List<MusicTrack> tracks;

        LxmcPlaylist(String name, List<MusicTrack> tracks) {
            this.name = name;
            this.tracks = tracks;
        }
    }

    private void pickLxmcFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "从洛雪 .lxmc 文件导入"), REQ_PICK_LXMC);
    }

    private void runLxmcImport(Uri uri) {
        Task.execute(() -> {
            List<LxmcPlaylist> lists;
            try {
                byte[] bytes = readAllCappedLxmc(uri);
                lists = parseLxmc(bytes);
            } catch (Exception e) {
                runOnUiThread(() -> Notify.show(e.getMessage() == null ? "不是有效的 LX 歌单文件" : e.getMessage()));
                return;
            }
            // 落雪源支撑情况在子线程先算好（hasLxSupport 要枚举落雪插件，不上主线程）
            Set<String> unsupported = new java.util.LinkedHashSet<>();
            for (LxmcPlaylist p : lists) {
                for (MusicTrack t : p.tracks) {
                    String key = lxmcPlatformKey(t);
                    if (!key.isEmpty() && !unsupported.contains(key) && !PlaylistImporter.hasLxSupport(key)) {
                        unsupported.add(key);
                    }
                }
            }
            runOnUiThread(() -> {
                if (unsupported.isEmpty()) {
                    importLxmcPlaylists(lists, 0, 0, 0, unsupported);
                    return;
                }
                // 第三波 G-B5：.lxmc 无支撑平台时导入前二次确认
                java.util.List<String> names = new java.util.ArrayList<>();
                for (String key : unsupported) names.add(LxPlatformSearch.platformName(key));
                new MaterialAlertDialogBuilder(MusicListActivity.this)
                        .setTitle("需要落雪脚本")
                        .setMessage("这些歌需要装支持" + android.text.TextUtils.join("、", names)
                                + "的落雪源才能播，当前没装，仍要导入吗？")
                        .setPositiveButton("仍要导入", (d, w) -> importLxmcPlaylists(lists, 0, 0, 0, unsupported))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        });
    }

    /** 从落雪歌 id 反推平台键：id 形如 lx:<平台键>:<原生 id> */
    private static String lxmcPlatformKey(MusicTrack t) {
        if (t == null || t.id == null) return "";
        String[] parts = t.id.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    /** 逐个落库（MusicLibrary.importPlaylist 自带主线程回调，串行避免提示乱序） */
    private void importLxmcPlaylists(List<LxmcPlaylist> lists, int idx, int imported, int inserted, Set<String> unsupported) {
        if (idx >= lists.size()) {
            if (inserted <= 0) {
                Notify.show(R.string.music_import_err_empty);
                return;
            }
            loadSheets();
            String msg = "已导入 " + imported + " 个歌单，共 " + inserted + " 首";
            if (!unsupported.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (String key : unsupported) names.add(LxPlatformSearch.platformName(key));
                StringBuilder joined = new StringBuilder();
                for (String n : names) {
                    if (joined.length() > 0) joined.append("、");
                    joined.append(n);
                }
                msg += "，" + getString(R.string.music_import_need_lx, joined.toString());
            }
            Notify.show(msg);
            return;
        }
        LxmcPlaylist p = lists.get(idx);
        if (p.tracks.isEmpty()) {
            importLxmcPlaylists(lists, idx + 1, imported, inserted, unsupported);
            return;
        }
        MusicLibrary.importPlaylist(p.name, p.tracks, res ->
                importLxmcPlaylists(lists, idx + 1, imported + 1, inserted + res.inserted, unsupported));
    }

    /** 带上限的整读（.lxmc 原文件不大，64MB 封顶；解压后上限另在解析里按瓜子 64MB 卡） */
    private byte[] readAllCappedLxmc(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("读不到这个文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > 64 * 1024 * 1024) throw new Exception("文件太大（超 64MB），跳过");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * .lxmc 格式（瓜子 LxPlaylistParser.parse／decodeText／parseBackup／parsePart 原样）：
     * 文件体＝明文 UTF-8 JSON，或首两字节 0x1F8B 的 GZIP（解压后超 64MB 拒收）；
     * 根对象须有 data 对象；data.lists 在＝备份形（defaultList／loveList／tempList 三固定歌单＋
     * userList 自建歌单数组）；否则 data.list／name 在＝单歌单形；其余结构拒收。
     * 曲目字段：id／name／singer／source／interval（mm:ss）／meta（albumName／picUrl／平台原生 id）；
     * source 只认 wy／tx／kg／kw／mg（与星辰落雪平台键同串），soda 等无星辰源的按瓜子「无映射源丢弃」处理。
     */
    private static List<LxmcPlaylist> parseLxmc(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) throw new Exception("文件为空");
        String text;
        if (bytes.length >= 2 && (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF)) == 0x1F8B) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int total = 0;
                int n;
                while ((n = gzip.read(buf)) > 0) {
                    total += n;
                    if (total > 64 * 1024 * 1024) throw new IllegalArgumentException("文件解压后过大，暂不支持");
                    bos.write(buf, 0, n);
                }
                text = new String(bos.toByteArray(), "UTF-8");
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("文件解压失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            }
        } else {
            text = new String(bytes, "UTF-8");
        }
        if (text.trim().isEmpty()) throw new Exception("文件内容为空");
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new Exception("不是有效的 LX 歌单文件（JSON 解析失败）");
        }
        if (!root.has("data") || !root.get("data").isJsonObject()) {
            throw new Exception("不是有效的 LX 歌单文件（缺少 data）");
        }
        JsonObject data = root.getAsJsonObject("data");
        List<LxmcPlaylist> out = new ArrayList<>();
        if (data.has("lists") && data.get("lists").isJsonObject()) {
            JsonObject lists = data.getAsJsonObject("lists");
            addLxmcList(out, lists, "defaultList", "试听列表");
            addLxmcList(out, lists, "loveList", "我的收藏");
            addLxmcList(out, lists, "tempList", "临时列表");
            if (lists.has("userList") && lists.get("userList").isJsonArray()) {
                for (JsonElement el : lists.getAsJsonArray("userList")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String name = lxmcStr(o, "name");
                    if (name.isEmpty()) name = lxmcStr(o, "id");
                    if (name.isEmpty()) name = "LX 自建歌单";
                    out.add(new LxmcPlaylist(name, lxmcTracks(lxmcArr(o, "list"))));
                }
            }
            return out;
        }
        if (data.has("list") || data.has("name")) {
            String name = lxmcStr(data, "name");
            if (name.isEmpty()) name = "LX 歌单";
            out.add(new LxmcPlaylist(name, lxmcTracks(lxmcArr(data, "list"))));
            return out;
        }
        throw new Exception("暂不支持的 LX 歌单文件结构");
    }

    private static void addLxmcList(List<LxmcPlaylist> out, JsonObject lists, String key, String name) {
        if (lists.has(key) && lists.get(key).isJsonArray()) {
            out.add(new LxmcPlaylist(name, lxmcTracks(lists.getAsJsonArray(key))));
        }
    }

    private static JsonArray lxmcArr(JsonObject o, String key) {
        if (o != null && o.has(key) && o.get(key).isJsonArray()) return o.getAsJsonArray(key);
        return new JsonArray();
    }

    private static List<MusicTrack> lxmcTracks(JsonArray arr) {
        List<MusicTrack> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            MusicTrack t = lxmcTrack(el.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    /**
     * 单曲映射（瓜子 toSearchSong／resolvePlatformId 原样）：
     * 名空或源无映射直接丢弃；平台原生 id 按源取首个非空候选，meta 里都没有时回退曲目 id
     * （带「平台键_」前缀的剥掉前缀）；_item＝meta 全字段＋按星辰搜索条目惯例补的规范 id 字段，
     * 取链时落雪脚本按各自习惯读，通用字段由 LxMusicSource.musicInfo 另补。
     */
    private static MusicTrack lxmcTrack(JsonObject o) {
        String name = lxmcStr(o, "name");
        if (name.isEmpty()) return null;
        String source = lxmcStr(o, "source");
        if (!"wy".equals(source) && !"tx".equals(source) && !"kg".equals(source)
                && !"kw".equals(source) && !"mg".equals(source)) {
            return null;
        }
        JsonObject meta = o.has("meta") && o.get("meta").isJsonObject()
                ? o.getAsJsonObject("meta") : new JsonObject();
        String rawId = lxmcPlatformId(source, meta);
        if (rawId.isEmpty()) {
            String id = lxmcStr(o, "id");
            if (id.startsWith(source + "_")) id = id.substring(source.length() + 1);
            rawId = id;
        }
        if (rawId.isEmpty()) return null;
        MusicTrack t = new MusicTrack();
        t.id = "lx:" + source + ":" + rawId;
        t.title = name;
        t.artist = lxmcStr(o, "singer");
        t.album = lxmcStr(meta, "albumName");
        t.cover = lxmcStr(meta, "picUrl");
        t.duration = lxmcIntervalMs(lxmcStr(o, "interval"));
        t.platform = LxMusicSource.PLATFORM;
        JsonObject item = meta.deepCopy();
        switch (source) {
            case "tx":
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "kw":
                if (lxmcStr(item, "rid").isEmpty()) item.addProperty("rid", rawId);
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "kg":
                if (lxmcStr(item, "hash").isEmpty()) item.addProperty("hash", rawId);
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                break;
            case "wy":
                if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                break;
            default: // mg
                if (rawId.contains(":")) {
                    String[] parts = rawId.split(":", 2);
                    if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", parts[0]);
                    if (lxmcStr(item, "copyrightId").isEmpty()) item.addProperty("copyrightId", parts[1]);
                    if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                } else {
                    if (lxmcStr(item, "songmid").isEmpty()) item.addProperty("songmid", rawId);
                    if (lxmcStr(item, "id").isEmpty()) item.addProperty("id", rawId);
                }
                break;
        }
        JsonObject wrap = new JsonObject();
        wrap.addProperty("_platform", source);
        wrap.add("_item", item);
        t.rawJson = wrap.toString();
        return t;
    }

    /** 平台原生 id 候选顺序（瓜子 resolvePlatformId 原样） */
    private static String lxmcPlatformId(String source, JsonObject meta) {
        switch (source) {
            case "tx": {
                String songId = lxmcStr(meta, "songId");
                // 瓜子：songId 含字母才进候选，全数字时跳过它
                boolean songIdOk = false;
                for (int i = 0; i < songId.length(); i++) {
                    if (Character.isLetter(songId.charAt(i))) {
                        songIdOk = true;
                        break;
                    }
                }
                return lxmcFirst(lxmcStr(meta, "songmid"), lxmcStr(meta, "mid"),
                        songIdOk ? songId : "", lxmcStr(meta, "strMediaMid"));
            }
            case "kw":
                return lxmcFirst(lxmcStr(meta, "rid"), lxmcStr(meta, "hash"), lxmcFirstQualityHash(meta));
            case "kg":
                return lxmcFirst(lxmcStr(meta, "hash"), lxmcFirstQualityHash(meta));
            case "wy":
                return lxmcFirst(lxmcStr(meta, "songId"), lxmcStr(meta, "id"));
            default: { // mg
                String contentId = lxmcStr(meta, "contentId");
                String copyrightId = lxmcStr(meta, "copyrightId");
                if (!contentId.isEmpty() && !copyrightId.isEmpty()) return contentId + ":" + copyrightId;
                return lxmcFirst(contentId, copyrightId);
            }
        }
    }

    /** 瓜子 firstQualityHash：qualitys 数组里优先 type＝128k 的 hash，否则首个非空 hash */
    private static String lxmcFirstQualityHash(JsonObject meta) {
        if (!meta.has("qualitys") || !meta.get("qualitys").isJsonArray()) return "";
        String first = "";
        for (JsonElement el : meta.getAsJsonArray("qualitys")) {
            if (!el.isJsonObject()) continue;
            JsonObject q = el.getAsJsonObject();
            String hash = lxmcStr(q, "hash");
            if (hash.isEmpty()) continue;
            if ("128k".equals(lxmcStr(q, "type"))) return hash;
            if (first.isEmpty()) first = hash;
        }
        return first;
    }

    /** 瓜子 parseIntervalMs：取首个 mm:ss 匹配，转毫秒；无匹配为 0 */
    private static long lxmcIntervalMs(String interval) {
        if (interval == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3}):(\\d{1,2})").matcher(interval);
        if (!m.find()) return 0;
        try {
            long min = Long.parseLong(m.group(1));
            long sec = Long.parseLong(m.group(2));
            return (min * 60 + sec) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String lxmcFirst(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String lxmcStr(JsonObject o, String key) {
        try {
            if (o != null && o.has(key) && !o.get(key).isJsonNull()) return o.get(key).getAsString();
        } catch (Exception ignored) {
        }
        return "";
    }

    // ================= 列表 =================

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_CARD = 1;
        private static final int TYPE_PLUGIN = 2;
        private static final int TYPE_HEADER = 3;
        private static final int TYPE_SHEET = 4;
        private static final int TYPE_DL_TASK = 5;
        private static final int TYPE_SECTION = 6;
        private static final int TYPE_SHEET_HERO = 7;
        private static final int TYPE_SHEET_ACTIONS = 8;
        private static final int TYPE_SHEET_SECTION = 9;
        private static final int TYPE_SHEET_TRACK = 10;
        private static final int TYPE_CHART_FOCUS = 11;
        private static final int TYPE_CHART_SECTION = 12;
        private static final int TYPE_CHART_CARD = 13;
        private static final int TYPE_CHART_HERO = 14;
        private static final int TYPE_CHART_TRACK = 15;
        private static final int TYPE_GUESS_TITLE = 16;
        private static final int TYPE_GUESS_HERO = 17;
        private static final int TYPE_GUESS_SECTION = 18;
        private static final int TYPE_GUESS_TRACK = 19;
        /** 第二波：插件分组头（sites 列表内插项） */
        private static final int TYPE_PLUGIN_GROUP = 20;
        /** 第二波：下载已完成区头（进行中任务之后、已完成列表之前） */
        private static final int TYPE_DL_SECTION = 21;

        /** 本地音乐页首行是「导入加密歌曲」头，歌曲行整体下移一位 */
        private int headerCount() {
            return deviceHeader() ? 1 : 0;
        }

        @Override
        public int getItemViewType(int position) {
            if (deviceHeader() && position == 0) return TYPE_HEADER;
            // 第 1 项：歌单详情页＝英雄卡＋操作行＋区头＋歌曲行（英雄卡随列表滚动，与 GZ 一致）
            if (mode.equals("sheet")) {
                if (position == 0) return TYPE_SHEET_HERO;
                if (position == 1) return TYPE_SHEET_ACTIONS;
                if (position == 2) return TYPE_SHEET_SECTION;
                return TYPE_SHEET_TRACK;
            }
            // 第 3 项：榜单列表＝焦点卡＋分区头＋普通卡；榜单详情＝英雄卡＋排名行
            if (mode.equals("charts")) {
                if (position == 0) return TYPE_CHART_FOCUS;
                if (position == 1) return TYPE_CHART_SECTION;
                return TYPE_CHART_CARD;
            }
            if (mode.equals("chartDetail")) {
                if (position == 0) return TYPE_CHART_HERO;
                return TYPE_CHART_TRACK;
            }
            // 第 9 项：猜你喜欢＝标题＋英雄卡＋精选区头＋精选行（标题与英雄卡随列表滚动，与 GZ 一致）
            if (mode.equals("guess")) {
                if (position == 0) return TYPE_GUESS_TITLE;
                if (position == 1) return TYPE_GUESS_HERO;
                if (position == 2) return TYPE_GUESS_SECTION;
                return TYPE_GUESS_TRACK;
            }
            // 第 8 项：本地页组视图走卡、歌曲分组视图的区块头走分组头
            if (mode.equals("device") && deviceView != DEVICE_VIEW_SONGS) return TYPE_CARD;
            if (mode.equals("device") && deviceSectionPos.contains(position)) return TYPE_SECTION;
            if (mode.equals("download") && position < dlTasks.size()) return TYPE_DL_TASK;
            // 第二波：下载已完成区头（仅已完成非空时插在任务之后；空时不占位，空态文案管）
            if (mode.equals("download") && !tracks.isEmpty() && position == dlTasks.size()) return TYPE_DL_SECTION;
            if (isCardMode()) {
                // 第二波：插件分组头（sites 列表内插项，非插件行）
                if (mode.equals("sites") && cards.get(position) instanceof PluginGroup) return TYPE_PLUGIN_GROUP;
                // 插件管理页里插件行用专用布局（名＋版本＋格式标签＋开关＋⋮），添加卡仍走通用卡
                if (mode.equals("sites") && cards.get(position) instanceof MusicSite) return TYPE_PLUGIN;
                // 歌单页用 GZ 歌单行（50dp 格＋名 14sp＋“N 首”11sp＋播放圆钮＋⋮），榜单页仍走通用卡
                if (mode.equals("sheets") && cards.get(position) instanceof MusicPlaylist) return TYPE_SHEET;
                return TYPE_CARD;
            }
            return TYPE_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_DL_TASK) {
                return new DlTaskHolder(inf.inflate(R.layout.item_music_download_task, parent, false));
            }
            if (viewType == TYPE_DL_SECTION) {
                // 第二波：下载已完成区头（纯程序化 TextView，不另起布局文件；字号沿用歌单区头 17sp，瓜子该区头字号未抠到）
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(17);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setTextColor(0xFF1C1C1E);
                tv.setPadding(dp(4), dp(12), dp(4), dp(8));
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new DlSectionHolder(tv);
            }
            if (viewType == TYPE_PLUGIN_GROUP) {
                // 瓜子分组头（图 112927）：无卡底、蓝点＋组名＋右端计数；旧卡片＋箭头形制与瓜子不符，已改；
                // 分组头不可折叠（旧箭头仅装饰），改后无点击行为。
                LinearLayout row = new LinearLayout(parent.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                View dot = new View(parent.getContext());
                android.graphics.drawable.GradientDrawable dotBg =
                        new android.graphics.drawable.GradientDrawable();
                dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dotBg.setColor(0xFF42A5F5);
                dot.setBackground(dotBg);
                row.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(14);
                tv.setTextColor(0xFF1C1C1E);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = dp(8);
                row.addView(tv, tlp);
                row.addView(new View(parent.getContext()),
                        new LinearLayout.LayoutParams(0, 0, 1f));
                TextView count = new TextView(parent.getContext());
                count.setTextSize(13);
                count.setTextColor(0xFF8E8E93);
                row.addView(count, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                row.setLayoutParams(lp);
                return new GroupHolder(row, tv, count);
            }
            if (viewType == TYPE_SECTION) {
                return new SectionHolder(inf.inflate(R.layout.item_music_device_section, parent, false));
            }
            if (viewType == TYPE_SHEET_HERO) {
                return new SheetHeroHolder(inf.inflate(R.layout.item_music_sheet_hero, parent, false));
            }
            if (viewType == TYPE_SHEET_ACTIONS) {
                return new SheetActionsHolder(inf.inflate(R.layout.item_music_sheet_actions, parent, false));
            }
            if (viewType == TYPE_SHEET_SECTION) {
                return new SheetSectionHolder(inf.inflate(R.layout.item_music_sheet_section, parent, false));
            }
            if (viewType == TYPE_SHEET_TRACK) {
                return new SheetTrackHolder(inf.inflate(R.layout.item_music_sheet_track, parent, false));
            }
            if (viewType == TYPE_CHART_FOCUS) {
                return new ChartFocusHolder(inf.inflate(R.layout.item_music_chart_focus, parent, false));
            }
            if (viewType == TYPE_CHART_SECTION) {
                return new ChartSectionHolder(inf.inflate(R.layout.item_music_chart_section, parent, false));
            }
            if (viewType == TYPE_CHART_CARD) {
                return new ChartCardHolder(inf.inflate(R.layout.item_music_chart_card, parent, false));
            }
            if (viewType == TYPE_CHART_HERO) {
                return new ChartHeroHolder(inf.inflate(R.layout.item_music_chart_hero, parent, false));
            }
            if (viewType == TYPE_CHART_TRACK) {
                return new ChartTrackHolder(inf.inflate(R.layout.item_music_chart_track, parent, false));
            }
            if (viewType == TYPE_GUESS_TITLE) {
                return new GuessTitleHolder(inf.inflate(R.layout.item_music_guess_title, parent, false));
            }
            if (viewType == TYPE_GUESS_HERO) {
                return new GuessHeroHolder(inf.inflate(R.layout.item_music_guess_hero, parent, false));
            }
            if (viewType == TYPE_GUESS_SECTION) {
                return new GuessSectionHolder(inf.inflate(R.layout.item_music_guess_section, parent, false));
            }
            if (viewType == TYPE_GUESS_TRACK) {
                return new GuessTrackHolder(inf.inflate(R.layout.item_music_guess_track, parent, false));
            }
            if (viewType == TYPE_PLUGIN) {
                return new PluginHolder(inf.inflate(R.layout.item_music_plugin, parent, false));
            }
            if (viewType == TYPE_SHEET) {
                return new PlaylistHolder(inf.inflate(R.layout.item_music_playlist_row, parent, false));
            }
            if (viewType == TYPE_CARD || viewType == TYPE_HEADER) {
                return new CardHolder(inf.inflate(R.layout.item_music_nav, parent, false));
            }
            return new TrackHolder(inf.inflate(R.layout.item_music_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (deviceHeader() && position == 0) {
                bindImportRow((CardHolder) h);
                return;
            }
            // 第 1 项：歌单详情页四类行
            if (mode.equals("sheet")) {
                if (h instanceof SheetHeroHolder) bindSheetHero((SheetHeroHolder) h);
                else if (h instanceof SheetActionsHolder) bindSheetActions((SheetActionsHolder) h);
                else if (h instanceof SheetSectionHolder) bindSheetSection((SheetSectionHolder) h);
                else bindSheetTrack((SheetTrackHolder) h, trackIndex(position));
                return;
            }
            // 第 3 项：榜单列表与榜单详情
            if (mode.equals("charts")) {
                if (h instanceof ChartFocusHolder) bindChartFocus((ChartFocusHolder) h, (MusicChart) cards.get(0));
                else if (h instanceof ChartSectionHolder) bindChartSection((ChartSectionHolder) h);
                else bindChartCard((ChartCardHolder) h, (MusicChart) cards.get(position - 1));
                return;
            }
            if (mode.equals("chartDetail")) {
                if (h instanceof ChartHeroHolder) bindChartHero((ChartHeroHolder) h);
                else bindChartTrack((ChartTrackHolder) h, trackIndex(position));
                return;
            }
            // 第 9 项：猜你喜欢四类行
            if (mode.equals("guess")) {
                if (h instanceof GuessTitleHolder) return;
                else if (h instanceof GuessHeroHolder) bindGuessHero((GuessHeroHolder) h);
                else if (h instanceof GuessSectionHolder) bindGuessSection((GuessSectionHolder) h);
                else bindGuessTrack((GuessTrackHolder) h, trackIndex(position));
                return;
            }
            // 第 8 项：本地组视图的卡要减设备头；分组区块头单独绑
            if (mode.equals("device") && deviceView != DEVICE_VIEW_SONGS) {
                bindCard((CardHolder) h, position - headerCount());
                return;
            }
            if (mode.equals("device") && deviceSectionPos.contains(position)) {
                bindDeviceSection((SectionHolder) h, deviceSectionPos.indexOf(position));
                return;
            }
            if (h instanceof DlTaskHolder) bindDlTask((DlTaskHolder) h, position);
            else if (h instanceof DlSectionHolder) {
                // 第二波：下载已完成区头（搜时「搜索结果」，平时「全部歌曲」；瓜子 DownloadsScreen 区头口径）
                ((DlSectionHolder) h).title.setText(dlSearchKw.isEmpty()
                        ? getString(R.string.wave2_section_all_songs)
                        : getString(R.string.wave2_section_search_result));
            }
            else if (h instanceof GroupHolder) {
                // 第二波：插件分组头（cards 内插项，位置即 cards 下标）；右端计数＝其后连续插件卡数
                Object o = cards.get(position);
                ((GroupHolder) h).title.setText(o instanceof PluginGroup ? ((PluginGroup) o).title : "");
                int n = 0;
                for (int j = position + 1; j < cards.size(); j++) {
                    if (cards.get(j) instanceof MusicSite) n++;
                    else break;
                }
                ((GroupHolder) h).count.setText(String.valueOf(n));
            }
            else if (h instanceof PluginHolder) bindPlugin((PluginHolder) h, position);
            else if (h instanceof PlaylistHolder) bindPlaylistRow((PlaylistHolder) h, position);
            else if (h instanceof CardHolder) bindCard((CardHolder) h, position);
            else bindTrack((TrackHolder) h, trackIndex(position));
        }

        /** 歌曲行在列表里的下标：download 模式前面有进行中任务行，要减掉；歌单详情页前面有英雄卡／操作行／区头 3 行；本地分组视图还要减掉前面的区块头；其它模式减设备头 */
        private int trackIndex(int position) {
            if (mode.equals("download")) {
                // 第二波：已完成区头占 1 位（仅已完成非空时存在；区头位本身不走 trackIndex）
                int idx = position - dlTasks.size();
                if (!tracks.isEmpty() && position > dlTasks.size()) idx--;
                return idx;
            }
            if (mode.equals("sheet")) return position - 3;
            if (mode.equals("chartDetail")) return position - 1;
            if (mode.equals("guess")) return position - 3;
            if (mode.equals("device")) {
                int sectionsBefore = 0;
                for (int p : deviceSectionPos) if (p < position) sectionsBefore++;
                return position - headerCount() - sectionsBefore;
            }
            return position - headerCount();
        }

        /** 第三波 B②：插件是否官方预置（按播种地址认，用户改名／改地址后不算官方）。 */
        private boolean isOfficialSite(MusicSite s) {
            if (s == null || s.srcUrl == null) return false;
            for (MusicSite b : MusicSourceManager.builtinSites()) {
                if (s.srcUrl.equals(b.srcUrl)) return true;
            }
            return false;
        }

        /** 插件图标色：插件模型无图标字段，行里一律灰拼图是绘画感的根；按源地址（改名不变）哈希进固定彩色盘，每插件一色；⋮ 菜单行灰拼图不动。 */
        private int pluginColor(MusicSite s) {
            int[] palette = {0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF66BB6A, 0xFF26A69A, 0xFF42A5F5, 0xFF7E57C2, 0xFFEC407A};
            String key = s.srcUrl != null && !s.srcUrl.isEmpty() ? s.srcUrl : (s.name == null ? "" : s.name);
            return palette[Math.abs(key.hashCode()) % palette.length];
        }

        /** 插件行：名＋版本号小字＋格式标签；右侧金开关（真接线启停）＋⋮菜单 */
        private void bindPlugin(PluginHolder h, int position) {
            MusicSite s = (MusicSite) cards.get(position);
            h.name.setText(s.name == null ? "" : s.name);
            if (h.pluginIcon != null) {
                h.pluginIcon.setImageResource(R.drawable.ic_music_puzzle);
                int c = pluginColor(s);
                h.pluginIcon.setImageTintList(ColorStateList.valueOf(c));
                // 瓜子行图标＝彩色淡底方块＋同色拼图：底色＝行色 12% 透明度、圆角 12dp
                android.graphics.drawable.GradientDrawable iconBg =
                        new android.graphics.drawable.GradientDrawable();
                iconBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                iconBg.setCornerRadius(dp(12));
                iconBg.setColor((c & 0x00FFFFFF) | 0x1F000000);
                h.pluginIcon.setBackground(iconBg);
            }
            boolean lx = MusicSourceManager.isLx(s);
            h.formatTag.setText(lx ? "落雪" : "FreeJS");
            h.formatTag.setTextColor(lx ? 0xFFFA243C : 0xFF9AA7B8);
            // M14：副文案串单行“官方 · 版本 · 作者 · 耗时”（只有真实数据的段才拼；
            // 官方按播种地址认（isOfficialSite，不读字段）；加密／配置项无数据来源，字段已删、不出段；
            // 耗时是单次检测耗时，均耗时未存检测层，不冒充平均值）
            StringBuilder sub = new StringBuilder();
            if (isOfficialSite(s)) sub.append("官方");
            if (s.version != null && !s.version.isEmpty()) {
                String v = s.version.trim();
                while (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
                if (sub.length() > 0) sub.append(" · ");
                sub.append("v").append(v);
            }
            if (s.author != null && !s.author.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(s.author);
            }
            MusicPluginTest latencyTest = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (latencyTest != null && latencyTest.latencyMs > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("耗时 ").append(latencyTest.latencyMs).append("ms");
            }
            if (sub.length() > 0) {
                h.version.setVisibility(View.VISIBLE);
                h.version.setText(sub.toString());
            } else {
                h.version.setVisibility(View.GONE);
            }
            h.pluginSwitch.setOnCheckedChangeListener(null);
            h.pluginSwitch.setChecked(s.enabled);
            h.pluginSwitch.setOnCheckedChangeListener((v, checked) -> {
                MusicActivity.clearHomeChartCache();
                Task.execute(() -> MusicSourceManager.get().setEnabled(s.srcUrl, checked));
            });
            // C4：四态显示＋检测中态（行内钮定为唯一检测入口，pluginMenu 不另加检测项）
            MusicPluginTest test = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (MusicSourceManager.get().isTesting(s.srcUrl)) {
                h.testStatus.setText("检测中…");
                h.testStatus.setTextColor(0xFF8E8E93);
                if (h.statusDot != null) setStatusDot(h.statusDot, 0xFF8E8E93);
                h.pluginTest.setText("检测中…");
                h.pluginTest.setEnabled(false);
            } else {
                if (test == null) test = new MusicPluginTest();
                h.testStatus.setText(test.label());
                h.testStatus.setTextColor(test.labelColor());
                if (h.statusDot != null) setStatusDot(h.statusDot, test.labelColor());
                h.pluginTest.setText("检测");
                h.pluginTest.setEnabled(true);
            }
            // P12：检测失败且 message 非空时行内另起一行显 message（11sp，色与状态色一致）
            MusicPluginTest rowTest = MusicSourceManager.get().loadPluginTest(s.srcUrl);
            if (rowTest == null) rowTest = new MusicPluginTest();
            if (!MusicSourceManager.get().isTesting(s.srcUrl)
                    && rowTest.state == MusicPluginTest.State.FAILED
                    && rowTest.message != null && !rowTest.message.isEmpty()) {
                h.pluginError.setVisibility(View.VISIBLE);
                h.pluginError.setText(rowTest.message);
                h.pluginError.setTextColor(rowTest.labelColor());
            } else {
                h.pluginError.setVisibility(View.GONE);
            }
            h.pluginTest.setOnClickListener(v -> startPluginTest(s));
            View.OnClickListener menu = v -> pluginMenu(s);
            h.pluginMore.setOnClickListener(menu);
            h.itemView.setOnClickListener(menu);
        }

        /** 第三波 B②：状态圆点着色（mutate 后改色，不污染其它行的共用 drawable）。 */
        private void setStatusDot(View dot, int color) {
            try {
                android.graphics.drawable.Drawable d = dot.getBackground();
                if (d == null) return;
                d = d.mutate();
                if (d instanceof android.graphics.drawable.GradientDrawable) {
                    ((android.graphics.drawable.GradientDrawable) d).setColor(color);
                }
            } catch (Throwable ignored) {
            }
        }

        /** 第 11 项：GZ 歌单行绑定（多选态下行点按变勾选，播放钮播整单，⋮走重命名／删除） */
        /** 歌单马赛克格加载：纯铺满裁切，不套圆角（圆角由 coverBox 的 clipToOutline 一次裁）。 */
        private void loadMosaicCover(String url, android.widget.ImageView view) {
            try {
                com.bumptech.glide.Glide.with(view).clear(view);
                com.bumptech.glide.Glide.with(view).load(url).centerCrop().into(view);
            } catch (Throwable ignored) {
            }
        }

        private void bindPlaylistRow(PlaylistHolder h, int position) {
            MusicPlaylist p = (MusicPlaylist) cards.get(position);
            boolean sel = sheetsSelected.contains(p.getId());
            h.name.setText(p.getName());
            String count = p.trackCount >= 0 ? p.trackCount + " 首" : "";
            h.status.setText(count + (sel ? " · 已选" : ""));
            // 歌单页重做：封面＝瓜子 CoverMosaic 口径。缓存有封面走马赛克（1 张铺满、2 张以上 2×2，
            // 缺格露底灰）；无封面（空歌单／缓存未到）回落原彩色圆盘图标。回收行先清在途加载，防旧图迟到。
            java.util.List<String> covers = sheetCovers.get(p.getId());
            boolean mosaic = covers != null && !covers.isEmpty();
            h.coverBox.setBackgroundResource(mosaic ? R.drawable.bg_playlist_mosaic : 0);
            h.coverBox.setClipToOutline(mosaic);
            if (!mosaic) {
                h.icon.setVisibility(View.VISIBLE);
                h.mosaicSingle.setVisibility(View.GONE);
                h.mosaicGrid.setVisibility(View.GONE);
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(sel ? 0xFFFA243C : 0xFF3B82F6));
            } else {
                h.icon.setVisibility(View.GONE);
                if (covers.size() == 1) {
                    h.mosaicSingle.setVisibility(View.VISIBLE);
                    h.mosaicGrid.setVisibility(View.GONE);
                    loadMosaicCover(covers.get(0), h.mosaicSingle);
                } else {
                    h.mosaicSingle.setVisibility(View.GONE);
                    h.mosaicGrid.setVisibility(View.VISIBLE);
                    android.widget.ImageView[] qs = {h.mosaic1, h.mosaic2, h.mosaic3, h.mosaic4};
                    for (int qi = 0; qi < 4; qi++) {
                        if (qi < covers.size()) loadMosaicCover(covers.get(qi), qs[qi]);
                        else {
                            try {
                                com.bumptech.glide.Glide.with(qs[qi]).clear(qs[qi]);
                            } catch (Throwable ignored) {
                            }
                            qs[qi].setImageDrawable(null);
                        }
                    }
                }
            }
            h.rowPlay.setBackground(circle(0xFFFA243C));
            h.rowPlay.setVisibility(sheetsMulti ? View.GONE : View.VISIBLE);
            h.rowMore.setVisibility(sheetsMulti ? View.GONE : View.VISIBLE);
            View.OnClickListener toggle = v -> {
                if (sheetsSelected.contains(p.getId())) sheetsSelected.remove(p.getId());
                else sheetsSelected.add(p.getId());
                binding.tvPlayAll.setText("删除所选 (" + sheetsSelected.size() + ")");
                int pos = h.getBindingAdapterPosition();
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) adapter.notifyItemChanged(pos);
            };
            h.itemView.setOnClickListener(sheetsMulti ? toggle
                    : v -> startSheet(MusicListActivity.this, p.getId(), p.getName()));
            h.itemView.setOnLongClickListener(v -> {
                if (sheetsMulti) return false;
                sheetLongPress(p);
                return true;
            });
            h.rowPlay.setOnClickListener(v -> MusicLibrary.playlistTracks(p.getId(), list -> {
                if (list == null || list.isEmpty()) {
                    Notify.show(R.string.music_no_track);
                    return;
                }
                MusicController.get().play(new ArrayList<>(list), 0, p.getName(), "list", listRouteParams());
            }));
            h.rowMore.setOnClickListener(v -> sheetLongPress(p));
        }

        private void bindCard(CardHolder h, int position) {
            // 本地页回炉：回收行先复位（图标显、马赛克位藏），各分支只在需要时开马赛克，防旧图残留到别的卡。
            h.icon.setVisibility(View.VISIBLE);
            h.mosaicSingle.setVisibility(View.GONE);
            h.mosaicGrid.setVisibility(View.GONE);
            h.coverBox.setBackgroundResource(0);
            h.coverBox.setClipToOutline(false);
            Object o = cards.get(position);
            if (o instanceof NewPlaylistCard) {
                h.name.setText(R.string.music_new_playlist);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_plus);
                h.icon.setBackground(circle(0xFF22C55E));
                h.itemView.setOnClickListener(v -> inputPlaylistName(name ->
                        MusicLibrary.createPlaylist(name, p -> loadSheets())));
            } else if (o instanceof ImportPlaylistCard) {
                h.name.setText(R.string.music_import_playlist);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_download);
                h.icon.setBackground(circle(0xFFFA243C));
                h.itemView.setOnClickListener(v -> showImportPlaylistDialog());
            } else if (o instanceof MusicPlaylist) {
                MusicPlaylist p = (MusicPlaylist) o;
                h.name.setText(p.getName());
                h.status.setText(p.trackCount >= 0 ? p.trackCount + " 首" : "");
                h.icon.setImageResource(R.drawable.ic_music_queue);
                h.icon.setBackground(circle(0xFF3B82F6));
                h.itemView.setOnClickListener(v -> startSheet(MusicListActivity.this, p.getId(), p.getName()));
                h.itemView.setOnLongClickListener(v -> {
                    sheetLongPress(p);
                    return true;
                });
            } else if (o instanceof MusicChart) {
                MusicChart c = (MusicChart) o;
                h.name.setText(c.name);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_eq);
                h.icon.setBackground(circle(0xFFA855F7));
                h.itemView.setOnClickListener(v -> openChart(c));
            } else if (o instanceof DeviceGroupCard) {
                // 第 8 项：本地组卡（点卡进该组的歌曲列，筛选条可清掉回到全部）
                // 本地页回炉：组卡封面＝瓜子合集行做法（成员封面马赛克，1 张铺满、2 张以上 2×2；
                // 无封面回落原彩色圆盘图标；圆角与底复用歌单马赛克件）。
                DeviceGroupCard g = (DeviceGroupCard) o;
                h.name.setText(g.key);
                h.status.setText(g.count + " 首");
                int color = g.view == DEVICE_VIEW_ALBUMS ? 0xFF3B82F6
                        : g.view == DEVICE_VIEW_ARTISTS ? 0xFF22C55E : 0xFFA855F7;
                if (g.covers == null || g.covers.isEmpty()) {
                    h.icon.setImageResource(R.drawable.ic_music_queue);
                    h.icon.setBackground(circle(color));
                } else {
                    h.icon.setVisibility(View.GONE);
                    h.coverBox.setBackgroundResource(R.drawable.bg_playlist_mosaic);
                    h.coverBox.setClipToOutline(true);
                    if (g.covers.size() == 1) {
                        h.mosaicSingle.setVisibility(View.VISIBLE);
                        loadMosaicCover(g.covers.get(0), h.mosaicSingle);
                    } else {
                        h.mosaicGrid.setVisibility(View.VISIBLE);
                        android.widget.ImageView[] qs = {h.mosaic1, h.mosaic2, h.mosaic3, h.mosaic4};
                        for (int qi = 0; qi < 4; qi++) {
                            if (qi < g.covers.size()) loadMosaicCover(g.covers.get(qi), qs[qi]);
                            else {
                                try {
                                    com.bumptech.glide.Glide.with(qs[qi]).clear(qs[qi]);
                                } catch (Throwable ignored) {
                                }
                                qs[qi].setImageDrawable(null);
                            }
                        }
                    }
                }
                h.itemView.setOnClickListener(v -> {
                    deviceGroupType = g.view == DEVICE_VIEW_ALBUMS ? 0
                            : g.view == DEVICE_VIEW_ARTISTS ? 1 : 2;
                    deviceGroupKey = g.key;
                    deviceView = DEVICE_VIEW_SONGS;
                    refreshDeviceDisplay();
                });
            } else if (o instanceof SearchGroupCard) {
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
                h.itemView.setOnClickListener(v -> startSheet(MusicListActivity.this, g.id, g.name));
            }
            h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }

        private void openChart(MusicChart chart) {
            // 第 3 项：榜单详情开新页（旧的同页切 tracks 已废弃，详情有自己的英雄卡＋排名行）
            openChartDetail(chart);
        }

        private void sheetLongPress(MusicPlaylist p) {
            List<String> opts = new ArrayList<>();
            opts.add("重命名");
            opts.add("删除歌单");
            MusicOptionsDialog.show(MusicListActivity.this, p.getName(), opts, -1, idx -> {
                if (idx == 0) {
                    inputPlaylistName(name -> MusicLibrary.renamePlaylist(p.getId(), name, () -> loadSheets()));
                } else {
                    MusicLibrary.deletePlaylist(p.getId(), () -> loadSheets());
                }
            });
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
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1C1C1E);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            // 第 7 项尾巴：下载多选态（选中行同正在播行的底）
            boolean dlSel = mode.equals("download") && t.id != null && dlSelected.contains(t.id);
            if (dlSel) h.itemView.setBackgroundResource(R.drawable.card_music_current);
            if (mode.equals("device")) {
                // 第 8 项：本地行尾音质徽标 9sp（GZ LocalQualityBadge 9sp、胶囊内边距 4/1dp；
                // GZ 档位判定要逐文件读采样率/位深/码率，扫描会变慢，星辰侧显真实格式：只显编码名（flac＝FLAC），其余显扩展名，不冒充档位）
                String badge = deviceBadge(t);
                if (!badge.isEmpty()) {
                    h.badge.setVisibility(View.VISIBLE);
                    h.badge.setText(badge);
                    h.badge.setTextSize(9);
                    int p4 = dp(4);
                    int p1 = dp(1);
                    h.badge.setPadding(p4, p1, p4, p1);
                } else {
                    h.badge.setVisibility(View.GONE);
                }
            } else if (mode.equals("history") && isTrackCached(t)) {
                // 第二波：历史行「已缓存」标签（瓜子 HistoryScreenKt 内边距 7/3dp、圆角 8dp；
                // 口径确证：瓜子已缓存＝音频已落本地；星辰判定＝非设备库曲目且 localPath 文件真实存在——
                // 播放历史 JSON 持久化整首曲目（含 localPath），下载／本地文件曲目播过即带路径；在线曲目无路径不贴。
                // 标签字号瓜子侧未抠到，沿用行内徽标 11sp；底沿用行内徽标底，圆角差（4dp vs 瓜子 8dp）见交卷。）
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(R.string.wave2_cached);
                h.badge.setTextSize(11);
                h.badge.setTextColor(0xFF8E8E93);
                int p7 = dp(7);
                int p3 = dp(3);
                h.badge.setPadding(p7, p3, p7, p3);
            } else if (t.qualityTag != null && !t.qualityTag.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(t.qualityTag);
                h.badge.setTextSize(11);
                int p6 = dp(6);
                int p2 = dp(2);
                h.badge.setPadding(p6, p2, p6, p2);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            if (mode.equals("download")) {
                // 第 7 项尾巴：已下载行第二行＝歌手 · 已下载 · 大小（实测文件大小）
                String size = "";
                try {
                    if (t.localPath != null && !t.localPath.isEmpty()) {
                        size = com.fongmi.android.tv.music.MusicCache.formatSize(
                                new java.io.File(t.localPath).length());
                    }
                } catch (Throwable ignored) {
                }
                h.artist.setText(artistDisplay(t.displayArtist()) + " · 已下载" + (size.isEmpty() ? "" : " · " + size));
            } else if (mode.equals("device")) {
                // 本页对照修：本地行第二行＝歌手 · 专辑（GZ 第二字段疑似专辑；无专辑时只显歌手）
                String second = t.displayArtist();
                if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(second)) second = getString(R.string.music_unknown_artist);
                if (t.album != null && !t.album.trim().isEmpty()) second += " · " + t.album.trim();
                h.artist.setText(second);
            } else {
                // 第三波 R1/R2：通用行第二行统一拼“歌手 · 专辑”（有专辑才拼，与本地／歌单行
                // 口径一致）；当前行统一加在播前缀（正在播放／已暂停，与收藏／历史行口径一致，
                // 选了“前缀”口径，未做封面在播动效）。
                StringBuilder second = new StringBuilder();
                if (isCurrent) second.append(MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
                second.append(artistDisplay(t.displayArtist()));
                if (t.album != null && !t.album.trim().isEmpty()) second.append(" · ").append(t.album.trim());
                // 搜索页重做（结果混一起）：默认「全部来源」是多源合并池，行内此前无来源标识，
                // 同名同歌手的不同源混成一堆分不清。搜索模式第二行末尾补来源名（星辰自有信息，
                // 瓜子单后端无此物）；行高由封面定、第二行单行省略，补字不改行高、不跳字。
                if (mode.equals("search")) {
                    String src = searchSiteNameForTrack(t);
                    if (!src.isEmpty()) second.append(" · ").append(src);
                }
                h.artist.setText(second.toString());
            }
            h.duration.setText(fmt(t.duration));
            if (t.cover == null || t.cover.isEmpty()) {
                // 没封面地址才上素图（素灰底＋歌名排字）；有地址的一律走加载、
                // 失败保持透明。列表是回收行，先清掉上一行可能在途的加载请求，
                // 防旧封面迟到盖掉素图
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                // 第二波：共用行封面圆角 8→12dp（瓜子搜索／收藏／历史／下载／本地行封面均为 12dp；
                // ImgUtil.loadRounded 写死 8dp，走本页 12dp helper；横内边距 4→14dp 另见 item_music_track.xml）
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            if (mode.equals("download") && !dlSelected.isEmpty()) {
                // 多选态下行点按变勾选（与歌单页多选口径一致）；⋮ 仍进菜单（详情／删除）
                h.itemView.setOnClickListener(v -> {
                    if (t.id != null) {
                        if (dlSelected.contains(t.id)) dlSelected.remove(t.id);
                        else dlSelected.add(t.id);
                    }
                    refreshDlSelectText();
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
                h.more.setImageResource(R.drawable.ic_music_more);
                int p12d = dp(12);
                h.more.setPadding(p12d, p12d, p12d, p12d);
                h.more.setOnClickListener(v -> onMore(t, position));
            } else if (favHistMulti && (mode.equals("favorite") || mode.equals("history"))) {
                // 第 6 项：收藏／历史多选态（行点按变勾选；行尾 24dp 选中圆圈，选中红底、未选灰框）
                boolean sel = t.id != null && favHistSelected.contains(t.id);
                android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
                circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int s24 = dp(24);
                circle.setSize(s24, s24);
                if (sel) circle.setColor(0xFFFA243C);
                else circle.setStroke(dp(2), 0xFF9AA7B8);
                h.more.setImageDrawable(circle);
                int p10 = dp(10);
                h.more.setPadding(p10, p10, p10, p10);
                android.view.View.OnClickListener toggle = v -> {
                    if (t.id != null) {
                        if (favHistSelected.contains(t.id)) favHistSelected.remove(t.id);
                        else favHistSelected.add(t.id);
                    }
                    refreshFavHistMultiText();
                    if (adapter != null) adapter.notifyDataSetChanged();
                };
                h.itemView.setOnClickListener(toggle);
                h.more.setOnClickListener(toggle);
            } else {
                h.itemView.setOnClickListener(v -> playTrack(position));
                // 回收行复位：多选圆圈换回 ⋮ 图标
                h.more.setImageResource(R.drawable.ic_music_more);
                int p12 = dp(12);
                h.more.setPadding(p12, p12, p12, p12);
                h.more.setOnClickListener(v -> onMore(t, position));
            }
        }

        /** 第 7 项尾巴：进行中任务行绑定（名＋GZ 状态文案＋进度＋34dp 圆钮：暂停／继续／取消，图标 20dp；瓜子 DownloadsScreenKt 圆钮口径） */
        private void bindDlTask(DlTaskHolder h, int position) {
            MusicDownloadStore.LiveTask t = dlTasks.get(position);
            h.name.setText(t.name == null ? "" : t.name);
            h.status.setText(t.statusText());
            h.progress.setProgress(Math.max(0, Math.min(100, t.progress)));
            int st = t.status;
            if (st == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_PAUSED) {
                h.btnPause.setVisibility(View.VISIBLE);
                h.btnPause.setImageResource(R.drawable.ic_music_play);
                h.btnPause.setContentDescription("继续下载");
                h.btnPause.setOnClickListener(v -> {
                    com.fongmi.android.tv.utils.download.DownloadManager.get().resume(t.taskId);
                    refreshDlTasks();
                });
            } else if (st == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_WAITING
                    || st == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_DOWNLOADING) {
                h.btnPause.setVisibility(View.VISIBLE);
                h.btnPause.setImageResource(R.drawable.ic_music_pause);
                h.btnPause.setContentDescription("暂停下载");
                h.btnPause.setOnClickListener(v -> {
                    com.fongmi.android.tv.utils.download.DownloadManager.get().pause(t.taskId);
                    refreshDlTasks();
                });
            } else {
                h.btnPause.setVisibility(View.GONE);
            }
            // 下载页重做补齐：两圆钮按压 0.9（布局自留的未实现项）；setOnTouchListener 与点击监听共存（返回 false 不吞点击）。
            pressScale(h.btnPause, 0.9f);
            pressScale(h.btnCancel, 0.9f);
            h.btnCancel.setImageResource(R.drawable.ic_music_close);
            h.btnCancel.setContentDescription("取消下载");
            h.btnCancel.setOnClickListener(v -> {
                // GZ 单任务取消确认文案
                new MaterialAlertDialogBuilder(MusicListActivity.this)
                        .setTitle("取消下载")
                        .setMessage("确定取消「" + (t.name == null ? "" : t.name) + "」的下载吗？已下载的临时内容将被删除，无法继续恢复。")
                        .setPositiveButton("取消下载", (d, which) -> {
                            MusicDownloadStore.cancelLive(t.taskId);
                            refreshDlTasks();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        /** 第二波：历史行「已缓存」判定（见 bindTrack 注释口径） */
        private boolean isTrackCached(MusicTrack t) {
            if (t == null) return false;
            if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(t.platform)) return false;
            if (t.localPath == null || t.localPath.isEmpty()) return false;
            try {
                return new java.io.File(t.localPath).isFile();
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 第 8 项：本地分组区块头绑定（组名＋N 首＋右侧升/降序切换，点切换即重排） */
        private void bindDeviceSection(SectionHolder h, int sectionIdx) {
            h.name.setText(deviceSectionTitles.get(sectionIdx));
            h.count.setText(deviceSectionCounts.get(sectionIdx) + " 首");
            h.direction.setText(deviceAsc ? "升序" : "降序");
            h.direction.setOnClickListener(v -> {
                deviceAsc = !deviceAsc;
                refreshDeviceDisplay();
            });
        }

        class SectionHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView count;
            final TextView direction;

            SectionHolder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                count = v.findViewById(R.id.count);
                direction = v.findViewById(R.id.direction);
            }
        }

        // ================= 第 1 项：歌单详情页 =================

        class SheetHeroHolder extends RecyclerView.ViewHolder {
            final ImageView heroCover;
            final TextView heroName;
            final TextView heroDesc;

            SheetHeroHolder(View v) {
                super(v);
                heroCover = v.findViewById(R.id.heroCover);
                heroName = v.findViewById(R.id.heroName);
                heroDesc = v.findViewById(R.id.heroDesc);
            }
        }

        class SheetActionsHolder extends RecyclerView.ViewHolder {
            final View normalRow;
            final View multiRow;
            final TextView btnShuffle;
            final TextView btnPlayAll;
            final TextView btnFav;
            final TextView btnDeleteSel;
            final TextView btnCancelMulti;
            final TextView tvResume;

            SheetActionsHolder(View v) {
                super(v);
                normalRow = v.findViewById(R.id.normalRow);
                multiRow = v.findViewById(R.id.multiRow);
                btnShuffle = v.findViewById(R.id.btnShuffle);
                btnPlayAll = v.findViewById(R.id.btnPlayAll);
                btnFav = v.findViewById(R.id.btnFav);
                btnDeleteSel = v.findViewById(R.id.btnDeleteSel);
                btnCancelMulti = v.findViewById(R.id.btnCancelMulti);
                tvResume = v.findViewById(R.id.tvResume);
            }
        }

        class SheetSectionHolder extends RecyclerView.ViewHolder {
            final TextView count;

            SheetSectionHolder(View v) {
                super(v);
                count = v.findViewById(R.id.count);
            }
        }

        class SheetTrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final ImageView more;

            SheetTrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                more = v.findViewById(R.id.more);
            }
        }

        /** 第 1 项：歌单英雄卡绑定（背景高 0.56×屏高；封面＝自定义＞首首歌封面＞灰底；简介仅非空时显示） */
        private void bindSheetHero(SheetHeroHolder h) {
            int screenH = h.itemView.getResources().getDisplayMetrics().heightPixels;
            android.view.ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (lp != null) {
                lp.height = (int) (screenH * 0.56f);
                h.itemView.setLayoutParams(lp);
            }
            h.heroName.setText(bigTitle);
            // 第二波：全幅英雄卡名照瓜子 FullBleedPlaylistHero 确值：24sp／800／字距 −0.5sp（24sp × −0.021em）；
            // 名行高瓜子侧默认（MarqueeTitle 未另设行高），不加；简介 12sp／行高 18sp／0.65 透明见布局。
            h.heroName.getPaint().setFakeBoldText(true);
            h.heroName.setLetterSpacing(-0.021f);
            String desc = com.fongmi.android.tv.music.MusicStore.getSheetDesc(playlistId);
            if (desc == null || desc.isEmpty()) {
                h.heroDesc.setVisibility(View.GONE);
            } else {
                h.heroDesc.setVisibility(View.VISIBLE);
                h.heroDesc.setText(desc);
            }
            String custom = com.fongmi.android.tv.music.MusicStore.getSheetCover(playlistId);
            String cover = null;
            if (custom != null && !custom.isEmpty() && new java.io.File(custom).isFile()) {
                cover = custom;
            } else if (!sheetAll.isEmpty() && sheetAll.get(0).cover != null && !sheetAll.get(0).cover.isEmpty()) {
                cover = sheetAll.get(0).cover;
            }
            if (cover == null) {
                try {
                    com.bumptech.glide.Glide.with(h.heroCover).clear(h.heroCover);
                } catch (Throwable ignored) {
                }
                h.heroCover.setImageDrawable(null);
                h.heroCover.setBackgroundColor(0xFF3A3A3C);
            } else {
                h.heroCover.setBackgroundColor(0x00000000);
                try {
                    java.io.File f = new java.io.File(cover);
                    if (f.isFile()) {
                        com.bumptech.glide.Glide.with(h.heroCover).load(f).centerCrop().into(h.heroCover);
                    } else {
                        com.bumptech.glide.Glide.with(h.heroCover).load(cover).centerCrop().into(h.heroCover);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        /** 第 1 项：歌单操作行绑定（固定顺序：随机播放／播放全部（主按钮）／收藏歌单；续播行；多选行） */
        private void bindSheetActions(SheetActionsHolder h) {
            h.normalRow.setVisibility(sheetMulti ? View.GONE : View.VISIBLE);
            h.multiRow.setVisibility(sheetMulti ? View.VISIBLE : View.GONE);
            h.btnShuffle.setOnClickListener(v -> shufflePlay());
            h.btnPlayAll.setOnClickListener(v -> {
                if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
                else playTrack(0);
            });
            boolean fav = com.fongmi.android.tv.music.MusicStore.loadSheetFavs().contains(playlistId);
            h.btnFav.setText(fav ? "取消收藏歌单" : "收藏歌单");
            h.btnFav.setOnClickListener(v -> {
                boolean nowFav = !com.fongmi.android.tv.music.MusicStore.loadSheetFavs().contains(playlistId);
                com.fongmi.android.tv.music.MusicStore.setSheetFav(playlistId, nowFav);
                adapter.notifyItemChanged(1);
            });
            // 续播行：最近播放里第一首在本单里的，否则「暂无播放进度」
            String resume = null;
            try {
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (MusicTrack t : sheetAll) if (t.id != null) ids.add(t.id);
                for (MusicTrack r : com.fongmi.android.tv.music.MusicStore.loadRecent()) {
                    if (r.id != null && ids.contains(r.id)) {
                        resume = r.displayTitle();
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            h.tvResume.setText(resume == null ? "暂无播放进度" : "继续上次播放：" + resume);
            h.btnDeleteSel.setText("删除所选 (" + sheetSelected.size() + ")");
            h.btnDeleteSel.setOnClickListener(v -> deleteSheetSelected());
            h.btnCancelMulti.setOnClickListener(v -> setSheetMulti(false));
        }

        private void bindSheetSection(SheetSectionHolder h) {
            h.count.setText(tracks.size() + " 首");
        }

        /** 第 1 项：歌单歌曲行绑定（封面 46dp、歌名 14sp、第二行 12sp＝歌手［· 专辑］［· 平台］［· 不可播原因］） */
        private void bindSheetTrack(SheetTrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1C1C1E);
            h.title.getPaint().setFakeBoldText(isCurrent);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            StringBuilder second = new StringBuilder();
            second.append(artistDisplay(t.displayArtist()));
            if (t.album != null && !t.album.isEmpty()) second.append(" · ").append(t.album);
            String plat = sheetPlatformName(t);
            if (!plat.isEmpty()) second.append(" · ").append(plat);
            String reason = sheetUnplayableReason(t);
            if (!reason.isEmpty()) second.append(" · ").append(reason);
            h.artist.setText(second.toString());
            if (t.cover == null || t.cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            }
            if (sheetMulti) {
                boolean sel = t.id != null && sheetSelected.contains(t.id);
                android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
                circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                int s24 = dp(24);
                circle.setSize(s24, s24);
                if (sel) circle.setColor(0xFFFA243C);
                else circle.setStroke(dp(2), 0xFF9AA7B8);
                h.more.setImageDrawable(circle);
                int p6 = dp(6);
                h.more.setPadding(p6, p6, p6, p6);
                android.view.View.OnClickListener toggle = v -> {
                    if (t.id != null) {
                        if (sheetSelected.contains(t.id)) sheetSelected.remove(t.id);
                        else sheetSelected.add(t.id);
                    }
                    adapter.notifyItemChanged(1);
                    adapter.notifyDataSetChanged();
                };
                h.itemView.setOnClickListener(toggle);
                h.more.setOnClickListener(toggle);
            } else {
                h.itemView.setOnClickListener(v -> playTrack(position));
                h.more.setImageResource(R.drawable.ic_music_more);
                int p8 = dp(8);
                h.more.setPadding(p8, p8, p8, p8);
                h.more.setOnClickListener(v -> sheetRowMore(t, position));
            }
        }

        /** 第 1 项：歌单行平台名（本地／设备＝「本地」，其余走瓜子平台名，取不到则不显） */
        private String sheetPlatformName(MusicTrack t) {
            if (t == null || t.platform == null || t.platform.isEmpty()) return "";
            if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(t.platform)) return "本地";
            if (com.fongmi.android.tv.music.MusicDownloadStore.isLocal(t)) return "本地";
            return com.fongmi.android.tv.api.music.LxPlatformSearch.platformName(t.platform);
        }

        /** 第 1 项：歌单行不可播原因（GZ 原因来自服务端字段，星辰无此字段；只做实测的一种：本地／设备文件不存在） */
        private String sheetUnplayableReason(MusicTrack t) {
            try {
                if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                    if (!new java.io.File(t.localPath).exists()) return "文件不存在";
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

    /** 第 1 项：歌单行内「更多」表（GZ 行表＋任务要求的移除／换源／刷新／改封面；同步到云端需账号，排除只列不建） */
    private void sheetRowMore(MusicTrack track, int position) {
        MusicLibrary.isFavorite(track, fav -> {
            List<String> opts = new ArrayList<>();
            opts.add("播放");
            opts.add(getString(R.string.music_play_next));
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            opts.add(getString(R.string.music_download));
            opts.add("加入播放队列");
            opts.add(getString(R.string.music_add_to_playlist));
            opts.add("歌曲信息");
            opts.add("获取元数据");
            opts.add("重新匹配封面");
            opts.add("换源");
            opts.add(getString(R.string.music_remove));
            opts.add("改封面");
            MusicOptionsDialog.show(MusicListActivity.this, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) toggleFavorite(track, opts.get(2));
                else if (idx == 3) downloadTrack(track);
                else if (idx == 4) {
                    MusicController.get().appendTrack(track);
                    Notify.show("已加入播放队列");
                } else if (idx == 5) pickPlaylist(track);
                else if (idx == 6) showTrackInfo(track);
                else if (idx == 7) refreshSheetTrack(track);
                else if (idx == 8) rematchSheetCover(track);
                else if (idx == 9) switchSheetSource(track);
                else if (idx == 10) removeFromSheet(track);
                else if (idx == 11) showSheetCoverDialog();
            });
        });
    }

    /** 第 1 项：歌曲信息（歌名／歌手／专辑／时长／来源／封面有无，全是实测值） */
    private void showTrackInfo(MusicTrack track) {
        LinearLayout box = new LinearLayout(MusicListActivity.this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        String[][] rows = {
                {"歌名", track.displayTitle()},
                {"歌手", artistDisplay(track.displayArtist())},
                {"专辑", track.album == null || track.album.isEmpty() ? "未知专辑" : track.album},
                {"时长", fmtMs(track.duration)},
                {"来源", sheetPlatformName(track).isEmpty() ? "未知" : sheetPlatformName(track)},
                {"封面", track.cover == null || track.cover.isEmpty() ? "无" : "有"},
        };
        for (String[] r : rows) {
            TextView tv = new TextView(MusicListActivity.this);
            tv.setText(r[0] + "：" + r[1]);
            tv.setTextSize(14);
            tv.setTextColor(0xFF1C1C1E);
            tv.setPadding(0, dp(3), 0, dp(3));
            box.addView(tv);
        }
        new MaterialAlertDialogBuilder(MusicListActivity.this)
                .setTitle("歌曲信息")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 第 1 项：获取元数据（刷新）＝按原插件／同平台重搜同名歌，命中则原位替换全部字段；本地／设备歌暂不支持 */
    private void refreshSheetTrack(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || com.fongmi.android.tv.music.MusicDownloadStore.isLocal(track)) {
            Notify.show("本地歌曲暂不支持刷新");
            return;
        }
        Notify.show("正在刷新…");
        Task.execute(() -> {
            MusicTrack hit = null;
            try {
                hit = searchSameTrack(track);
            } catch (Throwable ignored) {
            }
            MusicTrack h = hit;
            runOnUiThread(() -> {
                if (h == null) {
                    Notify.show("没有找到匹配的歌曲");
                    return;
                }
                MusicLibrary.replaceTrackInPlaylist(playlistId, track.id, h, ok -> {
                    Notify.show(ok ? "已刷新" : "刷新失败");
                    if (ok) loadSheetTracks();
                });
            });
        });
    }

    /** 第 1 项：重新匹配封面＝重搜同名歌，只把封面换成命中那首的（其它字段不动） */
    private void rematchSheetCover(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || com.fongmi.android.tv.music.MusicDownloadStore.isLocal(track)) {
            Notify.show("本地歌曲暂不支持重新匹配封面");
            return;
        }
        Notify.show("正在匹配封面…");
        Task.execute(() -> {
            MusicTrack hit = null;
            try {
                hit = searchSameTrack(track);
            } catch (Throwable ignored) {
            }
            MusicTrack h = hit;
            runOnUiThread(() -> {
                if (h == null || h.cover == null || h.cover.isEmpty()) {
                    Notify.show("没有找到匹配的封面");
                    return;
                }
                MusicLibrary.updateTrackCover(playlistId, track.id, h.cover, () -> {
                    Notify.show("封面已更新");
                    loadSheetTracks();
                });
            });
        });
    }

    /** 第 1 项：换源＝全站搜同名歌，按平台去重（跳过本首、跳过单插件屏蔽的平台对），选一个原位替换 */
    private void switchSheetSource(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || com.fongmi.android.tv.music.MusicDownloadStore.isLocal(track)) {
            Notify.show("本地歌曲暂不支持换源");
            return;
        }
        Notify.show("正在搜索其它来源…");
        Task.execute(() -> {
            List<MusicTrack> found = new ArrayList<>();
            java.util.Set<String> seenPlatforms = new java.util.HashSet<>();
            try {
                String kw = ((track.title == null ? "" : track.title) + " "
                        + (track.artist == null ? "" : track.artist)).trim();
                for (MusicSite s : MusicRepository.sites()) {
                    if (s == null || s.srcUrl == null) continue;
                    try {
                        List<MusicTrack> r = MusicRepository.searchSite(s, kw);
                        if (r == null) continue;
                        for (MusicTrack c : r) {
                            if (c == null || c.id == null || c.id.equals(track.id)) continue;
                            if (!sameSongName(track, c)) continue;
                            String plat = c.platform == null ? "" : c.platform;
                            if (seenPlatforms.contains(plat)) continue;
                            // 第 11 项：单插件屏蔽平台＝该插件屏蔽掉的平台不进换源候选；
                            // 判据用候选的内容平台（源层 platform 是 freejs/lx 这类，与屏蔽键不同层）
                            String cp = com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(c);
                            if (com.fongmi.android.tv.music.MusicStore.isPlatformBlocked(s.srcUrl, cp != null ? cp : plat)) continue;
                            seenPlatforms.add(plat);
                            found.add(c);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    Notify.show("没有找到其它来源");
                    return;
                }
                List<String> names = new ArrayList<>();
                for (MusicTrack c : found) {
                    String plat = sheetPlatformName(c);
                    names.add((plat.isEmpty() ? "" : plat + " · ") + c.displayTitle()
                            + (c.displayArtist().isEmpty() ? "" : " - " + c.displayArtist()));
                }
                MusicOptionsDialog.show(MusicListActivity.this, "换源", names, -1, idx -> {
                    MusicTrack pick = found.get(idx);
                    MusicLibrary.replaceTrackInPlaylist(playlistId, track.id, pick, ok -> {
                        Notify.show(ok ? "已换源" : "换源失败");
                        if (ok) loadSheetTracks();
                    });
                });
            });
        });
    }

    /** 换源／刷新用的同名判定（与 MusicRepository.matchSong 同口径：歌名双向包含＋歌手双向包含） */
    private static boolean sameSongName(MusicTrack target, MusicTrack c) {
        String title = normalizeName(target.title);
        if (title.isEmpty()) return false;
        String ct = normalizeName(c.title);
        if (ct.isEmpty()) return false;
        if (!ct.contains(title) && !title.contains(ct)) return false;
        String artist = normalizeName(target.artist);
        if (!artist.isEmpty()) {
            String ca = normalizeName(c.artist);
            if (!ca.isEmpty() && !ca.contains(artist) && !artist.contains(ca)) return false;
        }
        return true;
    }

    private static String normalizeName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }

    /** 刷新／重新匹配封面用的原站重搜（先 rawJson _plugin 对站，再同平台站兜底） */
    private MusicTrack searchSameTrack(MusicTrack track) throws Exception {
        String kw = ((track.title == null ? "" : track.title) + " "
                + (track.artist == null ? "" : track.artist)).trim();
        String plugin = null;
        try {
            if (track.rawJson != null && !track.rawJson.isEmpty()) {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(track.rawJson).getAsJsonObject();
                if (o.has("_plugin") && !o.get("_plugin").isJsonNull()) plugin = o.get("_plugin").getAsString();
            }
        } catch (Throwable ignored) {
        }
        for (MusicSite s : MusicRepository.sites()) {
            if (s == null || s.srcUrl == null) continue;
            if (plugin != null && !plugin.equals(s.srcUrl)) continue;
            try {
                MusicTrack hit = MusicRepository.matchSong(track, MusicRepository.searchSite(s, kw));
                if (hit != null) return hit;
            } catch (Throwable ignored) {
            }
        }
        if (plugin != null) {
            for (MusicSite s : MusicRepository.sites()) {
                if (s == null || s.platform == null) continue;
                if (!s.platform.equals(track.platform)) continue;
                try {
                    MusicTrack hit = MusicRepository.matchSong(track, MusicRepository.searchSite(s, kw));
                    if (hit != null) return hit;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 第二波：指定圆角加载（ImgUtil.loadRounded 写死 8dp；榜单卡 14dp／共用行 12dp 在此另起；
     * 占位／失败素图口径与行内 CoverTextDrawable 一致，圆角值取调用方 dp 数） */
    private void loadCoverRounded(String text, String url, android.widget.ImageView view, int radiusDp) {
        view.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
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

    /** 第二波：榜单 accent 色（封面语气色；取色失败回落红 #FA243C；holder 复用以 tag 守卫） */
    private void tintChartAccent(MusicChart chart, android.widget.TextView... views) {
        String key = chart.platform + ":" + chart.id;
        for (android.widget.TextView v : views) {
            if (v == null) continue;
            v.setTag(key);
            v.setTextColor(0xFFFA243C);
        }
        if (chart.cover == null || chart.cover.isEmpty()) return;
        ImgUtil.load(chart.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    runOnUiThread(() -> {
                        for (android.widget.TextView v : views) {
                            if (v != null && key.equals(v.getTag())) v.setTextColor(dom);
                        }
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 第二波：平台胶囊（瓜子尾行：半粗、accent 字、accent 0.14 底；普通卡内边距 7/3dp＋圆角 7dp，详情英雄卡 8/3dp＋圆角 8dp） */
    private void tintChartCapsule(MusicChart chart, android.widget.TextView capsule, int cornerDp) {
        if (capsule == null) return;
        String key = chart.platform + ":" + chart.id;
        capsule.setTag(key);
        capsule.setTextColor(0xFFFA243C);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(cornerDp));
        bg.setColor(0x24FA243C);
        capsule.setBackground(bg);
        if (chart.cover == null || chart.cover.isEmpty()) return;
        ImgUtil.load(chart.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    runOnUiThread(() -> {
                        if (!key.equals(capsule.getTag())) return;
                        capsule.setTextColor(dom);
                        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
                        d.setCornerRadius(dp(cornerDp));
                        d.setColor((dom & 0x00FFFFFF) | 0x24000000);
                        capsule.setBackground(d);
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 第二波：卡内预览行（瓜子 TopTrackPreview：「序号. 歌名 · 歌手」；歌手空时只剩序号＋歌名，不编空歌手） */
    private static String previewText(MusicTrack t, int index) {
        String title = t == null ? "" : t.displayTitle();
        String artist = t == null ? "" : t.displayArtist();
        String s = (index + 1) + ". " + title;
        if (artist != null && !artist.isEmpty()) s += " · " + artist;
        return s;
    }

    /** 第二波：榜单条目 rawJson 里同名字段映射（瓜子 TopListItem 字段名；只有源给了同名字段才填，无则留空） */
    private static void fillChartMetaFromRaw(MusicChart c) {
        if (c == null || c.rawJson == null || c.rawJson.isEmpty()) return;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(c.rawJson).getAsJsonObject();
            if (c.trackCount <= 0 && o.has("trackCount") && !o.get("trackCount").isJsonNull()) {
                int n = o.get("trackCount").getAsInt();
                if (n > 0) c.trackCount = n;
            }
            if ((c.updateFrequency == null || c.updateFrequency.isEmpty())
                    && o.has("updateFrequency") && !o.get("updateFrequency").isJsonNull()) {
                String s = o.get("updateFrequency").getAsString();
                if (s != null && !s.isEmpty()) c.updateFrequency = s;
            }
            if ((c.description == null || c.description.isEmpty())) {
                String d = null;
                if (o.has("description") && !o.get("description").isJsonNull()) d = o.get("description").getAsString();
                else if (o.has("desc") && !o.get("desc").isJsonNull()) d = o.get("desc").getAsString();
                if (d != null && !d.isEmpty()) c.description = d;
            }
        } catch (Throwable ignored) {
        }
    }

    class ChartFocusHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView cover;
        final TextView name;
        final TextView preview0;
        final TextView preview1;
        final TextView preview2;
        final TextView btnExplore;
        final TextView focusLabel;
        final TextView freq;

        ChartFocusHolder(View v) {
            super(v);
            cardRoot = v.findViewById(R.id.chartCardRoot);
            cover = v.findViewById(R.id.cover);
            name = v.findViewById(R.id.name);
            preview0 = v.findViewById(R.id.preview0);
            preview1 = v.findViewById(R.id.preview1);
            preview2 = v.findViewById(R.id.preview2);
            btnExplore = v.findViewById(R.id.btnExplore);
            focusLabel = v.findViewById(R.id.focusLabel);
            freq = v.findViewById(R.id.chartFreq);
        }
    }

    class ChartSectionHolder extends RecyclerView.ViewHolder {
        final TextView count;

        ChartSectionHolder(View v) {
            super(v);
            count = v.findViewById(R.id.count);
        }
    }

    class ChartCardHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView cover;
        final TextView name;
        final TextView preview0;
        final TextView preview1;
        final TextView capsule;
        final TextView meta;
        final TextView metaFallback;

        ChartCardHolder(View v) {
            super(v);
            cardRoot = v.findViewById(R.id.chartCardRoot);
            cover = v.findViewById(R.id.cover);
            name = v.findViewById(R.id.name);
            preview0 = v.findViewById(R.id.preview0);
            preview1 = v.findViewById(R.id.preview1);
            capsule = v.findViewById(R.id.chartCapsule);
            meta = v.findViewById(R.id.chartMeta);
            metaFallback = v.findViewById(R.id.chartMetaFallback);
        }
    }

    class ChartHeroHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView name;
        final TextView capsule;
        final TextView desc;
        final TextView freq;
        final View btnPlayAll;
        final View btnRefresh;

        ChartHeroHolder(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            name = v.findViewById(R.id.name);
            capsule = v.findViewById(R.id.chartHeroCapsule);
            desc = v.findViewById(R.id.chartHeroDesc);
            freq = v.findViewById(R.id.chartHeroFreq);
            btnPlayAll = v.findViewById(R.id.btnChartPlayAll);
            btnRefresh = v.findViewById(R.id.btnChartRefresh);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
                }
            });
            cover.setClipToOutline(true);
        }
    }

    class ChartTrackHolder extends RecyclerView.ViewHolder {
        final TextView rank;
        final ImageView stateMark;
        final ImageView cover;
        final TextView title;
        final TextView artist;
        final TextView duration;

        ChartTrackHolder(View v) {
            super(v);
            rank = v.findViewById(R.id.rank);
            stateMark = v.findViewById(R.id.stateMark);
            cover = v.findViewById(R.id.cover);
            title = v.findViewById(R.id.title);
            artist = v.findViewById(R.id.artist);
            duration = v.findViewById(R.id.duration);
            cover.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
                }
            });
            cover.setClipToOutline(true);
        }
    }

    private void bindChartFocus(ChartFocusHolder h, MusicChart chart) {
        h.name.setText(chart.name);
        // 第二波：焦点榜名 24sp／800／字距 −0.6sp（24sp × −0.025em）；封面圆角 8→14dp（瓜子 TopListFeaturedCard）
        h.name.getPaint().setFakeBoldText(true);
        h.name.setLetterSpacing(-0.025f);
        if (chart.cover == null || chart.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
            } catch (Throwable ignored) {
            }
            h.cover.setImageDrawable(null);
            h.cover.setBackgroundColor(0xFFE5E5EA);
        } else {
            h.cover.setBackgroundColor(0x00000000);
            loadCoverRounded(chart.name, chart.cover, h.cover, 14);
        }
        tintChartCard(h.cardRoot, chart, 20);
        // 第二波：标签行顺序照瓜子（「此刻焦点」accent 色在前＋弹性空位＋「CHART SPOTLIGHT」在后）；
        // 更新频率行 12sp 仅源给了值才显，无源留空不编。
        if (h.focusLabel != null) {
            h.focusLabel.setText("此刻焦点");
            tintChartAccent(chart, h.focusLabel, h.btnExplore);
        }
        if (h.freq != null) {
            if (chart.updateFrequency != null && !chart.updateFrequency.isEmpty()) {
                h.freq.setVisibility(View.VISIBLE);
                h.freq.setText(chart.updateFrequency);
            } else {
                h.freq.setVisibility(View.GONE);
            }
        }
        List<MusicTrack> prev = chartPreviews.get(chart.platform + ":" + chart.id);
        TextView[] views = {h.preview0, h.preview1, h.preview2};
        for (int i = 0; i < views.length; i++) {
            if (prev != null && i < prev.size() && prev.get(i) != null) {
                views[i].setVisibility(View.VISIBLE);
                views[i].setText(previewText(prev.get(i), i));
            } else {
                views[i].setVisibility(View.GONE);
            }
        }
        // 第二波：「探索榜单」由深色胶囊改 accent 色文字 12sp 半粗（瓜子 TopListFeaturedCard 口径）
        if (h.btnExplore != null) {
            h.btnExplore.setText("探索榜单");
            setSemiBold(h.btnExplore);
        }
        h.btnExplore.setOnClickListener(v -> openChart(chart));
        h.itemView.setOnClickListener(v -> openChart(chart));
    }

    private void bindChartSection(ChartSectionHolder h) {
        h.count.setText(cards.size() + " 个榜单");
    }

    private void bindChartCard(ChartCardHolder h, MusicChart chart) {
        h.name.setText(chart.name);
        // 第二波：普通榜名 17sp／800（瓜子 TopListCard；字距未抠到不设）；封面圆角 8→14dp
        h.name.getPaint().setFakeBoldText(true);
        if (chart.cover == null || chart.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
            } catch (Throwable ignored) {
            }
            h.cover.setImageDrawable(null);
            h.cover.setBackgroundColor(0xFFE5E5EA);
        } else {
            h.cover.setBackgroundColor(0x00000000);
            loadCoverRounded(chart.name, chart.cover, h.cover, 14);
        }
        tintChartCard(h.cardRoot, chart, 16);
        List<MusicTrack> prev = chartPreviews.get(chart.platform + ":" + chart.id);
        TextView[] views = {h.preview0, h.preview1};
        for (int i = 0; i < views.length; i++) {
            if (prev != null && i < prev.size() && prev.get(i) != null) {
                views[i].setVisibility(View.VISIBLE);
                views[i].setText(previewText(prev.get(i), i));
            } else {
                views[i].setVisibility(View.GONE);
            }
        }
        // 第二波：元信息行（平台胶囊 11sp 半粗＋accent 字／0.14 底＋内边距 7/3dp＋圆角 7dp；「N 首」＋更新频率仅源给了值才显，
        // 无源时末行沿用瓜子无曲文案「实时更新热门歌曲」，不拿详情曲目数冒充榜总曲数）
        if (h.capsule != null) {
            String platName = LxPlatformSearch.platformName(chart.platform);
            if (platName == null || platName.isEmpty()) platName = chart.platform == null ? "" : chart.platform;
            if (platName.isEmpty()) {
                h.capsule.setVisibility(View.GONE);
            } else {
                h.capsule.setVisibility(View.VISIBLE);
                h.capsule.setText(platName);
                setSemiBold(h.capsule);
                tintChartCapsule(chart, h.capsule, 7);
            }
        }
        if (h.meta != null) {
            // 第二波：尾行只写源给出的数／频率；源没给数时不写「N 首」，改由下方兜底行写瓜子无曲文案
            String m = "";
            if (chart.trackCount > 0) m = chart.trackCount + " 首";
            if (chart.updateFrequency != null && !chart.updateFrequency.isEmpty())
                m += (m.isEmpty() ? "" : " · ") + chart.updateFrequency;
            h.meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
            h.meta.setText(m);
        }
        if (h.metaFallback != null) h.metaFallback.setVisibility(chart.trackCount > 0 ? View.GONE : View.VISIBLE);
        h.itemView.setOnClickListener(v -> openChart(chart));
    }

    private void bindChartHero(ChartHeroHolder h) {
        // 第二波：详情英雄卡（瓜子 BoardDetailHero：榜名 24sp／800／字距 −0.4sp＋平台胶囊 10sp＋描述／更新行＋48dp 高「播放全部」钮；
        // 刷新照瓜子详情顶栏刷新落点——星辰详情页无顶栏（铁律不放自带返回箭头、顶栏已隐），刷新钮放英雄卡操作行右端，落点不同、功能同）
        h.name.setText(bigTitle);
        h.name.getPaint().setFakeBoldText(true);
        h.name.setLetterSpacing(-0.017f);
        String platName = LxPlatformSearch.platformName(chartPlatform);
        if (h.capsule != null) {
            if (platName == null || platName.isEmpty()) {
                h.capsule.setVisibility(View.GONE);
            } else {
                h.capsule.setVisibility(View.VISIBLE);
                h.capsule.setText(platName);
                setSemiBold(h.capsule);
                MusicChart heroChart = new MusicChart(chartPlatform, chartId, bigTitle);
                heroChart.cover = chartCover;
                tintChartCapsule(heroChart, h.capsule, 8);
            }
        }
        // 描述／更新频率来自列表页 intent 透传（源没给则留空隐藏；详情现取的曲目数不冒充榜总曲数，故英雄卡不显「N 首」）
        String heroDesc = getIntent() == null ? null : getIntent().getStringExtra("chartDesc");
        String heroFreq = getIntent() == null ? null : getIntent().getStringExtra("chartFreq");
        if (h.desc != null) {
            if (heroDesc == null || heroDesc.isEmpty()) h.desc.setVisibility(View.GONE);
            else {
                h.desc.setVisibility(View.VISIBLE);
                h.desc.setText(heroDesc);
            }
        }
        if (h.freq != null) {
            if (heroFreq == null || heroFreq.isEmpty()) h.freq.setVisibility(View.GONE);
            else {
                h.freq.setVisibility(View.VISIBLE);
                h.freq.setText(heroFreq);
            }
        }
        if (chartCover == null || chartCover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
            } catch (Throwable ignored) {
            }
            h.cover.setImageDrawable(null);
            h.cover.setBackgroundColor(0xFFE5E5EA);
        } else {
            h.cover.setBackgroundColor(0x00000000);
            ImgUtil.loadRounded16(bigTitle, chartCover, h.cover);
        }
        if (h.btnPlayAll != null) {
            h.btnPlayAll.setOnClickListener(v -> {
                if (tracks.isEmpty()) {
                    Notify.show(R.string.music_no_track);
                    return;
                }
                playTrack(0);
            });
        }
        if (h.btnRefresh != null) {
            h.btnRefresh.setOnClickListener(v -> loadChartDetail());
        }
    }

    private void bindChartTrack(ChartTrackHolder h, int position) {
        MusicTrack t = tracks.get(position);
        MusicTrack cur = MusicController.get().current();
        boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
        // 排名始终可见：在播行取封面语气色（GZ TopListDetailScreenKt），其余默认灰
        h.rank.setVisibility(View.VISIBLE);
        h.rank.setText(String.valueOf(position + 1));
        h.rank.setTextColor(0xFF8E8E93);
        h.title.setText(t.displayTitle());
        h.title.setTextColor(0xFF1C1C1E);
        h.title.getPaint().setFakeBoldText(false);
        h.artist.setText(artistDisplay(t.displayArtist()));
        h.duration.setText(fmt(t.duration));
        String trackKey = t.id == null ? "" : t.id;
        h.rank.setTag(trackKey);
        h.stateMark.setTag(trackKey);
        if (isCurrent) {
            // 第二波：在播标记按瓜子图标版（返工此前文字版）：播放态 14dp／暂停态 12dp 图标，tint 同取封面语气色；
            // 图标字形瓜子侧未解码，星辰侧用均衡条（播）／暂停（停）图标，无障碍文案沿用瓜子「正在播放／已暂停」
            boolean playing = MusicController.get().isPlaying();
            h.stateMark.setVisibility(View.VISIBLE);
            h.stateMark.setImageResource(playing ? R.drawable.ic_music_eq : R.drawable.ic_music_pause);
            h.stateMark.setContentDescription(playing ? "正在播放" : "已暂停");
            android.view.ViewGroup.LayoutParams slp = h.stateMark.getLayoutParams();
            int sz = dp(playing ? 14 : 12);
            slp.width = sz;
            slp.height = sz;
            h.stateMark.setLayoutParams(slp);
            if (t.cover == null || t.cover.isEmpty()) {
                h.rank.setTextColor(0xFFFA243C);
                h.stateMark.setColorFilter(0xFFFA243C);
            } else {
                h.rank.setTextColor(0xFFFA243C);
                h.stateMark.setColorFilter(0xFFFA243C);
                ImgUtil.load(t.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull android.graphics.Bitmap bmp,
                                                com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                        if (!trackKey.equals(h.rank.getTag())) return;
                        Task.execute(() -> {
                            int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                            runOnUiThread(() -> {
                                if (!trackKey.equals(h.rank.getTag())) return;
                                h.rank.setTextColor(dom);
                                h.stateMark.setColorFilter(dom);
                            });
                        });
                    }

                    @Override
                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                    }
                });
            }
        } else {
            h.stateMark.setVisibility(View.GONE);
        }
        if (t.cover == null || t.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
            } catch (Throwable ignored) {
            }
            h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                    t.displayTitle(), false, 10 * h.cover.getResources().getDisplayMetrics().density));
        } else {
            ImgUtil.loadRounded10(t.displayTitle(), t.cover, h.cover);
        }
        h.itemView.setOnClickListener(v -> playTrack(position));
        h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
    }

        /** 第 9 项：猜你喜欢四类行 ViewHolder（标题行是纯静态文案，无需字段） */
        class GuessTitleHolder extends RecyclerView.ViewHolder {
            GuessTitleHolder(View v) {
                super(v);
            }
        }

        class GuessHeroHolder extends RecyclerView.ViewHolder {
            final View root;
            final ImageView deckBack2;
            final ImageView deckBack1;
            final ImageView deckFront;
            final TextView countNum;
            final TextView clue;
            final TextView btnPlayAll;
            final ImageView btnShuffle;
            final ImageView btnSave;

            GuessHeroHolder(View v) {
                super(v);
                root = v.findViewById(R.id.guessHeroRoot);
                deckBack2 = v.findViewById(R.id.deckBack2);
                deckBack1 = v.findViewById(R.id.deckBack1);
                deckFront = v.findViewById(R.id.deckFront);
                countNum = v.findViewById(R.id.tvGuessCountNum);
                clue = v.findViewById(R.id.tvGuessClue);
                btnPlayAll = v.findViewById(R.id.btnGuessPlayAll);
                btnShuffle = v.findViewById(R.id.btnGuessShuffle);
                btnSave = v.findViewById(R.id.btnGuessSave);
            }
        }

        class GuessSectionHolder extends RecyclerView.ViewHolder {
            final TextView count;
            final View btnRefresh;
            final TextView tvRefresh;

            GuessSectionHolder(View v) {
                super(v);
                count = v.findViewById(R.id.tvGuessSectionCount);
                btnRefresh = v.findViewById(R.id.btnGuessRefresh);
                tvRefresh = v.findViewById(R.id.tvGuessRefresh);
            }
        }

        class GuessTrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final ImageView more;

            GuessTrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                more = v.findViewById(R.id.more);
            }
        }

        private void bindGuessHero(GuessHeroHolder h) {
            // 叠卡：前一张取第 1 首封面，后两张取第 2、3 首；不够 3 首的层直接隐藏，不编假封面
            loadDeckCover(h.deckFront, tracks.size() > 0 ? tracks.get(0) : null);
            loadDeckCover(h.deckBack1, tracks.size() > 1 ? tracks.get(1) : null);
            loadDeckCover(h.deckBack2, tracks.size() > 2 ? tracks.get(2) : null);
            h.countNum.setText(String.valueOf(tracks.size()));
            h.clue.setText("来自收藏与最近播放的 " + guessClueCount + " 条线索\n越常听，越懂你");
            // 英雄卡跟随首张封面取色淡染（同榜单玻璃卡口径，失败回落白卡）
            tintGuessHero(h.root);
            h.btnPlayAll.setOnClickListener(v -> {
                if (tracks.isEmpty()) return;
                MusicController.get().play(new ArrayList<>(tracks), 0, bigTitle, "list", listRouteParams());
                MusicPlayerActivity.start(MusicListActivity.this);
            });
            h.btnShuffle.setBackground(circle(0xFFF2F2F7));
            h.btnShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
            h.btnShuffle.setOnClickListener(v -> {
                if (tracks.isEmpty()) return;
                List<MusicTrack> shuffled = new ArrayList<>(tracks);
                Collections.shuffle(shuffled);
                MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
                MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
                MusicPlayerActivity.start(MusicListActivity.this);
            });
            h.btnSave.setBackground(circle(0xFFF2F2F7));
            h.btnSave.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
            h.btnSave.setOnClickListener(v -> {
                if (tracks.isEmpty()) return;
                inputPlaylistName(name -> MusicLibrary.importPlaylist(name, new ArrayList<>(tracks), res -> {
                    if (res.inserted <= 0) {
                        Notify.show(R.string.music_import_err_empty);
                        return;
                    }
                    Notify.show(getString(R.string.music_imported, res.playlist.getName(), res.inserted));
                }));
            });
        }

        private void loadDeckCover(ImageView iv, MusicTrack t) {
            if (t == null || t.cover == null || t.cover.isEmpty()) {
                iv.setVisibility(View.GONE);
                return;
            }
            iv.setVisibility(View.VISIBLE);
            ImgUtil.loadRounded16(t.displayTitle(), t.cover, iv);
        }

        private void bindGuessSection(GuessSectionHolder h) {
            h.count.setText(tracks.size() + " 首");
            // 第三波 R5：文案改“换个顺序”——重算做不到：loadGuess 的池是收藏／最近／已下载
            // 三路轮流取的固定池，重跑得同一池；不叫“换一批”假装换了推荐，只做本地重排。
            if (h.tvRefresh != null) h.tvRefresh.setText("换个顺序");
            View.OnClickListener refresh = v -> {
                // 换个顺序＝本地轮换池重排，不是 GZ 的推荐算法重算；英雄卡叠卡随新前 3 首一起换
                Collections.shuffle(tracks);
                notifyDataSetChanged();
            };
            h.btnRefresh.setOnClickListener(refresh);
            h.tvRefresh.setOnClickListener(refresh);
        }

        private void bindGuessTrack(GuessTrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1C1C1E);
            h.title.getPaint().setFakeBoldText(isCurrent);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            StringBuilder second = new StringBuilder();
            second.append(artistDisplay(t.displayArtist()));
            if (t.album != null && !t.album.isEmpty()) second.append(" · ").append(t.album);
            h.artist.setText(second.toString());
            if (t.cover == null || t.cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(h.cover).clear(h.cover);
                } catch (Throwable ignored) {
                }
                h.cover.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t.displayTitle(), false, 8 * h.cover.getResources().getDisplayMetrics().density));
            } else {
                ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            }
            h.itemView.setOnClickListener(v -> playTrack(position));
            h.more.setOnClickListener(v -> onMore(t, position));
        }

        public int getItemCount() {
            if (mode.equals("charts")) return cards.isEmpty() ? 0 : 2 + (cards.size() - 1);
            if (mode.equals("chartDetail")) return 1 + tracks.size();
            if (mode.equals("guess")) return tracks.isEmpty() ? 0 : 3 + tracks.size();
            if (isCardMode()) return cards.size();
            if (mode.equals("download")) return dlTasks.size() + tracks.size() + (tracks.isEmpty() ? 0 : 1);
            if (mode.equals("sheet")) return 3 + tracks.size();
            if (mode.equals("device") && deviceView != DEVICE_VIEW_SONGS) {
                return cards.size() + headerCount();
            }
            if (mode.equals("device")) {
                return tracks.size() + deviceSectionPos.size() + headerCount();
            }
            return tracks.size() + headerCount();
        }

        class DlTaskHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView status;
            final android.widget.ProgressBar progress;
            final android.widget.ImageView btnPause;
            final android.widget.ImageView btnCancel;

            DlTaskHolder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
                progress = v.findViewById(R.id.progress);
                btnPause = v.findViewById(R.id.btnPause);
                btnCancel = v.findViewById(R.id.btnCancel);
            }
        }

        /** 第二波：下载已完成区头 holder（程序化 TextView） */
        class DlSectionHolder extends RecyclerView.ViewHolder {
            final TextView title;

            DlSectionHolder(View v) {
                super(v);
                title = (TextView) v;
            }
        }

        /** 第二波：插件分组头 holder（程序化 TextView） */
        class GroupHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView count;

            GroupHolder(View itemView, TextView title, TextView count) {
                super(itemView);
                this.title = title;
                this.count = count;
            }
        }

        class TrackHolder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView badge;
            final TextView artist;
            final TextView duration;
            final ImageView more;

            TrackHolder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                badge = v.findViewById(R.id.badge);
                artist = v.findViewById(R.id.artist);
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

        class PlaylistHolder extends RecyclerView.ViewHolder {
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
            final ImageView rowPlay;
            final ImageView rowMore;

            PlaylistHolder(View v) {
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
                rowPlay = v.findViewById(R.id.rowPlay);
                rowMore = v.findViewById(R.id.rowMore);
            }
        }

        class PluginHolder extends RecyclerView.ViewHolder {
            final android.widget.ImageView pluginIcon;
            final View statusDot;
            final TextView name;
            final TextView formatTag;
            final TextView version;
            final TextView testStatus;
            final TextView pluginError;
            final TextView pluginTest;
            final com.google.android.material.switchmaterial.SwitchMaterial pluginSwitch;
            final ImageView pluginMore;

            PluginHolder(View v) {
                super(v);
                pluginIcon = v.findViewById(R.id.pluginIcon);
                statusDot = v.findViewById(R.id.statusDot);
                name = v.findViewById(R.id.name);
                formatTag = v.findViewById(R.id.formatTag);
                version = v.findViewById(R.id.version);
                testStatus = v.findViewById(R.id.testStatus);
                pluginError = v.findViewById(R.id.pluginError);
                pluginTest = v.findViewById(R.id.pluginTest);
                pluginSwitch = v.findViewById(R.id.pluginSwitch);
                pluginMore = v.findViewById(R.id.pluginMore);
                // U4：插件行开关 M3 Switch 缩放 0.75（与自动屏蔽行 0.72 区分，不混）
                pluginSwitch.setScaleX(0.75f);
                pluginSwitch.setScaleY(0.75f);
                // 插件开关（选中红、未选灰）：音乐点缀统一 GZ 红，代旧金
                ColorStateList thumb = new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{0xFFFA243C, 0xFF5E6B7E});
                ColorStateList track = new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{0x66FA243C, 0x445E6B7E});
                pluginSwitch.setThumbTintList(thumb);
                pluginSwitch.setTrackTintList(track);
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

    /** 单参回调 */
    private interface NameInput {
        void onResult(String name);
    }
}
