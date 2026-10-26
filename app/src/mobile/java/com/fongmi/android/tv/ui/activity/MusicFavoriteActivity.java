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
import com.fongmi.android.tv.utils.XcFadeTransition;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 音乐收藏独立页（拆页 p4）：英雄卡（大标题＋N 首＋播放全部／随机播放
 * ＋多选＋页内搜索框＋排序＋续播卡＋多选删除；页头原拼贴封面块已按潇洒哥令整块移除，
 * 标题与首数行左对齐顶页边）；
 * 数据源＝MusicLibrary 收藏（favorites），行点击走 MusicController 播放，
 * 队列 route=list、参数 mode=favorite，队列面板来源跳转落回本页。
 * 顶栏只留标题，无返回箭头，走系统返回。
 */
public class MusicFavoriteActivity extends BaseActivity {

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
    private View favHistResume;
    private TextView tvFavHistResumeTitle;
    private TextView tvFavHistResumeState;
    private TextView tvFavHistSection;
    private View btnPlayAll;
    private TextView tvPlayAll;
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

    /** 显示列表（收藏全量按页内搜索过滤＋排序后） */
    private final List<MusicTrack> tracks = new ArrayList<>();
    private FavoriteAdapter adapter;

    // 收藏页态：英雄卡全量＋页内搜索＋排序（0 最近收藏 1 最早收藏 2 歌曲名称）＋多选＋续播
    private final List<MusicTrack> favAll = new ArrayList<>();
    private String favSearchKw = "";
    private int favSort = 0;
    private boolean favMulti;
    private final java.util.Set<String> favSelected = new java.util.HashSet<>();
    private MusicTrack favResume;
    private boolean favSearchWired;

    private String bigTitle = "";
    /** onResume 首过标记：首次由 initView 的 loadFavorites 负责，之后回页才重拉。 */
    private boolean resumedOnce;
    private android.os.Parcelable pendingListState;
    private String lastCurrentId;

    public static void startFavorite(Activity activity) {
        activity.startActivity(new Intent(activity, MusicFavoriteActivity.class));
    }

