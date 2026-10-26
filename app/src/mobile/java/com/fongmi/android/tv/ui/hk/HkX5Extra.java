package com.fongmi.android.tv.ui.hk;

import java.util.List;
import java.util.Map;

/**
 * 照 8.83 com.example.hikerview.ui.home.model.article.extra.X5Extra 转写（字段与默认值逐项对原文）。
 * 默认值：canBack=false、redirect=true、showProgress=true、autoPlay=false、imgLongClick=true，其余 false/null。
 * 原版经 fastjson 按字段名映射 JSON，星辰由 HkIWebViewParser.generateExtra 用 Gson 手工映射等价字段。
 * 扫描 O10：16 字段全钉 @SerializedName（值＝字段名）——extra JSON 来自规则作者，
 * 无注解时 R8 改名后 release 包全字段读不到、恒回默认。
 */
public class HkX5Extra {

    @com.google.gson.annotations.SerializedName("autoPlay")
    private boolean autoPlay;
    @com.google.gson.annotations.SerializedName("blockRules")
    private List<String> blockRules;
    @com.google.gson.annotations.SerializedName("canBack")
    private boolean canBack;
    @com.google.gson.annotations.SerializedName("disableX5")
    private boolean disableX5;
    @com.google.gson.annotations.SerializedName("floatVideo")
    private boolean floatVideo;
    @com.google.gson.annotations.SerializedName("headers")
    private Map<String, String> headers;
    @com.google.gson.annotations.SerializedName("imgLongClick")
    private boolean imgLongClick = true;
    @com.google.gson.annotations.SerializedName("js")
    private String js;
    @com.google.gson.annotations.SerializedName("jsLoadingInject")
    private boolean jsLoadingInject;
    @com.google.gson.annotations.SerializedName("redirect")
    private boolean redirect = true;
    @com.google.gson.annotations.SerializedName("referer")
    private String referer;
    @com.google.gson.annotations.SerializedName("showProgress")
    private boolean showProgress = true;
    @com.google.gson.annotations.SerializedName("ua")
    private String ua;
    @com.google.gson.annotations.SerializedName("urlInterceptor")
    private String urlInterceptor;
    @com.google.gson.annotations.SerializedName("videoExcludeRules")
    private List<String> videoExcludeRules;
    @com.google.gson.annotations.SerializedName("videoRules")
    private List<String> videoRules;

    public boolean isAutoPlay() {
        return autoPlay;
    }

    public void setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
    }

    public List<String> getBlockRules() {
        return blockRules;
    }

    public void setBlockRules(List<String> blockRules) {
        this.blockRules = blockRules;
    }

    public boolean isCanBack() {
        return canBack;
    }

    public void setCanBack(boolean canBack) {
        this.canBack = canBack;
    }

    public boolean isDisableX5() {
        return disableX5;
    }

    public void setDisableX5(boolean disableX5) {
        this.disableX5 = disableX5;
    }

    public boolean isFloatVideo() {
        return floatVideo;
    }

    public void setFloatVideo(boolean floatVideo) {
        this.floatVideo = floatVideo;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public boolean isImgLongClick() {
        return imgLongClick;
    }

    public void setImgLongClick(boolean imgLongClick) {
        this.imgLongClick = imgLongClick;
    }

    public String getJs() {
        return js;
    }

    public void setJs(String js) {
        this.js = js;
    }

    public boolean isJsLoadingInject() {
        return jsLoadingInject;
    }

    public void setJsLoadingInject(boolean jsLoadingInject) {
        this.jsLoadingInject = jsLoadingInject;
    }

    public boolean isRedirect() {
        return redirect;
    }

    public void setRedirect(boolean redirect) {
        this.redirect = redirect;
    }

    public String getReferer() {
        return referer;
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public boolean isShowProgress() {
        return showProgress;
    }

    public void setShowProgress(boolean showProgress) {
        this.showProgress = showProgress;
    }

    public String getUa() {
        return ua;
    }

    public void setUa(String ua) {
        this.ua = ua;
    }

    public String getUrlInterceptor() {
        return urlInterceptor;
    }

    public void setUrlInterceptor(String urlInterceptor) {
        this.urlInterceptor = urlInterceptor;
    }

    public List<String> getVideoExcludeRules() {
        return videoExcludeRules;
    }

    public void setVideoExcludeRules(List<String> videoExcludeRules) {
        this.videoExcludeRules = videoExcludeRules;
    }

    public List<String> getVideoRules() {
        return videoRules;
    }

    public void setVideoRules(List<String> videoRules) {
        this.videoRules = videoRules;
    }
}
