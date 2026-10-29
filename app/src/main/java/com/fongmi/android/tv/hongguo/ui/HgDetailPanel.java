package com.fongmi.android.tv.hongguo.ui;

import android.app.Dialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.databinding.ViewHongguoDetailPanelBinding;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 流内侧滑详情面板（批四卡 16）：官方在播放流里以侧滑手势唤出轻量
 * 详情面板、内嵌详情 Fragment 本体（与剧集详情页同源），先滑出看
 * 详情再决定是否进全页。咱家落法：从流页信息区（剧名行/剧条）点入
 * 唤出右侧面板——触发手势的精确方向/阈值与面板宽度占比官方未抠到
 * （普查册一 #11），手势与竖向 ViewPager2 翻页冲突风险未定，不硬塞
 * 手势；关闭走点遮罩（Dialog 点外关闭）/系统返回。
 *
 * <p>内容是承接详情数据的轻量面，不另起详情逻辑：取数走既有
 * HgSeriesDetail 口（fetchVideoDetail），只渲染回包实有字段（封面/
 * 剧名/副标题段/标签/简介/集数）；追更态读追剧架正源（HgStore
 * followed，批二口径），回包 followed 到达时同详情页口径校准回写
 * 正源；切换走统一互动层 toggleFavorite（宿主先过登录门）。选集与
 * 进全页详情两个入口都回宿主既有落点（选集弹窗/详情页），面板本身
 * 不复制它们的逻辑。</p>
 */
public final class HgDetailPanel {

    public interface Host {
        /** 点「选集」：宿主开既有选集弹窗（面板已关闭）。 */
        void onPanelEpisodes();

        /** 点「查看详情」：宿主进全页详情（面板已关闭）。 */
        void onPanelFullDetail();
    }

    private HgDetailPanel() {
    }

    public static void show(FragmentActivity activity, HgFeedItem item,
                            HgInteractions interactions, Host host) {
        if (item == null || item.seriesId == null || item.seriesId.isEmpty()) return;
        ViewHongguoDetailPanelBinding panel = ViewHongguoDetailPanelBinding.inflate(
                LayoutInflater.from(activity));
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel.getRoot());
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.END);
            // 面板宽度占比官方未抠到：自家值 0.82（账里标明非官方值）
            int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.82);
            window.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.45f;
            window.setAttributes(lp);
        }
        // 首帧先用条目自带信息垫上（封面/剧名/集数），详情回包到达再补全
        ImgUtil.load(item.title == null ? "" : item.title, item.coverUrl, panel.hgDpCover);
        panel.hgDpTitle.setText(item.title == null ? "" : item.title);
        if (item.episodeCount > 0) {
            panel.hgDpSubline.setText("全" + item.episodeCount + "集");
        }
        paintFollow(panel, item.seriesId);
        panel.hgDpFollow.setOnClickListener(v -> {
            // 登录门照详情页口径：未登录先拉壳子统一登录、不落状态
            if (interactions.requiresLogin()) {
                boolean delivered = com.fongmi.android.tv.login.LoginManager
                        .loginHongguoDelivered();
                if (!delivered) {
                    android.widget.Toast.makeText(activity,
                            "登录界面暂时打不开，稍后再试",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                return;
            }
            interactions.toggleFavorite(item.seriesId);
            paintFollow(panel, item.seriesId);
        });
        panel.hgDpEpisodes.setOnClickListener(v -> {
            dialog.dismiss();
            if (host != null) host.onPanelEpisodes();
        });
        panel.hgDpFull.setOnClickListener(v -> {
            dialog.dismiss();
            if (host != null) host.onPanelFullDetail();
        });
        dialog.show();
        HgFeedSession.get().client.fetchVideoDetail(item.seriesId,
                new HgCallback<HgSeriesDetail>() {
                    @Override
                    public void onSuccess(HgSeriesDetail d) {
                        activity.runOnUiThread(() -> {
                            if (!dialog.isShowing() || d == null) return;
                            bindDetail(panel, item.seriesId, d);
                        });
                    }

                    @Override
                    public void onError(int code, String msg) {
                        activity.runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            panel.hgDpState.setVisibility(android.view.View.VISIBLE);
                            panel.hgDpState.setText("详情没拉到，点「查看详情」看完整页");
                        });
                    }
                });
    }

    private static void bindDetail(ViewHongguoDetailPanelBinding panel,
                                   String seriesId, HgSeriesDetail d) {
        if (d.getTitle() != null && !d.getTitle().isEmpty()) {
            panel.hgDpTitle.setText(d.getTitle());
        }
        if (d.getCoverUrl() != null && !d.getCoverUrl().isEmpty()) {
            ImgUtil.load(d.getTitle() == null ? "" : d.getTitle(), d.getCoverUrl(),
                    panel.hgDpCover);
        }
        StringBuilder sub = new StringBuilder();
        for (String s : d.getSubTitles()) {
            if (s == null || s.isEmpty()) continue;
            if (sub.length() > 0) sub.append(" · ");
            sub.append(s);
        }
        if (sub.length() == 0 && d.getEpisodeCount() > 0) {
            sub.append("全").append(d.getEpisodeCount()).append("集");
        }
        panel.hgDpSubline.setText(sub.toString());
        if (d.getTags() != null && !d.getTags().isEmpty()) {
            StringBuilder tags = new StringBuilder();
            for (String t : d.getTags()) {
                if (t == null || t.isEmpty()) continue;
                if (tags.length() > 0) tags.append(" · ");
                tags.append(t);
            }
            if (tags.length() > 0) {
                panel.hgDpTags.setVisibility(android.view.View.VISIBLE);
                panel.hgDpTags.setText(tags.toString());
            }
        }
        if (d.getDesc() != null && !d.getDesc().isEmpty()) {
            panel.hgDpDesc.setVisibility(android.view.View.VISIBLE);
            panel.hgDpDesc.setText(d.getDesc());
        }
        // 追更态以回包 followed 校准并回写正源（与详情页 bindDetail 同口径）
        HgStore.setFollowed(seriesId, d.isFollowed());
        paintFollow(panel, seriesId);
    }

    /** 追更钮两态：已追＝「已追更」弱态、未追＝「追更」亮态（读正源）。 */
    private static void paintFollow(ViewHongguoDetailPanelBinding panel, String seriesId) {
        boolean followed = HgStore.isFollowed(seriesId);
        panel.hgDpFollowText.setText(followed ? "已追更" : "追更");
        panel.hgDpFollowText.setTextColor(followed ? 0x99FFFFFF : 0xFFFFFFFF);
    }
}
