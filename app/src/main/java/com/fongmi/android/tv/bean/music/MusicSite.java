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

    /**
     * 脚本格式："freejs"（MusicFree 插件）或 "lx"（落雪脚本）。
     * 老数据没有这个字段（null）时按 freejs 处理，见 MusicSourceManager.isLx。
     */
    public String format;
    /** 本地文件安装时的插件文件绝对路径；在线安装为空。删除插件时连文件一起删 */
    public String localPath;

    public MusicSite() {
    }

    public MusicSite(String platform, String name, String version, String srcUrl) {
        this.platform = platform;
        this.name = name;
        this.version = version;
        this.srcUrl = srcUrl;
    }
}
