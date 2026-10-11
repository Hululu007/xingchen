package com.fongmi.android.tv.api.hk;

import java.util.List;
import java.util.Map;

/**
 * 直接移植自海阔 8.83 {@code com.example.hikerview.ui.video.model.PlayData}（do not rewrite）。
 * <p>
 * 仅保留 HkHttpParser 用到的字段。
 */
public class HkPlayData {

    private String url;
    private List<String> urls;
    private List<String> names;
    private Map<String, String> header;
    private List<Map<String, String>> headers;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public Map<String, String> getHeader() {
        return header;
    }

    public void setHeader(Map<String, String> header) {
        this.header = header;
    }

    public List<Map<String, String>> getHeaders() {
        return headers;
    }

    public void setHeaders(List<Map<String, String>> headers) {
        this.headers = headers;
    }
}
