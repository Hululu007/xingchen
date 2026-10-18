package com.fongmi.android.tv.read;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.widget.Toast;

import com.fongmi.android.tv.ui.activity.ComicReaderActivity;
import com.fongmi.android.tv.ui.activity.NovelReaderActivity;
import com.fongmi.android.tv.ui.activity.ReadingShelfActivity;

/**
 * 阅读路由：判别层 → 阅读器 / 书架的统一入口。
 * 大数据走 ReadingSession 静态仓，Intent 只带 id。
 */
public class ReadingRouter {

    private ReadingRouter() {
    }

    /** 打开阅读器（漫画/小说按会话 kind 分流）。 */
    public static void openReading(Context context, ReadingSession session) {
        if (context == null || session == null) return;
        String id = ReadingSession.put(session);
        Class<?> cls = session.kind == ReadingSession.Kind.NOVEL
                ? NovelReaderActivity.class : ComicReaderActivity.class;
        Intent intent = new Intent(context, cls);
        intent.putExtra("session", id);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable t) {
            ReadingSession.take(id);
        }
    }

    /** 打开书架。 */
    public static void openShelf(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, ReadingShelfActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 继续读：最近一本。记录里带章节地址，重建最小会话；
     * 章节表缺失时阅读器按单章打开（切章按钮收起），不崩。
     */
    public static void continueReading(Context context) {
        if (context == null) return;
        ReadingStore.Record r = ReadingStore.latest();
        if (r == null || TextUtils.isEmpty(r.bookTitle)) {
            Toast.makeText(context, "书架是空的，先去看一本吧", Toast.LENGTH_SHORT).show();
            openShelf(context);
            return;
        }
        ReadingSession s = new ReadingSession();
        s.kind = "NOVEL".equals(r.kind) ? ReadingSession.Kind.NOVEL : ReadingSession.Kind.COMIC;
        s.sourceName = r.sourceName;
        s.bookTitle = r.bookTitle;
        s.coverUrl = r.coverUrl;
        s.chapterIndex = 0;
        ReadingSession.Chapter c = new ReadingSession.Chapter(r.chapterName, r.chapterUrl);
        s.chapters.add(c);
        openReading(context, s);
    }
}
