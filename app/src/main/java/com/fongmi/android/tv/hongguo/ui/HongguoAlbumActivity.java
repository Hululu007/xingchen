package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoAlbumBinding;
import com.fongmi.android.tv.databinding.ItemHongguoAlbumEpisodeBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgAlbumDetail;
import com.fongmi.android.tv.hongguo.model.HgSeriesItem;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果·合集详情页（批四卡 8 落点）：官方对位 VideoAlbumDetailActivity
 * （头部 VideoAlbumHeaderLayout＋集列表 IAlbumEpisodeListView DETAIL
 * 场景＋点集以 video.k 携 albumId 起播）。此前自家没有合集详情页，
 * 搜索/流页的合集条只能以专辑名单弹层（HgSeriesSheet）承接（批一
 * 残项 2）；本页落地后两处 ALBUM 落点统一改指本页，多季选部仍走
 * 系列单不变。
 *
 * <p>数据只走 album_detail/v1 一口（HgApiClient.fetchAlbumDetail）：
 * 头部字段＝回包 album_data 实有键（见 HgAlbumDetail 类注，官方模型
 * 的 collectCnt 本回包无此键、不编）；作者行点进作者页、关注钮走
 * 作者关注统一口（HgStore 作者集＋fqClient.doFollowAction，乐观
 * 先行＋失败回滚，与作者页/流页同一条链；关系态初值由回包
 * user_relation 校准回写本地镜像）；点集行走既有集流起播路
 * （startSeries，携合集作者 uid 作身份线索＋本页 albumId——批四
 * R5 后半已接：七参 startSeries 落集流剧头 playAlbumId、展开逐集
 * 页继承，集流剧条前缀据此判「合集」，与官方 video.k.i=Album 的
 * CatalogView 前缀同口径；播放解链/追更收藏链经逐位核查无 albumId
 * 消费位，见账 hongguo-album-play-albumid-20261030）。</p>
 */
public class HongguoAlbumActivity extends BaseActivity {

    private static final String EXTRA_ALBUM_ID = "hg_ab_album_id";
    private static final String EXTRA_TITLE = "hg_ab_title";
    private static final String EXTRA_COVER = "hg_ab_cover";

