package com.fongmi.android.tv.hongguo.model;

/**
 * 红果用户资料（GET /reading/user/profile/get/ 定版，a45/k.java:344
 * GetUserProfile 逐字；模型 GetUserProfileData/UgcUserInfo/UserBaseInfo/
 * UserRelation 的 @SerializedName 逐字段）：ugc_user.base_info 的名/头像/简介
 * ＋user_relation 的三计数（follow_user_num 关注／fans_num 粉丝／
 * recv_digged_count 获赞）与 relation_type（我与他的关系）。
 *
 * <p>消费面：「我的」头部关系行（HongguoMineRelationLayout b98 三组拼法）
 * 与作者主页数据行（SeriesProfileSocialRecordLayout ct0 三组拼法）同源；
 * 作者页关注钮初态以 relation_type 为准（Follow=2/MutualFollow=4 记已关注，
 * 枚举值 UserRelationType 定版），本地镜像只作回源前的占位与失败兜底。</p>
 */
public final class HgUserProfile {

    public final String userId;
    public final String name;
    public final String avatar;
    public final String desc;
    public final int followUserNum;
    public final int fansNum;
    public final int recvDiggedCount;
    /** relation_type 原始值（数字 1–4 或枚举名字符串），无法判读时为 null。 */
    public final String relationType;
    /** 我已关注他（relation_type＝Follow/MutualFollow）；判读不出为 null。 */
    public final Boolean followedByMe;

    public HgUserProfile(String userId, String name, String avatar, String desc,
                         int followUserNum, int fansNum, int recvDiggedCount,
                         String relationType, Boolean followedByMe) {
        this.userId = userId;
        this.name = name;
        this.avatar = avatar;
        this.desc = desc;
        this.followUserNum = followUserNum;
        this.fansNum = fansNum;
        this.recvDiggedCount = recvDiggedCount;
        this.relationType = relationType;
        this.followedByMe = followedByMe;
    }

    /** 计数三组齐备才算有数据行（任一计数缺失时整行不显，不摆半份数）。 */
    public boolean hasRelationCounts() {
        return followUserNum >= 0 && fansNum >= 0 && recvDiggedCount >= 0;
    }
}
