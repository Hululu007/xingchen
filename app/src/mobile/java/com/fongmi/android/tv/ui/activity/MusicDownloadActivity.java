package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.ui.music.MusicGlassWire;
import com.fongmi.android.tv.ui.music.MusicPlayerActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 下载音乐独立页（拆页 p2）：进行中任务行＋已完成曲目（页内搜索过滤）＋概览统计卡
 * ＋批量管理条（全选／删除所选／全部暂停／继续／取消／清除失败／播放全部）＋通用工具条
 * （随机播／顺序播／排序）；行点击走 MusicController 播放，队列 route=list、
 * 参数 mode=download，队列面板来源跳转落回本页。
 * 顶栏只留标题，无返回箭头，走系统返回。
 */
public class MusicDownloadActivity extends BaseActivity {

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
    private TextView tvBigTitle;
    private TextView tvBigCount;
    private View downloadStatsCard;
    private TextView tvDlBigNumber;
    private TextView tvDlSub;
    private TextView tvDlFailed;
    private View downloadBatchBar;
    private TextView btnDlSelect;
    private TextView btnDlPauseAll;
    private TextView btnDlResumeAll;
    private TextView btnDlCancelAll;
    private TextView btnDlClearFailed;
    private TextView btnDlPlayAll;
    private TextView tvDlLiveHeader;
    private EditText etDlSearch;
    private View btnPlayAll;
    private View btnPlaySeq;
    private RecyclerView recycler;
    private boolean listIntroDone;

    /** 列表首屏：数据第一次到达时整表淡入 200ms（统一口径），后续刷新不再重播。 */
    private void fadeListInOnce() {
        if (listIntroDone || recycler == null) return;
        listIntroDone = true;
        recycler.setAlpha(0f);
        XcFadeTransition.fadeIn(recycler);
    }
    private TextView empty;

    /** 显示列表（已完成曲目按页内搜索过滤后） */
    private final List<MusicTrack> tracks = new ArrayList<>();
    private DownloadAdapter adapter;

    // 下载页态：进行中任务行＋已完成多选（全选／删除所选）＋下载监听
    private final List<MusicDownloadStore.LiveTask> dlTasks = new ArrayList<>();
    /** 下载已完成全量（不受搜索过滤影响；显示列表 tracks 是过滤后的） */
    private final List<MusicTrack> dlAllTracks = new ArrayList<>();
    private String dlSearchKw = "";
    private boolean dlSearchWired;
    private final java.util.Set<String> dlSelected = new java.util.HashSet<>();
    private boolean dlListenerOn;
    private final com.fongmi.android.tv.utils.download.DownloadManager.Listener dlListener = task -> {
        runOnUiThread(() -> {
            if (task.getStatus() == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_COMPLETED) {
                loadDownloads();
            } else {
                refreshDlTasks();
            }
        });
    };

    private String bigTitle = "";
    /** onResume 首过标记：首次由 initView 的 loadDownloads 负责，之后回页才重拉。 */
    private boolean resumedOnce;
    private android.os.Parcelable pendingListState;
    private String lastCurrentId;

    /** 通用排序第二段升降序弹层的 post 用字段 Handler＋字段 Runnable，onDestroy 取消，
     * 不许页死后还弹。 */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

    public static void startDownload(Activity activity) {
        activity.startActivity(new Intent(activity, MusicDownloadActivity.class));
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
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_download, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        // 与旧列表页同口径：锁 adjustPan，键盘不缩放窗口、不带乱空态位置。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        if (savedInstanceState != null) {
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        tvBigTitle = findViewById(R.id.tvBigTitle);
        tvBigCount = findViewById(R.id.tvBigCount);
        downloadStatsCard = findViewById(R.id.downloadStatsCard);
        tvDlBigNumber = findViewById(R.id.tvDlBigNumber);
        tvDlSub = findViewById(R.id.tvDlSub);
        tvDlFailed = findViewById(R.id.tvDlFailed);
        downloadBatchBar = findViewById(R.id.downloadBatchBar);
        btnDlSelect = findViewById(R.id.btnDlSelect);
        btnDlPauseAll = findViewById(R.id.btnDlPauseAll);
        btnDlResumeAll = findViewById(R.id.btnDlResumeAll);
        btnDlCancelAll = findViewById(R.id.btnDlCancelAll);
        btnDlClearFailed = findViewById(R.id.btnDlClearFailed);
        btnDlPlayAll = findViewById(R.id.btnDlPlayAll);
        tvDlLiveHeader = findViewById(R.id.tvDlLiveHeader);
        etDlSearch = findViewById(R.id.etDlSearch);
        btnPlayAll = findViewById(R.id.btnPlayAll);
        btnPlaySeq = findViewById(R.id.btnPlaySeq);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);

