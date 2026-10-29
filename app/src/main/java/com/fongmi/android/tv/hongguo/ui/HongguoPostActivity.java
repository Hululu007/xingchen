package com.fongmi.android.tv.hongguo.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.ActivityHongguoPostBinding;
import com.fongmi.android.tv.databinding.ItemHongguoPostSeriesBinding;
import com.fongmi.android.tv.hongguo.HgFormat;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgAuthorWork;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 红果·帖子详情页（作者页「动态」帖子卡的去处，2026-10-29 落地）。
 *
 * <p>官方去处定版：帖子 expand.schema＝dragon1967://seriesVideoList?
 * post_id=…&post_type=22（帖子承载面）；正文与图在作品列表回包里已
 * 全量自带（common.content.pure_text＋content.text 的 materials：
 * img-list 图集、series_card_id 关联剧），本页直接用条目数据成形，
 * 不另拉正文、不拿网页糊弄。</p>
 *
 * <p>构成：作者行（头像/名/时间）＋标题＋正文＋图集＋关联剧卡
 * （详情回源补标题封面、点卡进剧）＋计数行（点赞/收藏/评论只展示）。
 * 未落（记账）：评论区——帖子评论的 group_type 候选已定位
 * （UgcCommentGroupType.Post=11）但未实拉验通，不接现行评论链硬凑；
 * 点赞/收藏动作——帖子维度的 do_action 参数未验通，不摆假按钮。</p>
 */
public class HongguoPostActivity extends BaseActivity {

    private static final String EXTRA_POST_ID = "hg_post_id";
    private static final String EXTRA_TITLE = "hg_post_title";
    private static final String EXTRA_TEXT = "hg_post_text";
    private static final String EXTRA_IMAGES = "hg_post_images";
    private static final String EXTRA_SERIES = "hg_post_series_ids";
    private static final String EXTRA_DIGG = "hg_post_digg";
    private static final String EXTRA_COLLECT = "hg_post_collect";
    private static final String EXTRA_COMMENTS = "hg_post_comments";
    private static final String EXTRA_AUTHOR_NAME = "hg_post_author_name";
    private static final String EXTRA_AUTHOR_AVATAR = "hg_post_author_avatar";
    private static final String EXTRA_AUTHOR_UID = "hg_post_author_uid";
    private static final String EXTRA_TIME = "hg_post_time";

    /** 从作者页帖子条目进页：料全取条目本身（postBody 缺失时降级为只有标题）。 */
    public static void start(Context context, HgAuthorWork work, String authorUid) {
        Intent intent = new Intent(context, HongguoPostActivity.class);
        intent.putExtra(EXTRA_POST_ID, work.id);
        intent.putExtra(EXTRA_TITLE, work.title);
        HgAuthorWork.PostBody body = work.postBody;
        intent.putExtra(EXTRA_TEXT, body == null ? "" : body.text);
        if (body != null) {
            intent.putStringArrayListExtra(EXTRA_IMAGES, new ArrayList<>(body.imageUrls));
            intent.putStringArrayListExtra(EXTRA_SERIES, new ArrayList<>(body.seriesIds));
            intent.putExtra(EXTRA_COLLECT, body.collectCount);
            intent.putExtra(EXTRA_COMMENTS, body.commentCount);
        }
        intent.putExtra(EXTRA_DIGG, work.diggCount);
        intent.putExtra(EXTRA_AUTHOR_NAME, work.authorName);
        intent.putExtra(EXTRA_AUTHOR_AVATAR, work.authorAvatar);
        intent.putExtra(EXTRA_AUTHOR_UID, authorUid == null ? "" : authorUid);
        intent.putExtra(EXTRA_TIME, work.createTime);
        context.startActivity(intent);
    }

    private ActivityHongguoPostBinding binding;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String authorUid = "";

