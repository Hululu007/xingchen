package com.fongmi.android.tv.api.hk;

import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 直接移植自海阔 8.83 {@code com.example.hikerview.service.parser.SearchParser}（do not rewrite）。
 * <p>
 * 搜索结果页解析：findRule 6 段 = 列表;标题;链接;描述;详情;图片。
 * 适配：
 * <ul>
 * <li>SearchEngine → {@link HkRule}（取 findRule 相关字段，调用方传入）</li>
 * <li>MovieRule → {@link HkRule}；CommonParser → {@link HkCommonParser}</li>
 * <li>SettingConfig → 去掉（走非开发者模式分支，即正常逻辑）</li>
 * <li>JsEngineBridge.parseCallBack → {@link JsSearchBridge}（宿主注入）</li>
 * <li>SearchResult → {@link SearchResult}（hk 包已存在，字段齐全）</li>
 * <li>Timber → Log；StringUtil → {@link HkParserKit}</li>
 * </ul>
 */
public class HkSearchParser {
    private static final String TAG = "HkSearchParser";

    /** 8.83 SearchJsCallBack 原样。 */
    public interface SearchJsCallBack<T> {
        void showErr(String msg);

        void showData(T data);
    }

    /** js: 搜索模式的 JS 求值桥（对应 8.83 JsEngineBridge.parseCallBack）。 */
    public interface JsSearchBridge {
        void parseCallBack(String baseUrl, HkRule rule, String html, Map<String, Object> vars,
                           SearchJsCallBack<List<SearchResult>> callback);
    }

    private static volatile JsSearchBridge jsSearchBridge;

    public static void setJsSearchBridge(JsSearchBridge bridge) {
        jsSearchBridge = bridge;
    }

    /**
     * 8.83 原文。
     *
     * @param baseUrl     搜索基址（8.83 的 str，即 searchEngine 所在页 URL）
     * @param keyword     关键词（MY_KEYWORD）
     * @param rule        规则（取 searchFind；8.83 为 SearchEngine）
     * @param searchFind  搜索解析规则（6 段）
     * @param displayTitle 展示标题（写入 SearchResult.desc）
     * @param html        已抓取的搜索结果页源码
     * @param page        页码（MY_PAGE）
     */
    public static void findList(String baseUrl, String keyword, HkRule rule, String searchFind,
                                String displayTitle, String html, int page,
                                SearchJsCallBack<List<SearchResult>> callback) {
        HkRule movieRule = rule;
        movieRule.setChapterUrl(baseUrl);
        if (HkParserKit.isEmpty(searchFind)) {
            callBackError(callback, "搜索解析规则不能为空");
            return;
        }
        if (searchFind.startsWith("js:")) {
            HashMap<String, Object> vars = new HashMap<>();
            vars.put("MY_PAGE", page);
            vars.put("MY_KEYWORD", keyword);
            JsSearchBridge bridge = jsSearchBridge;
            if (bridge != null) {
                bridge.parseCallBack(baseUrl, rule, html, vars, callback);
            } else {
                callBackError(callback, "js: 搜索需要 JS 运行时");
            }
            return;
        }
        List<SearchResult> results = new ArrayList<>();
        try {
            Document doc = Jsoup.parse(html);
            String[] seg = searchFind.split(";");
            String[] chain = seg[0].split("&&");
            Element el = HkCommonParser.getTrueElement(chain[0], doc);
            for (int i = 1; i < chain.length - 1; i++) {
                el = HkCommonParser.getTrueElement(chain[i], el);
            }
            Iterator<Element> it = HkCommonParser.selectElements(el, chain[chain.length - 1]).iterator();
            while (it.hasNext()) {
                Element item = it.next();
                try {
                    SearchResult sr = new SearchResult();
                    sr.setTitle(HkCommonParser.getTextByRule(item, seg[1]));
                    sr.setUrl(getUrl(sr, baseUrl, movieRule, item, seg));
                    getDesc(sr, baseUrl, movieRule, item, seg);
                    getContent(sr, baseUrl, movieRule, item, seg);
                    getPicUrl(sr, baseUrl, movieRule, item, seg);
                    sr.setDesc(displayTitle);
                    sr.setType("video");
                    results.add(sr);
                } catch (Exception e) {
                    Log.e(TAG, "findList item", e);
                }
            }
            callBackSuccess(callback, results);
        } catch (Exception e) {
            Log.e(TAG, "findList", e);
            callBackError(callback, "findList：msg：" + e);
        }
    }

    private static String getUrl(SearchResult sr, String baseUrl, HkRule rule, Element item, String[] seg) {
        String url = HkCommonParser.getUrlByRule(baseUrl, rule, item, seg[2]);
        sr.setUrl(url);
        return url;
    }

    private static void getPicUrl(SearchResult sr, String baseUrl, HkRule rule, Element item, String[] seg) {
        if (seg.length <= 5 || !HkParserKit.isNotEmpty(seg[5]) || "*".equals(seg[5])) {
            return;
        }
        sr.setImg(HkCommonParser.getUrlByRule("*", rule, item, seg[5]));
    }

    private static void getDesc(SearchResult sr, String baseUrl, HkRule rule, Element item, String[] seg) {
        if (seg.length <= 3 || !HkParserKit.isNotEmpty(seg[3]) || "*".equals(seg[3])) {
            return;
        }
        sr.setDescMore(HkCommonParser.getTextByRule(item, seg[3]));
    }

    private static void getContent(SearchResult sr, String baseUrl, HkRule rule, Element item, String[] seg) {
        if (seg.length <= 4 || !HkParserKit.isNotEmpty(seg[4]) || "*".equals(seg[4])) {
            return;
        }
        sr.setContent(HkCommonParser.getTextByRule(item, seg[4]));
    }

    private static synchronized void callBackError(SearchJsCallBack<List<SearchResult>> callback, String msg) {
        synchronized (HkSearchParser.class) {
            callback.showErr(msg);
        }
    }

    private static synchronized void callBackSuccess(SearchJsCallBack<List<SearchResult>> callback, List<SearchResult> list) {
        synchronized (HkSearchParser.class) {
            callback.showData(list);
        }
    }
}
