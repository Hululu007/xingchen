package com.fongmi.android.tv.read;

import android.app.Activity;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.Episode;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;

/**
 * 详情级改道：不漏认两层（源级标记 / 详情级判别）命中后，
 * 用当前线路的选集表组装阅读会话、打开对应阅读器。
 * <p>
 * 与 playerContent 时的改道（VideoActivity.divertToReader）同源：
 * 会话只带章节表（name/url），内容由阅读器按章懒取
 * （漫画：会话自带/内嵌串/海阔懒取；小说：首章内联/按章重跑解析/海阔抓取）。
 * kind 由 {@link ReadingKindProbe} 解析（内联图片→漫画，否则小说）。
 */
public class ReadingDetailDivert {

    private ReadingDetailDivert() {
    }

    private static void log(String fmt, Object... args) {
        try {
            SpiderDebug.log("reading-nomiss", fmt, args);
        } catch (Throwable ignored) {
        }
    }

    /** 从选集表取章节名表（判别用）。 */
    public static List<String> chapterNames(List<Episode> episodes) {
        List<String> names = new ArrayList<>();
        if (episodes == null) return names;
        for (Episode e : episodes) {
            if (e != null) names.add(e.getName() == null ? "" : e.getName());
        }
        return names;
    }

    /** 从选集表组装章节表。 */
    public static List<ReadingSession.Chapter> toChapters(List<Episode> episodes) {
        List<ReadingSession.Chapter> chapters = new ArrayList<>();
        if (episodes == null) return chapters;
        for (Episode e : episodes) {
            if (e == null) continue;
            String name = e.getName() == null ? "" : e.getName();
            String url = e.getUrl() == null ? "" : e.getUrl();
            if (!TextUtils.isEmpty(name) || !TextUtils.isEmpty(url)) {
                chapters.add(new ReadingSession.Chapter(name, url));
            }
        }
        return chapters;
    }

    /**
     * 整页改道阅读器。
     *
     * @param activity   当前详情页（改道后 finish）
     * @param siteKey    源 key（会话 siteKey，py 小说按章重跑用）
     * @param siteName   源显示名
     * @param ruleTitle  海阔规则名（非海阔传空串；阅读器按规则懒取用）
     * @param bookTitle  书名（详情标题）
     * @param coverUrl   封面
     * @param episodes   当前线路选集表
     * @param why        判据（记日志）
     * @return true=已改道（调用方须 finish 详情页并抑制自动播）
     */
    public static boolean divert(Activity activity, String siteKey, String siteName,
                                 String ruleTitle, String bookTitle, String coverUrl,
                                 List<Episode> episodes, String why) {
        try {
            List<ReadingSession.Chapter> chapters = toChapters(episodes);
            if (chapters.isEmpty()) return false;
            // 听书守卫：章节地址是音频文件 → 不改道（听书源也用第X章命名）
            if (ReadingKindProbe.hasAudioChapterUrl(chapters)) {
                log("skip audio-chapters key=%s", siteKey);
                return false;
            }
            ReadingKindProbe.ProbeResult pr = ReadingKindProbe.probe(
                    chapters, chapterNames(episodes), null);
            ReadingSession s = new ReadingSession();
            s.kind = pr.kind == ReadingKindProbe.Kind.NOVEL
                    ? ReadingSession.Kind.NOVEL : ReadingSession.Kind.COMIC;
            s.sourceName = siteName == null ? "" : siteName;
            s.siteKey = siteKey == null ? "" : siteKey;
            // 海阔：传规则名（阅读器按规则懒取 pics/正文）；py 源不走规则，传空防误查
            s.ruleTitle = ruleTitle == null ? "" : ruleTitle;
            s.bookTitle = bookTitle == null ? "" : bookTitle;
            s.coverUrl = coverUrl == null ? "" : coverUrl;
            s.chapters = chapters;
            s.chapterIndex = 0;
            ReadingSession.Chapter c = s.currentChapter();
            s.chapterUrl = c.url;
            log("detail divert key=%s why=%s kind=%s chapters=%d",
                    siteKey, why, s.kind, chapters.size());
            ReadingRouter.openReading(activity, s);
            return true;
        } catch (Throwable t) {
            log("detail divert failed key=%s err=%s", siteKey, t.getMessage());
            return false;
        }
    }
}
