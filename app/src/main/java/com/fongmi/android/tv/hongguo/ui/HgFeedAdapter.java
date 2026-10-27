package com.fongmi.android.tv.hongguo.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ItemHongguoFeedBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 刷剧流适配器（ViewPager2 用）：一页一集，页 View 随 ViewPager2 回收复用。
 * 只负责绑数据与转发点击；播放器表面挂载、互动状态刷新由 Activity 经
 * {@link #bindInteractionState(Holder, HgFeedItem)} 与表面挂载口处理。
 */
public final class HgFeedAdapter extends RecyclerView.Adapter<HgFeedAdapter.Holder> {

    public enum Action {FAV, COMMENT, LIKE, SHARE, FOLLOW}

    public interface Host {
        void onTap(int position);

        void onAction(int position, Action action);

        void onSeriesBar(int position);

        void onDanmakuToggle(int position);

        void onFullscreenWatch(int position);

        void onRetry(int position);

        void onHolderBound(Holder holder, int position);
    }

    private final List<HgFeedItem> items;
    private final HgFeedSession session;
    private final Host host;
    private final Set<String> expandedDescKeys = new HashSet<>();
    private int currentPos = -1;

    public HgFeedAdapter(List<HgFeedItem> items, HgFeedSession session, Host host) {
        this.items = items;
        this.session = session;
        this.host = host;
    }

    public void setCurrentPos(int pos) {
        this.currentPos = pos;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemHongguoFeedBinding binding = ItemHongguoFeedBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        HgFeedItem item = items.get(position);
        ItemHongguoFeedBinding b = holder.binding;
        // 封面：走树里 ImgUtil 口径（有址加载、无址按标题素图）；播出首帧后由 Activity 撤掉
        b.hgCover.setVisibility(View.VISIBLE);
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, b.hgCover);
        b.hgError.setVisibility(View.GONE);
        b.hgPauseIcon.setVisibility(View.GONE);
        b.hgProgressFill.setScaleX(0f);
        // 作者：有真名才带 @；无名整行藏
        if (item.authorName != null && !item.authorName.isEmpty()) {
            b.hgAuthor.setVisibility(View.VISIBLE);
            b.hgAuthor.setText("@" + item.authorName);
        } else {
            b.hgAuthor.setVisibility(View.GONE);
        }
        // 头像：有头像址走圆形图；没有用作者/剧名首字圆牌，不编假图
        if (item.authorAvatar != null && !item.authorAvatar.isEmpty()) {
            b.hgAvatarInitial.setVisibility(View.GONE);
            b.hgAvatar.setVisibility(View.VISIBLE);
            ImgUtil.loadCircle("", item.authorAvatar, b.hgAvatar);
        } else {
            b.hgAvatar.setVisibility(View.GONE);
            b.hgAvatarInitial.setVisibility(View.VISIBLE);
            String src = item.authorName != null && !item.authorName.isEmpty() ? item.authorName
                    : (item.title == null ? "" : item.title);
            b.hgAvatarInitial.setText(src.isEmpty() ? "红" : src.substring(0, 1));
        }
        // 简介：空则整行藏；长文给展开
        String key = descKey(item);
        boolean expanded = expandedDescKeys.contains(key);
        if (item.desc == null || item.desc.isEmpty()) {
            b.hgDesc.setVisibility(View.GONE);
            b.hgDescMore.setVisibility(View.GONE);
        } else {
            b.hgDesc.setVisibility(View.VISIBLE);
            b.hgDesc.setText(item.desc);
            b.hgDesc.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
            b.hgDescMore.setVisibility(item.desc.length() > 40 ? View.VISIBLE : View.GONE);
            b.hgDescMore.setText(expanded ? "收起" : "展开");
        }
        b.hgSeriesText.setText(item.seriesBarText());
        // 横屏剧才给全屏观看钮（尺寸由取模型回包定，未知先不给，解链后刷新）
        b.hgFullscreenWatch.setVisibility(item.isLandscape() ? View.VISIBLE : View.GONE);
        bindInteractionState(holder, item);
        bindDanmakuState(holder);
        bindClicks(holder, position);
        if (position == currentPos) host.onHolderBound(holder, position);
    }

    /** 互动三件的即时状态（点赞/收藏/关注）：图标填充态＋计数（数据有才显）。 */
    public void bindInteractionState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        HgInteractions in = session.interactions;
        ImageView likeIcon = (ImageView) ((ViewGroup) b.hgActLike).getChildAt(0);
        ImageView favIcon = (ImageView) ((ViewGroup) b.hgActFav).getChildAt(0);
        likeIcon.setImageResource(in.isLiked(item.seriesId) ? R.drawable.ic_hg_heart_fill : R.drawable.ic_hg_heart);
        favIcon.setImageResource(in.isFavorite(item.seriesId) ? R.drawable.ic_hg_star_fill : R.drawable.ic_hg_star);
        boolean followed = in.isFollowing(item.seriesId);
        b.hgFollowBadge.setText(followed ? "✓" : "+");
        b.hgFollowBadge.setAlpha(followed ? 0.55f : 1f);
        setCount(b.hgLikeCount, item.likeCount);
        setCount(b.hgCommentCount, item.commentCount);
        setCount(b.hgFavCount, item.favCount);
    }

    /** 弹幕开关态：关时芯片压暗。弹幕内容本阶段不接（见 Activity 注）。 */
    public void bindDanmakuState(Holder holder) {
        holder.binding.hgDanmaku.setAlpha(HgStore.isDanmakuOn() ? 1f : 0.55f);
    }

    private void setCount(View view, String count) {
        if (count == null || count.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            ((android.widget.TextView) view).setText(count);
        }
    }

    private String descKey(HgFeedItem item) {
        return (item.seriesId == null ? "" : item.seriesId) + "#" + item.episodeIndex;
    }

    private void bindClicks(Holder holder, int position) {
        ItemHongguoFeedBinding b = holder.binding;
        b.hgPageRoot.setOnClickListener(v -> host.onTap(holder.getBindingAdapterPosition()));
        b.hgActFav.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.FAV));
        b.hgActComment.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.COMMENT));
        b.hgActLike.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.LIKE));
        b.hgActShare.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.SHARE));
        b.hgAvatarBox.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.FOLLOW));
        b.hgSeriesBar.setOnClickListener(v -> host.onSeriesBar(holder.getBindingAdapterPosition()));
        b.hgDanmaku.setOnClickListener(v -> host.onDanmakuToggle(holder.getBindingAdapterPosition()));
        b.hgFullscreenWatch.setOnClickListener(v -> host.onFullscreenWatch(holder.getBindingAdapterPosition()));
        b.hgError.setOnClickListener(v -> host.onRetry(holder.getBindingAdapterPosition()));
        View.OnClickListener descToggle = v -> {
            int p = holder.getBindingAdapterPosition();
            if (p < 0 || p >= items.size()) return;
            String key = descKey(items.get(p));
            if (!expandedDescKeys.remove(key)) expandedDescKeys.add(key);
            notifyItemChanged(p);
        };
        b.hgDesc.setOnClickListener(descToggle);
        b.hgDescMore.setOnClickListener(descToggle);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static final class Holder extends RecyclerView.ViewHolder {
        public final ItemHongguoFeedBinding binding;

        Holder(ItemHongguoFeedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
