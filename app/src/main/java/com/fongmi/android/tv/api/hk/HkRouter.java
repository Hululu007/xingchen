package com.fongmi.android.tv.api.hk;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.SourceGate;
import com.fongmi.android.tv.ui.hk.HkEngineManager;
import com.orhanobut.logger.Logger;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 海阔规则引擎路由层（M3，设计文档 §4.5）。
 *
 * <p>纯数据 API，供上层调用：</p>
 * <ul>
 *   <li>{@link #home} / {@link #search} → HkPageActivity（列表/搜索）</li>
 *   <li>{@link #detail} → VideoActivity 海阔分支（详情/线路/选集）</li>
 *   <li>{@link #play} → VideoActivity 播放前解析（M4 完整分流链）</li>
 * </ul>
 */
public class HkRouter {

    private static final String TAG = "HkRouter";

    private final HkRule rule;
    private final HkEngine engine;

    public HkRouter(HkRule rule) throws Exception {
        this.rule = rule;
        // 修法 A（b12 分类点不进去定案，见 ~/workspace/oneapp-reopen-diagnosis.md）：
        // 点击求值（putMyVar 写分类号）必须与页面刷新求值落在同一个运行时上。
        // 8.83 JSEngine 是进程单例；星辰对应物是 HkEngineManager 的常驻运行时。
        // 之前这里每次 new HkEngine → 写入随一次性实例丢弃，刷新读到默认值永远回首页。
        // ensureInit 每实例只跑一次（不重复 init、不清变量表）；destroy 由 HkEngine
        // 的 adopted 标记护住，常驻运行时永不销毁。
        HkJsRuntime rt = HkEngineManager.runtimeFor(rule);
        HkEngineManager.ensureInit(rt);
        this.engine = HkEngine.wrapResident(rule, rt);
    }

    /** 按规则名打开路由（规则不存在抛异常）。 */
    public static HkRouter open(String title) throws Exception {
        HkRule r = HkRuleManager.get().getRule(title);
        if (r == null) throw new IllegalArgumentException("hk rule not found: " + title);
        return new HkRouter(r);
    }

    public HkRule getRule() {
        return rule;
    }

    public HkEngine getEngine() {
        return engine;
    }

    /** 首页/分类列表。海阔分类即 url 替换词，直接复用 home。 */
    public List<HkItem> home(int page, String cls, String area, String year, String sort) {
        return engine.home(page, cls, area, year, sort);
    }

    /** 单页内置搜索。8.83 同款：返回独立 SearchResult。 */
    public List<SearchResult> search(String keyword, int page) {
        return engine.search(keyword, page);
    }

    /**
     * V4 详情：先看条目 URL 自带逻辑，再看规则的 detail_find_rule。
     *
     * <p>98/115 条规则的 detail_find_rule 为空，V4 全靠条目 URL 分流：</p>
     * <ol>
     *   <li>{@code @lazyRule=}（如大哥视频）：先求值；结果含 {@code #isVideo=true#}
     *       → 置 directPlayUrl 标记，调用方跳过 V4 直接播放；否则用求值结果 URL 继续；</li>
     *   <li>{@code @rule=js:}（如 MissAV）：{@code @rule=} 前为 pageUrl（MY_URL），
     *       js 部分求值出详情；</li>
     *   <li>纯 {@code js:}（$.toString 生成）：求值得到真实 URL 后继续；</li>
     *   <li>最后走原有 detail_find_rule 流程。</li>
     * </ol>
     */
    public HkDetail detail(String itemUrl, HkItem item, boolean fromSearch) {
        HkDetail detail = new HkDetail();
        if (item != null) {
            detail.setTitle(item.getTitle());
            detail.setPic(item.getPic());
            detail.setContent(item.getDesc());
        }
        try {
            rule.validate();
            String u = itemUrl == null ? "" : itemUrl.trim();

            // P1：hiker://page/ 子页面不进 V4（应由 openPage 打开新列表页）；
            // 若走到这里说明调用方漏拦截，直接返回空详情避免"0条线路"空 V4。
            if (u.startsWith("hiker://page/")) {
                detail.setLazyParseFailed(true);
                return detail;
            }

            // ① @lazyRule= 条目：先求值 → dealWithUrl 分流（官方：不进 V4）
            int lr = u.indexOf("@lazyRule=");
            if (lr >= 0) {
                // 官方 clickItem → dealLazyRule：分类切换类回调（putMyVar + refreshPage）
                // 只起副作用，靠 OnRefreshPageEvent 重刷列表，不进 V4。这里做"变化检测"：
                // 仅当本次求值新产生了 refreshPage 请求时才标记为分类切换，
                // 避免把之前残留的标记误判（consume 保证每次只消费一次）。
                boolean hadRefresh = engine.getJsRuntime().isRefreshRequested();
                String v = evalEntryLazy(u, lr);
                if (engine.getJsRuntime().consumeRefreshRequest() && !hadRefresh) {
                    detail.setTabSwitch(true);
                    return detail;
                }
                // lazyRule 求值失败（null/空）：标记解析失败，调用方不应推空 V4
                if (v == null || v.trim().isEmpty() || "hiker://empty".equals(v.trim())) {
                    detail.setLazyParseFailed(true);
                    return detail;
                }
                String vt = v.trim();
                String kind = dealKind(vt);
                if (!kind.isEmpty()) {
                    // 官方 dealWithUrl：video 直接播放；pics/x5/web/image/magnet 按种类分流，均不进 V4
                    if ("video".equals(kind)) detail.setDirectPlayUrl(vt);
                    else {
                        detail.setDealKind(kind);
                        detail.setDealUrl(vt);
                    }
                    return detail;
                }
                // 动作 URL（如 toast://）：绝不能被当成直接播放地址。
                // 8.83 无 isEmptyDetailRule 兜底；星辰旧逻辑把 toast:// 误判为可播地址导致进播放器
                // （如星集Media"查看更新日志"按钮返回 toast://更新日志加载失败）。
                if (isActionUrl(vt)) {
                    detail.setActionUrl(vt);
                    return detail;
                }
                // 未知种类：回退旧逻辑（无详情规则则直接播放，否则继续 V4 流程）
                if (vt.contains("#isVideo=true#") || isEmptyDetailRule() || looksLikeMediaUrl(vt)) {
                    detail.setDirectPlayUrl(vt);
                    return detail;
                }
                // @lazyRule= 有结果但无法识别种类：如果结果是有效 HTTP URL，默认按直接播放处理，
                // 避免落入 V4 空详情（官方 dealWithUrl 对未知 http URL 走 web 分流，不进 V4）
                if (vt.toLowerCase().startsWith("http")) {
                    detail.setDirectPlayUrl(vt);
                    return detail;
                }
                // 非 http 结果也标记为解析失败，不进 V4 空页面
                detail.setLazyParseFailed(true);
                return detail;
            }

            // ② 纯 js: 条目（$.toString 生成）：求值得到真实 URL
            if (u.startsWith("js:")) {
                // 与 @lazyRule= 分支一致：本次求值新产生了 refreshPage 请求 → 标记分类切换，不进 V4
                boolean hadRefresh = engine.getJsRuntime().isRefreshRequested();
                String v = evalEntryJs(u);
                if (engine.getJsRuntime().consumeRefreshRequest() && !hadRefresh) {
                    detail.setTabSwitch(true);
                    return detail;
                }
                if (v != null && !v.trim().isEmpty()) {
                    String vt = v.trim();
                    // 文本型结果（非 URL、非规则标记）：设为 textResult，调用方弹窗显示，不进 V4/播放
                    // （如"查看更新日志"按钮；官方文本按钮语义）
                    String kind = dealKind(vt);
                    boolean isUrl = vt.toLowerCase().startsWith("http") || vt.contains("@rule=")
                            || vt.contains("@lazyRule=") || vt.contains("@inputRule=")
                            || vt.contains("@confirmRule=") || vt.startsWith("js:")
                            || !kind.isEmpty();
                    if (!isUrl) {
                        detail.setTextResult(vt);
                        return detail;
                    }
                    u = vt;
                } else {
                    // 纯 js: 求值空：与 ① @lazyRule= 分支一致，标记解析失败，
                    // 不带 js: 串落入详情规则流程（否则空详情/误播）。
                    detail.setLazyParseFailed(true);
                    return detail;
                }
            }

            // ③ @rule=js: 条目：pageUrl 为 MY_URL，js 部分求值出详情
            int ar = u.indexOf("@rule=");
            if (ar >= 0) {
                // 8.83 原版：传完整 URL 给 JS（含 # 后真实地址），clean 只给非 JS 分支用
                String rawPageUrl = u.substring(0, ar).trim();
                String pageUrl = cleanDetailUrl(rawPageUrl);
                String entryRule = u.substring(ar + 6).trim();
                if (entryRule.startsWith("js:")) {
                    if (item != null) engine.getJsRuntime().setDetailParams(item.getExtra());
                    // 8.83 原版：详情条目直接进通用列表渲染，不做线路/选集分类
                    List<HkDetailItem> jsItems = fromJsDetail(entryRule, rawPageUrl);
                    if (jsItems == null || jsItems.isEmpty()) {
                        // @rule=js: 求值出空详情：与 ① 分支一致，标记解析失败，不推空 V4
                        detail.setLazyParseFailed(true);
                        return detail;
                    }
                    detail.setPageUrl(pageUrl);
                    detail.setRawItems(jsItems);
                    return detail;
                }
                u = pageUrl;
            }

            // ④ 原有 detail_find_rule 流程
            String ruleText = effectiveDetailRule(fromSearch);
            if (ruleText == null || ruleText.trim().isEmpty()) {
                // 无详情规则：若条目 URL 本身形如媒体直链 → 直接播放，避免 V4"加载失败"
                String cu = u.trim();
                if (looksLikeMediaUrl(cu)) detail.setDirectPlayUrl(cu);
                // 同 @lazyRule= 分支的 dealWithUrl 口径：裸磁力／ed2k／thunder／ftp 不进 V4，
                // 以 dealKind=magnet 交调用方走星辰播放器（isP2pPlayLink 门），
                // 否则这里回空详情、调用方只能报"加载失败"。
                else if (isP2pPlayLink(cu)) {
                    detail.setDealKind("magnet");
                    detail.setDealUrl(cu);
                }
                return detail;
            }
            // 8.83 原版：JS 分支拿完整 URL（含 #），clean 只给选择器分支用
            String rawUrl = u;
            String url = cleanDetailUrl(u);
            List<HkDetailItem> items;
            if (HkSelector.isJsRule(ruleText)) {
                // 官方 dealRule：点击条目的 extra 作为新规则的 params（MY_PARAMS）
                if (item != null) engine.getJsRuntime().setDetailParams(item.getExtra());
                items = fromJsDetail(ruleText, rawUrl);
            } else {
                String html = getMarked(url);
                items = engine.getSelector().parseDetail(html, ruleText, url);
                // 选择器解析的条目若无 col_type，回退到规则的 detail_col_type（官方 dealRule 语义）
                String defaultColType = rule.getDetailColType();
                if (!defaultColType.isEmpty()) {
                    for (HkDetailItem it : items) {
                        if (it.getColType() == null || it.getColType().isEmpty()) {
                            it.setColType(defaultColType);
                        }
                    }
                }
            }
            // 8.83 原版：详情条目直接进通用列表渲染，不做线路/选集分类
            detail.setPageUrl(url);
            detail.setRawItems(items);
        } catch (Throwable e) {
            Logger.t(TAG).d("detail failed: %s", e.getMessage());
        }
        return detail;
    }

    /**
     * 条目 URL 里的 {@code @lazyRule=} 求值（与 play() 的 resolveLazyRule 同逻辑，
     * 返回原始求值结果；失败返回 null）。
     */
    private String evalEntryLazy(String fullUrl, int lrIndex) {
        try {
            String pageUrl = fullUrl.substring(0, lrIndex).trim();
            String r = decodeConflict(fullUrl.substring(lrIndex + 10).trim()).trim();
            boolean js = false;
            if (r.startsWith(".js:")) {
                r = r.substring(4);
                js = true;
            } else if (r.startsWith("js:")) {
                r = r.substring(3);
                js = true;
            }
            if (js) return engine.getJsRuntime().evalLazy(r, pageUrl);
            String html = getMarked(pageUrl);
            return engine.getSelector().evalField(html, r, pageUrl);
        } catch (Throwable e) {
            Logger.t(TAG).d("entry lazyRule eval failed: %s", e.getMessage());
            return null;
        }
    }

    /** 纯 js: 条目求值（$.toString 生成），期望返回真实 URL 字符串。 */
    private String evalEntryJs(String u) {
        try {
            return engine.getJsRuntime().evalLazy(u.substring(3), u);
        } catch (Throwable e) {
            Logger.t(TAG).d("entry js eval failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * 播放地址完整分流链（M4，设计文档 §6）。
     *
     * <p>顺序：</p>
     * <ol>
     *   <li>剥 {@code #isVideo=true#} / {@code #noLoading#} 等页面标识；</li>
     *   <li>拆 {@code ;} URL 增强（{@code url;method;encoding;{H@V&&H2@V2}}），header 进结果；</li>
     *   <li>{@code video://} 剥协议；</li>
     *   <li>{@code @lazyRule=}：{@code .js:} 走 JS 独立作用域求值，否则按选择器对剧集页提地址；</li>
     *   <li>{@code @rule=js:}：按 JS 求值；</li>
     *   <li>{@code js:} 整段：按 JS 求值；</li>
     *   <li>{@code {urls:[],names:[],headers:[],subtitle}} JSON：取首地址 + headers；</li>
     *   <li>直链或兜底：清理后的原样地址（播放器/嗅探继续处理）。</li>
     * </ol>
     */
    public HkPlay play(String episodeUrl) {
        HkPlay out = new HkPlay();
        if (episodeUrl == null) return out;
        String u = episodeUrl.trim();
        // -1. webRule:// 网页嗅探（8.83 WebkitParser）：必须在 @lazyRule/普通分流之前判定，
        // 否则伪协议串会被后续分流原样塞给播放器
        if (u.regionMatches(true, 0, "webRule://", 0, 10)) {
            // 与步骤 4 @lazyRule= 同口径：嗅探没出地址就是失败、停住抛可见错，
            // 不回空 HkPlay（调用方会把空地址组装成空 Result 静默死 00:00、无报错）
            if (!resolveWebRule(u, out) || out.isEmpty()) {
                throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 0. @lazyRule= 优先提取（必须在 splitEnhancement 之前：JS 代码里含 {}/; 等字符）
        int lr0 = u.indexOf("@lazyRule=");
        String lazyRuleText = null;
        String lazyPageUrl = null;
        if (lr0 >= 0) {
            lazyPageUrl = u.substring(0, lr0).trim();
            lazyRuleText = decodeConflict(u.substring(lr0 + 10).trim());
            u = lazyPageUrl;
        }
        // 0.1 播放数据 JSON 必须最先认：urls 里的地址常带 #isVideo=true# 等标记，
        // 若先走下面第 1 步按 # 切串，JSON 会被拦腰截断、解析失败后整串塞给播放器（白屏）
        if (lazyRuleText == null && u.startsWith("{") && u.contains("\"urls\"")) {
            if (parsePlayJson(u, out)) return out;
        }
        // 0.5 P1：页面标识里的播放标记（剥 # 之前先提取）
        // #isM3u8# 强制 m3u8；#concat# 多段拼接；#fastPlayMode#/#threads=N# 极速模式
        if (u.contains("#isM3u8#")) out.setForceM3u8(true);
        if (u.contains("#concat#")) out.setConcat(true);
        if (u.contains("#fastPlayMode#")) out.setFastPlayMode(true);
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("#threads=(\\d+)#").matcher(u);
        if (tm.find()) {
            try {
                out.setThreads(Integer.parseInt(tm.group(1)));
                out.setFastPlayMode(true);
            } catch (Throwable ignored) {
            }
        }
        // 1.5 P1：pics:// 漫画多图模式（非视频，记入 pics 列表，UI 提示不支持但不崩）
        if (u.startsWith("pics://")) {
            parsePicsInto(u, out);
            return out;
        }
        // 1.6 P1：特殊协议识别（ed2k/magnet/thunder/ftp），原样透传给播放器。
        // 口径与 isP2pPlayLink / Thunder.match 的 scheme 判定看齐：只认 scheme 前缀，
        // 不要求 "//"（ed2k:/thunder: 无双斜杠形态同样进迅雷线，不走系统选应用）。
        String low = u.toLowerCase();
        if (low.startsWith("ed2k:")) out.setPlayType("ed2k");
        else if (low.startsWith("magnet:")) out.setPlayType("magnet");
        else if (low.startsWith("thunder:")) out.setPlayType("thunder");
        else if (low.startsWith("ftp://")) out.setPlayType("ftp");
        // 2. ; URL 增强（必须在 # 截断之前：8.83 是先从完整串解析 ;{...} 请求头块、
        // 再剥标记；旧顺序先按 # 一刀切，url#标记;{Referer@x} 的请求头块会被连带丢掉）
        u = splitEnhancement(u, out);
        // 2.5 # 页面标识截断（#threads 等已在 0.5 提取完毕）
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        // 3. video://
        if (u.startsWith("video://")) u = u.substring(8).trim();
        // 4. @lazyRule=（已在步骤0提取）
        if (lazyRuleText != null) {
            resolveLazyRule(lazyPageUrl, lazyRuleText, out, 0);
            // 照 8.83：懒解析没出地址就是失败、停住报错；
            // 旧代码继续拿剧集页地址走兜底，最终把网页塞给播放器（白屏/解析失败）。
            // 空结果不再回空 HkPlay：调用方（SiteApi.hkPlayerContent）会把空地址
            // 组装成空 Result 静默死（00:00、无报错），故这里直接抛可见错；
            // 播放链 SiteViewModel PLAYER 出错分支会把消息投成 Result.error，
            // 与迅雷解析失败抛中文真错同一口径。预热/漫画/列表等调用方均已
            // catch Throwable（或声明 throws），签名不用改。
            if (out.isEmpty()) {
                throw new IllegalStateException("海阔懒解析未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 5. @rule=js:
        int ar = u.indexOf("@rule=");
        if (ar >= 0) {
            String pageUrl = u.substring(0, ar).trim();
            String ruleText = u.substring(ar + 6).trim();
            if (ruleText.startsWith("js:")) {
                String real = evalJsPlay(ruleText.substring(3), pageUrl);
                // 与步骤 4 @lazyRule= 同口径：js: 求值空就是失败、停住抛可见错，
                // 不落 pageUrl 兜底（否则网页被塞给播放器白屏/解析失败）
                if (real == null || real.isEmpty()) {
                    throw new IllegalStateException("海阔 @rule=js: 求值未返回播放地址，请换一条试试");
                }
                real = real.trim();
                if (real.startsWith("{") && real.contains("\"urls\"") && parsePlayJson(real, out)) return out;
                // js: 结果是 webRule:// 嗅探串时走网页嗅探（P1），不再原样塞播放器
                if (real.regionMatches(true, 0, "webRule://", 0, 10)) {
                    if (!resolveWebRule(real, out) || out.isEmpty()) {
                        throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
                    }
                    return out;
                }
                applyLazyResult(real, out, 0, pageUrl);
                if (out.isEmpty()) {
                    throw new IllegalStateException("海阔 @rule=js: 懒解析未返回播放地址，请换一条试试");
                }
                return out;
            }
            u = pageUrl;
        }
        // 6. js: 整段
        if (u.startsWith("js:")) {
            String real = evalJsPlay(u.substring(3), u);
            // 与步骤 4 @lazyRule= 同口径：js: 求值空就是失败、停住抛可见错，
            // 不落步骤 8 兜底（否则 js: 原串会被原样塞给播放器）
            if (real == null || real.isEmpty()) {
                throw new IllegalStateException("海阔 js: 求值未返回播放地址，请换一条试试");
            }
            real = real.trim();
            if (real.startsWith("{") && real.contains("\"urls\"") && parsePlayJson(real, out)) return out;
            // js: 结果是 webRule:// 嗅探串时走网页嗅探（P1），不再原样塞播放器
            if (real.regionMatches(true, 0, "webRule://", 0, 10)) {
                if (!resolveWebRule(real, out) || out.isEmpty()) {
                    throw new IllegalStateException("海阔网页嗅探未返回播放地址，请换一条试试");
                }
                return out;
            }
            // js: 整段以自身为 MY_URL 求值，没有真实宿主页面，产出页传空
            applyLazyResult(real, out, 0, "");
            if (out.isEmpty()) {
                throw new IllegalStateException("海阔 js: 懒解析未返回播放地址，请换一条试试");
            }
            return out;
        }
        // 7. {urls:[]} JSON
        if (u.startsWith("{") && u.contains("\"urls\"")) {
            if (parsePlayJson(u, out)) return out;
        }
        // 8. 兜底
        out.setUrl(u);
        return out;
    }

    /**
     * V2 分类 tab 点击：求值条目 url 里的 {@code @lazyRule=}，只为其副作用
     * （如 {@code putMyVar} 设置分类变量），不取播放地址。调用后由上层重新
     * {@code loadContent(true)} 刷新列表。求值异常只打日志，不抛给上层。
     *
     * @return 规则回调里是否调了 refreshPage（调用方据此决定是否重刷；当前
     * HkPageActivity 在 evalTab 后本来就会 loadContent(true)，故恒为 true 语义）
     */
    public boolean evalTab(String tabUrl) {
        if (tabUrl == null) return false;
        int lr = tabUrl.indexOf("@lazyRule=");
        if (lr < 0) return false;
        try {
            String r = decodeConflict(tabUrl.substring(lr + 10).trim()).trim();
            if (r.startsWith(".js:")) r = r.substring(4);
            else if (r.startsWith("js:")) r = r.substring(3);
            // 官方 LazyRuleParser.parseByJs：input = @lazyRule= 前的 URL 部分（lazyRule[0]）
            String prefix = tabUrl.substring(0, lr).trim();
            engine.getJsRuntime().evalLazy(r, prefix.isEmpty() ? null : prefix);
        } catch (Throwable e) {
            Logger.t(TAG).d("tab eval (side effects applied): %s", e.getMessage());
        }
        // 规则标准写法是 setItem(...); refreshPage(true); —— 无论是否显式调用，
        // tab 点击后都需要重刷；显式调用时打日志便于排障。
        boolean asked = engine.getJsRuntime().consumeRefreshRequest();
        Logger.t(TAG).d("tab eval done, refreshPage asked=%s", asked);
        return true;
    }

    /**
     * 拆 {@code ;} URL 增强：{@code url;method;encoding;{K@V&&K2@V2}}。
     * header 块进结果，method/encoding 丢弃，只保留纯 url。
     */
    private String splitEnhancement(String u, HkPlay out) {
        int brace = u.indexOf('{');
        int braceEnd = u.lastIndexOf('}');
        if (brace > 0 && braceEnd > brace) {
            String inner = u.substring(brace + 1, braceEnd);
            for (String kv : inner.split("&&")) {
                int at = kv.indexOf('@');
                // 请求头值照 8.83 过 decodeConflictStr（＆＆/；；等全角转义要还原），否则发错头
                if (at > 0) out.putHeader(kv.substring(0, at).trim(), decodeConflict(kv.substring(at + 1).trim()));
            }
            u = (u.substring(0, brace) + u.substring(braceEnd + 1)).trim();
        }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi).trim();
        return u;
    }

    /**
     * webRule:// 分流（8.83 WebkitParser.parse）：拆「页@解析js」，后台 WebView
     * 加载跑 js、经 fy_bridge_app.finishParse 回真实地址；失败/超时回空并留痕，
     * 绝不把伪协议串原样塞给播放器。拿到真实地址后与 lazy 同口径：
     * 先认 {urls} JSON，再当直链。
     */
    private boolean resolveWebRule(String webRuleUrl, HkPlay out) {
        String body = webRuleUrl.substring(10).trim();
        int at = body.indexOf('@');
        if (at < 0) {
            SpiderDebug.log("hk", "webRule invalid (no @) rule=%s", rule.getTitle());
            return false;
        }
        String loadUrl = body.substring(0, at).trim();
        String innerJs = body.substring(at + 1).trim();
        String real = HkWebkitFetcher.parseWebRule(loadUrl, innerJs, null);
        if (TextUtils.isEmpty(real)) {
            SpiderDebug.log("hk", "webRule parse empty rule=%s host=%s", rule.getTitle(), hostOf(loadUrl));
            return false;
        }
        real = real.trim();
        if (real.regionMatches(true, 0, "webRule://", 0, 10)) {
            // 解析结果又是 webRule：不再递归嗅探，按空处理（防死循环）
            SpiderDebug.log("hk", "webRule nested result rule=%s", rule.getTitle());
            return false;
        }
        if (real.startsWith("{") && real.contains("\"urls\"") && parsePlayJson(real, out)) return true;
        out.setUrl(splitEnhancement(real, out));
        return true;
    }

    /**
     * pics:// 串解析（play 步骤 1.5 与 applyLazyResult 的 pics 分支共用同一口径，
     * 防两处漂移）：清标记后先按行拆、再按字面 "&&" 拆——8.83 按字面 "&&" 分隔；
     * 旧写法 "[\\n&&]+" 是字符类，图片地址查询参数里的 & 会被切碎。
     *
     * @param picsUrl 以 {@code pics://} 开头的完整串
     */
    private static void parsePicsInto(String picsUrl, HkPlay out) {
        out.setPicsAttempted(true);
        String rest = HkParserKit.clearTag(picsUrl.substring(7).trim());
        for (String line : rest.split("\\n")) {
            for (String p : line.split("&&")) {
                String t = p.trim();
                if (!t.isEmpty()) out.addPic(t);
            }
        }
    }

    /**
     * pics:// 串拆分的共用出口（列表页/搜索页/详情页各入口一律走这里，
     * 不许再各自裸 split("&&")）：内部就是 {@link #parsePicsInto} 同一口径
     * （清标记、先按行拆、再按字面 "&&" 拆、trim 滤空段）。
     *
     * @param picsUrl 以 {@code pics://} 开头的完整串（大小写不敏感，调用方已判）
     * @return 拆出的图片条目（可空表，绝不为 null）
     */
    public static java.util.List<String> parsePics(String picsUrl) {
        HkPlay tmp = new HkPlay();
        if (picsUrl != null && picsUrl.length() >= 7) parsePicsInto(picsUrl, tmp);
        return tmp.getPics();
    }

    /**
     * 拆完是否有可加载的图：至少一条以 http:// 或 https:// 开头
     * （条目可带 {@code @Referer=} 等后缀，不影响前缀判定）。
     * 空体（"pics://" 后无内容）与全是非 http 条目都算无图——
     * 此判据只许作各入口的强制留底判据，**不许据此拦入口**
     * （fix4）：阅读器自带回退解析（会话图→章节串→router.play
     * 重取→书源），入口判空只是入口这一刻的拆分结果，判死
     * 会把整条回退链一并掐断（1.4.19 真机「漫画详情页都进不去」）。
     */
    public static boolean hasLoadablePic(java.util.List<String> pics) {
        if (pics == null) return false;
        for (String p : pics) {
            if (p == null) continue;
            String t = p.trim();
            if (t.regionMatches(true, 0, "http://", 0, 7)
                    || t.regionMatches(true, 0, "https://", 0, 8)) return true;
        }
        return false;
    }

    /**
     * 解析 {@code @lazyRule=}：{@code .js:}/{@code js:} 开头走 JS 独立作用域求值，
     * 否则把规则当选择器对剧集页提单个地址（{@code .js:} 值后缀由选择器内部处理）。
     */
    /**
     * 解析结果再分流：结果本身又是 {@code @lazyRule=} 串时递归解析下一段
     * （红果两段式：第一段脚本返回的还是带懒规则的地址，海阔原版是循环解析到
     * 干净地址为止；旧实现直接对整串做 splitEnhancement，花括号匹配会跨进
     * 脚本里、把第二段懒规则连同地址一起毁掉，最终把 uri: 串塞给播放器报
     * Malformed URL——1.3.23 真机日志实证）。深度上限 3 防死循环。
     *
     * @param pageUrl 本次求值实际跑的页面地址（产出该结果的页面），
     *                结果是 pics 时记入 {@link HkPlay#setPageUrl}；调用方
     *                拿不到真实页面（如 js: 整段以自身为 MY_URL）时传空串。
     */
    private void applyLazyResult(String v, HkPlay out, int depth, String pageUrl) {
        v = v.trim();
        // 懒解析直接返回播放数据 JSON 时当场拆开，否则整串会被当播放地址塞给播放器
        if (v.startsWith("{") && v.contains("\"urls\"") && parsePlayJson(v, out)) return;
        // 懒解析结果是 webRule:// 嗅探串时走网页嗅探（P1），不再原样塞播放器
        if (v.regionMatches(true, 0, "webRule://", 0, 10)) {
            resolveWebRule(v, out);
            return;
        }
        // 懒解析现算出的 pics:// 是漫画多图（与步骤 1.5 同语义）：记入 pics 列表
        // 供上层分流进阅读器，绝不许落到末尾当视频地址吞掉（复核报告 §3——
        // 此前无此分支，lazy 现算的漫画集被当播放地址、阅读器永远进不去）。
        // 产出页（本次求值实际跑的页面）一并记入 out，供分流时作会话页面地址。
        if (v.startsWith("pics://")) {
            parsePicsInto(v, out);
            if (pageUrl != null && !pageUrl.isEmpty()) out.setPageUrl(pageUrl);
            SpiderDebug.log("hk", "lazy resolved pics rule=%s pics=%d host=%s",
                    rule.getTitle(), out.getPics().size(), hostOf(out.getPageUrl()));
            return;
        }
        int lr = v.indexOf("@lazyRule=");
        if (lr >= 0) {
            if (depth >= 3) {
                SpiderDebug.log("hk", "lazy chain too deep rule=%s", rule.getTitle());
                return;
            }
            String page = v.substring(0, lr).trim();
            String nextRule = decodeConflict(v.substring(lr + 10).trim());
            // 页址部分的 ;增强段先拆（此时串里已无脚本，花括号匹配安全）
            page = splitEnhancement(page, out);
            int hash = page.indexOf('#');
            if (hash >= 0) page = page.substring(0, hash).trim();
            SpiderDebug.log("hk", "lazy chain depth=%d rule=%s host=%s", depth + 1, rule.getTitle(), hostOf(page));
            resolveLazyRule(page, nextRule, out, depth + 1);
            return;
        }
        out.setUrl(splitEnhancement(v, out));
        SpiderDebug.log("hk", "lazy resolved rule=%s -> %s", rule.getTitle(), hostOf(out.getUrl()));
    }

    private void resolveLazyRule(String pageUrl, String ruleText, HkPlay out, int depth) {
        try {
            String r = ruleText.trim();
            boolean js = false;
            if (r.startsWith(".js:")) {
                r = r.substring(4);
                js = true;
            } else if (r.startsWith("js:")) {
                r = r.substring(3);
                js = true;
            }
            String v;
            if (js) {
                v = engine.getJsRuntime().evalLazy(r, pageUrl);
            } else {
                String html = getMarked(pageUrl);
                v = engine.getSelector().evalField(html, r, pageUrl);
            }
            if (!TextUtils.isEmpty(v)) {
                applyLazyResult(v, out, depth, pageUrl);
            } else {
                // 空结果也留痕到文件日志：规则解析失败（如福建兄妹抠不到 playUrl 时
                // 懒函数抛错/回空）此前只进 logcat，他发来的日志里看不见、定不了案。
                SpiderDebug.log("hk", "lazy play empty rule=%s host=%s", rule.getTitle(), hostOf(pageUrl));
            }
        } catch (Throwable e) {
            Logger.t(TAG).d("lazyRule failed: %s", e.getMessage());
            SpiderDebug.log("hk", "lazy play failed rule=%s host=%s err=%s", rule.getTitle(), hostOf(pageUrl), String.valueOf(e));
        }
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = java.net.URI.create(url.trim()).getHost();
            return h == null ? "" : h;
        } catch (Throwable e) {
            return "";
        }
    }

    private String evalJsPlay(String code, String pageUrl) {
        try {
            return engine.getJsRuntime().evalLazy(code, pageUrl);
        } catch (Throwable e) {
            Logger.t(TAG).d("js play failed: %s", e.getMessage());
            SpiderDebug.log("hk", "js play failed rule=%s host=%s err=%s", rule.getTitle(), hostOf(pageUrl), String.valueOf(e));
            return "";
        }
    }

    /**
     * 解析多线路/字幕 JSON：{@code {urls:[], names:[], headers:[], subtitle:'...'}}。
     * 三列按 8.83 PlayData 语义逐下标对齐保全在 HkPlay：urls 全收、names 空补「线路1..N」、
     * headers 元素两种形态都认（{@code "Cookie@xxx"} 字符串属同线路多对累积，
     * {@code {"Referer":"..."}} 对象即 8.83 的单线路头表）——绝不跨线路合并。
     * P1：同时解析 audioUrls（音视频分离，取首条）。
     */
    private boolean parsePlayJson(String u, HkPlay out) {
        try {
            JSONObject o = new JSONObject(u);
            JSONArray urls = o.optJSONArray("urls");
            if (urls != null) {
                for (int i = 0; i < urls.length(); i++) out.addUrl(urls.optString(i, "").trim());
                if (urls.length() > 0) out.setUrl(urls.optString(0, "").trim());
            }
            JSONArray names = o.optJSONArray("names");
            if (names != null) {
                for (int i = 0; i < names.length(); i++) out.addName(names.optString(i, "").trim());
            }
            // 8.83 HttpParser.getPlayData：names 为空补「线路1..N」；多于 urls 的截断
            for (int i = out.getNames().size(); i < out.getUrls().size(); i++) out.addName("线路" + (i + 1));
            while (out.getNames().size() > out.getUrls().size() && !out.getNames().isEmpty())
                out.getNames().remove(out.getNames().size() - 1);
            // P1：音视频分离
            JSONArray audioUrls = o.optJSONArray("audioUrls");
            if (audioUrls != null && audioUrls.length() > 0)
                out.setAudioUrl(audioUrls.optString(0, "").trim());
            JSONArray headers = o.optJSONArray("headers");
            if (headers != null) {
                java.util.Map<String, String> cur = new java.util.LinkedHashMap<>();
                for (int i = 0; i < headers.length(); i++) {
                    Object el = headers.opt(i);
                    if (el instanceof JSONObject) {
                        // 一个对象 = 一条线路的头表：先结算之前累积的同线路多对，再单独成表
                        if (!cur.isEmpty()) {
                            out.addLineHeaders(cur);
                            cur = new java.util.LinkedHashMap<>();
                        }
                        JSONObject ho = (JSONObject) el;
                        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
                        java.util.Iterator<String> keys = ho.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            String v = decodeConflict(ho.optString(k, "").trim());
                            if (!k.trim().isEmpty() && !v.isEmpty()) m.put(k.trim(), v);
                        }
                        out.addLineHeaders(m);
                    } else {
                        // 星辰内编码（expandedUrl）：同线路的 "K@V" 对分多条存，累积进当前表
                        String kv = headers.optString(i, "");
                        int at = kv.indexOf('@');
                        if (at > 0) cur.put(kv.substring(0, at).trim(), decodeConflict(kv.substring(at + 1).trim()));
                    }
                }
                if (!cur.isEmpty()) out.addLineHeaders(cur);
            }
            String sub = o.optString("subtitle", "").trim();
            if (!sub.isEmpty()) out.setSubtitle(sub);
            return !out.isEmpty();
        } catch (Throwable e) {
            Logger.t(TAG).d("play json failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * P2 线路记忆（8.83 PlayerChooser.getSwitchIndex 语义）：键=规则名+剧集 id，
     * 默认 0。供播放器内线路切换入口调用（当前桥路径暂无切换 UI，只记不换）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> LINE_INDEX =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static int lineIndex(String ruleTitle, String episodeId) {
        if (ruleTitle == null || episodeId == null) return 0;
        Integer i = LINE_INDEX.get(ruleTitle + "|" + episodeId);
        return i == null ? 0 : Math.max(0, i);
    }

    public static void rememberLine(String ruleTitle, String episodeId, int index) {
        if (ruleTitle == null || episodeId == null || index < 0) return;
        LINE_INDEX.put(ruleTitle + "|" + episodeId, index);
    }

    /**
     * 冲突字符解码（官方 StringUtil.decodeConflictStr）：
     * {@code ？？}→{@code ?}、{@code ＆＆}→{@code &}、{@code ；；}→{@code ;}、{@code ，，}→{@code ,}。
     */
    private String decodeConflict(String s) {
        if (s == null) return "";
        return s.replace("？？", "?").replace("＆＆", "&").replace("；；", ";").replace("，，", ",");
    }

    public void destroy() {
        engine.destroy();
    }

    // ================= 内部 =================

    private String effectiveDetailRule(boolean fromSearch) {
        String r = null;
        if (fromSearch) {
            r = rule.getSdetailFindRule();
            if (r == null || r.trim().isEmpty() || "*".equals(r.trim())) r = null;
        }
        if (r == null) r = rule.getDetailFindRule();
        if (r == null || r.trim().isEmpty() || "*".equals(r.trim())) return null;
        return r;
    }

    /** 规则是否没有详情解析规则（此时 V4 必空，条目 lazyRule 求值结果应直接播放）。 */
    private boolean isEmptyDetailRule() {
        String r = rule.getDetailFindRule();
        return r == null || r.trim().isEmpty() || "*".equals(r.trim());
    }

    /** 结果是否形如媒体直链（去 # 标记后看 http 前缀与常见媒体后缀/关键字）。 */
    private static boolean looksLikeMediaUrl(String url) {
        if (url == null) return false;
        String l = url.toLowerCase();
        int h = l.indexOf('#');
        if (h >= 0) l = l.substring(0, h);
        l = l.trim();
        if (!l.startsWith("http")) return false;
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".flv")
                || l.contains(".ts?") || l.contains(".ts&") || l.endsWith(".ts")
                || l.endsWith(".mkv") || l.endsWith(".avi") || l.contains("mime=video")
                || l.contains("mime=audio") || l.contains(".mp3") || l.contains(".wav");
    }

    /**
     * 官方 UrlDetector.isVideoOrMusic 同款判定（静态规则部分；含 @rule=/@lazyRule= 的返回 false，
     * 因需先解析）。
     */
    public static boolean isVideoOrMusicUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.trim();
        if (u.contains("ignoreVideo=true") || u.contains("#ignoreMusic=true#")) return false;
        if (u.contains("isVideo=true") || u.contains("isMusic=true")) return true;
        String low = u.toLowerCase();
        if (low.startsWith("x5play://")) return true;
        if (u.contains("@rule=") || u.contains("@lazyRule=")) return false;
        if (low.startsWith("rtmp://") || low.startsWith("rtsp://") || u.contains("video://")) return true;
        if (low.contains(".mp4.jp") || low.contains(".mp4.png")) return false;
        boolean media = looksLikeMediaUrl(u);
        // 诊断（定根账可观测性缺口）：后缀门是电台流被判网页的第一道关，此前
        // 无日志。只记判定布尔与后缀形态，不记完整地址；后缀表本体不动。
        try {
            SpiderDebug.log("hk", "suffixGate hit=%b ext=%s", media, suffixShape(u));
        } catch (Throwable ignored) {
        }
        return media;
    }

    /** 诊断用：只取地址的后缀形态（最后一个点后、问号/井号前的短串），不记完整地址。 */
    private static String suffixShape(String url) {
        try {
            String s = url;
            int h = s.indexOf('#');
            if (h >= 0) s = s.substring(0, h);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            int slash = s.lastIndexOf('/');
            int dot = s.lastIndexOf('.');
            if (dot > slash && dot < s.length() - 1) {
                String ext = s.substring(dot + 1).toLowerCase();
                return ext.length() <= 10 ? ext : ext.substring(0, 10);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 是否为图片 URL（静态后缀判定）。 */
    public static boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String u = url.trim().toLowerCase();
        if (u.contains("@rule=") || u.contains("@lazyRule=")) return false;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg")
                || u.endsWith(".gif") || u.endsWith(".webp") || u.endsWith(".bmp");
    }

    /**
     * 是否为动作 URL（8.83 StringUtil 动作 URL 表同款：toast://、copy://、input://、
     * confirm://、select://、download:// 并列）。
     * 这类 URL 只应走 handleActionUrl，绝不能被当成直接播放地址或进 V4。
     */
    public static boolean isActionUrl(String url) {
        if (url == null) return false;
        String l = url.trim().toLowerCase();
        return l.startsWith("toast://") || l.startsWith("copy://")
                || l.startsWith("input://") || l.startsWith("confirm://")
                || l.startsWith("select://") || l.startsWith("download://")
                || l.startsWith("hiker://empty");
    }

    /**
     * dealWithUrl 分流（官方 ArticleListFragment.dealWithUrl 顺序）：
     * video=直接播放 / pics=漫画 / x5=webview规则 / web=网页 / image=图片 / magnet=分享 / ""=未知。
     */
    public static String dealKind(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        String u = url.trim();
        // 8.83 H2：@lazyRule= 优先（dealLazyRule），绝不按普通 URL 分流；
        // 只看 @rule= 前段是否含 @lazyRule=（与 clickItem 顺序一致）。
        int ar = u.indexOf("@rule=");
        String pre = ar >= 0 ? u.substring(0, ar) : u;
        if (pre.contains("@lazyRule=")) return "";
        String low = u.toLowerCase();
        if (low.startsWith("pics://")) return "pics";
        if (low.startsWith("x5://")) return "x5";
        if (isVideoOrMusicUrl(u)) return "video";
        if (low.startsWith("magnet:") || low.startsWith("thunder:")
                || low.startsWith("ftp://") || low.startsWith("ed2k:")) return "magnet";
        if (isImageUrl(u)) return "image";
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("file://")) return "web";
        return "";
    }

    /**
     * 用户拍板（相对原版的有意分叉）：海阔磁力／ed2k／thunder／ftp 链接一律走星辰播放器
     * （经 SiteApi.hkPlayerContent 的 Source.fetch → Thunder.fetch 迅雷转换），
     * 不维持原版、不走系统选应用。口径只认 scheme 前缀（magnet:/ed2k:/thunder:/ftp://），
     * 不要求 "//"，与 Thunder.match 的 scheme 判定、SiteApi.isThunderId、
     * VideoActivity.isThunderUrl 看齐。上游 Thunder SCHEMES 本就含 ftp，活链
     * XLTaskHelper.addThunderTask 内 ftp:// 走 P2sp 任务，故 ftp 不再留给系统选应用。
     */
    public static boolean isP2pPlayLink(String url) {
        if (url == null) return false;
        String low = url.trim().toLowerCase();
        return low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:") || low.startsWith("ftp://");
    }

    private String cleanDetailUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash).trim();
        return u;
    }

    /** 带按源定向打点的取数：请求归属当前规则（kind=hk、id=规则名），进出恢复旧标记。 */
    private String getMarked(String url) throws IOException {
        SourceGate.Mark prev = SourceGate.current();
        SourceGate.mark("hk", rule.getTitle());
        try {
            return HkHttp.get(url, rule.resolvedUa());
        } finally {
            if (prev != null) SourceGate.mark(prev.kind, prev.id);
            else SourceGate.clear();
        }
    }

    private List<HkDetailItem> fromJsDetail(String jsCode, String url) throws Exception {
        // R2：8.83 详情 JS 求值前先抓详情 HTML，getResCode()/getCode() 可取正文
        String fetchUrl = cleanDetailUrl(url);
        if (!fetchUrl.isEmpty() && (fetchUrl.startsWith("http://") || fetchUrl.startsWith("https://"))) {
            try {
                String body = getMarked(fetchUrl);
                if (body != null && !body.isEmpty()) engine.getJsRuntime().setEvalResCodeBody(body);
            } catch (Throwable ignored) {
            }
        }
        try {
            List<Map<String, String>> raw = engine.getJsRuntime().parseDetailRaw(jsCode, url);
            List<HkDetailItem> items = new ArrayList<>();
            String defaultColType = rule.getDetailColType();
            for (Map<String, String> m : raw) {
                HkDetailItem it = new HkDetailItem();
                it.setTitle(str(m, "title"));
                it.setUrl(str(m, "url"));
                String pic = str(m, "pic_url");
                if (pic.isEmpty()) pic = str(m, "pic");
                if (pic.isEmpty()) pic = str(m, "img");
                if (pic.isEmpty()) pic = str(m, "image");
                if (pic.isEmpty()) pic = str(m, "cover");
                if (pic.isEmpty()) pic = str(m, "thumbnail");
                it.setPic(pic);
                it.setDesc(str(m, "desc"));
                it.setLine(str(m, "line"));
                String col = str(m, "col_type");
                if (col.isEmpty()) col = str(m, "colType");
                // 官方 dealRule：detail_col_type 作为整页默认样式，无 col_type 的条目回退到它
                if (col.isEmpty()) col = defaultColType;
                it.setColType(col);
                items.add(it);
            }
            return items;
        } finally {
            engine.getJsRuntime().setEvalResCodeBody(null);
        }
    }

    private static String str(Map<String, String> m, String key) {
        String v = m.get(key);
        return v == null ? "" : v;
    }

}
