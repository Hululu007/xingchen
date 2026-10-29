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
import com.fongmi.android.tv.hongguo.model.HgItemKind;
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

        /**
         * 流页头像关注徽标（官方 PugcSubscribeView 覆钮）：与头像盒的
         * onAuthor 分开——点徽标只发关注、不进作者页；只关关注（官方
         * 此件无取关），取关走作者页。与集流作者行同口同状态源。
         */
        void onAuthorBadgeFollow(int position);

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
    /** 清屏态（与 Activity 的 cleanMode 同步，进出清屏时经 setCleanMode
     * 推过来）：清屏期间条目一旦重绑，绑完按清屏隐显集合复位，不许把
     * 方钮亮度/分享/全屏钮/弹幕框漏回屏上（扫查 H3）。 */
    private boolean cleanMode;

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

    public void setCleanMode(boolean cleanMode) {
        this.cleanMode = cleanMode;
    }

    public boolean isCleanMode() {
        return cleanMode;
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
        if (landInstance) {
            // 横屏实例的竖屏件抑制必须由 bind 自己兜底：XML 缺省全显，
            // 此前只靠 Activity.setChromeHidden 对 currentHolder() 一次性
            // 置 GONE——数据到达/富集重绑新建的 holder 带着缺省显隐上屏、
            // 且 settle 的隐藏调用可能打在空/错位 holder 上，竖屏右列/
            // 信息摞/选集条便与横排控制条同屏叠压（2026-10-29 他录屏
            // 《急诊摸鱼王》实证，账 video-issues-20261029.md 第二段①）。
            // 剧条/弹幕筹/方钮/全屏筹各自已有 landInstance 闸，不在此列。
            b.hgRightRail.setVisibility(View.GONE);
            b.hgInfo.setVisibility(View.GONE);
            b.hgBottomRow.setVisibility(View.GONE);
        }
        bindCover(b, item);
        b.hgError.setVisibility(View.GONE);
        b.hgPauseIcon.setVisibility(View.GONE);
        b.hgProgressFill.setScaleX(0f);
        b.hgProgressDot.setTranslationX(0f);
        // 条目款式（账 §3/§5）：挂多季/专辑关系的走系列剧款，其余作者款。
        // 系列款是正剧条目的专辑属性形态（普查卡 8 第一义）——只给
        // SERIES 型：UGC 短片的 albumId 是所属合集、不是它自己的系列
        // 身份，套系列款就是批一要拆的拼装推断（账 hongguo-batch1）
        boolean seriesStyle = item.kind == HgItemKind.SERIES
                && (item.multiSeason || item.albumId != null || !item.siblings.isEmpty());
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
        // 点赞三件）；流页头像盒只给带作者头像的条目（作者帖/UGC）——
        // 官方流页实拍 113476/113592/113598 正剧条目右列直接从星起、
        // 没有头像位，旧版给正剧也摆首字圆牌是自创件（复核账
        // feed-infostack-reaudit-20261029）
        boolean hasAvatarImg = item.authorAvatar != null && !item.authorAvatar.isEmpty();
        b.hgAvatarBox.setVisibility(seriesMode || !hasAvatarImg ? View.GONE : View.VISIBLE);
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
        // 简介：空则整行藏；长文给展开。流页形态照官方实拍
        // 113476/113592 定版：正文是「第N集｜剧简介」——集号取条目
        // 的展示集序 pushIndex（被推集集序，解析/落定两路已带；纯
        // 展示字段，不碰 episodeIndex 的断点语义），集流页不拼
        // （其形态未在实拍里核到、不动）。展开阈值旧版 40 字与单行
        // 容量（约 20 字）脱节：21–40 字简介被省略号截断却没展开钮；
        // 改按拼串后长度 20 字判（与单行容量同口径，复核账
        // feed-infostack-reaudit-20261029）
        String key = descKey(item);
        boolean expanded = expandedDescKeys.contains(key);
        if (seriesStyle || item.desc == null || item.desc.isEmpty()) {
            b.hgDesc.setVisibility(View.GONE);
            b.hgDescMore.setVisibility(View.GONE);
        } else {
            String descText = item.desc;
            if (!seriesMode && item.pushIndex > 0 && !hasEpPrefix(descText)) {
                descText = "第" + item.pushIndex + "集｜" + descText;
            }
            b.hgDesc.setVisibility(View.VISIBLE);
            b.hgDesc.setText(descText);
            b.hgDesc.setMaxLines(expanded ? Integer.MAX_VALUE : 1);
            b.hgDescMore.setVisibility(descText.length() > 20 ? View.VISIBLE : View.GONE);
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
        bindRankStrip(b, item);
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
            b.hgStatement.setText("作者声明：" + item.statement);
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
        b.hgSeriesText.setText(seriesMode ? item.seriesCatalogText() : item.feedBarText());
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
        // 「弹」筹纵位（仅集流竖屏）：官方锚在剧名行上方；咱家摞高随行数
        // 变，写死 158dp 会在摞高时压住摞内行（旧病，本批收口一并挪）——
        // 布局后按摞实高把筹底钉在摞顶之上 6dp；发现流保持布局原值不动
        if (seriesMode && !landInstance) {
            b.getRoot().post(() -> {
                int infoH = b.hgInfo.getHeight();
                if (infoH <= 0) return;
                float density = b.getRoot().getResources().getDisplayMetrics().density;
                FrameLayout.LayoutParams chipLp =
                        (FrameLayout.LayoutParams) b.hgDanmakuBox.getLayoutParams();
                int want = Math.round(110 * density) + infoH;
                if (chipLp.bottomMargin != want) {
                    chipLp.bottomMargin = want;
                    b.hgDanmakuBox.setLayoutParams(chipLp);
                }
            });
        }
        bindClicks(holder, position);
        if (cleanMode) applyCleanState(holder);
        if (position == currentPos) host.onHolderBound(holder, position);
    }

    /**
     * 条目铺底海报的唯一渲染口（刷剧流、集流、横屏实例共用同一适配器）：
     * 无论内容在服务端被标成竖还是横、也无论视频尺寸回填前后，都只用
     * FIT_CENTER 按图片自身比例满宽/满高居中，黑边落在页根黑底上。
     * 不许再按条目朝向切 CENTER_CROP——ImgUtil 的 vod=true 会同时切
     * ImageView 与 Glide 变换，竖条目会把海报裁成满屏（113624 真机点名），
     * 而 XML 的 fitCenter 对已加载图片并不生效。无址素图由 ImgUtil 的
     * FIT_CENTER 分支绘制后会把 View 置 GONE，这里恢复绑定时显性，避免
     * 无封面条目只剩黑页；有址加载失败仍按 ImgUtil 定案保持透明。
     */
    private static void bindCover(ItemHongguoFeedBinding b, HgFeedItem item) {
        String title = item.title == null ? "" : item.title;
        String url = item.coverUrl;
        b.hgCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.hgCover.setVisibility(View.VISIBLE);
        ImgUtil.load(title, url, b.hgCover, false);
        if (url == null || url.isEmpty()) b.hgCover.setVisibility(View.VISIBLE);
    }

    /**
     * 清屏态重绑复位：隐显集合与 Activity.enterCleanMode 对当前 holder
     * 置的那套逐件一致（右列/信息摞/弹幕框/全屏观看钮/进度线全隐、选集
     * 条 INVISIBLE 保位、方钮独留且变淡 0.4 防烧屏）。清屏只存在于集流
     * 竖屏，模式不对不动——普通模式绑定零影响。
     */
    private void applyCleanState(Holder holder) {
        if (!seriesMode || landInstance) return;
        ItemHongguoFeedBinding b = holder.binding;
        b.hgRightRail.setVisibility(View.GONE);
        b.hgInfo.setVisibility(View.GONE);
        b.hgDramaBar.setVisibility(View.GONE);
        b.hgSeriesBar.setVisibility(View.INVISIBLE);
        b.hgDanmakuBox.setVisibility(View.GONE);
        b.hgFullscreenWatch.setVisibility(View.GONE);
        b.hgProgressBox.setVisibility(View.GONE);
        b.hgFsBtn.setVisibility(View.VISIBLE);
        b.hgFsBtn.setAlpha(0.4f);
    }

    /** 互动三件的即时状态（点赞/收藏/关注）：图标填充态＋计数（数据有才显）。 */
    public void bindInteractionState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        HgInteractions in = session.interactions;
        ImageView likeIcon = (ImageView) ((ViewGroup) b.hgActLike).getChildAt(0);
        ImageView favIcon = (ImageView) ((ViewGroup) b.hgActFav).getChildAt(0);
        likeIcon.setImageResource(in.isLiked(item.seriesId) ? R.drawable.ic_hg_heart_fill : R.drawable.ic_hg_heart);
        favIcon.setImageResource(in.isFavorite(item.seriesId) ? R.drawable.ic_hg_star_fill : R.drawable.ic_hg_star);
        // 右列顶部动作按类型分家（普查专题 B）：星钮是正剧的追剧架动作
        // （对象是剧）；UGC 短片顶部是作者头像＋关注徽标（对象是人，在
        // 头像盒上）、合集条目的身份是 album——这两型都不许再亮星钮、
        // 不许走追剧架口子（批一拆型点，账 hongguo-batch1-item-types）
        boolean shelfAction = item.kind == HgItemKind.SERIES;
        b.hgActFav.setVisibility(shelfAction ? View.VISIBLE : View.GONE);
        // 头像「+」徽标＝作者关注钮（官方 PugcSubscribeView 口径，账
        // hongguo-ugc-follow-dig-20261030 §2.2/§5.3）：条目带 relation 时
        // 先按 {2,4}=已关注校准 HgStore 镜像（与合集页回写镜像同范式、
        // 镜像已同值不重复落盘），再读镜像定显隐——已关注整钮消失
        // （不是变对勾：旧版对勾图 ic_hg_follow_check 与官方不符，本批
        // 摘除其使用）、本人不亮、relation 明确 can_follow=false 不亮；
        // relation 缺失（-1）时纯读镜像（旧行为）。图标恒加号。
        if (item.authorUid != null && item.authorRelationType >= 0) {
            boolean relFollowed = item.authorRelationType == 2 || item.authorRelationType == 4;
            if (HgStore.isAuthorFollowed(item.authorUid) != relFollowed) {
                HgStore.setAuthorFollowed(item.authorUid, relFollowed);
            }
        }
        boolean authorFollowed = item.authorUid != null && HgStore.isAuthorFollowed(item.authorUid);
        boolean badgeVisible = HgFeedItem.followBadgeVisible(item.authorUid,
                HgStore.profileUid(), authorFollowed,
                item.authorRelationType, item.authorCanFollow);
        b.hgFollowBadge.setVisibility(badgeVisible ? View.VISIBLE : View.GONE);
        b.hgFollowBadge.setImageResource(R.drawable.ic_hg_follow_plus);
        b.hgFollowBadge.setAlpha(1f);
        setCount(b.hgLikeCount, item.likeCount);
        // 评论数数据驱动：会话里有真总数（评论面板拉到过）才亮，否则不显示
        String cc = session.commentCountOf(item.seriesId);
        setCount(b.hgCommentCount, cc != null ? cc : item.commentCount);
        // 星数（followed_cnt）同星钮口径：只有正剧型才显——UGC/合集的
        // favCount 是作者/合集维度的数，挂在追剧架星位就是串位
        setCount(b.hgFavCount, shelfAction ? item.favCount : null);
        bindReserveState(holder, item);
        bindDramaBarState(holder, item);
    }

    /** 预约卡：只在发现流给未上线剧（reservable）亮；官方 cu6 形态——
     * 封面＋标题＋上线文案副行＋48dp 品牌钮，钮文案/状态色对照 ks3.i
     * （预约＝橘底白字、已预约＝透明白底灰字）。集流页不走本卡：预约
     * 与追更已收进剧条同槽药丸（bindDramaBarState），摞内再摆大卡就是
     * 他点名的底部叠压。 */
    public void bindReserveState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        // 预约面是正剧未上线剧的形态（普查卡 13）：非正剧型不亮
        if (seriesMode || item.kind != HgItemKind.SERIES || !item.reservable) {
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

    /**
     * 剧条（集流专款，官方单剧播放页实拍 113630 定版排布）：信息摞之下、
     * 选集条之上的一整行——小封面＋剧名＋副行＋右端品牌药丸。药丸同槽
     * 互斥：未上线剧＝预约（副行＝上线文案）、已上线剧＝追更（副行＝
     * 更新至N集）；显隐按他实看官方定证——正剧条目就显，不按更新中/
     * 已完结过滤（官方包显隐链同向：挂专辑条目＋实验开关、无完结条件，
     * 核查账 official-follow-where §4）。两态色对走 ks3（未＝橘底白字、
     * 已＝透明白底灰字），药丸点击走既有 KIND_FOLLOW／预约管道（本地
     * 镜像＋服务端同步、失败回滚在互动层）。发现流恒藏（流页追剧是
     * 右列星钮本体、无药丸）；横屏实例恒藏（自有 chrome）。
     * 旧版同位是摞内 54dp 追更/预约大卡，卡底距选集条只 2dp、进度线
     * 横穿卡面、剧名集数重复——本批整卡废除（账 series-play-layout）。
     */
    public void bindDramaBarState(Holder holder, HgFeedItem item) {
        ItemHongguoFeedBinding b = holder.binding;
        if (!seriesMode || landInstance || item.seriesId == null || item.seriesId.isEmpty()
                || item.kind != HgItemKind.SERIES) {
            b.hgDramaBar.setVisibility(View.GONE);
            return;
        }
        b.hgDramaBar.setVisibility(View.VISIBLE);
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, b.hgDramaCover, true);
        b.hgDramaTitle.setText(item.title == null ? "" : item.title);
        String sub;
        boolean acted;
        if (item.reservable) {
            String due = item.dueStartText;
            sub = due != null && !due.isEmpty() ? due : "即将上线";
            acted = session.interactions.isReserved(item.seriesId);
            b.hgDramaAct.setText(acted ? "已预约" : "预约");
        } else {
            sub = barSubText(item);
            acted = session.interactions.isFollowing(item.seriesId);
            b.hgDramaAct.setText(acted ? "已追更" : "追更");
        }
        b.hgDramaSub.setText(sub);
        b.hgDramaSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        b.hgDramaAct.setBackgroundResource(acted
                ? R.drawable.bg_hg_follow_pill_on : R.drawable.bg_hg_follow_pill);
        b.hgDramaAct.setTextColor(acted ? 0x66FFFFFF : 0xFFFFFFFF);
    }

    /** 剧条副行（追更态）：只用条目真数据——有集数一律「更新至N集」
     * （官方 113630 定版样本：已完结 77 集剧同位文案仍是更新至77集，
     * 完结态不在这行表达）；没集数退类目，都没有就空行隐藏，不编。 */
    private static String barSubText(HgFeedItem item) {
        if (item.episodeCount > 0) return "更新至" + item.episodeCount + "集";
        return item.category == null ? "" : item.category;
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
     * 注意边距的坑：布局 XML 用 layout_marginStart/End 写边距，
     * setMargins() 只写左右不清 Start/End，流页清零必须连 Start/End 一起
     * 清——旧版只 setMargins(0,0,0,0)，12dp 侧距残留、剧条悬在中间两侧
     * 留空（1.4.59 真机图 113593 像素实证、他点名）。
     */
    private void applyCatalogMode(ItemHongguoFeedBinding b) {
        float density = b.getRoot().getResources().getDisplayMetrics().density;
        // 信息摞下沿：集流给剧条让位——剧条带 52..96、摞底抬到 104 留
        // 8dp 气口；发现流无剧条，维持布局原值 50（只动 bottomMargin
        // 字段，不走 setMargins，免踩下方 Start/End 残留的坑）
        FrameLayout.LayoutParams infoLp = (FrameLayout.LayoutParams) b.hgInfo.getLayoutParams();
        int infoBottom = seriesMode ? 104 : 50;
        if (infoLp.bottomMargin != Math.round(infoBottom * density)) {
            infoLp.bottomMargin = Math.round(infoBottom * density);
            b.hgInfo.setLayoutParams(infoLp);
        }
        FrameLayout.LayoutParams rowLp = (FrameLayout.LayoutParams) b.hgBottomRow.getLayoutParams();
        FrameLayout.LayoutParams progressLp = (FrameLayout.LayoutParams) b.hgProgressBox.getLayoutParams();
        LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams) b.hgSeriesBar.getLayoutParams();
        LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) b.hgSeriesText.getLayoutParams();
        if (seriesMode) {
            rowLp.setMargins(Math.round(12 * density), 0, Math.round(12 * density), Math.round(8 * density));
            rowLp.setMarginStart(Math.round(12 * density));
            rowLp.setMarginEnd(Math.round(12 * density));
            // 进度线落选集条（顶 48）与剧条（底 52）之间的 4dp 气口：
            // 线盒 6dp 底距 48 → 可见 2dp 线在 50..52，正贴剧条底沿之下。
            // 旧值 52 把线抬进剧条带 54..56——咱家封面仅 44dp、文案列
            // 居中后副行字形下沿逼近条底，线横穿「更新至N集」与封面下沿
            // （2026-10-29 静帧 113635 实证）；官方 113630 的线同在剧条
            // 底沿之下，只是官方封面更高、文案列不下探到线位
            progressLp.bottomMargin = Math.round(48 * density);
            b.hgSeriesBar.setBackgroundResource(R.drawable.bg_hg_catalog_bar);
            barLp.height = Math.round(40 * density);
            b.hgSeriesBar.setMinimumHeight(0);
            b.hgSeriesPlayIcon.setVisibility(View.GONE);
            textLp.setMarginStart(Math.round(16 * density));
            b.hgSeriesText.setTextSize(14);
            b.hgSeriesText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.hgSeriesArrow.setVisibility(View.VISIBLE);
            b.hgSeriesArrowTxt.setVisibility(View.GONE);
        } else {
            // 流页剧条照官方流页实拍（113482/113476）重对：左标＝白圆角
            // 方块＋深三角的视频标 20dp（左边距 14dp）、文案 15sp 常规
            // 字重（官方非粗体）、尾箭头细「›」；旧版裸三角 14dp＋14sp
            // 粗体＋集流文案拼法是整条借了集流的面孔
            rowLp.setMargins(0, 0, 0, 0);
            rowLp.setMarginStart(0);
            rowLp.setMarginEnd(0);
            progressLp.bottomMargin = 0;
            b.hgSeriesBar.setBackgroundResource(R.drawable.bg_hg_series_bar);
            barLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            b.hgSeriesBar.setMinimumHeight(Math.round(44 * density));
            b.hgSeriesPlayIcon.setVisibility(View.VISIBLE);
            b.hgSeriesPlayIcon.setImageResource(R.drawable.ic_hg_bar_play);
            LinearLayout.LayoutParams iconLp = (LinearLayout.LayoutParams) b.hgSeriesPlayIcon.getLayoutParams();
            iconLp.width = Math.round(20 * density);
            iconLp.height = Math.round(20 * density);
            iconLp.setMarginStart(Math.round(14 * density));
            b.hgSeriesPlayIcon.setLayoutParams(iconLp);
            textLp.setMarginStart(Math.round(8 * density));
            b.hgSeriesText.setTextSize(15);
            b.hgSeriesText.setTypeface(android.graphics.Typeface.DEFAULT);
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

    /**
     * 榜单徽章条绑定（官方上榜形态）：文案/奖杯图标/渐变色全由回包
     * rec_tags 榜单条目下发（HgFeedItem.rank*），渐变底程序化生成；
     * 没上榜整条藏。条本身在官方是榜单页入口，咱家没有榜单页——只做
     * 展示、不挂点击（点了没去处比不点更差，账里记明）。
     */
    private void bindRankStrip(ItemHongguoFeedBinding b, HgFeedItem item) {
        if (item.rankText == null || item.rankText.isEmpty()) {
            b.hgRankStrip.setVisibility(View.GONE);
            return;
        }
        b.hgRankStrip.setVisibility(View.VISIBLE);
        b.hgRankText.setText(item.rankText);
        if (item.rankIconUrl != null && !item.rankIconUrl.isEmpty()) {
            b.hgRankIcon.setVisibility(View.VISIBLE);
            ImgUtil.load("", item.rankIconUrl, b.hgRankIcon, false);
        } else {
            b.hgRankIcon.setVisibility(View.GONE);
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(6 * b.hgRankStrip.getResources().getDisplayMetrics().density);
        int[] colors = parseRankColors(item.rankColors);
        if (colors.length >= 2) {
            bg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
            bg.setColors(colors);
        } else if (colors.length == 1) {
            bg.setColor(colors[0]);
        } else {
            bg.setColor(0xB33A3A3A);
        }
        b.hgRankStrip.setBackground(bg);
    }

    private static int[] parseRankColors(List<String> raw) {
        if (raw == null) return new int[0];
        List<Integer> out = new java.util.ArrayList<>();
        for (String s : raw) {
            try {
                out.add(android.graphics.Color.parseColor(s));
            } catch (Exception ignored) {
                // 回包色串解不开就跳过这一档，不整条丢
            }
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /**
     * 标签筹填充（系列款行与普通款通行行共用）：最多 4 枚，数据空整行藏。
     * 数据优先官方筹行 infoChips（评分/季/题材/演员全集，评分筹带星标），
     * 没带才退回 category_schema 的 tags——旧版只有 tags，官方筹里的
     * 评分/季/演员永远出不来（2026-10-29 他真机对比点名）。
     */
    private void fillTags(LinearLayout row, HgFeedItem item) {
        row.removeAllViews();
        float density = row.getResources().getDisplayMetrics().density;
        int shown = 0;
        List<String> chipsData = item.infoChips != null && !item.infoChips.isEmpty() ? item.infoChips : item.tags;
        for (String tag : chipsData) {
            if (shown >= 4) break;
            android.widget.TextView chip = new android.widget.TextView(row.getContext());
            chip.setText(item.scoreChipPos == shown && chipsData == item.infoChips ? "★" + tag : tag);
            chip.setTextSize(12);
            chip.setTextColor(0xFFFFFFFF);
            chip.setBackgroundResource(R.drawable.bg_hg_tag);
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
        // 展开阈值照官方实拍两例定：113598/113482 热评正文均在约
        // 12 字处截断出「…＋展开」——旧阈值 24 让 13–24 字热评被截
        // 断却没展开钮（复核账 feed-infostack-reaudit-20261029）
        b.hgHotMore.setVisibility(item.hotComment.length() > 12 ? View.VISIBLE : View.GONE);
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

    /** 简介原文是否已带「第N集｜」形（防重拼：详情 desc 个别带集前缀）。 */
    private static boolean hasEpPrefix(String desc) {
        if (desc == null || desc.length() < 4 || !desc.startsWith("第")) return false;
        int bar = desc.indexOf('｜');
        return bar > 1 && bar < 8 && desc.substring(1, bar).contains("集");
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
        // 徽标独立点击：子件自消费不到父盒（与官方头像点击/覆钮点击两路
        // 分开同构）——点徽标只发关注、不进作者页
        b.hgFollowBadge.setOnClickListener(v -> host.onAuthorBadgeFollow(holder.getBindingAdapterPosition()));
        b.hgAuthor.setOnClickListener(v -> host.onAuthor(holder.getBindingAdapterPosition()));
        b.hgAuthorRow.setOnClickListener(v -> host.onAuthor(holder.getBindingAdapterPosition()));
        b.hgFollowBtn.setOnClickListener(v -> host.onAuthorFollow(holder.getBindingAdapterPosition()));
        b.hgEpTitleRow.setOnClickListener(v -> host.onSeriesDetail(holder.getBindingAdapterPosition()));
        b.hgFeedTitleRow.setOnClickListener(v -> host.onSeriesDetail(holder.getBindingAdapterPosition()));
        b.hgGuideChip.setOnClickListener(v -> host.onStartFromFirst(holder.getBindingAdapterPosition()));
        b.hgReserve.setOnClickListener(v -> host.onAction(holder.getBindingAdapterPosition(), Action.RESERVE));
        // 剧条：药丸按当前条目分发（未上线剧＝预约、已上线剧＝追更，
        // 走 host 既有登录门＋KIND 管道）；条身（封面/剧名区）＝进详情，
        // 与信息摞集流标题行同一去处
        b.hgDramaAct.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos < 0 || pos >= items.size()) return;
            host.onAction(pos, items.get(pos).reservable ? Action.RESERVE : Action.FOLLOW);
        });
        b.hgDramaBar.setOnClickListener(v -> host.onSeriesDetail(holder.getBindingAdapterPosition()));
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
