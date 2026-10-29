package com.fongmi.android.tv.hongguo.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import android.app.AlertDialog;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoAuthorBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgAuthorWork;
import com.fongmi.android.tv.hongguo.model.HgUserProfile;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果·作者主页（官方 SeriesGuestProfile 构成，2026-10-29 按实拍 113562
 * ＋本机实拉补全）。
 *
 * <p>页头：置顶 chip 行＋头像姓名行（58dp／20sp 粗）＋头衔徽标
 * （user_title_info 的 zh_title/图标，如「已入驻」）＋副标题行
 * （user_tag.sub_title_list 段拼串＋末段「红果号：biz_user_id」，
 * 点行复制红果号原值；2026-10-29 再挖定版，见 HgUserProfile 类注）
 * ＋头像右下 V 标（is_blue_vip＋vip_icon_url）＋简介＋数据行与
 * 关注钮同带；页头底用服务端 data.bg_color/bg_covers。数据行与徽标
 * 数据源＝GET /reading/user/profile/get/（进页回源，名/头像/简介同步
 * 以服务端为准，关注钮初态以 relation_type 校准）。</p>
 *
 * <p>三页签（作品/收藏/点赞，收藏/点赞受服务端隐私开关控制显隐）：
 * 作品＝POST commentapi/user/profile/:uid/v1/（实拉定版）——子档筹由
 * 服务端 tabs 驱动（全部 tab_type=5／剧作 tab_type=11／合集 sub=13／
 * 剧评 sub=121），排序 最新 time_desc／最热 smart_hot_desc，cursor
 * 整串回传翻页；收藏＝GET bookshelf/video/info 的 target_user_id 维度；
 * 点赞＝GET ugc/action/mget action_type=3。三签同走 HgAuthorWorksAdapter
 * 双列卡（剧评子档整宽行带星级）。</p>
 *
 * <p>关注走真接口双路（HgCommentClient.doFollowAction）；状态单一事实
 * 源＝HgStore 作者关注集。底部浮动关注卡：未关注且非本人时出（文案照
 * 实拍），关注后收。</p>
 */
public class HongguoAuthorActivity extends BaseActivity {

    private static final String EXTRA_UID = "hg_author_uid";
    private static final String EXTRA_NAME = "hg_author_name";
    private static final String EXTRA_AVATAR = "hg_author_avatar";
    private static final String EXTRA_DESC = "hg_author_desc";

