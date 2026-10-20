package com.fongmi.android.tv.ui.dialog;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 播放队列面板（照 GZ PlayQueueSheet 浅色转写）：标题「播放列表」＋随机徽标（仅随机模式）＋计数筹＋清空（清空后关面板）、
 * 提示行、队列行（高 56dp、封面 36dp 圆角 8dp、歌名 13sp/歌手 11sp、时长或格式筹、当前行高亮＋播放指示）、
 * 行点按切歌、长按展开操作行（收藏/加歌单/移除，钮盒 30dp 图标 18dp）、拖动手柄排序、空态。
 * 颜色差异（用户口径）：GZ 当前行红字/红徽标改中性深色（#1C1C1E），结构尺寸照 GZ。
 * 队列无「来源」数据（MusicQueue 不存队列级来源），GZ 的来源行 Q02 本版不做（见报告缺口），不编造。
 */
public class MusicQueueDialog extends BaseBottomSheetDialog {

    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_CURRENT_ARTIST = 0xB81C1C1E; // GZ 当前行歌手强调色 0.72 alpha 的中性深色版

    private DialogMusicQueueBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private QueueAdapter adapter;
    private ItemTouchHelper touchHelper;
    private int expandedPos = -1;

    public static void show(FragmentActivity activity) {
        new MusicQueueDialog().show(activity.getSupportFragmentManager(), "music_queue");
    }

    // 内容自带浅色底（sheet_panel_white）：弹窗容器/窗口必须透明，
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
                if (expandedPos == from) expandedPos = to;
                else if (expandedPos == to) expandedPos = from;
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // 长按留给展开操作行（GZ Q06）；排序走行尾手柄拖动（GZ Q07 的星辰落法）
                return false;
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                refresh();
            }
        });
        touchHelper.attachToRecyclerView(binding.recycler);
        refresh();
    }

    private void refresh() {
        tracks.clear();
        tracks.addAll(MusicController.get().queue());
        if (expandedPos >= tracks.size()) expandedPos = -1;
        binding.tvCount.setText(tracks.size() + " " + getString(R.string.music_tracks));
        binding.badgeShuffle.setVisibility(
                MusicController.get().getMode() == MusicQueue.MODE_SHUFFLE ? View.VISIBLE : View.GONE);
        boolean empty = tracks.isEmpty();
        binding.recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.tvHint.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.btnClear.setVisibility(empty ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
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

    private static String durationText(MusicTrack t) {
        if (t.duration > 0) {
            long sec = t.duration / 1000;
            return String.format("%d:%02d", sec / 60, sec % 60);
        }
        return t.qualityTag == null ? "" : t.qualityTag;
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
            h.title.setText(t.displayTitle());
            h.title.setTextColor(COLOR_TEXT);
            h.title.getPaint().setFakeBoldText(current);
            h.artist.setText(t.displayArtist());
            h.artist.setTextColor(current ? COLOR_CURRENT_ARTIST : COLOR_LOW);
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            h.playing.setVisibility(current ? View.VISIBLE : View.GONE);
            h.playing.setImageResource(MusicController.get().isPlaying()
                    ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
            String dur = durationText(t);
            h.duration.setText(dur);
            h.duration.setVisibility(dur.isEmpty() ? View.GONE : View.VISIBLE);
            h.ops.setVisibility(position == expandedPos ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                MusicController.get().playAt(position);
                refresh();
            });
            h.itemView.setOnLongClickListener(v -> {
                expandedPos = expandedPos == position ? -1 : position;
                notifyDataSetChanged();
                return true;
            });
            h.handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
                    touchHelper.startDrag(h);
                }
                return false;
            });
            // 收藏态（异步查库回填图标）
            h.btnFav.setImageResource(R.drawable.ic_music_heart);
            MusicLibrary.isFavorite(t.id, fav -> {
                if (h.getBindingAdapterPosition() == position) {
                    h.btnFav.setImageResource(fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart);
                }
            });
            h.btnFav.setOnClickListener(v -> MusicLibrary.isFavorite(t.id, fav ->
                    MusicLibrary.setFavorite(t, !fav, () -> {
                        Notify.show(!fav ? R.string.music_favorited : R.string.music_unfavorited);
                        notifyDataSetChanged();
                    })));
            h.btnAdd.setOnClickListener(v -> pickPlaylist(t));
            h.btnRemove.setOnClickListener(v -> {
                MusicController.get().removeAt(position);
                if (expandedPos == position) expandedPos = -1;
                refresh();
            });
        }

        @Override
        public int getItemCount() {
            return tracks.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView artist;
            final ImageView playing;
            final TextView duration;
            final ImageView handle;
            final View ops;
            final ImageView btnFav;
            final ImageView btnAdd;
            final ImageView btnRemove;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                playing = v.findViewById(R.id.playing);
                duration = v.findViewById(R.id.duration);
                handle = v.findViewById(R.id.handle);
                ops = v.findViewById(R.id.ops);
                btnFav = v.findViewById(R.id.btnFav);
                btnAdd = v.findViewById(R.id.btnAdd);
                btnRemove = v.findViewById(R.id.btnRemove);
                playing.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
                handle.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
                btnFav.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
                btnAdd.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
                btnRemove.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
            }
        }
    }
}
