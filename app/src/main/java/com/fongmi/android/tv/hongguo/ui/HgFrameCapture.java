package com.fongmi.android.tv.hongguo.ui;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.media3.ui.PlayerView;

/**
 * 播放面当前帧抓取（切集末帧冻结用）：把 PlayerView 正在显示的
 * 这一帧复制成 Bitmap，交给切集流程在新集首帧到达前留屏。
 *
 * <p>口径：TextureView 走 getBitmap（同步）；SurfaceView 走
 * PixelCopy（API 26+，异步、回调在传入 Handler 上）；其余情形
 * （低版本/面未就绪/复制失败）一律回 null，调用方按无冻结走
 * 常路，不许拿黑位图冒充帧——PixelCopy 成功但内容不可知，
 * 回调只在 SUCCESS 时给图。</p>
 */
public final class HgFrameCapture {

    public interface Callback {
        /** bmp 为 null 表示没抓到（调用方不冻结、走常路）。 */
        void onFrame(Bitmap bmp);
    }

    private HgFrameCapture() {
    }

    public static void capture(PlayerView playerView, Handler handler, Callback cb) {
        if (playerView == null || cb == null) return;
        View sv = playerView.getVideoSurfaceView();
        if (sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0) {
            cb.onFrame(null);
            return;
        }
        if (sv instanceof TextureView) {
            Bitmap bmp = null;
            try {
                bmp = ((TextureView) sv).getBitmap();
            } catch (Throwable ignored) {
            }
            cb.onFrame(bmp);
            return;
        }
        if (sv instanceof SurfaceView && Build.VERSION.SDK_INT >= 26 && handler != null) {
            final Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(sv.getWidth(), sv.getHeight(),
                        Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                cb.onFrame(null);
                return;
            }
            try {
                PixelCopy.request((SurfaceView) sv, bmp, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        cb.onFrame(bmp);
                    } else {
                        bmp.recycle();
                        cb.onFrame(null);
                    }
                }, handler);
            } catch (Throwable t) {
                bmp.recycle();
                cb.onFrame(null);
            }
            return;
        }
        cb.onFrame(null);
    }
}
