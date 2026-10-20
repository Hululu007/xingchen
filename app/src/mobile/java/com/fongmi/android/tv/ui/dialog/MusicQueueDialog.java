package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicQueueBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicRepository;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放队列面板（GZ PlayQueueSheet 证据 redo2-gz-queue-evidence 逐项转写）：
 * 头部 44dp＝[‹ 圆钮][标题 17sp/800][随机筹（仅随机模式）][计数筹·纯数字][清空·红 13sp][✕ 圆钮]，‹ 与 ✕ 均关面板；
 * 来源行＝开面板时按 track.platform 统计队列级来源，全队一致才显示（星辰无 GZ 的 route 跳转，不可点、无 ›）；
 * 提示行「长按拖动调整顺序」；队列行＝序号 %02d＋封面 36dp＋歌名 13sp/700＋歌手 11sp＋三钮常显（30dp/图标 18dp），
 * 无时长/播放指示/手柄，长按整行拖动排序，被拖行白底圆角 12；当前行歌名红 #FA243C、歌手红 0.72、序号红 0.7；
 * 清空＝清队并关面板（无二次确认）；空态照 GZ。上一版的「中性深代红」口径无用户原话、与本体证据冲突，本版废止。
 */
public class MusicQueueDialog extends BaseBottomSheetDialog {

    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_RED = 0xFFFA243C; // GZ AccentRed（PlayQueueSheetKt:130 实证）
    private static final int COLOR_CURRENT_ARTIST = 0xB8FA243C; // 当前行歌手＝AccentRed × 0.72
    private static final int COLOR_CURRENT_INDEX = 0xB3FA243C; // 当前行序号＝AccentRed × 0.7

    private DialogMusicQueueBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private QueueAdapter adapter;
    private ItemTouchHelper touchHelper;

    public static void show(FragmentActivity activity) {
        new MusicQueueDialog().show(activity.getSupportFragmentManager(), "music_queue");
    }

