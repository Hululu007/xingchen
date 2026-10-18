package com.fongmi.android.tv.read;

import android.app.Activity;
import android.widget.Toast;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.legado.LegadoException;
import com.fongmi.android.tv.legado.LegadoPipeline;
import com.fongmi.android.tv.legado.LegadoReading;
import com.fongmi.android.tv.legado.LegadoSource;
import com.fongmi.android.tv.legado.LegadoStore;
import com.fongmi.android.tv.ui.activity.AudioBookActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 书源搜书 → 打开阅读/听书（阅读首页搜书入口用）。
 * 原 LegadoActivity 单源搜书链的多源版：按类型搜全部已启用书源，结果聚合后进阅读流。
 * 各类型各搜各的，不许混搜——调用方按当前页签传类型。
 */
public final class LegadoSearchFlow {

    private LegadoSearchFlow() {
    }

    /** 一条命中：书＋它来自哪个源。 */
    private static final class Hit {
        final LegadoSource source;
        final LegadoPipeline.Book book;

        Hit(LegadoSource s, LegadoPipeline.Book b) {
            source = s;
            book = b;
        }
    }

    /** 按类型搜全部已启用书源。 */
    public static void search(Activity activity, int bookSourceType, String key) {
        if (key == null || key.isEmpty()) return;
        List<LegadoSource> sources = enabledSources(bookSourceType);
        if (sources.isEmpty()) {
            Toast.makeText(activity, "还没装这种源", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "搜索中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Hit> hits = new ArrayList<>();
            // 哪个源坏了记下来，展示时点名，不再静默吞掉
            List<String> failures = new ArrayList<>();
            for (LegadoSource s : sources) {
                try {
                    List<LegadoPipeline.Book> books = new LegadoPipeline(s).search(key);
                    for (LegadoPipeline.Book b : books) hits.add(new Hit(s, b));
                } catch (Throwable t) {
                    String n = s == null || s.bookSourceName == null ? "" : s.bookSourceName;
                    failures.add(n.isEmpty() ? "未知源" : n);
                }
            }
            activity.runOnUiThread(() -> showHits(activity, hits, failures));
        }).start();
    }

    private static List<LegadoSource> enabledSources(int bookSourceType) {
        List<LegadoSource> out = new ArrayList<>();
        try {
            for (LegadoStore.Entry e : LegadoStore.all()) {
                if (!e.enabled) continue;
                LegadoSource s = LegadoStore.parse(e.json);
                if (s == null) continue;
                if (s.bookSourceType != bookSourceType) continue;
                if (!LegadoSource.runnable(s.bookSourceType)) continue;
                out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 反馈三档（不再是一闪而过的 Toast）：
     * 有结果→弹列表；没结果→明说没搜到；有源报错→点名哪个源坏了（人话一句）。
     */
    private static void showHits(Activity activity, List<Hit> hits, List<String> failures) {
        if (!hits.isEmpty()) {
            String[] names = new String[hits.size()];
            for (int i = 0; i < names.length; i++) {
                Hit h = hits.get(i);
                names[i] = h.book.name
                        + (h.book.author.isEmpty() ? "" : " · " + h.book.author)
                        + "（" + h.source.tag() + "）";
            }
            ReadDialogs.options(activity, "搜索结果（" + names.length + "）", names,
                    which -> {
                        Hit h = hits.get(which);
                        openBook(activity, h.source, h.book);
                    });
            return;
        }
        if (!failures.isEmpty()) {
            String msg = failures.size() == 1
                    ? "「" + failures.get(0) + "」搜书失败，换个源试试。"
                    : failures.size() + " 个源搜书失败（"
                    + android.text.TextUtils.join("、", failures) + "），换个源试试。";
            new ReadDialogs(activity).setTitle("搜书").setMessage(msg)
                    .setPositiveButton("知道了", null).show();
            return;
        }
        new ReadDialogs(activity).setTitle("搜书").setMessage("没有搜到，换个词试试。")
                .setPositiveButton("知道了", null).show();
    }

    /** 拉目录 → 选章/选集 → 进阅读/听书（原 LegadoActivity.doOpenBook 原样搬迁）。 */
    public static void openBook(Activity activity, LegadoSource s, LegadoPipeline.Book book) {
        Toast.makeText(activity, "拉取目录中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                LegadoPipeline p = new LegadoPipeline(s);
                LegadoPipeline.BookInfo info = p.bookInfo(book.bookUrl);
                if (info.name.isEmpty()) info.name = book.name;
                if (info.coverUrl.isEmpty()) info.coverUrl = book.coverUrl;
                String tocUrl = info.tocUrl.isEmpty() ? book.bookUrl : info.tocUrl;
                List<LegadoPipeline.TocChapter> chapters = p.toc(tocUrl);
                if (chapters.isEmpty()) throw new LegadoException("目录为空");
                activity.runOnUiThread(() -> showChapters(activity, s, info, chapters));
            } catch (Throwable t) {
                activity.runOnUiThread(() -> failDialog(activity, "打开失败",
                        "这本书的目录拉不下来。多半是源地址失效或者网络不通。换个源再试试。"
                                + "详细原因已记到日志。", t));
            }
        }).start();
    }

    private static void showChapters(Activity activity, LegadoSource s,
                                     LegadoPipeline.BookInfo info,
                                     List<LegadoPipeline.TocChapter> chapters) {
        String[] names = new String[chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = chapters.get(i).name;
        String unit = s.bookSourceType == LegadoSource.TYPE_AUDIO ? " 集" : " 章";
        ReadDialogs.options(activity, info.name + "（" + names.length + unit + "）", names,
                which -> {
                    if (s.bookSourceType == LegadoSource.TYPE_AUDIO) {
                        // 听书：专辑进听书播放页
                        LegadoAudio.AudioAlbum album =
                                LegadoAudio.buildAlbum(s, info, chapters, which);
                        AudioBookActivity.start(activity, LegadoAudio.put(album));
                    } else {
                        ReadingSession session = LegadoReading.buildSession(s, info, chapters, which);
                        ReadingRouter.openReading(activity, session);
                    }
                });
    }

    private static void failDialog(Activity activity, String title, String body, Throwable t) {
        android.util.Log.e("Legado", title + " raw=" + t, t);
        new ReadDialogs(activity).setTitle(title).setMessage(body)
                .setPositiveButton("知道了", null).show();
    }
}
