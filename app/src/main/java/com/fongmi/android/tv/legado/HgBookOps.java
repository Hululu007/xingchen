package com.fongmi.android.tv.legado;

import java.util.List;

/**
 * 红果书源的执行面（实现类在红果包内、运行时按类名装配，见 HgBookBridge）：
 * 把红果小说/听书映射成阅读管线的四段（搜索/详情/目录/正文）。
 * 红果整套在朋友版里剥离，所以这里只留接口，实现缺席时桥会给出人话错误。
 */
public interface HgBookOps {

    List<LegadoPipeline.Book> search(boolean audio, String key, int page);

    LegadoPipeline.BookInfo bookInfo(boolean audio, String bookUrl);

    List<LegadoPipeline.TocChapter> toc(boolean audio, String tocUrl);

    LegadoPipeline.Content content(boolean audio, String chapterUrl);
}
