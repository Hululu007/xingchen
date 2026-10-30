package com.fongmi.android.tv.hongguo.ui;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.ui.dialog.CastDialog;

import java.util.Collections;

/**
 * 红果投屏桥：main 源集到壳子投屏组件的唯一调用口（账 hongguo-cast-20261030）。
 *
 * <p>壳子 CastDialog/CastVideo 在 mobile 与 leanback 两个 flavor 源集各有一份
 * 同名同包同 API 的实现，main 引用时按当前变体解析，不搬实现、不走反射。
 * 红果播放地址恒为本机代理解密流（127.0.0.1 的 /hongguo/stream），
 * CastVideo 构造器自带把 127.0.0.1 改写成局域网 IP，电视经本机代理拉
 * 解密后的明文 MP4——上游签名/UA/DEK 全留在手机侧，电视不感知。</p>
 *
 * <p>fm(false) 照壳子直播投屏先例：红果没有壳子 History，扫码配对与
 * FongMi 接收端那条 history 路走不了，目标设备是 DLNA 电视。
 * 投中回调走壳子契约：宿主 Activity 实现 CastDialog.Listener.onCasted()。</p>
 */
public final class HgCaster {

    private HgCaster() {
    }

    /**
     * 调起壳子投屏选设备窗。
     *
     * @param title     剧名（正剧带「第N集」）
     * @param localUrl  DramaResolver 给出的本机播放地址（127.0.0.1 口径，构造器内改写）
     * @param positionMs 当前播放进度，投中后电视自动 seek 到此处
     */
    public static void cast(FragmentActivity activity, String title, String localUrl, long positionMs) {
        if (activity == null || localUrl == null || localUrl.isEmpty()) return;
        CastVideo video = new CastVideo(title == null ? "" : title, localUrl,
                Math.max(0, positionMs), Collections.emptyMap());
        CastDialog.create().video(video).fm(false).show(activity);
    }
}
