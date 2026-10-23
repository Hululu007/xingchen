package com.fongmi.android.tv.utils.download;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Request;
import okhttp3.Response;

/**
 * HTTP 多线程下载器，支持 Range 分块 + 断点续传
 */
public class HttpDownloader {

    public interface Listener {
        void onProgress(long downloaded, long total);
        void onComplete(File file);
        void onError(String msg);
    }

    private final String url;
    private final File targetFile;
    private final Map<String, String> headers;
    private final int threadCount;
    private final Listener listener;
    private volatile boolean canceled;
    private ExecutorService executor;
    // C-25: 工作线程收尾闩。doDownload 返回（各子路径的文件句柄已在各自
    // try-with-resources 里关掉）后才放下，DownloadManager 靠它确认旧线程
    // 真的停笔，才允许 resume 新建下载器写同一文件。
    private final CountDownLatch finishedLatch = new CountDownLatch(1);
    private volatile Thread workerThread;

    public HttpDownloader(String url, File targetFile, Map<String, String> headers, int threadCount, Listener listener) {
        this.url = url;
        this.targetFile = targetFile;
        this.headers = headers != null ? headers : new HashMap<>();
        this.threadCount = Math.max(1, Math.min(32, threadCount));
        this.listener = listener;
    }

    public void cancel() {
        canceled = true;
        if (executor != null) executor.shutdownNow();
        OkHttp.cancel(url);
    }

    /** C-25: 等工作线程收尾（含文件句柄关闭）；超时返回 false，不无限等。 */
    public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException {
        return finishedLatch.await(timeout, unit);
    }

    /** C-25: 工作线程是否已经收尾。 */
    public boolean isFinished() {
        return finishedLatch.getCount() == 0;
    }

    public void start() {
        Thread t = new Thread(() -> {
            try {
                doDownload();
            } finally {
                finishedLatch.countDown();
            }
        });
        workerThread = t;
        t.start();
    }

    /**
     * C-17: 落笔前空间预检。总长已知时按「最终文件大小 − 已在盘上的部分」
     * 算还需的新增空间（续传/预分配同文件不重复占），盘可用量查不到时不拦
     * （避免误杀），查到了且不够就抛中文错，走 doDownload 既有 onError 路，
     * 不许写到一半才炸。
     */
    private void ensureSpaceFor(long finalLength) throws IOException {
        if (finalLength <= 0) return;
        long existing = targetFile.exists() ? targetFile.length() : 0;
        long additional = finalLength - existing;
        if (additional <= 0) return;
        FileUtil.StorageSpace space = FileUtil.getStorageSpace(targetFile);
        if (!space.available()) return;
        if (space.availableBytes() < additional) {
            throw new IOException("存储空间不足：还需约 " + DownloadTask.formatSize(additional)
                    + "，当前可用仅 " + DownloadTask.formatSize(space.availableBytes())
                    + "，请清理空间后重试");
        }
    }

    private void doDownload() {
        try {
            // 1. 获取文件长度，检查是否支持 Range
            long totalLength = getContentLength();
            // C-17: 总长已知先预检空间，不足一开始就报错，不再往下探测/落笔
            ensureSpaceFor(totalLength);
            boolean supportRange = checkRangeSupport();
            if (totalLength <= 0 || !supportRange || threadCount == 1) {
                // 不支持分块，单线程下载
                singleThreadDownload(totalLength);
                return;
            }
            // 2. 多线程分块下载
            multiThreadDownload(totalLength);
        } catch (Exception e) {
            if (!canceled && listener != null) {
                App.post(() -> listener.onError(e.getMessage()));
            }
        }
    }

