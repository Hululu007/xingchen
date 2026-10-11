package com.fongmi.quickjs.utils;

import android.text.TextUtils;

import com.fongmi.quickjs.bean.Cache;
import com.github.catvod.utils.UriUtil;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海阔选择器引擎（对齐 8.83 CommonParser）：
 * parseDomForUrl / parseDomForList / parseDomForHtmlList 语义。
 * <ul>
 * <li>中间步骤 getTrueElement：每步只取 .first()，,n 取单索引（含负数），|| 首个非空胜出，-- 剔除</li>
 * <li>列表末步 selectElements：|| 做 UNION，,m:n 取范围</li>
 * <li>末段 getUrl：|| 首个非空胜出，.js: 剥离后 evalJS，Text/Html/Attr/attr 取值 + URL 归一</li>
 * </ul>
 */
public class Parser {

    private final Pattern url = Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE | Pattern.DOTALL);
    private final Pattern joinUrl = Pattern.compile("(url|src|href|-original|-src|-play|-url|style)$|^(data-|url-|src-)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private final Pattern specialUrl = Pattern.compile("^(ftp|magnet|thunder|ws):", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private final Cache cache;

    /** 8.83 normalAttrs */
    private static final String[] NORMAL_ATTRS = {"href", "src", "class", "title", "alt"};

    /** .js: 求值器（HkJsRuntime 接入；quickjs Global 不接时为 null，js 剥离后不执行） */
    public interface JsEvaluator {
        String eval(String js, String input);
    }

    private JsEvaluator jsEvaluator;

    public void setJsEvaluator(JsEvaluator jsEvaluator) {
        this.jsEvaluator = jsEvaluator;
    }

    public Parser() {
        cache = new Cache();
    }

    public String joinUrl(String parent, String child) {
        return UriUtil.resolve(parent, child);
    }

    /** 8.83 parseDomForUrl */
    public String pdfh(String html, String rule, String addUrl) {
        try {
            Document doc = cache.getPdfh(html);
            if (doc == null) return "";
            if ("body&&Text".equals(rule) || "Text".equals(rule)) return doc.text();
            if ("body&&Html".equals(rule) || "Html".equals(rule)) return doc.html();
            String[] steps = rule.split("&&", -1);
            Element el;
            if (steps.length == 1) {
                el = doc;
            } else {
                el = getTrueElement(doc, steps[0]);
                for (int i = 1; i < steps.length - 1 && el != null; i++) {
                    el = getTrueElement(el, steps[i]);
                }
            }
            if (el == null) return "";
            return getUrl(el, steps[steps.length - 1], addUrl);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 8.83 parseDomForList */
    public List<String> pdfa(String html, String rule) {
        try {
            Document doc = cache.getPdfa(html);
            if (doc == null) return Collections.emptyList();
            String[] steps = rule.split("&&", -1);
            Element el;
            if (steps.length == 1) {
                el = doc;
            } else {
                el = getTrueElement(doc, steps[0]);
                for (int i = 1; i < steps.length - 1 && el != null; i++) {
                    el = getTrueElement(el, steps[i]);
                }
            }
            if (el == null) return Collections.emptyList();
            Elements elements = selectElements(el, steps[steps.length - 1]);
            List<String> items = new ArrayList<>();
            for (Element e : elements) items.add(e.outerHtml());
            return items;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public List<String> pdfl(String html, String rule, String texts, String urls, String urlKey) {
        List<String> items = pdfa(html, rule);
        List<String> out = new ArrayList<>();
        for (String item : items) {
            out.add(pdfh(item, texts, "").trim() + '$' + pdfh(item, urls, urlKey));
        }
        return out;
    }

    /** 8.83 getTrueElement：每步只取 .first()，,n 单索引，|| 首个非空胜出 */
    private Element getTrueElement(Element element, String rule) {
        if (element == null || rule == null) return null;
        if (rule.startsWith("Text") || rule.startsWith("Attr")) return element;
        for (String na : NORMAL_ATTRS) {
            if (na.equals(rule)) return element;
        }
        // -- 剔除
        if (rule.contains("--")) {
            String[] rules = rule.split("--", -1);
            Element e = getTrueElement(element, rules[0]);
            if (e == null) return null;
            String s = e.outerHtml();
            for (int i = 1; i < rules.length; i++) {
                Element r = getTrueElement(e, rules[i]);
                if (r != null) s = s.replace(r.outerHtml(), "");
            }
            return Jsoup.parse(s);
        }
        // ||：首个非空胜出
        if (rule.contains("||")) {
            for (String or : rule.split("\\|\\|", -1)) {
                try {
                    Element e = getTrueElement(element, or);
                    if (e != null) return e;
                } catch (Exception ignored) {
                }
            }
            return null;
        }
        // ,n：单索引（含负数）
        String[] ss01 = rule.split(",", -1);
        if (ss01.length > 1) {
            try {
                int index = Integer.parseInt(ss01[1].trim());
                Elements elements = element.select(decodeConflict(ss01[0]));
                if (elements.isEmpty()) return null;
                if (index < 0) index = elements.size() + index;
                if (index < 0 || index >= elements.size()) return null;
                return elements.get(index);
            } catch (NumberFormatException e) {
                // 非索引，回落普通选择器
            }
        }
        Elements els = element.select(decodeConflict(rule));
        return els.first();
    }

    /** 8.83 selectElements：|| 做 UNION */
    private Elements selectElements(Element element, String rule) {
        Elements res = new Elements();
        for (String or : rule.split("\\|\\|", -1)) {
            try {
                res.addAll(selectElementsWithoutOr(element, or));
            } catch (Exception ignored) {
            }
        }
        return res;
    }

    /** 8.83 selectElementsWithoutOr：,m:n 取范围 */
    private Elements selectElementsWithoutOr(Element element, String rule) {
        String[] rules = rule.split(",", -1);
        if (rules.length > 1) {
            String[] indexNumbs;
            try {
                indexNumbs = rules[1].split(":", -1);
                // 8.83 无越界检查：单 ,n 会 AIOOBE 被上层吞掉 → 空结果
                if (indexNumbs.length < 2) return new Elements();
            } catch (Exception e) {
                return new Elements();
            }
            int startPos = 0;
            int endPos = 0;
            if (!TextUtils.isEmpty(indexNumbs[0])) {
                try {
                    startPos = Integer.parseInt(indexNumbs[0].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            if (!TextUtils.isEmpty(indexNumbs[1])) {
                try {
                    endPos = Integer.parseInt(indexNumbs[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            Elements elements = element.select(decodeConflict(rules[0]));
            if (endPos > elements.size()) endPos = elements.size();
            if (endPos <= 0) endPos = elements.size() + endPos;
            Elements res = new Elements();
            for (int i = startPos; i < endPos; i++) {
                if (i >= 0 && i < elements.size()) res.add(elements.get(i));
            }
            return res;
        }
        return element.select(decodeConflict(rule));
    }

    /** 8.83 getUrl：|| 首个非空胜出 */
    private String getUrl(Element element, String lastRule, String baseUrl) {
        if ("*".equals(lastRule)) return "null";
        String[] ors = lastRule.split("\\|\\|", -1);
        if (ors.length > 1) {
            for (String or : ors) {
                String e = null;
                try {
                    e = getUrlWithoutOr(element, or, baseUrl);
                } catch (Exception ignored) {
                }
                if (!TextUtils.isEmpty(e)) return e;
            }
            return "";
        }
        try {
            return getUrlWithoutOr(element, lastRule, baseUrl);
        } catch (Exception e) {
            return "";
        }
    }

    /** 8.83 getUrlWithoutOr：.js: 剥离后 evalJS */
    private String getUrlWithoutOr(Element element, String lastRule, String baseUrl) {
        // C3：先剥离 .js:（JS 里含 .js: 时拼回去）
        String js = "";
        String[] ss = lastRule.split("\\.js:", -1);
        if (ss.length > 1) {
            lastRule = ss[0];
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ss.length; i++) {
                if (i > 1) sb.append(".js:");
                sb.append(ss[i]);
            }
            js = sb.toString();
        }
        if (element == null) return "";
        String url;
        if (lastRule.startsWith("Text")) {
            url = element.text();
        } else if ("Html".equals(lastRule)) {
            url = element.html();
        } else if (lastRule.startsWith("AttrNo")) {
            url = baseUrl + element.attr(lastRule.replaceFirst("AttrNo", ""));
            return url;
        } else if (lastRule.startsWith("AttrYes")) {
            url = element.attr(lastRule.replaceFirst("AttrYes", ""));
        } else if (lastRule.startsWith("Attr")) {
            url = element.attr(lastRule.replaceFirst("Attr", ""));
        } else {
            url = element.attr(lastRule);
        }
        if (TextUtils.isEmpty(js)) {
            if (!"Html".equals(lastRule)) url = trimBlanks(url);
        } else {
            try {
                if (jsEvaluator != null) url = jsEvaluator.eval(js, url);
                String t = url == null ? "" : url.toLowerCase().trim();
                if (t.startsWith("http") || t.startsWith("x5://")) {
                    return url == null ? "" : url.trim();
                }
            } catch (Exception e) {
                if (!"Html".equals(lastRule)) url = trimBlanks(url);
            }
        }
        if (TextUtils.isEmpty(url)) return "";
        if ("Html".equals(lastRule)) return url;
        if (url.startsWith("http")) return url;
        if (url.startsWith("//")) return "http:" + url;
        String low = url.toLowerCase();
        if (low.startsWith("magnet") || low.startsWith("thunder") || low.startsWith("ftp") || low.startsWith("ed2k")) return url;
        if (url.startsWith("/") || url.startsWith("./") || url.startsWith("../") || url.startsWith("?")) {
            return joinUrl(baseUrl, url);
        }
        String[] urls = url.split("\\$");
        if (urls.length > 1 && urls[1].startsWith("http")) return urls[1];
        if (url.contains("url(")) {
            String[] urls2 = url.split("url\\(");
            if (urls2.length > 1 && urls2[1].startsWith("http")) {
                return urls2[1].split("\\)")[0];
            }
        }
        // style url() 提取（原 parseAttr 逻辑保留）
        Matcher m = this.url.matcher(url);
        if (m.find()) {
            String u = m.group(1).replaceAll("^['\"](.*)['\"]$", "$1");
            if (!u.isEmpty()) return maybeJoin(baseUrl, lastRule, u);
        }
        return maybeJoin(baseUrl, lastRule, url);
    }

    private String maybeJoin(String baseUrl, String attrName, String url) {
        if (!TextUtils.isEmpty(url) && !TextUtils.isEmpty(baseUrl)
                && joinUrl.matcher(attrName).find() && !specialUrl.matcher(url).find()) {
            return url.contains("http") ? url.substring(url.indexOf("http")) : joinUrl(baseUrl, url);
        }
        return url;
    }

    private static String trimBlanks(String s) {
        return s == null ? "" : s.trim();
    }

    /** 官方 StringUtil.decodeConflictStr */
    private static String decodeConflict(String s) {
        if (s == null) return "";
        return s.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }
}
