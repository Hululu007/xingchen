package com.fongmi.android.tv.hongguo.drama;

/**
 * 搜索「用户」签（tab_type＝27）的一条账号命中：cell 的
 * celebrity_user_list[] 逐条抽出（2026-10-29 本机实拉定版：user_id 与
 * base_info{user_name/user_avatar}、user_stat.celebrity_work_cnt）。
 *
 * <p>用途：正剧条目作者 uid 的反查兜底——详情口对剧集内容恒不下发作者
 * 身份（实拉定根，author-page-why-missing-20261029 追补），会话层拿
 * 作者名/出品方品牌搜此签、同名精确匹配后按作品数定正主（潮汐剧场
 * uid＝3336352032369972 即此路取得，是结果序首位且作品数最高）。</p>
 */
public final class DramaAuthorHit {

    public final String uid;
    public final String name;
    /** 头像址；没带为 null。 */
    public final String avatar;
    /** 作品数（celebrity_work_cnt）；没带为 -1。同名多号时定正主用。 */
    public final int workCount;

    public DramaAuthorHit(String uid, String name, String avatar, int workCount) {
        this.uid = uid;
        this.name = name;
        this.avatar = avatar;
        this.workCount = workCount;
    }
}
