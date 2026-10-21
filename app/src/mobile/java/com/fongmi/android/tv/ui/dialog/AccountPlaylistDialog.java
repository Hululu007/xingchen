package com.fongmi.android.tv.ui.dialog;

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
import com.fongmi.android.tv.api.music.AccountPlaylistFetcher;
import com.fongmi.android.tv.databinding.DialogMusicOptionsBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.List;

/**
 * 登录账号导入：拉到的“我的歌单”列表（封面 50dp＋名 14sp＋“N首 · 创建者”11sp，共用歌曲行口径）。
 * 复用现有选项弹窗底布局（DialogMusicOptionsBinding：标题＋RecyclerView），不新增弹窗底布局；
 * 点某张回调宿主走 PlaylistImporter 现有导入链。
 */
public class AccountPlaylistDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onPick(AccountPlaylistFetcher.Sheet sheet);
    }

    private DialogMusicOptionsBinding binding;
    private String title;
    private List<AccountPlaylistFetcher.Sheet> sheets;
    private Listener listener;

    public static void show(FragmentActivity activity, String title, List<AccountPlaylistFetcher.Sheet> sheets, Listener listener) {
        if (activity == null || activity.isFinishing()) return;
        AccountPlaylistDialog d = new AccountPlaylistDialog();
        d.title = title;
        d.sheets = sheets;
        d.listener = listener;
        d.show(activity.getSupportFragmentManager(), "music_account_playlist");
    }

    @Override
    protected boolean transparent() {
        return true;
    }

    @NonNull
    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicOptionsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.tvTitle.setText(title == null ? "" : title);
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(new SheetAdapter());
    }

    private class SheetAdapter extends RecyclerView.Adapter<SheetAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_account_playlist, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AccountPlaylistFetcher.Sheet s = sheets.get(position);
            String name = s.name == null || s.name.isEmpty() ? s.id : s.name;
            h.name.setText(name);
            StringBuilder sub = new StringBuilder();
            if (s.songCount >= 0) sub.append(s.songCount).append("首");
            if (s.creator != null && !s.creator.isEmpty()) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(s.creator);
            }
            h.sub.setText(sub.toString());
            h.sub.setVisibility(sub.length() == 0 ? View.GONE : View.VISIBLE);
            if (s.coverUrl != null && !s.coverUrl.isEmpty()) {
                ImgUtil.loadRounded(name, s.coverUrl, h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPick(s);
                dismissAllowingStateLoss();
            });
        }

        @Override
        public int getItemCount() {
            return sheets == null ? 0 : sheets.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView name;
            final TextView sub;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.cover);
                name = v.findViewById(R.id.name);
                sub = v.findViewById(R.id.sub);
            }
        }
    }
}
