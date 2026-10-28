package com.fongmi.android.tv.hongguo.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.login.LoginManager;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 红果「我的」面（刷剧页壳内、底栏「我的」格）：登录区＋页签排＋海报格子，
 * 页签点开直接是格子内容，不再一层层菜单条（他对旧集合页的定罪点）。
 *
 * <p>构成按国内版官方包源码对照（mine-rework-spec-20261028）＋他的收敛口径：
 * 登录区照官方双态（未登录 c_x／已登录头像 60dp＋昵称）；页签＝历史／收藏／
 * 点赞／预约（官方 qb3/f 标题 16sp＋右端搜索图标；国内版预约是页内页签
 * 形态、与功能位互斥，本面取页签形态，他亲定预约放点赞后面）；子筛行照
 * 书架壳 auf（42dp＋二级页签 bb8 样式＋编辑 14sp 粗）：历史＝全部/已看完/
 * 未看完、收藏＝全部/真人剧/追更（他 2026-10-28 定）、预约＝已上线/待上线。
 * 格子走书架卡（封面比 114:159、新样式圆角 12dp、右上角标原文）；历史
 * 副行＝官方百分比算法（ceil(当前集×100/总集数)），收藏副行＝「X集/Y集」
 * 样式档。收藏读本地镜像＋服务端追剧架刷新；点格子回刷剧页续播。</p>
 */
public final class HgMineView extends LinearLayout {

    public interface OnOpenSeries {
        void onOpenSeries(String seriesId, String title, String cover, int episodeCount);
    }

    private static final int TAB_HISTORY = 0;
    private static final int TAB_FAV = 1;
    private static final int TAB_LIKE = 2;
    private static final int TAB_RESERVE = 3;

    private static final int HIST_ALL = 0;
    private static final int HIST_DONE = 1;
    private static final int HIST_UNDONE = 2;
    private static final int FAV_ALL = 0;
    private static final int FAV_REAL = 1;
    private static final int FAV_FOLLOW = 2;
    private static final int RES_ONLINE = 0;
    private static final int RES_UPCOMING = 1;
    private static final int FOLLOW_ALL = 0;
    private static final int FOLLOW_DONE = 1;
    private static final int FOLLOW_UNDONE = 2;

    private View guestRow;
    private View userRow;
    private TextView nicknameView;
    private android.widget.ImageView avatarView;
    private TextView avatarBadge;
    private View relationRow;
    private TextView relFollowNum;
    private TextView relFollowUnit;
    private TextView relFansNum;
    private TextView relFansUnit;
    private TextView relDiggNum;
    private TextView relDiggUnit;
    private TextView emptyView;
    private LinearLayout chipsBox;
    private LinearLayout followChipsBox;
    private TextView editBtn;
    private View editBar;
    private TextView editCount;
    private final TextView[] tabViews = new TextView[4];
    private final MineAdapter adapter = new MineAdapter();
    private int currentTab = TAB_HISTORY;
    private int histFilter = HIST_ALL;
    private int favFilter = FAV_ALL;
    private int followFilter = FOLLOW_ALL;
    private boolean followRefreshing;
    private int reserveFilter = RES_ONLINE;
    private final java.util.Map<String, String> reserveDue = new java.util.HashMap<>();
    private boolean reserveRefreshing;
    private boolean editing;
    private final java.util.Set<String> selected = new java.util.HashSet<>();
    private OnOpenSeries openListener;
    private int loginPollLeft;
    private final Runnable loginPoll = new Runnable() {
        @Override
        public void run() {
            if (LoginManager.isHongguoLoggedIn()) {
                refreshAccount();
                return;
            }
            if (--loginPollLeft > 0) postDelayed(this, 600);
        }
    };

    public HgMineView(Context context) {
        super(context);
    }

