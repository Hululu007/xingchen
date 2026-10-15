package com.fongmi.android.tv.api.hk;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直接移植自海阔 8.83 {@code com.example.hikerview.service.parser.PageParser}（do not rewrite）。
 * <p>
 * 处理 {@code hiker://page/} 子页面导航。
 * 适配：
 * <ul>
 * <li>ArticleListRule → {@link HkRule}；ArticleListPageRule → {@link HkPage}</li>
 * <li>LitePal 按 title 查规则 → {@link HkRuleManager#getRule(String)}</li>
 * <li>fastjson → Gson；Timber → Log</li>
 * <li>goPageByPageUrl 的 Activity 跳转改为返回解析后的 {@link HkRule}（宿主自行导航）</li>
 * </ul>
 */
public class HkPageParser {
    private static final String TAG = "HkPageParser";
    private static final Gson GSON = new Gson();

    /** 8.83 ArticleListPageRule 的最小移植（HkRule.pages JSON 数组的元素类型）。 */
    public static class HkPage {
        @com.google.gson.annotations.SerializedName("col_type")
        private String col_type;
        @com.google.gson.annotations.SerializedName("name")
        private String name;
        private Map<String, String> params;
        private transient String parent;
        private transient HkRule parentRule;
        @com.google.gson.annotations.SerializedName("path")
        private String path;
        @com.google.gson.annotations.SerializedName("rule")
        private String rule;

        public String getCol_type() {
            return col_type;
        }

        public void setCol_type(String col_type) {
            this.col_type = col_type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, String> getParams() {
            return params;
        }

        public void setParams(Map<String, String> params) {
            this.params = params;
        }

        public String getParent() {
            return parent;
        }

        public void setParent(String parent) {
            this.parent = parent;
        }

        public HkRule getParentRule() {
            return parentRule;
        }

        public void setParentRule(HkRule parentRule) {
            this.parentRule = parentRule;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getRule() {
            return rule;
        }

        public void setRule(String rule) {
            this.rule = rule;
        }
    }

    /** 8.83 ParseException 的最小移植。 */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    public static boolean isPageUrl(String url) {
        return HkParserKit.isNotEmpty(url) && url.startsWith("hiker://page/");
    }

    /**
     * 8.83 原文（PageParser.smali，jadx 未能反编译，按内嵌 smali 逐分支移植）：
     * 由 hiker://page/ URL 构造下一页的 HkRule。
     */
    public static HkRule getNextPage(HkRule rule, String url, String paramsJson) throws ParseException {
        if (!isPageUrl(url)) {
            return null;
        }
        HkRule next = new HkRule();
        String ruleTitle = null;
        Map<String, String> paramsMap = null;
        if (HkParserKit.isNotEmpty(paramsJson) && paramsJson.startsWith("{") && paramsJson.endsWith("}")) {
            try {
                paramsMap = GSON.fromJson(paramsJson, new TypeToken<Map<String, String>>() {
                }.getType());
                if (paramsMap != null && paramsMap.containsKey("rule")) {
                    ruleTitle = paramsMap.get("rule");
                }
            } catch (Exception e) {
                Log.e(TAG, "getNextPage", e);
            }
        }
        HkPage pageRule = parsePageRule(rule, ruleTitle, url);
        HkRule parentRule = pageRule.getParentRule();
        next.setFindRule(pageRule.getRule());
        next.setPreRule(parentRule.getPreRule());
        next.setGroup(parentRule.getGroup());
        next.setUa(parentRule.getUa());
        next.setTitle(parentRule.getTitle());
        next.setLastChapterRule(parentRule.getLastChapterRule());
        next.setColType(pageRule.getCol_type());
        next.setPages(parentRule.getPages());
        if (HkParserKit.isEmpty(next.getColType()) || "*".equals(next.getColType())) {
            next.setColType(parentRule.getColType());
        }
        Map<String, String> pageParams = pageRule.getParams();
        if (pageParams != null && pageParams.containsKey("url")) {
            next.setUrl(pageParams.get("url"));
        } else if (paramsMap != null && paramsMap.containsKey("url") && paramsMap.get("url") != null) {
            next.setUrl(paramsMap.get("url"));
        } else {
            next.setUrl(url);
        }
        if (HkParserKit.isNotEmpty(paramsJson) && paramsJson.startsWith("{") && paramsJson.endsWith("}")) {
            // 照 8.83 PageParser 原文 rule.setParams(params)：写进可序列化字段，
            // 子页规则过 Intent 的 JSON 往返后仍在，MY_PARAMS 从 getParams() 生成。
            // pageParams 是 transient 只作内存副本保留，不再当跨页通道（静态槽已撤）。
            next.setParams(paramsJson);
            next.setPageParams(paramsJson);
        }
        return next;
    }

    public static String getUrl(String url, String paramsJson) {
        // 8.83 原文：解析 paramsJson 后仍返回 url（逻辑恒等，保留原样）
        return url;
    }

    private static HkPage parsePageRule(HkRule rule, String ruleTitle, String url) throws ParseException {
        String title = rule == null ? null : rule.getTitle();
        Map<String, String> params = HkHttpParser.getParamsByUrl(url);
        if (HkParserKit.isNotEmpty(ruleTitle) && !params.containsKey("rule")) {
            params.put("rule", ruleTitle);
        }
        if (params.containsKey("rule")) {
            title = params.get("rule");
            if (HkParserKit.isEmpty(title)) {
                title = rule == null ? null : rule.getTitle();
            } else {
                rule = HkRuleManager.get().getRule(title);
                if (rule == null) {
                    throw new ParseException("找不到“" + title + "”这个小程序");
                }
            }
        }
        String path = HkParserKit.clearTag(HkParserKit.replaceOnce(
                HkParserKit.splitUrlByQuestionMark(url.split(";")[0])[0], "hiker://page/", ""));
        if (rule == null) {
            throw new ParseException("找不到对应的小程序");
        }
        List<HkPage> pageList = getPageList(rule);
        HkPage found = null;
        if (HkParserKit.isNotEmpty(pageList)) {
            for (HkPage p : pageList) {
                if (path.equals(p.getPath())) {
                    found = p;
                    break;
                }
            }
        }
        if (found == null) {
            throw new ParseException("找不到“" + path + "”这个页面");
        }
        found.setParent(title);
        found.setParentRule(rule);
        found.setParams(params);
        return found;
    }

    /** 8.83 ArticleListRule.getPageList：pages JSON → List<HkPage>。 */
    public static List<HkPage> getPageList(HkRule rule) {
        List<HkPage> list = new ArrayList<>();
        if (rule == null || HkParserKit.isEmpty(rule.getPages())) {
            return list;
        }
        try {
            List<HkPage> parsed = GSON.fromJson(rule.getPages(), new TypeToken<List<HkPage>>() {
            }.getType());
            if (parsed != null) {
                list.addAll(parsed);
            }
        } catch (Exception e) {
            Log.e(TAG, "getPageList", e);
        }
        return list;
    }

    /**
     * 8.83 goPageByPageUrl 的逻辑核心（去掉 Activity 跳转，返回解析后的规则，宿主自行导航）。
     *
     * @return 下一页规则；非 page URL 或解析失败返回 null
     */
    public static HkRule resolvePageUrl(String url) {
        try {
            HkRule next = getNextPage(null, url, null);
            if (next == null) {
                return null;
            }
            if (next.getUrl() != null && next.getUrl().contains("#noHistory#")) {
                next.setUrl(HkParserKit.replaceOnce(next.getUrl(), "#noHistory#", ""));
            }
            return next;
        } catch (Exception e) {
            Log.e(TAG, "resolvePageUrl", e);
            return null;
        }
    }
}
