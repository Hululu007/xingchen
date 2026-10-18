package com.fongmi.android.tv.legado;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 书源执行管线：搜索 → 详情 → 目录 → 正文。
 * <p>
 * 每段独立 try/catch，失败抛带源名的 LegadoException（源级错，不崩壳）。
 * URL 模板：{@code {{key}}} / {@code {{page}}} / {@code {{var}}} 变量替换，
 * 尾部 {@code ,{...}} 请求选项 JSON（method/body/headers/charset）。
 */
public class LegadoPipeline {

    private static final int MAX_TOC_PAGES = 50;
    private static final int MAX_CONTENT_PAGES = 20;

    // ================= 结果 POJO =================

    public static class Book {
        public String name = "";
        public String author = "";
        public String coverUrl = "";
        public String bookUrl = "";
    }

    public static class BookInfo {
        public String name = "";
        public String author = "";
        public String coverUrl = "";
        public String intro = "";
        public String tocUrl = "";
    }

    public static class TocChapter {
        public String name = "";
        public String url = "";
    }

    public static class Content {
        public String title = "";
        /** 文本：正文；图片：换行分隔的图片 URL。 */
        public String text = "";
    }

    // ================= 请求选项 =================

    static class ReqOpt {
        String method = "GET";
        byte[] body;
        final Map<String, String> headers = new HashMap<>();
        String charset = "utf-8";
    }

    /** 解析「url,{json}」形态。 */
    static class UrlSpec {
        String url;
        final ReqOpt opt = new ReqOpt();
    }

    static UrlSpec parseUrl(String template, Map<String, String> vars) {
        UrlSpec spec = new UrlSpec();
        String t = template == null ? "" : template.trim();
        int oi = t.lastIndexOf(",{");
        if (oi > 0 && t.endsWith("}")) {
            String json = t.substring(oi + 1);
            try {
                JSONObject o = new JSONObject(json);
                if (o.has("method")) spec.opt.method = o.optString("method", "GET");
                if (o.has("charset")) spec.opt.charset = o.optString("charset", "utf-8");
                if (o.has("body")) {
                    String b = o.optString("body", "");
                    spec.opt.body = b.getBytes("UTF-8");
                }
                if (o.has("headers")) {
                    JSONObject h = o.optJSONObject("headers");
                    if (h != null) {
                        for (String k : JSONObject.getNames(h)) spec.opt.headers.put(k, h.optString(k, ""));
                    }
                }
                t = t.substring(0, oi);
            } catch (Throwable ignored) {
                // JSON 解析失败：按纯 URL 走
            }
        }
        spec.url = renderVars(t, vars);
        // body 里的变量也渲染
        if (spec.opt.body != null) {
            try {
                String b = new String(spec.opt.body, "UTF-8");
                spec.opt.body = renderVars(b, vars).getBytes("UTF-8");
            } catch (Throwable ignored) {
            }
        }
        return spec;
    }

    private static String renderVars(String t, Map<String, String> vars) {
        if (t == null || vars == null) return t == null ? "" : t;
        Matcher m = Pattern.compile("\\{\\{([^}]+)\\}\\}").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1).trim();
            String v = vars.get(key);
            if (v == null) v = "";
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ================= 抓取 =================

    private final LegadoSource source;
    private final LegadoHttp http;
    /** 整本书会话变量（@put/@get 跨阶段）。 */
    private final Map<String, String> sessionVars = new HashMap<>();

    public LegadoPipeline(LegadoSource source) {
        this(source, LegadoHttpAndroid.get());
    }

    public LegadoPipeline(LegadoSource source, LegadoHttp http) {
        if (source == null) throw new LegadoException("书源为空");
        this.source = source;
        this.http = http == null ? LegadoHttpAndroid.get() : http;
    }

    private String fetchText(UrlSpec spec) {
        try {
            LegadoHttp.Result r = http.fetch(spec.url, spec.opt.method, spec.opt.headers, spec.opt.body);
            String charset = charsetOf(r, spec.opt.charset);
            return new String(r.body, charset);
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException(source.tag() + " 抓取失败: " + t.getMessage());
        }
    }

    private String charsetOf(LegadoHttp.Result r, String fallback) {
        String ct = r.contentType == null ? "" : r.contentType.toLowerCase();
        int i = ct.indexOf("charset=");
        if (i >= 0) {
            String cs = ct.substring(i + 8).split("[; ]")[0].trim().replace("\"", "");
            if (!cs.isEmpty()) return normCharset(cs);
        }
        return normCharset(fallback);
    }

