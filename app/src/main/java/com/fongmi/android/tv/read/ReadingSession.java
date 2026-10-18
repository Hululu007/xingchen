package com.fongmi.android.tv.read;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阅读会话载荷：判别层（{@link ReadingGate}）产出、阅读器消费。
 * 大数据（图片列表）走进程内静态仓，Intent 只带 id 与小字段，
 * 与 VideoActivity.HK_EPISODES_STORE 同思路，避免 Binder 事务撑爆。
 */
public class ReadingSession {

    public enum Kind {COMIC, NOVEL}

    /** 单章。 */
    public static class Chapter {
        public String name = "";
        public String url = "";
        public String pic = "";

        public Chapter() {
        }

        public Chapter(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    public Kind kind = Kind.COMIC;
    /** 来源名：规则名（海阔）/ 源名（py）。 */
    public String sourceName = "";
    public String bookTitle = "";
    public String coverUrl = "";
    /** 当前章下标（chapters 为空时为 0）。 */
    public int chapterIndex = 0;
    public List<Chapter> chapters = new ArrayList<>();
    /** 漫画：当前章图片（可能为空＝进阅读器后懒取）。 */
    public List<String> images = new ArrayList<>();
    /** 小说：当前章正文（可能为空＝进阅读器后懒取）。 */
    public String text = "";
    /** 海阔懒取：规则名。 */
    public String ruleTitle = "";
    /** 懒取键：章节地址。 */
    public String chapterUrl = "";
    /** 图片防盗链 Referer（海阔规则页地址）。 */
    public String referer = "";
    /** py 源 key（懒取章节表用）。 */
    public String siteKey = "";
    /** Legado 书源 bookSourceUrl（书源懒取正文/图片用）。 */
    public String legadoKey = "";
    /** 本地书 id（localbook 包；非空=本地书，懒取走 LocalBookReader）。 */
    public String localId = "";
    /** 起始页（漫画第几张图；阅读器打开时定位用）。 */
    public int startPage = 0;

    // ---- 进程内静态仓：大数据不走 Intent ----
    private static final ConcurrentHashMap<String, ReadingSession> STORE = new ConcurrentHashMap<>();

    /** 存入并返回 id。 */
    public static String put(ReadingSession s) {
        String id = UUID.randomUUID().toString();
        STORE.put(id, s);
        if (STORE.size() > 8) {
            String first = STORE.keys().nextElement();
            STORE.remove(first);
        }
        return id;
    }

    public static ReadingSession take(String id) {
        if (id == null) return null;
        return STORE.remove(id);
    }

    public static ReadingSession peek(String id) {
        if (id == null) return null;
        return STORE.get(id);
    }

    public Chapter currentChapter() {
        if (chapters != null && chapterIndex >= 0 && chapterIndex < chapters.size()) {
            return chapters.get(chapterIndex);
        }
        Chapter c = new Chapter();
        c.name = bookTitle;
        c.url = chapterUrl;
        return c;
    }
}
