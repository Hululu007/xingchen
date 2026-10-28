package com.fongmi.android.tv.hongguo.model;

/**
 * 红果剧评评分卡数据（整剧评论口 comment/list 的 extra 段，本机实拉验通：
 * credibility_score 红果分／credibility_score_count 评分人数／
 * collectors_count 收藏数／can_comment_threshold 参与评分的观看门槛秒数／
 * score_cnt 带分评论数／user_comment 本人那条带分评论——登录态才下发，
 * 匿名回包无此键）。详情页评分卡专用；回包没带对应字段时取缺省。
 */
public final class HgSeriesRating {

    /** 红果分（一位小数）；没带为 -1。 */
    public final float credibilityScore;
    /** 评分人数；没带为 -1。 */
    public final long credibilityScoreCount;
    /** 收藏数；没带为 -1。 */
    public final long collectorsCount;
    /** 参与评分门槛（秒，官方文案「看5分钟参与评分」＝300）；没带为 -1。 */
    public final int canCommentThresholdSec;
    /** 带分评论（剧评）数；没带为 -1。 */
    public final long scoreCount;
    /** 本人已评的分（十分制 0..10，extra.user_comment.expand.score）；未评/未登录为 -1。 */
    public final int myScore;
    /** 本人那条带分评论的 id（改分走 comment/update 用）；没有为 ""。 */
    public final String myCommentId;

    public HgSeriesRating(float credibilityScore, long credibilityScoreCount,
                          long collectorsCount, int canCommentThresholdSec, long scoreCount) {
        this(credibilityScore, credibilityScoreCount, collectorsCount,
                canCommentThresholdSec, scoreCount, -1, "");
    }

    public HgSeriesRating(float credibilityScore, long credibilityScoreCount,
                          long collectorsCount, int canCommentThresholdSec, long scoreCount,
                          int myScore, String myCommentId) {
        this.credibilityScore = credibilityScore;
        this.credibilityScoreCount = credibilityScoreCount;
        this.collectorsCount = collectorsCount;
        this.canCommentThresholdSec = canCommentThresholdSec;
        this.scoreCount = scoreCount;
        this.myScore = myScore;
        this.myCommentId = myCommentId == null ? "" : myCommentId;
    }

    public static HgSeriesRating empty() {
        return new HgSeriesRating(-1, -1, -1, -1, -1);
    }

    /** 评分卡有东西可摆（有分或有收藏数）。 */
    public boolean hasAny() {
        return credibilityScore >= 0 || collectorsCount >= 0;
    }

    /** 本人已评过（改分走 update 口）。 */
    public boolean hasMine() {
        return myScore >= 0 && !myCommentId.isEmpty();
    }
}
