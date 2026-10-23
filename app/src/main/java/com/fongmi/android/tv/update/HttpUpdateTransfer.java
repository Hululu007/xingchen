package com.fongmi.android.tv.update;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class HttpUpdateTransfer implements UpdateTransfer {

    private final String url;
    private final File file;
    private final long expectedSize;
    private final String expectedIdentity;
    private volatile boolean canceled;
    private volatile Call call;
    private Future<?> future;

    public HttpUpdateTransfer(String url, File file, long expectedSize, String expectedIdentity) {
        this.url = UpdateUrl.requireHttpsUrl(url);
        this.file = file;
        this.expectedSize = Math.max(0, expectedSize);
        this.expectedIdentity = expectedIdentity;
    }

    @Override
    public void start(Callback callback) {
        canceled = false;
        future = Task.submit(() -> download(callback));
    }

    @Override
    public void cancel() {
        canceled = true;
        if (call != null) call.cancel();
        if (future != null) future.cancel(true);
        // 不删档：已下部分留给下次 Range 续传；好坏最终由 Updater.validate
        // 四重验（大小／sha256／包名／签名）把关，验不过由那边删档。
    }

    // 可续传长度：先过身份门——旁车（UpdateIdentity）对不上本次目标就不认
    // 这半截，只凭长度认档会把旧版整包当成本次前缀续传（新包尾巴拼旧包）
    // 或当成品直接交回。身份过关后再认「清单有总长、本地有半截、半截短于
    // 总长」；总长未知时无法确认半截与本次内容的关系，按 0 处理（落盘时
    // 截断重写）；半截长于总长同样按 0（截断重写）；恰好等于总长时原样
    // 返回，由 download() 直接交回给 validate 定夺，不再重复下载。
    private long resumableLength() {
        if (!file.exists() || expectedSize <= 0) return 0;
        if (!UpdateIdentity.matches(file, expectedIdentity)) return 0;
        long length = file.length();
        return length > expectedSize ? 0 : length;
    }

    private void download(Callback callback) {
        long existing = resumableLength();
        if (expectedSize > 0 && existing == expectedSize) {
            App.post(() -> {
                if (!canceled) callback.success(file);
            });
            call = null;
            future = null;
            return;
        }
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (existing > 0) builder.header("Range", "bytes=" + existing + "-");
        call = UpdateHttp.client().newCall(builder.build());
        try (Response response = call.execute()) {
            int code = response.code();
            if (code == 416 && existing > 0) {
                // 服务器认为这个偏移不可续：半截文件已不是可续传前缀，
                // 它留着只会让每次重试都撞同一处——删档并报错，下次从零下。
                // 这是「校验／协商证明此档不可用才删」的例外，不是失败即删。
                deleteQuietly();
                throw new IOException("Download range not satisfiable");
            }
            if (!response.isSuccessful()) throw new IOException("Download failed: HTTP " + code);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Download failed: empty response");
            long contentLength = body.contentLength();
            boolean append;
            long start;
            long total;
            if (code == 206) {
                String contentRange = response.header("Content-Range");
                long rangeStart = parseRangeStart(contentRange);
                long rangeTotal = parseRangeTotal(contentRange);
                if (rangeStart >= 0 && rangeStart != existing) throw new IOException("Download range mismatch");
                if (rangeTotal > 0 && expectedSize > 0 && rangeTotal != expectedSize) throw new IOException("Download size mismatch");
                if (contentLength > 0 && expectedSize > 0 && existing + contentLength != expectedSize) throw new IOException("Download size mismatch");
                append = existing > 0;
                start = existing;
                total = expectedSize > 0 ? expectedSize : rangeTotal > 0 ? rangeTotal : contentLength > 0 ? existing + contentLength : -1;
            } else {
                // 回 200＝服务器无视 Range 正在发全量（与下载器单线程路同口径）：
                // 截断重写，绝不把全量接在半截文件后面。
                if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) throw new IOException("Download size mismatch");
                append = false;
                start = 0;
                total = expectedSize > 0 ? expectedSize : contentLength;
            }
            stream(body.byteStream(), total, start, append, callback);
            if (canceled) return;
            App.post(() -> {
                if (!canceled) callback.success(file);
            });
        } catch (Exception e) {
            // 不删档：网络失败留下的半截就是下次续传的起点；
            // 若内容本身不对，最后由 validate 的 sha256／签名验出并删档。
            if (canceled || isCanceled(e)) return;
            App.post(() -> callback.error(message(e)));
        } finally {
            call = null;
            future = null;
        }
    }

    private void stream(InputStream source, long total, long start, boolean append, Callback callback) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Unable to create update directory");
        // append=true 接着半截写；false 时 FileOutputStream 本身截断重写，
        // 不走 Path.create（它会先删档，与续传相冲突）。
        try (BufferedInputStream input = new BufferedInputStream(source); FileOutputStream output = new FileOutputStream(file, append)) {
            byte[] buffer = new byte[16384];
            long bytes = start;
            long runStart = System.currentTimeMillis();
            long lastTime = runStart;
            long lastBytes = bytes;
            int lastProgress = -1;
            postProgress(callback, total > 0 ? (int) (bytes * 100 / total) : -1, bytes, total, 0, 0);
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (canceled || Thread.currentThread().isInterrupted()) throw new IOException("Canceled");
                output.write(buffer, 0, count);
                bytes += count;
                if (expectedSize > 0 && bytes > expectedSize) throw new IOException("Download exceeds expected size");
                long now = System.currentTimeMillis();
                int progress = total > 0 ? (int) (bytes * 100 / total) : -1;
                if (progress == lastProgress && now - lastTime < 1000) continue;
                long speed = (bytes - lastBytes) * 1000 / Math.max(1, now - lastTime);
                postProgress(callback, progress, bytes, total, speed, now - runStart);
                lastProgress = progress;
                lastTime = now;
                lastBytes = bytes;
            }
            if (total > 0 && bytes != total) throw new IOException("Download incomplete");
            if (expectedSize > 0 && bytes != expectedSize) throw new IOException("Download size mismatch");
        }
    }

    // Content-Range: bytes <start>-<end>/<total|*>；解析失败返回 -1。
    private long parseRangeStart(String header) {
        if (header == null) return -1;
        String value = header.trim();
        if (value.startsWith("bytes")) value = value.substring(5).trim();
        int dash = value.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Long.parseLong(value.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long parseRangeTotal(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        String tail = header.substring(slash + 1).trim();
        if (tail.isEmpty() || "*".equals(tail)) return -1;
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void deleteQuietly() {
        try {
            // 删失败不静默：留痕到文件日志，否则坏档留盘下次还会撞同一处。
            // 身份旁车一并摘掉（与 Updater 校验失败处的口径对齐）：只删包体
            // 会把孤旁车留在盘上，下次 reconcile 前谁读到都得再猜一遍。
            if (file.exists() && !file.delete()) {
                com.github.catvod.crawler.SpiderDebug.log("update", "http transfer delete failed path=%s", file.getAbsolutePath());
            }
            if (!UpdateIdentity.clearSidecar(file)) {
                com.github.catvod.crawler.SpiderDebug.log("update", "http transfer sidecar delete failed path=%s", file.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
    }

    private void postProgress(Callback callback, int progress, long bytes, long total, long speed, long elapsed) {
        App.post(() -> {
            if (!canceled) callback.progress(progress, bytes, total, speed, elapsed);
        });
    }

    private boolean isCanceled(Exception e) {
        String message = e.getMessage();
        return "Canceled".equals(message) || "Socket closed".equals(message);
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
