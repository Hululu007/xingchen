package com.fongmi.android.tv.api;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红果弹幕壳子抓取器：与 HongguoCommentApi 同层共建——共用其传输（轻签名器/设备号/全局
 * OkHttp 直连），只差 body 形态（601）与解析。形态逐字段照 hongguo-danmaku-trace.md §2：
 * comment/list/{vid} 按 30 秒一窗分段拉完整集，窗间 ≥180ms，整集 ≤40 窗、≤3000 条。
 * 产出渲染层现成认的 Bilibili 弹幕 XML 落本地缓存文件，渲染层零改。
 * 增量口径（douyu-live-danmaku-noshow-trace.md 第五节修法）：已拉到的窗先落盘先挂，
 * 任一窗失败只停在已得部分、不整集连坐；全程零条才按无弹幕返回 null。
 * 缓存复用口径：一次拉取「自然收尾」（服务端没更多/时长到头/游标停/撞上限，且无一窗
 * 失败）就在同目录落 {vid}.done 完成标记，标记内容区分两种收尾——有条目写 '1'、真零行
 * 空集写 '0'，免得日后把「拉过但真没有」与「拉满了」混作一谈；下次 fetch 命中标记直接
 * 用现成文件（有文件给文件、没文件是已知空集），不再重复拉。没标记的半截文件当种子：重拉时与旧货合并
 * 去重继续长大，拉失败也不缩水；中断/失败不记完成，下次进来接着补。
 * 预拉（下一集）与本集制备走同一条 fetchToFile：预拉只落盘、永不挂载，
 * 挂载只在播放侧按当前集 vid 裁定（VideoActivity.onHongguoDanmakuReady 复核当前集）。
 * 全部方法失败静默降级，绝不抛异常进播放链；平台只下发采样热弹幕，
 * 条数少于平台自报是平台行为（trace §结论 4），本类不做任何补凑。
 */
public class HongguoDanmakuApi {

    public static final String SOURCE_NAME = "红果弹幕";

    private static final long WINDOW_GAP_MS = 180L;
    private static final int MAX_WINDOWS = 40;
    private static final int MAX_ITEMS = 3000;
    private static final int WINDOW_COUNT = 90;

    public static class Item {
        public final long offsetMs;
        public final String text;

        Item(long offsetMs, String text) {
            this.offsetMs = offsetMs;
            this.text = text;
        }
    }

    /** 制备进度回调：每落盘一份可用文件（首份或内容增长后的新版）调一次，item 的 url 始终指向同一文件。在抓取线程调用，UI 挂载由宿主自己切线程。 */
    public interface ProgressListener {
        void onProgress(Danmaku item);
    }

    /** 一窗数据源接缝（生产＝post＋parseWindow，单测喂假窗）：取一窗，失败/回验不过返回 null。 */
    interface WindowSource {
        Window fetch(long start, String cursor);
    }

    /** 累积有增长时的回调，参数为当前全量快照（防御性拷贝）。 */
    interface GrowthListener {
        void onGrowth(List<Item> snapshot);
    }

    /** 拉取结果：items 为最终累积；complete 为是否自然收尾（无一窗失败地拉到底/到上限）——只有 complete 才配落完成标记，半截不记。 */
    static class CollectOutcome {
        final List<Item> items;
        final boolean complete;

        CollectOutcome(List<Item> items, boolean complete) {
            this.items = items;
            this.complete = complete;
        }
    }

    /** 一窗的解析结果：nextStart 为服务端给的下一窗起点（没给时为 -1），hasMore 为服务端原值，推进与否由 nextWindowStart 裁定。 */
    static class Window {
        final List<Item> items;
        final long nextStart;
        final boolean hasMore;
        final String cursor;

        Window(List<Item> items, long nextStart, boolean hasMore, String cursor) {
            this.items = items;
            this.nextStart = nextStart;
            this.hasMore = hasMore;
            this.cursor = cursor;
        }
    }

    // ---------- 窗口推进（纯逻辑，可单测） ----------

    /**
     * 下一窗起点判定：has_more 为假、服务端没给推进值、推进值不大于本窗起点，一律停（返回 -1），
     * 防游标不推进死循环（与评论侧游标纪律同口径）。
     */
    static long nextWindowStart(long start, long responseNext, boolean hasMore) {
        if (!hasMore) return -1;
        if (responseNext <= start) return -1;
        return responseNext;
    }

