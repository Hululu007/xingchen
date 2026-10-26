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
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放历史独立页（拆页 p3）：英雄卡（拼贴封面＋大标题＋N 首＋播放全部／随机播放
 * ＋多选＋清空＋页内搜索框＋排序＋续播卡＋多选删除）＋历史行（已缓存标签）；
 * 数据源＝MusicStore 播放历史（loadRecent），行点击走 MusicController 播放，
 * 队列 route=list、参数 mode=history，队列面板来源跳转落回本页。
 * 顶栏只留标题，无返回箭头，走系统返回。
 */
public class MusicHistoryActivity extends BaseActivity {

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
    private View favHistHero;
    private TextView tvFavHistLabel;
    private TextView tvFavHistTitle;
    private TextView tvFavHistCount;
    private TextView btnFavHistPlayAll;
    private TextView btnFavHistShuffle;
    private TextView btnFavHistMulti;
    private TextView btnFavHistClear;
    private EditText etFavHistSearch;
    private ImageView btnFavHistClearSearch;
    private ImageView ivMosaic0;
    private ImageView ivMosaic1;
    private ImageView ivMosaic2;
    private ImageView ivMosaic3;
    private View favHistResume;
    private TextView tvFavHistResumeTitle;
    private TextView tvFavHistResumeState;
    private TextView tvFavHistSection;
    private View btnPlayAll;
    private TextView tvPlayAll;
    private View btnPlaySeq;
    private ImageView btnSort;
    private RecyclerView recycler;
    private TextView empty;

    /** 显示列表（历史全量按页内搜索过滤＋排序后） */
    private final List<MusicTrack> tracks = new ArrayList<>();
    private HistoryAdapter adapter;

    // 历史页态：英雄卡全量＋页内搜索＋排序（0 最近播放 1 最早播放 2 歌曲名称）＋多选＋续播
    private final List<MusicTrack> histAll = new ArrayList<>();
    private String histSearchKw = "";
    private int histSort = 0;
    private boolean histMulti;
    private final java.util.Set<String> histSelected = new java.util.HashSet<>();
    private MusicTrack histResume;
    private boolean histSearchWired;

    private String bigTitle = "";
    /** onResume 首过标记：首次由 initView 的 loadHistory 负责，之后回页才重拉。 */
    private boolean resumedOnce;
    private android.os.Parcelable pendingListState;
    private String lastCurrentId;

