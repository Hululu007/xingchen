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
 * <p>构成按国内版官方包源码对照（dig-mine-profile／dig-mine2）＋他的收敛口径：
 * 登录区照官方双态（未登录 c_x／已登录头像 60dp＋昵称）；功能列表只留短剧
 * 相关且有真去处的项（我的预约开预约页）；页签＝历史／收藏／点赞（官方
 * qb3/f 标题 16sp＋右端搜索图标）；子筛行照书架壳 auf（42dp＋编辑 14sp 粗）：
 * 历史＝全部/已看完/未看完、收藏＝全部/真人剧/追更（他 2026-10-28 定）。
 * 格子走书架卡（封面比 114:159、圆角、右上角标原文）；历史副行＝官方百分比
 * 算法（ceil(当前集×100/总集数)），收藏副行＝「X集/Y集」样式档。
 * 收藏读本地镜像＋服务端追剧架刷新；点格子回刷剧页续播。</p>
 */
public final class HgMineView extends LinearLayout {

    public interface OnOpenSeries {
        void onOpenSeries(String seriesId, String title, String cover, int episodeCount);
    }

    private static final int TAB_HISTORY = 0;
    private static final int TAB_FAV = 1;
    private static final int TAB_LIKE = 2;

    private static final int HIST_ALL = 0;
    private static final int HIST_DONE = 1;
    private static final int HIST_UNDONE = 2;
    private static final int FAV_ALL = 0;
    private static final int FAV_REAL = 1;
    private static final int FAV_FOLLOW = 2;

    private View guestRow;
    private View userRow;
    private TextView nicknameView;
    private android.widget.ImageView avatarView;
    private TextView avatarBadge;
    private TextView emptyView;
    private LinearLayout chipsBox;
    private TextView editBtn;
    private View editBar;
    private TextView editCount;
    private final TextView[] tabViews = new TextView[3];
    private final MineAdapter adapter = new MineAdapter();
    private int currentTab = TAB_HISTORY;
    private int histFilter = HIST_ALL;
    private int favFilter = FAV_ALL;
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
        emptyView = findViewById(R.id.hg_mine_empty);
        chipsBox = findViewById(R.id.hg_mine_chips);
        editBtn = findViewById(R.id.hg_mine_edit);
        editBar = findViewById(R.id.hg_mine_edit_bar);
        editCount = findViewById(R.id.hg_mine_edit_count);
        tabViews[TAB_HISTORY] = findViewById(R.id.hg_mine_tab_history);
        tabViews[TAB_FAV] = findViewById(R.id.hg_mine_tab_fav);
        tabViews[TAB_LIKE] = findViewById(R.id.hg_mine_tab_like);
        RecyclerView grid = findViewById(R.id.hg_mine_grid);
        grid.setLayoutManager(new GridLayoutManager(getContext(), 3));
        grid.setAdapter(adapter);
        findViewById(R.id.hg_mine_login_btn).setOnClickListener(v -> onAccountClick());
        userRow.setOnClickListener(v -> onAccountClick());
        findViewById(R.id.hg_mine_entry_reserve).setOnClickListener(
                v -> HongguoReserveActivity.start(getContext()));
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
            addChip("追更", favFilter == FAV_FOLLOW, v -> {
                favFilter = FAV_FOLLOW;
                renderTab();
            });
        }
    }

    private void addChip(String text, boolean on, OnClickListener click) {
        TextView chip = new TextView(getContext());
        chip.setText(text);
        chip.setTextSize(13);
        chip.setTextColor(on ? 0xFFFF7A1A : 0xFF333333);
        chip.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on ? 0xFFFFF3E8 : 0xFFF5F5F5);
        chip.setBackground(bg);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(click);
        chipsBox.addView(chip);
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
            else if (currentTab == TAB_FAV && favFilter == FAV_FOLLOW) HgStore.setFollowed(id, false);
            else if (currentTab == TAB_FAV) HgStore.setFaved(id, false);
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
                rows.add(new Row(id, meta[0], meta[1], count, shelfMeta(id, count),
                        meta.length > 3 ? meta[3] : null));
            }
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
                // 封面圆角：浅灰底垫＋8dp 圆角裁切（官方 SimpleShortVideoCover
                // 的圆角卡面；旧版直角裸图被他点名「生硬」）
                android.graphics.drawable.GradientDrawable round =
                        new android.graphics.drawable.GradientDrawable();
                round.setColor(0xFFF5F5F5);
                round.setCornerRadius(dp(8));
                cover.setBackground(round);
                cover.setClipToOutline(true);
            }
        }
    }
}