    private long getContentLength() throws IOException {
        Request.Builder builder = new Request.Builder().url(url).head();
        for (Map.Entry<String, String> e : headers.entrySet()) builder.addHeader(e.getKey(), e.getValue());
        try (Response res = OkHttp.client().newCall(builder.build()).execute()) {
            String len = res.header("Content-Length");
            if (len != null) return Long.parseLong(len);
        } catch (Exception ignored) {}
        // HEAD 失败时用 GET 的 Range 0-0 探测
        Map<String, String> h = new HashMap<>(headers);
        h.put("Range", "bytes=0-0");
        Request.Builder b2 = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> e : h.entrySet()) b2.addHeader(e.getKey(), e.getValue());
        try (Response res = OkHttp.client().newCall(b2.build()).execute()) {
            String cr = res.header("Content-Range");
            if (cr != null && cr.contains("/")) {
                return Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private boolean checkRangeSupport() throws IOException {
        Map<String, String> h = new HashMap<>(headers);
        h.put("Range", "bytes=0-0");
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> e : h.entrySet()) builder.addHeader(e.getKey(), e.getValue());
        try (Response res = OkHttp.client().newCall(builder.build()).execute()) {
            return res.code() == 206;
        }
    }

    private void singleThreadDownload(long totalLength) throws IOException {
        // 断点续传：已下载部分继续
        long downloaded = 0;
        if (targetFile.exists()) downloaded = targetFile.length();
        Map<String, String> h = new HashMap<>(headers);
        boolean sentRange = false;
        if (downloaded > 0 && totalLength > downloaded) {
            h.put("Range", "bytes=" + downloaded + "-");
            sentRange = true;
        } else {
            downloaded = 0;
            if (targetFile.exists()) targetFile.delete();
        }
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> e : h.entrySet()) builder.addHeader(e.getKey(), e.getValue());
        try (Response res = OkHttp.client().newCall(builder.build()).execute()) {
            if (!res.isSuccessful() && res.code() != 206) throw new IOException("HTTP " + res.code());
            if (res.body() == null) throw new IOException("empty body");
            // C-22: 发了 Range 但服务器回 200 = 不支持续传、正在发全量，
            // 不能把全量接在半截文件后面，必须截断从 0 重写。
            boolean rangeIgnored = sentRange && res.code() == 200;
            long responseLength = -1;
            String cl = res.header("Content-Length");
            if (cl != null) {
                try {
                    responseLength = Long.parseLong(cl.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            // C-17: 总长探测未知时用本次响应推最终大小，打开文件落笔前再预检一次；
            // 在 RandomAccessFile 打开/截断之前抛错，已下的半截文件不被破坏。
            long finalExpected;
            if (rangeIgnored) {
                finalExpected = responseLength > 0 ? responseLength : totalLength;
            } else if (totalLength > 0) {
                finalExpected = totalLength;
            } else if (responseLength > 0) {
                finalExpected = sentRange ? downloaded + responseLength : responseLength;
            } else {
                finalExpected = -1;
            }
            ensureSpaceFor(finalExpected);
            long total;
            try (InputStream is = res.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                if (rangeIgnored) {
                    raf.setLength(0);
                    raf.seek(0);
                    downloaded = 0;
                } else {
                    raf.seek(downloaded);
                }
                byte[] buf = new byte[32768];
                int len;
                total = downloaded;
                while ((len = is.read(buf)) != -1) {
                    if (canceled) return;
                    raf.write(buf, 0, len);
                    total += len;
                    final long d = total;
                    // 回 200 全量时总量以本次响应为准；206 续传时以探测到的总长为准
                    final long t = rangeIgnored
                            ? (responseLength > 0 ? responseLength : totalLength)
                            : (totalLength > 0 ? totalLength : (responseLength > 0 ? downloaded + responseLength : -1));
                    if (listener != null) App.post(() -> { if (!canceled) listener.onProgress(d, t); });
                }
            }
            if (canceled) return;
            // 写完核对最终长度：Range 被无视时期望 = 本次全量长度；
            // 续传 206 / 普通 200 时期望 = 已知总长（未知则用本次响应长度推导）。
            long expected = -1;
            if (rangeIgnored) {
                expected = responseLength > 0 ? responseLength : totalLength;
            } else if (totalLength > 0) {
                expected = totalLength;
            } else if (responseLength > 0) {
                expected = downloaded + responseLength;
                // 未发 Range 时 downloaded 已归零，上式即 responseLength
                if (!sentRange) expected = responseLength;
            }
            if (expected > 0) {
                long actual = targetFile.exists() ? targetFile.length() : -1;
                if (actual != expected) {
                    throw new IOException("下载长度校验失败: 期望 " + expected + " 字节, 实际 " + actual + " 字节");
                }
            }
            if (listener != null) {
                App.post(() -> listener.onComplete(targetFile));
            }
        }
    }

    private void multiThreadDownload(long totalLength) throws IOException {
        // C-17: 预分配 setLength 前同样先预检空间，不足直接走 onError
        ensureSpaceFor(totalLength);
        // 预分配文件
        try (RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
            raf.setLength(totalLength);
        }
        // 断点续传：读取已完成的分块记录
        File progressFile = new File(targetFile.getAbsolutePath() + ".progress");
        boolean[] blockDone = new boolean[threadCount];
        // 简化：暂不支持跨进程断点，只支持本次会话内取消后重下
        // 实际断点续传靠单线程模式的 Range 续传；多线程模式每次重新分块
        long blockSize = totalLength / threadCount;
        AtomicLong totalDownloaded = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> errors = new ArrayList<>();
        // C-21: 每块实收字节数，latch 齐后逐块与期望块长核对
        final long[] blockReceived = new long[threadCount];
        final long[] blockExpected = new long[threadCount];
        executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            final long start = index * blockSize;
            final long end = (index == threadCount - 1) ? totalLength - 1 : (index + 1) * blockSize - 1;
            // 末块长度：end 已按 totalLength-1 收口，块长统一为 end-start+1，
            // 等价于 totalLength - start（非末块为 blockSize），不会多算/漏算取模余数
            blockExpected[index] = (end >= start) ? (end - start + 1) : 0;
            executor.submit(() -> {
                try {
                    // 块长为 0 的空块（文件比线程数还小时出现）不发请求，实收记 0
                    blockReceived[index] = blockExpected[index] == 0 ? 0
                            : downloadBlock(start, end, totalDownloaded, totalLength);
                } catch (Exception e) {
                    synchronized (errors) {
                        if (errors.isEmpty()) errors.add(e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(24, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();
        if (canceled) return;
        if (!errors.isEmpty()) {
            throw new IOException(errors.get(0));
        }
        // C-21: 逐块核对实收 == 块长；短响应留下的预分配零洞不许报成功
        for (int i = 0; i < threadCount; i++) {
            if (blockReceived[i] != blockExpected[i]) {
                throw new IOException("分块下载长度校验失败: 第 " + i + " 块期望 " + blockExpected[i]
                        + " 字节, 实际 " + blockReceived[i] + " 字节");
            }
        }
        long actualLength = targetFile.exists() ? targetFile.length() : -1;
        if (actualLength != totalLength) {
            throw new IOException("下载长度校验失败: 期望 " + totalLength + " 字节, 实际 " + actualLength + " 字节");
        }
        if (progressFile.exists()) progressFile.delete();
        if (listener != null) App.post(() -> listener.onComplete(targetFile));
    }

    /** @return 本块实际收到并写入的字节数（取消时返回已收部分，调用方在 canceled 时不做校验） */
    private long downloadBlock(long start, long end, AtomicLong totalDownloaded, long totalLength) throws IOException {
        Map<String, String> h = new HashMap<>(headers);
        h.put("Range", "bytes=" + start + "-" + end);
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> e : h.entrySet()) builder.addHeader(e.getKey(), e.getValue());
        long received = 0;
        try (Response res = OkHttp.client().newCall(builder.build()).execute()) {
            if (res.code() != 206) throw new IOException("Range not supported, HTTP " + res.code());
            if (res.body() == null) throw new IOException("empty body");
            try (InputStream is = res.body().byteStream();
                 RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                raf.seek(start);
                byte[] buf = new byte[32768];
                int len;
                while ((len = is.read(buf)) != -1) {
                    if (canceled) return received;
                    raf.write(buf, 0, len);
                    received += len;
                    long d = totalDownloaded.addAndGet(len);
                    if (listener != null) App.post(() -> { if (!canceled) listener.onProgress(d, totalLength); });
                }
            }
        }
        return received;
    }
}
