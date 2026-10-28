package com.fongmi.android.tv.hongguo.model;

import java.util.List;

/** 红果评论一条（hongguo 包自有模型，不借规则年代 api 层的类）。 */
public final class HgComment {

    public final String id;
    public final String author;
    public final String avatar;
    public final String content;
    /** 创建时间（秒）。 */
    public final long created;
    public final long likes;
    public final long replyCount;
    /** 仅回复条目有值：被回复人昵称。 */
    public final String replyTo;
    /** 评论者 uid（base_info.user_id；点头像/名进作者页用）；没带为空串。 */
    public final String userId;
    /** 剧评评分（十分制，expand.score；剧评条目才有）；没带为 -1。 */
    public final int score;

    public HgComment(String id, String author, String avatar, String content,
                     long created, long likes, long replyCount, String replyTo) {
        this(id, author, avatar, content, created, likes, replyCount, replyTo, "", -1);
    }

    public HgComment(String id, String author, String avatar, String content,
                     long created, long likes, long replyCount, String replyTo,
                     String userId, int score) {
        this.id = id;
        this.author = author;
        this.avatar = avatar;
        this.content = content;
        this.created = created;
        this.likes = likes;
        this.replyCount = replyCount;
        this.replyTo = replyTo;
        this.userId = userId == null ? "" : userId;
        this.score = score;
    }

    /** 评论一页：条目＋总数＋下一页游标（hasMore=false 时游标为空串）。 */
    public static final class Page {
        public final List<HgComment> items;
        public final long total;
        public final String nextCursor;
        public final boolean hasMore;

        public Page(List<HgComment> items, long total, String nextCursor, boolean hasMore) {
            this.items = items;
            this.total = total;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }
    }
}
