package com.fongmi.android.tv.ktv;

import com.github.catvod.crawler.SpiderDebug;

/**
 * KTV 关键节点留痕：统一写进应用的调试日志（设置里导出的那份），
 * 配 MV/配伴奏/消音出问题时一次就能定位卡在哪。
 */
public class KtvLog {

    public static void add(String msg) {
        try {
            SpiderDebug.log("ktv: " + msg);
        } catch (Throwable ignored) {
        }
    }
}
