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
     * 是否支持搜歌（type=music）的落盘判据：0 未知（要建桥实测）、1 支持、2 不支持。
     * 搜索页枚举页签时靠它免建桥：歌词型插件不必为了「判它不能搜歌」先把整座桥
     * （下载＋QuickJS 冷启求值）白建一遍（第十件：搜索慢的主因之一）。
     * 插件更新（applyUpdate）时清回 0 重新实测。
     */
    public int musicSearch = 0;

    /**
     * 脚本格式："freejs"（MusicFree 插件）或 "lx"（落雪脚本）。
     * 老数据没有这个字段（null）时按 freejs 处理，见 MusicSourceManager.isLx。
     */
    public String format;
    /** 本地文件安装时的插件文件绝对路径；在线安装为空。删除插件时连文件一起删 */
    public String localPath;

    /**
     * 运行时标记（transient，不落盘）：后加的预置源——不是首播种年代就在内置表里的老预置。
     * 老用户存量从没被播种过它，升级后由 MusicSourceManager 补播种；老预置不带此标记，
     * 存量里缺了只当用户已删、永不补。标记只由 builtinSites() 构造时打，存量读回恒 false。
     */
    public transient boolean lateBuiltin;

    public MusicSite() {
    }

    public MusicSite(String platform, String name, String version, String srcUrl) {
        this.platform = platform;
        this.name = name;
        this.version = version;
        this.srcUrl = srcUrl;
    }
}
