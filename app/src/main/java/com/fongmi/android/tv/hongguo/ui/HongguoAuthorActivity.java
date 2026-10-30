package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
        context.startActivity(intent);
    }

    private String uid;
    private ImageView avatarView;
    private TextView nameView;
    private TextView descView;
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
        HgWindowStyle.apply(this, false, false);
        uid = getIntent().getStringExtra(EXTRA_UID);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111114);
        root.setPadding(dp(16), statusBarHeight() + dp(6), dp(16), navBarHeight());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_drama_back);
        back.setColorFilter(Color.WHITE);
        back.setPadding(dp(4), dp(8), dp(12), dp(8));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView topTitle = new TextView(this);
        topTitle.setText("作者");
        topTitle.setTextColor(Color.WHITE);
        topTitle.setTextSize(16.5f);
        topTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(topTitle);
        root.addView(top);

        // 头部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(14);
        avatarView = new ImageView(this);
        avatarView.setBackground(roundBg(0xFF2C2C30, dp(30)));
        head.addView(avatarView, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 1f);
        ilp.leftMargin = dp(13);
        nameView = new TextView(this);
        nameView.setTextColor(Color.WHITE);
        nameView.setTextSize(17.5f);
        nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameView.setMaxLines(1);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(nameView);
        descView = new TextView(this);
        descView.setTextColor(0xFF9E9EA7);
        descView.setTextSize(12.5f);
        descView.setMaxLines(2);
        descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(4);
        info.addView(descView, dlp);
        head.addView(info, ilp);
        root.addView(head, hlp);

        TextView worksLabel = new TextView(this);
        worksLabel.setText("作品");
        worksLabel.setTextColor(Color.WHITE);
        worksLabel.setTextSize(15);
        worksLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-2, -2);
        wlp.topMargin = dp(20);
        wlp.bottomMargin = dp(6);
        root.addView(worksLabel, wlp);

        RecyclerView grid = new RecyclerView(this);
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        grid.setLayoutManager(glm);
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
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        stateText = new TextView(this);
        stateText.setTextColor(0xFF9E9EA7);
        stateText.setTextSize(13.5f);
        stateText.setGravity(Gravity.CENTER);
        stateText.setPadding(0, dp(10), 0, dp(10));
        stateText.setVisibility(View.GONE);
        stateText.setOnClickListener(v -> {
            if (works.isEmpty()) loadWorks(true);
        });
        root.addView(stateText);

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
            final TextView title;
            final TextView meta;

            H(LinearLayout cell) {
                super(cell);
                cover = cell.findViewWithTag("cover");
                title = cell.findViewWithTag("title");
                meta = cell.findViewWithTag("meta");
            }
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(HongguoAuthorActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setPadding(dp(5), dp(6), dp(5), dp(10));
            ImageView cover = new ImageView(HongguoAuthorActivity.this);
            cover.setTag("cover");
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setBackground(roundBg(0xFF232328, dp(8)));
            int w = (getResources().getDisplayMetrics().widthPixels - dp(62)) / 3;
            cell.addView(cover, new LinearLayout.LayoutParams(-1, (int) (w * 1.38f)));
            TextView title = new TextView(HongguoAuthorActivity.this);
            title.setTag("title");
            title.setTextColor(0xFFF2F2F4);
            title.setTextSize(13);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.topMargin = dp(6);
            cell.addView(title, tlp);
            TextView meta = new TextView(HongguoAuthorActivity.this);
            meta.setTag("meta");
            meta.setTextColor(0xFF8E8E96);
            meta.setTextSize(11);
            meta.setMaxLines(1);
            cell.addView(meta);
            return new H(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int position) {
            DramaWork work = works.get(position);
            if (work.cover != null && !work.cover.isEmpty()) {
                Glide.with(HongguoAuthorActivity.this).load(work.cover).into(h.cover);
            } else {
                h.cover.setImageDrawable(null);
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
