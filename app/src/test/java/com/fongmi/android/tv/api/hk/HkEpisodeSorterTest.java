package com.fongmi.android.tv.api.hk;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;

/**
 * HkEpisodeSorter 单测：用例 ①② 取材于用户 2026-10-23 两张真机截图的污染形态
 * （标签簇＋页内锚点、海报图直链＋多加速节点），③④⑤ 为常规形态防误删。
 */
public class HkEpisodeSorterTest {

    private static boolean[] select(String pageUrl, String... urls) {
        return HkEpisodeSorter.select(Arrays.asList(urls), pageUrl);
    }

    /** 截图一：标签与播放入口同线路混杂。标签自成更大的簇，靠本片编号命中播放簇。 */
    @Test
    public void tagsDroppedByMainIdCluster() {
        String page = "https://site.example/vod/12345.html";
        boolean[] keep = select(page,
                "https://site.example/vod/12345.html#info",   // 影片介绍（自链锚点）
                "https://site.example/vod/12345.html#comment", // 用户评价（自链锚点）
                "https://site.example/play/12345-1-1.html",   // 原片
                "https://site.example/play/12345-0-1.html",   // 片花预览
                "https://site.example/tag/101.html",           // 日本
                "https://site.example/tag/205.html",           // 青空光
                "https://site.example/tag/309.html",           // 单体作品
                "https://site.example/tag/411.html",           // 限时免费
                "https://site.example/tag/512.html");          // 写真
        assertArrayEquals(new boolean[]{false, false, true, true, false, false, false, false, false}, keep);
    }

    /** 截图二：海报展示卡（url 是封面图）＋四个不同域名的加速节点（全单例→全保留）。 */
    @Test
    public void posterImageDroppedNodesKept() {
        String page = "https://site.example/video/98765.html";
        boolean[] keep = select(page,
                "https://img.example/covers/abc98765.jpg",       // 片名条目（封面图直链）
                "https://node1.example/play/98765.html",         // 播放地址1
                "https://node2.example/play/98765.html",         // 播放地址2
                "https://node3.example/play/98765.html",         // 播放地址3
                "https://node4.example/play/98765.html");        // 播放地址4
        assertArrayEquals(new boolean[]{false, true, true, true, true}, keep);
    }

    /** 常规多集：整簇保留，规则型入口（磁力/lazy）永远保留。 */
    @Test
    public void normalSeriesAllKept() {
        String page = "https://site.example/detail/777.html";
        boolean[] keep = select(page,
                "https://site.example/play/777-1-1.html",
                "https://site.example/play/777-1-2.html",
                "https://site.example/play/777-1-3.html",
                "magnet:?xt=urn:btih:0123456789abcdef",
                "https://site.example/play/777-1-4.html@lazyRule=.js:input");
        assertArrayEquals(new boolean[]{true, true, true, true, true}, keep);
    }

    /** 全单例线路（无任何 ≥2 簇）：整线路原样全收，不误删单集源。 */
    @Test
    public void allSingletonsKeptAsIs() {
        String page = "https://site.example/movie/555.html";
        boolean[] keep = select(page,
                "https://site.example/play/555.html",
                "https://site.example/tag/9.html",
                "https://site.example/vod/556.html");
        assertArrayEquals(new boolean[]{true, true, true}, keep);
    }

    /** 无本片编号可依时保留最大簇：播放簇 3 条胜过标签簇 2 条，单例剔除。 */
    @Test
    public void largestClusterWhenNoId() {
        String page = "https://site.example/watch/abc";
        boolean[] keep = select(page,
                "https://site.example/play/ep-1001.html",
                "https://site.example/play/ep-1002.html",
                "https://site.example/play/ep-1003.html",
                "https://site.example/tag/japan.html",
                "https://site.example/tag/actress.html",
                "https://site.example/about/us.html");
        assertArrayEquals(new boolean[]{true, true, true, false, false, false}, keep);
    }

    /** 查询型播放址：同路径同键名不同集数聚为一簇；图片与自链先剔。 */
    @Test
    public void queryStyleEpisodesCluster() {
        String page = "https://site.example/detail.php?id=31415";
        boolean[] keep = select(page,
                "https://site.example/play.php?id=31415&ep=1",
                "https://site.example/play.php?id=31415&ep=2",
                "https://site.example/play.php?id=31415&ep=3",
                "https://site.example/detail.php?id=31415",
                "https://img.example/p/31415.png");
        assertArrayEquals(new boolean[]{true, true, true, false, false}, keep);
    }

    @Test
    public void helpers() {
        org.junit.Assert.assertEquals("31415", HkEpisodeSorter.extractMainId("https://a/b.php?id=31415&x=2"));
        org.junit.Assert.assertEquals("12345", HkEpisodeSorter.extractMainId("https://a/vod/12345.html"));
        org.junit.Assert.assertNull(HkEpisodeSorter.extractMainId("https://a/watch/abc"));
        org.junit.Assert.assertTrue(HkEpisodeSorter.containsIdToken("https://a/play/12345-1-1.html", "12345"));
        org.junit.Assert.assertFalse(HkEpisodeSorter.containsIdToken("https://a/play/12346-1-1.html", "12345"));
        List<String> none = java.util.Collections.emptyList();
        assertArrayEquals(new boolean[0], HkEpisodeSorter.select(none, "https://a/1.html"));
    }
}
