package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.databinding.ActivityHongguoSeriesDetailBinding;
import com.fongmi.android.tv.databinding.ItemHongguoSeriesReviewBinding;
import com.fongmi.android.tv.hongguo.HgFormat;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgCommentClient;
import com.fongmi.android.tv.hongguo.model.HgComment;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.hongguo.model.HgSeriesRating;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 红果·剧详情页（2026-10-29 按实拍 113563 落地；集流页剧名行点入，与
 * 作者页是两个去处——用户亲授语义）。
 *
 * <p>数据全走已定版口：详情＝fetchVideoDetail（封面取色 series_color_hex、
 * 副标题段 series_sub_title_list、热度 hot_score、标签 category_schema、
 * 收藏态 followed/收藏数 followed_cnt 都在 video_data 里）；剧评与评分卡
 * ＝整剧评论口同一回包（条目 expand.score 十分制、extra 的
 * credibility_score 红果分/collectors_count 收藏数/can_comment_threshold
 * 参与门槛，本机实拉验通）。集格点选＝写 HgStore 断点后走集流进剧
 * （与选集单同一落点口径）；底栏收藏＝bookshelf/video/update 真口
 * 乐观先行＋失败回滚；继续播放＝进集流（断点由集流侧续）。</p>
 *
 * <p>参与评分（2026-10-29 补）：评分卡五星可点（整星，十分制＝星数×2）、
 * 提交走 comment/add（commit_source=12 纯打分体，p12/w.e 定版）、已评回显
 * 走整剧评论回包 extra.user_comment、改分走 comment/update；门槛是端上
 * 校验（官方 CSSPlayletCommentListView 同口径：can_comment_threshold 秒
 * 对 HgStore 按剧累计观看，累计由播放页按位置增量记账），watch_duration
 * 随体上送。两口 2026-10-29 本机实拉只差登录（code=103006「用户未登录」），
 * 成功回包待首登验。半星是官方实验旗能力、咱家只做整星。</p>
 *
 * <p>顶右分享＝系统分享（文案与集流页分享同口径「我在看《剧名》」——官方
 * 分享内容由服务端分享模型驱动、包内无客户端文案模板）；更多＝官方「⋯」
 * 的不感兴趣形态（do_action Dislike，2026-10-29 实拉匿名受理）；举报形态
 * 走字节 spam SDK 自有链、请求形状未验通，不摆。</p>
 */
public class HongguoSeriesDetailActivity extends BaseActivity {

    private static final String EXTRA_SID = "hg_sd_series_id";
    private static final String EXTRA_TITLE = "hg_sd_title";
    private static final String EXTRA_COVER = "hg_sd_cover";