    /** GBK/Big5 按盘点要求处理。 */
    static String normCharset(String cs) {
        if (cs == null) return "UTF-8";
        String c = cs.trim().toLowerCase();
        if (c.contains("gbk") || c.contains("gb2312") || c.contains("gb18030")) return "GBK";
        if (c.contains("big5")) return "Big5";
        return "UTF-8";
    }

    private LegadoRuleEvaluator.Ctx ctxOf(String rawText, String baseUrl) {
        LegadoRuleEvaluator.Ctx ctx = LegadoRuleEvaluator.newCtx(rawText, baseUrl, http);
        ctx.vars = sessionVars;
        ctx.source.put("bookSourceUrl", source.bookSourceUrl);
        ctx.source.put("bookSourceName", source.bookSourceName);
        ctx.source.put("bookSourceType", String.valueOf(source.bookSourceType));
        if (source.jsLib != null && !source.jsLib.isEmpty()) {
            try {
                ctx.bridge = new LegadoJsBridge(ctx);
                ctx.bridge.eval(source.jsLib);
            } catch (Throwable t) {
                throw new LegadoException(source.tag() + " jsLib 执行失败: " + t.getMessage());
            }
        }
        return ctx;
    }

    private Map<String, String> baseVars() {
        Map<String, String> v = new HashMap<>();
        v.put("bookSourceUrl", source.bookSourceUrl);
        return v;
    }

    // ================= 搜索 =================

    public List<Book> search(String key) {
        return search(key, 1);
    }

    public List<Book> search(String key, int page) {
        if (source.ruleSearch == null || isEmpty(source.ruleSearch.bookList)) {
            throw new LegadoException(source.tag() + " 未配置搜索规则");
        }
        try {
            Map<String, String> vars = baseVars();
            vars.put("key", urlEncode(key));
            vars.put("page", String.valueOf(page));
            UrlSpec spec = parseUrl(source.searchUrl, vars);
            if (source.header != null) spec.opt.headers.putAll(source.header);
            String text = fetchText(spec);
            LegadoRuleEvaluator.Ctx ctx = ctxOf(text, spec.url);
            List<LegadoRuleEvaluator.Sel> els = LegadoRuleEvaluator.elements(ctx, source.ruleSearch.bookList);
            List<Book> out = new ArrayList<>();
            for (LegadoRuleEvaluator.Sel el : els) {
                Book b = new Book();
                b.name = LegadoRuleEvaluator.str(el, ctx, source.ruleSearch.name, false);
                b.author = LegadoRuleEvaluator.str(el, ctx, source.ruleSearch.author, false);
                b.coverUrl = LegadoRuleEvaluator.str(el, ctx, source.ruleSearch.coverUrl, true);
                b.bookUrl = LegadoRuleEvaluator.str(el, ctx, source.ruleSearch.bookUrl, true);
                if (!b.name.isEmpty() && !b.bookUrl.isEmpty()) out.add(b);
            }
            return out;
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException(source.tag() + " 搜索失败: " + t.getMessage());
        }
    }

    // ================= 详情 =================

    public BookInfo bookInfo(String bookUrl) {
        if (source.ruleBookInfo == null) throw new LegadoException(source.tag() + " 未配置详情规则");
        try {
            UrlSpec spec = parseUrl(bookUrl, baseVars());
            if (source.header != null) spec.opt.headers.putAll(source.header);
            String text = fetchText(spec);
            LegadoRuleEvaluator.Ctx ctx = ctxOf(text, spec.url);
            LegadoSource.RuleBookInfo r = source.ruleBookInfo;
            BookInfo info = new BookInfo();
            info.name = LegadoRuleEvaluator.str(ctx.root, ctx, r.name, false);
            info.author = LegadoRuleEvaluator.str(ctx.root, ctx, r.author, false);
            info.coverUrl = LegadoRuleEvaluator.str(ctx.root, ctx, r.coverUrl, true);
            info.intro = LegadoRuleEvaluator.str(ctx.root, ctx, r.intro, false);
            info.tocUrl = LegadoRuleEvaluator.str(ctx.root, ctx, r.tocUrl, true);
            return info;
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException(source.tag() + " 详情失败: " + t.getMessage());
        }
    }

