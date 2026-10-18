package com.fongmi.android.tv.read;

import android.text.TextUtils;

import com.fongmi.android.tv.api.hk.HkPlay;
import com.fongmi.android.tv.api.hk.HkRule;
import com.fongmi.android.tv.api.hk.HkRuleManager;
import com.fongmi.android.tv.bean.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 阅读分流判别层（阶段一·地基）。
 * <p>
 * 职责：在 playerContent 产出 Result、进 Source.fetch / 播放器之前，
 * 识别"漫画/小说"数据并改道去阅读器。
 * <p>
 * <b>红线（视频三链不误伤）</b>：判别条件全部是"非视频数据"的充分条件——
 * <ul>
 * <li>海阔漫画：HkPlay.pics 非空。pics 只由 {@code pics://} 解析分支产出，
 * 视频规则永远不走该分支（HkRouter 第 1.5 步），误判不可能。</li>
 * <li>海阔小说：规则 type == "小说"。视频/漫画规则的 type 不是小说，误判不可能。</li>
 * <li>py 漫画：地址串按 [#/换行/逗号] 拆后 ≥2 段且<b>每段</b>都是图片 URL
 * （扩展名必须在 path 部分，不在 query 里）。视频地址（m3u8/mp4/磁力/解析口）
 * 永远满足不了"全是图片"。</li>
 * <li>py 小说：返回串不含任何 URL 特征（无 ://、无 magnet:/ed2k:/thunder: 等协议头、
 * 不是 JSON），且长度 > 200。视频地址必带协议头，误判不可能。</li>
 * </ul>
 * Emby（csp_Emby）与 push_agent 链调用点直接跳过本层，不进判别。
 */
public class ReadingGate {

    /** 图片 URL：扩展名必须在 path 部分（query 里的 ?x=a.jpg 不算，防 m3u8 误判）。 */
    private static final Pattern IMAGE_URL = Pattern.compile(
            "^https?://[^\\s?#]+\\.(jpg|jpeg|png|webp|gif|bmp)([?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private static final int NOVEL_MIN_LEN = 200;

    private ReadingGate() {
    }

    // ---------------- 海阔 ----------------

    /** 海阔漫画：pics 非空即漫画（pics:// 分支是漫画专用标记）。 */
    public static ReadingSession fromHkComic(String ruleTitle, String bookTitle, String chapterName,
                                             String chapterUrl, HkPlay play, String referer) {
        if (play == null || play.getPics().isEmpty()) return null;
        ReadingSession s = new ReadingSession();
        s.kind = ReadingSession.Kind.COMIC;
        s.sourceName = ruleTitle == null ? "" : ruleTitle;
        s.ruleTitle = s.sourceName;
        s.bookTitle = bookTitle == null ? "" : bookTitle;
        s.chapterUrl = chapterUrl == null ? "" : chapterUrl;
        ReadingSession.Chapter c = new ReadingSession.Chapter(chapterName, s.chapterUrl);
        s.chapters.add(c);
        s.chapterIndex = 0;
        s.images = new ArrayList<>(play.getPics());
        s.referer = referer == null ? "" : referer;
        return s;
    }

    /** 海阔小说规则判定（type == "小说"）。 */
    public static boolean isHkNovelRule(String ruleTitle) {
        if (TextUtils.isEmpty(ruleTitle)) return false;
        try {
            HkRule rule = HkRuleManager.get().getRule(ruleTitle);
            return rule != null && "小说".equals(rule.getType());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 海阔小说会话：正文懒取（阅读器里按章拉）。 */
    public static ReadingSession fromHkNovel(String ruleTitle, String bookTitle,
                                             List<ReadingSession.Chapter> chapters, int chapterIndex) {
        ReadingSession s = new ReadingSession();
        s.kind = ReadingSession.Kind.NOVEL;
        s.sourceName = ruleTitle == null ? "" : ruleTitle;
        s.ruleTitle = s.sourceName;
        s.bookTitle = bookTitle == null ? "" : bookTitle;
        if (chapters != null) s.chapters = chapters;
        s.chapterIndex = Math.max(0, chapterIndex);
        ReadingSession.Chapter c = s.currentChapter();
        s.chapterUrl = c.url;
        s.referer = "";
        return s;
    }

    // ---------------- py ----------------

    /**
     * py 链判别：rawUrl 是 spider playerContent 返回、Source.fetch 之前的原始地址串。
     * 只在蜘蛛/接口源调用点使用；Emby、push_agent、海阔不进这里。
     */
    public static ReadingSession detectPy(String siteKey, String siteName, String bookTitle,
                                          String episodeName, String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) return null;
        String raw = rawUrl.trim();
        // 漫画：多段且每段都是图片 URL
        List<String> images = splitImages(raw);
        if (images.size() >= 2) {
            boolean allImage = true;
            for (String u : images) {
                if (!isImageUrl(u)) {
                    allImage = false;
                    break;
                }
            }
            if (allImage) {
                ReadingSession s = new ReadingSession();
                s.kind = ReadingSession.Kind.COMIC;
                s.sourceName = siteName == null ? "" : siteName;
                s.siteKey = siteKey == null ? "" : siteKey;
                s.bookTitle = bookTitle == null ? "" : bookTitle;
                s.images = images;
                ReadingSession.Chapter c = new ReadingSession.Chapter(episodeName, raw);
                s.chapters.add(c);
                s.chapterIndex = 0;
                return s;
            }
        }
        // 小说：纯文本（无任何 URL 特征、非 JSON、够长）
        if (!looksLikeUrl(raw) && !raw.startsWith("{") && raw.length() > NOVEL_MIN_LEN) {
            ReadingSession s = new ReadingSession();
            s.kind = ReadingSession.Kind.NOVEL;
            s.sourceName = siteName == null ? "" : siteName;
            s.siteKey = siteKey == null ? "" : siteKey;
            s.bookTitle = bookTitle == null ? "" : bookTitle;
            s.text = raw;
            ReadingSession.Chapter c = new ReadingSession.Chapter(episodeName, "");
            s.chapters.add(c);
            s.chapterIndex = 0;
            return s;
        }
        return null;
    }

    /**
     * 图片串拆分：先按行、再按字面 #、再按逗号。
     * <b>绝不按单个 & 拆</b>（HkRouter 的教训：图片地址 query 里的 & 会被切碎）。
     */
    public static List<String> splitImages(String raw) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String line : raw.split("\n")) {
            for (String seg : line.split("#")) {
                for (String part : seg.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) out.add(t);
                }
            }
        }
        return out;
    }

    public static boolean isImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        return IMAGE_URL.matcher(url.trim()).matches();
    }

    /**
     * 取某章的图片列表：章节 url 本身就是图片串（py 生态约定）时现场拆分；
     * 海阔章节走 HkPlay.pics（已在会话 images 里），这里只处理 url 内嵌串。
     */
    public static List<String> imagesOfChapter(ReadingSession.Chapter chapter) {
        List<String> out = new ArrayList<>();
        if (chapter == null || TextUtils.isEmpty(chapter.url)) return out;
        List<String> segs = splitImages(chapter.url);
        if (segs.size() >= 2) {
            boolean allImage = true;
            for (String u : segs) {
                if (!isImageUrl(u)) {
                    allImage = false;
                    break;
                }
            }
            if (allImage) return segs;
        }
        return out;
    }

    /** 是否带 URL 特征：有 :// 或常见协议头即算。 */
    public static boolean looksLikeUrl(String s) {
        if (TextUtils.isEmpty(s)) return false;
        String t = s.trim();
        if (t.contains("://")) return true;
        String low = t.toLowerCase(Locale.ROOT);
        return low.startsWith("magnet:") || low.startsWith("ed2k:") || low.startsWith("thunder:")
                || low.startsWith("ftp:") || low.startsWith("rtsp:") || low.startsWith("rtmp:")
                || low.startsWith("ws:") || low.startsWith("video://") || low.startsWith("file:");
    }

    /** 从 Result 取出阅读会话（无则 null）。 */
    public static ReadingSession extract(Result result) {
        if (result == null) return null;
        try {
            return result.getReadingSession();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
