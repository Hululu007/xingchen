package com.fongmi.android.tv.api.hk;

/**
 * 海阔详情收藏记录：对齐 8.83 ViewCollection 的关键字段
 * （MTitle / CUrl / picUrl / group / params / time）。
 * 查找键 = ruleTitle + episodeUrl（对齐原版 CUrl + MTitle 唯一定位）。
 */
public class HkFavorite {

    private String title = "";
    private String ruleTitle = "";
    private String ruleUrl = "";
    private String episodeUrl = "";
    private String picUrl = "";
    private String group = "";
    private long time;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title;
    }

    public String getRuleTitle() {
        return ruleTitle;
    }

    public void setRuleTitle(String ruleTitle) {
        this.ruleTitle = ruleTitle == null ? "" : ruleTitle;
    }

    public String getRuleUrl() {
        return ruleUrl;
    }

    public void setRuleUrl(String ruleUrl) {
        this.ruleUrl = ruleUrl == null ? "" : ruleUrl;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl == null ? "" : episodeUrl;
    }

    public String getPicUrl() {
        return picUrl;
    }

    public void setPicUrl(String picUrl) {
        this.picUrl = picUrl == null ? "" : picUrl;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group == null ? "" : group;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }
}
