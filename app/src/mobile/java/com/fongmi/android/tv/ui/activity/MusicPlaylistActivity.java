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
 * 歌单详情独立页（拆页 p9）：英雄卡＋操作行＋区头＋歌曲行；
 * 顶栏 ⋮＝刷新／随机播放／页内搜索／排序（歌单 6 档）／分享／多选；
 * 数据走 MusicLibrary.playlistTracks（存量救援读在 MusicLibrary／存储层，本页只消费）；
 * 行播放队列 route=list、参数 mode=sheet＋playlistId＋title，队列面板来源跳转落回本页；
 * 播放偏移 +3（英雄卡／操作行／区头）在本页 adapter 内落实。
 * 旧页大标题行在 sheet 下恒隐藏、本页不带；无返回箭头，走系统返回。
 */
public class MusicPlaylistActivity extends BaseActivity {

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
    private ImageView btnSheetMore;
    private EditText etSheetSearch;
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

    private final List<MusicTrack> tracks = new ArrayList<>();
    private SheetAdapter adapter;
    private long playlistId = -1;
    private String bigTitle = "";
    // 歌单详情页态（GZ：英雄卡＋操作行＋区头＋歌曲行＋排序弹层＋更多表；同步到云端需账号，排除只列不建）
    private static final int REQ_SHEET_COVER = 4314;
    private final List<MusicTrack> sheetAll = new ArrayList<>();
    private String sheetSearchKw = "";
    private int sheetSort = 0; // 0 歌单原顺序 1 加入时间从新到旧 2 加入时间从旧到新 3 按歌曲名 4 按专辑名 5 按歌手名
    private boolean sheetMulti;
    /** 待裁4：选中集存复合身份 trackKey（platform|id）——歌单条目唯一键是
     * (playlistId, trackId, platform)，只按 trackId 选会让同 id 异源两行同显已选、
     * 删除连坐（与编前扫查「歌单移除复合身份」同口径）。 */
    private final java.util.Set<String> sheetSelected = new java.util.HashSet<>();
    private boolean sheetSearchWired;
    private boolean sheetSearchShown;
    private String lastCurrentId;
    private android.os.Parcelable pendingListState;
    /** onResume 首过标记：首次由 initView 的 loadSheetTracks 负责，之后回页才重拉。 */
    private boolean resumedOnce = false;

    /** 通用排序第二段升降序弹层的 post（页销毁取消，见 onDestroy） */
    private final android.os.Handler sortDialogHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sortDirRunnable;

