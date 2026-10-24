package com.fongmi.android.tv.bean.music;

/** 榜单（一期源抽象的榜单条目）。
 * 第二波：补瓜子 TopListItem 同名字段（有源给值、无源留空不编）——QQ GetAll 条目无这三字段、
 * FreeJS 榜单条目 rawJson 里有同名字段时才填（见 MusicChartsActivity.loadCharts 的 rawJson 映射）；
 * 取不到时 UI 不显对应行，不拿详情曲目数冒充榜总曲数。 */
public class MusicChart {

    public String platform;
    public String id;
    public String name;
    /** 榜单封面（插件条目原样带回，可空） */
    public String cover;
    /** 插件榜单条目原文 JSON：详情接口要整个条目对象，裸 id 不够（见 FreeJsBridge.getTopListDetail） */
    public String rawJson;
    /** 榜单总曲目数（0 = 源没给，不显） */
    public int trackCount;
    /** 更新频率文案，如“每天更新”（空 = 源没给，不显） */
    public String updateFrequency;
    /** 榜单简介（空 = 源没给，不显） */
    public String description;

    public MusicChart() {
    }

    public MusicChart(String platform, String id, String name) {
        this.platform = platform;
        this.id = id;
        this.name = name;
    }
}
