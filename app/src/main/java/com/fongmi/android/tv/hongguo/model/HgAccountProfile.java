package com.fongmi.android.tv.hongguo.model;

/**
 * 红果账号资料（passport account/info 定版，account-info-dig-20261028.md）：
 * 「我的」头部展示用——昵称（screen_name）＋头像（avatar_url）＋账号 id。
 * 字段是客户端愿读的键；网页会话实际下发哪些待新版首登对回包定论，
 * 解析层对缺字段容忍（全空返回 null，不拿半份资料盖掉旧镜像）。
 */
public final class HgAccountProfile {

    public final String displayName;
    public final String avatarUrl;
    public final String userId;

    public HgAccountProfile(String displayName, String avatarUrl, String userId) {
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.userId = userId;
    }
}
