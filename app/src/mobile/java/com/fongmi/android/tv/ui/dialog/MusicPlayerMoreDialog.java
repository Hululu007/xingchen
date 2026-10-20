package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicMoreBinding;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页「更多」底部弹窗：动作行（定时关闭/倍速/加入歌单/下一首播放/加入播放队列/搜索歌词）
 * ＋信息行（来源/歌手/专辑，有值才显示）。点一行就回一行，无假项。
 */
public class MusicPlayerMoreDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onSleep();

        void onSpeed();

        void onAddToPlaylist();

        void onPlayNext();

        void onAppendQueue();

        void onSearchLyric();
    }

    private static final int ROW_SLEEP = 0;
    private static final int ROW_SPEED = 1;
    private static final int ROW_PLAYLIST = 2;
    private static final int ROW_NEXT = 3;
    private static final int ROW_APPEND = 4;
    private static final int ROW_LYRIC = 5;

    private DialogMusicMoreBinding binding;
    private MusicTrack track;
    private String sourceName;
    private String sleepName;
    private String speedName;
    private Listener listener;

    public static void show(FragmentActivity activity, MusicTrack track, String sourceName,
                           String sleepName, String speedName, Listener listener) {
        MusicPlayerMoreDialog d = new MusicPlayerMoreDialog();
        d.track = track;
        d.sourceName = sourceName;
        d.sleepName = sleepName;
        d.speedName = speedName;
        d.listener = listener;
        d.show(activity.getSupportFragmentManager(), "music_player_more");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicMoreBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        if (track != null) {
            binding.tvTitle.setText(track.displayTitle());
            binding.tvArtist.setText(track.displayArtist());
            if (TextUtils.isEmpty(track.cover)) {
                binding.ivCover.setImageDrawable(null);
            } else {
                ImgUtil.load(track.displayTitle(), track.cover, binding.ivCover);
            }
        }
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(new RowsAdapter(buildRows()));
        setInfo(binding.tvInfoSource, getString(R.string.music_info_source), sourceName);
        setInfo(binding.tvInfoArtist, getString(R.string.music_info_artist), track == null ? null : track.artist);
        setInfo(binding.tvInfoAlbum, getString(R.string.music_info_album), track == null ? null : track.album);
    }

    private void setInfo(TextView tv, String label, String value) {
        if (TextUtils.isEmpty(value)) {
            tv.setVisibility(View.GONE);
            return;
        }
        tv.setText(label + "：" + value);
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(ROW_SLEEP, R.drawable.ic_music_timer, R.string.music_sleep, sleepName));
        rows.add(new Row(ROW_SPEED, R.drawable.ic_music_sort, R.string.music_speed, speedName));
        rows.add(new Row(ROW_PLAYLIST, R.drawable.ic_music_plus, R.string.music_add_to_playlist, null));
        rows.add(new Row(ROW_NEXT, R.drawable.ic_music_next, R.string.music_play_next, null));
        rows.add(new Row(ROW_APPEND, R.drawable.ic_music_queue, R.string.music_add_queue, null));
        rows.add(new Row(ROW_LYRIC, R.drawable.ic_music_search, R.string.music_search_lyric, null));
        return rows;
    }

    private void pick(int id) {
        if (listener == null) return;
        switch (id) {
            case ROW_SLEEP:
                listener.onSleep();
                break;
            case ROW_SPEED:
                listener.onSpeed();
                break;
            case ROW_PLAYLIST:
                listener.onAddToPlaylist();
                break;
            case ROW_NEXT:
                listener.onPlayNext();
                break;
            case ROW_APPEND:
                listener.onAppendQueue();
                break;
            case ROW_LYRIC:
                listener.onSearchLyric();
                break;
        }
        dismissAllowingStateLoss();
    }

    private static class Row {
        final int id;
        final int icon;
        final int label;
        final String value;

        Row(int id, int icon, int label, String value) {
            this.id = id;
            this.icon = icon;
            this.label = label;
            this.value = value;
        }
    }

    private class RowsAdapter extends RecyclerView.Adapter<RowsAdapter.Holder> {
        private final List<Row> rows;

        RowsAdapter(List<Row> rows) {
            this.rows = rows;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_more_row, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row r = rows.get(position);
            h.icon.setImageResource(r.icon);
            h.label.setText(r.label);
            if (r.value == null || r.value.isEmpty()) {
                h.value.setVisibility(View.GONE);
            } else {
                h.value.setVisibility(View.VISIBLE);
                h.value.setText(r.value);
            }
            h.itemView.setOnClickListener(v -> pick(r.id));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView value;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                label = v.findViewById(R.id.label);
                value = v.findViewById(R.id.value);
            }
        }
    }
}