    public static void startSheet(Activity activity, long playlistId, String name) {
        Intent i = new Intent(activity, MusicPlaylistActivity.class);
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
        // 只 inflate 一次：BaseActivity 先取根 setContentView、initView 再取根走 glassifyTree，
        // 两次必须是同一棵树——重复 inflate 会让接线落在游离树上、显示的那棵永不生效。
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_playlist, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(getBinding().getRoot()); // 全页玻璃件同源接线：共用件按资源识别统一换液态玻璃（MusicGlassWire）
        // 与旧列表页同口径：锁 adjustPan，键盘不缩放窗口、不带乱空态位置。
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        Intent i = getIntent();
        playlistId = i.getLongExtra("playlistId", -1);
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";
        if (savedInstanceState != null) {
            sheetSearchKw = savedInstanceState.getString("ml_sheetSearchKw", sheetSearchKw);
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        tvTitle = findViewById(R.id.tvTitle);
        btnSheetMore = findViewById(R.id.btnSheetMore);
        etSheetSearch = findViewById(R.id.etSheetSearch);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        empty.setOnClickListener(null);
        tvTitle.setText(bigTitle);
        btnSheetMore.setVisibility(View.VISIBLE);
        btnSheetMore.setOnClickListener(v -> showSheetTopMenu());
        if (!sheetSearchWired) {
            sheetSearchWired = true;
            etSheetSearch.addTextChangedListener(new android.text.TextWatcher() {
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
        findViewById(R.id.btnPlayAll).setOnClickListener(v -> shufflePlay());
        findViewById(R.id.btnPlaySeq).setOnClickListener(v -> playAllSequential());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new SheetAdapter());
        MusicController.get().addListener(controllerListener);
        loadSheetTracks();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putString("ml_sheetSearchKw", sheetSearchKw);
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
        // 回页重拉本单曲目（旧 onResume 的 sheet 支）
        loadSheetTracks();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        if (sortDialogHandler != null) sortDialogHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SHEET_COVER) {
            // 改封面＝把选的图拷到应用文件目录，按歌单 id 索引（不改 Room schema）
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
                        MusicStore.setSheetCover(playlistId, out.getAbsolutePath());
                        runOnUiThread(() -> adapter.notifyItemChanged(0));
                    } catch (Throwable t) {
                        runOnUiThread(() -> Notify.show("封面设置失败"));
                    }
                });
            }
        }
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void loadSheetTracks() {
        showLoading();
        // 歌单详情页顶栏标题＝歌单名（英雄卡里也有名）
        tvTitle.setText(bigTitle);
        btnSheetMore.setVisibility(View.VISIBLE);
        MusicLibrary.playlistTracks(playlistId, list -> {
            sheetAll.clear();
            if (list != null) sheetAll.addAll(list);
            refreshSheetDisplay();
        });
    }

    /** 歌单详情显示刷新（搜索过滤＋排序；区头 N 首＝当前显示条数） */
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
        fadeListInOnce();
        if (tracks.isEmpty()) XcFadeTransition.fadeIn(empty);
        else XcFadeTransition.fadeOutThenGone(empty);
        if (tracks.isEmpty()) {
            // 歌单专属空／搜不到文案（瓜子 PlaylistDetailScreenKt 口径）
            if (!sheetSearchKw.isEmpty()) {
                empty.setText("没有找到「" + sheetSearchKw + "」\n换一个歌名、歌手或专辑试试");
            } else {
                empty.setText("歌单中暂时没有歌曲\n这里还很安静");
            }
            empty.setOnClickListener(v -> loadSheetTracks());
        } else {
            empty.setOnClickListener(null);
        }
    }

    /** 歌单详情页顶栏 ⋮ 菜单（GZ PlaylistMoreButton：刷新歌单／随机播放／搜索歌曲／排序歌曲／分享歌单；多选是星辰侧入口） */
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
                etSheetSearch.setVisibility(sheetSearchShown ? View.VISIBLE : View.GONE);
                if (!sheetSearchShown) {
                    sheetSearchKw = "";
                    etSheetSearch.setText("");
                    refreshSheetDisplay();
                } else {
                    etSheetSearch.requestFocus();
                }
            } else if (idx == 3) showSheetSortDialog();
            else if (idx == 4) shareSheet();
            else if (idx == 5) setSheetMulti(!sheetMulti);
        });
    }

    /** 歌单排序弹层（GZ：首项固定「歌单原顺序」，其后 5 项按枚举顺序） */
    private void showSheetSortDialog() {
        String[] items = {"歌单原顺序", "加入歌单时间从新到旧", "加入歌单时间从旧到新",
                "按歌曲名排序", "按专辑名排序", "按歌手名排序"};
        MusicOptionsDialog.show(this, "排序方式", java.util.Arrays.asList(items), sheetSort, idx -> {
            sheetSort = idx;
            refreshSheetDisplay();
        });
    }

    /** 分享歌单（GZ 分享歌单项；星辰侧用系统分享发歌单名＋曲目文本，无云端） */
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

    /** 改封面（GZ 封面表：修改封面／恢复默认封面；「仅本机 · 赞助用户可同步到云端」与同步到云端需账号，星辰侧不建） */
    private void showSheetCoverDialog() {
        MusicOptionsDialog.show(this, "封面", java.util.Arrays.asList("修改封面", "恢复默认封面"), -1, idx -> {
            if (idx == 0) {
                Intent i = new Intent(Intent.ACTION_PICK);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "选择封面"), REQ_SHEET_COVER);
            } else {
                MusicStore.setSheetCover(playlistId, null);
                adapter.notifyItemChanged(0);
            }
        });
    }

    /** 歌单多选态切换（GZ 批量态：移除；行尾 24dp 选中圆圈口径与收藏／历史多选一致） */
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
        java.util.Set<String> keys = new java.util.HashSet<>(sheetSelected);
        Task.execute(() -> {
            try {
                // 待裁4：按复合身份逐行删（trackId＋platform 三参 delete），
                // 只按 trackId 的两参 delete 会把同 id 异源行一并删掉
                for (MusicTrack t : new java.util.ArrayList<>(sheetAll)) {
                    if (t.id == null || !keys.contains(MusicQueue.trackKey(t))) continue;
                    com.fongmi.android.tv.db.AppDatabase.get().getMusicPlaylistItemDao()
                            .delete(playlistId, t.id, t.platform);
                }
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                setSheetMulti(false);
                loadSheetTracks();
            });
        });
    }

    /** 外层时长格式化（与行内 fmt 同款，供详情弹层用） */
    private static String fmtMs(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ================= 播放 =================

    /** list 路由参数照旧口径（mode=sheet＋keyword/sitePlatform 空＋playlistId＋title，可重建本页） */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "sheet");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", String.valueOf(playlistId));
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
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

    /** 通用“播放全部”——顺序入队从头播（与“随机播放”分开） */
    private void playAllSequential() {
        if (!tracks.isEmpty()) {
            playTrack(0);
            return;
        }
        Notify.show(R.string.music_no_track);
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

    /** tracks 下标 → adapter 位置：前面有英雄卡／操作行／区头 3 行（旧共享 adapter 口径） */
    private int adapterPositionForTrack(int trackIdx) {
        if (adapter == null || trackIdx < 0 || trackIdx >= tracks.size()) return -1;
        return trackIdx + 3;
    }

    private int indexOfTrackId(String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            MusicTrack t = tracks.get(i);
            if (t != null && id.equals(t.id)) return i;
        }
        return -1;
    }

    // ================= 通用排序（工具条排序键照旧口径） =================

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
        if (MusicStore.isListSortDesc()) Collections.reverse(list);
    }

    
    /** 通用排序落点：按新档重排当前显示列（旧 showTracks 在 sheet 下的等价动作，不碰歌单原序数据） */
    private void applyGenericSort() {
        List<MusicTrack> list = new ArrayList<>(tracks);
        sortTracks(list);
        tracks.clear();
        tracks.addAll(list);
        adapter.notifyDataSetChanged();
        if (tracks.isEmpty()) XcFadeTransition.fadeIn(empty);
        else XcFadeTransition.fadeOutThenGone(empty);
        if (tracks.isEmpty()) empty.setText(R.string.music_no_track);
    }

    
    // ================= 行菜单与行操作 =================

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

    private void removeFromSheet(MusicTrack track) {
        Task.execute(() -> {
            // 按行主键删：同歌单里同 trackId 异来源可并存，按 (playlistId, trackId) 删会连坐别家那行
            // （与 MusicLibrary.replaceTrackInPlaylist 同口径：按 trackId＋platform＋插件戳三段身份定位到行）
            try {
                com.fongmi.android.tv.db.dao.MusicPlaylistItemDao dao =
                        com.fongmi.android.tv.db.AppDatabase.get().getMusicPlaylistItemDao();
                for (com.fongmi.android.tv.bean.MusicPlaylistItem item : dao.findByPlaylist(playlistId)) {
                    if (item != null && track.id != null && track.id.equals(item.getTrackId())
                            && java.util.Objects.equals(track.platform, item.getPlatform())
                            && java.util.Objects.equals(
                                    com.fongmi.android.tv.music.MusicIdentity.pluginOf(track.rawJson),
                                    com.fongmi.android.tv.music.MusicIdentity.pluginOf(item.getRawJson()))) {
                        dao.deleteById(item.getId());
                        break;
                    }
                }
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 显示用歌手：占位（空／<unknown>／unknown 类）一律显统一文案，不只空值。 */
    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    /** 歌单行内「更多」表（GZ 行表＋移除／换源／刷新／改封面；同步到云端需账号，排除只列不建） */
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
            MusicOptionsDialog.show(this, track.displayTitle(), opts, -1, idx -> {
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

    /** 歌曲信息（歌名／歌手／专辑／时长／来源／封面有无，全是实测值） */
    private void showTrackInfo(MusicTrack track) {
        LinearLayout box = new LinearLayout(this);
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
            TextView tv = new TextView(this);
            tv.setText(r[0] + "：" + r[1]);
            tv.setTextSize(14);
            tv.setTextColor(0xFF1C1C1E);
            tv.setPadding(0, dp(3), 0, dp(3));
            box.addView(tv);
        }
        new MusicGlassDialogBuilder(this)
                .setTitle("歌曲信息")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 获取元数据（刷新）＝按原插件／同平台重搜同名歌，命中则原位替换全部字段；本地／设备歌暂不支持 */
    private void refreshSheetTrack(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || MusicDownloadStore.isLocal(track)) {
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
                MusicLibrary.replaceTrackInPlaylist(playlistId, track, h, ok -> {
                    Notify.show(ok ? "已刷新" : "刷新失败");
                    if (ok) loadSheetTracks();
                });
            });
        });
    }

    /** 重新匹配封面＝重搜同名歌，只把封面换成命中那首的（其它字段不动） */
    private void rematchSheetCover(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || MusicDownloadStore.isLocal(track)) {
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
                MusicLibrary.updateTrackCover(playlistId, track, h.cover, () -> {
                    Notify.show("封面已更新");
                    loadSheetTracks();
                });
            });
        });
    }

    /** 换源＝全站搜同名歌，按平台去重（跳过本首、跳过单插件屏蔽的平台对），选一个原位替换 */
    private void switchSheetSource(MusicTrack track) {
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(track.platform)
                || MusicDownloadStore.isLocal(track)) {
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
                            // 单插件屏蔽平台＝该插件屏蔽掉的平台不进换源候选；
                            // 判据用候选的内容平台（源层 platform 是 freejs/lx 这类，与屏蔽键不同层）
                            String cp = com.fongmi.android.tv.music.MusicIdentity.contentPlatformOf(c);
                            if (MusicStore.isPlatformBlocked(s.srcUrl, cp != null ? cp : plat)) continue;
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
                MusicOptionsDialog.show(this, "换源", names, -1, idx -> {
                    MusicTrack pick = found.get(idx);
                    MusicLibrary.replaceTrackInPlaylist(playlistId, track, pick, ok -> {
                        Notify.show(ok ? "已换源" : "换源失败");
                        if (ok) loadSheetTracks();
                    });
                });
            });
        });
    }

    /** 歌单行平台名（本地／设备＝「本地」，其余走瓜子平台名，取不到则不显） */
    private String sheetPlatformName(MusicTrack t) {
        if (t == null || t.platform == null || t.platform.isEmpty()) return "";
        if (com.fongmi.android.tv.music.DeviceMusicSource.PLATFORM.equals(t.platform)) return "本地";
        if (MusicDownloadStore.isLocal(t)) return "本地";
        return com.fongmi.android.tv.api.music.LxPlatformSearch.platformName(t.platform);
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

    // ================= 列表 =================

    private class SheetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_SHEET_HERO = 7;
        private static final int TYPE_SHEET_ACTIONS = 8;
        private static final int TYPE_SHEET_SECTION = 9;
        private static final int TYPE_SHEET_TRACK = 10;

        @Override
        public int getItemViewType(int position) {
            // 歌单详情页＝英雄卡＋操作行＋区头＋歌曲行（英雄卡随列表滚动，与 GZ 一致）
            if (position == 0) return TYPE_SHEET_HERO;
            if (position == 1) return TYPE_SHEET_ACTIONS;
            if (position == 2) return TYPE_SHEET_SECTION;
            return TYPE_SHEET_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_SHEET_HERO) {
                return new SheetHeroHolder(inf.inflate(R.layout.item_music_sheet_hero, parent, false));
            }
            if (viewType == TYPE_SHEET_ACTIONS) {
                return new SheetActionsHolder(inf.inflate(R.layout.item_music_sheet_actions, parent, false));
            }
            if (viewType == TYPE_SHEET_SECTION) {
                return new SheetSectionHolder(inf.inflate(R.layout.item_music_sheet_section, parent, false));
            }
            return new SheetTrackHolder(inf.inflate(R.layout.item_music_sheet_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            if (h instanceof SheetHeroHolder) bindSheetHero((SheetHeroHolder) h);
            else if (h instanceof SheetActionsHolder) bindSheetActions((SheetActionsHolder) h);
            else if (h instanceof SheetSectionHolder) bindSheetSection((SheetSectionHolder) h);
            else bindSheetTrack((SheetTrackHolder) h, trackIndex(position));
        }

        /** 歌曲行在 tracks 里的下标：前面有英雄卡／操作行／区头 3 行 */
        private int trackIndex(int position) {
            return position - 3;
        }

        @Override
        public int getItemCount() {
            return 3 + tracks.size();
        }

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
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
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

        /** 歌单英雄卡绑定（背景高 0.56×屏高；封面＝自定义＞首首歌封面＞灰底；简介仅非空时显示） */
        private void bindSheetHero(SheetHeroHolder h) {
            int screenH = h.itemView.getResources().getDisplayMetrics().heightPixels;
            android.view.ViewGroup.LayoutParams lp = h.itemView.getLayoutParams();
            if (lp != null) {
                lp.height = (int) (screenH * 0.56f);
                h.itemView.setLayoutParams(lp);
            }
            h.heroName.setText(bigTitle);
            // 全幅英雄卡名照瓜子 FullBleedPlaylistHero：24sp／800／字距 −0.5sp（24sp × −0.021em）
            h.heroName.getPaint().setFakeBoldText(true);
            h.heroName.setLetterSpacing(-0.021f);
            String desc = MusicStore.getSheetDesc(playlistId);
            if (desc == null || desc.isEmpty()) {
                h.heroDesc.setVisibility(View.GONE);
            } else {
                h.heroDesc.setVisibility(View.VISIBLE);
                h.heroDesc.setText(desc);
            }
            String custom = MusicStore.getSheetCover(playlistId);
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

        /** 歌单操作行绑定（固定顺序：随机播放／播放全部（主按钮）／收藏歌单；续播行；多选行） */
        private void bindSheetActions(SheetActionsHolder h) {
            h.normalRow.setVisibility(sheetMulti ? View.GONE : View.VISIBLE);
            h.multiRow.setVisibility(sheetMulti ? View.VISIBLE : View.GONE);
            h.btnShuffle.setOnClickListener(v -> shufflePlay());
            h.btnPlayAll.setOnClickListener(v -> {
                if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
                else playTrack(0);
            });
            boolean fav = MusicStore.loadSheetFavs().contains(playlistId);
            h.btnFav.setText(fav ? "取消收藏歌单" : "收藏歌单");
            h.btnFav.setOnClickListener(v -> {
                boolean nowFav = !MusicStore.loadSheetFavs().contains(playlistId);
                MusicStore.setSheetFav(playlistId, nowFav);
                adapter.notifyItemChanged(1);
            });
            // 续播行：最近播放里第一首在本单里的，否则「暂无播放进度」
            String resume = null;
            try {
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (MusicTrack t : sheetAll) if (t.id != null) ids.add(t.id);
                for (MusicTrack r : MusicStore.loadRecent()) {
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

        /** 歌单歌曲行绑定（封面 46dp、歌名 14sp、第二行 12sp＝歌手［· 专辑］［· 平台］［· 不可播原因］） */
        private void bindSheetTrack(SheetTrackHolder h, int position) {
            MusicTrack t = tracks.get(position);
            MusicTrack cur = MusicController.get().current();
            boolean isCurrent = cur != null && cur.id != null && cur.id.equals(t.id);
            h.title.setText(t.displayTitle());
            // 歌名恒粗（布局 textStyle）＋#1A1A1A，在播行只变红名
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
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
                boolean sel = t.id != null && sheetSelected.contains(MusicQueue.trackKey(t));
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
                        String key = MusicQueue.trackKey(t);
                        if (sheetSelected.contains(key)) sheetSelected.remove(key);
                        else sheetSelected.add(key);
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

        /** 歌单行不可播原因（GZ 原因来自服务端字段，星辰无此字段；只做实测的一种：本地／设备文件不存在） */
        private String sheetUnplayableReason(MusicTrack t) {
            try {
                if (t != null && t.localPath != null && !t.localPath.isEmpty()) {
                    if (!new java.io.File(t.localPath).exists()) return "文件不存在";
                }
            } catch (Throwable ignored) {
            }
            return "";
        }
    }
}
