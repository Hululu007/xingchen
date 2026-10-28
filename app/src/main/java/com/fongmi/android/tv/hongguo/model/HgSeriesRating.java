package com.fongmi.android.tv.hongguo.model;

/**
 * 红果剧评评分卡数据（整剧评论口 comment/list 的 extra 段，本机实拉验通：
 * credibility_score 红果分／credibility_score_count 评分人数／
 * collectors_count 收藏数／can_comment_threshold 参与评分的观看门槛秒数／
 * score_cnt 带分评论数）。详情页评分卡专用；回包没带对应字段时取缺省。
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

    public HgSeriesRating(float credibilityScore, long credibilityScoreCount,
                          long collectorsCount, int canCommentThresholdSec, long scoreCount) {
        this.credibilityScore = credibilityScore;
        this.credibilityScoreCount = credibilityScoreCount;
        this.collectorsCount = collectorsCount;
        this.canCommentThresholdSec = canCommentThresholdSec;
        this.scoreCount = scoreCount;
    }

    public static HgSeriesRating empty() {
        return new HgSeriesRating(-1, -1, -1, -1, -1);
    }

    /** 评分卡有东西可摆（有分或有收藏数）。 */
    public boolean hasAny() {
        return credibilityScore >= 0 || collectorsCount >= 0;
    }
}
