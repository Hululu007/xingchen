package com.fongmi.android.tv.hongguo.model;

import java.util.List;

/**
 * 单条弹幕（官方弹幕＝评论口弹幕档，挖掘账 danmaku-dig-20261028.md）：
 * 正文与评论同体（common.content.text），弹幕专属字段在 expand——
 * offset_time 是片内出现时刻（毫秒）、danmaku_position_type
 * 0 滚动／1 底／2 顶（缺省 0）、danmaku_style 给颜色（缺省白字）。
 */
public final class HgDanmaku {

    public final String text;
    public final long offsetTimeMs;
    public final int positionType;
    /** 颜色 hex（如 "#FF0000"），回包没给为 null（渲染按默认白）。 */
    public final String colorHex;
    public final long diggCount;

    public HgDanmaku(String text, long offsetTimeMs, int positionType, String colorHex, long diggCount) {
        this.text = text;
        this.offsetTimeMs = offsetTimeMs;
        this.positionType = positionType;
        this.colorHex = colorHex;
        this.diggCount = diggCount;
    }

    /** 一窗弹幕（服务端 30 秒切窗）：条目＋续拉游标＋下一窗起点。 */
    public static final class Window {
        public final List<HgDanmaku> items;
        /** common_list_info.cursor 原样回传续拉；无为 null。 */
        public final String cursor;
        /** extra.next_query_danmaku_list_time：播放头跨过它拉下一窗；回包没给为 -1。 */
        public final long nextQueryTimeMs;
        /** common_list_info.total（本集弹幕总量口径），没给为 -1。 */
        public final long total;

        public Window(List<HgDanmaku> items, String cursor, long nextQueryTimeMs, long total) {
            this.items = items;
            this.cursor = cursor;
            this.nextQueryTimeMs = nextQueryTimeMs;
            this.total = total;
        }
    }
}
