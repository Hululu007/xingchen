package com.fongmi.android.tv.api;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 红果弹幕壳子抓取器：与 HongguoCommentApi 同层共建——共用其传输（轻签名器/设备号/全局
 * OkHttp 直连），只差 body 形态（601）与解析。形态逐字段照 hongguo-danmaku-trace.md §2：
 * comment/list/{vid} 按 30 秒一窗分段拉完整集，窗间 ≥180ms，整集 ≤40 窗、≤3000 条。
 * 产出渲染层现成认的 Bilibili 弹幕 XML 落本地缓存文件，渲染层零改。
 * 增量口径（douyu-live-danmaku-noshow-trace.md 第五节修法）：已拉到的窗先落盘先挂，
 * 任一窗失败只停在已得部分、不整集连坐；全程零条才按无弹幕返回 null。
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
        List<Item> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = "";
        long start = 0;
        for (int window = 0; window < MAX_WINDOWS; window++) {
            if (window > 0) {
                try {
                    Thread.sleep(WINDOW_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (durationMs > 0 && start >= durationMs) break;
            Window parsed;
            try {
                parsed = source.fetch(start, cursor);
            } catch (Exception e) {
                break;
            }
            if (parsed == null) break;
            int before = all.size();
            mergeWindow(all, seen, parsed.items);
            if (growth != null && all.size() > before) growth.onGrowth(new ArrayList<>(all));
            if (all.size() >= MAX_ITEMS) break;
            long next = nextWindowStart(start, parsed.nextStart, parsed.hasMore);
            if (next < 0) break;
            start = next;
            cursor = parsed.cursor;
        }
        return all;
    }

    public static Danmaku fetchToFile(String seriesId, String vid, long durationMs) {
        return fetchToFile(seriesId, vid, durationMs, null);
    }

    /**
     * 拉本集弹幕并增量落盘成本地 XML 文件：第一窗拿到货就落盘并经 listener 报出（不等整集），
     * 后续窗有新增就重写同一文件再报；单窗失败保留已得部分。最终返回指向该文件的 Danmaku
     * 条目（name=红果弹幕、url=本地绝对路径）；全程零条才返回 null。本类无静态状态，
     * 一次失败不毒化下一次制备（宿主的重试门在 VideoActivity 侧按成功与否置位）。
     */
    public static Danmaku fetchToFile(String seriesId, String vid, long durationMs, ProgressListener listener) {
        if (!HongguoCommentApi.isId(seriesId) || !HongguoCommentApi.isId(vid)) return null;
        Danmaku[] ready = new Danmaku[1];
        List<Item> all = collectWindows((start, cursor) -> {
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
                return null;
            }
        }, durationMs, snapshot -> {
            Danmaku item = writeItem(vid, snapshot);
            if (item != null) {
                ready[0] = item;
                if (listener != null) listener.onProgress(item);
            }
        });
        return all.isEmpty() ? null : ready[0];
    }

    /** 把当前累积写盘并包成 Danmaku 条目；写盘失败返回 null（已有旧文件不删，后续窗还会再试）。 */
    private static Danmaku writeItem(String vid, List<Item> items) {
        File file = writeXmlFile(vid, buildXml(items));
        if (file == null) return null;
        Danmaku danmaku = new Danmaku();
        danmaku.setName(SOURCE_NAME);
        danmaku.setUrl(file.getAbsolutePath());
        return danmaku;
    }

    // ---------- 解析（含回验闸） ----------

    /** 解析一窗响应；code≠0 或归属回验不过返回 null——回验只管本窗，本窗数据整窗丢弃，先前已验过的窗不受牵连（collectWindows 按已得部分收尾）。 */
    static Window parseWindow(JSONObject result, String seriesId, String vid) {
        try {
            if (result == null || result.optInt("code", -1) != 0) return null;
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
                    // 回验闸：归属对不上整窗作废，防误抓别家弹幕（与评论侧同一道闸）
                    if (common.has("group_id") && !vid.equals(common.optString("group_id"))) return null;
                    if (expand.has("book_id") && !seriesId.equals(expand.optString("book_id"))) return null;
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
