package com.fongmi.android.tv.read;

import java.util.List;
import java.util.Locale;

/**
 * 阅读 kind 解析：漫画 vs 小说（详情级改道用）。
 * <p>
 * 详情级判别只定了"是书"，没定是哪种书。本解析按序尝试，
 * 全程免网络（详情打开时不能为定 kind 再拉一次网）：
 * <ol>
 * <li>内联图片串：前 3 章任一章节 url 本身就是图片串（py 漫画生态约定）
 * → COMIC；</li>
 * <li>显式声明：typeName 含小说/文学 → NOVEL；</li>
 * <li>章节名：含章/回 → NOVEL（章/回为小说专属）；含卷 → NOVEL
 * （轻小说分卷常见；漫画单行本分卷若落到这里会错进小说阅读器，
 * 属已知残余风险，见报告，可用手动入口纠正）；</li>
 * <li>兜底：COMIC（py 漫画章节多为链接形态，小说多用"章"命名，
 * 未知形态偏漫画侧；错分不崩，阅读器有失败提示）。</li>
 * </ol>
 * 音频章节（mp3/m4a 等）由改道层先行排除，不进本解析。
 */
public class ReadingKindProbe {

    public enum Kind {COMIC, NOVEL}

    /** 解析结果。 */
    public static class ProbeResult {
        public final Kind kind;
        /** 判据（记日志用）。 */
        public final String why;

        private ProbeResult(Kind kind, String why) {
            this.kind = kind;
            this.why = why == null ? "" : why;
        }
    }

    /** 章节 url 里的音频扩展名（听书源）：这类不进阅读改道。 */
    private static final String[] AUDIO_EXTS = {
            ".mp3", ".m4a", ".aac", ".ogg", ".oga", ".wav", ".flac", ".opus", ".wma", ".amr"
    };

    private ReadingKindProbe() {
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 任一章节 url 是否音频地址（听书源守卫）。 */
    public static boolean hasAudioChapterUrl(List<ReadingSession.Chapter> chapters) {
        if (chapters == null) return false;
        for (ReadingSession.Chapter c : chapters) {
            if (c != null && isAudioUrl(c.url)) return true;
        }
        return false;
    }

    private static boolean isAudioUrl(String url) {
        if (isEmpty(url)) return false;
        String low = url.trim().toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        if (q >= 0) low = low.substring(0, q);
        int h = low.indexOf('#');
        if (h >= 0) low = low.substring(0, h);
        for (String ext : AUDIO_EXTS) {
            if (low.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 解析 kind。
     *
     * @param chapters     章节表（name/url）
     * @param chapterNames 章节名表（与 chapters 对应，可空）
     * @param typeName     详情分类/类型名
     */
    public static ProbeResult probe(List<ReadingSession.Chapter> chapters,
                                    List<String> chapterNames, String typeName) {
        // 1. 内联图片串（py 漫画生态约定：章节 url 即图片串）
        if (chapters != null) {
            int checked = 0;
            for (ReadingSession.Chapter c : chapters) {
                if (c == null || checked >= 3) break;
                checked++;
                if (!ReadingGate.imagesOfChapter(c).isEmpty()) {
                    return new ProbeResult(Kind.COMIC, "inline-images");
                }
            }
        }
        // 2. 显式声明
        if (ReadingDetailJudge.declaredNovelCategory(typeName)) {
            return new ProbeResult(Kind.NOVEL, "declared-novel:" + typeName.trim());
        }
        // 3. 章节名：章/回/卷 → 小说
        if (chapterNames != null) {
            for (String n : chapterNames) {
                if (ReadingDetailJudge.isBookChapterName(n)) {
                    return new ProbeResult(Kind.NOVEL, "chapter-name:" + n.trim());
                }
            }
        }
        // 4. 兜底：漫画侧
        return new ProbeResult(Kind.COMIC, "fallback-comic");
    }
}
