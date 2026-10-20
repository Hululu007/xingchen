package com.fongmi.android.tv.bean.music;

/** 榜单（一期源抽象的榜单条目）。 */
public class MusicChart {

    public String platform;
    public String id;
    public String name;
    /** 榜单封面（插件条目原样带回，可空） */
    public String cover;
    /** 插件榜单条目原文 JSON：详情接口要整个条目对象，裸 id 不够（见 FreeJsBridge.getTopListDetail） */
    public String rawJson;

    public MusicChart() {
    }

    public MusicChart(String platform, String id, String name) {
        this.platform = platform;
        this.id = id;
        this.name = name;
    }
}