    public static void start(Context context, String albumId, String title, String cover) {
        if (albumId == null || albumId.isEmpty()) return;
        Intent intent = new Intent(context, HongguoAlbumActivity.class);
        intent.putExtra(EXTRA_ALBUM_ID, albumId);
        intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_COVER, cover);
        context.startActivity(intent);
    }

    private ActivityHongguoAlbumBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<HgSeriesItem> rows = new ArrayList<>();
    private RecyclerView.Adapter<RowHolder> adapter;
    private String albumId;
    private HgAlbumDetail detail;
    private boolean followBusy;

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoAlbumBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        albumId = getIntent().getStringExtra(EXTRA_ALBUM_ID);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String cover = getIntent().getStringExtra(EXTRA_COVER);
        if (albumId == null || albumId.isEmpty()) {
            finish();
            return;
        }
        if (title != null) binding.hgAbTitle.setText(title);
        if (cover != null && !cover.isEmpty()) {
            ImgUtil.load(title == null ? "" : title, cover, binding.hgAbCover);
            ImgUtil.load(title == null ? "" : title, cover, binding.hgAbBg);
        }
        adapter = new RecyclerView.Adapter<RowHolder>() {
            @NonNull
            @Override
            public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RowHolder(ItemHongguoAlbumEpisodeBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull RowHolder holder, int position) {
                HgSeriesItem item = rows.get(position);
                ItemHongguoAlbumEpisodeBinding b = holder.binding;
                ImgUtil.load(item.getTitle() == null ? "" : item.getTitle(),
                        item.getCoverUrl(), b.hgAeCover);
                b.hgAeTitle.setText(item.getTitle() == null ? "" : item.getTitle());
                StringBuilder sub = new StringBuilder();
                if (item.getTags() != null) {
                    for (String t : item.getTags()) {
                        if (t == null || t.isEmpty()) continue;
                        if (sub.length() > 0) sub.append(" · ");
                        sub.append(t);
                    }
                }
                b.hgAeSub.setText(sub.toString());
                b.hgAeSub.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
                b.getRoot().setOnClickListener(v -> {
                    String authorUid = detail == null ? null : detail.authorUid;
                    // 起播携本页 albumId（官方 video.k 对位，批四 R5 后半）：
                    // 走七参 startSeries 落集流剧头 playAlbumId，展开后逐集
                    // 页继承、集流剧条前缀据此判「合集」；身份只从本页进，
                    // 其他进集流路不带
                    HongguoFeedActivity.startSeries(HongguoAlbumActivity.this,
                            item.getSeriesId(), item.getTitle(), item.getCoverUrl(),
                            authorUid, null, albumId);
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        };
        binding.hgAbList.setLayoutManager(new LinearLayoutManager(this));
        binding.hgAbList.setAdapter(adapter);
        binding.hgAbState.setVisibility(View.VISIBLE);
        binding.hgAbState.setText("加载中…");
        load();
    }

    private void load() {
        HgFeedSession.get().client.fetchAlbumDetail(albumId, new HgCallback<HgAlbumDetail>() {
            @Override
            public void onSuccess(HgAlbumDetail d) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (d == null || (d.rows.isEmpty() && d.title == null)) {
                        binding.hgAbState.setVisibility(View.VISIBLE);
                        binding.hgAbState.setText("合集暂时打不开，稍后再试");
                        return;
                    }
                    detail = d;
                    bindHeader(d);
                    rows.clear();
                    rows.addAll(d.rows);
                    adapter.notifyDataSetChanged();
                    binding.hgAbState.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    if (rows.isEmpty()) binding.hgAbState.setText("合集暂时没有内容");
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    binding.hgAbState.setVisibility(View.VISIBLE);
                    binding.hgAbState.setText("合集暂时打不开，稍后再试");
                });
            }
        });
    }

    private void bindHeader(HgAlbumDetail d) {
        if (d.title != null && !d.title.isEmpty()) binding.hgAbTitle.setText(d.title);
        if (d.cover != null && !d.cover.isEmpty()) {
            ImgUtil.load(d.title == null ? "" : d.title, d.cover, binding.hgAbCover);
            ImgUtil.load(d.title == null ? "" : d.title, d.cover, binding.hgAbBg);
        }
        // 副标题段：sub_title_list 原文段＋热度文案（都是回包实有文案，不自拼数字）
        StringBuilder sub = new StringBuilder();
        for (String s : d.subTitles) {
            if (s == null || s.isEmpty()) continue;
            if (sub.length() > 0) sub.append(" · ");
            sub.append(s);
        }
        if (d.hotScoreText != null && !d.hotScoreText.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(d.hotScoreText);
        }
        binding.hgAbSubline.setText(sub.toString());
        binding.hgAbSubline.setVisibility(sub.length() > 0 ? View.VISIBLE : View.GONE);
        if (d.intro != null && !d.intro.isEmpty()) {
            binding.hgAbIntro.setVisibility(View.VISIBLE);
            binding.hgAbIntro.setText(d.intro);
        } else {
            binding.hgAbIntro.setVisibility(View.GONE);
        }
        int count = d.episodeCount > 0 ? d.episodeCount : d.rows.size();
        String status = "";
        if (count > 0) {
            status = (d.seriesStatus != null && d.seriesStatus == 1 ? "已完结 · " : "")
                    + "共" + count + "集";
        }
        binding.hgAbEpStatus.setText(status);
        bindAuthor(d);
    }

    /** 作者行：有作者身份才显；整行进作者页，关注钮原地翻转（统一口）。 */
    private void bindAuthor(HgAlbumDetail d) {
        if (d.authorUid == null || d.authorUid.isEmpty()
                || d.authorName == null || d.authorName.isEmpty()) {
            binding.hgAbAuthorRow.setVisibility(View.GONE);
            return;
        }
        binding.hgAbAuthorRow.setVisibility(View.VISIBLE);
        ImgUtil.loadCircle(d.authorName, d.authorAvatar, binding.hgAbAuthorAvatar);
        binding.hgAbAuthorName.setText(d.authorName);
        // 关系态以回包为准校准本地镜像（与作者页资料回源同口径）
        HgStore.setAuthorFollowed(d.authorUid, d.isAuthorFollowed());
        boolean self = d.authorUid.equals(HgStore.profileUid());
        binding.hgAbAuthorFollow.setVisibility(
                d.authorCanFollow && !self ? View.VISIBLE : View.GONE);
        paintAuthorFollow();
        binding.hgAbAuthorRow.setOnClickListener(v ->
                HongguoAuthorActivity.start(HongguoAlbumActivity.this,
                        d.authorUid, d.authorName, d.authorAvatar, null));
        binding.hgAbAuthorFollow.setOnClickListener(v -> onAuthorFollowClicked());
    }

    private void paintAuthorFollow() {
        if (detail == null) return;
        boolean followed = HgStore.isAuthorFollowed(detail.authorUid);
        binding.hgAbAuthorFollow.setText(followed ? "已关注" : "关注");
        binding.hgAbAuthorFollow.setBackgroundResource(followed
                ? com.fongmi.android.tv.R.drawable.bg_hg_author_followed_btn
                : com.fongmi.android.tv.R.drawable.bg_hg_author_follow_btn);
        binding.hgAbAuthorFollow.setTextColor(followed ? 0xFFFFFFFF : 0xFFFA7705);
    }

    /** 关注/取关：与作者页同一条链（登录门→乐观写镜像→真口收口→失败回滚）。 */
    private void onAuthorFollowClicked() {
        if (detail == null || followBusy) return;
        String uid = detail.authorUid;
        if (HgFeedSession.get().interactions.requiresLogin()) {
            boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
            if (!delivered) Toast.makeText(this, "登录界面暂时打不开，稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean follow = !HgStore.isAuthorFollowed(uid);
        followBusy = true;
        HgStore.setAuthorFollowed(uid, follow);
        paintAuthorFollow();
        HgFeedSession.get().fqClient.doFollowAction(follow, uid, new HgCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                mainHandler.post(() -> {
                    followBusy = false;
                    paintAuthorFollow();
                });
            }

            @Override
            public void onError(int code, String msg) {
                mainHandler.post(() -> {
                    followBusy = false;
                    HgStore.setAuthorFollowed(uid, !follow);
                    paintAuthorFollow();
                    Toast.makeText(HongguoAlbumActivity.this,
                            follow ? "关注没成功，再试试" : "取关没成功，再试试",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final ItemHongguoAlbumEpisodeBinding binding;

        RowHolder(ItemHongguoAlbumEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
