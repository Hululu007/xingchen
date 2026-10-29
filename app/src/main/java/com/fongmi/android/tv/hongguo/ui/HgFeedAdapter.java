package com.fongmi.android.tv.hongguo.ui;

import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

        /**
         * 长按触发（手指还按着）：x/width 为页根坐标系（带判定在 Activity，
         * 官方 F6 中间带口径）；快进/面板分工由 Activity 按朝向与带位定。
         */
        void onLongPress(int position, float x, int width);

        /** 长按后的抬手/取消：快进在此收尾（恢复面板倍速档）。 */
        void onLongPressRelease(int position);

        /**
         * 点头像/作者名进作者主页（官方 HGOpenProfilePageServiceImpl 口径：
         * enableClickAvatarOpenSeriesProfilePage＝非本人才能打开）。
         * 旧占位把点头像做成「关注作者」——语义错位，本方法整段替代。
         */
        void onAuthor(int position);

        void onAction(int position, Action action);

        void onSeriesBar(int position);

        /**
         * 点剧名行（带 › 的那行）进剧详情页（用户亲授语义：剧名与 @作者名
         * 是两个去处，剧名→详情页、作者名/头像→作者页）。只挂集流页的
         * 剧名行 hgEpTitleRow；底部剧条 hgSeriesBar 维持原口径（流页进剧、
         * 集流开选集）。
         */
        void onSeriesDetail(int position);

        /** 进这部剧（短片流）：点系列胶囊条/系列标题行或条目上左滑（账 §4/§5.4）；系列先弹半屏单、单季直进集流。 */
        void onEnterSeries(int position);

        /** 点「弹」发弹幕入口：开自家发弹幕编辑层（不是开关）。 */
        void onDanmakuPublish(int position);

        void onFullscreenWatch(int position);

        /**
         * 条目最近已知视频尺寸（宿主存档：播放器首帧尺寸／解链回包尺寸，
         * 按条目 videoId 查）：没存档返回 null。绑定锚「全屏观看」钮用——
         * 条目自带尺寸（取模型回包）没到手时靠它兜底，免得重绑把钮打回正中。
         */
        default int[] videoSizeFor(HgFeedItem item) {
            return null;
        }

        /**
         * 右下全屏方钮（选集条同行那只）：与「全屏观看」筹分开——筹是
         * 横屏剧进横屏页的入口，方钮在集流竖屏是清屏开关（用户亲点定版：
         * 按下界面全隐、只剩此钮且变淡，再按恢复）。旧版两钮同走
         * onFullscreenWatch，按方钮被扔进横屏页是串线。
         */
        void onFsButton(int position);

        /** 集流作者行关注钮：只在未关注时亮，点击＝关注作者（取关走作者页）。 */
        void onAuthorFollow(int position);

        /** 「从第1集开始看」引导筹：回第一集从头播（不走断点）。 */
        void onStartFromFirst(int position);

        void onRetry(int position);

        void onHolderBound(Holder holder, int position);
    }

    private final List<HgFeedItem> items;
    private final HgFeedSession session;
    private final Host host;
    private final Set<String> expandedDescKeys = new HashSet<>();
    private int currentPos = -1;
    /** 横屏独立页实例：竖屏件（右列配套的全屏观看钮/发弹幕入口）整套不亮，互动走横排控制条。 */
    private boolean landInstance;
    /** 集流模式（进剧后的竖屏剧集页）：底部行/作者行/信息摞按官方剧集页
     * 构成走，与刷剧流是两副面孔（材料与构成在各 bind 处按此旗分工）。 */
    private boolean seriesMode;

    public HgFeedAdapter(List<HgFeedItem> items, HgFeedSession session, Host host) {
        this.items = items;
        this.session = session;
        this.host = host;
    }

    public void setLandInstance(boolean landInstance) {
        this.landInstance = landInstance;
    }

    public void setSeriesMode(boolean seriesMode) {
        this.seriesMode = seriesMode;
    }

    public boolean isSeriesMode() {
        return seriesMode;
    }

    public boolean isLandInstance() {
        return landInstance;
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

                    @Override
                    public void onLongPress(MotionEvent e) {
                        int pos = holder.getBindingAdapterPosition();
                        if (pos == RecyclerView.NO_POSITION) return;
                        holder.longPressFired = true;
                        host.onLongPress(pos, e.getX(), holder.binding.hgPageRoot.getWidth());
                    }
                });
        binding.hgPageRoot.setOnTouchListener((v, ev) -> {
            boolean handled = detector.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                // 新触摸序列：上一回长按留下的抑制旗不许串到这次
                holder.suppressTap = false;
            } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && holder.longPressFired) {
                // 长按收尾：先报释放（停快进），再立抑制旗——本次抬手
                // 随后触发的单击必须被吃掉（长按序列不许再变单击）
                holder.longPressFired = false;
                holder.suppressTap = true;
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) host.onLongPressRelease(pos);
            }
            return handled;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        HgFeedItem item = items.get(position);
        ItemHongguoFeedBinding b = holder.binding;
        // 封面：走树里 ImgUtil 口径（有址加载、无址按标题素图）；播出首帧后由 Activity 撤掉
        // 缩放按官方封面层同构（official-scale-dig 定版）：竖条目 CENTER_CROP
        // 裁满、横条目 FIT_CENTER 整张显——旧版一律 centerCrop 把横封面裁满
        // 是错的。ImgUtil 的 vod 参正是这两态的开关（true=CENTER_CROP /
        // false=FIT_CENTER）；无址时退回默认口径（素图垫底不分朝向）。
        b.hgCover.setVisibility(View.VISIBLE);
        boolean landscapeCover = (item.isLandscape() || item.landscapeHint)
                && item.coverUrl != null && !item.coverUrl.isEmpty();
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, b.hgCover, !landscapeCover);
        b.hgError.setVisibility(View.GONE);
        b.hgPauseIcon.setVisibility(View.GONE);
        b.hgProgressFill.setScaleX(0f);
        b.hgProgressDot.setTranslationX(0f);
        // 条目款式（账 §3/§5）：挂多季/专辑关系的走系列剧款，其余作者款
        boolean seriesStyle = item.multiSeason || item.albumId != null || !item.siblings.isEmpty();
        // 作者行分工：集流＝官方 InfoPanel 作者行（头像 30dp＋名＋箭头＋
        // 关注钮）；流页＝纯文本「@名」行（官方流页实拍同此）。有真名才显，
        // 无名整行藏；系列剧款两边都不走作者行
        boolean hasAuthor = !seriesStyle && item.authorName != null && !item.authorName.isEmpty();
        if (seriesMode && hasAuthor) {
            b.hgAuthor.setVisibility(View.GONE);
            b.hgAuthorRow.setVisibility(View.VISIBLE);
            b.hgAuthorName.setText(item.authorName);
            if (item.authorAvatar != null && !item.authorAvatar.isEmpty()) {
                b.hgInfoAvatar.setVisibility(View.VISIBLE);
                ImgUtil.loadCircle("", item.authorAvatar, b.hgInfoAvatar);
            } else {
                b.hgInfoAvatar.setVisibility(View.GONE);
            }
            boolean followed = item.authorUid != null && HgStore.isAuthorFollowed(item.authorUid);
            b.hgFollowBtn.setVisibility(item.authorUid != null && !followed ? View.VISIBLE : View.GONE);
        } else {
            b.hgAuthorRow.setVisibility(View.GONE);
            b.hgAuthor.setVisibility(hasAuthor ? View.VISIBLE : View.GONE);
            if (hasAuthor) b.hgAuthor.setText("@" + item.authorName);
        }
        // 集流页右列不设头像盒（官方实拍 113558：集流右列只有收藏/评论/
        // 点赞三件；流页保留头像盒＋关注角标）
        b.hgAvatarBox.setVisibility(seriesMode ? View.GONE : View.VISIBLE);
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
        // 集流标题行＋通行标签行（非专辑款）：官方摞里标题/标签是常设位，
        // 此前只有专辑款有这两样、普通集流条目全缺
        if (seriesMode && !seriesStyle) {
            b.hgEpTitleRow.setVisibility(View.VISIBLE);
            b.hgEpTitle.setText(item.title == null ? "" : item.title);
            if (item.badge != null && !item.badge.isEmpty()) {
                b.hgEpBadge.setVisibility(View.VISIBLE);
                b.hgEpBadge.setText(item.badge);
            } else {
                b.hgEpBadge.setVisibility(View.GONE);
            }
            fillTags(b.hgTagsRowMain, item);
        } else {
            b.hgEpTitleRow.setVisibility(View.GONE);
            b.hgTagsRowMain.setVisibility(View.GONE);
        }
        // 流页剧名行＋标签行（官方实拍 113476 构成：剧名行在标签/简介之上）：
        // 正剧短片（有剧归属、无作者名）出此行；作者帖（有作者名）维持
        // @作者行形态（官方实拍 113482）。旧版流页无此行，正剧条目信息摞
        // 只剩简介一行（113579 真机点名：剧名不出）
        boolean feedTitle = !seriesMode && !seriesStyle && item.seriesId != null
                && item.title != null && !item.title.isEmpty()
                && (item.authorName == null || item.authorName.isEmpty());
        if (feedTitle) {
            b.hgFeedTitleRow.setVisibility(View.VISIBLE);
            b.hgFeedTitle.setText(item.title);
            fillTags(b.hgTagsRowMain, item);
        } else {
            b.hgFeedTitleRow.setVisibility(View.GONE);
        }
        bindHotRow(holder, item);
        // 次级信息行（官方 recommendReason）：数据一直在条目里、此前从未上屏
        if (item.recText != null && !item.recText.isEmpty()) {
            b.hgRecText.setVisibility(View.VISIBLE);
            b.hgRecText.setText(item.recText);
        } else {
            b.hgRecText.setVisibility(View.GONE);
        }
        // 作者声明：数据有才显，没有不编（账 §3.5）。集流模式与 seriesStyle
        // 解绑——官方实拍 113558 集流页（系列剧）左下就有声明行，旧条件
        // 「非系列款才显」把集流里的系列剧声明整行吞了
        if ((seriesMode || !seriesStyle) && item.statement != null && !item.statement.isEmpty()) {
            b.hgStatement.setVisibility(View.VISIBLE);
            b.hgStatement.setText("ⓘ 作者声明：" + item.statement);
        } else {
            b.hgStatement.setVisibility(View.GONE);
        }
        // 「从第1集开始看」引导筹（官方触发链定版）：集流里、本剧断点集
        // 数大于 1 才出——断点在第一集或没断点都不出
        if (seriesMode && item.episodeIndex > 0) {
            HgStore.Progress bp = HgStore.progressOf(item.seriesId);
            b.hgGuideChip.setVisibility(bp != null && bp.episodeIndex > 1 ? View.VISIBLE : View.GONE);
        } else {
            b.hgGuideChip.setVisibility(View.GONE);
        }
        applyCatalogMode(b);
        b.hgSeriesText.setText(seriesMode ? item.seriesCatalogText() : item.seriesBarText());
        // 全屏方钮：只集流竖屏出（用户亲点件：清屏开关——按下界面全隐、
        // 只剩此钮且变淡，再按恢复；与全屏观看筹的横屏跳转分开，见
        // Host.onFsButton）；回收复用先把透明度归位（清屏态的变淡不许串页）
        b.hgFsBtn.setVisibility(seriesMode && !landInstance ? View.VISIBLE : View.GONE);
        b.hgFsBtn.setAlpha(1f);
        // 分享件：官方集流页右列（ShortSeriesRightView 布局 cv5/cv6/cv7）
        // 里 SeriesShareView 在点赞下方、最底位，实拍 113560 在列；流页
        // 右列无分享（实拍 113482）。点击链与系统分享早已接好，此前只
        // 差显隐没开、布局里恒 gone
        b.hgActShare.setVisibility(seriesMode ? View.VISIBLE : View.GONE);
        // 横屏短片给全屏观看钮（账 §3.8）：取模型宽高定真朝向，未知先按 cell vertical 预判；
        // 横屏实例里此钮不存在（已在横屏页里），整套不亮
        b.hgFullscreenWatch.setVisibility(!landInstance && (item.isLandscape() || item.landscapeHint) ? View.VISIBLE : View.GONE);
        // 钮锚位与 Activity.anchorFullscreenWatch 同公式（贴视频下沿居中，
        // 官方实拍 113482 口径）。尺寸来源两级：条目自带（取模型回包）→
        // 宿主存档（首帧/解链尺寸）。旧版只有条目自带一级、缺尺寸时无条件
        // 归零——起播后任意一次重绑（短片富集回帖等）就把首帧锚位抹掉，
        // 钮永久停在页正中（113576 真机实证）；存档兜底后重绑自愈
        int fsW = item.videoWidth, fsH = item.videoHeight;
        if ((fsW <= 0 || fsH <= 0) && host != null) {
            int[] known = host.videoSizeFor(item);
            if (known != null && known.length >= 2) {
                fsW = known[0];
                fsH = known[1];
            }
        }
        if (fsW > fsH && fsH > 0) {
            final int vw = fsW, vh = fsH;
            b.getRoot().post(() -> {
                int pageW = b.getRoot().getWidth();
                if (pageW <= 0) return;
                float density = b.getRoot().getResources().getDisplayMetrics().density;
                float renderedH = (float) pageW * vh / vw;
                int btnH = b.hgFullscreenWatch.getHeight() > 0
                        ? b.hgFullscreenWatch.getHeight() : Math.round(40 * density);
                b.hgFullscreenWatch.setTranslationY(renderedH / 2f + 15 * density + btnH / 2f);
            });
        } else {
            b.hgFullscreenWatch.setTranslationY(0f);
        }
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
        // 头像「+」徽标＝作者关注态（与作者主页关注钮同读 HgStore 作者集，
        // 按作者 uid 记）：旧实现读的是按剧的追更集（isFollowing(seriesId)）
        //＋已关注变暗——语义与呈现都错位，本批整段换掉；条目没带 uid
        // （详情/搜索来源）时徽标整件不亮，不许拿剧 id 冒充作者 id
        boolean authorFollowed = item.authorUid != null && HgStore.isAuthorFollowed(item.authorUid);
        b.hgFollowBadge.setVisibility(item.authorUid == null ? View.GONE : View.VISIBLE);
        b.hgFollowBadge.setText(authorFollowed ? "✓" : "+");
        b.hgFollowBadge.setAlpha(1f);
        setCount(b.hgLikeCount, item.likeCount);
        // 评论数数据驱动：会话里有真总数（评论面板拉到过）才亮，否则不显示
        String cc = session.commentCountOf(item.seriesId);
        setCount(b.hgCommentCount, cc != null ? cc : item.commentCount);
        setCount(b.hgFavCount, item.favCount);
        bindReserveState(holder, item);
    }

    /** 预约卡：只在未上线剧（reservable）亮；官方 cu6 形态——封面＋标题＋
     * 上线文案副行＋48dp 品牌钮，钮文案/状态色对照 ks3.i（预约＝橘底白字、
     * 已预约＝透明白底灰字）。 */
    public void bindReserveState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        if (!item.reservable) {
            b.hgReserve.setVisibility(View.GONE);
            return;
        }
        boolean reserved = session.interactions.isReserved(item.seriesId);
        b.hgReserve.setVisibility(View.VISIBLE);
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, b.hgReserveCover, true);
        b.hgReserveTitle.setText(item.title == null ? "" : item.title);
        String due = item.dueStartText;
        b.hgReserveDue.setText(due != null && !due.isEmpty() ? due : "即将上线");
        b.hgReserveBtn.setText(reserved ? "已预约" : "预约");
        b.hgReserveBtn.setBackgroundResource(reserved
                ? R.drawable.bg_hg_reserve_btn_on : R.drawable.bg_hg_reserve_btn);
        b.hgReserveBtn.setTextColor(reserved ? 0x66FFFFFF : 0xFFFFFFFF);
    }

    /** 「弹」＝发弹幕入口（官方 A25：PublishDanmakuEntranceView）：它不是
     * 开关——开关在更多面板/抽屉，点击走 onDanmakuPublish 开编辑层。
     * 显隐分工：集流页常显、关态带斜杠（官方实拍 113558 证，与官方弹幕
     * 开关图标的三态画法同义）；流页维持开着才显（A25 旧口径、实拍未推翻）。 */
    public void bindDanmakuState(Holder holder) {
        ItemHongguoFeedBinding b = holder.binding;
        if (landInstance) {
            b.hgDanmakuBox.setVisibility(View.GONE);
            return;
        }
        boolean on = HgStore.isDanmakuOn();
        b.hgDanmakuBox.setVisibility(seriesMode || on ? View.VISIBLE : View.GONE);
        b.hgDanmakuSlash.setVisibility(seriesMode && !on ? View.VISIBLE : View.GONE);
    }

    /**
     * 底部行材料/几何按模式定死（同一布局两副面孔，bind 时切换）：
     * 集流＝官方 CatalogView 材料（cto/ctp 定版：条高 40dp、底 #1AFFFFFF
     * 圆角 8dp、无播放图标、文案起点 16dp、尾箭头图 20×10；行侧距 12dp/
     * 底距 8dp 照实拍 113558、进度线移到条上方）；流页＝全宽深底 strip
     * （官方流页实拍 113482：全宽、播放图标＋「›」、进度线贴条底沿）。
     */
    private void applyCatalogMode(ItemHongguoFeedBinding b) {
        float density = b.getRoot().getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams rowLp = (FrameLayout.LayoutParams) b.hgBottomRow.getLayoutParams();
        FrameLayout.LayoutParams progressLp = (FrameLayout.LayoutParams) b.hgProgressBox.getLayoutParams();
        LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) b.hgSeriesBar.getLayoutParams();
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) b.hgSeriesText.getLayoutParams();
        if (seriesMode) {
            rowLp.setMargins(Math.round(12 * density), 0, Math.round(12 * density), Math.round(8 * density));
            progressLp.bottomMargin = Math.round(52 * density);
            b.hgSeriesBar.setBackgroundResource(R.drawable.bg_hg_catalog_bar);
            barLp.height = Math.round(40 * density);
            b.hgSeriesBar.setMinimumHeight(0);
            b.hgSeriesPlayIcon.setVisibility(View.GONE);
            textLp.setMarginStart(Math.round(16 * density));
            b.hgSeriesArrow.setVisibility(View.VISIBLE);
            b.hgSeriesArrowTxt.setVisibility(View.GONE);
        } else {
            rowLp.setMargins(0, 0, 0, 0);
            progressLp.bottomMargin = 0;
            b.hgSeriesBar.setBackgroundResource(R.drawable.bg_hg_series_bar);
            barLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            b.hgSeriesBar.setMinimumHeight(Math.round(44 * density));
            b.hgSeriesPlayIcon.setVisibility(View.VISIBLE);
            textLp.setMarginStart(Math.round(8 * density));
            b.hgSeriesArrow.setVisibility(View.GONE);
            b.hgSeriesArrowTxt.setVisibility(View.VISIBLE);
        }
        b.hgBottomRow.setLayoutParams(rowLp);
        b.hgProgressBox.setLayoutParams(progressLp);
        b.hgSeriesBar.setLayoutParams(barLp);
        b.hgSeriesText.setLayoutParams(textLp);
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
        fillTags(b.hgTagsRow, item);
    }

    /** 标签筹填充（系列款行与普通款通行行共用）：最多 4 枚，数据空整行藏。 */
    private void fillTags(LinearLayout row, HgFeedItem item) {
        row.removeAllViews();
        float density = row.getResources().getDisplayMetrics().density;
        int shown = 0;
        for (String tag : item.tags) {
            if (shown >= 4) break;
            android.widget.TextView chip = new android.widget.TextView(row.getContext());
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
            row.addView(chip);
            shown++;
        }
        row.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
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
        b.hgPageRoot.setOnClickListener(v -> {
            if (holder.suppressTap) {
                holder.suppressTap = false;
                return;
            }
            host.onTap(holder.getBindingAdapterPosition());
        });
        b.hgActFav.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.FAV));
        b.hgActComment.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.COMMENT));
        b.hgActLike.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.LIKE));
        b.hgActShare.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.SHARE));
        // 头像与作者名同路：进作者主页（官方 enableClickAvatarOpenSeriesProfilePage
        // 语义）；旧占位把点头像当「关注作者」的 Action.FOLLOW 已整段摘除
        b.hgAvatarBox.setOnClickListener(v -> host.onAuthor(holder.getBindingAdapterPosition()));
        b.hgAuthor.setOnClickListener(v -> host.onAuthor(holder.getBindingAdapterPosition()));
        b.hgAuthorRow.setOnClickListener(v -> host.onAuthor(holder.getBindingAdapterPosition()));
        b.hgFollowBtn.setOnClickListener(v -> host.onAuthorFollow(holder.getBindingAdapterPosition()));
        b.hgEpTitleRow.setOnClickListener(v -> host.onSeriesDetail(holder.getBindingAdapterPosition()));
        b.hgFeedTitleRow.setOnClickListener(v -> host.onSeriesDetail(holder.getBindingAdapterPosition()));
        b.hgGuideChip.setOnClickListener(v -> host.onStartFromFirst(holder.getBindingAdapterPosition()));
        b.hgReserve.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.RESERVE));
        b.hgSeriesBar.setOnClickListener(v -> host.onSeriesBar(holder.getBindingAdapterPosition()));
        b.hgDanmakuBox.setOnClickListener(v -> host.onDanmakuPublish(holder.getBindingAdapterPosition()));
        b.hgFullscreenWatch.setOnClickListener(v -> host.onFullscreenWatch(holder.getBindingAdapterPosition()));
        b.hgFsBtn.setOnClickListener(v -> host.onFsButton(holder.getBindingAdapterPosition()));
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
        // 系列款标题行：流页点＝进剧，集流页点＝进剧详情页（与普通款剧名行同口径）
        b.hgSeriesTitleRow.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (seriesMode) host.onSeriesDetail(pos);
            else host.onEnterSeries(pos);
        });
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
        /** 本次触摸序列已触发长按（抬手时报释放并立抑制旗）。 */
        boolean longPressFired;
        /** 长按后的单击抑制：置位后吃掉紧随的一次页根单击并自动清。 */
        boolean suppressTap;

        Holder(ItemHongguoFeedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
