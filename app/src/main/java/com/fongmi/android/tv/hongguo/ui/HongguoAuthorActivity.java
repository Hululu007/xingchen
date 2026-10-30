package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.drama.DramaClient;
import com.fongmi.android.tv.hongguo.drama.DramaUserProfile;
import com.fongmi.android.tv.hongguo.drama.DramaWork;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者页（2026-10-30 从零重建，简单口径）：头像/名字/简介＋作品
 * 网格，一页一件事。**没有关注钮**（纯本地口径，他定版：关注整件
 * 摘除）。作品点开：成剧的进详情页、单条 UGC 进集流直播。
 */
public class HongguoAuthorActivity extends AppCompatActivity {

    public static final String EXTRA_UID = "hg_author_uid";
    public static final String EXTRA_NAME = "hg_author_name";
    public static final String EXTRA_AVATAR = "hg_author_avatar";

    public static void start(Context context, String uid, String name, String avatar) {
        Intent intent = new Intent(context, HongguoAuthorActivity.class);
        intent.putExtra(EXTRA_UID, uid);
        intent.putExtra(EXTRA_NAME, name);
        intent.putExtra(EXTRA_AVATAR, avatar);
        HgTransition.push(context, intent);
    }

    @Override
    public void finish() {
        super.finish();
        HgTransition.applyPushExit(this);
    }