    @Override
    protected ViewBinding getBinding() {
        binding = ActivityHongguoPostBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 顶部避让（与搜索页同口径，2026-10-29 补）：白底全幅垫到状态栏底下
        // 不断层，但正文列原只有 16dp 顶内距、作者行会钻进状态栏区——按
        // 状态栏真实高度在原 16dp 之上追加，间距口径不变。
        final int baseTop = Math.round(16 * getResources().getDisplayMetrics().density);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            int want = baseTop + top;
            if (binding.hgPostContent.getPaddingTop() != want) {
                binding.hgPostContent.setPadding(binding.hgPostContent.getPaddingLeft(), want,
                        binding.hgPostContent.getPaddingRight(), binding.hgPostContent.getPaddingBottom());
            }
            return insets;
        });
        Intent intent = getIntent();
        authorUid = orEmpty(intent.getStringExtra(EXTRA_AUTHOR_UID));
        String authorName = orEmpty(intent.getStringExtra(EXTRA_AUTHOR_NAME));
        String authorAvatar = orEmpty(intent.getStringExtra(EXTRA_AUTHOR_AVATAR));
        if (!authorAvatar.isEmpty()) {
            ImgUtil.loadCircle(authorName, authorAvatar, binding.hgPostAvatar);
        }
        binding.hgPostAuthor.setText(authorName);
        long time = intent.getLongExtra(EXTRA_TIME, 0);
        if (time > 0) {
            binding.hgPostTime.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                    .format(new Date(time * 1000L)));
        } else {
            binding.hgPostTime.setVisibility(View.GONE);
        }
        String title = orEmpty(intent.getStringExtra(EXTRA_TITLE));
        String text = orEmpty(intent.getStringExtra(EXTRA_TEXT));
        // 标题缺省时正文首行兼标题（解析层已回落 pure_text 作标题）：正文与
        // 标题同文时只显正文，避免一进页同一句话出现两遍
        if (title.isEmpty() || title.equals(text)) {
            binding.hgPostTitle.setVisibility(View.GONE);
        } else {
            binding.hgPostTitle.setText(title);
        }
        if (text.isEmpty()) {
            binding.hgPostText.setVisibility(View.GONE);
        } else {
            binding.hgPostText.setText(text);
        }
        List<String> images = intent.getStringArrayListExtra(EXTRA_IMAGES);
        if (images != null) {
            for (String url : images) {
                if (url == null || url.isEmpty()) continue;
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                iv.setLayoutParams(lp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                // 图集圆角（与家族封面口径同手法：圆角底＋clipToOutline 裁角）
                iv.setBackgroundResource(com.fongmi.android.tv.R.drawable.bg_hg_cover_r12);
                iv.setClipToOutline(true);
                binding.hgPostImages.addView(iv);
                ImgUtil.load(title, url, iv);
            }
        }
        // 计数行＝真图标（布局侧 drawableStart）＋数，不再拿文字当前缀凑图标
        binding.hgPostDigg.setText(HgFormat.countLine(
                intent.getLongExtra(EXTRA_DIGG, 0)));
        binding.hgPostCollect.setText(HgFormat.countLine(
                intent.getLongExtra(EXTRA_COLLECT, 0)));
        binding.hgPostComment.setText(HgFormat.countLine(
                intent.getLongExtra(EXTRA_COMMENTS, 0)));
        List<String> seriesIds = intent.getStringArrayListExtra(EXTRA_SERIES);
        if (seriesIds != null && !seriesIds.isEmpty()) loadSeriesCards(seriesIds);
    }

    /** 关联剧卡：批量详情回源补标题/封面/集数，没回到的剧不摆假卡。 */
    private void loadSeriesCards(List<String> seriesIds) {
        HgFeedSession.get().client.fetchVideoDetails(seriesIds, new HgCallback<Map<String, HgSeriesDetail>>() {
            @Override
            public void onSuccess(Map<String, HgSeriesDetail> map) {
                mainHandler.post(() -> {
                    if (isFinishing() || map == null) return;
                    for (String sid : seriesIds) {
                        HgSeriesDetail detail = map.get(sid);
                        if (detail == null || detail.getTitle() == null) continue;
                        addSeriesCard(sid, detail);
                    }
                });
            }

            @Override
            public void onError(int code, String msg) {
                // 回源失败就不出卡（帖子本体照常看），不摆只有 id 的空卡
            }
        });
    }

    private void addSeriesCard(String seriesId, HgSeriesDetail detail) {
        ItemHongguoPostSeriesBinding row = ItemHongguoPostSeriesBinding.inflate(
                LayoutInflater.from(this), binding.hgPostSeries, false);
        ImgUtil.load(detail.getTitle(), detail.getCoverUrl(), row.hgPsCover);
        row.hgPsTitle.setText(detail.getTitle());
        if (detail.getEpisodeCount() > 0) {
            row.hgPsMeta.setText("全" + detail.getEpisodeCount() + "集");
        } else {
            row.hgPsMeta.setVisibility(View.GONE);
        }
        row.getRoot().setOnClickListener(v -> HongguoFeedActivity.startSeries(
                this, seriesId, detail.getTitle(), detail.getCoverUrl(),
                authorUid.isEmpty() ? null : authorUid, null));
        binding.hgPostSeries.addView(row.getRoot());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
