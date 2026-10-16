package com.fongmi.android.tv.ktv;

/**
 * 一首可点的歌。两种出身：
 * server = 点歌服务器（/v2/ 协议，编号点歌，文件可能双音轨）；
 * vod = 他的点播源（站键+视频 id+选集名，唱时走源的播放解析）。
 */
public class KtvSong {

    public String origin = "server"; // server | vod
    public String number = "";       // 服务器歌曲编号
    public String name = "";
    public String singer = "";
    public String acronym = "";
    public String mtvOrVcd = "";     // 服务器文件的双音轨形态标记（原样保留）
    public String languageId = "";
    public String typeId = "";
    public String pic = "";
    // vod 出身才用
    public String siteKey = "";
    public String siteName = "";
    public String vodId = "";
    public String flag = "";         // 线路名（唱时走 playerContent 解析要带）
    public String episode = "";      // 选集名（点播源里那一集就是那首歌）
    public String episodeUrl = "";   // 选集的原始 id/url（解析时回传给源）

    public boolean isFromServer() {
        return !"vod".equals(origin);
    }

    /** 存档去重用的身份键。 */
    public String identity() {
        if (isFromServer()) return "s|" + number + "|" + name;
        return "v|" + siteKey + "|" + vodId + "|" + episode;
    }

    public String title() {
        return name == null || name.isEmpty() ? episode : name;
    }

    public String sub() {
        String s = singer == null ? "" : singer;
        if (isFromServer()) return s;
        String src = siteName == null || siteName.isEmpty() ? "点播源" : siteName;
        return s.isEmpty() ? src : s + " · " + src;
    }
}
