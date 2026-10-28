package com.fongmi.android.tv.hongguo.book;

import com.fongmi.android.tv.legado.HgBookBridge;
import com.fongmi.android.tv.legado.HgBookOps;
import com.fongmi.android.tv.legado.LegadoException;
import com.fongmi.android.tv.legado.LegadoPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果书源执行实现（红果包内）：把 HgBookClient 的书数据映射成阅读管线四段。
 * 小说正文走加密链（HgNovelCrypto 在客户端内处理）；听书的音频是 CENC 加密、
 * 内容密钥解包还没破（见 dig-audio-novel-source §1.4），所以听书正文段直接给
 * 人话错误——浏览/挑书/目录都能用，点播先明确告诉他播不了，不许假装能播。
 */
public final class HgBookOpsImpl implements HgBookOps {

    private final HgBookClient client = new HgBookClient();

    @Override
    public List<LegadoPipeline.Book> search(boolean audio, String key, int page) {
        try {
            int offset = Math.max(0, page - 1) * 20;
            List<HgBookClient.Entry> entries = client.search(audio, key, offset);
            List<LegadoPipeline.Book> out = new ArrayList<>();
            for (HgBookClient.Entry e : entries) {
                LegadoPipeline.Book b = new LegadoPipeline.Book();
                b.name = e.title;
                b.author = e.author;
                b.coverUrl = e.coverUrl;
                b.bookUrl = HgBookBridge.bookUrl(e.bookId);
                out.add(b);
            }
            return out;
        } catch (LegadoException e) {
            throw e;
        } catch (Exception e) {
            throw new LegadoException("红果搜索失败：" + msg(e));
        }
    }

    @Override
    public LegadoPipeline.BookInfo bookInfo(boolean audio, String bookUrl) {
        try {
            String bookId = HgBookBridge.bookIdOf(bookUrl);
            HgBookClient.Entry e = client.detail(bookId);
            if (e.title.isEmpty()) {
                HgBookClient.Entry fromToc = client.tocBookInfo(bookId);
                if (fromToc != null) e = fromToc;
            }
            LegadoPipeline.BookInfo info = new LegadoPipeline.BookInfo();
            info.bookUrl = bookUrl;
            info.name = e.title;
            info.author = e.author;
            info.coverUrl = e.coverUrl;
            info.intro = e.intro;
            info.tocUrl = bookUrl;
            return info;
        } catch (LegadoException e) {
            throw e;
        } catch (Exception e) {
            throw new LegadoException("红果书籍详情失败：" + msg(e));
        }
    }

    @Override
    public List<LegadoPipeline.TocChapter> toc(boolean audio, String tocUrl) {
        try {
            String bookId = HgBookBridge.bookIdOf(tocUrl);
            List<HgBookClient.Chapter> chapters = client.toc(bookId);
            List<LegadoPipeline.TocChapter> out = new ArrayList<>();
            for (HgBookClient.Chapter c : chapters) {
                LegadoPipeline.TocChapter t = new LegadoPipeline.TocChapter();
                t.name = c.title;
                t.url = HgBookBridge.chapterUrl(bookId, c.itemId);
                out.add(t);
            }
            return out;
        } catch (LegadoException e) {
            throw e;
        } catch (Exception e) {
            throw new LegadoException("红果目录失败：" + msg(e));
        }
    }

    @Override
    public LegadoPipeline.Content content(boolean audio, String chapterUrl) {
        if (audio) {
            throw new LegadoException("红果听书的音频是加密的，内容密钥还没破解，"
                    + "暂时播不了；挑书和目录可以正常看");
        }
        try {
            String bookId = HgBookBridge.bookIdOf(chapterUrl);
            String itemId = HgBookBridge.itemIdOf(chapterUrl);
            String[] r = client.chapterText(bookId, itemId);
            LegadoPipeline.Content c = new LegadoPipeline.Content();
            c.title = r[0];
            c.text = r[1];
            return c;
        } catch (LegadoException e) {
            throw e;
        } catch (Exception e) {
            throw new LegadoException("红果正文失败：" + msg(e));
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "网络不通或接口异常" : m;
    }
}
