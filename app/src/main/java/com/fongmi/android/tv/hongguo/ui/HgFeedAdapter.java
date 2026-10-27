package com.fongmi.android.tv.hongguo.ui;

import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

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

    public enum Action {FAV, COMMENT, LIKE, SHARE, FOLLOW, RESERVE}

    public interface Host {
        void onTap(int position);

        void onAction(int position, Action action);

        void onSeriesBar(int position);

        /** 进这部剧（短片流）：点系列胶囊条/系列标题行或条目上左滑（账 §4/§5.4）；系列先弹半屏单、单季直进集流。 */
        void onEnterSeries(int position);

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
        Holder holder = new Holder(binding);
        // 左滑进剧（账 §4）：挂整页根、只认 fling 终点位移——阈值口径：
        // 水平位移 dx ≤ −80dp 且 |dx| ≥ 1.5×|dy| 才算左滑进剧；点按、
        // 上下滑、斜滑一律不消费，交给点击与竖滑分页，互不抢事件。
        GestureDetector detector = new GestureDetector(parent.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float density = holder.itemView.getResources().getDisplayMetrics().density;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (dx <= -80f * density && Math.abs(dx) >= 1.5f * Math.abs(dy)) {
                            int pos = holder.getBindingAdapterPosition();
                            if (pos != RecyclerView.NO_POSITION) host.onEnterSeries(pos);
                            return true;
                        }
                        return false;
                    }
                });
        binding.hgPageRoot.setOnTouchListener((v, ev) -> detector.onTouchEvent(ev));
        return holder;
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
        // 条目款式（账 §3/§5）：挂多季/专辑关系的走系列剧款，其余作者款
        boolean seriesStyle = item.multiSeason || item.albumId != null || !item.siblings.isEmpty();
        // 作者：有真名才带 @；无名整行藏；系列剧款不走作者行
        if (!seriesStyle && item.authorName != null && !item.authorName.isEmpty()) {
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
        if (seriesStyle || item.desc == null || item.desc.isEmpty()) {
            b.hgDesc.setVisibility(View.GONE);
            b.hgDescMore.setVisibility(View.GONE);
        } else {
            b.hgDesc.setVisibility(View.VISIBLE);
            b.hgDesc.setText(item.desc);
            b.hgDesc.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
            b.hgDescMore.setVisibility(item.desc.length() > 40 ? View.VISIBLE : View.GONE);
            b.hgDescMore.setText(expanded ? "收起" : "展开");
        }
        bindSeriesStyle(b, item, seriesStyle);
        bindHotRow(holder, item);
        // 作者声明：数据有才显，没有不编（账 §3.5，字段名未定 U4、数据槽已留）
        if (!seriesStyle && item.statement != null && !item.statement.isEmpty()) {
            b.hgStatement.setVisibility(View.VISIBLE);
            b.hgStatement.setText("ⓘ 作者声明：" + item.statement);
        } else {
            b.hgStatement.setVisibility(View.GONE);
        }
        b.hgSeriesText.setText(item.seriesBarText());
        // 横屏短片给全屏观看钮（账 §3.8）：取模型宽高定真朝向，未知先按 cell vertical 预判
        b.hgFullscreenWatch.setVisibility(item.isLandscape() || item.landscapeHint ? View.VISIBLE : View.GONE);
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
        // 评论数数据驱动：会话里有真总数（评论面板拉到过）才亮，否则不显示
        String cc = session.commentCountOf(item.seriesId);
        setCount(b.hgCommentCount, cc != null ? cc : item.commentCount);
        setCount(b.hgFavCount, item.favCount);
        bindReserveState(holder, item);
    }

    /** 预约片：只在未上线剧（reservable）亮；文案照官方串族（点击预约/已预约＋上线文案）。 */
    public void bindReserveState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        if (!item.reservable) {
            b.hgReserve.setVisibility(View.GONE);
            return;
        }
        boolean reserved = session.interactions.isReserved(item.seriesId);
        String due = item.dueStartText;
        b.hgReserve.setVisibility(View.VISIBLE);
        b.hgReserve.setText(reserved
                ? "已预约" + (due != null && !due.isEmpty() ? " · " + due : " · 即将上线")
                : "点击预约" + (due != null && !due.isEmpty() ? " · " + due : " · 即将上线"));
        b.hgReserve.setAlpha(reserved ? 0.75f : 1f);
    }

    /** 弹幕开关态：关时芯片压暗。弹幕内容本阶段不接（见 Activity 注）。 */
    public void bindDanmakuState(Holder holder) {
        holder.binding.hgDanmaku.setAlpha(HgStore.isDanmakuOn() ? 1f : 0.55f);
    }

    /** 系列剧款（账 §5.2）：胶囊文案只拼有实证的部分——季数来自 album_serial_count；在追人数无字段（U5）不拼。 */
    private void bindSeriesStyle(ItemHongguoFeedBinding b, HgFeedItem item, boolean seriesStyle) {
        if (!seriesStyle) {
            b.hgSeriesStyle.setVisibility(View.GONE);
            return;
        }
        b.hgSeriesStyle.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder("系列剧");
        if (item.albumSerialCount > 0) sb.append(" · 已上线").append(item.albumSerialCount).append("季");
        b.hgAlbumText.setText(sb.toString());
        if (item.badge != null && !item.badge.isEmpty()) {
            b.hgBadgeChip.setVisibility(View.VISIBLE);
            b.hgBadgeChip.setText(item.badge);
        } else {
            b.hgBadgeChip.setVisibility(View.GONE);
        }
        b.hgSeriesTitle.setText((item.title == null ? "" : item.title) + " ›");
        b.hgTagsRow.removeAllViews();
        float density = b.getRoot().getResources().getDisplayMetrics().density;
        int shown = 0;
        for (String tag : item.tags) {
            if (shown >= 4) break;
            android.widget.TextView chip = new android.widget.TextView(b.getRoot().getContext());
            chip.setText(tag);
            chip.setTextSize(12);
            chip.setTextColor(0xFFFFFFFF);
            chip.setBackgroundResource(R.drawable.bg_hg_chip);
            int hPad = Math.round(8 * density);
            int vPad = Math.round(3 * density);
            chip.setPadding(hPad, vPad, hPad, vPad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (shown > 0) lp.setMarginStart(Math.round(6 * density));
            chip.setLayoutParams(lp);
            b.hgTagsRow.addView(chip);
            shown++;
        }
        b.hgTagsRow.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /** 热评/主演说行（账 §3.4）：文案有才显；前缀按种类（2＝主演说，其余热评）。 */
    private void bindHotRow(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        if (item.hotComment == null || item.hotComment.isEmpty()) {
            b.hgHotRow.setVisibility(View.GONE);
            return;
        }
        b.hgHotRow.setVisibility(View.VISIBLE);
        String prefix = item.hotKind == 2 ? "主演说：" : "💧热评：";
        b.hgHotComment.setText(prefix + item.hotComment);
        boolean expanded = expandedDescKeys.contains(descKey(item) + "#hot");
        b.hgHotComment.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
        b.hgHotMore.setVisibility(item.hotComment.length() > 24 ? View.VISIBLE : View.GONE);
        b.hgHotMore.setText(expanded ? "收起" : "展开");
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
        b.hgReserve.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.RESERVE));
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
        b.hgAlbumBar.setOnClickListener(v -> host.onEnterSeries(holder.getBindingAdapterPosition()));
        b.hgSeriesTitleRow.setOnClickListener(v -> host.onEnterSeries(holder.getBindingAdapterPosition()));
        b.hgHotMore.setOnClickListener(v -> {
            int p = holder.getBindingAdapterPosition();
            if (p < 0 || p >= items.size()) return;
            String key = descKey(items.get(p)) + "#hot";
            if (!expandedDescKeys.remove(key)) expandedDescKeys.add(key);
            notifyItemChanged(p);
        });
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
