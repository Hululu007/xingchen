package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import com.fongmi.android.tv.databinding.DialogHongguoCommentsBinding;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 评论面板壳：打开路径与容器在本阶段（三阶段）就位，内容四阶段落。
 *
 * <p>四阶段接法（树里已有材料）：api 层 HongguoCommentApi 是壳子侧红果评论
 * 抓取器（与 HongguoDanmakuApi 同层共建、现挂点播播放链），四阶段把它的
 * 列表数据灌进 hg_cm_container、替换空态即可，页面侧不用改。</p>
 */
public final class HgCommentsPanel {

    private HgCommentsPanel() {
    }

    public static void show(Activity activity, HgFeedItem item) {
        DialogHongguoCommentsBinding binding = DialogHongguoCommentsBinding.inflate(
                LayoutInflater.from(activity));
        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        dialog.setContentView(binding.getRoot());
        binding.hgCmClose.setOnClickListener(v -> dialog.dismiss());
        int half = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            try {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(half);
                behavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            } catch (IllegalArgumentException ignored) {
            }
        });
        dialog.show();
    }
}
