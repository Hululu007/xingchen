package com.fongmi.android.tv.api.hk;

import java.util.Date;

/**
 * 小程序收藏记录（移植自 8.83 ViewCollection）。
 * 8.83 原文：com/example/hikerview/model/ViewCollection.smali
 * 字段、getter/setter 与原文一致。LitePal 换成 HkCollectService（SharedPreferences JSON）。
 */
public class HkViewCollection {

    private String CUrl;
    private String MITitle;
    private String MTitle;
    private String desc;
    private String extraData;
    private String group;
    private boolean isUpdating;
    private String lastClick;
    private String params;
    private String picUrl;
    private Date time;
    private String videoUrl;

    public HkViewCollection() {
    }

    public String getCUrl() {
        return CUrl;
    }

    public void setCUrl(String CUrl) {
        this.CUrl = CUrl;
    }

    public String getMITitle() {
        return MITitle;
    }

    public void setMITitle(String MITitle) {
        this.MITitle = MITitle;
    }

    public String getMTitle() {
        return MTitle;
    }

    public void setMTitle(String MTitle) {
        this.MTitle = MTitle;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getExtraData() {
        return extraData;
    }

    public void setExtraData(String extraData) {
        this.extraData = extraData;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public boolean isUpdating() {
        return isUpdating;
    }

    public void setUpdating(boolean updating) {
        isUpdating = updating;
    }

    public String getLastClick() {
        return lastClick;
    }

    public void setLastClick(String lastClick) {
        this.lastClick = lastClick;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getPicUrl() {
        return picUrl;
    }

    public void setPicUrl(String picUrl) {
        this.picUrl = picUrl;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }
}