    /** 定稿名字字体：Noto Serif SC 900 的系统近似（serif 粗体）。 */
    private static final android.graphics.Typeface SERIF_BLACK =
            android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD);

    private String uid;
    private ImageView avatarView;
    private TextView nameView;
    private TextView descView;
    private TextView worksCount;
    private TextView stateText;
    private WorksAdapter adapter;
    private final List<DramaWork> works = new ArrayList<>();
    private String cursor;
    private boolean hasMore;
    private boolean loading;

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
        HgWindowStyle.apply(this, true, true);
        uid = getIntent().getStringExtra(EXTRA_UID);

        // 页面层重做（定稿 #authorOverlay）：浅底，顶栏浅底圆返回钮
        // ＋「作者主页」，头部居中列，作品三列格圆 13。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFAF9F7);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), statusBarHeight() + dp(8), dp(14), dp(2));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(0xFF1B1B1F);
        back.setBackground(roundBg(0x0D1B1B1F, dp(19)));
        back.setPadding(dp(9), dp(9), dp(9), dp(9));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView topTitle = new TextView(this);
        topTitle.setText("作者主页");
        topTitle.setTextColor(0xFF1B1B1F);
        topTitle.setTextSize(16);
        topTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(-2, -2);
        ttlp.leftMargin = dp(10);
        top.addView(topTitle, ttlp);
        root.addView(top);

        // 头部：居中列（头像 74 圆、名 Serif 21、meta 12.5 sub 居中）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(dp(30), dp(20), dp(30), dp(4));
        avatarView = new ImageView(this);
        avatarView.setBackground(roundBg(0xFFE8E6E2, dp(37)));
        head.addView(avatarView, new LinearLayout.LayoutParams(dp(74), dp(74)));
        nameView = new TextView(this);
        nameView.setTextColor(0xFF1B1B1F);
        nameView.setTextSize(21);
        nameView.setTypeface(SERIF_BLACK);
        nameView.setGravity(Gravity.CENTER);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(13);
        nlp.bottomMargin = dp(5);
        head.addView(nameView, nlp);
        descView = new TextView(this);
        descView.setTextColor(0xFF8A8A93);
        descView.setTextSize(12.5f);
        descView.setGravity(Gravity.CENTER);
        // 定稿头部 meta 行高 1.7，按全单字体换算（≈×0.83）落 1.41
        descView.setLineSpacing(0, 1.41f);
        descView.setMaxLines(3);
        descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(descView);
        root.addView(head);

        LinearLayout worksHead = new LinearLayout(this);
        worksHead.setOrientation(LinearLayout.HORIZONTAL);
        worksHead.setBaselineAligned(true);
        worksHead.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(24);
        wlp.bottomMargin = dp(2);
        TextView worksLabel = new TextView(this);
        worksLabel.setText("他的作品");
        worksLabel.setTextColor(0xFF1B1B1F);
        worksLabel.setTextSize(16);
        worksLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        worksLabel.setIncludeFontPadding(false);
        worksHead.addView(worksLabel);
        worksCount = new TextView(this);
        worksCount.setTextColor(0xFF8A8A93);
        worksCount.setTextSize(12);
        worksCount.setIncludeFontPadding(false);
        LinearLayout.LayoutParams wclp = new LinearLayout.LayoutParams(-2, -2);
        wclp.leftMargin = dp(7);
        worksHead.addView(worksCount, wclp);
        root.addView(worksHead, wlp);

        RecyclerView grid = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        grid.setLayoutManager(glm);
        grid.setPadding(dp(13), dp(16), dp(13), dp(16) + navBarHeight());
        grid.setClipToPadding(false);
        adapter = new WorksAdapter();
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy > 0 && !loading && hasMore
                        && glm.findLastVisibleItemPosition() >= works.size() - 6) {
                    loadWorks(false);
                }
            }
        });
        // 状态字（加载中/暂无作品/失败）盖在网格区正中，与剧场同改
        // （他 2026-10-31 点名：加载中不许在最底下）
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.addView(grid, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        stateText = new TextView(this);
        stateText.setTextColor(0xFF8A8A93);
        stateText.setTextSize(13.5f);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(0, dp(10), 0, dp(10));
        stateText.setVisibility(View.GONE);
        stateText.setOnClickListener(v -> {
            if (works.isEmpty()) loadWorks(true);
        });
        stage.addView(stateText, new android.widget.FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        String name = getIntent().getStringExtra(EXTRA_NAME);
        nameView.setText(name == null ? "" : name);
        String avatar = getIntent().getStringExtra(EXTRA_AVATAR);
        if (avatar != null && !avatar.isEmpty()) {
            Glide.with(this).load(avatar).circleCrop().into(avatarView);
        }
        loadProfile();
        loadWorks(true);
    }

    private void loadProfile() {
        if (uid == null) return;
        DramaClient.fetchUserProfile(uid, new HgCallback<DramaUserProfile>() {
            @Override
            public void onSuccess(DramaUserProfile p) {
                runOnUiThread(() -> {
                    if (p.name != null && !p.name.isEmpty()) nameView.setText(p.name);
                    if (p.avatar != null && !p.avatar.isEmpty()) {
                        Glide.with(HongguoAuthorActivity.this).load(p.avatar)
                                .circleCrop().into(avatarView);
                    }
                    StringBuilder d = new StringBuilder();
                    if (p.desc != null && !p.desc.isEmpty()) d.append(p.desc);
                    if (p.fansNum > 0) {
                        if (d.length() > 0) d.append("\n");
                        d.append("粉丝 ").append(HongguoFeedActivity.HgTimeTextCount.format(p.fansNum));
                    }
                    descView.setText(d.toString());
                });
            }

            @Override
            public void onError(int code, String msg) {
            }
        });
    }

    private void loadWorks(boolean first) {
        if (loading || uid == null) return;
        loading = true;
        if (first) {
            stateText.setText("加载中…");
            stateText.setVisibility(View.VISIBLE);
        }
        DramaClient.fetchAuthorWorks(uid, first ? null : cursor, new HgCallback<DramaWork.Page>() {
            @Override
            public void onSuccess(DramaWork.Page page) {
                runOnUiThread(() -> {
                    loading = false;
                    works.addAll(page.items);
                    cursor = page.nextCursor;
                    hasMore = page.hasMore;
                    // 计数＝已加载部数（总数接口无字段，随续拉增长）
                    worksCount.setText(works.isEmpty() ? "" : "· " + works.size() + " 部");
                    adapter.notifyDataSetChanged();
                    stateText.setVisibility(works.isEmpty() ? View.VISIBLE : View.GONE);
                    if (works.isEmpty()) stateText.setText("暂无作品");
                });
            }

            @Override
            public void onError(int code, String msg) {
                runOnUiThread(() -> {
                    loading = false;
                    if (works.isEmpty()) {
                        stateText.setText("取数据失败，点按重试");
                        stateText.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private class WorksAdapter extends RecyclerView.Adapter<WorksAdapter.H> {

        class H extends RecyclerView.ViewHolder {
            final ImageView cover;
            final TextView score;
            final TextView title;
            final TextView meta;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                score = cell.findViewWithTag("score");
                title = cell.findViewWithTag("title");
                meta = cell.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // 定稿作品格：海报 aspect 3/4.1、圆 13、占位 #E8E6E2；
            // 标题 13.5/700 ink、meta 11.5 sub 单行；右上评分角标
            // 与剧场卡同式（有分才显——作品回包无评分字段，见账）。
            LinearLayout cell = new LinearLayout(HongguoAuthorActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            android.widget.FrameLayout coverBox = new android.widget.FrameLayout(
                    HongguoAuthorActivity.this);
            ImageView cover = new ImageView(HongguoAuthorActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFFE8E6E2, dp(13)));
            coverBox.addView(cover, new android.widget.FrameLayout.LayoutParams(-1, -1));
            TextView score = new TextView(HongguoAuthorActivity.this);
            score.setTag("score");
            score.setTextColor(0xFFFFC44D);
            score.setTextSize(10.5f);
            score.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            score.setPadding(dp(7), dp(3), dp(7), dp(3));
            score.setBackground(roundBg(0x850A0806, dp(7)));
            score.setVisibility(View.GONE);
            android.widget.FrameLayout.LayoutParams sclp =
                    new android.widget.FrameLayout.LayoutParams(-2, -2);
            sclp.gravity = Gravity.TOP | Gravity.RIGHT;
            sclp.rightMargin = dp(7);
            sclp.topMargin = dp(7);
            coverBox.addView(score, sclp);
            int w = (getResources().getDisplayMetrics().widthPixels - dp(56)) / 3;
            cell.addView(coverBox, new LinearLayout.LayoutParams(-1, (int) (w * (4.1f / 3f))));
            TextView title = new TextView(HongguoAuthorActivity.this);
            title.setTag("title");
            title.setTextColor(0xFF1B1B1F);
            title.setTextSize(13.5f);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(8);
            tlp.leftMargin = dp(1);
            cell.addView(title, tlp);
            TextView meta = new TextView(HongguoAuthorActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0xFF8A8A93);
            meta.setTextSize(11.5f);
            meta.setMaxLines(1);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
            mlp.topMargin = dp(3);
            mlp.leftMargin = dp(1);
            cell.addView(meta, mlp);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaWork work = works.get(position);
            int col = position % 3;
            h.itemView.setPadding(col == 0 ? 0 : dp(5), 0,
                    col == 2 ? 0 : dp(5), dp(14));
            if (work.cover != null && !work.cover.isEmpty()) {
                Glide.with(HongguoAuthorActivity.this).load(work.cover)
                        .transform(new RoundedCorners(dp(13))).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
            }
            // 评分角标：有分才显（DramaWork.score 仅剧评条目带分，
            // 视频作品回包无评分字段、恒 -1，不编数）
            if (work.score > 0) {
                h.score.setVisibility(View.VISIBLE);
                h.score.setText(String.valueOf(work.score));
            } else {
                h.score.setVisibility(View.GONE);
            }
            h.title.setText(work.title == null ? "" : work.title);
            // 角标文案：服务端副标题段优先（自带完结态，如「更新至12集」），
            // 无则回退集数/播放量（DramaWork 无完结态字段，不编完结）
            String meta = null;
            if (work.subTitles != null && !work.subTitles.isEmpty()
                    && work.subTitles.get(0) != null && !work.subTitles.get(0).isEmpty()) {
                meta = work.subTitles.get(0);
            } else if (work.episodeCount > 0) {
                meta = work.episodeCount + "集";
            } else if (work.playCount > 0) {
                meta = HongguoFeedActivity.HgTimeTextCount.format(work.playCount) + " 播放";
            }
            h.meta.setText(meta == null ? "" : meta);
            h.itemView.setOnClickListener(v -> {
                if (work.seriesId != null && !work.seriesId.isEmpty()
                        && work.kind != DramaWork.KIND_VIDEO) {
                    HongguoDetailActivity.start(HongguoAuthorActivity.this,
                            work.seriesId, work.title, null);
                } else if (work.id != null && !work.id.isEmpty()) {
                    HongguoFeedActivity.startUgc(HongguoAuthorActivity.this, work.id,
                            work.seriesId, work.title, work.cover, 1001);
                }
            });
        }

        @Override
        public int getItemCount() {
            return works.size();
        }
    }
}