    // ================= 目录 =================

    public List<TocChapter> toc(String tocUrl) {
        if (source.ruleToc == null || isEmpty(source.ruleToc.chapterList)) {
            throw new LegadoException(source.tag() + " 未配置目录规则");
        }
        List<TocChapter> out = new ArrayList<>();
        String next = tocUrl;
        int pages = 0;
        try {
            while (next != null && !next.isEmpty() && pages < MAX_TOC_PAGES) {
                pages++;
                UrlSpec spec = parseUrl(next, baseVars());
                if (source.header != null) spec.opt.headers.putAll(source.header);
                String text = fetchText(spec);
                LegadoRuleEvaluator.Ctx ctx = ctxOf(text, spec.url);
                LegadoSource.RuleToc r = source.ruleToc;
                // 优先逐元素；元素口径取空时退化为列表 zip
                List<LegadoRuleEvaluator.Sel> els = LegadoRuleEvaluator.elements(ctx, r.chapterList);
                boolean usedElements = false;
                for (LegadoRuleEvaluator.Sel el : els) {
                    String name = LegadoRuleEvaluator.str(el, ctx, r.chapterName, false);
                    if (!name.isEmpty()) usedElements = true;
                }
                if (usedElements) {
                    for (LegadoRuleEvaluator.Sel el : els) {
                        TocChapter c = new TocChapter();
                        c.name = LegadoRuleEvaluator.str(el, ctx, r.chapterName, false);
                        c.url = LegadoRuleEvaluator.str(el, ctx, r.chapterUrl, true);
                        if (!c.name.isEmpty()) out.add(c);
                    }
                } else {
                    List<String> names = LegadoRuleEvaluator.strList(ctx.root, ctx, r.chapterName);
                    List<String> urls = new ArrayList<>();
                    for (String u : LegadoRuleEvaluator.strList(ctx.root, ctx, r.chapterUrl)) {
                        urls.add(LegadoRuleEvaluator.absUrl(spec.url, u));
                    }
                    int n = Math.min(names.size(), urls.size());
                    for (int i = 0; i < n; i++) {
                        TocChapter c = new TocChapter();
                        c.name = names.get(i);
                        c.url = urls.get(i);
                        out.add(c);
                    }
                }
                next = LegadoRuleEvaluator.str(ctx.root, ctx, r.nextTocUrl, true);
            }
            return out;
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException(source.tag() + " 目录失败: " + t.getMessage());
        }
    }

    // ================= 正文 =================

    public Content content(String chapterUrl) {
        if (source.ruleContent == null || isEmpty(source.ruleContent.content)) {
            throw new LegadoException(source.tag() + " 未配置正文规则");
        }
        Content c = new Content();
        StringBuilder sb = new StringBuilder();
        String next = chapterUrl;
        int pages = 0;
        try {
            while (next != null && !next.isEmpty() && pages < MAX_CONTENT_PAGES) {
                pages++;
                UrlSpec spec = parseUrl(next, baseVars());
                if (source.header != null) spec.opt.headers.putAll(source.header);
                String text = fetchText(spec);
                LegadoRuleEvaluator.Ctx ctx = ctxOf(text, spec.url);
                LegadoSource.RuleContent r = source.ruleContent;
                if (pages == 1) c.title = LegadoRuleEvaluator.str(ctx.root, ctx, r.title, false);
                String part;
                if (source.bookSourceType == LegadoSource.TYPE_IMAGE) {
                    // 漫画：图片 URL 列表（换行分隔为社区共识；JSON [*] 走列表口径）
                    List<String> imgs = LegadoRuleEvaluator.strList(ctx.root, ctx, r.content);
                    part = String.join("\n", imgs);
                    for (String line : part.split("\n")) {
                        String u = line.trim();
                        if (u.startsWith("http")) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(u);
                        }
                    }
                } else {
                    part = LegadoRuleEvaluator.str(ctx.root, ctx, r.content, false);
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(part.trim());
                }
                next = LegadoRuleEvaluator.str(ctx.root, ctx, r.nextContentUrl, true);
            }
            c.text = sb.toString().trim();
            return c;
        } catch (LegadoException e) {
            throw e;
        } catch (Throwable t) {
            throw new LegadoException(source.tag() + " 正文失败: " + t.getMessage());
        }
    }

    // ================= 杂项 =================

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }
}
