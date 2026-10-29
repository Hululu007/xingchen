package com.fongmi.android.tv.hongguo.model;

import java.util.List;

/**
 * 单条弹幕（官方弹幕＝评论口弹幕档，挖掘账 danmaku-dig-20261028.md）：
 * 正文与评论同体（common.content.text），弹幕专属字段在 expand——
 * offset_time 是片内出现时刻（毫秒）、danmaku_position_type
 * 0 滚动／1 底／2 顶（缺省 0）、danmaku_style 给颜色（缺省白字）。
 * 点赞面字段（完工账 danmaku-completion-20261029.md，官方包＋存档回包
 * 双证）：comment_id 在 comment 层（串/数两形都有）；本人点赞态在
 * comment.user_action.user_digg／user_disagree；expand.show_digg 是
 * 服务端「带点赞尾巴展示」旗（SerialName 实证，非本地阈值），
 * expand.hot_danmaku 是「热门」旗（官方拿它挂 +1 跟发尾巴）；
 * expand.add_one_parent_id 非空＝这条是 +1 跟发出来的拷贝。
 */
public final class HgDanmaku {

    public final String text;
    public final long offsetTimeMs;
    public final int positionType;
    /** 颜色 hex（如 "#FF0000"），回包没给为 null（渲染按默认白）。 */
    public final String colorHex;
    public final long diggCount;
    /** 评论 id（弹幕点赞 do_action 的 object_id）；回包没给为 ""。 */
    public final String commentId;
    /** 本人已点赞（user_action.user_digg）。 */
    public final boolean userDigg;
    /** 本人已点不喜欢（user_action.user_disagree）。 */
    public final boolean userDisagree;
    /** 服务端点赞尾巴旗（expand.show_digg）：官方只给这类条目挂心形尾巴。 */
    public final boolean showDigg;
    /** 服务端热门旗（expand.hot_danmaku）：官方给这类条目挂 +1 尾巴。 */
    public final boolean hotDanmaku;
    /** +1 跟发的母条 id（expand.add_one_parent_id）；不是跟发条为 ""。 */
    public final String addOneParentId;

    public HgDanmaku(String text, long offsetTimeMs, int positionType, String colorHex, long diggCount) {
        this(text, offsetTimeMs, positionType, colorHex, diggCount, "", false, false, false, false, "");
    }

    public HgDanmaku(String text, long offsetTimeMs, int positionType, String colorHex,
                     long diggCount, String commentId, boolean userDigg, boolean userDisagree,
                     boolean showDigg, boolean hotDanmaku, String addOneParentId) {
        this.text = text;
        this.offsetTimeMs = offsetTimeMs;
        this.positionType = positionType;
        this.colorHex = colorHex;
        this.diggCount = diggCount;
        this.commentId = commentId == null ? "" : commentId;
        this.userDigg = userDigg;
        this.userDisagree = userDisagree;
        this.showDigg = showDigg;
        this.hotDanmaku = hotDanmaku;
        this.addOneParentId = addOneParentId == null ? "" : addOneParentId;
    }

    /** 点赞态翻转后的副本（乐观更新／回滚用，其余字段原样）。 */
    public HgDanmaku withDigg(boolean digg, long count) {
        return new HgDanmaku(text, offsetTimeMs, positionType, colorHex, Math.max(0, count),
                commentId, digg, userDisagree, showDigg, hotDanmaku, addOneParentId);
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