    /** 收藏变化订阅：写库落盘后 MusicLibrary 才广播，这里再静默重拉——
     *  旧 toggleFavorite 在写刚发出时就抢跑 loadFavorites，5 线程池里读赢了删除、
     *  列表刷成旧账且再无第二次重拉，歌就赖在列表里（2026-10-26 真机截图实锤）。 */
    private final MusicLibrary.FavoritesListener favoritesListener = () -> loadFavorites(true);

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
        if (rootView == null) rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_favorite, null);
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
        favHistResume = findViewById(R.id.favHistResume);
        tvFavHistResumeTitle = findViewById(R.id.tvFavHistResumeTitle);
        tvFavHistResumeState = findViewById(R.id.tvFavHistResumeState);
        tvFavHistSection = findViewById(R.id.tvFavHistSection);
        btnPlayAll = findViewById(R.id.btnPlayAll);
        tvPlayAll = findViewById(R.id.tvPlayAll);
        btnPlaySeq = findViewById(R.id.btnPlaySeq);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);

        bigTitle = getString(R.string.music_favorite);
        tvTitle.setText(R.string.music_favorite);
        // 英雄卡操作行
        btnFavHistPlayAll.setOnClickListener(v -> {
            if (tracks.isEmpty()) Notify.show(R.string.music_no_track);
            else playTrack(0);
        });
        btnFavHistShuffle.setOnClickListener(v -> shufflePlay());
        btnFavHistMulti.setOnClickListener(v -> setFavoriteMulti(!favMulti));
        favHistResume.setOnClickListener(v -> {
            if (favResume == null) return;
            int idx = -1;
            for (int j = 0; j < favAll.size(); j++) {
                if (favResume.id != null && favResume.id.equals(favAll.get(j).id)) {
                    idx = j;
                    break;
                }
            }
            if (idx < 0) return;
            MusicController.get().play(new ArrayList<>(favAll), idx, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
        });
        btnFavHistClearSearch.setOnClickListener(v -> etFavHistSearch.setText(""));
        if (!favSearchWired) {
            favSearchWired = true;
            etFavHistSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                }

                @Override
                public void onTextChanged(CharSequence s, int st, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    favSearchKw = s == null ? "" : s.toString().trim();
                    btnFavHistClearSearch.setVisibility(
                            favSearchKw.isEmpty() ? View.GONE : View.VISIBLE);
                    refreshFavoriteDisplay();
                }
            });
        }
        // 工具栏：多选态外只留排序（播放／随机播在英雄卡操作行）；btnPlayAll 多选态复用作删除所选
        btnPlayAll.setOnClickListener(v -> shufflePlay());
        btnPlaySeq.setOnClickListener(v -> playAllSequential());
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new FavoriteAdapter());
        // 收藏行间分隔线（GZ：0.5dp 厚、起始偏移 76dp、末端 14dp，与 76dp 拼贴对齐）
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
        MusicLibrary.addFavoritesListener(favoritesListener);
        loadFavorites();
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
        loadFavorites();
    }

    @Override
    protected void onDestroy() {
        MusicLibrary.removeFavoritesListener(favoritesListener);
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 数据加载 =================

    private void showLoading() {
        XcFadeTransition.fadeIn(empty);
        empty.setText("正在加载…");
    }

    private void loadFavorites() {
        loadFavorites(false);
    }

    /** @param quiet true＝收藏变化广播触发的重拉：不闪「正在加载…」，列表原地换新账 */
    private void loadFavorites(boolean quiet) {
        if (!quiet) showLoading();
        MusicLibrary.favorites(list -> {
            favAll.clear();
            if (list != null) favAll.addAll(list);
            refreshFavoriteDisplay();
        });
    }

    /** 收藏显示刷新（英雄卡＋搜索过滤＋排序＋续播卡；空态用 GZ 文案） */
    private void refreshFavoriteDisplay() {
        favHistHero.setVisibility(View.VISIBLE);
        tvFavHistLabel.setVisibility(View.VISIBLE);
        // 英雄卡标题是「我喜欢的音乐」，不是导航用的「收藏」（bigTitle）
        tvFavHistTitle.setText("我喜欢的音乐");
        tvFavHistCount.setText(favAll.size() + " 首");
        btnFavHistClear.setVisibility(View.GONE);
        btnFavHistMulti.setText(favMulti ? "取消多选" : "多选");
        etFavHistSearch.setHint("搜索喜欢的歌曲");
        // 续播卡：收藏页＝最近播放里第一首在收藏里的（无则不显）
        favResume = null;
        try {
            List<MusicTrack> recent = MusicStore.loadRecent();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (MusicTrack t : favAll) if (t.id != null) ids.add(t.id);
            for (MusicTrack r : recent) {
                if (r.id != null && ids.contains(r.id)) {
                    favResume = r;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (favResume != null) {
            favHistResume.setVisibility(View.VISIBLE);
            MusicTrack cur = MusicController.get().current();
            boolean isCur = cur != null && cur.id != null && cur.id.equals(favResume.id);
            // 在播/暂停前缀只在续播曲就是当前曲时加；不是当前曲时不称暂停
            String prefix = !isCur ? ""
                    : (MusicController.get().isPlaying() ? "正在播放 · " : "已暂停 · ");
            tvFavHistResumeTitle.setText(prefix + favResume.displayTitle());
            tvFavHistResumeState.setText("上次听到这首"
                    + (favResume.displayArtist().isEmpty() ? "" : " · " + favResume.displayArtist()));
        } else {
            favHistResume.setVisibility(View.GONE);
        }
        // 搜索过滤＋排序
        List<MusicTrack> show = new ArrayList<>();
        for (MusicTrack t : favAll) {
            if (!favSearchKw.isEmpty()) {
                String kw = favSearchKw.toLowerCase(java.util.Locale.getDefault());
                String title = t.title == null ? "" : t.title.toLowerCase(java.util.Locale.getDefault());
                String artist = t.artist == null ? "" : t.artist.toLowerCase(java.util.Locale.getDefault());
                if (!title.contains(kw) && !artist.contains(kw)) continue;
            }
            show.add(t);
        }
        if (favSort == 2) {
            Collections.sort(show, java.util.Comparator.comparing(t -> t.title == null ? "" : t.title));
        } else if (favSort == 0) {
            // 收藏：0 最近收藏＝favoriteAddedTime 降序，1 最早收藏＝升序
            Collections.sort(show, (a, b) -> Long.compare(b.favoriteAddedTime, a.favoriteAddedTime));
        } else {
            Collections.sort(show, (a, b) -> Long.compare(a.favoriteAddedTime, b.favoriteAddedTime));
        }
        tracks.clear();
        tracks.addAll(show);
        adapter.notifyDataSetChanged();
        fadeListInOnce();
        // 列表区头（瓜子 SectionHeader：收藏「全部歌曲」／搜时「搜索结果」；
        // 区头放英雄卡块内静态行，不进 adapter——收藏列表无区头项，trackIndex 不动）
        if (tvFavHistSection != null) {
            if (tracks.isEmpty()) {
                tvFavHistSection.setVisibility(View.GONE);
            } else {
                tvFavHistSection.setVisibility(View.VISIBLE);
                tvFavHistSection.setText(favSearchKw.isEmpty()
                        ? getString(R.string.wave2_section_all_songs)
                        : getString(R.string.wave2_section_search_result));
            }
        }
        if (tracks.isEmpty()) {
            XcFadeTransition.fadeIn(empty);
            // 搜不到时显 GZ 搜不到文案，不许套用收藏空态
            if (!favSearchKw.isEmpty()) {
                empty.setText("未找到匹配歌曲\n换个关键词试试歌名、歌手或专辑");
            } else {
                empty.setText("还没有喜欢的歌曲\n播放时点亮爱心，它们会出现在这里");
            }
        } else {
            XcFadeTransition.fadeOutThenGone(empty);
        }
        refreshFavoriteMultiText();
    }

    
    /** 收藏多选态切换（GZ：选中圆圈 24dp＋「已选 N 首」；工具条复用口径：删除所选／取消） */
    private void setFavoriteMulti(boolean on) {
        favMulti = on;
        if (!on) favSelected.clear();
        btnFavHistMulti.setText(on ? "取消多选" : "多选");
        if (on) {
            btnPlayAll.setVisibility(View.VISIBLE);
            tvPlayAll.setText("删除所选 (" + favSelected.size() + ")");
            btnPlayAll.setOnClickListener(v -> deleteFavoriteSelected());
        } else {
            btnPlayAll.setVisibility(View.GONE);
            btnPlayAll.setOnClickListener(v -> shufflePlay());
        }
        refreshFavoriteMultiText();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void refreshFavoriteMultiText() {
        if (favMulti) tvPlayAll.setText("删除所选 (" + favSelected.size() + ")");
    }

    private void deleteFavoriteSelected() {
        if (favSelected.isEmpty()) {
            setFavoriteMulti(false);
            return;
        }
        List<MusicTrack> sel = new ArrayList<>();
        for (MusicTrack t : favAll) if (t.id != null && favSelected.contains(t.id)) sel.add(t);
        final int[] left = {sel.size()};
        for (MusicTrack t : sel) {
            MusicLibrary.setFavorite(t, false, () -> {
                if (--left[0] <= 0) {
                    setFavoriteMulti(false);
                    loadFavorites();
                }
            });
        }
    }

    // ================= 播放 =================

    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "favorite");
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

    /** tracks 下标 → adapter 位置：收藏列表无区头项，下标即位置。 */
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
        // 列表刷新不再在这里抢跑：写落盘后 favoritesListener 收到广播才重拉，读到的必是新账
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

    private class FavoriteAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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
                // 共用行封面圆角 12dp 口径（本页 10dp helper，与旧页收藏行同值）
                loadCoverRounded(t.displayTitle(), t.cover, h.cover, 10);
            }
            if (favMulti) {
                // 收藏多选态（行点按变勾选；行尾 24dp 选中圆圈，选中红底、未选灰框）
                boolean sel = t.id != null && favSelected.contains(t.id);
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
                        if (favSelected.contains(t.id)) favSelected.remove(t.id);
                        else favSelected.add(t.id);
                    }
                    refreshFavoriteMultiText();
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
