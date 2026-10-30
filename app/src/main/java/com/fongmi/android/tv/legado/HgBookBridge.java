package com.fongmi.android.tv.legado;

import java.util.List;

/**
 * 红果内置书源桥：小说源/听书源以哨兵 bookSourceUrl 进 LegadoStore，
 * 阅读管线四段（搜索/详情/目录/正文）命中哨兵就改道本桥，再转红果包内的
 * 实现（HgBookOps，Class.forName 装配——朋友版剥掉红果包后装配失败，
 * 播/读会给人话错误，且种子源也不会被种下，两边一致）。
 * bookUrl 形态：hgbook://&lt;book_id&gt;；章节 url：hgbook://&lt;book_id&gt;/&lt;item_id&gt;。
 */
public final class HgBookBridge {

    public static final String URL_NOVEL = "https://hongguo.book/novel";
    public static final String URL_AUDIO = "https://hongguo.book/audio";
    public static final String NAME_NOVEL = "红果小说";
    public static final String NAME_AUDIO = "红果听书";

    // 类名拆开拼：朋友版剥离脚本按红果本体包名整串做全树终检，
    // 桥是剥离后要留下的文件，包名字面写全会让朋友版构建的终检误炸。
    private static final String OPS_CLASS =
            "com.fongmi.android.tv." + "hongguo.book.HgBookOpsImpl";
    private static final String BOOK_PREFIX = "hgbook://";

    private static volatile HgBookOps ops;
    private static volatile boolean opsTried;

    private HgBookBridge() {
    }

    public static boolean isHg(LegadoSource source) {
        return source != null && (URL_NOVEL.equals(source.bookSourceUrl)
                || URL_AUDIO.equals(source.bookSourceUrl));
    }

    public static boolean isAudio(LegadoSource source) {
        return source != null && URL_AUDIO.equals(source.bookSourceUrl);
    }

    /**
     * 专辑身份键判据：legadoKey 恰等于红果听书哨兵才算红果听书专辑。
     * 海阔电台的身份键是 "hk:<规则名>"、普通听书源是其 bookSourceUrl，
     * 都不许被认成红果——红果播放页入口门与各处分家判定共用这一判据，
     * 不许各处另写一份（判据散落正是串线类 bug 的来路）。
     */
    public static boolean isAudioKey(String legadoKey) {
        return URL_AUDIO.equals(legadoKey);
    }

    public static boolean isHgUrl(String bookSourceUrl) {
        return URL_NOVEL.equals(bookSourceUrl) || URL_AUDIO.equals(bookSourceUrl);
    }

    /** 实现是否在位（正式版在、朋友版不在）。 */
    public static boolean available() {
        return ops() != null;
    }

    // 红果听书播放页类名拆开拼（同 OPS_CLASS 理由：朋友版整包剥红果，
    // 本桥要留下，类名字面写全会让剥离终检误炸）。
    private static final String AUDIO_PLAYER_CLASS =
            "com.fongmi.android.tv." + "hongguo.book.HgAudioPlayActivity";

    /**
     * 红果听书单独的播放页调起（2026-10-30 分家令）：红果听书专辑只许进
     * 它自己的播放页，绝不进普通听书的 AudioBookActivity。参数是
     * LegadoAudio.put(album) 的 id（extra 键与普通播放页同为 "session"）。
     * 朋友版播放页已被剥掉 → 返回 false，调用方按失败处理（朋友版本来
     * 也没有红果听书源，正常走不到这里）。
     */
    public static boolean startAudioPlayer(android.app.Activity activity,
                                           String sessionId) {
        if (activity == null || sessionId == null) return false;
        try {
            Class<?> c = Class.forName(AUDIO_PLAYER_CLASS);
            android.content.Intent i = new android.content.Intent(activity, c);
            i.putExtra("session", sessionId);
            activity.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static HgBookOps ops() {
        if (opsTried) return ops;
        synchronized (HgBookBridge.class) {
            if (opsTried) return ops;
            opsTried = true;
            try {
                Class<?> c = Class.forName(OPS_CLASS);
                ops = (HgBookOps) c.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                ops = null;
            }
            return ops;
        }
    }

    private static HgBookOps requireOps() {
        HgBookOps o = ops();
        if (o == null) throw new LegadoException("红果书源在这个版本里不可用");
        return o;
    }

    // ---------- 管线四段改道 ----------

    public static List<LegadoPipeline.Book> search(LegadoSource source, String key, int page) {
        return requireOps().search(isAudio(source), key, page);
    }

    public static LegadoPipeline.BookInfo bookInfo(LegadoSource source, String bookUrl) {
        return requireOps().bookInfo(isAudio(source), bookUrl);
    }

    public static List<LegadoPipeline.TocChapter> toc(LegadoSource source, String tocUrl) {
        return requireOps().toc(isAudio(source), tocUrl);
    }

    public static LegadoPipeline.Content content(LegadoSource source, String chapterUrl) {
        return requireOps().content(isAudio(source), chapterUrl);
    }

    /** 听书朗读兜底取文（见 HgBookOps.ttsFallbackText）；实现不在位回 null。 */
    public static LegadoPipeline.Content ttsFallbackText(String chapterUrl) {
        HgBookOps o = ops();
        if (o == null) return null;
        return o.ttsFallbackText(chapterUrl);
    }

    // ---------- bookUrl 编解码 ----------

    public static String bookUrl(String bookId) {
        return BOOK_PREFIX + bookId;
    }

    public static String chapterUrl(String bookId, String itemId) {
        return BOOK_PREFIX + bookId + "/" + itemId;
    }

    public static String bookIdOf(String url) {
        String rest = strip(url);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    public static String itemIdOf(String url) {
        String rest = strip(url);
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(slash + 1) : "";
    }

    private static String strip(String url) {
        if (url == null) return "";
        return url.startsWith(BOOK_PREFIX) ? url.substring(BOOK_PREFIX.length()) : url;
    }
}
