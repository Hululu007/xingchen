package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogHongguoEpisodesBinding;
import com.fongmi.android.tv.databinding.ItemHongguoEpisodeBinding;
import com.fongmi.android.tv.hongguo.model.HgSeriesDetail;
import com.fongmi.android.tv.utils.ImgUtil;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 选集单（官方 dig-search-sheets.md 第二节 E1–E19 定版）：半屏底部层
 * （单高＝屏高 50%、可拉升），顶部小横把、无 × 关闭钮，单体白底。
 * 头部＝封面 44×62 圆角 4dp＋剧名 16sp 粗体（maxWidth 170dp）＋跳详情
 * 箭头＋右端收藏钮 72×28；作者副行（作者名＋圆点＋sub_title_list[0]，
 * 12sp 灰）数据有才显；简介 14sp 折叠 2 行、展开文案「更多」，空则整行
 * 不显。官方头部没有「选集·已完结·全X集」文案行与完结标记（E10），
 * 不许再加。集格单个 RecyclerView 连续竖滚、6 列（E14/E16，无分页）；
 * 格高 52dp、圆角 8dp、底 #08000000（E17）；当前集＝橙字 #FFFA7705＋
 * 粗体＋底 #1AFA7705（E18，逐值对上），并带 12dp 橙色播放三角代用官方
 * 的「正在播放」Lottie（我方无此资源，静态近似）。点集回调全局下标由
 * 播放页跳播。剧头尚未展开时只列已知集。
 */
public final class HgEpisodeSheet {

    private static final int COLUMNS = 6;
    // 集号字色三档（官方 E18 浅皮肤解值）：未看黑／看过 gray_40／不可播 gray_20
    private static final int COLOR_UNWATCHED = 0xFF000000;
    private static final int COLOR_WATCHED = 0x66000000;
    private static final int COLOR_UNPLAYABLE = 0x33000000;
    private static final int COLOR_CURRENT = 0xFFFA7705;

    private HgEpisodeSheet() {
    }

    public static void show(Activity activity, HgFeedSession session, int currentGlobalPos,
                            IntConsumer onPick) {
        if (currentGlobalPos < 0 || currentGlobalPos >= session.items.size()) return;
        HgFeedItem current = session.items.get(currentGlobalPos);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < session.items.size(); i++) {
            HgFeedItem it = session.items.get(i);
            if (it.seriesId != null && it.seriesId.equals(current.seriesId) && !it.seriesHead) positions.add(i);
        }
        positions.sort((a, b) -> Integer.compare(
                session.items.get(a).episodeIndex, session.items.get(b).episodeIndex));
        if (positions.isEmpty()) positions.add(currentGlobalPos);

