package com.fongmi.android.tv.tts;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 朗读合成引擎（后台单线程）：
 * 按 (章, 段) 队列逐段合成 mp3 落缓存，当前段优先、后段预取。
 * 回调跑在 worker 线程，调用方自行切回主线程。
 * 失败：单段重试一次，再败即报人话错误并停工（不许回退系统机械声）。
 */
public class TtsEngine {

    /** 章正文提供方（调方实现，可阻塞）。 */
    public interface TextProvider {
        String textOf(int chapterIndex);
    }

    public interface Listener {
        void onSegmentReady(int chapterIndex, int segIndex, File file);

        void onChapterEmpty(int chapterIndex);

        void onTtsError(String humanMsg);
    }

    private static class Need {
        final int chapter;
        final int seg;

        Need(int c, int s) {
            chapter = c;
            seg = s;
        }

        String key() {
            return chapter + ":" + seg;
        }
    }

    private final File root;
    private final TextProvider provider;
    private final Listener listener;
    private final Deque<Need> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<Integer, List<String>> segCache = new HashMap<>();
    private final Map<Integer, String> textCache = new HashMap<>();

    private volatile String voiceId;
    private volatile float rate;
    private volatile boolean running = true;
    private volatile boolean errorSent;
    private final Thread worker;

    public TtsEngine(Context context, String bookKey, TextProvider provider, Listener listener) {
        this.provider = provider;
        this.listener = listener;
        this.voiceId = EdgeVoiceCatalog.defaultVoice();
        this.rate = 1.0f;
        String safe = bookKey.replaceAll("[^a-zA-Z0-9]", "_");
        if (safe.length() > 48) safe = safe.substring(0, 48);
        this.root = new File(new File(context.getCacheDir(), "tts"), safe);
        purgeOld(new File(context.getCacheDir(), "tts"));
        worker = new Thread(this::loop, "tts-engine");
        worker.start();
    }

    public void setVoice(String voiceId, float rate) {
        if (voiceId != null) this.voiceId = voiceId;
        this.rate = rate;
    }

    public String voiceId() {
        return voiceId;
    }

    public float rate() {
        return rate;
    }

    /** 请求一段：本段优先插队，后段自动预取。 */
    public void request(int chapterIndex, int segIndex) {
        synchronized (queue) {
            Need n = new Need(chapterIndex, segIndex);
            if (!queued.contains(n.key())) {
                queue.addFirst(n);
                queued.add(n.key());
                queue.notify();
            }
        }
    }

    /** 该章的段表（确定性切分；空章返回空表）。 */
    public List<String> segmentsOf(int chapterIndex) {
        synchronized (segCache) {
            List<String> segs = segCache.get(chapterIndex);
            if (segs == null) {
                String text = textOf(chapterIndex);
                segs = TtsSegmenter.split(text);
                if (segCache.size() > 8) segCache.clear();
                segCache.put(chapterIndex, segs);
            }
            return segs;
        }
    }

    private String textOf(int chapterIndex) {
        synchronized (textCache) {
            String t = textCache.get(chapterIndex);
            if (t == null) {
                try {
                    t = provider.textOf(chapterIndex);
                } catch (Throwable ignored) {
                    t = "";
                }
                if (t == null) t = "";
                if (textCache.size() > 8) textCache.clear();
                textCache.put(chapterIndex, t);
            }
            return t;
        }
    }

    private void loop() {
        while (running) {
            Need n;
            synchronized (queue) {
                while (queue.isEmpty() && running) {
                    try {
                        queue.wait();
                    } catch (InterruptedException ignored) {
                    }
                }
                if (!running) return;
                n = queue.pollFirst();
                if (n != null) queued.remove(n.key());
            }
            if (n != null) process(n);
        }
    }

    private void process(Need n) {
        List<String> segs = segmentsOf(n.chapter);
        if (segs.isEmpty()) {
            listener.onChapterEmpty(n.chapter);
            return;
        }
        if (n.seg < 0 || n.seg >= segs.size()) return;
        File f = cacheFile(n.chapter, n.seg);
        if (!f.exists()) {
            if (!synthesize(n, segs.get(n.seg))) return; // 已报错停工
        }
        listener.onSegmentReady(n.chapter, n.seg, f);
        // 预取：本章后两段
        prefetch(n.chapter, n.seg + 1);
        prefetch(n.chapter, n.seg + 2);
        // 章尾：下章前两段
        if (n.seg + 2 >= segs.size()) {
            prefetch(n.chapter + 1, 0);
            prefetch(n.chapter + 1, 1);
        }
    }

    private void prefetch(int chapter, int seg) {
        if (chapter < 0 || seg < 0) return;
        synchronized (queue) {
            Need n = new Need(chapter, seg);
            if (!queued.contains(n.key())) {
                queue.addLast(n);
                queued.add(n.key());
                queue.notify();
            }
        }
    }

    private boolean synthesize(Need n, String text) {
        String v = voiceId;
        float r = rate;
        for (int attempt = 0; attempt < 2 && running; attempt++) {
            try {
                byte[] mp3 = new EdgeTtsClient().synthesize(text, v, r);
                File f = cacheFile(n.chapter, n.seg);
                f.getParentFile().mkdirs();
                File tmp = new File(f.getParent(), f.getName() + ".part");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(mp3);
                }
                if (!tmp.renameTo(f)) {
                    tmp.delete();
                    throw new EdgeTtsClient.TtsException(EdgeTtsClient.ERR_OFFLINE);
                }
                return true;
            } catch (EdgeTtsClient.TtsException e) {
                if (attempt == 1) {
                    fail(e.getMessage());
                    return false;
                }
            } catch (Throwable t) {
                if (attempt == 1) {
                    fail(EdgeTtsClient.ERR_OFFLINE);
                    return false;
                }
            }
        }
        return false;
    }

    private void fail(String msg) {
        if (!errorSent) {
            errorSent = true;
            listener.onTtsError(msg == null ? EdgeTtsClient.ERR_OFFLINE : msg);
        }
        running = false;
        synchronized (queue) {
            queue.notify();
        }
    }

    private File cacheFile(int chapter, int seg) {
        String v = voiceId.replaceAll("[^a-zA-Z0-9]", "_");
        String rateTag = EdgeTtsClient.rateTag(rate).replace("+", "p").replace("%", "").replace("-", "m");
        File dir = new File(root, v + "_" + rateTag);
        return new File(dir, "ch" + chapter + "_seg" + seg + ".mp3");
    }

    /** 清 3 天前的旧缓存（有界）。 */
    private void purgeOld(File ttsRoot) {
        try {
            if (!ttsRoot.exists()) return;
            long cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            List<File> all = new ArrayList<>();
            collect(ttsRoot, all);
            long total = 0;
            for (File f : all) total += f.length();
            // 超 150MB 或超期都清（旧的先清）
            all.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : all) {
                if (f.lastModified() < cutoff || total > 150L * 1024 * 1024) {
                    total -= f.length();
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".mp3")) out.add(f);
        }
    }

    public void shutdown() {
        running = false;
        synchronized (queue) {
            queue.notify();
        }
    }
}
