package com.fongmi.android.tv.hongguo.drama;

/**
 * 搜索结果一行（按型分形，批三 C 卡 18）：官方综合签是服务端原序混排
 * 下发、多型同列（普查分册二卡 2）——剧集卡（video_data）／视频嵌套卡
 * （video_detail_list）／小说卡（book_data）／用户卡（celebrity_user_list）
 * ／帖卡（post_data）。此前解析层只有 DramaItem 一型：用户成员整条
 * 丢弃、帖子被压成 id＝"0" 的假剧条目（book_id＝0 冒充剧 id，存档 tab11
 * 实跑坐实）、小说被压成剧条目点进集流死路。本类把成员按型收齐、保回包
 * 原序，渲染与落点在 HongguoSearchActivity 按 kind 分发。
 *
 * <p>落点口径：SERIES 沿现有剧/合集路；USER 进作者页；POST 进帖子页
 * （HongguoPostActivity，料自带）；BOOK 只出卡——红果面没有小说阅读链，
 * 落点承接差记在批三 C 账里，不编造阅读链。</p>
 */
public final class DramaSearchRow {

    public static final int KIND_SERIES = 1;
    public static final int KIND_USER = 2;
    public static final int KIND_POST = 3;
    public static final int KIND_BOOK = 4;

    public final int kind;
    /** KIND_SERIES 的条目；其余型为 null。 */
    public final DramaItem series;
    /** KIND_USER 的账号命中；其余型为 null。 */
    public final DramaAuthorHit user;
    /** KIND_POST 的帖子条目（复用帖子页的入参形态）；其余型为 null。 */
    public final DramaWork post;
    /** KIND_POST 的作者 uid（post_data.user_info.user_id，帖子页作者行
     * 落点用）；其余型为空串。 */
    public final String postAuthorUid;
    /** KIND_BOOK 的小说 id / 书名 / 封面 / 简介 / 连载数 / 作者（book_data
     * 字段集，协议原件 catalogBook 逐字：book_id/book_name/thumb_url/
     * abstract/serial_count；author 为存档真回包实有键、协议原件未收）；
     * 其余型为空串/0。 */
    public final String bookId;
    public final String bookTitle;
    public final String bookCover;
    public final String bookAbstract;
    public final int bookSerialCount;
    public final String bookAuthor;

    private DramaSearchRow(int kind, DramaItem series, DramaAuthorHit user, DramaWork post,
                        String bookId, String bookTitle, String bookCover,
                        String bookAbstract, int bookSerialCount) {
        this(kind, series, user, post, bookId, bookTitle, bookCover, bookAbstract,
                bookSerialCount, "", null);
    }

    private DramaSearchRow(int kind, DramaItem series, DramaAuthorHit user, DramaWork post,
                        String bookId, String bookTitle, String bookCover,
                        String bookAbstract, int bookSerialCount, String postAuthorUid) {
        this(kind, series, user, post, bookId, bookTitle, bookCover, bookAbstract,
                bookSerialCount, postAuthorUid, null);
    }

    private DramaSearchRow(int kind, DramaItem series, DramaAuthorHit user, DramaWork post,
                        String bookId, String bookTitle, String bookCover,
                        String bookAbstract, int bookSerialCount, String postAuthorUid,
                        String bookAuthor) {
        this.kind = kind;
        this.series = series;
        this.user = user;
        this.post = post;
        this.postAuthorUid = postAuthorUid == null ? "" : postAuthorUid;
        this.bookId = bookId == null ? "" : bookId;
        this.bookTitle = bookTitle == null ? "" : bookTitle;
        this.bookCover = bookCover == null ? "" : bookCover;
        this.bookAbstract = bookAbstract == null ? "" : bookAbstract;
        this.bookSerialCount = bookSerialCount;
        this.bookAuthor = bookAuthor == null ? "" : bookAuthor;
    }

    public static DramaSearchRow ofSeries(DramaItem item) {
        return new DramaSearchRow(KIND_SERIES, item, null, null, null, null, null, null, 0);
    }

    public static DramaSearchRow ofUser(DramaAuthorHit hit) {
        return new DramaSearchRow(KIND_USER, null, hit, null, null, null, null, null, 0);
    }

    public static DramaSearchRow ofPost(DramaWork work) {
        return ofPost(work, "");
    }

    public static DramaSearchRow ofPost(DramaWork work, String authorUid) {
        return new DramaSearchRow(KIND_POST, null, null, work, null, null, null, null, 0,
                authorUid);
    }

    public static DramaSearchRow ofBook(String id, String title, String cover,
                                     String abs, int serialCount) {
        return ofBook(id, title, cover, abs, serialCount, null);
    }

    public static DramaSearchRow ofBook(String id, String title, String cover,
                                     String abs, int serialCount, String author) {
        return new DramaSearchRow(KIND_BOOK, null, null, null, id, title, cover, abs,
                serialCount, "", author);
    }

    /** 探针/日志用的一行摘要。 */
    public String label() {
        switch (kind) {
            case KIND_SERIES:
                return series == null ? "" : series.getTitle();
            case KIND_USER:
                return user == null ? "" : user.name;
            case KIND_POST:
                return post == null ? "" : post.title;
            case KIND_BOOK:
                return bookTitle;
            default:
                return "";
        }
    }
}
