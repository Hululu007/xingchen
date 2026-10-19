package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
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
import com.fongmi.android.tv.databinding.ActivityMusicListBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.MusicOptionsDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 歌曲列表页：大标题＋工具条，行＝封面左＋两行字＋徽章＋时长/⋮，
 * 当前播行高亮＋歌名变金。兼做搜索、我的歌单、榜单列表。
 */
public class MusicListActivity extends BaseActivity {

    private ActivityMusicListBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<Object> cards = new ArrayList<>();
    private ListAdapter adapter;

    private String mode = "tracks";
    private String keyword = "";
    private String bigTitle = "";
    private String sitePlatform = "";
    private long playlistId = -1;

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

    public static void startCharts(Activity activity) {
        Intent i = new Intent(activity, MusicListActivity.class);
        i.putExtra("mode", "charts");
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
    };

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityMusicListBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
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
        bigTitle = i.getStringExtra("title");
        if (bigTitle == null) bigTitle = "";

        binding.recycler.setLayoutManager(new LinearLayoutManager(this));
        binding.recycler.setAdapter(adapter = new ListAdapter());
        binding.btnPlayAll.setOnClickListener(v -> shufflePlay());
        binding.btnSort.setOnClickListener(v -> cycleSort());
        binding.btnSearch.setOnClickListener(v -> startSearch(this, ""));
        binding.btnDoSearch.setOnClickListener(v -> doSearch());
        binding.etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        MusicController.get().addListener(controllerListener);
        load();
    }

    @Override
    protected void onDestroy() {
        MusicController.get().removeListener(controllerListener);
        MusicController.get().unbind();
        super.onDestroy();
    }

    private boolean isCardMode() {
        return mode.equals("sheets") || mode.equals("charts");
    }

    private void load() {
        binding.tvTitle.setText(titleForMode());
        binding.searchBar.setVisibility(mode.equals("search") ? View.VISIBLE : View.GONE);
        binding.btnSearch.setVisibility(mode.equals("search") ? View.GONE : View.VISIBLE);
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
                loadCharts();
                break;
            case "favorite":
                bigTitle = getString(R.string.music_favorite);
                loadFavorites();
                break;
            case "site":
                loadSiteTracks();
                break;
            case "sheet":
                loadSheetTracks();
                break;
            case "search":
                bigTitle = getString(R.string.music_search);
                showTracks(new ArrayList<>());
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
            default:
                return getString(R.string.music_playlist);
        }
    }

    private void doSearch() {
        keyword = binding.etKeyword.getText().toString().trim();
        if (keyword.isEmpty()) {
            Notify.show(R.string.music_search_hint);
            return;
        }
        bigTitle = keyword;
        showLoading();
        Task.execute(() -> {
            List<MusicTrack> r = MusicRepository.search(keyword);
            runOnUiThread(() -> showTracks(r));
        });
    }

    private void loadSheets() {
        showLoading();
        MusicLibrary.playlists(list -> {
            cards.clear();
            cards.add(new NewPlaylistCard());
            for (MusicPlaylist p : list) cards.add(p);
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                binding.tvBigCount.setText(list.size() + " 个歌单");
                binding.empty.setVisibility(View.GONE);
                // 异步补每首歌单的曲目数
                for (int idx = 0; idx < cards.size(); idx++) {
                    Object o = cards.get(idx);
                    if (o instanceof MusicPlaylist) {
                        int pos = idx;
                        MusicPlaylist p = (MusicPlaylist) o;
                        MusicLibrary.playlistTrackCount(p.getId(), n -> {
                            p.trackCount = n;
                            adapter.notifyItemChanged(pos);
                        });
                    }
                }
            });
        });
    }

    private void loadCharts() {
        showLoading();
        Task.execute(() -> {
            List<MusicSite> sites = MusicRepository.sites();
            List<MusicChart> charts = new ArrayList<>();
            for (MusicSite s : sites) charts.addAll(MusicRepository.charts(s));
            runOnUiThread(() -> {
                cards.clear();
                cards.addAll(charts);
                adapter.notifyDataSetChanged();
                binding.tvBigTitle.setText(bigTitle);
                binding.tvBigCount.setText(charts.size() + " 个榜单");
                binding.empty.setVisibility(charts.isEmpty() ? View.VISIBLE : View.GONE);
                if (charts.isEmpty()) binding.empty.setText(R.string.music_no_result);
            });
        });
    }

    private void loadFavorites() {
        showLoading();
        MusicLibrary.favorites(this::showTracks);
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
        MusicLibrary.playlistTracks(playlistId, this::showTracks);
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
            binding.empty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
            if (tracks.isEmpty() && !mode.equals("search")) binding.empty.setText(R.string.music_no_track);
            else if (tracks.isEmpty()) binding.empty.setText(R.string.music_no_result);
        });
    }

    private void sortTracks(List<MusicTrack> list) {
        int sort = MusicStore.getListSort();
        if (sort == 1) Collections.sort(list, Comparator.comparing(t -> t.title == null ? "" : t.title));
        else if (sort == 2) Collections.sort(list, Comparator.comparing(t -> t.artist == null ? "" : t.artist));
        else if (sort == 3) Collections.sort(list, Comparator.comparingLong(t -> t.duration));
    }

    private void cycleSort() {
        int s = (MusicStore.getListSort() + 1) % 4;
        MusicStore.setListSort(s);
        String[] names = {getString(R.string.music_sort_default), getString(R.string.music_sort_title),
                getString(R.string.music_sort_artist), getString(R.string.music_sort_duration)};
        Notify.show(names[s]);
        if (isCardMode()) load();
        else showTracks(new ArrayList<>(tracks));
    }

    private void shufflePlay() {
        if (tracks.isEmpty()) {
            Notify.show(R.string.music_no_track);
            return;
        }
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        MusicController.get().setMode(MusicQueue.MODE_SHUFFLE);
        MusicController.get().play(shuffled, 0);
        MusicPlayerActivity.start(this);
    }

    private void playTrack(int position) {
        MusicController.get().play(new ArrayList<>(tracks), position);
        MusicPlayerActivity.start(this);
    }

    private void refreshCurrentRow() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private static class NewPlaylistCard {
    }

    // ================= ⋮ 菜单 =================

    private void onMore(MusicTrack track, int position) {
        FragmentActivity act = this;
        List<String> opts = new ArrayList<>();
        opts.add("播放");
        opts.add(getString(R.string.music_play_next));
        opts.add(getString(R.string.music_add_to_playlist));
        MusicLibrary.isFavorite(track.id, fav -> {
            opts.add(fav ? getString(R.string.music_unfavorite) : getString(R.string.music_favorite));
            if (mode.equals("sheet")) opts.add(getString(R.string.music_remove));
            MusicOptionsDialog.show(act, track.displayTitle(), opts, -1, idx -> {
                if (idx == 0) playTrack(position);
                else if (idx == 1) {
                    MusicController.get().insertNext(track);
                    Notify.show(getString(R.string.music_play_next));
                } else if (idx == 2) pickPlaylist(track);
                else if (idx == 3) toggleFavorite(track, opts.get(3));
                else if (idx == 4 && mode.equals("sheet")) removeFromSheet(track);
            });
        });
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
        EditText et = new EditText(this);
        et.setHint(R.string.music_playlist_name_hint);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0xFF5E6B7E);
        et.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ================= 列表 =================

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_TRACK = 0;
        private static final int TYPE_CARD = 1;

        @Override
        public int getItemViewType(int position) {
            if (isCardMode()) return TYPE_CARD;
            return TYPE_TRACK;
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

        private void bindCard(CardHolder h, int position) {
            Object o = cards.get(position);
            if (o instanceof NewPlaylistCard) {
                h.name.setText(R.string.music_new_playlist);
                h.status.setText("");
                h.icon.setImageResource(R.drawable.ic_music_plus);
                h.icon.setBackground(circle(0xFF22C55E));
                h.itemView.setOnClickListener(v -> inputPlaylistName(name ->
                        MusicLibrary.createPlaylist(name, p -> loadSheets())));
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
            }
            h.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        }

        private void openChart(MusicChart chart) {
            bigTitle = chart.name;
            mode = "tracks";
            showLoading();
            Task.execute(() -> {
                List<MusicTrack> r = MusicRepository.chartTracks(chart);
                runOnUiThread(() -> showTracks(r));
            });
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
            h.title.setTextColor(isCurrent ? 0xFFF2B33D : 0xFFFFFFFF);
            h.title.getPaint().setFakeBoldText(isCurrent);
            h.itemView.setBackgroundResource(isCurrent ? R.drawable.card_music_current : 0);
            if (t.qualityTag != null && !t.qualityTag.isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(t.qualityTag);
            } else {
                h.badge.setVisibility(View.GONE);
            }
            h.artist.setText(t.displayArtist());
            h.duration.setText(fmt(t.duration));
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            h.itemView.setOnClickListener(v -> playTrack(position));
            h.more.setOnClickListener(v -> onMore(t, position));
        }

        private String fmt(long ms) {
            if (ms <= 0) return "--:--";
            long s = ms / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        @Override
        public int getItemCount() {
            return isCardMode() ? cards.size() : tracks.size();
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
            final ImageView icon;
            final TextView name;
            final TextView status;

            CardHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                name = v.findViewById(R.id.name);
                status = v.findViewById(R.id.status);
            }
        }
    }

    /** 单参回调 */
    private interface NameInput {
        void onResult(String name);
    }
}