    /** 合并一窗弹幕：按 offset_ms:text 去重（HGTV 同口径），空文/无时间整条丢弃。 */
    static void mergeWindow(List<Item> acc, Set<String> seen, List<Item> windowItems) {
        for (Item item : windowItems) {
            if (item == null || item.text == null || item.text.isEmpty() || item.offsetMs < 0) continue;
            if (!seen.add(item.offsetMs + ":" + item.text)) continue;
            acc.add(item);
            if (acc.size() >= MAX_ITEMS) return;
        }
    }

    /** 转 Bilibili 弹幕 XML：按时间升序，p=秒数浮点,滚动,25 号字,白字；正文做 XML 转义。 */
    static String buildXml(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong(item -> item.offsetMs));
        StringBuilder sb = new StringBuilder(sorted.size() * 64 + 64);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>");
        for (Item item : sorted) {
            sb.append("<d p=\"")
                    .append(String.format(Locale.US, "%.3f", item.offsetMs / 1000f))
                    .append(",1,25,16777215,0,0\">")
                    .append(escapeXml(item.text))
                    .append("</d>");
        }
        sb.append("</i>");
        return sb.toString();
    }

    static String escapeXml(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // XML 1.0 非法控制字符直接丢弃，防整份文件被解析器拒收
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 调试打点（hongguo 两 API 原本零日志，真机定案只能靠样本回放）：只记日志、绝不影响行为——日志通道自身出问题也吞掉，本类的失败静默纪律优先。 */
    private static void log(String msg, Object... args) {
        try {
            SpiderDebug.log("hongguo-danmaku", msg, args);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 抓取 ----------

    /**
     * 窗口拉取循环（纯逻辑，可单测）：逐窗取数合并去重，任一窗取不到（请求失败/回验不过）
     * 即停在已得部分——不再整集连坐；每窗有新增就回调当前全量快照，供增量落盘/挂载。
     * 窗数（40）/条数（3000）上限、窗间 ≥180ms、游标不推进即停的纪律都在这。
     *
     * @param durationMs 本集时长（毫秒），&lt;=0 表示未知——靠服务端推进值与窗数上限收尾。
     * @return 最终累积（可能为空：全程一窗都没取到或全是空窗）。
     */
    static List<Item> collectWindows(WindowSource source, long durationMs, GrowthListener growth) {
        return collectWindows(source, durationMs, growth, null).items;
    }

    /**
     * 带种子与完整性结果的拉取循环：seed 是半截旧文件的既有条目，先并入累积再逐窗拉，
     * 跨新旧按 offset:text 去重——重拉只长不缩。任一窗取不到（null/异常）或线程被中断，
     * 停在已得部分且 complete=false；服务端没更多、时长到头、游标不推进、撞窗数/条数
     * 上限而收尾的，complete=true。
     */
    static CollectOutcome collectWindows(WindowSource source, long durationMs, GrowthListener growth, List<Item> seed) {
        List<Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (seed != null) mergeWindow(all, seen, seed);
        boolean failed = false;
        String cursor = "";
        long start = 0;
        for (int window = 0; window < MAX_WINDOWS; window++) {
            if (window > 0) {
                try {
                    Thread.sleep(WINDOW_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed = true;
                    break;
                }
            }
            if (durationMs > 0 && start >= durationMs) break;
            Window parsed;
            try {
                parsed = source.fetch(start, cursor);
            } catch (Exception e) {
                failed = true;
                break;
            }
            if (parsed == null) {
                failed = true;
                break;
            }
            int before = all.size();
            mergeWindow(all, seen, parsed.items);
            if (growth != null && all.size() > before) growth.onGrowth(new ArrayList<>(all));
            if (all.size() >= MAX_ITEMS) break;
            long next = nextWindowStart(start, parsed.nextStart, parsed.hasMore);
            if (next < 0) break;
            start = next;
            cursor = parsed.cursor;
        }
        return new CollectOutcome(all, !failed);
    }

    public static Danmaku fetchToFile(String seriesId, String vid, long durationMs) {
        return fetchToFile(seriesId, vid, durationMs, null);
    }

    /**
     * 拉本集弹幕并增量落盘成本地 XML 文件：第一窗拿到货就落盘并经 listener 报出（不等整集），
     * 后续窗有新增就重写同一文件再报；单窗失败保留已得部分。最终返回指向该文件的 Danmaku
     * 条目（name=红果弹幕、url=本地绝对路径）；全程零条才返回 null。本类无静态状态，
     * 一次失败不毒化下一次制备（宿主的重试门在 VideoActivity 侧按成功与否置位）。
     * 命中完成标记时不发任何请求：有文件直接包条目返回、没文件返回 null（已知空集）。
     * 本集制备与下一集预拉共用本方法，预拉侧传 listener=null 且丢弃返回值——只落盘不挂载。
     */
    public static Danmaku fetchToFile(String seriesId, String vid, long durationMs, ProgressListener listener) {
        if (!HongguoCommentApi.isId(seriesId) || !HongguoCommentApi.isId(vid)) return null;
        // 完整缓存免重复拉：标记与文件同目录同生死，标记在就算拉满过
        if (hasCompleteCache(vid)) {
            File file = cacheFile(vid);
            Danmaku cached = file != null && file.length() > 0 ? toDanmaku(file) : null;
            log("fetch cache-hit vid=%s item=%s", vid, cached != null);
            return cached;
        }
        // 半截文件当种子：旧货先入账，重拉在其上继续长，拉失败也不缩水
        List<Item> seed = readCacheItems(vid);
        Danmaku[] ready = new Danmaku[1];
        CollectOutcome outcome = collectWindows((start, cursor) -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("comment_source", 601).put("server_channel", 1000)
                        .put("group_id", vid).put("group_type", 30)
                        .put("comment_type", 20).put("sort", 1)
                        .put("count", WINDOW_COUNT).put("cursor", cursor)
                        .put("aid", 8662).put("compliance_status", 0)
                        .put("business_param", new JSONObject()
                                .put("book_id", seriesId)
                                .put("start_offset_time", start)
                                .put("playlet_item_duration", Math.max(0, durationMs))
                                .put("need_danmaku_guide_type", new JSONArray().put(1).put(3).put(4).put(2)));
                return parseWindow(HongguoCommentApi.post("comment/list/" + vid, payload, 601, 1000), seriesId, vid);
            } catch (Exception e) {
                log("window fetch failed vid=%s start=%d err=%s", vid, start, e.getClass().getSimpleName());
                return null;
            }
        }, durationMs, snapshot -> {
            Danmaku item = writeItem(vid, snapshot);
            if (item != null) {
                ready[0] = item;
                if (listener != null) listener.onProgress(item);
            }
        }, seed);
        List<Item> all = outcome.items;
        if (all.isEmpty()) {
            // 自然收尾且真空集：记完成免得每次起播白跑全程；失败中断的不记，下次还能补
            if (outcome.complete) markComplete(vid, true);
            log("fetch done vid=%s items=0 complete=%s", vid, outcome.complete);
            return null;
        }
        Danmaku item = ready[0] != null ? ready[0] : writeItem(vid, all);
        // 记完成的门槛：自然收尾且最终文件真落了盘——半截（失败收尾）不记，下次继续增量补
        if (outcome.complete && item != null) markComplete(vid, false);
        log("fetch done vid=%s items=%d complete=%s item=%s", vid, all.size(), outcome.complete, item != null);
        return item;
    }

    /** 把当前累积写盘并包成 Danmaku 条目；写盘失败返回 null（已有旧文件不删，后续窗还会再试）。 */
    private static Danmaku writeItem(String vid, List<Item> items) {
        File file = writeXmlFile(vid, buildXml(items));
        return file == null ? null : toDanmaku(file);
    }

    private static Danmaku toDanmaku(File file) {
        Danmaku danmaku = new Danmaku();
        danmaku.setName(SOURCE_NAME);
        danmaku.setUrl(file.getAbsolutePath());
        return danmaku;
    }

    // ---------- 缓存文件与完成标记 ----------

    private static File cacheFile(String vid) {
        try {
            return new File(new File(App.get().getCacheDir(), "hongguo_danmaku"), vid + ".xml");
        } catch (Throwable t) {
            return null;
        }
    }

    private static File markerFile(String vid) {
        try {
            return new File(new File(App.get().getCacheDir(), "hongguo_danmaku"), vid + ".done");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这集是不是已经拉满过（完成标记在手）。标记与 XML 同目录：系统清缓存两者同去，不会只剩标记骗过判定。 */
    static boolean hasCompleteCache(String vid) {
        File marker = markerFile(vid);
        return marker != null && marker.isFile();
    }

    /** 落完成标记：内容区分收尾形态——有条目的完整集写 '1'、真零行空集写 '0'，只凭标记在不在分不出这两者，日后排查别再误判。 */
    private static void markComplete(String vid, boolean empty) {
        try {
            File marker = markerFile(vid);
            if (marker == null) return;
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(marker)) {
                out.write(empty ? '0' : '1');
            }
        } catch (Throwable ignored) {
        }
    }

    /** 读半截文件的既有条目当种子；读不出（文件不在/坏）就当没有——重拉照常从零长。 */
    private static List<Item> readCacheItems(String vid) {
        File file = cacheFile(vid);
        if (file == null || !file.isFile() || file.length() == 0) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return parseXmlItems(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static final Pattern DANMAKU_ROW = Pattern.compile("<d p=\"([0-9]+(?:\\.[0-9]+)?),[^\"]*\">(.*?)</d>", Pattern.DOTALL);

    /** 把自家 buildXml 写出的文件读回条目（只认自家格式）：秒数还原毫秒、正文反转义；坏行跳过。 */
    static List<Item> parseXmlItems(String xml) {
        List<Item> items = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return items;
        Matcher matcher = DANMAKU_ROW.matcher(xml);
        while (matcher.find()) {
            try {
                long offsetMs = Math.round(Double.parseDouble(matcher.group(1)) * 1000);
                String text = unescapeXml(matcher.group(2));
                if (offsetMs >= 0 && !text.isEmpty()) items.add(new Item(offsetMs, text));
            } catch (Exception ignored) {
            }
        }
        return items;
    }

    static String unescapeXml(String text) {
        // &amp; 必须最后还原，防 &amp;lt; 被二次解成 <
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    // ---------- 解析（含回验闸） ----------

    /** 解析一窗响应；code≠0 或归属回验不过返回 null——回验只管本窗，本窗数据整窗丢弃，先前已验过的窗不受牵连（collectWindows 按已得部分收尾）。 */
    static Window parseWindow(JSONObject result, String seriesId, String vid) {
        try {
            if (result == null) {
                log("gate reject vid=%s reason=no-result", vid);
                return null;
            }
            if (result.optInt("code", -1) != 0) {
                log("gate reject vid=%s reason=code=%d", vid, result.optInt("code", -1));
                return null;
            }
            JSONObject data = result.getJSONObject("data");
            JSONObject info = data.getJSONObject("common_list_info");
            boolean more = info.optBoolean("has_more", false);
            JSONObject extra = data.optJSONObject("extra");
            long next = extra == null ? -1 : extra.optLong("next_query_danmaku_list_time", -1);
            JSONArray rows = data.optJSONArray("data_list");
            List<Item> items = new ArrayList<>();
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject comment = row.optJSONObject("comment");
                    if (comment == null) continue;
                    JSONObject common = comment.optJSONObject("common");
                    JSONObject expand = comment.optJSONObject("expand");
                    if (common == null || expand == null) continue;
                    // 回验闸 group_id 腿：归属对不上整窗作废，防误抓别家弹幕（与评论侧同一道闸）。
                    // 窗口本就是按 group_id=vid 定向请求的，此腿对得上是请求寻址的本集 id，作废语义成立。
                    if (common.has("group_id") && !vid.equals(common.optString("group_id"))) {
                        log("gate reject vid=%s leg=group got=%s", vid, common.optString("group_id"));
                        return null;
                    }
                    // book_id 腿只拦硬冲突：601 弹幕载荷不守评论侧的字段语义——真人行 book_id
                    // 恒为 0（只有平台插入行带真值），0/空/缺失＝该行未带书籍归属，跳过此腿；
                    // 只有带了真 id 又与本剧对不上才整窗作废。照评论语义判 0 会把每窗全废（真机实证）。
                    String bookId = expand.optString("book_id", "");
                    if (HongguoCommentApi.isId(bookId) && !seriesId.equals(bookId)) {
                        log("gate reject vid=%s leg=book got=%s", vid, bookId);
                        return null;
                    }
                    JSONObject contentObj = common.optJSONObject("content");
                    String text = contentObj == null ? "" : contentObj.optString("text").trim();
                    long offset = expand.optLong("offset_time", -1);
                    if (text.isEmpty() || offset < 0) continue;
                    items.add(new Item(offset, text));
                }
            }
            return new Window(items, next, more, info.optString("cursor"));
        } catch (Exception e) {
            return null;
        }
    }

    private static File writeXmlFile(String vid, String xml) {
        try {
            File dir = new File(App.get().getCacheDir(), "hongguo_danmaku");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File file = new File(dir, vid + ".xml");
            // 增量重写走临时文件＋改名原子替换，防渲染层正好读到写一半的文件
            File tmp = new File(dir, vid + ".xml.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            if (tmp.renameTo(file)) return file.length() > 0 ? file : null;
            // 个别文件系统改名失败：退回直写覆盖
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(xml.getBytes(StandardCharsets.UTF_8));
            }
            return file.length() > 0 ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
