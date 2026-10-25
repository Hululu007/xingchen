package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 猜你喜欢独立页（拆页 p7）：标题行＋英雄卡（叠卡封面＋线索文案＋播放全部／随机／保存）
 * ＋精选区头（N 首＋换个顺序）＋精选行；
 * 数据＝收藏／最近播放／已下载三源混池（轮流取、按 id 去重、上限 50 首），
 * 行点击走 MusicController 播放，队列 route=list、参数 mode=guess，
 * 队列面板来源跳转落回本页。
 * 旧页顶栏／大标题行／工具条在 guess 下恒隐藏，本页不带；无返回箭头，走系统返回。
 */
public class MusicGuessActivity extends BaseActivity {

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
    private RecyclerView recycler;
    private TextView empty;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private GuessAdapter adapter;
    private String bigTitle = "";
    /** 猜你喜欢线索数（文案「来自收藏与最近播放的 N 条线索」里的 N，收藏＋最近播放去重后条数） */
    private int guessClueCount = 0;
    private String lastCurrentId;
    private android.os.Parcelable pendingListState;

    public static void startGuess(Activity activity) {
        activity.startActivity(new Intent(activity, MusicGuessActivity.class));
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
        rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_guess, null);
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
        bigTitle = getString(R.string.music_guess);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        // 空态点击监听是旧共享 overlay 的复位口径：本页空态无点击行为，进页先清
        empty.setOnClickListener(null);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter = new GuessAdapter());
        MusicController.get().addListener(controllerListener);
        loadGuess();
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
        // 旧口径：猜你喜欢回页不重拉（旧 onResume 只重拉 sheets/sheet）
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

    /**
     * 猜你喜欢。推荐算法待他定——先用本地已有数据简单轮换顶上，不假装是 GZ 的推荐算法：
     * 收藏／最近播放／已下载三路轮流取、按 id 去重、上限 50 首。
     * 设备媒体库扫描要权限流程，不在猜你喜欢页内触发（“本地”口径这里＝已下载本地文件）。
     * 空态用 GZ 空文案；“推荐获取失败／重试”是拉取失败态，本地数据没有失败触发点，只显空态。
     * 旧 mode.equals("guess") 守卫在本页＝存活守卫（页不在了就不回填）。
     */
    private void loadGuess() {
        showLoading();
        MusicLibrary.favorites(favs -> {
            if (isFinishing() || isDestroyed()) return;
            MusicDownloadStore.all(dls -> {
                if (isFinishing() || isDestroyed()) return;
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
                    if (isFinishing() || isDestroyed()) return;
                    tracks.clear();
                    tracks.addAll(pool);
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
                    if (tracks.isEmpty()) empty.setText("熟悉的偏爱，也有意外的新发现\n多听几首，让推荐慢慢懂你");
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

    /** 猜你喜欢英雄卡跟随首张封面取色淡染（榜单卡同款口径，圆角 20dp，失败回落白卡） */
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

    // ================= 播放 =================

    /** list 路由参数照旧口径（与旧壳 listRouteParams 在 guess 下的取值逐字同：
     * mode=guess＋keyword/sitePlatform 空＋playlistId=-1＋title=页标题） */
    private java.util.Map<String, String> listRouteParams() {
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("mode", "guess");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle == null ? "" : bigTitle);
        return params;
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position, bigTitle, "list", listRouteParams());
        MusicPlayerActivity.start(this);
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

    /** tracks 下标 → adapter 位置：前面有标题／英雄卡／区头 3 行（旧共享 adapter 口径） */
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
                // 设备歌曲本来就在手机里，不给"下载"；旧 guess 下无歌单移除项（那是 sheet 专属）
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

    // ================= 工具 =================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String artistDisplay(String raw) {
        return com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(raw)
                ? getString(R.string.music_unknown_artist) : raw;
    }

    // ================= 列表 =================

    private class GuessAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_GUESS_TITLE = 16;
        private static final int TYPE_GUESS_HERO = 17;
        private static final int TYPE_GUESS_SECTION = 18;
        private static final int TYPE_GUESS_TRACK = 19;

        @Override
        public int getItemViewType(int position) {
            // 猜你喜欢＝标题＋英雄卡＋精选区头＋精选行（标题与英雄卡随列表滚动，与 GZ 一致）
            if (position == 0) return TYPE_GUESS_TITLE;
            if (position == 1) return TYPE_GUESS_HERO;
            if (position == 2) return TYPE_GUESS_SECTION;
            return TYPE_GUESS_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_GUESS_TITLE) {
                return new GuessTitleHolder(inf.inflate(R.layout.item_music_guess_title, parent, false));
            }
            if (viewType == TYPE_GUESS_HERO) {
                return new GuessHeroHolder(inf.inflate(R.layout.item_music_guess_hero, parent, false));
            }
            if (viewType == TYPE_GUESS_SECTION) {
                return new GuessSectionHolder(inf.inflate(R.layout.item_music_guess_section, parent, false));
            }
            return new GuessTrackHolder(inf.inflate(R.layout.item_music_guess_track, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            // 四类行按行义分发：标题行纯静态无点击，英雄卡走其操作行，区头走换序，精选行才播放
            if (h instanceof GuessTitleHolder) return;
            else if (h instanceof GuessHeroHolder) bindGuessHero((GuessHeroHolder) h);
            else if (h instanceof GuessSectionHolder) bindGuessSection((GuessSectionHolder) h);
            else bindGuessTrack((GuessTrackHolder) h, trackIndex(position));
        }

        /** 歌曲行在 tracks 里的下标：前面有标题／英雄卡／区头 3 行 */
        private int trackIndex(int position) {
            return position - 3;
        }

        @Override
        public int getItemCount() {
            return tracks.isEmpty() ? 0 : 3 + tracks.size();
        }

        private GradientDrawable circle(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            return d;
        }

        /** 猜你喜欢四类行 ViewHolder（标题行是纯静态文案，无需字段） */
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
                MusicGlassWire.glassifyTree(v); // 行一框：行内玻璃件同源接线（根或内卡按资源识别）
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
                MusicGlassWire.glassifyTree(v); // 分区行胶囊同源真玻璃
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
                MusicPlayerActivity.start(MusicGuessActivity.this);
            });
            h.btnShuffle.setBackground(circle(0xFFF2F2F7));
            h.btnShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
            h.btnShuffle.setOnClickListener(v -> {
                if (tracks.isEmpty()) return;
                List<MusicTrack> shuffled = new ArrayList<>(tracks);
                Collections.shuffle(shuffled);
                MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
                MusicController.get().play(shuffled, 0, bigTitle, "list", listRouteParams());
                MusicPlayerActivity.start(MusicGuessActivity.this);
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
            // 文案“换个顺序”——重算做不到：loadGuess 的池是收藏／最近／已下载
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
            // 共用行口径：歌名恒粗（布局 textStyle）＋#1A1A1A，在播行只变红名
            h.title.setTextColor(isCurrent ? 0xFFFA243C : 0xFF1A1A1A);
            MusicGlassWire.frameRow(h.itemView); // 行一框：液态玻璃（原口径非当前行裸底、当前行白卡；当前行另有红名＋在播字样作标识）
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
    }
}