    public static void start(Context context, String seriesId, String title, String cover) {
        Intent intent = new Intent(context, HongguoSeriesDetailActivity.class);
        intent.putExtra(EXTRA_SID, seriesId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        context.startActivity(intent);
    }

    private static final int SEGMENT = 30;

    private ActivityHongguoSeriesDetailBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String seriesId;
    private String bgUrl;
    private HgSeriesDetail detail;
    private boolean fav;
    private boolean favBusy;
    private boolean descExpanded;
    private boolean descExpandable;
    private String fullDesc = "";
    private int segment;
    // 参与评分：本人已评（十分制，-1 未评）、本人评论 id（改分用）、
    // 当前点选的待提交分（0 未选）、门槛秒（回包 extra）、提交在途旗
    private int myScore = -1;
    private String myCommentId = "";
    private int pendingScore;
    private int thresholdSec = -1;
    private boolean scoreBusy;
    private final ImageView[] starViews = new ImageView[5];

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoSeriesDetailBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        seriesId = getIntent().getStringExtra(EXTRA_SID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String cover = getIntent().getStringExtra(EXTRA_COVER);
        if (seriesId == null || seriesId.isEmpty()) {
            finish();
            return;
        }
        if (title != null) binding.hgSdTitle.setText(title);
        if (cover != null && !cover.isEmpty()) {
            ImgUtil.load(title == null ? "" : title, cover, binding.hgSdCover);
            loadBlurredBg(cover);
        }
        HgStore.Progress p = HgStore.progressOf(seriesId);
        binding.hgSdPlayText.setText(p != null && p.episodeIndex > 0 ? "继续播放" : "开始播放");
        binding.hgSdPlay.setOnClickListener(v ->
                HongguoFeedActivity.startSeries(this, seriesId,
                        binding.hgSdTitle.getText().toString(),
                        detail != null ? detail.getCoverUrl() : cover));
        binding.hgSdFav.setOnClickListener(v -> toggleFav());
        // 剧集状态行点按：集条滚到当前集（官方 › 的确切去向未抠到，此为咱家口径）
        binding.hgSdEpStatus.setOnClickListener(v -> scrollStripToCurrent());
        // 集条滚动反推当前分段（手指滑条时页签高亮跟着走）
        binding.hgSdEpStrip.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (detail == null || detail.getEpisodes().isEmpty()) return;
            int pitch = epCellSide() + dp(4);
            if (pitch <= 0) return;
            int idx = (binding.hgSdEpStrip.getScrollX() + pitch / 2) / pitch;
            int segments = (detail.getEpisodes().size() + SEGMENT - 1) / SEGMENT;
            int seg = Math.min(segments - 1, Math.max(0, idx / SEGMENT));
            if (seg != segment) {
                segment = seg;
                paintSegmentTabs();
            }
        });
        binding.hgSdDesc.setOnClickListener(v -> {
            if (!descExpandable && !descExpanded) return;
            descExpanded = !descExpanded;
            paintDesc();
        });
        // 评分卡五星逐颗建（点按分颗接）：整星，点第 i 颗＝(i+1)×2 十分制；
        // 星面走矢量图（ic_hg_star 着色），不用 ★ 字形（字形图标清零口径）
        for (int i = 0; i < starViews.length; i++) {
            ImageView star = new ImageView(this);
            star.setImageResource(com.fongmi.android.tv.R.drawable.ic_hg_star);
            star.setPadding(dp(2), dp(2), dp(2), dp(2));
            android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(dp(27), dp(27));
            star.setLayoutParams(slp);
            final int idx = i;
            star.setOnClickListener(v -> {
                if (scoreBusy) return;
                pendingScore = (idx + 1) * 2;
                paintStars();
                paintScoreAction();
            });
            starViews[i] = star;
            binding.hgSdStars.addView(star);
        }
        binding.hgSdScoreAction.setOnClickListener(v -> submitScore());
        binding.hgSdShare.setOnClickListener(v -> shareSeries());
        binding.hgSdMore.setOnClickListener(v -> showMoreMenu());
        paintStars();
        paintScoreAction();
        loadDetail();
        loadReviews();
    }

    private void loadDetail() {
        HgFeedSession.get().client.fetchVideoDetail(seriesId, new HgCallback<HgSeriesDetail>() {
            @Override
            public void onSuccess(HgSeriesDetail d) {
                mainHandler.post(() -> {
                    if (isFinishing() || d == null) return;
                    detail = d;
                    bindDetail(d);
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    Toast.makeText(HongguoSeriesDetailActivity.this,
                            "详情没拉到，稍后再试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void bindDetail(HgSeriesDetail d) {
        if (d.getTitle() != null && !d.getTitle().isEmpty()) binding.hgSdTitle.setText(d.getTitle());
        if (d.getCoverUrl() != null && !d.getCoverUrl().isEmpty()) {
            ImgUtil.load(d.getTitle(), d.getCoverUrl(), binding.hgSdCover);
            loadBlurredBg(d.getCoverUrl());
        }
        if (d.getColorHex() != null) {
            try {
                int c = Color.parseColor(d.getColorHex());
                binding.hgSdScrim.setBackgroundColor(c & 0x00FFFFFF | 0x59000000);
                binding.getRoot().setBackgroundColor(
                        Color.rgb(Math.max(0, Color.red(c) - 24),
                                Math.max(0, Color.green(c) - 24),
                                Math.max(0, Color.blue(c) - 24)));
                // 播放钮取剧色亮版（官方 113563：页面底是剧色压暗、钮是同族
                // 亮金，采样差约 +72/+62/+28；单样本推断，无剧色时用布局素金兜底）
                android.graphics.drawable.GradientDrawable playBg =
                        new android.graphics.drawable.GradientDrawable();
                playBg.setCornerRadius(dp(10));
                playBg.setColor(Color.rgb(
                        Math.min(255, Color.red(c) + 64),
                        Math.min(255, Color.green(c) + 56),
                        Math.min(255, Color.blue(c) + 26)));
                binding.hgSdPlay.setBackground(playBg);
            } catch (Exception ignored) {
            }
        }
        // 副标题段：服务端拼好的段优先（全X集/红果热度值），缺了自己拼
        String subline;
        if (!d.getSubTitles().isEmpty()) {
            subline = String.join(" · ", d.getSubTitles());
        } else {
            StringBuilder sb = new StringBuilder();
            if (d.getEpisodeCount() > 0) sb.append("全").append(d.getEpisodeCount()).append("集");
            if (d.getHotScore() > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("红果热度值").append(HgFormat.countLine(d.getHotScore()));
            }
            subline = sb.toString();
        }
        binding.hgSdSubline.setText(subline);
        // 标签筹（官方 113563：白透底、与「简介」标同行）
        binding.hgSdTags.removeAllViews();
        for (String tagName : d.getTags()) {
            TextView chip = new TextView(this);
            chip.setText(tagName);
            chip.setTextSize(12);
            chip.setTextColor(0xE6FFFFFF);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackgroundResource(com.fongmi.android.tv.R.drawable.bg_hg_detail_chip);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            binding.hgSdTags.addView(chip);
        }
        binding.hgSdTags.setVisibility(d.getTags().isEmpty() ? View.GONE : View.VISIBLE);
        // 简介（展开/收起在正文行尾内嵌，官方 113563 同款）
        fullDesc = d.getDesc() == null ? "" : d.getDesc();
        descExpanded = false;
        paintDesc();
        // 剧集状态行（官方 113563：「已完结 共N集 ›」空格分隔、带 ›；
        // 更新中此图无样本、按同构写「更新至N集 ›」）
        Integer status = d.getSeriesStatus();
        int count = d.getEpisodeCount() > 0 ? d.getEpisodeCount() : d.getEpisodes().size();
        String statusText;
        if (status != null && status == 1) statusText = "已完结 共" + count + "集 ›";
        else if (status != null && status == 0) statusText = "更新至" + count + "集 ›";
        else statusText = "共" + count + "集 ›";
        binding.hgSdEpStatus.setText(statusText);
        buildEpisodes(d);
        // 收藏态以服务端为准
        fav = d.isFollowed();
        paintFav();
    }

    /**
     * 简介正文：收起态最多 3 行，被截断时把第 3 行行尾截出位置内嵌蓝色
     * 「展开」（官方 113563 是行尾同行，不是另起一行）；展开态全文尾随
     * 蓝色「收起」。点正文切换（descExpandable/descExpanded 守卫）。
     */
    private void paintDesc() {
        TextView view = binding.hgSdDesc;
        String desc = fullDesc;
        if (descExpanded) {
            view.setMaxLines(Integer.MAX_VALUE);
            android.text.SpannableString ss = new android.text.SpannableString(desc + "  收起");
            ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF6FB3FF),
                    desc.length() + 2, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(ss);
            return;
        }
        view.setMaxLines(3);
        view.setText(desc);
        descExpandable = false;
        view.post(() -> {
            if (descExpanded || !desc.equals(fullDesc)) return;
            android.text.Layout layout = view.getLayout();
            if (layout == null || layout.getLineCount() < 3) return;
            int lineStart = layout.getLineStart(2);
            int lineEnd = layout.getLineEnd(2);
            if (layout.getEllipsisCount(2) <= 0 && lineEnd >= desc.length()) return;
            String suffix = "… 展开";
            float avail = view.getWidth() - view.getPaddingStart() - view.getPaddingEnd()
                    - view.getPaint().measureText(suffix);
            String line3 = desc.substring(lineStart, Math.min(lineEnd, desc.length()));
            int fit = view.getPaint().breakText(line3, true, Math.max(avail, 0), null);
            if (fit <= 0) return;
            String head = desc.substring(0, lineStart + fit);
            android.text.SpannableString ss = new android.text.SpannableString(head + suffix);
            ss.setSpan(new android.text.style.ForegroundColorSpan(0xFF6FB3FF),
                    head.length() + 2, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(ss);
            descExpandable = true;
        });
    }

    /**
     * 背景垫图（官方 113563 口径：重虚化＋剧色压暗的平洗面，不是原图直垫）：
     * 封面位图先缩到约 54px 宽再两遍 FastBlur，海报细节全化掉只剩色块；
     * 压暗由 hg_sd_scrim 的剧色罩承担。bgUrl 守卫防详情回包换图时旧图回贴。
     */
    private void loadBlurredBg(String url) {
        if (url == null || url.isEmpty() || url.equals(bgUrl)) return;
        bgUrl = url;
        ImgUtil.load(url, 96, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                if (isFinishing() || !url.equals(bgUrl)) return;
                try {
                    int w = 54;
                    int h = Math.max(1, Math.round(resource.getHeight() * (w / (float) resource.getWidth())));
                    Bitmap small = Bitmap.createScaledBitmap(resource, w, h, true);
                    Bitmap blurred = jp.wasabeef.glide.transformations.internal.FastBlur.blur(
                            jp.wasabeef.glide.transformations.internal.FastBlur.blur(small, 25, false), 25, false);
                    binding.hgSdBg.setImageBitmap(blurred);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    // ---------- 集格（官方 113563：横滑单行方格条＋文字分段页签） ----------

    /** 方格边长：按屏宽算（(屏宽dp－56)/6，他机实测 ≈61dp 正方）。 */
    private int epCellSide() {
        float density = getResources().getDisplayMetrics().density;
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels / density);
        return dp(Math.max(48, (screenDp - 56) / 6));
    }

    private void paintSegmentTabs() {
        for (int i = 0; i < binding.hgSdSegments.getChildCount(); i++) {
            TextView tab = (TextView) binding.hgSdSegments.getChildAt(i);
            boolean on = i == segment;
            tab.setTextColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            tab.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void scrollStripToEp(int epNum) {
        int idx = epNum - 1;
        if (idx < 0 || idx >= binding.hgSdEpCells.getChildCount()) return;
        int x = idx * (epCellSide() + dp(4));
        binding.hgSdEpStrip.post(() ->
                binding.hgSdEpStrip.smoothScrollTo(Math.max(0, x - dp(2)), 0));
    }

    private void scrollStripToCurrent() {
        HgStore.Progress p = HgStore.progressOf(seriesId);
        if (p != null && p.episodeIndex > 0) scrollStripToEp(p.episodeIndex);
    }

    private void buildEpisodes(HgSeriesDetail d) {
        List<HgSeriesDetail.Episode> eps = d.getEpisodes();
        binding.hgSdSegments.removeAllViews();
        binding.hgSdEpCells.removeAllViews();
        if (eps.isEmpty()) return;
        int segments = (eps.size() + SEGMENT - 1) / SEGMENT;
        HgStore.Progress p = HgStore.progressOf(seriesId);
        int currentEp = p == null ? -1 : p.episodeIndex;
        if (currentEp > 0) segment = Math.min(segments - 1, (currentEp - 1) / SEGMENT);
        binding.hgSdSegmentsScroll.setVisibility(segments <= 1 ? View.GONE : View.VISIBLE);
        if (segments > 1) {
            for (int s = 0; s < segments; s++) {
                int from = s * SEGMENT + 1;
                int to = Math.min(eps.size(), (s + 1) * SEGMENT);
                TextView tab = new TextView(this);
                tab.setText(from + "-" + to);
                tab.setTextSize(15);
                tab.setPadding(dp(2), dp(6), dp(2), dp(6));
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                if (s > 0) lp.setMarginStart(dp(26));
                tab.setLayoutParams(lp);
                final int seg = s;
                tab.setOnClickListener(v -> {
                    segment = seg;
                    paintSegmentTabs();
                    scrollStripToEp(seg * SEGMENT + 1);
                });
                binding.hgSdSegments.addView(tab);
            }
            paintSegmentTabs();
        }
        int side = epCellSide();
        for (int i = 0; i < eps.size(); i++) {
            HgSeriesDetail.Episode ep = eps.get(i);
            int epNum = ep.getIndex() > 0 ? ep.getIndex() : i + 1;
            boolean current = epNum == currentEp;
            FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundResource(current
                    ? com.fongmi.android.tv.R.drawable.bg_hg_detail_current
                    : com.fongmi.android.tv.R.drawable.bg_hg_detail_cell);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(side, side);
            if (i > 0) lp.setMarginStart(dp(4));
            cell.setLayoutParams(lp);
            TextView num = new TextView(this);
            num.setText(String.valueOf(epNum));
            num.setTextSize(16);
            num.setGravity(android.view.Gravity.CENTER);
            num.setTextColor(0xFFFFFFFF);
            if (current) num.setTypeface(null, android.graphics.Typeface.BOLD);
            cell.addView(num, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            if (current) {
                ImageView bars = new ImageView(this);
                bars.setImageResource(com.fongmi.android.tv.R.drawable.ic_hg_now_playing);
                bars.setContentDescription(null);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(11), dp(11));
                blp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
                blp.setMargins(0, dp(4), dp(4), 0);
                bars.setLayoutParams(blp);
                cell.addView(bars);
            }
            final int targetEp = epNum;
            cell.setOnClickListener(v -> {
                HgStore.saveProgress(seriesId, targetEp, 0);
                HongguoFeedActivity.startSeries(this, seriesId,
                        binding.hgSdTitle.getText().toString(),
                        detail != null ? detail.getCoverUrl() : null);
            });
            binding.hgSdEpCells.addView(cell);
        }
        if (currentEp > 0) scrollStripToEp(currentEp);
    }

    // ---------- 剧评/评分卡 ----------

    private void loadReviews() {
        HgFeedSession.get().fqClient.seriesReviews(seriesId, new HgCallback<HgCommentClient.SeriesReviews>() {
            @Override
            public void onSuccess(HgCommentClient.SeriesReviews r) {
                mainHandler.post(() -> {
                    if (isFinishing() || r == null) return;
                    bindRating(r.rating);
                    List<HgComment> scored = new ArrayList<>();
                    for (HgComment c : r.page.items) {
                        if (c.score >= 0) scored.add(c);
                    }
                    if (!scored.isEmpty()) {
                        binding.hgSdReviewsTitle.setVisibility(View.VISIBLE);
                        String t = "剧评";
                        if (r.rating.scoreCount > 0) t += " · " + r.rating.scoreCount;
                        binding.hgSdReviewsTitle.setText(t);
                        binding.hgSdReviews.setVisibility(View.VISIBLE);
                        binding.hgSdReviews.setLayoutManager(new LinearLayoutManager(
                                HongguoSeriesDetailActivity.this, RecyclerView.HORIZONTAL, false));
                        binding.hgSdReviews.setAdapter(new ReviewAdapter(scored));
                    } else if (r.rating.hasAny()) {
                        binding.hgSdReviewsTitle.setVisibility(View.VISIBLE);
                        binding.hgSdReviewsTitle.setText("剧评");
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
                // 静默：剧评区整段不摆
            }
        });
    }

    private void bindRating(HgSeriesRating rating) {
        if (rating == null || !rating.hasAny()) return;
        binding.hgSdRating.setVisibility(View.VISIBLE);
        if (rating.credibilityScore >= 0) {
            binding.hgSdScoreBig.setTextSize(24);
            binding.hgSdScoreBig.setText(String.format(Locale.US, "%.1f", rating.credibilityScore));
        } else {
            binding.hgSdScoreBig.setTextSize(17);
            binding.hgSdScoreBig.setText("未开分");
        }
        if (rating.collectorsCount >= 0) {
            binding.hgSdCollectors.setText(HgFormat.countLine(rating.collectorsCount) + "人收藏");
        }
        int th = rating.canCommentThresholdSec;
        thresholdSec = th;
        if (th >= 60 && th % 60 == 0) {
            binding.hgSdThreshold.setText("看" + (th / 60) + "分钟参与评分");
        } else if (th > 0) {
            binding.hgSdThreshold.setText("看" + th + "秒参与评分");
        } else {
            binding.hgSdThreshold.setText("");
        }
        // 已评回显（extra.user_comment）：服务端为准刷新本人态
        if (rating.myScore >= 0) {
            myScore = rating.myScore;
            myCommentId = rating.myCommentId;
            if (pendingScore == myScore) pendingScore = 0;
        }
        paintStars();
        paintScoreAction();
    }

    // ---------- 参与评分 ----------

    /** 星面：待提交分优先、否则本人已评分、再否则全灰。 */
    private void paintStars() {
        int shown = pendingScore > 0 ? pendingScore : Math.max(myScore, 0);
        int filled = Math.round(shown / 2f);
        for (int i = 0; i < starViews.length; i++) {
            if (starViews[i] == null) continue;
            starViews[i].setColorFilter(i < filled ? 0xFFFFC53D : 0x59FFFFFF);
        }
    }

    /** 状态/提交行：提交中／提交评分／修改评分／已评分／点击评分 五态。 */
    private void paintScoreAction() {
        TextView action = binding.hgSdScoreAction;
        if (action == null) return;
        if (scoreBusy) {
            action.setText("提交中…");
            action.setTextColor(0x99FFFFFF);
        } else if (pendingScore > 0 && pendingScore != myScore) {
            action.setText(myScore >= 0 ? "修改评分" : "提交评分");
            action.setTextColor(0xFFFFC53D);
        } else if (myScore >= 0) {
            action.setText("已评分");
            action.setTextColor(0xBFFFFFFF);
        } else {
            action.setText("点击评分");
            action.setTextColor(0xBFFFFFFF);
        }
    }

    private void submitScore() {
        if (scoreBusy) return;
        if (pendingScore <= 0 || pendingScore == myScore) {
            if (myScore < 0) Toast.makeText(this, "请先选择打分等级", Toast.LENGTH_SHORT).show();
            return;
        }
        if (HgFeedSession.get().interactions.requiresLogin()) {
            promptLogin();
            return;
        }
        // 门槛在端上（官方同口径）：累计观看没到门槛不发请求
        long watchedSec = HgStore.watchMsOf(seriesId) / 1000;
        if (thresholdSec > 0 && watchedSec < thresholdSec) {
            long remainMin = Math.max(1, (long) Math.ceil((thresholdSec - watchedSec) / 60.0));
            Toast.makeText(this, "还需观看" + remainMin + "分钟才能评分", Toast.LENGTH_SHORT).show();
            return;
        }
        scoreBusy = true;
        paintScoreAction();
        final int score = pendingScore;
        HgCallback<Void> cb = new HgCallback<Void>() {
            @Override
            public void onSuccess(Void v) {
                mainHandler.post(() -> {
                    scoreBusy = false;
                    myScore = score;
                    pendingScore = 0;
                    paintStars();
                    paintScoreAction();
                    Toast.makeText(HongguoSeriesDetailActivity.this, "评分已提交", Toast.LENGTH_SHORT).show();
                    loadReviews();
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    scoreBusy = false;
                    paintScoreAction();
                    if (code == HgCommentClient.CODE_NOT_LOGIN) {
                        promptLogin();
                        return;
                    }
                    Toast.makeText(HongguoSeriesDetailActivity.this, "评分没成功，再试试", Toast.LENGTH_SHORT).show();
                });
            }
        };
        if (myScore >= 0 && !myCommentId.isEmpty()) {
            HgFeedSession.get().fqClient.updateScore(myCommentId, seriesId, score, watchedSec, cb);
        } else {
            HgFeedSession.get().fqClient.submitScore(seriesId, score, watchedSec, cb);
        }
    }

    private void promptLogin() {
        boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
        if (!delivered) Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
    }

    // ---------- 分享/更多 ----------

    /** 系统分享：文案与集流页分享同口径（官方分享内容由服务端模型驱动、无客户端模板）。 */
    private void shareSeries() {
        String title = binding.hgSdTitle.getText() == null ? "" : binding.hgSdTitle.getText().toString().trim();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, title.isEmpty() ? "红果短剧" : "我在看《" + title + "》");
        startActivity(Intent.createChooser(send, "分享"));
    }

    /** 更多小单：只放「不感兴趣」一项真功能（举报形态未验通不摆，见类头账）。 */
    private void showMoreMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, binding.hgSdMore);
        menu.getMenu().add(0, 1, 0, "不感兴趣");
        menu.setOnMenuItemClickListener(item -> {
            dislikeSeries();
            return true;
        });
        menu.show();
    }

    private void dislikeSeries() {
        // business.video_id＝当前在看集（断点集，没有就第一集，再没有传空串——
        // 官方 UGC 分支即空串形状、服务端已受理）
        String vid = "";
        if (detail != null && !detail.getEpisodes().isEmpty()) {
            HgStore.Progress p = HgStore.progressOf(seriesId);
            int idx = p != null && p.episodeIndex > 0 ? p.episodeIndex - 1 : 0;
            if (idx >= detail.getEpisodes().size()) idx = 0;
            vid = detail.getEpisodes().get(idx).getVideoId();
        }
        HgFeedSession.get().fqClient.dislikeSeries(seriesId, vid, new HgCallback<Void>() {
            @Override
            public void onSuccess(Void v) {
                mainHandler.post(() -> Toast.makeText(HongguoSeriesDetailActivity.this,
                        "感谢反馈", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    if (code == HgCommentClient.CODE_NOT_LOGIN) {
                        promptLogin();
                        return;
                    }
                    Toast.makeText(HongguoSeriesDetailActivity.this,
                            "反馈没成功，再试试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private final class ReviewAdapter extends RecyclerView.Adapter<ReviewAdapter.Holder> {
        private final List<HgComment> items;

        ReviewAdapter(List<HgComment> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemHongguoSeriesReviewBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            HgComment c = items.get(position);
            ItemHongguoSeriesReviewBinding b = holder.binding;
            if (c.avatar != null && !c.avatar.isEmpty()) {
                b.hgSrAvatar.setVisibility(View.VISIBLE);
                ImgUtil.loadCircle("", c.avatar, b.hgSrAvatar);
            } else {
                b.hgSrAvatar.setVisibility(View.GONE);
            }
            b.hgSrAuthor.setText(c.author == null || c.author.isEmpty() ? "红果用户" : c.author);
            paintReviewStars(b.hgSrStars, c.score);
            b.hgSrContent.setText(c.content);
            boolean hasDigg = c.likes > 0;
            b.hgSrDiggIcon.setVisibility(hasDigg ? View.VISIBLE : View.GONE);
            b.hgSrDigg.setText(hasDigg ? HgFormat.countLine(c.likes) : "");
            if (c.userId != null && !c.userId.isEmpty()) {
                View.OnClickListener go = v -> HongguoAuthorActivity.start(
                        HongguoSeriesDetailActivity.this, c.userId, c.author, c.avatar, null);
                b.hgSrAvatar.setOnClickListener(go);
                b.hgSrAuthor.setOnClickListener(go);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        /** 剧评星级：五颗矢量星（11dp），亮颗金、灭颗白 35%，代原 ★☆ 字形串。 */
        private void paintReviewStars(android.widget.LinearLayout box, int scoreTen) {
            box.removeAllViews();
            int filled = Math.round(scoreTen / 2f);
            if (filled < 0) filled = 0;
            if (filled > 5) filled = 5;
            for (int i = 0; i < 5; i++) {
                ImageView s = new ImageView(box.getContext());
                s.setImageResource(com.fongmi.android.tv.R.drawable.ic_hg_star);
                s.setColorFilter(i < filled ? 0xFFFFC53D : 0x59FFFFFF);
                box.addView(s, new android.widget.LinearLayout.LayoutParams(dp(11), dp(11)));
            }
        }

        private final class Holder extends RecyclerView.ViewHolder {
            final ItemHongguoSeriesReviewBinding binding;

            Holder(ItemHongguoSeriesReviewBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    // ---------- 收藏 ----------

    private void paintFav() {
        binding.hgSdFavStar.setImageResource(fav
                ? com.fongmi.android.tv.R.drawable.ic_hg_star_fill
                : com.fongmi.android.tv.R.drawable.ic_hg_detail_star_outline);
    }

    private void toggleFav() {
        if (favBusy) return;
        if (HgFeedSession.get().interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        favBusy = true;
        boolean target = !fav;
        fav = target;
        paintFav();
        HgFeedSession.get().client.bookshelfVideoUpdate(seriesId, target, new HgCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean ok) {
                mainHandler.post(() -> favBusy = false);
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    favBusy = false;
                    fav = !target;
                    paintFav();
                    Toast.makeText(HongguoSeriesDetailActivity.this,
                            target ? "收藏没成功，再试试" : "取消收藏没成功，再试试",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