    public static void startHistory(Activity activity) {
        activity.startActivity(new Intent(activity, MusicHistoryActivity.class));
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
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_history, null);
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
        tvTitle = findViewById(R.id.tvTitle);
        favHistHero = findViewById(R.id.favHistHero);
        tvFavHistLabel = findViewById(R.id.tvFavHistLabel);
        tvFavHistTitle = findViewById(R.id.tvFavHistTitle);
        tvFavHistCount = findViewById(R.id.tvFavHistCount);
        btnFavHistPlayAll = findViewById(R.id.btnFavHistPlayAll);
        btnFavHistShuffle = findViewById(R.id.btnFavHistShuffle);
        btnFavHistMulti = findViewById(R.id.btnFavHistMulti);
        btnFavHistClear = findViewById(R.id.btnFavHistClear);
        etFavHistSearch = findViewById(R.id.etFavHistSearch);
        btnFavHistClearSearch = findViewById(R.id.btnFavHistClearSearch);
        ivMosaic0 = findViewById(R.id.ivMosaic0);
        ivMosaic1 = findViewById(R.id.ivMosaic1);
        ivMosaic2 = findViewById(R.id.ivMosaic2);
        ivMosaic3 = findViewById(R.id.ivMosaic3);
        favHistResume = findViewById(R.id.favHistResume);
        tvFavHistResumeTitle = findViewById(R.id.tvFavHistResumeTitle);
        tvFavHistResumeState = findViewById(R.id.tvFavHistResumeState);
        tvFavHistSection = findViewById(R.id.tvFavHistSection);
        btnPlayAll = findViewById(R.id.btnPlayAll);
        tvPlayAll = findViewById(R.id.tvPlayAll);
        btnPlaySeq = findViewById(R.id.btnPlaySeq);
        btnSort = findViewById(R.id.btnSort);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);

        bigTitle = getString(R.string.music_history);
        tvTitle.setText(R.string.music_history);
        // 英雄卡操作行
        btnFavHistPlayAll.setOnClickListener(v -> {
            if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
            else playTrack(0);
        });
        btnFavHistShuffle.setOnClickListener(v -> shufflePlay());
        btnFavHistMulti.setOnClickListener(v -> setHistoryMulti(!histMulti));
        btnFavHistClear.setOnClickListener(v -> {
            // 历史页清空（GZ：历史页有清除；收藏页无清空全部按钮）
            new MusicGlassDialogBuilder(this)
                    .setTitle("清空播放历史")
                    .setMessage("确定清空全部播放历史吗？")
                    .setPositiveButton("清空", (d, w) -> {
                        MusicStore.clearRecent();
                        loadHistory();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        favHistResume.setOnClickListener(v -> {
            if (histResume == null) return;
            int idx = -1;
            for (int j = 0; j < histAll.size(); j++) {
                if (histResume.id != null && histResume.id.equals(histAll.get(j).id)) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) return;
            MusicController.get().play(new ArrayList<>(histAll), idx, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
        });
        btnFavHistClearSearch.setOnClickListener(v -> etFavHistSearch.setText(""));
        if (!histSearchWired) {
            histSearchWired = true;
            etFavHistSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    histSearchKw = s == null ? "" : s.toString().trim();
                    btnFavHistClearSearch.setVisibility(
                            histSearchKw.isEmpty() ? View.GONE : View.VISIBLE);
                    refreshHistoryDisplay();
                }
            });
        }
        // 工具栏：多选态外只留排序（播放／随机播在英雄卡操作行）；btnPlayAll 多选态复用作删除所选
        btnPlayAll.setOnClickListener(v -> shufflePlay());
        btnPlaySeq.setOnClickListener(v -> playAllSequential());
        btnSort.setOnClickListener(v -> showHistorySortDialog());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new HistoryAdapter());
        // 历史行间分隔线（GZ：0.5dp 厚、起始偏移 76dp、末端 14dp，与 76dp 拼贴对齐）
        recycler.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void onDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
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
        MusicController.get().addListener(controllerListener);
        loadHistory();
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
        loadHistory();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 数据加载 =================

    private void showLoading() {
        empty.setVisibility(View.VISIBLE);
        empty.setText("正在加载…");
    }

    private void loadHistory() {
        showLoading();
        histAll.clear();
        try {
            histAll.addAll(MusicStore.loadRecent());
        } catch (Throwable ignored) {
        }
        refreshHistoryDisplay();
    }

    /** 历史显示刷新（英雄卡＋搜索过滤＋排序＋续播卡；空态用 GZ 文案） */
    private void refreshHistoryDisplay() {
        favHistHero.setVisibility(View.VISIBLE);
        tvFavHistLabel.setVisibility(View.GONE);
        tvFavHistTitle.setText(bigTitle);
        tvFavHistCount.setText(histAll.size() + " 首");
        btnFavHistClear.setVisibility(View.VISIBLE);
        btnFavHistMulti.setText(histMulti ? "取消多选" : "多选");
        etFavHistSearch.setHint("搜索历史歌曲");
        // 拼贴：前 4 首的封面（没封面的格子留灰底）
        ImageView[] cells = {ivMosaic0, ivMosaic1, ivMosaic2, ivMosaic3};
        for (int i = 0; i < cells.length; i++) {
            ImageView iv = cells[i];
            String cover = i < histAll.size() ? histAll.get(i).cover : null;
            if (cover == null || cover.isEmpty()) {
                try {
                    com.bumptech.glide.Glide.with(iv).clear(iv);
                } catch (Throwable ignored) {
                }
                iv.setImageDrawable(null);
                iv.setBackgroundColor(0xFFE5E5EA);
            } else {
                iv.setBackgroundColor(0x00000000);
                ImgUtil.loadRounded(histAll.get(i).displayTitle(), cover, iv);
            }
        }
        // 续播卡：历史页＝最近播放第一首（无则不显）
        histResume = null;
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            if (!recent.isEmpty()) {
                histResume = recent.get(0);
            }
        } catch (Throwable ignored) {
        }
        if (histResume != null) {
            favHistResume.setVisibility(View.VISIBLE);
            MusicTrack cur = MusicController.get().current();
            boolean isCur = cur != null && cur.id != null && cur.id.equals(histResume.id);
            // 在播/暂停前缀只在续播曲就是当前曲时加；不是当前曲时不称暂停
            String prefix = !isCur ? ""
                    : (MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
            tvFavHistResumeTitle.setText(prefix + histResume.displayTitle());
            tvFavHistResumeState.setText("上次听到这首"
                    + (histResume.displayArtist().isEmpty() ? "" : " · " + histResume.displayArtist()));
        } else {
            favHistResume.setVisibility(View.GONE);
        }
        // 搜索过滤＋排序
        List<MusicTrack> show = new ArrayList<>();
        for (MusicTrack t : histAll) {
            if (!histSearchKw.isEmpty()) {
                String kw = histSearchKw.toLowerCase(java.util.Locale.getDefault());
                String title = t.title == null ? "" : t.title.toLowerCase(java.util.Locale.getDefault());
                String artist = t.artist == null ? "" : t.artist.toLowerCase(java.util.Locale.getDefault());
                if (!title.contains(kw) && !artist.contains(kw)) continue;
            }
            show.add(t);
        }
        if (histSort == 2) {
            Collections.sort(show, java.util.Comparator.comparing(t -> t.title == null ? "" : t.title));
        } else {
            // 历史：0 最近播放＝loadRecent 顺序（已是最近在前），1 最早播放＝倒序
            if (histSort == 1) Collections.reverse(show);
        }
        tracks.clear();
        tracks.addAll(show);
        adapter.notifyDataSetChanged();
        // 列表区头（瓜子 SectionHeader：历史「全部记录」／搜时「搜索结果」；
        // 区头放英雄卡块内静态行，不进 adapter——历史列表无区头项，trackIndex 不动）
        if (tvFavHistSection != null) {
            if (tracks.isEmpty()) {
                tvFavHistSection.setVisibility(View.GONE);
            } else {
                tvFavHistSection.setVisibility(View.VISIBLE);
                tvFavHistSection.setText(histSearchKw.isEmpty()
                        ? getString(R.string.wave2_section_all_records)
                        : getString(R.string.wave2_section_search_result));
            }
        }
        if (tracks.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            // 搜不到时显 GZ 搜不到文案，不许套用历史空态
            if (!histSearchKw.isEmpty()) {
                empty.setText("未找到匹配歌曲\n换个关键词试试歌名、歌手或专辑");
            } else {
                empty.setText("还没有播放记录\n听歌后这里会记录你的播放历史");
            }
        } else {
            empty.setVisibility(View.GONE);
        }
        refreshHistoryMultiText();
    }

    /** 历史排序弹层（GZ：历史 3 项＋当前项文案「当前：…」） */
    private void showHistorySortDialog() {
        String[] items = new String[]{"最近播放", "最早播放", "歌曲名称"};
        new MusicGlassDialogBuilder(this)
                .setTitle("历史排序")
                .setMessage("当前：" + items[histSort])
                .setSingleChoiceItems(items, histSort, (d, which) -> {
                    histSort = which;
                    d.dismiss();
                    refreshHistoryDisplay();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 历史多选态切换（GZ：选中圆圈 24dp＋「已选 N 首」；工具条复用口径：删除所选／取消） */
    private void setHistoryMulti(boolean on) {
        histMulti = on;
        if (!on) histSelected.clear();
        btnFavHistMulti.setText(on ? "取消多选" : "多选");
        if (on) {
            btnPlayAll.setVisibility(View.VISIBLE);
            btnSort.setVisibility(View.GONE);
            tvPlayAll.setText("删除所选 (" + histSelected.size() + ")");
            btnPlayAll.setOnClickListener(v -> deleteHistorySelected());
        } else {
            btnPlayAll.setVisibility(View.GONE);
            btnSort.setVisibility(View.VISIBLE);
            btnPlayAll.setOnClickListener(v -> shufflePlay());
        }
        refreshHistoryMultiText();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshHistoryMultiText() {
        if (histMulti) tvPlayAll.setText("删除所选 (" + histSelected.size() + ")");
    }

    private void deleteHistorySelected() {
        if (histSelected.isEmpty()) {
            setHistoryMulti(false);
            return;
        }
        MusicStore.removeRecentByIds(new java.util.HashSet<>(histSelected));
        setHistoryMulti(false);
        loadHistory();
    }

    // ================= 播放 =================

    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "history");
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

    /** tracks 下标 → adapter 位置：历史列表无区头项，下标即位置。 */
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
            if (local) {
                // 已下载歌曲：不给"下载"（已是本地），给"删除本地文件"
                deleteLocalIdx = opts.size();
                opts.add(getString(R.string.music_download_delete));
            } else if (!device) {
                downloadIdx = opts.size();
                opts.add(getString(R.string.music_download));
            }
            int dIdx = downloadIdx;
            int delIdx = deleteLocalIdx;
            // 历史页行级移除（GZ：历史页有「移除／移除历史」行级操作）
            int removeHistIdx = opts.size();
            opts.add("移除历史");
            int rmHistIdx = removeHistIdx;
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == dIdx) downloadTrack(track);
                else if (idx == rmHistIdx) {
                    MusicStore.removeRecentByIds(
                            java.util.Collections.singleton(track.id));
                    loadHistory();
                }
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

    // ================= 列表 =================

    private class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new TrackHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_music_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            bindTrack((TrackHolder) h, position);
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }

        private void bindTrack(TrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 共用行口径：歌名 15sp 粗 #1A1A1A，在播行红名
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
            if (isTrackCached(t)) {
                // 历史行「已缓存」标签（瓜子 HistoryScreenKt 内边距 7/3dp、圆角 8dp；
                // 口径：已缓存＝音频已落本地；判定＝非设备库曲目且 localPath 文件真实存在）
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
            // 通用行第二行统一拼“歌手 · 专辑”（有专辑才拼）；当前行加在播前缀
            StringBuilder second = new StringBuilder();
            if (isCurrent) second.append(MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
            second.append(artistDisplay(t.displayArtist()));
            if (t.album != null && !t.album.trim().isEmpty()) second.append(" · ").append(t.album.trim());
            h.artist.setText(second.toString());
            // 本地行路径行：本页不显，一律 GONE 防回收行串显
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
                // 共用行封面圆角 12dp 口径（本页 10dp helper，与旧页历史行同值）
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            if (histMulti) {
                // 历史多选态（行点按变勾选；行尾 24dp 选中圆圈，选中红底、未选灰框）
                boolean sel = t.id != null && histSelected.contains(t.id);
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
                        if (histSelected.contains(t.id)) histSelected.remove(t.id);
                        else histSelected.add(t.id);
                    }
                    refreshHistoryMultiText();
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

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        /** 历史行「已缓存」判定（见 bindTrack 注释口径） */
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
