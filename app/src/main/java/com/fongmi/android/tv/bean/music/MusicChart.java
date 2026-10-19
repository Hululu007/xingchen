package com.fongmi.android.tv.bean.music;

/** 榜单（一期源抽象的榜单条目）。 */
public class MusicChart {

    public String platform;
    public String id;
    public String name;

    public MusicChart() {
    }

    public MusicChart(String platform, String id, String name) {
        this.platform = platform;
        this.id = id;
        this.name = name;
    }
}
