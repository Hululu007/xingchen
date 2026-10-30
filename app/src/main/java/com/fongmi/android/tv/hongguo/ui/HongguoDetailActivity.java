package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.HongguoCommentApi;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaDetail;
import com.fongmi.android.tv.hongguo.drama.DramaItem;
import com.fongmi.android.tv.hongguo.drama.DramaUserProfile;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 剧详情页（2026-10-30 UI 同步批照定稿整页重排）：整页随海报主色走
 * 暖棕渐变；顶栏返回/分享/竖三点；海报＋剧名＋集数；作者行（无关注钮）；
 * 大收藏钮；三格数据（红果热度/累计收藏/上架时间——数据面没有的整格
 * 不显，不编数：上架时间详情面无源，见 hongguo-ui-sync 账）；基本信息
 * （简介可展开＋标签筹＋出品方）；剧评（分数卡只展示、无打星；评论卡
 * 横排、只看、无点赞心、无写剧评）；相关作品横排（官方 plan 口
 * 547 cell 的同 IP 绑定表＋合集兄弟季补齐，两路皆空整段不显）；
 * 底部固定开始播放＋收藏。详情页无选集入口（定稿）。
 */
public class HongguoDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SERIES_ID = "hg_detail_series";
    public static final String EXTRA_TITLE = "hg_detail_title";
    public static final String EXTRA_ALBUM_ID = "hg_detail_album";

    public static void start(Context context, String seriesId, String title, String albumId) {
        Intent intent = new Intent(context, HongguoDetailActivity.class);
        intent.putExtra(EXTRA_SERIES_ID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        context.startActivity(intent);
    }

    private String seriesId;
    private String albumId;
    private DramaDetail detail;
    private int currentEpisode = 1;

    private FrameLayout rootFrame;
    private TextView titleText;
    private TextView countText;
    private ImageView coverView;
    private LinearLayout authorRow;
    private ImageView authorAvatar;
    private TextView authorName;
    private TextView bigFavBtn;
    private LinearLayout statsRow;
    private TextView descText;
    private TextView descToggle;
    private boolean descExpanded;
    private LinearLayout tagsRow;
    private TextView publisherText;
    private TextView scoreCard;
    private TextView scoreSub;
    private LinearLayout scoreBox;
    private RecyclerView commentList;
    private CommentCardAdapter commentAdapter;
    private LinearLayout relatedBox;
    private RecyclerView relatedList;
    private RelatedAdapter relatedAdapter;
    /** 相关作品两路快照：null＝未回；空表＝回了但无条目。 */
    private List<DramaDetail.RelatedWork> planRelated;
    private List<DramaDetail.RelatedWork> albumRelated;
    private TextView playBtn;
    private ImageView favBtn;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hg-detail");
        t.setDaemon(true);
        return t;
    });

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private GradientDrawable roundBg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HgWindowStyle.apply(this, false, false);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
        albumId = getIntent().getStringExtra(EXTRA_ALBUM_ID);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(0xFF241610);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), statusBarHeight() + dp(6), dp(16), dp(92) + navBarHeight());

        // 顶栏：返回 / 分享 / 竖三点
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(4), dp(8), dp(12), dp(8));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView share = new ImageView(this);
        share.setImageResource(android.R.drawable.ic_menu_share);
        share.setColorFilter(Color.WHITE);
        share.setPadding(dp(8), dp(8), dp(8), dp(8));
        share.setOnClickListener(v -> shareDrama());
        top.addView(share, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView more = new ImageView(this);
        more.setImageResource(R.drawable.ic_drama_more);
        more.setColorFilter(Color.WHITE);
        more.setPadding(dp(8), dp(8), dp(8), dp(8));
        more.setOnClickListener(v -> showMoreSheet());
        top.addView(more, new LinearLayout.LayoutParams(dp(40), dp(40)));
        col.addView(top);

        // 头部：海报＋剧名＋集数
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(14);
        coverView = new ImageView(this);
        coverView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverView.setBackground(roundBg(0x33FFFFFF, dp(8)));
        head.addView(coverView, new LinearLayout.LayoutParams(dp(96), dp(132)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        ilp.leftMargin = dp(14);
        titleText = new TextView(this);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(19);
        titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleText.setMaxLines(3);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        titleText.setText(t == null ? "" : t);
        info.addView(titleText);
        countText = new TextView(this);
        countText.setTextColor(0xCCFFFFFF);
        countText.setTextSize(12.5f);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        info.addView(countText, clp);
        head.addView(info, ilp);
        col.addView(head, hlp);

        // 作者行（头像＋名，无关注钮；有 uid 才可点进作者页）
        authorRow = new LinearLayout(this);
        authorRow.setOrientation(LinearLayout.HORIZONTAL);
        authorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(-1, -2);
        arlp.topMargin = dp(16);
        authorAvatar = new ImageView(this);
        authorAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        authorRow.addView(authorAvatar, new LinearLayout.LayoutParams(dp(28), dp(28)));
        authorName = new TextView(this);
        authorName.setTextColor(0xF2FFFFFF);
        authorName.setTextSize(13.5f);
        authorName.setMaxLines(1);
        authorName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams anlp = new LinearLayout.LayoutParams(0, -2, 1f);
        anlp.leftMargin = dp(9);
        authorRow.addView(authorName, anlp);
        TextView authorArrow = new TextView(this);
        authorArrow.setText("›");
        authorArrow.setTextColor(0x99FFFFFF);
        authorArrow.setTextSize(16);
        authorRow.addView(authorArrow);
        authorRow.setVisibility(View.GONE);
        col.addView(authorRow, arlp);

        // 大收藏钮
        bigFavBtn = new TextView(this);
        bigFavBtn.setGravity(Gravity.CENTER);
        bigFavBtn.setTextSize(15);
        bigFavBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bigFavBtn.setPadding(0, dp(12), 0, dp(12));
        LinearLayout.LayoutParams bflp = new LinearLayout.LayoutParams(-1, -2);
        bflp.topMargin = dp(14);
        bigFavBtn.setOnClickListener(v -> toggleFav());
        col.addView(bigFavBtn, bflp);

        // 三格数据（有数据的格才建；上架时间无源恒不显，见账）
        statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(-1, -2);
        srlp.topMargin = dp(18);
        statsRow.setVisibility(View.GONE);
        col.addView(statsRow, srlp);

        // 基本信息
        col.addView(sectionLabel("基本信息"));
        descText = new TextView(this);
        descText.setTextColor(0xE6FFFFFF);
        descText.setTextSize(13.5f);
        descText.setLineSpacing(0, 1.45f);
        descText.setMaxLines(3);
        descText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(descText);
        descToggle = new TextView(this);
        descToggle.setText("展开");
        descToggle.setTextColor(0x99FFFFFF);
        descToggle.setTextSize(12.5f);
        descToggle.setPadding(0, dp(4), 0, 0);
        descToggle.setVisibility(View.GONE);
        descToggle.setOnClickListener(v -> {
            descExpanded = !descExpanded;
            descText.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
            descToggle.setText(descExpanded ? "收起" : "展开");
        });
        col.addView(descToggle);
        tagsRow = new LinearLayout(this);
        tagsRow.setOrientation(LinearLayout.HORIZONTAL);
        tagsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(-2, -2);
        tglp.topMargin = dp(10);
        col.addView(tagsRow, tglp);
        publisherText = new TextView(this);
        publisherText.setTextColor(0xB3FFFFFF);
        publisherText.setTextSize(12.5f);
        publisherText.setVisibility(View.GONE);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(-1, -2);
        pblp.topMargin = dp(10);
        col.addView(publisherText, pblp);

        // 剧评：分数卡（只展示）＋评论卡横排（只看）
        col.addView(sectionLabel("剧评"));
        scoreBox = new LinearLayout(this);
        scoreBox.setOrientation(LinearLayout.HORIZONTAL);
        scoreBox.setGravity(Gravity.CENTER_VERTICAL);
        scoreBox.setBackground(roundBg(0x26FFFFFF, dp(12)));
        scoreBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        scoreBox.setVisibility(View.GONE);
        scoreCard = new TextView(this);
        scoreCard.setTextColor(Color.WHITE);
        scoreCard.setTextSize(24);
        scoreCard.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        scoreBox.addView(scoreCard);
        scoreSub = new TextView(this);
        scoreSub.setTextColor(0xCCFFFFFF);
        scoreSub.setTextSize(12);
        scoreSub.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(-2, -2);
        sslp.leftMargin = dp(12);
        scoreBox.addView(scoreSub, sslp);
        col.addView(scoreBox);
        commentList = new RecyclerView(this);
        commentList.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        commentList.setNestedScrollingEnabled(false);
        commentAdapter = new CommentCardAdapter();
        commentList.setAdapter(commentAdapter);
        commentList.setVisibility(View.GONE);
        LinearLayout.LayoutParams cmlp = new LinearLayout.LayoutParams(-1, -2);
        cmlp.topMargin = dp(10);
        col.addView(commentList, cmlp);

        // 相关作品（同合集兄弟季横排；无合集整段不显）
        relatedBox = new LinearLayout(this);
        relatedBox.setOrientation(LinearLayout.VERTICAL);
        relatedBox.setVisibility(View.GONE);
        relatedBox.addView(sectionLabel("相关作品"));
        relatedList = new RecyclerView(this);
        relatedList.setLayoutManager(new LinearLayoutManager(this,
                LinearLayoutManager.HORIZONTAL, false));
        relatedList.setNestedScrollingEnabled(false);
        relatedAdapter = new RelatedAdapter();
        relatedList.setAdapter(relatedAdapter);
        relatedBox.addView(relatedList);
        col.addView(relatedBox);

        scroll.addView(col);
        rootFrame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // 底部固定：开始播放＋收藏
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(0xFF1E130C);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10) + navBarHeight());
        playBtn = new TextView(this);
        playBtn.setText("开始播放");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(15.5f);
        playBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setBackground(roundBg(0xFFFF5A4E, dp(12)));
        playBtn.setPadding(0, dp(12), 0, dp(12));
        playBtn.setOnClickListener(v -> {
            if (detail == null) return;
            HongguoFeedActivity.startSeries(this, seriesId, detail.getTitle(), currentEpisode);
        });
        bottom.addView(playBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        favBtn = new ImageView(this);
        favBtn.setImageResource(R.drawable.ic_drama_star);
        favBtn.setPadding(dp(10), dp(10), dp(10), dp(10));
        favBtn.setOnClickListener(v -> toggleFav());
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(dp(48), dp(48));
        fblp.leftMargin = dp(10);
        bottom.addView(favBtn, fblp);
        FrameLayout.LayoutParams blp2 = new FrameLayout.LayoutParams(-1, -2);
        blp2.gravity = Gravity.BOTTOM;
        rootFrame.addView(bottom, blp2);

        setContentView(rootFrame);
        styleFav(HgStore.isFaved(seriesId));
        load();
        loadRelatedWorks();
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------- 动作 ----------

    private void toggleFav() {
        if (detail == null || seriesId == null) return;
        boolean now = HgStore.toggleFaved(seriesId);
        HgStore.putMeta(seriesId, detail.getTitle(), detail.getCoverUrl(),
                detail.getEpisodeCount());
        styleFav(now);
    }

    private void styleFav(boolean faved) {
        if (bigFavBtn != null) {
            bigFavBtn.setText(faved ? "★ 已收藏" : "☆ 收藏");
            bigFavBtn.setTextColor(faved ? 0xFF3D2E00 : Color.WHITE);
            bigFavBtn.setBackground(roundBg(faved ? 0xFFFFC531 : 0x26FFFFFF, dp(12)));
        }
        if (favBtn != null) favBtn.setColorFilter(faved ? 0xFFFFC531 : Color.WHITE);
    }

    private void shareDrama() {
        if (detail == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, detail.getTitle() == null ? ""
                : "我在看《" + detail.getTitle() + "》");
        startActivity(Intent.createChooser(send, "分享"));
    }

    private void showMoreSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundBg(0xFF1C1C1E, dp(18)));
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.addView(sheetRow("分享", v -> {
            dialog.dismiss();
            shareDrama();
        }));
        root.addView(sheetRow("清除本剧观看记录", v -> {
            dialog.dismiss();
            if (seriesId != null) {
                HgStore.removeHistory(seriesId);
                currentEpisode = 1;
                refreshPlayLabel(false);
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.setContentView(root);
        dialog.show();
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) sheet.setBackgroundColor(Color.TRANSPARENT);
    }

    private TextView sheetRow(String text, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(15);
        t.setPadding(0, dp(13), 0, dp(13));
        t.setOnClickListener(click);
        return t;
    }

    // ---------- 数据 ----------

    private void load() {
        if (seriesId == null || seriesId.isEmpty()) {
            Toast.makeText(this, "剧集不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        DramaClient.fetchDetail(seriesId, new HgCallback<DramaDetail>() {
            @Override
            public void onSuccess(DramaDetail d) {
                runOnUiThread(() -> bind(d));
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> Toast.makeText(HongguoDetailActivity.this,
                        "取数据失败，请重试", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void bind(DramaDetail d) {
        detail = d;
        renderRelated();
        applyGradient(d.getColorHex());
        if (d.getTitle() != null) titleText.setText(d.getTitle());
        if (d.getCoverUrl() != null && !d.getCoverUrl().isEmpty()) {
            Glide.with(this).load(d.getCoverUrl())
                    .transform(new RoundedCorners(dp(8))).into(coverView);
        }
        int total = d.getEpisodeCount() > 0 ? d.getEpisodeCount() : d.getEpisodes().size();
        boolean finished = d.getSeriesStatus() != null && d.getSeriesStatus() == 1;
        countText.setText(total > 0 ? HgPanelLogic.countText(finished, total) : "");

        // 作者行：有名才显；有 uid 才可点＋拉头像
        String name = d.getAuthorName();
        if (name != null && !name.isEmpty()) {
            authorRow.setVisibility(View.VISIBLE);
            authorName.setText(name);
            String uid = d.getAuthorUid();
            if (uid != null && !uid.isEmpty()) {
                authorRow.setOnClickListener(v -> HongguoAuthorActivity.start(
                        this, uid, d.getAuthorName(), null));
                DramaClient.fetchUserProfile(uid, new HgCallback<DramaUserProfile>() {
                    @Override
                    public void onSuccess(DramaUserProfile p) {
                        runOnUiThread(() -> {
                            if (p.avatar != null && !p.avatar.isEmpty()) {
                                Glide.with(HongguoDetailActivity.this).load(p.avatar)
                                        .circleCrop().into(authorAvatar);
                            }
                            if ((name == null || name.isEmpty()) && p.name != null) {
                                authorName.setText(p.name);
                            }
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                    }
                });
            }
        }

        // 三格数据（热度/累计收藏有值才建格；上架时间无源不建）
        statsRow.removeAllViews();
        boolean anyStat = false;
        if (d.getHotScore() > 0) {
            addStat(HgTimeTextCount.format(d.getHotScore()), "红果热度");
            anyStat = true;
        }
        if (d.getFollowedCnt() >= 0) {
            addStat(HgTimeTextCount.format(d.getFollowedCnt()), "累计收藏");
            anyStat = true;
        }
        statsRow.setVisibility(anyStat ? View.VISIBLE : View.GONE);

        // 基本信息：简介＋标签筹＋出品方
        descText.setText(d.getDesc() == null || d.getDesc().isEmpty()
                ? "暂无简介" : d.getDesc());
        descToggle.setVisibility(d.getDesc() != null && d.getDesc().length() > 90
                ? View.VISIBLE : View.GONE);
        tagsRow.removeAllViews();
        if (d.getTags() != null && !d.getTags().isEmpty()) {
            tagsRow.setVisibility(View.VISIBLE);
            for (String tag : d.getTags()) {
                TextView chip = new TextView(this);
                chip.setText(tag + " ›");
                chip.setTextColor(0xE6FFFFFF);
                chip.setTextSize(12);
                chip.setBackground(roundBg(0x26FFFFFF, dp(13)));
                chip.setPadding(dp(10), dp(5), dp(10), dp(5));
                // 点搜接通（2026-10-30）：带词直达搜索页出结果
                chip.setOnClickListener(v ->
                        HongguoSearchActivity.startWithKeyword(this, tag));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(8);
                tagsRow.addView(chip, lp);
            }
        } else {
            tagsRow.setVisibility(View.GONE);
        }
        String publisher = publisherOf(d);
        if (publisher != null && !publisher.isEmpty()) {
            publisherText.setText("出品方：" + publisher);
            publisherText.setVisibility(View.VISIBLE);
        } else {
            publisherText.setVisibility(View.GONE);
        }

        HgStore.Progress p = HgStore.progressOf(seriesId);
        currentEpisode = p != null ? Math.max(1, p.episodeIndex) : 1;
        if (total > 0) currentEpisode = Math.min(currentEpisode, total);
        refreshPlayLabel(p != null && p.positionMs > 0);
        styleFav(HgStore.isFaved(seriesId));
        loadComments();
    }

    private void refreshPlayLabel(boolean resume) {
        playBtn.setText(resume ? "继续播放 第" + currentEpisode + "集" : "开始播放");
    }

    private void addStat(String value, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cell.addView(v);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0x99FFFFFF);
        l.setTextSize(11);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = dp(3);
        cell.addView(l, llp);
        statsRow.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    /** 整页渐变：顶色取剧主色（series_color_hex），无则定版暖棕；底恒深棕。 */
    private void applyGradient(String colorHex) {
        int top = 0xFF6E4525;
        if (colorHex != null && !colorHex.isEmpty()) {
            try {
                top = Color.parseColor(colorHex.startsWith("#") ? colorHex : "#" + colorHex);
            } catch (Exception ignored) {
            }
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, 0xFF1E130C});
        rootFrame.setBackground(g);
    }

    /** 出品方：详情模型无此字段，从原回包 video_list[0].copyright 容忍抽取。 */
    private String publisherOf(DramaDetail d) {
        try {
            JsonObject raw = d.getRaw();
            if (raw == null) return null;
            JsonObject data = raw.has("data") && raw.get("data").isJsonObject()
                    ? raw.getAsJsonObject("data") : raw;
            JsonObject vd = data;
            if (!vd.has("video_list")) {
                for (java.util.Map.Entry<String, JsonElement> e : data.entrySet()) {
                    if (e.getValue().isJsonObject()
                            && e.getValue().getAsJsonObject().has("video_list")) {
                        vd = e.getValue().getAsJsonObject();
                        break;
                    }
                }
            }
            JsonArray list = vd.has("video_list") && vd.get("video_list").isJsonArray()
                    ? vd.getAsJsonArray("video_list") : null;
            if (list == null || list.size() == 0 || !list.get(0).isJsonObject()) return null;
            JsonObject first = list.get(0).getAsJsonObject();
            if (first.has("copyright") && first.get("copyright").isJsonPrimitive()) {
                String c = first.get("copyright").getAsString();
                return c == null || c.isEmpty() ? null : c;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 剧评（只看） ----------

    private void loadComments() {
        final String sid = seriesId;
        worker.execute(() -> {
            HongguoCommentApi.Page page;
            try {
                page = HongguoCommentApi.seriesComments(sid, null);
            } catch (Exception e) {
                return;
            }
            if (page == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || detail == null) return;
                if (page.score != null && !page.score.isEmpty()) {
                    scoreBox.setVisibility(View.VISIBLE);
                    scoreCard.setText(page.score);
                    scoreSub.setText("红果评分"
                            + (page.scoreCount > 0
                            ? "\n" + HgTimeTextCount.format(page.scoreCount) + "人评分" : ""));
                }
                if (page.items != null && !page.items.isEmpty()) {
                    commentAdapter.setItems(page.items);
                    commentList.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private class CommentCardAdapter extends RecyclerView.Adapter<CommentCardAdapter.H> {

        private final List<HongguoCommentApi.Comment> data = new ArrayList<>();

        void setItems(List<HongguoCommentApi.Comment> items) {
            data.clear();
            data.addAll(items);
            notifyDataSetChanged();
        }

        class H extends RecyclerView.ViewHolder {
            final ImageView avatar;
            final TextView name;
            final TextView content;

            H(LinearLayout cell) {
                super(cell);
                avatar = cell.findViewWithTag("avatar");
                name = cell.findViewWithTag("name");
                content = cell.findViewWithTag("content");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoDetailActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackground(roundBg(0x26FFFFFF, dp(12)));
            cell.setPadding(dp(12), dp(10), dp(12), dp(10));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(228), dp(104));
            lp.rightMargin = dp(10);
            cell.setLayoutParams(lp);
            LinearLayout headRow = new LinearLayout(HongguoDetailActivity.this);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            ImageView avatar = new ImageView(HongguoDetailActivity.this);
            avatar.setTag("avatar");
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            headRow.addView(avatar, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView name = new TextView(HongguoDetailActivity.this);
            name.setTag("name");
            name.setTextColor(0xE6FFFFFF);
            name.setTextSize(12);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.leftMargin = dp(7);
            headRow.addView(name, nlp);
            cell.addView(headRow);
            TextView content = new TextView(HongguoDetailActivity.this);
            content.setTag("content");
            content.setTextColor(0xD9FFFFFF);
            content.setTextSize(12.5f);
            content.setMaxLines(3);
            content.setEllipsize(android.text.TextUtils.TruncateAt.END);
            content.setLineSpacing(0, 1.35f);
            LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(-1, -2);
            clp2.topMargin = dp(7);
            cell.addView(content, clp2);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            HongguoCommentApi.Comment c = data.get(position);
            h.name.setText(c.author == null ? "" : c.author);
            h.content.setText(c.content == null ? "" : c.content);
            if (c.avatar != null && !c.avatar.isEmpty()) {
                Glide.with(HongguoDetailActivity.this).load(c.avatar)
                        .circleCrop().into(h.avatar);
            } else {
                h.avatar.setImageDrawable(null);
            }
            h.itemView.setOnClickListener(v -> {
                if (detail == null || detail.getEpisodes().isEmpty()) return;
                int idx = Math.min(Math.max(1, currentEpisode), detail.getEpisodes().size()) - 1;
                HgCommentsSheet.show(HongguoDetailActivity.this, seriesId,
                        detail.getEpisodes().get(idx).getVideoId(), detail.getTitle());
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    // ---------- 相关作品（官方 plan 547 同 IP 绑定表＋合集兄弟季补齐） ----------

    /**
     * 两路并行取，回来一路渲一路：plan 547 cell 是官方相关作品本体
     * （同 IP 绑定：其它季＋衍生＋漫剧形态，官方序）；合集兄弟季只补
     * plan 没带到的季（他定版「多少季都在相关作品里面」），不抢序、
     * 不重复。两路皆空整段不显。任一路失败按空表处理（另一路照常）。
     */
    private void loadRelatedWorks() {
        DramaClient.fetchRelatedWorks(seriesId, new HgCallback<List<DramaDetail.RelatedWork>>() {
            @Override
            public void onSuccess(List<DramaDetail.RelatedWork> works) {
                runOnUiThread(() -> {
                    planRelated = works == null ? new ArrayList<>() : works;
                    renderRelated();
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    planRelated = new ArrayList<>();
                    renderRelated();
                });
            }
        });
        if (albumId == null || albumId.isEmpty()) {
            albumRelated = new ArrayList<>();
            return;
        }
        DramaClient.fetchAlbum(albumId, new HgCallback<List<DramaItem>>() {
            @Override
            public void onSuccess(List<DramaItem> seasons) {
                runOnUiThread(() -> {
                    List<DramaDetail.RelatedWork> rows = new ArrayList<>();
                    if (seasons != null) {
                        for (DramaItem s : seasons) {
                            if (s.getSeriesId() != null) {
                                rows.add(DramaDetail.RelatedWork.fromAlbumItem(s));
                            }
                        }
                    }
                    albumRelated = rows;
                    renderRelated();
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    albumRelated = new ArrayList<>();
                    renderRelated();
                });
            }
        });
    }

    /** 合并两路快照渲染：plan 序在前、合集补季在后，按剧 id 去重并排自身。 */
    private void renderRelated() {
        if (relatedAdapter == null) return;
        List<DramaDetail.RelatedWork> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (seriesId != null) seen.add(seriesId);
        if (planRelated != null) {
            for (DramaDetail.RelatedWork w : planRelated) {
                if (w.getSeriesId() != null && seen.add(w.getSeriesId())) merged.add(w);
            }
        }
        if (albumRelated != null) {
            for (DramaDetail.RelatedWork w : albumRelated) {
                if (w.getSeriesId() != null && seen.add(w.getSeriesId())) merged.add(w);
            }
        }
        if (detail != null) detail.attachRelatedWorks(merged);
        if (merged.isEmpty()) {
            relatedBox.setVisibility(View.GONE);
            return;
        }
        relatedAdapter.setItems(merged);
        relatedBox.setVisibility(View.VISIBLE);
    }

    private class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.H> {

        private final List<DramaDetail.RelatedWork> data = new ArrayList<>();

        void setItems(List<DramaDetail.RelatedWork> items) {
            data.clear();
            data.addAll(items);
            notifyDataSetChanged();
        }

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView badge;
            final TextView title;
            final TextView meta;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                badge = cell.findViewWithTag("badge");
                title = cell.findViewWithTag("title");
                meta = cell.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoDetailActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(84), -2);
            lp.rightMargin = dp(10);
            cell.setLayoutParams(lp);
            FrameLayout coverBox = new FrameLayout(HongguoDetailActivity.this);
            ImageView cover = new ImageView(HongguoDetailActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0x33FFFFFF, dp(6)));
            coverBox.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = new TextView(HongguoDetailActivity.this);
            badge.setTag("badge");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(9.5f);
            badge.setBackground(roundBg(0x99000000, dp(4)));
            badge.setPadding(dp(4), dp(1), dp(4), dp(1));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2);
            blp.gravity = Gravity.TOP | Gravity.LEFT;
            blp.leftMargin = dp(4);
            blp.topMargin = dp(4);
            coverBox.addView(badge, blp);
            cell.addView(coverBox, new LinearLayout.LayoutParams(-1, dp(112)));
            TextView title = new TextView(HongguoDetailActivity.this);
            title.setTag("title");
            title.setTextColor(0xF2FFFFFF);
            title.setTextSize(12);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            TextView meta = new TextView(HongguoDetailActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0x99FFFFFF);
            meta.setTextSize(10.5f);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(meta);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaDetail.RelatedWork item = data.get(position);
            if (item.getCoverUrl() != null && !item.getCoverUrl().isEmpty()) {
                Glide.with(HongguoDetailActivity.this).load(item.getCoverUrl())
                        .transform(new RoundedCorners(dp(6))).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            if (item.getTagText() != null && !item.getTagText().isEmpty()) {
                h.badge.setVisibility(View.VISIBLE);
                h.badge.setText(item.getTagText());
            } else {
                h.badge.setVisibility(View.GONE);
            }
            h.title.setText(item.getTitle() == null ? "" : item.getTitle());
            if (item.getEpisodeCount() > 0) {
                h.meta.setText(HgPanelLogic.countText(item.isFinished(), item.getEpisodeCount()));
            } else {
                h.meta.setText(item.getSubText() == null ? "" : item.getSubText());
            }
            h.itemView.setOnClickListener(v -> {
                if (item.isComingSoon()) {
                    HongguoDetailActivity.start(HongguoDetailActivity.this,
                            item.getSeriesId(), item.getTitle(), albumId);
                } else {
                    HongguoFeedActivity.startSeries(HongguoDetailActivity.this,
                            item.getSeriesId(), item.getTitle(), 1);
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    /** 计数简写（与流页同口径，万级一位小数去尾零）。 */
    static final class HgTimeTextCount {
        static String format(long n) {
            if (n < 10000) return String.valueOf(n);
            double w = n / 10000.0;
            String s = w >= 100 ? String.valueOf(Math.round(w)) : String.format(
                    java.util.Locale.US, "%.1f", w).replaceAll("\\.0$", "");
            return s + "万";
        }
    }
}