    // 内容自带浅色底（bg_sheet_panel #F3F3F3）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicQueueBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 右缘防裁切：窗口宽强制 MATCH_PARENT
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        // 队列面板最大高度＝屏高 0.68（GZ PlayQueueSheet 口径）：布局后再钳高，内容短时不留白
        if (getContext() != null) {
            int maxH = Math.round(getContext().getResources().getDisplayMetrics().heightPixels * 0.68f);
            binding.recycler.post(() -> {
                if (binding == null) return;
                ViewGroup.LayoutParams lp = binding.recycler.getLayoutParams();
                if (binding.recycler.getHeight() > maxH && lp.height != maxH) {
                    lp.height = maxH;
                    binding.recycler.setLayoutParams(lp);
                }
            });
        }
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(adapter = new QueueAdapter());
        binding.btnClear.setOnClickListener(v -> {
            MusicController.get().clearQueue();
            dismissAllowingStateLoss();
        });
        // GZ SheetSubPanelHeader：‹ 与 ✕ 传的是同一个关闭回调——两个都是关面板，不是返回上一级
        binding.btnBack.setOnClickListener(v -> dismissAllowingStateLoss());
        binding.btnClose.setOnClickListener(v -> dismissAllowingStateLoss());
        touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0 || from >= tracks.size() || to >= tracks.size()) return false;
                MusicController.get().move(from, to);
                Collections.swap(tracks, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // GZ：长按整行任意位置触发拖动（detectDragGesturesAfterLongPress），无独立手柄
                return true;
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                // 被拖行底＝sheetListBg 白＋圆角 12（GZ QueueItem 拖动态）
                if (vh != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh.itemView.setBackgroundResource(R.drawable.bg_sheet_row_drag);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setBackground(null);
                refresh();
            }
        });
        touchHelper.attachToRecyclerView(binding.recycler);
        refresh();
    }

    private void refresh() {
        tracks.clear();
        tracks.addAll(MusicController.get().queue());
        // GZ 计数筹＝纯数字（PlayQueueSheetKt:724 String.valueOf(size)），无「首」
        binding.tvCount.setText(String.valueOf(tracks.size()));
        bindSourceRow();
        binding.badgeShuffle.setVisibility(
                MusicController.get().getMode() == MusicQueue.MODE_SHUFFLE ? View.VISIBLE : View.GONE);
        boolean empty = tracks.isEmpty();
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.tvHint.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.btnClear.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    /**
     * 来源行（GZ Q02）：GZ 是队列级 QueueSource；星辰 MusicQueue 不存队列级来源，
     * 开面板时按各 track.platform 反查站点名统计——全队同源才显示，混源/无源整行隐藏。
     * GZ 的 route 跳转星辰无对应物：整行不可点、无 ›（差异残留，见对照表）。
     */
    private void bindSourceRow() {
        String label = null;
        boolean uniform = !tracks.isEmpty();
        for (MusicTrack t : tracks) {
            String l = sourceLabel(t);
            if (l == null || (label != null && !label.equals(l))) {
                uniform = false;
                break;
            }
            label = l;
        }
        if (uniform && label != null) {
            binding.sourceName.setText(label);
            binding.sourceRow.setVisibility(View.VISIBLE);
        } else {
            binding.sourceRow.setVisibility(View.GONE);
        }
    }

    private String sourceLabel(MusicTrack t) {
        if (t == null || t.platform == null || t.platform.isEmpty()) return null;
        if ("device".equals(t.platform)) return getString(R.string.home_device);
        try {
            for (MusicSite site : MusicRepository.sites()) {
                if (t.platform.equals(site.platform)) return site.name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 加歌单（与播放页宿主 pickPlaylist 同链）：选单/新建 → MusicLibrary.addToPlaylist */
    private void pickPlaylist(MusicTrack track) {
        FragmentActivity host = getActivity();
        if (host == null || track == null) return;
        MusicLibrary.playlists(list -> {
            if (!isAdded()) return;
            List<String> names = new ArrayList<>();
            for (MusicPlaylist p : list) names.add(p.getName());
            names.add(getString(R.string.music_new_playlist));
            MusicOptionsDialog.show(host, getString(R.string.music_add_to_playlist), names, -1, idx -> {
                if (idx < list.size()) {
                    MusicLibrary.addToPlaylist(list.get(idx).getId(), track, added ->
                            Notify.show(added ? R.string.music_added : R.string.music_already_in));
                } else {
                    inputPlaylistName(name -> {
                        if (name == null || name.trim().isEmpty()) return;
                        MusicLibrary.createPlaylist(name.trim(), p ->
                                MusicLibrary.addToPlaylist(p.getId(), track, added ->
                                        Notify.show(added ? R.string.music_added : R.string.music_already_in)));
                    });
                }
            });
        });
    }

    private interface NameInput {
        void onResult(String name);
    }

    private void inputPlaylistName(NameInput cb) {
        if (getContext() == null) return;
        EditText et = new EditText(getContext());
        et.setHint(R.string.music_playlist_name_hint);
        et.setSingleLine(true);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.music_new_playlist)
                .setView(et)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onResult(et.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_queue, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MusicTrack t = tracks.get(position);
            boolean current = position == MusicController.get().index();
            h.itemView.setBackground(null);
            h.index.setText(String.format(java.util.Locale.US, "%02d", position + 1));
            h.index.setTextColor(current ? COLOR_CURRENT_INDEX : COLOR_LOW);
            h.title.setText(t.displayTitle());
            h.title.setTextColor(current ? COLOR_RED : COLOR_TEXT);
            String artist = t.displayArtist();
            h.artist.setText(artist == null || artist.isEmpty() ? getString(R.string.music_unknown_artist) : artist);
            h.artist.setTextColor(current ? COLOR_CURRENT_ARTIST : COLOR_LOW);
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            h.itemView.setOnClickListener(v -> {
                MusicController.get().playAt(position);
                refresh();
            });
            // 收藏态（异步查库回填图标＋染色：已收藏实心红、未收藏线框灰，GZ QueueRowActionButton）
            paintFav(h, false);
            MusicLibrary.isFavorite(t.id, fav -> {
                if (h.getBindingAdapterPosition() == position) paintFav(h, fav);
            });
            h.btnFav.setOnClickListener(v -> MusicLibrary.isFavorite(t.id, fav ->
                    MusicLibrary.setFavorite(t, !fav, () -> {
                        Notify.show(!fav ? R.string.music_favorited : R.string.music_unfavorited);
                        notifyDataSetChanged();
                    })));
            h.btnAdd.setOnClickListener(v -> pickPlaylist(t));
            h.btnRemove.setOnClickListener(v -> {
                MusicController.get().removeAt(position);
                refresh();
            });
        }

        private void paintFav(Holder h, boolean fav) {
            h.btnFav.setImageResource(fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
            h.btnFav.setImageTintList(ColorStateList.valueOf(fav ? COLOR_RED : COLOR_LOW));
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView index;
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final ImageView btnFav;
            final ImageView btnAdd;
            final ImageView btnRemove;

            Holder(View v) {
                super(v);
                index = v.findViewById(R.id.index);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                btnFav = v.findViewById(R.id.btnFav);
                btnAdd = v.findViewById(R.id.btnAdd);
                btnRemove = v.findViewById(R.id.btnRemove);
                btnAdd.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
                btnRemove.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
            }
        }
    }
}