    public HgMineView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        guestRow = findViewById(R.id.hg_mine_guest_row);
        userRow = findViewById(R.id.hg_mine_user_row);
        nicknameView = findViewById(R.id.hg_mine_nickname);
        avatarView = findViewById(R.id.hg_mine_avatar);
        avatarBadge = findViewById(R.id.hg_mine_avatar_badge);
        relationRow = findViewById(R.id.hg_mine_relation);
        relFollowNum = findViewById(R.id.hg_mine_rel_follow);
        relFollowUnit = findViewById(R.id.hg_mine_rel_follow_unit);
        relFansNum = findViewById(R.id.hg_mine_rel_fans);
        relFansUnit = findViewById(R.id.hg_mine_rel_fans_unit);
        relDiggNum = findViewById(R.id.hg_mine_rel_digg);
        relDiggUnit = findViewById(R.id.hg_mine_rel_digg_unit);
        emptyView = findViewById(R.id.hg_mine_empty);
        chipsBox = findViewById(R.id.hg_mine_chips);
        followChipsBox = findViewById(R.id.hg_mine_follow_chips);
        editBtn = findViewById(R.id.hg_mine_edit);
        editBar = findViewById(R.id.hg_mine_edit_bar);
        editCount = findViewById(R.id.hg_mine_edit_count);
        tabViews[TAB_HISTORY] = findViewById(R.id.hg_mine_tab_history);
        tabViews[TAB_FAV] = findViewById(R.id.hg_mine_tab_fav);
        tabViews[TAB_LIKE] = findViewById(R.id.hg_mine_tab_like);
        tabViews[TAB_RESERVE] = findViewById(R.id.hg_mine_tab_reserve);
        RecyclerView grid = findViewById(R.id.hg_mine_grid);
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(adapter);
        grid.addItemDecoration(new MineGridSpacing());
        findViewById(R.id.hg_mine_login_btn).setOnClickListener(v -> onAccountClick());
        userRow.setOnClickListener(v -> onAccountClick());
        // 功能卡两格（官方同位）：看剧偏好＝服务端偏好页；反馈与帮助＝
        // 官方反馈网页（应用内 WebView）。我的下载缺前置未落，见布局注。
        findViewById(R.id.hg_mine_fn_pref).setOnClickListener(v ->
                HongguoPreferenceActivity.start(getContext()));
        findViewById(R.id.hg_mine_fn_feedback).setOnClickListener(v ->
                HongguoFeedbackActivity.start(getContext()));
        findViewById(R.id.hg_mine_search).setOnClickListener(v ->
                getContext().startActivity(new android.content.Intent(
                        getContext(), HongguoSearchActivity.class)));
        editBtn.setOnClickListener(v -> setEditing(!editing));
        findViewById(R.id.hg_mine_edit_delete).setOnClickListener(v -> deleteSelected());
        for (int i = 0; i < tabViews.length; i++) {
            final int tab = i;
            tabViews[i].setOnClickListener(v -> selectTab(tab));
        }
    }

    public void setOnOpenSeriesListener(OnOpenSeries listener) {
        this.openListener = listener;
    }

    /** 每次切到本面时调：账号态与当前页签数据都按最新状态重读。 */
    public void refresh() {
        refreshAccount();
        renderTab();
        backfillMissingCovers();
        refreshFollowDotSource();
    }

    /** 召回弹窗「去我的-预约查看」的落点：切到预约页签＋已上线档重列。 */
    public void openReservations() {
        currentTab = TAB_RESERVE;
        reserveFilter = RES_ONLINE;
        if (editing) setEditing(false);
        renderTab();
    }

    /**
     * 历史封面回填：解析器旧 bug（条目空 cover 盖掉真封面，96fa6fa5 已修）把
     * 一批历史记录的封面存成了空，这些记录在格子里只剩占位。本面可见时把
     * 缺封面的历史（最多 12 部）逐个拉详情补封面落盘，回来重绑历史页签；
     * 只补空的、不动已有封面，失败静默下次再补。
     */
    private void backfillMissingCovers() {
        List<String> missing = new ArrayList<>();
        for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
            if ((r.cover == null || r.cover.isEmpty()) && r.seriesId != null) {
                missing.add(r.seriesId);
                if (missing.size() >= 12) break;
            }
        }
        if (missing.isEmpty()) return;
        HgFeedSession.get().client.fetchVideoDetails(missing,
                new HgCallback<java.util.Map<String, com.fongmi.android.tv.hongguo.model.HgSeriesDetail>>() {
                    @Override
                    public void onSuccess(java.util.Map<String, com.fongmi.android.tv.hongguo.model.HgSeriesDetail> map) {
                        if (map == null || map.isEmpty()) return;
                        boolean changed = false;
                        for (java.util.Map.Entry<String, com.fongmi.android.tv.hongguo.model.HgSeriesDetail> e : map.entrySet()) {
                            com.fongmi.android.tv.hongguo.model.HgSeriesDetail d = e.getValue();
                            if (d == null || d.getCoverUrl() == null || d.getCoverUrl().isEmpty()) continue;
                            String[] meta = HgStore.metaOf(e.getKey());
                            HgStore.putMeta(e.getKey(),
                                    d.getTitle() != null ? d.getTitle() : meta != null ? meta[0] : null,
                                    d.getCoverUrl(),
                                    d.getEpisodeCount() > 0 ? d.getEpisodeCount()
                                            : meta != null ? parseCount(meta) : -1);
                            changed = true;
                        }
                        if (changed) post(() -> {
                            if (currentTab == TAB_HISTORY) renderTab();
                        });
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        // 静默：下次进本面再补
                    }
                });
    }

    /** 页面销毁时停掉登录轮询，别让回调落到已销毁的面上。 */
    public void cancelPending() {
        removeCallbacks(loginPoll);
    }

    private void refreshAccount() {
        // 头部双态（官方构成）：未登录＝大标题/副行＋立即登录钮；已登录＝
        // 头像 60dp＋昵称。昵称/头像走 passport 资料口（account-info-dig）：
        // 先显本地镜像（登出已随 clearAccountMirrors 清），再按官方节奏
        // 每次本面可见时回源刷新一次，回来落镜像重绑；回源失败静默保旧镜像。
        boolean in = LoginManager.isHongguoLoggedIn();
        guestRow.setVisibility(in ? GONE : VISIBLE);
        userRow.setVisibility(in ? VISIBLE : GONE);
        if (!in) return;
        bindProfile();
        HgFeedSession.get().client.fetchAccountInfo(
                new HgCallback<com.fongmi.android.tv.hongguo.model.HgAccountProfile>() {
                    @Override
                    public void onSuccess(com.fongmi.android.tv.hongguo.model.HgAccountProfile p) {
                        HgStore.saveProfile(p.displayName, p.avatarUrl, p.userId);
                        post(() -> {
                            if (LoginManager.isHongguoLoggedIn()) bindProfile();
                        });
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        // 静默：资料口认不认网页会话待首登定论，失败不冲镜像不报错
                    }
                });
        refreshRelation();
    }

    /**
     * 关系行回源（官方 HongguoMineRelationLayout 同源）：本人资料
     * profile/get 的 user_relation 三计数（关注/粉丝/获赞）。本人 uid
     * 未知（资料口还没回过）或回源失败/计数不齐时整行不显——不摆假 0；
     * 与资料口同命：认不认网页会话待首登定论。
     */
    private void refreshRelation() {
        if (relationRow == null) return;
        String uid = HgStore.profileUid();
        if (uid == null || uid.isEmpty()) {
            relationRow.setVisibility(GONE);
            return;
        }
        HgFeedSession.get().client.fetchUserProfile(uid,
                new HgCallback<com.fongmi.android.tv.hongguo.model.HgUserProfile>() {
                    @Override
                    public void onSuccess(com.fongmi.android.tv.hongguo.model.HgUserProfile p) {
                        post(() -> {
                            if (p == null || !p.hasRelationCounts()) {
                                relationRow.setVisibility(GONE);
                                return;
                            }
                            setRelationCount(relFollowNum, relFollowUnit, p.followUserNum);
                            setRelationCount(relFansNum, relFansUnit, p.fansNum);
                            setRelationCount(relDiggNum, relDiggUnit, p.recvDiggedCount);
                            relationRow.setVisibility(VISIBLE);
                        });
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        post(() -> relationRow.setVisibility(GONE));
                    }
                });
    }

    /** 计数拆数＋单位两件（官方 o6 拼法，统一走 HgFormat：万以下原数无单位、万起 x.x万一位小数不剥尾）。 */
    private static void setRelationCount(TextView num, TextView unit, int n) {
        String[] p = com.fongmi.android.tv.hongguo.HgFormat.countUnit(n);
        num.setText(p[0]);
        unit.setText(p[1]);
    }

    /**
     * 网格间距（官方 az3/a.java:53-118 逐行定版，VideoCollLayout 新样式以
     * z=false 构造）：3 列、横向单位 b＝8dp 按 left＝列×b/列数、
     * right＝b－(列+1)×b/列数 分配（列间合计 8dp、两边 0）、行底 c＝18dp、
     * 首行顶 d＝11dp、末行底 e＝18dp（与 c 同值）。
     */
    private final class MineGridSpacing extends RecyclerView.ItemDecoration {
        @Override
        public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int pos = parent.getChildAdapterPosition(view);
            if (pos < 0) return;
            int unit = dp(8);
            int col = pos % 3;
            outRect.left = col * unit / 3;
            outRect.right = unit - (col + 1) * unit / 3;
            outRect.top = pos < 3 ? dp(11) : 0;
            outRect.bottom = dp(18);
        }
    }

    /** 头部绑定：昵称＝资料镜像（无镜像回落账号态文案）；头像有图显图、无图显字徽。 */
    private void bindProfile() {
        String name = HgStore.profileName();
        nicknameView.setText(name != null ? name : "红果账号已登录");
        String avatar = HgStore.profileAvatar();
        if (avatar != null && avatarView != null) {
            avatarView.setVisibility(VISIBLE);
            if (avatarBadge != null) avatarBadge.setVisibility(GONE);
            ImgUtil.loadCircle("", avatar, avatarView);
        } else {
            if (avatarView != null) avatarView.setVisibility(GONE);
            if (avatarBadge != null) {
                avatarBadge.setVisibility(VISIBLE);
                avatarBadge.setText(name != null && !name.isEmpty()
                        ? name.substring(0, 1) : "红");
            }
        }
    }

    private void onAccountClick() {
        if (!LoginManager.isHongguoLoggedIn()) {
            // 走壳子统一托管的红果登录（短信链），回来轮询刷账号态
            // 轮询窗 100 次×600ms＝60 秒：旧 20 次（12 秒）装不下验证码往返，
            // 慢一点的登录回来就落「已登录但本面显未登录」的假态（审计实证）
            LoginManager.loginHongguoDelivered();
            loginPollLeft = 100;
            removeCallbacks(loginPoll);
            postDelayed(loginPoll, 600);
            return;
        }
        new AlertDialog.Builder(getContext())
                .setMessage("退出红果登录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (d, w) -> {
                    LoginManager.logoutHongguo();
                    // 账号维度的本地镜像（收藏/点赞/预约/追剧架）随登出清掉：
                    // 不清则换号登录直接串上前一账号的格子（审计实证）；
                    // 本地观看历史是设备维度数据，不在此列
                    HgStore.clearAccountMirrors();
                    refreshAccount();
                    renderTab();
                })
                .show();
    }

    private void selectTab(int tab) {
        currentTab = tab;
        if (editing) setEditing(false);
        renderTab();
    }

    /** 子筛筹行：按当前页签重建（历史三筹／收藏三筹／点赞无筹），编辑钮点赞页签也留。 */
    private void renderChips() {
        chipsBox.removeAllViews();
        if (currentTab == TAB_HISTORY) {
            addChip("全部", histFilter == HIST_ALL, v -> {
                histFilter = HIST_ALL;
                renderTab();
            });
            addChip("已看完", histFilter == HIST_DONE, v -> {
                histFilter = HIST_DONE;
                renderTab();
            });
            addChip("未看完", histFilter == HIST_UNDONE, v -> {
                histFilter = HIST_UNDONE;
                renderTab();
            });
        } else if (currentTab == TAB_FAV) {
            addChip("全部", favFilter == FAV_ALL, v -> {
                favFilter = FAV_ALL;
                renderTab();
            });
            addChip("真人剧", favFilter == FAV_REAL, v -> {
                favFilter = FAV_REAL;
                renderTab();
            });
            addFollowChip(favFilter == FAV_FOLLOW, v -> {
                favFilter = FAV_FOLLOW;
                renderTab();
            });
        } else if (currentTab == TAB_RESERVE) {
            addChip("已上线", reserveFilter == RES_ONLINE, v -> {
                reserveFilter = RES_ONLINE;
                renderTab();
            });
            addChip("待上线", reserveFilter == RES_UPCOMING, v -> {
                reserveFilter = RES_UPCOMING;
                renderTab();
            });
        }
        renderFollowChips();
    }

    /** 追更自带筛选行（官方 MineFollowUpdateFilter：全部/已看完/未看完）：仅追更子筛选中时显。 */
    private void renderFollowChips() {
        if (followChipsBox == null) return;
        boolean show = currentTab == TAB_FAV && favFilter == FAV_FOLLOW;
        followChipsBox.setVisibility(show ? VISIBLE : GONE);
        if (!show) return;
        followChipsBox.removeAllViews();
        addChipTo(followChipsBox, "全部", followFilter == FOLLOW_ALL, v -> {
            followFilter = FOLLOW_ALL;
            renderTab();
        });
        addChipTo(followChipsBox, "已看完", followFilter == FOLLOW_DONE, v -> {
            followFilter = FOLLOW_DONE;
            renderTab();
        });
        addChipTo(followChipsBox, "未看完", followFilter == FOLLOW_UNDONE, v -> {
            followFilter = FOLLOW_UNDONE;
            renderTab();
        });
    }

    private void addChip(String text, boolean on, OnClickListener click) {
        addChipTo(chipsBox, text, on, click);
    }

    /**
     * 追更筹＋红点：官方唯一带红点的面是「我的追更」功能位（服务端
     * showRedDot、点击消），咱家无金刚位、对应载体是这个追更子筛筹——
     * 红点压筹右上角内侧，形态照官方 follow 页签红点件（bg_hg_red_dot：
     * #FFFA6725＋1dp 描边）。亮灭判定走 HgUpdateLogic（最新集数 vs
     * 已看快照/断点），数据靠本面回源刷新（refreshFollowDotSource）。
     */
    private void addFollowChip(boolean on, OnClickListener click) {
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(getContext());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.rightMargin = dp(8);
        wrap.setLayoutParams(wlp);
        wrap.addView(createChip("追更", on, click));
        View dot = new View(getContext());
        dot.setBackgroundResource(R.drawable.bg_hg_red_dot);
        android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(
                dp(10), dp(10));
        dlp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        dlp.topMargin = dp(2);
        dlp.rightMargin = dp(2);
        dot.setLayoutParams(dlp);
        dot.setVisibility(followDotOn() ? VISIBLE : GONE);
        wrap.addView(dot);
        chipsBox.addView(wrap);
    }

    /** 红点亮灭：任一追更剧的最新集数越过已看基线即亮（判定纯逻辑在 HgUpdateLogic）。 */
    private boolean followDotOn() {
        for (String id : HgStore.followedIds()) {
            String[] meta = HgStore.metaOf(id);
            if (meta == null) continue;
            HgStore.Progress p = HgStore.progressOf(id);
            int watched = p == null ? 0 : p.episodeIndex;
            if (com.fongmi.android.tv.hongguo.model.HgUpdateLogic.followHasUpdate(
                    parseCount(meta), HgStore.followSeenCount(id), watched)) return true;
        }
        return false;
    }

    /** 进追更面即「看过」：把各剧当前最新集数落成已看快照，红点随之灭（下次真更新再亮）。 */
    private void markFollowSeenNow() {
        for (String id : HgStore.followedIds()) {
            String[] meta = HgStore.metaOf(id);
            if (meta == null) continue;
            int count = parseCount(meta);
            if (count > 0) HgStore.markFollowSeen(id, count);
        }
    }

    private void addChipTo(LinearLayout box, String text, boolean on, OnClickListener click) {
        TextView chip = createChip(text, on, click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        box.addView(chip);
    }

    private TextView createChip(String text, boolean on, OnClickListener click) {
        // 二级页签样式照 bb8.xml：14sp、左右 padding 14dp、上下 5dp、带底选择器
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextSize(14);
        chip.setTextColor(on ? 0xFFFF7A1A : 0xFF333333);
        chip.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? 0xFFFFF3E8 : 0xFFF5F5F5);
        chip.setBackground(bg);
        chip.setPadding(dp(14), dp(5), dp(14), dp(5));
        chip.setOnClickListener(click);
        return chip;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setEditing(boolean on) {
        editing = on;
        if (!on) selected.clear();
        editBtn.setText(on ? "完成" : "编辑");
        editBar.setVisibility(on ? VISIBLE : GONE);
        updateEditBar();
        adapter.notifyDataSetChanged();
    }

    private void updateEditBar() {
        editCount.setText(selected.isEmpty() ? "未选择" : "已选 " + selected.size() + " 部");
    }

    private void deleteSelected() {
        if (selected.isEmpty()) return;
        for (String id : new ArrayList<>(selected)) {
            if (currentTab == TAB_HISTORY) HgStore.removeHistory(id);
            else if (currentTab == TAB_LIKE) HgStore.setLiked(id, false);
            else if (currentTab == TAB_RESERVE) {
                // 取消预约走预约开关（本地镜像＋服务端同步，失败回滚在开关内）
                if (HgStore.isReserved(id)) {
                    HgFeedSession.get().remoteInteractions.toggleReserve(id);
                }
            } else if (currentTab == TAB_FAV && favFilter == FAV_FOLLOW) {
                // 取消追更走追剧架开关（本地镜像＋服务端同步，失败回滚在
                // 开关内）；只改本地会被下一次回源拉回（删除复活）
                if (HgStore.isFollowed(id)) {
                    HgFeedSession.get().remoteInteractions.toggleFollow(id);
                } else HgStore.setFollowed(id, false);
            } else if (currentTab == TAB_FAV) {
                // 取消收藏同走追剧架开关（与收藏面同一服务端本体）
                if (HgStore.isFaved(id)) {
                    HgFeedSession.get().remoteInteractions.toggleFavorite(id);
                } else HgStore.setFaved(id, false);
            }
        }
        selected.clear();
        updateEditBar();
        renderTab();
    }

    private void renderTab() {
        for (int i = 0; i < tabViews.length; i++) {
            boolean on = i == currentTab;
            tabViews[i].setTextColor(on ? 0xFF111111 : 0xFF999999);
            tabViews[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        renderChips();
        List<Row> rows = new ArrayList<>();
        if (currentTab == TAB_RESERVE) {
            // 分档以服务端回源集为准（官方 is_online 分档）；从未回源过
            // （集为空）才回落旧推断：本地 meta 集数>0 视为已上线。
            java.util.Set<String> onlineIds = HgStore.reservedOnlineIds();
            boolean serverKnown = !onlineIds.isEmpty();
            for (String id : HgStore.reservedIds()) {
                String[] meta = HgStore.metaOf(id);
                if (meta == null) continue;
                int count = parseCount(meta);
                boolean online = serverKnown ? onlineIds.contains(id) : count > 0;
                if (reserveFilter == RES_ONLINE && !online) continue;
                if (reserveFilter == RES_UPCOMING && online) continue;
                String due = reserveDue.get(id);
                String metaText = due != null && !due.isEmpty() ? "已预约 · " + due : "已预约";
                rows.add(new Row(id, meta[0], meta[1], count, metaText,
                        meta.length > 3 ? meta[3] : null));
            }
            adapter.setRows(rows);
            emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
            emptyView.setText(LoginManager.isHongguoLoggedIn()
                    ? "还没有预约的剧" : "登录后这里显示你预约的剧");
            if (LoginManager.isHongguoLoggedIn()) refreshReservedFromServer();
            return;
        }
        if (currentTab == TAB_HISTORY) {
            for (HgStore.HistoryRecord r : HgStore.historyRecords()) {
                if (r.title == null) continue;
                boolean done = r.episodeCount > 0 && r.episodeIndex >= r.episodeCount;
                if (histFilter == HIST_DONE && !done) continue;
                if (histFilter == HIST_UNDONE && done) continue;
                // 历史副行＝官方百分比口径（dig-mine2 §1.4：进度X%，
                // X＝ceil((当前集)×100/总集数) 夹 0–100）；集数式只留收藏面
                String meta;
                if (r.episodeIndex <= 0) meta = "未看过";
                else if (r.episodeCount > 0) {
                    int pct = (int) Math.ceil(r.episodeIndex * 100.0 / r.episodeCount);
                    meta = "已看" + Math.max(0, Math.min(100, pct)) + "%";
                } else meta = "看到第" + r.episodeIndex + "集";
                String[] m = HgStore.metaOf(r.seriesId);
                rows.add(new Row(r.seriesId, r.title, r.cover, r.episodeCount, meta,
                        m != null && m.length > 3 ? m[3] : null));
            }
        } else if (currentTab == TAB_FAV && favFilter == FAV_FOLLOW) {
            for (String id : HgStore.followedIds()) {
                String[] meta = HgStore.metaOf(id);
                if (meta == null) continue;
                int count = parseCount(meta);
                // 追更面内筛选（官方 MineFollowUpdateFilter）：已看完＝断点
                // 到总集数，未看完＝其余（含没看过）
                HgStore.Progress p = HgStore.progressOf(id);
                boolean done = count > 0 && p != null && p.episodeIndex >= count;
                if (followFilter == FOLLOW_DONE && !done) continue;
                if (followFilter == FOLLOW_UNDONE && done) continue;
                rows.add(new Row(id, meta[0], meta[1], count, shelfMeta(id, count),
                        meta.length > 3 ? meta[3] : null));
            }
            // 进了追更面＝看过：落已看快照、红点灭（renderChips 在
            // renderTab 开头已按旧状态建过筹，这里重建一次把点收掉）
            markFollowSeenNow();
            renderChips();
            if (LoginManager.isHongguoLoggedIn()) refreshFollowFromServer();
        } else {
            List<String> ids = currentTab == TAB_FAV ? HgStore.favedIds() : HgStore.likedIds();
            for (String id : ids) {
                String[] meta = HgStore.metaOf(id);
                if (meta == null) continue;
                String badge = meta.length > 3 ? meta[3] : null;
                if (currentTab == TAB_FAV && favFilter == FAV_REAL
                        && !"真人剧".equals(badge)) continue;
                int count = parseCount(meta);
                // 收藏副行同书架样式档（有断点走「X集/Y集」、没看过走
                // 「未看过」）；点赞副行维持集数式、不编造
                String metaText = currentTab == TAB_FAV ? shelfMeta(id, count)
                        : (count > 0 ? "共" + count + "集" : "");
                rows.add(new Row(id, meta[0], meta[1], count, metaText, badge));
            }
        }
        adapter.setRows(rows);
        emptyView.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        if (rows.isEmpty()) {
            emptyView.setText(currentTab == TAB_HISTORY ? "还没有观看记录"
                    : currentTab == TAB_FAV
                    ? (favFilter == FAV_FOLLOW ? "还没有追更的剧" : "还没有收藏")
                    : "还没有点赞");
        }
        if (currentTab == TAB_FAV && favFilter != FAV_FOLLOW
                && LoginManager.isHongguoLoggedIn()) {
            refreshFavFromServer();
        }
    }

    private static int parseCount(String[] meta) {
        try {
            return Integer.parseInt(meta[2]);
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * 收藏页签后台刷新：追剧架列表（收藏的服务端本体）回来后以服务端为准
     * 重列，并同步本地镜像与元信息（与预约页签同口径）。旧版收藏只读本地
     * 镜像——在官方 App/他端收藏的剧在本面永远看不到（审计实证）。
     * 服务端回空时不冲掉已显的镜像行（与预约页签同一防冲空口径）。
     */
    private void refreshFavFromServer() {
        HgFeedSession.get().client.bookshelfVideoList(new HgCallback<com.fongmi.android.tv.hongguo.model.HgCellPage>() {
            @Override
            public void onSuccess(com.fongmi.android.tv.hongguo.model.HgCellPage page) {
                post(() -> {
                    if (currentTab != TAB_FAV || page == null || page.getItems() == null) return;
                    List<Row> fresh = new ArrayList<>();
                    for (HgSeriesItem s : page.getItems()) {
                        if (s.getSeriesId() == null) continue;
                        HgStore.setShelfMirrored(s.getSeriesId(), true);
                        HgStore.setFaved(s.getSeriesId(), true);
                        String[] local = HgStore.metaOf(s.getSeriesId());
                        String title = s.getTitle() != null ? s.getTitle()
                                : local != null ? local[0] : null;
                        String cover = s.getCoverUrl() != null ? s.getCoverUrl()
                                : local != null ? local[1] : null;
                        HgStore.putMeta(s.getSeriesId(), title, cover, s.getEpisodeCount(),
                                s.getBadge());
                        fresh.add(new Row(s.getSeriesId(), title, cover, s.getEpisodeCount(),
                                s.getEpisodeCount() > 0 ? "共" + s.getEpisodeCount() + "集" : "",
                                s.getBadge()));
                    }
                    if (fresh.isEmpty()) return;
                    adapter.setRows(fresh);
                    emptyView.setVisibility(GONE);
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                // 镜像已先显，刷新失败不打扰
            }
        });
    }

    /**
     * 追更子筛后台刷新：追更与收藏同在追剧架（服务端同一列表、同一开关），
     * 回源把架上条目并入双镜像与元信息后重列。此前追更只读本地
     * followedIds()、从不回源——他端追的更在本面永远看不到。
     * 服务端回空不冲已显镜像行（防冲空口径同收藏/预约）。
     */
    private void refreshFollowFromServer() {
        if (followRefreshing) return;
        followRefreshing = true;
        HgFeedSession.get().client.bookshelfVideoList(new HgCallback<com.fongmi.android.tv.hongguo.model.HgCellPage>() {
            @Override
            public void onSuccess(com.fongmi.android.tv.hongguo.model.HgCellPage page) {
                post(() -> {
                    if (currentTab != TAB_FAV || favFilter != FAV_FOLLOW
                            || page == null || page.getItems() == null || page.getItems().isEmpty()) {
                        followRefreshing = false;
                        return;
                    }
                    mergeShelfIntoStore(page);
                    renderTab();
                    // 刷完才放行：renderTab 会再走一遍本方法，放行在前
                    // 会自咬成无限回源（本批顺手堵，口径仍是进面刷一次）
                    followRefreshing = false;
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                post(() -> followRefreshing = false);
                // 镜像已先显，刷新失败不打扰
            }
        });
    }

    /**
     * 追剧架回源合并（收藏/追更同一服务端本体）：架上条目并入双镜像与
     * 元信息（标题/封面/集数/角标以服务端为准、缺项回落本地已存）。
     */
    private void mergeShelfIntoStore(com.fongmi.android.tv.hongguo.model.HgCellPage page) {
        for (HgSeriesItem s : page.getItems()) {
            if (s.getSeriesId() == null) continue;
            HgStore.setShelfMirrored(s.getSeriesId(), true);
            String[] local = HgStore.metaOf(s.getSeriesId());
            String title = s.getTitle() != null ? s.getTitle()
                    : local != null ? local[0] : null;
            String cover = s.getCoverUrl() != null ? s.getCoverUrl()
                    : local != null ? local[1] : null;
            HgStore.putMeta(s.getSeriesId(), title, cover, s.getEpisodeCount(),
                    s.getBadge());
        }
    }

    private boolean followDotRefreshing;

    /**
     * 追更红点的数据源刷新：本面每次露面时回源一次追剧架（与官方红点
     * 随资料口下发同节奏），合并最新集数后重建筹行刷红点。不限于当前
     * 正看追更子筛——红点要在进「我的」时就亮对，不必先点开追更才知道。
     * 失败静默（镜像旧值判出的点维持原样，下次露面再刷）。
     */
    private void refreshFollowDotSource() {
        if (followDotRefreshing || !LoginManager.isHongguoLoggedIn()) return;
        followDotRefreshing = true;
        HgFeedSession.get().client.bookshelfVideoList(new HgCallback<com.fongmi.android.tv.hongguo.model.HgCellPage>() {
            @Override
            public void onSuccess(com.fongmi.android.tv.hongguo.model.HgCellPage page) {
                post(() -> {
                    followDotRefreshing = false;
                    if (page == null || page.getItems() == null || page.getItems().isEmpty()) return;
                    mergeShelfIntoStore(page);
                    renderChips();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                post(() -> followDotRefreshing = false);
            }
        });
    }

    /**
     * 预约页签后台刷新：按官方 is_online 分两档回源——两档都拉，已上线档
     * 落盘为分档集（renderTab 分档以它为准）；镜像与上线时间同步后重列。
     * 防冲空口径不变：某档回空不冲该档镜像行（本地预约动作先落镜像，
     * 服务端最终一致）。
     */
    private void refreshReservedFromServer() {
        if (reserveRefreshing) return;
        reserveRefreshing = true;
        fetchReserveTier(true);
        fetchReserveTier(false);
    }

    private void fetchReserveTier(boolean online) {
        HgFeedSession.get().client.fetchMyReservations(online, new HgCallback<java.util.List<HgSeriesItem>>() {
            @Override
            public void onSuccess(java.util.List<HgSeriesItem> list) {
                post(() -> {
                    reserveRefreshing = false;
                    if (list == null) return;
                    if (online) {
                        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                        for (HgSeriesItem s : list) {
                            if (s.getSeriesId() != null) ids.add(s.getSeriesId());
                        }
                        if (!ids.isEmpty()) HgStore.setReservedOnlineIds(ids);
                    }
                    if (currentTab != TAB_RESERVE || list.isEmpty()) return;
                    for (HgSeriesItem s : list) {
                        if (s.getSeriesId() == null) continue;
                        HgStore.setReserved(s.getSeriesId(), true);
                        String[] local = HgStore.metaOf(s.getSeriesId());
                        String title = s.getTitle() != null ? s.getTitle()
                                : local != null ? local[0] : null;
                        String cover = s.getCoverUrl() != null ? s.getCoverUrl()
                                : local != null ? local[1] : null;
                        HgStore.putMeta(s.getSeriesId(), title, cover, s.getEpisodeCount(),
                                s.getBadge());
                        if (s.getDueStartText() != null) {
                            reserveDue.put(s.getSeriesId(), s.getDueStartText());
                        }
                    }
                    renderTab();
                });
            }

            @Override
            public void onError(int httpCode, String message) {
                post(() -> reserveRefreshing = false);
                // 镜像已先显，刷新失败不打扰
            }
        });
    }

    /** 书架副行样式档（官方 c3()）：有断点＝「X集/Y集」，没看过＝「未看过」。 */
    private static String shelfMeta(String seriesId, int total) {
        HgStore.Progress p = HgStore.progressOf(seriesId);
        int idx = p == null ? 0 : p.episodeIndex;
        if (idx <= 0) return total > 0 ? "未看过" : "";
        if (total > 0) return idx + "集/" + total + "集";
        return "看到第" + idx + "集";
    }

    private static final class Row {
        final String seriesId;
        final String title;
        final String cover;
        final int episodeCount;
        final String meta;
        final String badge;

        Row(String seriesId, String title, String cover, int episodeCount, String meta,
            String badge) {
            this.seriesId = seriesId;
            this.title = title;
            this.cover = cover;
            this.episodeCount = episodeCount;
            this.meta = meta;
            this.badge = badge;
        }
    }

    private final class MineAdapter extends RecyclerView.Adapter<MineAdapter.Holder> {

        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> next) {
            rows = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hongguo_mine_card, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row row = rows.get(position);
            ImgUtil.load(row.title == null ? "" : row.title, row.cover, h.cover);
            h.title.setText(row.title == null ? "" : row.title);
            h.meta.setText(row.meta == null ? "" : row.meta);
            if (row.badge != null && !row.badge.isEmpty()) {
                h.badge.setVisibility(VISIBLE);
                h.badge.setText(row.badge);
            } else {
                h.badge.setVisibility(GONE);
            }
            h.check.setVisibility(editing ? VISIBLE : GONE);
            if (editing) {
                h.check.setImageResource(selected.contains(row.seriesId)
                        ? R.drawable.ic_hg_check_on : R.drawable.bg_hg_check_off);
            }
            h.itemView.setOnClickListener(v -> {
                if (editing) {
                    if (!selected.remove(row.seriesId)) selected.add(row.seriesId);
                    updateEditBar();
                    notifyItemChanged(h.getBindingAdapterPosition());
                    return;
                }
                if (openListener != null) {
                    openListener.onOpenSeries(row.seriesId, row.title, row.cover, row.episodeCount);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView title;
            final TextView meta;
            final TextView badge;
            final ImageView check;

            Holder(View itemView) {
                super(itemView);
                cover = itemView.findViewById(R.id.hg_m_cover);
                title = itemView.findViewById(R.id.hg_m_title);
                meta = itemView.findViewById(R.id.hg_m_meta);
                badge = itemView.findViewById(R.id.hg_m_badge);
                check = itemView.findViewById(R.id.hg_m_check);
                // 封面圆角：浅灰底垫＋12dp 圆角裁切（官方新样式书架卡
                // bookcover f(12dp)，x33/k.java:248；旧版直角裸图被他点名「生硬」）
                android.graphics.drawable.GradientDrawable round =
                        new android.graphics.drawable.GradientDrawable();
                round.setColor(0xFFF5F5F5);
                round.setCornerRadius(dp(12));
                cover.setBackground(round);
                cover.setClipToOutline(true);
            }
        }
    }
}
