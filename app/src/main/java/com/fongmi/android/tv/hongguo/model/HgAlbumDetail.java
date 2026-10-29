package com.fongmi.android.tv.hongguo.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 合集详情（UGC album，普查分册一卡 8）：POST /novel/player/album_detail/v1/
 * 回包 data.album_data 的头部字段＋集列表行。官方对应物＝包内
 * AlbumDetailModel（jadx6 data/ugc）＋VideoAlbumDetailActivity/
 * VideoAlbumHeaderLayout：头部＝封面／标题／subTitleList 标签行／作者行
 * （UgcUserTagView：uid＋名＋头像）／简介（CollapsibleTextView），集列表
 * IAlbumEpisodeListView（Scene.DETAIL），点集以 video.k 携 albumId 起播。
 *
 * <p>字段只收回包实有键（2026-10-29 存档真回包 tabprobe-20261029
 * -searchparity-album 逐键）：album_id_str／title／cover／intro／
 * episode_cnt／sub_title_list／hot_score_text／followed_cnt／play_cnt／
 * series_status／color_hex／ugc_user_info{user_id, base_info{user_name,
 * user_avatar}, user_relation{can_follow, relation_type}}。官方模型另有
 * collectCnt（右列星计数源），本回包无 collect_cnt 键——不编，收藏数
 * 只用 followed_cnt。多季系列回包（series_list 形）头部字段大半缺失，
 * 对应 getter 为空/缺省，消费端按有才显处理。</p>
 */
public final class HgAlbumDetail {

    public final String albumId;
    public final String title;
    public final String cover;
    public final String intro;
    /** 合集总条数（episode_cnt）；没带为 -1。 */
    public final int episodeCount;
    /** 副标题段（sub_title_list，如「全14集」）；没带为空表。 */
    public final List<String> subTitles;
    /** 热度文案（hot_score_text，如「647万热度」）；没带为 null。 */
    public final String hotScoreText;
    /** 收藏数（followed_cnt）；没带为 -1。 */
    public final long followedCnt;
    /** 播放数（play_cnt）；没带为 -1。 */
    public final long playCnt;
    /** 完结态原值（series_status）；没带为 null。 */
    public final Integer seriesStatus;
    /** 主色（color_hex）；没带为 null。 */
    public final String colorHex;
    /** 作者 uid（ugc_user_info.user_id）；没带为 null。 */
    public final String authorUid;
    public final String authorName;
    public final String authorAvatar;
    /** 作者可否被关注（user_relation.can_follow）；没带为 false。 */
    public final boolean authorCanFollow;
    /** 作者关系态原值（user_relation.relation_type）；没带为 -1。
     * 已关注判读与 HgParser.parseUserProfile 同口径：2/4 为已关注。 */
    public final int authorRelationType;
    /** 集列表行（与 HgParser.parseAlbum 同一份抽取）。 */
    public final List<HgSeriesItem> rows;

    public HgAlbumDetail(String albumId, String title, String cover, String intro,
                         int episodeCount, List<String> subTitles, String hotScoreText,
                         long followedCnt, long playCnt, Integer seriesStatus,
                         String colorHex, String authorUid, String authorName,
                         String authorAvatar, boolean authorCanFollow,
                         int authorRelationType, List<HgSeriesItem> rows) {
        this.albumId = albumId;
        this.title = title;
        this.cover = cover;
        this.intro = intro;
        this.episodeCount = episodeCount;
        this.subTitles = subTitles == null ? Collections.emptyList()
                : Collections.unmodifiableList(subTitles);
        this.hotScoreText = hotScoreText;
        this.followedCnt = followedCnt;
        this.playCnt = playCnt;
        this.seriesStatus = seriesStatus;
        this.colorHex = colorHex;
        this.authorUid = authorUid;
        this.authorName = authorName;
        this.authorAvatar = authorAvatar;
        this.authorCanFollow = authorCanFollow;
        this.authorRelationType = authorRelationType;
        this.rows = rows == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(rows));
    }

    /** 作者当前是否已被关注（relation_type 2/4 口径，同 parseUserProfile）。 */
    public boolean isAuthorFollowed() {
        return authorRelationType == 2 || authorRelationType == 4;
    }
}