    public static void start(Context context, String uid, String name, String avatar, String desc) {
        Intent intent = new Intent(context, HongguoAuthorActivity.class);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_AVATAR, avatar);
        intent.putExtra(EXTRA_DESC, desc);
        context.startActivity(intent);
    }

    private static final int TAB_WORKS = 0;
    private static final int TAB_SHELF = 1;
    private static final int TAB_LIKES = 2;

    /** 作品子档规格：tab_type＋sub_tab_type（-1＝不带）。 */
    private static final class SubSpec {
        final String label;
        final int tabType;
        final int subTabType;

        SubSpec(String label, int tabType, int subTabType) {
            this.label = label;
            this.tabType = tabType;
            this.subTabType = subTabType;
        }

        boolean same(SubSpec o) {
            return o != null && tabType == o.tabType && subTabType == o.subTabType;
        }
    }

    private ActivityHongguoAuthorBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String uid;
    private boolean followBusy;
    private boolean ugcOpening;
    private HgUserProfile profile;

    /** 当前是否停在作品签的动态子档（tab_type=5：UGC 视频＋帖子）。 */
    private boolean isDynamicTab() {
        if (tab != TAB_WORKS || subIndex < 0 || subIndex >= subs.size()) return false;
        return subs.get(subIndex).tabType == 5;
    }

    /**
     * 动态 UGC 视频点播：先按官方 UGC 口径（source=17）拉批量详情，
     * 详情自带 video_model 就灌进会话模型缓存，再以该 vid 为「剧」进
     * 集流单播放器面（详情在解析层成唯一一集、解链命中缓存起播）。
     * 预拉失败也照常进页——页内展开会再拉单剧详情、同口径回填模型，
     * 两路都断才落到页面的取链失败态，不在作者页堵死。
     */
    private void openUgcVideo(HgAuthorWork work) {
        String vid = work.id;
        if (vid == null || vid.isEmpty() || ugcOpening) return;
        ugcOpening = true;
        HgFeedSession session = HgFeedSession.get();
        session.client.fetchUgcVideoDetail(vid, new HgCallback<com.fongmi.android.tv.hongguo.model.HgSeriesDetail>() {
            @Override
            public void onSuccess(com.fongmi.android.tv.hongguo.model.HgSeriesDetail detail) {
                mainHandler.post(() -> {
                    ugcOpening = false;
                    if (detail != null && detail.getUgcVideoModel() != null) {
                        com.fongmi.android.tv.hongguo.model.HgVideoModel m =
                                com.fongmi.android.tv.hongguo.model.HgParser.parseUgcVideoModel(
                                        detail.getUgcVideoModel(), vid,
                                        detail.getUgcVideoWidth(), detail.getUgcVideoHeight());
                        if (m != null) session.cache.put(vid, m);
                    }
                    HongguoFeedActivity.startSeries(HongguoAuthorActivity.this,
                            vid, work.title, work.cover, uid, null);
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    ugcOpening = false;
                    HongguoFeedActivity.startSeries(HongguoAuthorActivity.this,
                            vid, work.title, work.cover, uid, null);
                });
            }
        });
    }

    private HgAuthorWorksAdapter adapter;
    private int tab = TAB_WORKS;
    private final List<SubSpec> subs = new ArrayList<>();
    private int subIndex = 0;
    private boolean chipsFromServer;
    private boolean sortHot;
    private String cursor = "";
    private boolean hasMore;
    private boolean loading;
    private boolean loadedOnce;
    private int loadGen;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoAuthorBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        uid = getIntent().getStringExtra(EXTRA_UID);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String avatar = getIntent().getStringExtra(EXTRA_AVATAR);
        String desc = getIntent().getStringExtra(EXTRA_DESC);
        if (uid == null || uid.isEmpty()) {
            finish();
            return;
        }
        binding.hgAuthorChipName.setText(name == null ? "" : name);
        binding.hgAuthorName.setText(name == null ? "" : name);
        bindAvatar(binding.hgAuthorChipAvatar, avatar, name);
        bindAvatar(binding.hgAuthorAvatar, avatar, name);
        bindAvatar(binding.hgAuthorFloatAvatar, avatar, name);
        if (desc != null && !desc.isEmpty()) {
            binding.hgAuthorDesc.setText(desc);
            binding.hgAuthorDesc.setVisibility(View.VISIBLE);
        }
        // 本人门禁的页内侧：自己看自己不亮关注钮（入口侧已拦，这里兜底）
        if (uid.equals(HgStore.profileUid())) binding.hgAuthorFollow.setVisibility(View.GONE);
        binding.hgAuthorFollow.setOnClickListener(v -> onFollowClicked());
        binding.hgAuthorFloatFollow.setOnClickListener(v -> onFollowClicked());
        binding.hgAuthorSubline.setOnClickListener(v -> copyHongguoId());

        subs.add(new SubSpec("全部", 5, -1));
        subs.add(new SubSpec("剧作", 11, -1));
        buildChips();

        adapter = new HgAuthorWorksAdapter(work -> {
            // 剧评行只展示（无独立去处，不摆假跳转）；帖子卡进帖子详情页；
            // 动态（tab_type=5）的视频卡是 UGC 单条视频、走 UGC 取链；
            // 剧作/收藏/点赞的视频卡进剧播放
            if (work.kind == HgAuthorWork.KIND_POST) {
                HongguoPostActivity.start(this, work, uid);
                return;
            }
            if (work.kind != HgAuthorWork.KIND_VIDEO) return;
            if (isDynamicTab() && work.seriesId.isEmpty()) {
                openUgcVideo(work);
                return;
            }
            String sid = work.playSeriesId();
            if (sid == null || sid.isEmpty()) return;
            HongguoFeedActivity.startSeries(this, sid, work.title, work.cover);
        });
        GridLayoutManager glm = new GridLayoutManager(this, 2);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.spanOf(position);
            }
        });
        binding.hgAuthorList.setLayoutManager(glm);
        binding.hgAuthorList.setAdapter(adapter);
        binding.hgAuthorList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || loading || !hasMore) return;
                int last = glm.findLastVisibleItemPosition();
                if (last >= adapter.size() - 4) loadMore();
            }
        });
        binding.hgAuthorEmpty.setOnClickListener(v -> {
            if (!loading && adapter.size() == 0) loadFirst();
        });

        binding.hgAuthorTabWorksBox.setOnClickListener(v -> switchTab(TAB_WORKS));
        binding.hgAuthorTabShelfBox.setOnClickListener(v -> switchTab(TAB_SHELF));
        binding.hgAuthorTabLikesBox.setOnClickListener(v -> switchTab(TAB_LIKES));
        binding.hgAuthorSortNew.setOnClickListener(v -> setSort(false));
        binding.hgAuthorSortHot.setOnClickListener(v -> setSort(true));

        refreshFollowButton();
        refreshProfile();
        loadFirst();
    }

    // ---------- 页头回源 ----------

    /**
     * 资料回源（profile/get 定版口）：数据行三计数照 ct0 填充；名/头像/
     * 简介以服务端为准（入口 extras 只作回源前占位）；关注钮初态以
     * relation_type 校准本地镜像（服务端为准、镜像跟随）；徽标/副标题/
     * 页头底/隐私开关同包带回。回源失败静默，不摆假数。
     */
    private void refreshProfile() {
        HgFeedSession.get().client.fetchUserProfile(uid, new HgCallback<HgUserProfile>() {
            @Override
            public void onSuccess(HgUserProfile p) {
                mainHandler.post(() -> {
                    if (isFinishing() || p == null) return;
                    profile = p;
                    if (p.name != null && !p.name.isEmpty()) {
                        binding.hgAuthorChipName.setText(p.name);
                        binding.hgAuthorName.setText(p.name);
                    }
                    String displayName = binding.hgAuthorName.getText().toString();
                    if (p.avatar != null && !p.avatar.isEmpty()) {
                        bindAvatar(binding.hgAuthorChipAvatar, p.avatar, displayName);
                        bindAvatar(binding.hgAuthorAvatar, p.avatar, displayName);
                        bindAvatar(binding.hgAuthorFloatAvatar, p.avatar, displayName);
                    }
                    if (p.desc != null && !p.desc.isEmpty()) {
                        binding.hgAuthorDesc.setText(p.desc);
                        binding.hgAuthorDesc.setVisibility(View.VISIBLE);
                    }
                    if (p.hasRelationCounts()) {
                        binding.hgAuthorStatFollow.setText(formatStat(p.followUserNum));
                        binding.hgAuthorStatFans.setText(formatStat(p.fansNum));
                        binding.hgAuthorStatDigg.setText(formatStat(p.recvDiggedCount));
                        binding.hgAuthorStats.setVisibility(View.VISIBLE);
                    }
                    // 头衔徽标：有图标图优先摆图，否则摆文字筹
                    if (p.titleBadgeIcon != null && !p.titleBadgeIcon.isEmpty()) {
                        binding.hgAuthorBadgeIcon.setVisibility(View.VISIBLE);
                        binding.hgAuthorBadge.setVisibility(View.GONE);
                        ImgUtil.load(displayName, p.titleBadgeIcon, binding.hgAuthorBadgeIcon);
                    } else if (p.titleBadge != null && !p.titleBadge.isEmpty()) {
                        binding.hgAuthorBadge.setText(p.titleBadge);
                        binding.hgAuthorBadge.setVisibility(View.VISIBLE);
                        binding.hgAuthorBadgeIcon.setVisibility(View.GONE);
                    }
                    if (p.subTitleLine != null && !p.subTitleLine.isEmpty()) {
                        binding.hgAuthorSubline.setText(p.subTitleLine);
                        binding.hgAuthorSubline.setVisibility(View.VISIBLE);
                    }
                    // V 标：认证旗＋服务端图标双齐才显（图色随账号档变，不自绘）
                    if (p.blueVip && p.vipIconUrl != null && !p.vipIconUrl.isEmpty()) {
                        binding.hgAuthorVipBadge.setVisibility(View.VISIBLE);
                        ImgUtil.load("", p.vipIconUrl, binding.hgAuthorVipBadge);
                    } else {
                        binding.hgAuthorVipBadge.setVisibility(View.GONE);
                    }
                    // 页头底（ax8 定版）：bg_color 只作头图衬色垫在图下，
                    // bg_covers 全幅全不透明垫底（390dp 头图层），不再压淡
                    if (p.bgColor != null) {
                        try {
                            binding.hgAuthorBgBox.setBackgroundColor(Color.parseColor(p.bgColor));
                        } catch (Exception ignored) {
                        }
                    }
                    if (p.bgCover != null && !p.bgCover.isEmpty()) {
                        binding.hgAuthorBg.setVisibility(View.VISIBLE);
                        ImgUtil.load("", p.bgCover, binding.hgAuthorBg);
                    }
                    // 隐私开关：作者关掉的签不摆
                    if (!p.shelfOpen) binding.hgAuthorTabShelfBox.setVisibility(View.GONE);
                    if (!p.diggOpen) binding.hgAuthorTabLikesBox.setVisibility(View.GONE);
                    if (p.followedByMe != null) {
                        HgStore.setAuthorFollowed(uid, p.followedByMe);
                        refreshFollowButton();
                    }
                    refreshFloatCard();
                });
            }

            @Override
            public void onError(int code, String msg) {
                // 静默：保留入口数据，数据行维持 GONE
            }
        });
    }

    /** 计数拼一串（官方 SeriesProfileSocialRecordLayout 的数＋单位同串口径）。 */
    private static String formatStat(int n) {
        return com.fongmi.android.tv.hongguo.HgFormat.countLine(n);
    }

    /** 头像：有址圆图，没址用名首字牌（ImgUtil 的 text 占位，与 feed 作者行同一口径）。 */
    private void bindAvatar(android.widget.ImageView view, String avatar, String name) {
        ImgUtil.loadCircle(name, avatar, view);
    }

    private void copyHongguoId() {
        if (profile == null) return;
        String text = profile.hongguoId != null && !profile.hongguoId.isEmpty()
                ? profile.hongguoId : profile.subTitleLine;
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("红果号", text));
        Toast.makeText(this, "红果号已复制", Toast.LENGTH_SHORT).show();
    }

    // ---------- 关注 ----------

    private void refreshFollowButton() {
        boolean followed = HgStore.isAuthorFollowed(uid);
        binding.hgAuthorFollow.setText(followed ? "已关注" : "关注");
        // 官方 csp 的 fox 定版：未关注＝白底圆角 8dp＋橘字 #FA7705＋加号图标；
        // 已关注＝半透明白底＋白字、无加号
        binding.hgAuthorFollow.setBackgroundResource(followed
                ? com.fongmi.android.tv.R.drawable.bg_hg_author_followed_btn
                : com.fongmi.android.tv.R.drawable.bg_hg_author_follow_btn);
        binding.hgAuthorFollow.setTextColor(followed ? 0xFFFFFFFF : 0xFFFA7705);
        binding.hgAuthorFollow.setCompoundDrawablesWithIntrinsicBounds(
                followed ? null : androidx.core.content.ContextCompat.getDrawable(
                        this, com.fongmi.android.tv.R.drawable.ic_hg_author_plus),
                null, null, null);
        refreshFloatCard();
    }

    /** 浮动关注卡：未关注、非本人、资料已回源（或本地镜像未关注）才出；已关注即收。 */
    private void refreshFloatCard() {
        if (binding == null) return;
        boolean self = uid != null && uid.equals(HgStore.profileUid());
        boolean followed = HgStore.isAuthorFollowed(uid);
        binding.hgAuthorFloat.setVisibility(!self && !followed ? View.VISIBLE : View.GONE);
    }

    private void onFollowClicked() {
        if (followBusy) return;
        // 未登录先拉壳子统一登录（与 feed 互动同门），不落状态不假成功
        if (HgFeedSession.get().interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (HgStore.isAuthorFollowed(uid)) {
            // 取关确认框：官方 PugcFollowButton$onClick$1$1 文案逐字
            new AlertDialog.Builder(this)
                    .setMessage("不再关注该作者？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("不再关注", (d, w) -> applyFollow(false))
                    .show();
        } else {
            applyFollow(true);
        }
    }

    /** 乐观先行＋真接口收口：失败回滚镜像并如实提示（互动层同口径）。 */
    private void applyFollow(boolean follow) {
        followBusy = true;
        HgStore.setAuthorFollowed(uid, follow);
        refreshFollowButton();
        HgFeedSession.get().fqClient.doFollowAction(follow, uid, new HgCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                mainHandler.post(() -> {
                    followBusy = false;
                    refreshFollowButton();
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    followBusy = false;
                    HgStore.setAuthorFollowed(uid, !follow);
                    refreshFollowButton();
                    Toast.makeText(HongguoAuthorActivity.this,
                            follow ? "关注没成功，再试试" : "取关没成功，再试试",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ---------- 页签/子档/排序 ----------

    private void switchTab(int target) {
        if (tab == target) return;
        tab = target;
        paintTabs();
        loadFirst();
    }

    private void paintTabs() {
        paintTab(binding.hgAuthorTabWorks, binding.hgAuthorTabWorksLine, tab == TAB_WORKS);
        paintTab(binding.hgAuthorTabShelf, binding.hgAuthorTabShelfLine, tab == TAB_SHELF);
        paintTab(binding.hgAuthorTabLikes, binding.hgAuthorTabLikesLine, tab == TAB_LIKES);
        binding.hgAuthorSubrow.setVisibility(tab == TAB_WORKS ? View.VISIBLE : View.GONE);
    }

    private void paintTab(TextView tv, View line, boolean on) {
        tv.setTextColor(on ? 0xFF1D2129 : 0xFF86909C);
        tv.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        line.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    private void setSort(boolean hot) {
        if (sortHot == hot) return;
        sortHot = hot;
        binding.hgAuthorSortNew.setTextColor(hot ? 0xFF1D2129 : 0xFFFF7E0D);
        binding.hgAuthorSortHot.setTextColor(hot ? 0xFFFF7E0D : 0xFF1D2129);
        if (tab == TAB_WORKS) loadFirst();
    }

    private void buildChips() {
        binding.hgAuthorChips.removeAllViews();
        for (int i = 0; i < subs.size(); i++) {
            SubSpec spec = subs.get(i);
            TextView chip = new TextView(this);
            chip.setText(spec.label);
            chip.setTextSize(13);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(8));
            chip.setLayoutParams(lp);
            final int index = i;
            chip.setOnClickListener(v -> {
                if (subIndex == index) return;
                subIndex = index;
                buildChips();
                loadFirst();
            });
            boolean on = i == subIndex;
            chip.setBackgroundResource(on
                    ? com.fongmi.android.tv.R.drawable.bg_hg_sub_chip_on
                    : com.fongmi.android.tv.R.drawable.bg_hg_sub_chip);
            chip.setTextColor(on ? 0xFFFF7E0D : 0xFF4E5969);
            binding.hgAuthorChips.addView(chip);
        }
    }

    /** 服务端 tabs 到货后重建子档：全部（固定首位）＋剧作（固定）＋服务端其余子档。 */
    private void rebuildSubsFromServer(List<HgAuthorWork.SubTab> serverSubs) {
        if (chipsFromServer || serverSubs == null || serverSubs.isEmpty()) return;
        chipsFromServer = true;
        SubSpec current = subs.get(subIndex);
        List<SubSpec> next = new ArrayList<>();
        next.add(new SubSpec("全部", 5, -1));
        next.add(new SubSpec("剧作", 11, -1));
        for (HgAuthorWork.SubTab st : serverSubs) {
            if (st.tabType <= 0 || st.tabType == 100) continue;
            String label = st.name == null || st.name.isEmpty()
                    ? (st.tabType == 13 ? "合集" : st.tabType == 121 ? "剧评" : "其他")
                    : st.name;
            next.add(new SubSpec(label, 5, st.tabType));
        }
        subs.clear();
        subs.addAll(next);
        subIndex = 0;
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i).same(current)) {
                subIndex = i;
                break;
            }
        }
        buildChips();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- 列表加载 ----------

    private void loadFirst() {
        loadGen++;
        cursor = "";
        hasMore = false;
        loadedOnce = false;
        adapter.setItems(null);
        showEmpty("加载中…");
        requestPage(true);
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        requestPage(false);
    }

    private void requestPage(boolean first) {
        loading = true;
        final int gen = loadGen;
        HgCallback<HgAuthorWork.Page> cb = new HgCallback<HgAuthorWork.Page>() {
            @Override
            public void onSuccess(HgAuthorWork.Page page) {
                mainHandler.post(() -> {
                    if (isFinishing() || gen != loadGen) return;
                    loading = false;
                    loadedOnce = true;
                    if (page.subTabs != null && !page.subTabs.isEmpty()) {
                        rebuildSubsFromServer(page.subTabs);
                    }
                    if (first) adapter.setItems(page.items);
                    else adapter.addItems(page.items);
                    cursor = page.nextCursor;
                    hasMore = page.hasMore && !page.nextCursor.isEmpty();
                    if (adapter.size() == 0) showEmpty(emptyText());
                    else hideEmpty();
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    if (isFinishing() || gen != loadGen) return;
                    loading = false;
                    if (adapter.size() == 0) showEmpty("加载失败，点这里重试");
                });
            }
        };
        if (tab == TAB_WORKS) {
            SubSpec spec = subs.get(subIndex);
            HgFeedSession.get().fqClient.authorProfileList(uid, spec.tabType, spec.subTabType,
                    sortHot ? "smart_hot_desc" : "time_desc", cursor, cb);
        } else if (tab == TAB_SHELF) {
            HgFeedSession.get().client.authorShelf(uid, parseOffset(cursor), cb);
        } else {
            HgFeedSession.get().client.authorLikes(uid, parseOffset(cursor), cb);
        }
    }

    private static int parseOffset(String cursor) {
        if (cursor == null || cursor.isEmpty()) return 0;
        try {
            return Math.max(0, Integer.parseInt(cursor.trim()));
        } catch (Exception e) {
            return 0;
        }
    }

    private String emptyText() {
        if (tab == TAB_SHELF) return "还没有收藏";
        if (tab == TAB_LIKES) return "还没有点赞";
        SubSpec spec = subs.get(subIndex);
        if (spec.tabType == 11) return "还没有剧作";
        if (spec.subTabType == 121) return "还没有剧评";
        return "还没有作品";
    }

    private void showEmpty(String text) {
        binding.hgAuthorEmpty.setText(text);
        binding.hgAuthorEmpty.setVisibility(View.VISIBLE);
    }

    private void hideEmpty() {
        binding.hgAuthorEmpty.setVisibility(View.GONE);
    }
}