        DialogHongguoEpisodesBinding binding = DialogHongguoEpisodesBinding.inflate(
                LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        HgSeriesDetail detail = session.detailOf(current.seriesId);

        bindHeader(activity, binding, dialog, session, current, detail);
        bindActors(activity, binding, detail);
        bindDesc(binding, current, detail);
        bindGrid(activity, binding, dialog, session, current, positions, currentGlobalPos, onPick);

        // 半屏起步（官方单高＝屏高 50%）、可拉升到近全屏
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            try {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(half);
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } catch (IllegalArgumentException ignored) {
                // behavior 未关联时降级为默认高度，不崩
            }
        });
        dialog.show();
    }

    /**
     * 头部（E6–E9）：封面（详情优先、否则条目）圆角 4dp；剧名只出剧名本体
     * （官方绑 AlbumDetailModel.title，不拼集数/完结文案，E10）；箭头点击
     * ＝进该剧集流（等价 HongguoFeedActivity.startSeries）；头部钮走现行
     * interactions 口子，点击前先过登录门（与系列单同口径、照播放页
     * onAction 门法：未登录拉壳子登录、不落状态——互动层内并无登录处理，
     * 门只能由调用方守，批三 A/S19）。
     * 作者副行（E8 形态①）：作者名＋2dp 圆点＋sub_title_list[0]，12sp 灰，
     * 哪段有数据显哪段，两段皆空整行不显。sub_title_list 在详情模型里
     * 没有字段位，只能取条目 tags（频道 cell 的 sub_title_list 经
     * HgSeriesItem.tags 携带）；集流条目不带 tags 时副段缺省、不编。
     */
    private static void bindHeader(Activity activity, DialogHongguoEpisodesBinding binding,
                                   BottomSheetDialog dialog, HgFeedSession session,
                                   HgFeedItem current, HgSeriesDetail detail) {
        String title = detail != null && detail.getTitle() != null && !detail.getTitle().isEmpty()
                ? detail.getTitle() : current.title;
        String cover = detail != null && detail.getCoverUrl() != null && !detail.getCoverUrl().isEmpty()
                ? detail.getCoverUrl() : current.coverUrl;
        binding.hgEpTitle.setText(title == null ? "" : title);
        float radius = 4f * activity.getResources().getDisplayMetrics().density;
        binding.hgEpCover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        binding.hgEpCover.setClipToOutline(true);
        ImgUtil.load(title == null ? "" : title, cover, binding.hgEpCover);

        if (current.seriesId == null) {
            binding.hgEpDetailArrow.setVisibility(View.GONE);
            binding.hgEpFav.setVisibility(View.GONE);
            binding.hgEpFollow.setVisibility(View.GONE);
        } else {
            binding.hgEpDetailArrow.setOnClickListener(v -> {
                dialog.dismiss();
                HongguoFeedActivity.startSeries(activity, current.seriesId, title, cover);
            });
            // 头部右槽同槽互斥（官方选集弹窗 aib/q7：品牌钮 dll 与追更
            // 药丸 dlw 同槽）：咱家映射——已上线正剧亮追更药丸（走
            // KIND_FOLLOW 管道，与播放页剧条药丸同一动作两态），未上线
            // 剧维持收藏钮（预约在播放页剧条药丸里办，头部不并排两件）。
            // 批三 A 抠官方定版（账 hongguo-batch3a-slotmap-20261029）：
            // dll 的文案/动作恒是追剧架那一套（t0 文案提供器＋follow 点击
            // 链）、从不变预约；canReserve 时官方把 dll 置 INVISIBLE（星钮
            // 优化件 u2 在场与否影响最终显隐、线上配置未抓到）——证据不支
            // 持把头部改成预约、也不足以定「隐藏」，本槽映射维持不动。
            if (current.reservable) {
                binding.hgEpFollow.setVisibility(View.GONE);
                binding.hgEpFav.setVisibility(View.VISIBLE);
                bindFav(binding, session.interactions, current.seriesId);
                binding.hgEpFav.setOnClickListener(v -> {
                    if (!passLoginGate(activity, session)) return;
                    session.interactions.toggleFavorite(current.seriesId);
                    bindFav(binding, session.interactions, current.seriesId);
                });
            } else {
                binding.hgEpFav.setVisibility(View.GONE);
                bindFollowPill(binding, session.interactions, current.seriesId);
                binding.hgEpFollow.setOnClickListener(v -> {
                    if (!passLoginGate(activity, session)) return;
                    session.interactions.toggleFollow(current.seriesId);
                    bindFollowPill(binding, session.interactions, current.seriesId);
                });
            }
        }

        String author = detail != null && detail.getAuthorName() != null && !detail.getAuthorName().isEmpty()
                ? detail.getAuthorName() : current.authorName;
        String sub = current.tags != null && !current.tags.isEmpty() ? current.tags.get(0) : null;
        boolean hasAuthor = author != null && !author.isEmpty();
        boolean hasSub = sub != null && !sub.isEmpty();
        if (!hasAuthor && !hasSub) {
            binding.hgEpAuthorRow.setVisibility(View.GONE);
            return;
        }
        binding.hgEpAuthorRow.setVisibility(View.VISIBLE);
        binding.hgEpAuthor.setVisibility(hasAuthor ? View.VISIBLE : View.GONE);
        if (hasAuthor) binding.hgEpAuthor.setText(author);
        binding.hgEpAuthorDot.setVisibility(hasAuthor && hasSub ? View.VISIBLE : View.GONE);
        binding.hgEpSub.setVisibility(hasSub ? View.VISIBLE : View.GONE);
        if (hasSub) binding.hgEpSub.setText(sub);
    }

    /**
     * 登录门（S19，照 HongguoFeedActivity.onAction 现行门法）：未登录先拉
     * 壳子统一登录、不调 toggle、不落状态；拉不起来如实提示。宿主 Activity
     * 由 show() 透传进来（本单是 BottomSheetDialog，宿主同体）。
     */
    private static boolean passLoginGate(Activity activity, HgFeedSession session) {
        if (!session.interactions.requiresLogin()) return true;
        boolean delivered = com.fongmi.android.tv.login.LoginManager.loginHongguoDelivered();
        if (!delivered) {
            android.widget.Toast.makeText(activity, "登录界面暂时打不开，稍后再试",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    /** 追更药丸状态回显（官方 dlw 两态同底、只换字：追更/已追更）。 */
    private static void bindFollowPill(DialogHongguoEpisodesBinding binding,
                                       HgInteractions interactions, String seriesId) {
        binding.hgEpFollow.setVisibility(View.VISIBLE);
        binding.hgEpFollowText.setText(interactions.isFollowing(seriesId) ? "已追更" : "追更");
    }

    /** 收藏钮状态回显：已收藏＝灰底灰字（色板取官方已解值），未收藏＝橙底白字。 */
    private static void bindFav(DialogHongguoEpisodesBinding binding,
                                HgInteractions interactions, String seriesId) {
        boolean fav = interactions.isFavorite(seriesId);
        binding.hgEpFav.setBackgroundResource(fav
                ? R.drawable.bg_hg_ep_fav_on : R.drawable.bg_hg_ep_fav);
        binding.hgEpFav.setTextColor(fav ? COLOR_WATCHED : 0xFFFFFFFF);
    }

    /**
     * 简介（E11）：14sp、折叠 2 行，展开文案「更多」（色 #FF527EB0）；
     * 文本没溢出 2 行就不出「更多」；简介为空整块不显。
     */
    private static void bindDesc(DialogHongguoEpisodesBinding binding,
                                 HgFeedItem current, HgSeriesDetail detail) {
        String desc = detail != null && detail.getDesc() != null ? detail.getDesc() : current.desc;
        if (desc == null || desc.isEmpty()) {
            binding.hgEpDescBlock.setVisibility(View.GONE);
            return;
        }
        binding.hgEpDesc.setText(desc);
        binding.hgEpDesc.post(() -> {
            android.text.Layout layout = binding.hgEpDesc.getLayout();
            if (layout != null && layout.getLineCount() > 0
                    && layout.getEllipsisCount(layout.getLineCount() - 1) > 0) {
                binding.hgEpDescMore.setVisibility(View.VISIBLE);
            }
        });
        binding.hgEpDescMore.setOnClickListener(v -> {
            binding.hgEpDesc.setMaxLines(Integer.MAX_VALUE);
            binding.hgEpDescMore.setVisibility(View.GONE);
        });
    }

    /**
     * 集格（E14/E16/E17/E18）：单个 RecyclerView 连续竖滚、6 列。
     * 看过档以本地断点推定（HgStore 只存最新断点：断点集之前的集与
     * 断点集本身有进度者判看过）；不可播＝条目没带 videoId。
     */
    private static void bindGrid(Activity activity, DialogHongguoEpisodesBinding binding,
                                 BottomSheetDialog dialog, HgFeedSession session,
                                 HgFeedItem current, List<Integer> positions,
                                 int currentGlobalPos, IntConsumer onPick) {
        HgStore.Progress progress = current.seriesId == null
                ? null : HgStore.progressOf(current.seriesId);
        binding.hgEpGrid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        binding.hgEpGrid.setAdapter(new RecyclerView.Adapter<EpHolder>() {
            @NonNull
            @Override
            public EpHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new EpHolder(ItemHongguoEpisodeBinding.inflate(
                        LayoutInflater.from(parent.getContext()), parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull EpHolder h, int index) {
                int globalPos = positions.get(index);
                HgFeedItem ep = session.items.get(globalPos);
                TextView tv = h.binding.hgEpNum;
                tv.setText(ep.episodeIndex > 0 ? String.valueOf(ep.episodeIndex)
                        : String.valueOf(index + 1));
                boolean isCurrent = globalPos == currentGlobalPos;
                tv.setSelected(isCurrent);
                boolean watched = progress != null && ep.episodeIndex > 0
                        && (ep.episodeIndex < progress.episodeIndex
                        || (ep.episodeIndex == progress.episodeIndex && progress.positionMs > 0));
                if (isCurrent) {
                    tv.setTextColor(COLOR_CURRENT);
                    tv.setTypeface(null, Typeface.BOLD);
                } else if (ep.videoId == null) {
                    tv.setTextColor(COLOR_UNPLAYABLE);
                    tv.setTypeface(null, Typeface.NORMAL);
                } else if (watched) {
                    tv.setTextColor(COLOR_WATCHED);
                    tv.setTypeface(null, Typeface.NORMAL);
                } else {
                    tv.setTextColor(COLOR_UNWATCHED);
                    tv.setTypeface(null, Typeface.NORMAL);
                }
                h.binding.hgEpPlaying.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
                h.itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (globalPos != currentGlobalPos) onPick.accept(globalPos);
                });
            }

            @Override
            public int getItemCount() {
                return positions.size();
            }
        });
        int curIdx = positions.indexOf(currentGlobalPos);
        if (curIdx > 0) binding.hgEpGrid.post(() -> binding.hgEpGrid.scrollToPosition(curIdx));
    }

    /**
     * 演员区（官方 ActorDescriptionLayout 口径）：筹最多取前 2 个演员，
     * 圆角 4dp、padding 6/2/6/2、字 12sp；文案只拼真字段
     * （昵称＋sub_title 饰演行，缺 sub_title 时用 role_name 拼「饰 X」）。
     * 详情没带演员就不显，不编。
     */
    private static void bindActors(Activity activity, DialogHongguoEpisodesBinding binding,
                                   HgSeriesDetail detail) {
        if (detail == null || detail.getCelebrities().isEmpty()) {
            binding.hgEpActors.setVisibility(View.GONE);
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int shown = 0;
        for (HgSeriesDetail.Celebrity c : detail.getCelebrities()) {
            if (shown >= 2) break;
            String text = c.getNickname() == null ? "" : c.getNickname();
            if (c.getSubTitle() != null && !c.getSubTitle().isEmpty()) {
                text = text.isEmpty() ? c.getSubTitle() : text + " " + c.getSubTitle();
            } else if (c.getRoleName() != null && !c.getRoleName().isEmpty()) {
                text = text.isEmpty() ? c.getRoleName() : text + " 饰 " + c.getRoleName();
            }
            if (text.isEmpty()) continue;
            TextView chip = new TextView(activity);
            chip.setText(text);
            chip.setTextSize(12);
            chip.setTextColor(0xFFFFFFFF);
            chip.setBackgroundResource(R.drawable.bg_hg_actor_chip);
            chip.setPadding((int) (6 * density), (int) (2 * density),
                    (int) (6 * density), (int) (2 * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (shown > 0) lp.setMarginStart((int) (6 * density));
            chip.setLayoutParams(lp);
            binding.hgEpActors.addView(chip);
            shown++;
        }
        binding.hgEpActors.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private static final class EpHolder extends RecyclerView.ViewHolder {
        final ItemHongguoEpisodeBinding binding;

        EpHolder(ItemHongguoEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
