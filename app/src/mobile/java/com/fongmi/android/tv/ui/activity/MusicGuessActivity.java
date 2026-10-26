package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import com.fongmi.android.tv.ui.music.MusicGlassDialogBuilder;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.impl.CustomTarget;
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
import com.fongmi.android.tv.utils.CoverTextDrawable;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 猜你喜欢/每日精选独立页（从零重写，首页三彩卡「猜你喜欢」的子页；独立页：自有布局＋同源玻璃背景）。
 * 数据：收藏／最近播放／已下载三源轮排混池（每轮各源各取 1 首、按 id 去重、上限 50）；
 * 线索数＝收藏＋最近播放按 id 去重后的条数。
 * 列表构成：位 0 标题行（纯静态）、位 1 英雄卡（叠卡三层＋计数＋线索＋播放全部/随机/保存）、
 * 位 2 精选区头（计数＋换个顺序＝本地重排）、其余精选行（adapter 位＝ tracks 下标＋3）。
 * 回页不重拉（onResume 只回滚滚动位）；行菜单/下载选档/删本地/收藏/加歌单照旧口径自带。
 */
public class MusicGuessActivity extends BaseActivity {

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

    private static final int TYPE_TITLE = 16;
    private static final int TYPE_HERO = 17;
    private static final int TYPE_SECTION = 18;
    private static final int TYPE_TRACK = 19;
    private static final int POOL_CAP = 50;

