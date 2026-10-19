package com.fongmi.android.tv.bean.music;

/**
 * 音乐源（一期源抽象契约）。
 */
public class MusicSite {

    public String platform;
    public String name;
    public String version;
    public String srcUrl;

    /** 运行时状态：是否已连接 */
    public boolean connected;
    /** 运行时状态：曲目数（-1 未知） */
    public int trackCount = -1;

    /** 插件作者（源层读插件元数据回填） */
    public String author = "";
    /** 是否启用（源层持久化，用户可开关） */
    public boolean enabled = true;

    public MusicSite() {
    }

    public MusicSite(String platform, String name, String version, String srcUrl) {
        this.platform = platform;
        this.name = name;
        this.version = version;
        this.srcUrl = srcUrl;
    }
}