        bigTitle = getString(R.string.music_downloaded);
        // 下载批量管理条（随机播放走通用工具条的 shuffle，不在这里重复造入口）
        btnDlSelect.setOnClickListener(v -> toggleDlSelect());
        btnDlPauseAll.setOnClickListener(v -> {
            MusicDownloadStore.pauseAllLive();
            refreshDlTasks();
        });
        btnDlResumeAll.setOnClickListener(v -> {
            MusicDownloadStore.resumeAllLive();
            refreshDlTasks();
        });
        btnDlCancelAll.setOnClickListener(v -> {
            // GZ 批量取消确认文案（单任务版在任务行取消钮）
            if (dlTasks.isEmpty()) return;
            new MusicGlassDialogBuilder(this)
                    .setTitle("取消下载")
                    .setMessage("确定取消正在进行的 " + dlTasks.size() + " 个下载任务吗？已下载的临时内容将被删除，无法继续恢复。")
                    .setPositiveButton("取消下载", (d, which) -> {
                        MusicDownloadStore.cancelAllLive();
                        refreshDlTasks();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        btnDlClearFailed.setOnClickListener(v -> {
            MusicDownloadStore.clearFailedLive();
            refreshDlTasks();
        });
        btnDlPlayAll.setOnClickListener(v -> {
            if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
            else playTrack(0);
        });
        btnPlayAll.setOnClickListener(v -> shufflePlay());
        btnPlaySeq.setOnClickListener(v -> playAllSequential());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new DownloadAdapter());
        MusicController.get().addListener(controllerListener);
        loadDownloads();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
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
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        loadDownloads();
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
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void loadDownloads() {
        showLoading();
        registerDlListener();
        if (downloadStatsCard != null) downloadStatsCard.setVisibility(View.VISIBLE);
        if (downloadBatchBar != null) downloadBatchBar.setVisibility(View.VISIBLE);
        if (tvDlLiveHeader != null) tvDlLiveHeader.setVisibility(View.VISIBLE);
        if (etDlSearch != null) {
            etDlSearch.setVisibility(View.VISIBLE);
            if (!dlSearchWired) {
                dlSearchWired = true;
                etDlSearch.addTextChangedListener(new android.text.TextWatcher() {
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
        if (tvDlBigNumber != null) tvDlBigNumber.setText(String.valueOf(dlAllTracks.size()));
        if (tvDlSub != null) {
            tvDlSub.setText("占用 " + com.fongmi.android.tv.music.MusicCache.formatSize(size));
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
        if (tvDlFailed != null) {
            if (failed > 0) {
                tvDlFailed.setVisibility(View.VISIBLE);
                tvDlFailed.setText(failed + " 首下载失败");
            } else tvDlFailed.setVisibility(View.GONE);
        }
        if (tvDlLiveHeader != null) {
            // GZ 进行中区三态分开；waiting>0 时不许拼「0 首正在下载 · N 首等待继续」
            if (dlTasks.isEmpty()) tvDlLiveHeader.setText("暂无进行中的下载");
            else if (downloading == 0) tvDlLiveHeader.setText(waiting + " 首等待继续");
            else if (waiting > 0) tvDlLiveHeader.setText(downloading + " 首正在下载 · " + waiting + " 首等待继续");
            else tvDlLiveHeader.setText(downloading + " 首正在下载");
        }
        refreshBatchBarState();
    }

    /** 批量工具条真态：任务钮按任务实况禁用置灰（旧口径整条无条件常显，
     * 没任务时「全部暂停／继续／取消／清除失败」点了只空转刷新、无禁用态无提示）；
     * 无进行中任务且无已完成时整条隐藏。本方法是唯一刷新点（refreshDlStats 各路必经）。 */
    private void refreshBatchBarState() {
        boolean hasActive = false;
        boolean hasPaused = false;
        boolean hasFailed = false;
        for (MusicDownloadStore.LiveTask t : dlTasks) {
            if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_DOWNLOADING
                    || t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_WAITING) hasActive = true;
            else if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_PAUSED) hasPaused = true;
            else if (t.status == com.fongmi.android.tv.utils.download.DownloadTask.STATUS_ERROR) hasFailed = true;
        }
        setBatchBtn(btnDlPauseAll, hasActive);
        setBatchBtn(btnDlResumeAll, hasPaused);
        setBatchBtn(btnDlCancelAll, !dlTasks.isEmpty());
        setBatchBtn(btnDlClearFailed, hasFailed);
        if (downloadBatchBar != null) {
            downloadBatchBar.setVisibility(dlTasks.isEmpty() && dlAllTracks.isEmpty()
                    ? View.GONE : View.VISIBLE);
        }
    }

    private void setBatchBtn(TextView btn, boolean enabled) {
        if (btn == null) return;
        btn.setEnabled(enabled);
        btn.setAlpha(enabled ? 1f : 0.35f);
    }

    private void showTracks(List<MusicTrack> list) {
        sortTracks(list);
        tracks.clear();
        tracks.addAll(list);
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            fadeListInOnce();
            tvBigTitle.setText(bigTitle);
            tvBigCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
            // 有进行中任务时不显空态（已完成为空不等于没内容）
            boolean showEmpty = tracks.isEmpty() && dlTasks.isEmpty();
            if (showEmpty) XcFadeTransition.fadeIn(empty);
            else XcFadeTransition.fadeOutThenGone(empty);
            // 下载空态用 GZ 文案，不许套通用「还没有在播的歌」；搜已下载搜不到时走通用搜不到文案
            if (tracks.isEmpty()) {
                empty.setText(dlSearchKw.isEmpty()
                        ? "还没有下载的歌曲\n搜索并下载你喜欢的音乐"
                        : getString(R.string.music_no_result));
            }
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
        if (MusicStore.isListSortDesc()) Collections.reverse(list);
    }

    // ================= 多选／删除 =================

    /** 全选钮文案：无选中＝全选，有选中＝删除所选 (N)（与歌单页多选的工具条互换口径一致） */
    private void refreshDlSelectText() {
        if (btnDlSelect == null) return;
        btnDlSelect.setText(dlSelected.isEmpty() ? "全选" : "删除所选 (" + dlSelected.size() + ")");
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
        new MusicGlassDialogBuilder(this)
                .setTitle("删除下载")
                .setMessage("删除本地文件：释放存储空间，音频、封面、歌词一并删除\n仅移出列表：保留本地文件，仍可被本地音乐库扫描播放")
                .setItems(new String[]{"删除本地文件", "仅移出列表"}, (d, which) -> {
                    List<MusicTrack> copy = new ArrayList<>(sel);
                    // 真结果计数：每条删没删由 Store 按文件／记录实况回，收齐后有失败如实报数
                    MusicDownloadStore.DataCallback<Boolean> done = new MusicDownloadStore.DataCallback<Boolean>() {
                        int left = copy.size();
                        int failed = 0;

                        @Override
                        public void onData(Boolean ok) {
                            if (!Boolean.TRUE.equals(ok)) failed++;
                            if (--left <= 0) {
                                dlSelected.clear();
                                refreshDlSelectText();
                                if (failed > 0) {
                                    Notify.show(failed + " 首没删掉（文件删不动或记录已不存在），其余已处理");
                                }
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

    /** 单首删除二选一（GZ 弹层标题「删除下载」＋两项文案；本页 ⋮ 菜单走这里） */
    private void deleteDownloadTwoChoice(MusicTrack track) {
        new MusicGlassDialogBuilder(this)
                .setTitle("删除下载")
                .setMessage("删除本地文件：释放存储空间，音频、封面、歌词一并删除\n仅移出列表：保留本地文件，仍可被本地音乐库扫描播放")
                .setItems(new String[]{"删除本地文件", "仅移出列表"}, (d, which) -> {
                    MusicDownloadStore.DataCallback<Boolean> done = ok -> {
                        Notify.show(Boolean.TRUE.equals(ok) ? R.string.music_deleted : "删除失败，文件可能被占用");
                        loadDownloads();
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
                if (isFinishing() || isDestroyed()) return;
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
                new MusicGlassDialogBuilder(this)
                        .setTitle(track.displayTitle())
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    // ================= 排序 =================

    
    
    // ================= 播放 =================

    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "download");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    private void playTrack(int position) {
        // 入队时写队列级来源＝本页标题、route＝list（参数可重建本页，落点＝本页）
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    /** 通用“播放全部”——顺序入队从头播（与“随机播放”分开）。 */
    private void playAllSequential() {
        if (!tracks.isEmpty()) {
            playTrack(0);
            return;
        }
        Notify.show(R.string.music_no_track);
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
    }

    /** 切歌只刷旧在播行＋新在播行，不全量重绑（全量重绑整列表闪一下、封面重载）。 */
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

    /** tracks 下标 → adapter 位置：下载＝任务数＋已完成区头 1＋下标。 */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        return dlTasks.size() + 1 + trackIdx;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    // ================= 行菜单 =================

    /** 下载（每次询问档时先选档，否则直接下载；本页 ⋮ 菜单里未落本地的行走这里） */
    private void downloadTrack(MusicTrack track) {
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            int[] values = {MusicStore.QUALITY_LOW, MusicStore.QUALITY_STANDARD,
                    MusicStore.QUALITY_HIGH, MusicStore.QUALITY_SUPER};
            int fixed = MusicStore.getDownloadQualityFixed();
            int checked = 1;
            for (int i = 0; i < values.length; i++) if (values[i] == fixed) checked = i;
            final int checkedFinal = checked;
            // 选档前实测文件大小（取链＋HEAD；实测不到的档不显示大小、不估算）。
            // 探查期间先给现成 Notify 提示，探查回来了页已死则不弹选档窗。
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
            int detailIdx = -1;
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"＋详情弹层入口
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
                detailIdx = opts.size();
                opts.add("详情");
            } else if (!device) {
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int delIdx = deleteLocalIdx;
            int detIdx = detailIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) {
                    boolean toFav = opts.get(3).equals(getString(R.string.music_favorite));
                    MusicLibrary.setFavorite(track, toFav, () ->
                            Notify.show(toFav ? R.string.music_favorited : R.string.music_unfavorited));
                }
                else if (idx == dIdx) downloadTrack(track);
                else if (idx == detIdx) showDownloadDetail(track);
                else if (idx == delIdx) deleteDownloadTwoChoice(track);
            });
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

    // ================= 工具 =================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    private void pressScale(View v, float scale) {
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

    private void loadCoverRounded(String text, String url, ImageView view, int radiusDp) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            com.bumptech.glide.Glide.with(view).clear(view);
            com.bumptech.glide.Glide.with(view).load(ImgUtil.getUrl(url))
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

    // ================= 列表 =================

    private class DownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_DL_TASK = 5;
        /** 下载已完成区头（进行中任务之后、已完成列表之前） */
        private static final int TYPE_DL_SECTION = 21;

        @Override
        public int getItemViewType(int position) {
            if (position < dlTasks.size()) return TYPE_DL_TASK;
            // 已完成区头（仅已完成非空时插在任务之后；空时不占位，空态文案管）
            if (!tracks.isEmpty() && position == dlTasks.size()) return TYPE_DL_SECTION;
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
                // 下载已完成区头（纯程序化 TextView，不另起布局文件；字号沿用歌单区头 17sp）
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(17);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                tv.setTextColor(0xFFF1F4F5);
                tv.setPadding(dp(4), dp(12), dp(4), dp(8));
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new DlSectionHolder(tv);
            }
            return new TrackHolder(inf.inflate(R.layout.item_music_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof DlTaskHolder) bindDlTask((DlTaskHolder) h, position);
            else if (h instanceof DlSectionHolder) {
                // 已完成区头（搜时「搜索结果」，平时「全部歌曲」；瓜子 DownloadsScreen 区头口径）
                ((DlSectionHolder) h).title.setText(dlSearchKw.isEmpty()
                        ? getString(R.string.wave2_section_all_songs)
                        : getString(R.string.wave2_section_search_result));
            } else bindTrack((TrackHolder) h, trackIndex(position));
        }

        @Override
        public int getItemCount() {
            return dlTasks.size() + tracks.size() + (tracks.isEmpty() ? 0 : 1);
        }

        /** 歌曲行在列表里的下标：前面有进行中任务行与已完成区头，要减掉 */
        private int trackIndex(int position) {
            // 已完成区头占 1 位（仅已完成非空时存在；区头位本身不走 trackIndex）
            int idx = position - dlTasks.size();
            if (!tracks.isEmpty() && position > dlTasks.size()) idx--;
            return idx;
        }

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 共用行口径：歌名 15sp 粗 #1A1A1A，在播行红名
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
            // 下载多选态：选中只留字色变化（扫描 M24）——旧实现在 frameRow 之后又把
            // 玻璃整件换成粉色实底 card_music_current，字已翻白、白字压浅粉看不清，
            // 也违反「选中行粉红底撤掉」明令；选中行歌名改红字（在 frameRow 翻字之后写，防被翻回）
            boolean dlSel = t.id != null && dlSelected.contains(t.id);
            if (dlSel) h.title.setTextColor(0xFFFA243C);
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
            // 已下载行第二行＝歌手 · 已下载 · 大小（实测文件大小）
            String size = "";
            try {
                if (t.localPath != null && !t.localPath.isEmpty()) {
                    size = com.fongmi.android.tv.music.MusicCache.formatSize(
                            new java.io.File(t.localPath).length());
                }
            } catch (Throwable ignored) {
            }
            h.artist.setText(artistDisplay(t.displayArtist()) + " · 已下载" + (size.isEmpty() ? "" : " · " + size));
            // 本地行路径行：本页不显（旧页此分支恒空），一律 GONE 防回收行串显
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
                // 共用行封面圆角 12dp 口径（本页 10dp helper，与旧页下载行同值）
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            if (!dlSelected.isEmpty()) {
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
            } else {
                h.itemView.setOnClickListener(v -> playTrack(position));
                // 回收行复位：多选圆圈换回 ⋮ 图标
                h.more.setImageResource(R.drawable.ic_music_more);
                int p12 = dp(12);
                h.more.setPadding(p12, p12, p12, p12);
                h.more.setOnClickListener(v -> onMore(t, position));
            }
        }

        /** 进行中任务行绑定（名＋GZ 状态文案＋进度＋34dp 圆钮：暂停／继续／取消，图标 20dp；瓜子 DownloadsScreenKt 圆钮口径） */
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
            // 两圆钮按压 0.9；setOnTouchListener 与点击监听共存（返回 false 不吞点击）
            pressScale(h.btnPause, 0.9f);
            pressScale(h.btnCancel, 0.9f);
            h.btnCancel.setImageResource(R.drawable.ic_music_close);
            h.btnCancel.setContentDescription("取消下载");
            h.btnCancel.setOnClickListener(v -> {
                // GZ 单任务取消确认文案
                new MusicGlassDialogBuilder(MusicDownloadActivity.this)
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

        class DlTaskHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView status;
            final android.widget.ProgressBar progress;
            final android.widget.ImageView btnPause;
            final android.widget.ImageView btnCancel;

            DlTaskHolder(View v) {
                super(v);
                MusicGlassWire.frameRow(itemView); // 行一框：任务行原裸根，补液态玻璃框
                MusicGlassWire.glassifyTree(itemView);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
                progress = v.findViewById(R.id.progress);
                btnPause = v.findViewById(R.id.btnPause);
                btnCancel = v.findViewById(R.id.btnCancel);
            }
        }

        /** 下载已完成区头 holder（程序化 TextView） */
        class DlSectionHolder extends RecyclerView.ViewHolder {
            final TextView title;

            DlSectionHolder(View v) {
                super(v);
                title = (TextView) v;
            }
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
    }
}