    private View rootView;
    private RecyclerView recycler;
    private TextView empty;
    private GuessAdapter adapter;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private int guessClueCount;
    private String bigTitle = "";
    private android.os.Parcelable pendingListState;
    private boolean scrollRestoreDone;

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> refreshCurrentRow());
        }
    };

    public static void startGuess(Activity activity) {
        activity.startActivity(new Intent(activity, MusicGuessActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        rootView = LayoutInflater.from(this).inflate(R.layout.activity_music_guess, null);
        return new SimpleBinding(rootView);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        MusicGlassWire.glassifyTree(rootView);
        MusicRepository.ensureDebugFake();
        MusicController.get().bind(this);
        MusicController.get().addListener(listener);
        if (savedInstanceState != null) {
            pendingListState = savedInstanceState.getParcelable("ml_listState");
        }
        bigTitle = getString(R.string.music_guess);
        recycler = findViewById(R.id.recycler);
        empty = findViewById(R.id.empty);
        empty.setOnClickListener(null);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new GuessAdapter();
        recycler.setAdapter(adapter);
        loadGuess();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回页不重拉，只回滚滚动位一次
        if (!scrollRestoreDone && pendingListState != null && recycler != null) {
            recycler.post(() -> {
                try {
                    recycler.getLayoutManager().onRestoreInstanceState(pendingListState);
                } catch (Throwable ignored) {
                }
            });
        }
        scrollRestoreDone = true;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            outState.putParcelable("ml_listState", recycler.getLayoutManager().onSaveInstanceState());
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(listener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    // ================= 数据 =================

    private void showLoading() {
        empty.setText("正在加载…");
        empty.setVisibility(View.VISIBLE);
    }

    /** 三路本地数据：收藏（异步）→ 已下载（异步）→ 最近播放（同步读），各带存活守卫。 */
    private void loadGuess() {
        showLoading();
        MusicLibrary.favorites(favs -> {
            if (isFinishing() || isDestroyed()) return;
            MusicDownloadStore.all(dls -> {
                if (isFinishing() || isDestroyed()) return;
                List<MusicTrack> recent = new ArrayList<>();
                try {
                    List<MusicTrack> r = MusicStore.loadRecent();
                    if (r != null) recent.addAll(r);
                } catch (Throwable ignored) {
                }
                guessClueCount = clueCount(favs, recent);
                tracks.clear();
                tracks.addAll(buildGuessPool(favs, recent, dls));
                adapter.notifyDataSetChanged();
                if (tracks.isEmpty()) {
                    empty.setText("熟悉的偏爱，也有意外的新发现\n多听几首，让推荐慢慢懂你");
                    empty.setVisibility(View.VISIBLE);
                } else {
                    empty.setVisibility(View.GONE);
                }
            });
        });
    }

    /** 线索数：收藏＋最近播放按 id 去重（id 为 null 的每条都计）。 */
    private static int clueCount(List<MusicTrack> favs, List<MusicTrack> recent) {
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (List<MusicTrack> src : new List[]{favs, recent}) {
            if (src == null) continue;
            for (MusicTrack t : src) {
                if (t == null) continue;
                if (t.id == null) {
                    n++;
                } else if (seen.add(t.id)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 混池：三源轮流取（源序 收藏→最近→已下载，每轮各取 1 首），按 id 去重（null id 恒入），上限 50。 */
    private static List<MusicTrack> buildGuessPool(List<MusicTrack> favs, List<MusicTrack> recent, List<MusicTrack> dls) {
        List<List<MusicTrack>> sources = new ArrayList<>();
        sources.add(favs == null ? Collections.emptyList() : favs);
        sources.add(recent == null ? Collections.emptyList() : recent);
        sources.add(dls == null ? Collections.emptyList() : dls);
        int[] cursor = new int[sources.size()];
        Set<String> seen = new HashSet<>();
        List<MusicTrack> out = new ArrayList<>();
        boolean progressed = true;
        while (out.size() < POOL_CAP && progressed) {
            progressed = false;
            for (int s = 0; s < sources.size(); s++) {
                List<MusicTrack> src = sources.get(s);
                while (cursor[s] < src.size()) {
                    MusicTrack t = src.get(cursor[s]++);
                    progressed = true;
                    if (t == null) continue;
                    if (t.id != null && !seen.add(t.id)) continue;
                    out.add(t);
                    break;
                }
                if (out.size() >= POOL_CAP) break;
            }
        }
        return out;
    }

    // ================= 播放 =================

    private Map<String, String> listRouteParams() {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "guess");
        params.put("keyword", "");
        params.put("sitePlatform", "");
        params.put("playlistId", "-1");
        params.put("title", bigTitle);
        return params;
    }

    private void playTrack(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) return;
        try {
            MusicController.get().play(new ArrayList<>(tracks), trackIndex, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    private void playAll() {
        if (tracks.isEmpty()) return;
        playTrack(0);
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) return;
        List<MusicTrack> copy = new ArrayList<>(tracks);
        Collections.shuffle(copy);
        try {
            MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
            MusicController.get().play(copy, 0, bigTitle, "list", listRouteParams());
            MusicPlayerActivity.start(this);
        } catch (Throwable ignored) {
        }
    }

    /** 切歌定点刷行：adapter 位＝tracks 下标＋3（标题/英雄卡/区头占前 3 位）。 */
    private int adapterPositionForTrack(MusicTrack track) {
        if (track == null) return -1;
        String key = MusicQueue.trackKey(track);
        for (int i = 0; i < tracks.size(); i++) {
            if (key.equals(MusicQueue.trackKey(tracks.get(i)))) return i + 3;
        }
        return -1;
    }

    private MusicTrack lastCurrent;
    private void refreshCurrentRow() {
        MusicTrack cur = null;
        try {
            cur = MusicController.get().current();
        } catch (Throwable ignored) {
        }
        int oldPos = adapterPositionForTrack(lastCurrent);
        int newPos = adapterPositionForTrack(cur);
        lastCurrent = cur;
        if (adapter == null) return;
        if (oldPos >= 0 && oldPos != newPos) adapter.notifyItemChanged(oldPos);
        if (newPos >= 0) adapter.notifyItemChanged(newPos);
    }

    // ================= 行菜单 =================

    private void onMore(MusicTrack t, int trackIndex) {
        if (t == null || isFinishing() || isDestroyed()) return;
        MusicLibrary.isFavorite(t, fav -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean isLocal = false;
            try {
                isLocal = MusicDownloadStore.isLocal(t);
            } catch (Throwable ignored) {
            }
            List<String> options = new ArrayList<>();
            options.add("播放");
            options.add("下一首播放");
            options.add("加入歌单");
            options.add(Boolean.TRUE.equals(fav) ? "取消收藏" : "收藏");
            final boolean local = isLocal;
            options.add(local ? "删除本地文件" : "下载");
            MusicOptionsDialog.show(this, t.displayTitle(), options, -1, index -> {
                switch (index) {
                    case 0:
                        playTrack(trackIndex);
                        break;
                    case 1:
                        try {
                            MusicController.get().insertNext(t);
                            Notify.show("已设为下一首播放");
                        } catch (Throwable ignored) {
                        }
                        break;
                    case 2:
                        pickPlaylist(t);
                        break;
                    case 3:
                        toggleFavorite(t, Boolean.TRUE.equals(fav));
                        break;
                    case 4:
                        if (local) confirmDeleteLocal(t);
                        else downloadTrack(t);
                        break;
                    default:
                        break;
                }
            });
        }));
    }

    private void toggleFavorite(MusicTrack t, boolean currentlyFav) {
        MusicLibrary.setFavorite(t, !currentlyFav, () -> runOnUiThread(() ->
                Notify.show(!currentlyFav ? R.string.music_favorited : R.string.music_favorite)));
    }

    private void pickPlaylist(MusicTrack t) {
        MusicLibrary.playlists(list -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            List<String> options = new ArrayList<>();
            options.add(getString(R.string.music_new_playlist));
            List<MusicPlaylist> playlists = list == null ? Collections.emptyList() : list;
            for (MusicPlaylist p : playlists) options.add(p.getName() == null ? "" : p.getName());
            MusicOptionsDialog.show(this, getString(R.string.music_add_to_playlist), options, -1, index -> {
                if (index == 0) {
                    inputPlaylistName(name -> MusicLibrary.createPlaylist(name, created ->
                            runOnUiThread(() -> addToPlaylist(created, t))));
                } else if (index - 1 < playlists.size()) {
                    addToPlaylist(playlists.get(index - 1), t);
                }
            });
        }));
    }

    private void addToPlaylist(MusicPlaylist playlist, MusicTrack t) {
        if (playlist == null) return;
        MusicLibrary.addToPlaylist(playlist.getId(), t, added -> runOnUiThread(() -> {
            if (Boolean.TRUE.equals(added)) Notify.show("已加入歌单");
            else Notify.show(R.string.music_already_in);
        }));
    }

    /** 下载选档：先探各档大小再选档（探链只读、不记死、不改播放态）。 */
    private void downloadTrack(MusicTrack t) {
        final int[] values = {MusicStore.QUALITY_SUPER, MusicStore.QUALITY_HIGH,
                MusicStore.QUALITY_STANDARD, MusicStore.QUALITY_LOW};
        MusicDownloadStore.probeQualitySizes(t, values, sizes -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            List<String> options = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                String name = MusicStore.qualityName(values[i]);
                long size = sizes != null && i < sizes.length ? sizes[i] : -1;
                options.add(size > 0 ? name + " · " + formatSize(size) : name);
            }
            MusicOptionsDialog.show(this, "下载", options, -1, index -> {
                if (index >= 0 && index < values.length) {
                    MusicDownloadStore.downloadWithQuality(t, values[index]);
                    Notify.show("已加入下载");
                }
            });
        }));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0fKB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private void confirmDeleteLocal(MusicTrack t) {
        new MusicGlassDialogBuilder(this)
                .setMessage(R.string.music_download_delete_confirm)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> MusicDownloadStore.deleteLocal(t, () -> runOnUiThread(this::loadGuess)))
                .show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private void inputPlaylistName(NameCallback cb) {
        if (isFinishing() || isDestroyed()) return;
        EditText input = new EditText(this);
        input.setHint(R.string.music_playlist_name_hint);
        input.setSingleLine(true);
        new MusicGlassDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!name.isEmpty()) cb.onName(name);
                })
                .show();
    }

    /** 英雄卡保存：把整个池存成新歌单（建歌单弹窗＋importPlaylist 口径）。 */
    private void savePoolAsPlaylist() {
        if (tracks.isEmpty()) return;
        inputPlaylistName(name -> MusicLibrary.importPlaylist(name, new ArrayList<>(tracks), result ->
                runOnUiThread(() -> {
                    if (result == null || result.inserted <= 0) {
                        Notify.show(R.string.music_import_err_empty);
                    } else {
                        Notify.show(getString(R.string.music_imported, name, result.inserted));
                    }
                })));
    }

    // ================= 英雄卡取色 =================

    /** 首首封面取色淡染卡面：主色×0.15＋白×0.85、1dp 0x14000000 描边、圆角 20dp；tag 守卫防迟到串色。 */
    private void tintGuessHero(View heroRoot, String cover) {
        if (heroRoot == null || cover == null || cover.isEmpty()) return;
        heroRoot.setTag(cover);
        ImgUtil.load(cover, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                int main = averageColor(resource);
                int tint = MusicHomeMood.mixColors(main, 0xFFFFFFFF, 0.85f);
                runOnUiThread(() -> {
                    if (!cover.equals(heroRoot.getTag())) return;
                    GradientDrawable bg = new GradientDrawable();
                    bg.setCornerRadius(dp(20));
                    bg.setColor(tint);
                    bg.setStroke(dp(1), 0x14000000);
                    heroRoot.setBackground(bg);
                });
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    private static int averageColor(Bitmap bmp) {
        if (bmp == null || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return 0xFF8E8E93;
        int w = bmp.getWidth(), h = bmp.getHeight();
        long r = 0, g = 0, b = 0, n = 0;
        int step = Math.max(1, Math.min(w, h) / 12);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
        }
        if (n == 0) return 0xFF8E8E93;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ================= Adapter =================

    private final class GuessAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return tracks.isEmpty() ? 0 : 3 + tracks.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_TITLE;
            if (position == 1) return TYPE_HERO;
            if (position == 2) return TYPE_SECTION;
            return TYPE_TRACK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_TITLE) {
                return new TitleHolder(inflater.inflate(R.layout.item_music_guess_title, parent, false));
            }
            if (viewType == TYPE_HERO) {
                View v = inflater.inflate(R.layout.item_music_guess_hero, parent, false);
                return new HeroHolder(v);
            }
            if (viewType == TYPE_SECTION) {
                return new SectionHolder(inflater.inflate(R.layout.item_music_guess_section, parent, false));
            }
            View v = inflater.inflate(R.layout.item_music_guess_track, parent, false);
            MusicGlassWire.glassifyView(v);
            return new TrackHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeroHolder) bindGuessHero((HeroHolder) holder);
            else if (holder instanceof SectionHolder) bindGuessSection((SectionHolder) holder);
            else if (holder instanceof TrackHolder) bindGuessTrack((TrackHolder) holder, position - 3);
            // 标题行纯静态，bind 直接 return
        }
    }

    private static final class TitleHolder extends RecyclerView.ViewHolder {
        TitleHolder(View itemView) {
            super(itemView);
        }
    }

    private static final class HeroHolder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView deckBack2, deckBack1, deckFront;
        final TextView countNum, clue;

        HeroHolder(View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.guessHeroRoot);
            deckBack2 = itemView.findViewById(R.id.deckBack2);
            deckBack1 = itemView.findViewById(R.id.deckBack1);
            deckFront = itemView.findViewById(R.id.deckFront);
            countNum = itemView.findViewById(R.id.tvGuessCountNum);
            clue = itemView.findViewById(R.id.tvGuessClue);
            itemView.findViewById(R.id.btnGuessPlayAll);
            itemView.findViewById(R.id.btnGuessShuffle);
            itemView.findViewById(R.id.btnGuessSave);
        }
    }

    private static final class SectionHolder extends RecyclerView.ViewHolder {
        final TextView count;

        SectionHolder(View itemView) {
            super(itemView);
            count = itemView.findViewById(R.id.tvGuessSectionCount);
        }
    }

    private static final class TrackHolder extends RecyclerView.ViewHolder {
        final ImageView cover, more;
        final TextView title, artist;

        TrackHolder(View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.cover);
            title = itemView.findViewById(R.id.title);
            artist = itemView.findViewById(R.id.artist);
            more = itemView.findViewById(R.id.more);
        }
    }

    private void bindGuessHero(HeroHolder h) {
        loadDeckCover(h.deckFront, 0);
        loadDeckCover(h.deckBack1, 1);
        loadDeckCover(h.deckBack2, 2);
        h.countNum.setText(String.valueOf(tracks.size()));
        h.clue.setText("来自收藏与最近播放的 " + guessClueCount + " 条线索\n越常听，越懂你");
        if (!tracks.isEmpty() && tracks.get(0) != null) {
            tintGuessHero(h.root, tracks.get(0).cover);
        }
        h.itemView.findViewById(R.id.btnGuessPlayAll).setOnClickListener(v -> playAll());
        h.itemView.findViewById(R.id.btnGuessShuffle).setOnClickListener(v -> shufflePlay());
        h.itemView.findViewById(R.id.btnGuessSave).setOnClickListener(v -> savePoolAsPlaylist());
        MusicGlassWire.glassifyView(h.itemView.findViewById(R.id.btnGuessPlayAll));
        MusicGlassWire.glassifyView(h.itemView.findViewById(R.id.btnGuessShuffle));
        MusicGlassWire.glassifyView(h.itemView.findViewById(R.id.btnGuessSave));
    }

    /** 叠卡取封面：不够的层 GONE，不编假封面。 */
    private void loadDeckCover(ImageView iv, int index) {
        MusicTrack t = index < tracks.size() ? tracks.get(index) : null;
        if (t == null || t.cover == null || t.cover.isEmpty()) {
            iv.setVisibility(View.GONE);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        ImgUtil.loadRounded16(t.displayTitle(), t.cover, iv);
    }

    private void bindGuessSection(SectionHolder h) {
        h.count.setText(tracks.size() + " 首");
        View.OnClickListener refresh = v -> {
            Collections.shuffle(tracks);
            adapter.notifyDataSetChanged();
        };
        h.itemView.findViewById(R.id.btnGuessRefresh).setOnClickListener(refresh);
        h.itemView.findViewById(R.id.tvGuessRefresh).setOnClickListener(refresh);
    }

    private void bindGuessTrack(TrackHolder h, int trackIndex) {
        MusicTrack t = trackIndex >= 0 && trackIndex < tracks.size() ? tracks.get(trackIndex) : null;
        if (t == null) return;
        h.title.setText(t.displayTitle());
        String artist = t.displayArtist();
        if (artist == null || artist.trim().isEmpty()) artist = getString(R.string.music_unknown_artist);
        if (t.album != null && !t.album.trim().isEmpty()) artist = artist + " · " + t.album;
        h.artist.setText(artist);
        boolean current = false;
        try {
            MusicTrack cur = MusicController.get().current();
            current = cur != null && MusicQueue.trackKey(cur).equals(MusicQueue.trackKey(t));
        } catch (Throwable ignored) {
        }
        h.title.setTextColor(current ? 0xFFFA243C : 0xFF1A1A1A);
        // 扫描 M23：当前行也走玻璃——旧实现当前行整行换粉色实底 card_music_current，
        // 违反他明令的「选中行粉红底撤掉、只留字色变化」（字色已在上一行给了红色）。
        h.itemView.setBackgroundResource(R.drawable.bg_music_glass_row);
        MusicGlassWire.glassifyView(h.itemView);
        if (t.cover == null || t.cover.isEmpty()) {
            h.cover.setImageDrawable(new CoverTextDrawable(t.displayTitle(), false, 8));
        } else {
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
        }
        h.itemView.setOnClickListener(v -> playTrack(trackIndex));
        h.more.setOnClickListener(v -> onMore(t, trackIndex));
    }
}
