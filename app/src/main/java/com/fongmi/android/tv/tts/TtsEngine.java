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
 * 按 (章, 段) 队列逐段合成音频落缓存，当前段优先、后段预取。
 * 回调跑在 worker 线程，调用方自行切回主线程。
 * 供应商两家（他 2026-10-27 令「不许只押一家」）：Edge 神经音色优先，
 * 失败即切系统 TTS 兜底（本会话内保持系统声、不来回跳音色），
 * 两家全败才报人话错误并停工。引擎模式可在设置里选（自动/Edge/系统）。
 */
public class TtsEngine {

    public static final int MODE_AUTO = 0;
    public static final int MODE_EDGE = 1;
    public static final int MODE_SYSTEM = 2;

    /** 章正文提供方（调方实现，可阻塞）。 */
    public interface TextProvider {
        String textOf(int chapterIndex);
    }

    public interface Listener {
        void onSegmentReady(int chapterIndex, int segIndex, File file);

        void onChapterEmpty(int chapterIndex);

        void onTtsError(String humanMsg);

        /** Edge 失败、已切系统语音兜底（每次会话至多报一次，供页面提示）。 */
        default void onFallbackToSystem() {
        }
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
    private final android.content.Context appContext;
    private final TextProvider provider;
    private final Listener listener;
    private SystemTtsSynth systemSynth;
    private volatile boolean edgeDown;
    private volatile int engineMode = MODE_AUTO;
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
        this.appContext = context.getApplicationContext();
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

    /** 引擎模式（自动/Edge/系统）：页面设置项调；切模式后由页面重备当前段。 */
    public void setEngineMode(int mode) {
        this.engineMode = mode;
        if (mode == MODE_EDGE) edgeDown = false;
    }

    public int engineMode() {
        return engineMode;
    }

    /** 当前实际在用系统语音（模式=系统，或自动模式已兜底）。 */
    public boolean usingSystem() {
        return engineMode == MODE_SYSTEM || edgeDown;
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
        File f = fileFor(n.chapter, n.seg);
        if (f == null) {
            if (!synthesize(n, segs.get(n.seg))) return; // 已报错停工
            f = fileFor(n.chapter, n.seg);
            if (f == null) return;
        }
        // 已 shutdown 就不再回调/预取：在途段是 shutdown 前起的活，
        // 合成返回时页面多半已退出，回调摸已销毁宿主没有意义。
        if (!running) return;
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

    /**
     * 合成一段：按引擎模式走 Edge / 系统 / 自动（Edge 先行、败了切系统并
     * 在本会话内钉住系统声）。Edge 只试一次（12 秒快收），不静默重试——
     * 干挂重试正是 2026-10-28 定根的病象。两家全败才 fail()。
     */
    private boolean synthesize(Need n, String text) {
        boolean wantSystemOnly = engineMode == MODE_SYSTEM;
        if (!wantSystemOnly && !edgeDown) {
            String edgeErr = null;
            try {
                byte[] mp3 = new EdgeTtsClient().synthesize(text, voiceId, rate);
                File f = edgeFile(n.chapter, n.seg);
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
                edgeErr = e.getMessage();
            } catch (Throwable t) {
                edgeErr = EdgeTtsClient.ERR_OFFLINE;
            }
            if (engineMode == MODE_EDGE) {
                fail(edgeErr);
                return false;
            }
            // 自动模式：钉住系统声并报一次，剩下段不再轮番试 Edge
            edgeDown = true;
            try {
                listener.onFallbackToSystem();
            } catch (Throwable ignored) {
            }
        }
        // 系统语音（模式=系统 / 自动兜底）
        File sf = sysFile(n.chapter, n.seg);
        File tmp = new File(sf.getParent(), sf.getName() + ".part.wav");
        boolean ok = false;
        try {
            if (systemSynth == null) systemSynth = new SystemTtsSynth(appContext);
            ok = systemSynth.synthesize(text, rate, tmp);
        } catch (Throwable ignored) {
            ok = false;
        }
        if (ok) {
            try {
                sf.getParentFile().mkdirs();
                if (sf.exists()) sf.delete();
                if (tmp.renameTo(sf)) return true;
                tmp.delete();
            } catch (Throwable ignored) {
            }
        } else {
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
        }
        fail(engineMode == MODE_SYSTEM
                ? "系统语音不可用，请检查系统朗读设置后重试"
                : "朗读服务连不上，系统语音也不可用，请检查网络后重试");
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

    private File edgeFile(int chapter, int seg) {
        String v = voiceId.replaceAll("[^a-zA-Z0-9]", "_");
        String rateTag = EdgeTtsClient.rateTag(rate).replace("+", "p").replace("%", "").replace("-", "m");
        File dir = new File(root, v + "_" + rateTag);
        return new File(dir, "ch" + chapter + "_seg" + seg + ".mp3");
    }

    /** 系统语音段文件：语速由合成时定（文件名带语速档，同 Edge 口径分目录）。 */
    private File sysFile(int chapter, int seg) {
        String rateTag = String.valueOf(Math.round(rate * 100));
        File dir = new File(root, "sys_" + rateTag);
        return new File(dir, "ch" + chapter + "_seg" + seg + ".wav");
    }

    /** 已落盘的段文件（按当前实际引擎优先，防跨会话混音色）；都没有返回 null。 */
    private File fileFor(int chapter, int seg) {
        File e = edgeFile(chapter, seg);
        File s = sysFile(chapter, seg);
        if (usingSystem()) {
            if (s.exists()) return s;
            if (e.exists()) return e;
        } else {
            if (e.exists()) return e;
            if (s.exists()) return s;
        }
        return null;
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
            else if (f.getName().endsWith(".mp3") || f.getName().endsWith(".wav")) out.add(f);
        }
    }

    public void shutdown() {
        running = false;
        synchronized (queue) {
            queue.notify();
        }
        if (systemSynth != null) {
            try {
                systemSynth.shutdown();
            } catch (Throwable ignored) {
            }
            systemSynth = null;
        }
        // 只置旗时 worker 若正卡在在途合成的网络调用里，要等整段返回才
        // 退出；interrupt 让等待中的 worker 立刻醒，在途段返回后也会被
        // synthesize 的 running 检查与 process 的回调闸拦下，不再往下走。
        worker.interrupt();
    }
}
