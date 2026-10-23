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

    // 回调按令牌登记在进程内表里：令牌随 arguments 走，系统重建弹窗后凭令牌取回原回调；
    // 弹窗真正销毁（非配置变化）时摘除防泄漏。
    private static final java.util.Map<String, Listener> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    public static void show(FragmentActivity activity, String title, List<AccountPlaylistFetcher.Sheet> sheets, Listener listener) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || activity.getSupportFragmentManager().isStateSaved()) return;
        AccountPlaylistDialog d = new AccountPlaylistDialog();
        d.title = title;
        d.sheets = sheets;
        d.listener = listener;
        // 标题／歌单走 arguments（Sheet 非 Parcelable，按字段拆平行数组存）：
        // 系统重建弹窗时实例字段会丢，arguments 能原样恢复（回调无法序列化，仍走字段）。
        android.os.Bundle args = new android.os.Bundle();
        args.putString("title", title);
        if (sheets != null) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            java.util.ArrayList<String> covers = new java.util.ArrayList<>();
            java.util.ArrayList<String> descs = new java.util.ArrayList<>();
            java.util.ArrayList<String> creators = new java.util.ArrayList<>();
            long[] counts = new long[sheets.size()];
            for (int i = 0; i < sheets.size(); i++) {
                AccountPlaylistFetcher.Sheet s = sheets.get(i);
                ids.add(s.id);
                names.add(s.name);
                covers.add(s.coverUrl);
                descs.add(s.description);
                creators.add(s.creator);
                counts[i] = s.songCount;
            }
            args.putStringArrayList("sheet_ids", ids);
            args.putStringArrayList("sheet_names", names);
            args.putStringArrayList("sheet_covers", covers);
            args.putStringArrayList("sheet_descs", descs);
            args.putStringArrayList("sheet_creators", creators);
            args.putLongArray("sheet_counts", counts);
        }
        String token = null;
        if (listener != null) {
            token = "ap_" + SEQ.incrementAndGet();
            PENDING.put(token, listener);
            args.putString("cb_token", token);
        }
        d.setArguments(args);
        try {
            d.show(activity.getSupportFragmentManager(), "music_account_playlist");
        } catch (Throwable t) {
            // show 没成：弹窗不会走 onDestroy 摘令牌，必须在这里摘，否则令牌只进不出
            if (token != null) PENDING.remove(token);
            throw t;
        }
    }

    private Listener resolveListener() {
        if (listener != null) return listener;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token == null ? null : PENDING.get(token);
    }

    // 失效自关判定：字段已丢（系统重建的新实例）＋arguments 里有令牌（原本登记过回调）
    // ＋凭令牌回表取不到。三条全中只可能是进程死亡把静态表清空，原回调已不可恢复。
    private boolean callbackLost() {
        if (listener != null) return false;
        android.os.Bundle args = getArguments();
        String token = args == null ? null : args.getString("cb_token");
        return token != null && PENDING.get(token) == null;
    }

    @Override
    public void onStart() {
        super.onStart();
        // 显示后自检：不可恢复就立即自关，不留能看不能点的僵尸弹窗；用户从活页面重开即正常。
        if (callbackLost()) dismissAllowingStateLoss();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (getActivity() == null || !getActivity().isChangingConfigurations()) {
            android.os.Bundle args = getArguments();
            String token = args == null ? null : args.getString("cb_token");
            if (token != null) PENDING.remove(token);
        }
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
        android.os.Bundle args = getArguments();
        if (args != null) {
            if (args.containsKey("title")) title = args.getString("title");
            if (sheets == null && args.containsKey("sheet_ids")) {
                java.util.ArrayList<String> ids = args.getStringArrayList("sheet_ids");
                java.util.ArrayList<String> names = args.getStringArrayList("sheet_names");
                java.util.ArrayList<String> covers = args.getStringArrayList("sheet_covers");
                java.util.ArrayList<String> descs = args.getStringArrayList("sheet_descs");
                java.util.ArrayList<String> creators = args.getStringArrayList("sheet_creators");
                long[] counts = args.getLongArray("sheet_counts");
                sheets = new java.util.ArrayList<>();
                for (int i = 0; ids != null && i < ids.size(); i++) {
                    AccountPlaylistFetcher.Sheet s = new AccountPlaylistFetcher.Sheet();
                    s.id = ids.get(i);
                    if (names != null && i < names.size()) s.name = names.get(i);
                    if (covers != null && i < covers.size()) s.coverUrl = covers.get(i);
                    if (descs != null && i < descs.size()) s.description = descs.get(i);
                    if (creators != null && i < creators.size()) s.creator = creators.get(i);
                    if (counts != null && i < counts.length) s.songCount = counts[i];
                    sheets.add(s);
                }
            }
        }
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
            // 无封面地址也走 ImgUtil：内部按素图口径给 CoverTextDrawable 并清掉上一行的在途请求，
            // 不再手动置 null（既不合无封面口径，回收行还会被迟到图串图）。
            ImgUtil.loadRounded(name, s.coverUrl, h.cover);
            h.itemView.setOnClickListener(v -> {
                Listener pick = resolveListener();
                if (pick != null) pick.onPick(s);
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
