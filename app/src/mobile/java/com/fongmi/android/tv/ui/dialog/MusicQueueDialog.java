package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicQueueBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.ui.dialog.BaseBottomSheetDialog;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/** 播放队列弹窗：查看、删、调序、清空。 */
public class MusicQueueDialog extends BaseBottomSheetDialog {

    private DialogMusicQueueBinding binding;
    private final List<MusicTrack> tracks = new ArrayList<>();
    private QueueAdapter adapter;

    public static void show(FragmentActivity activity) {
        new MusicQueueDialog().show(activity.getSupportFragmentManager(), "music_queue");
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicQueueBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(adapter = new QueueAdapter());
        binding.btnClear.setOnClickListener(v -> {
            MusicController.get().clearQueue();
            refresh();
        });
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                MusicController.get().move(from, to);
                refresh();
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }
        }).attachToRecyclerView(binding.recycler);
        refresh();
    }

    private void refresh() {
        tracks.clear();
        tracks.addAll(MusicController.get().queue());
        binding.tvCount.setText(tracks.size() + " 首");
        adapter.notifyDataSetChanged();
        if (tracks.isEmpty()) dismissAllowingStateLoss();
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
            h.title.setTextColor(current ? 0xFFF2B33D : 0xFFFFFFFF);
            h.title.getPaint().setFakeBoldText(current);
            h.artist.setText(t.displayArtist());
            ImgUtil.loadRounded(t.displayTitle(), t.cover, h.cover);
            h.itemView.setBackgroundResource(current ? R.drawable.card_music_current : 0);
            h.itemView.setOnClickListener(v -> {
                MusicController.get().playAt(position);
                refresh();
            });
            h.btnRemove.setOnClickListener(v -> {
                MusicController.get().removeAt(position);
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
            final ImageView btnRemove;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                title = v.findViewById(R.id.title);
                artist = v.findViewById(R.id.artist);
                btnRemove = v.findViewById(R.id.btnRemove);
            }
        }
    }
}
