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

    /** 打开阅读器并指定起始页（漫画第几张图）。 */
    public static void openReadingWithStartPage(Context context, ReadingSession session, int startPage) {
        if (session == null) return;
        session.startPage = Math.max(0, startPage);
        openReading(context, session);
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
     * 按书架记录重建会话并打开。章节表缺失时单章模式（切章按钮收起），不崩。
     * 海阔：阅读器按规则/章节地址懒取；py 漫画：章节地址即图片串现场拆；
     * py 小说：用记录里的正文快照。
     */
    public static void openRecord(Context context, ReadingStore.Record r) {
        if (context == null || r == null || TextUtils.isEmpty(r.bookTitle)) return;
        ReadingSession s = new ReadingSession();
        s.kind = "NOVEL".equals(r.kind) ? ReadingSession.Kind.NOVEL : ReadingSession.Kind.COMIC;
        s.sourceName = r.sourceName == null ? "" : r.sourceName;
        s.bookTitle = r.bookTitle;
        s.coverUrl = r.coverUrl == null ? "" : r.coverUrl;
        s.chapterIndex = 0;
        ReadingSession.Chapter c = new ReadingSession.Chapter(
                r.chapterName == null ? "" : r.chapterName,
                r.chapterUrl == null ? "" : r.chapterUrl);
        s.chapters.add(c);
        s.chapterUrl = c.url;
        s.siteKey = r.siteKey == null ? "" : r.siteKey;
        s.legadoKey = r.legadoKey == null ? "" : r.legadoKey;
        // 海阔记录的 sourceName 即规则名；py 源不走规则（ruleTitle 置空防误查）
        s.ruleTitle = TextUtils.isEmpty(s.siteKey) ? s.sourceName : "";
        // Legado 书源走 legadoKey 懒取，不许误进海阔规则链
        if (!TextUtils.isEmpty(s.legadoKey)) s.ruleTitle = "";
        if (s.kind == ReadingSession.Kind.NOVEL && !TextUtils.isEmpty(r.content)) {
            s.text = r.content;
        }
        if (s.kind == ReadingSession.Kind.COMIC && r.position > 0) {
            openReadingWithStartPage(context, s, r.position);
        } else {
            openReading(context, s);
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
        openRecord(context, r);
    }
}
