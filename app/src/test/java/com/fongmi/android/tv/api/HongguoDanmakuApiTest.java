package com.fongmi.android.tv.api;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HongguoDanmakuApi 纯逻辑单测：窗口推进纪律（不推进即停，防死循环）、
 * Bilibili XML 转换（转义/时间格式/排序）、窗口合并去重、一窗解析与回验闸。
 * 样本形态照 hongguo-danmaku-trace.md §2 与第一步实打样本 danmaku-window0。
 */
public class HongguoDanmakuApiTest {

    private static final String SERIES = "7695727473111092286";
    private static final String VID = "7695729394496588862";

    // ---------- 窗口推进 ----------

    @Test
    public void windowAdvancesWhenServerPushesForward() {
        assertEquals(30000L, HongguoDanmakuApi.nextWindowStart(0, 30000, true));
        assertEquals(60000L, HongguoDanmakuApi.nextWindowStart(30000, 60000, true));
    }

    @Test
    public void windowStopsWhenNoMore() {
        assertEquals(-1L, HongguoDanmakuApi.nextWindowStart(0, 30000, false));
    }

    @Test
    public void windowStopsWhenCursorStalls() {
        // 服务端回原地/回退/没给推进值，一律停——游标不推进即停
        assertEquals(-1L, HongguoDanmakuApi.nextWindowStart(30000, 30000, true));
        assertEquals(-1L, HongguoDanmakuApi.nextWindowStart(30000, 15000, true));
        assertEquals(-1L, HongguoDanmakuApi.nextWindowStart(0, -1, true));
    }

    // ---------- XML 转换 ----------

    @Test
    public void xmlEscapesSpecialChars() {
        List<HongguoDanmakuApi.Item> items = new ArrayList<>();
        items.add(new HongguoDanmakuApi.Item(0, "a<b>&\"'c"));
        String xml = HongguoDanmakuApi.buildXml(items);
        assertTrue(xml.contains("a&lt;b&gt;&amp;&quot;&apos;c"));
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>"));
        assertTrue(xml.endsWith("</i>"));
    }

    @Test
    public void xmlDropsIllegalControlChars() {
        List<HongguoDanmakuApi.Item> items = new ArrayList<>();
        items.add(new HongguoDanmakuApi.Item(0, "前后"));
        String xml = HongguoDanmakuApi.buildXml(items);
        assertTrue(xml.contains("前后"));
    }

    @Test
    public void xmlTimeFormatAndSort() {
        List<HongguoDanmakuApi.Item> items = new ArrayList<>();
        items.add(new HongguoDanmakuApi.Item(20187, "晚"));
        items.add(new HongguoDanmakuApi.Item(2163, "早"));
        String xml = HongguoDanmakuApi.buildXml(items);
        int early = xml.indexOf("p=\"2.163,1,25,16777215,0,0\"");
        int late = xml.indexOf("p=\"20.187,1,25,16777215,0,0\"");
        assertTrue(early >= 0 && late > early);
    }

    // ---------- 合并去重 ----------

    @Test
    public void mergeDedupesByOffsetAndText() {
        List<HongguoDanmakuApi.Item> acc = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<HongguoDanmakuApi.Item> window = new ArrayList<>();
        window.add(new HongguoDanmakuApi.Item(1000, "同一条"));
        window.add(new HongguoDanmakuApi.Item(1000, "同一条"));
        window.add(new HongguoDanmakuApi.Item(1000, "同时间不同文"));
        window.add(new HongguoDanmakuApi.Item(2000, ""));
        window.add(new HongguoDanmakuApi.Item(-5, "无时间"));
        HongguoDanmakuApi.mergeWindow(acc, seen, window);
        assertEquals(2, acc.size());
    }

    // ---------- 一窗解析 ----------

    private static JSONObject windowJson(String bookId, String groupId, boolean hasMore, long next) throws Exception {
        String body = "{"
                + "\"code\":0,"
                + "\"data\":{"
                + "\"common_list_info\":{\"total\":5,\"has_more\":" + hasMore + ",\"cursor\":\"cur-1\"},"
                + "\"extra\":{\"next_query_danmaku_list_time\":" + next + "},"
                + "\"data_list\":["
                + "{\"comment\":{\"common\":{\"group_id\":\"" + groupId + "\",\"content\":{\"text\":\"第一条\"}},\"expand\":{\"book_id\":\"" + bookId + "\",\"offset_time\":2163}}},"
                + "{\"comment\":{\"common\":{\"group_id\":\"" + groupId + "\",\"content\":{\"text\":\"\"}},\"expand\":{\"book_id\":\"" + bookId + "\",\"offset_time\":3000}}},"
                + "{\"comment\":{\"common\":{\"group_id\":\"" + groupId + "\",\"content\":{\"text\":\"没时间\"}},\"expand\":{\"book_id\":\"" + bookId + "\"}}}"
                + "]}}";
        return new JSONObject(body);
    }

    @Test
    public void parseWindowKeepsValidRowsOnly() throws Exception {
        HongguoDanmakuApi.Window window = HongguoDanmakuApi.parseWindow(windowJson(SERIES, VID, true, 30000), SERIES, VID);
        assertTrue(window != null);
        assertEquals(1, window.items.size());
        assertEquals(2163L, window.items.get(0).offsetMs);
        assertEquals("第一条", window.items.get(0).text);
        assertEquals(30000L, window.nextStart);
        assertTrue(window.hasMore);
        assertEquals("cur-1", window.cursor);
        // 游标原样带出、推进交给 nextWindowStart 裁定（面板/抓取共用同一纪律）
        assertEquals(30000L, HongguoDanmakuApi.nextWindowStart(0, window.nextStart, window.hasMore));
    }

    @Test
    public void parseWindowGateRejectsForeignBook() throws Exception {
        assertNull(HongguoDanmakuApi.parseWindow(windowJson("9999999999999999999", VID, true, 30000), SERIES, VID));
        assertNull(HongguoDanmakuApi.parseWindow(windowJson(SERIES, "8888888888888888888", true, 30000), SERIES, VID));
    }

    @Test
    public void parseWindowRejectsErrorCode() throws Exception {
        JSONObject bad = new JSONObject("{\"code\":110001,\"data\":{}}");
        assertNull(HongguoDanmakuApi.parseWindow(bad, SERIES, VID));
        assertNull(HongguoDanmakuApi.parseWindow(null, SERIES, VID));
    }
}
