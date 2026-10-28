package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

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
 * <p>未摆：分享/更多（官方顶栏位，咱家无对应功能不摆假钮）、参与评分
 * 的发评入口（发剧评的写口参数未验通，评分卡右列只作信息呈现）。</p>
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
    private HgSeriesDetail detail;
    private boolean fav;
    private boolean favBusy;
    private boolean descExpanded;
    private int segment;

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
            ImgUtil.load("", cover, binding.hgSdBg);
        }
        HgStore.Progress p = HgStore.progressOf(seriesId);
        binding.hgSdPlay.setText(p != null && p.episodeIndex > 0 ? "▶ 继续播放" : "▶ 开始播放");
        binding.hgSdPlay.setOnClickListener(v ->
                HongguoFeedActivity.startSeries(this, seriesId,
                        binding.hgSdTitle.getText().toString(),
                        detail != null ? detail.getCoverUrl() : cover));
        binding.hgSdFav.setOnClickListener(v -> toggleFav());
        binding.hgSdExpand.setOnClickListener(v -> {
            descExpanded = !descExpanded;
            binding.hgSdDesc.setMaxLines(descExpanded ? Integer.MAX_VALUE : 3);
            binding.hgSdExpand.setText(descExpanded ? "收起" : "展开");
        });
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
            ImgUtil.load("", d.getCoverUrl(), binding.hgSdBg);
        }
        if (d.getColorHex() != null) {
            try {
                int c = Color.parseColor(d.getColorHex());
                binding.hgSdScrim.setBackgroundColor(c & 0x00FFFFFF | 0x59000000);
                binding.getRoot().setBackgroundColor(
                        Color.rgb(Math.max(0, Color.red(c) - 24),
                                Math.max(0, Color.green(c) - 24),
                                Math.max(0, Color.blue(c) - 24)));
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
        // 标签筹
        binding.hgSdTags.removeAllViews();
        for (String tagName : d.getTags()) {
            TextView chip = new TextView(this);
            chip.setText(tagName);
            chip.setTextSize(11);
            chip.setTextColor(0xE6FFFFFF);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackgroundResource(com.fongmi.android.tv.R.drawable.bg_hg_chip);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            binding.hgSdTags.addView(chip);
        }
        binding.hgSdTags.setVisibility(d.getTags().isEmpty() ? View.GONE : View.VISIBLE);
        // 简介
        String desc = d.getDesc();
        binding.hgSdDesc.setText(desc == null ? "" : desc);
        binding.hgSdExpand.setVisibility(desc != null && desc.length() > 80 ? View.VISIBLE : View.GONE);
        // 剧集状态行
        Integer status = d.getSeriesStatus();
        int count = d.getEpisodeCount() > 0 ? d.getEpisodeCount() : d.getEpisodes().size();
        String statusText;
        if (status != null && status == 1) statusText = "已完结 · 共" + count + "集";
        else if (status != null && status == 0) statusText = "更新中 · 已更新" + count + "集";
        else statusText = "共" + count + "集";
        binding.hgSdEpStatus.setText(statusText);
        buildEpisodes(d);
        // 收藏态以服务端为准
        fav = d.isFollowed();
        paintFav();
    }

    // ---------- 集格 ----------

    private void buildEpisodes(HgSeriesDetail d) {
        List<HgSeriesDetail.Episode> eps = d.getEpisodes();
        binding.hgSdSegments.removeAllViews();
        binding.hgSdEpGrid.removeAllViews();
        if (eps.isEmpty()) return;
        int segments = (eps.size() + SEGMENT - 1) / SEGMENT;
        HgStore.Progress p = HgStore.progressOf(seriesId);
        int currentEp = p == null ? -1 : p.episodeIndex;
        if (currentEp > 0) segment = Math.min(segments - 1, (currentEp - 1) / SEGMENT);
        if (segments <= 1) {
            binding.hgSdSegmentsScroll.setVisibility(View.GONE);
        } else {
            binding.hgSdSegmentsScroll.setVisibility(View.VISIBLE);
            for (int s = 0; s < segments; s++) {
                int from = s * SEGMENT + 1;
                int to = Math.min(eps.size(), (s + 1) * SEGMENT);
                TextView chip = new TextView(this);
                chip.setText(from + "-" + to);
                chip.setTextSize(13);
                chip.setPadding(dp(12), dp(6), dp(12), dp(6));
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                if (s > 0) lp.setMarginStart(dp(8));
                chip.setLayoutParams(lp);
                final int seg = s;
                chip.setOnClickListener(v -> {
                    segment = seg;
                    buildEpisodes(detail);
                });
                boolean on = s == segment;
                chip.setBackgroundResource(on
                        ? com.fongmi.android.tv.R.drawable.bg_hg_ep_current
                        : com.fongmi.android.tv.R.drawable.bg_hg_ep_cell_dark);
                chip.setTextColor(on ? 0xFF1D2129 : 0xFFFFFFFF);
                binding.hgSdSegments.addView(chip);
            }
        }
        int from = segment * SEGMENT;
        int to = Math.min(eps.size(), from + SEGMENT);
        for (int i = from; i < to; i++) {
            HgSeriesDetail.Episode ep = eps.get(i);
            int epNum = ep.getIndex() > 0 ? ep.getIndex() : i + 1;
            TextView cell = new TextView(this);
            cell.setText(String.valueOf(epNum));
            cell.setTextSize(14);
            cell.setGravity(android.view.Gravity.CENTER);
            boolean current = epNum == currentEp;
            cell.setBackgroundResource(current
                    ? com.fongmi.android.tv.R.drawable.bg_hg_ep_current
                    : com.fongmi.android.tv.R.drawable.bg_hg_ep_cell_dark);
            cell.setTextColor(current ? 0xFF1D2129 : 0xFFFFFFFF);
            if (current) cell.setTypeface(null, android.graphics.Typeface.BOLD);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = dp(44);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            cell.setLayoutParams(lp);
            final int targetEp = epNum;
            cell.setOnClickListener(v -> {
                HgStore.saveProgress(seriesId, targetEp, 0);
                HongguoFeedActivity.startSeries(this, seriesId,
                        binding.hgSdTitle.getText().toString(),
                        detail != null ? detail.getCoverUrl() : null);
            });
            binding.hgSdEpGrid.addView(cell);
        }
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
        if (th >= 60 && th % 60 == 0) {
            binding.hgSdThreshold.setText("看" + (th / 60) + "分钟参与评分");
        } else if (th > 0) {
            binding.hgSdThreshold.setText("看" + th + "秒参与评分");
        } else {
            binding.hgSdThreshold.setText("");
        }
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
            b.hgSrStars.setTextColor(0xFFFFC53D);
            b.hgSrStars.setText(starsText(c.score));
            b.hgSrContent.setText(c.content);
            b.hgSrDigg.setText(c.likes > 0 ? "♡ " + HgFormat.countLine(c.likes) : "");
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

        private String starsText(int scoreTen) {
            int filled = Math.round(scoreTen / 2f);
            if (filled < 0) filled = 0;
            if (filled > 5) filled = 5;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(i < filled ? "★" : "☆");
            return sb.toString();
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
        binding.hgSdFavStar.setText(fav ? "★" : "☆");
        binding.hgSdFavStar.setTextColor(fav ? 0xFFFFC53D : 0xFFFFFFFF);
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
