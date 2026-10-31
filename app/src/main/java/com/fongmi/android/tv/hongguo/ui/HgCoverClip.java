package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/**
 * 封面圆角·视图层裁剪（#4a/#12 全树统一口径）。
 *
 * <p>病根：Glide 的 RoundedCorners 只圆在位图自身四角，ImageView
 * 再以 CENTER_CROP 二次放大裁切时圆角被裁掉、封面仍是直角。圆角
 * 必须落在视图裁剪层：outline 按视图实时尺寸给圆角矩形＋
 * clipToOutline，位图怎么裁、圆角都在。</p>
 */
public final class HgCoverClip {

    private HgCoverClip() {
    }

    /** 给封面视图挂圆角裁剪（半径 px，可在 bind 里重复调、幂等）。 */
    public static void round(ImageView iv, final int radiusPx) {
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        iv.setClipToOutline(true);
    }
}
