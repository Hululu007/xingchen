package com.fongmi.android.tv.cloud.drive115;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 115 音频「搜索时一趟读全标签」：结果出来后挨条读文件头（本机代理地址、范围读，
 * 窗口 128K→512K→2M→8M 递进，够解析标签为止，ID3 头＋APIC／FLAC 块都在文件头），
 * 内嵌封面／内嵌歌词／时长／专辑／标签歌名歌手一次读全，按文件 id 落缓存
 * （cacheDir/pan115tag/&lt;fid&gt;.json＋&lt;fid&gt;.jpg；读过的不重复读，
 * 空结果留 settled 标记、24 小时内不重试，与 Pan115Cover 同口径）。
 * 限量并行 3 路；列表先行、标签随后回填同一批曲目对象并通知监听方重绑。
 * 播放时全是现成：applyTo 把缓存兑现在曲目上（内嵌封面第一优先、覆盖文件名猜测），
 * 歌词由 CloudMusicSource.lyric 先查本缓存，命中就不出门配词。
 */
public final class Pan115Tags {

    /** 一批曲目的标签回填完（逐条 settle 即报，changed 是本批内字段被改过的曲目） */
    public interface FillListener {
        void onPanTagsFilled(List<MusicTrack> tracks);
    }

    private static final List<FillListener> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addFillListener(FillListener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeFillListener(FillListener l) {
        LISTENERS.remove(l);
    }

    public static void notifyFilled(List<MusicTrack> changed) {
        if (changed == null || changed.isEmpty()) return;
        for (FillListener l : LISTENERS) {
            try {
                l.onPanTagsFilled(changed);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 缓存条目：字段为空＝标签里没有（不是没读过）；settled 区分「读过」与「没读过」 */
    public static final class Entry {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String lyric = "";
        public long durationMs;
        public String coverUri = "";
        public boolean settled;
        public long settledAt;

        public boolean anyUseful() {
            return !title.isEmpty() || !artist.isEmpty() || !album.isEmpty()
                    || !lyric.isEmpty() || durationMs > 0 || !coverUri.isEmpty();
        }
    }

    private static final long NONE_RETRY_MS = 24L * 60 * 60 * 1000;
    private static final int[] WINDOWS = {128 * 1024, 512 * 1024, 2 * 1024 * 1024, 8 * 1024 * 1024};
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "pan115tag");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Entry> MEM = new ConcurrentHashMap<>();
    private static final java.util.Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();

    private Pan115Tags() {
    }

    // ================= 缓存存取 =================

    private static File dir() {
        File d = new File(App.get().getCacheDir(), "pan115tag");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String safe(String fid) {
        return fid == null ? "" : fid.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static File jsonFile(String fid) {
        return new File(dir(), safe(fid) + ".json");
    }

    private static File coverFile(String fid) {
        return new File(dir(), safe(fid) + ".jpg");
    }

    /** 读缓存条目（内存→磁盘）；从没读过返回 null */
    public static Entry cached(String fid) {
        if (TextUtils.isEmpty(fid)) return null;
        Entry e = MEM.get(fid);
        if (e != null) return e;
        File jf = jsonFile(fid);
        if (!jf.isFile()) return null;
        try {
            byte[] bytes = readFile(jf);
            JSONObject o = new JSONObject(new String(bytes, "UTF-8"));
            e = new Entry();
            e.title = o.optString("title", "");
            e.artist = o.optString("artist", "");
            e.album = o.optString("album", "");
            e.lyric = o.optString("lyric", "");
            e.durationMs = o.optLong("duration", 0);
            e.settled = o.optBoolean("settled", false);
            e.settledAt = o.optLong("settledAt", 0);
            File cf = coverFile(fid);
            if (o.optBoolean("cover", false) && cf.isFile() && cf.length() > 0) {
                e.coverUri = Uri.fromFile(cf).toString();
            }
            MEM.put(fid, e);
            return e;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void persist(String fid, Entry e, byte[] picture) {
        try {
            boolean hasCover = false;
            if (picture != null && picture.length > 0) {
                File cf = coverFile(fid);
                File tmp = new File(dir(), safe(fid) + ".tmp");
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(picture);
                }
                if (tmp.length() > 0) {
                    if (cf.exists()) cf.delete();
                    if (tmp.renameTo(cf)) {
                        hasCover = true;
                        e.coverUri = Uri.fromFile(cf).toString();
                    }
                }
                tmp.delete();
            } else {
                File cf = coverFile(fid);
                if (cf.isFile() && !TextUtils.isEmpty(e.coverUri)) hasCover = true;
            }
            JSONObject o = new JSONObject();
            o.put("title", e.title);
            o.put("artist", e.artist);
            o.put("album", e.album);
            o.put("lyric", e.lyric);
            o.put("duration", e.durationMs);
            o.put("cover", hasCover);
            o.put("settled", true);
            o.put("settledAt", e.settledAt);
            File jf = jsonFile(fid);
            try (FileOutputStream fos = new FileOutputStream(jf)) {
                fos.write(o.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        MEM.put(fid, e);
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /** 缓存是否已定案（读过且未过重试期）：定案就不再读 */
    private static boolean settledFresh(Entry e) {
        if (e == null || !e.settled) return false;
        if (e.anyUseful()) return true;
        return System.currentTimeMillis() - e.settledAt < NONE_RETRY_MS;
    }

    // ================= 回填到曲目 =================

    /**
     * 把缓存标签兑现到曲目上：标签歌名／歌手／专辑非空就覆盖文件名解析值（标签更准），
     * 时长补正，内嵌封面第一优先（直接盖过平台配图与空位）。返回是否有字段变化。
     */
    public static boolean applyTo(MusicTrack track) {
        if (track == null) return false;
        String fid = fidOf(track);
        if (fid == null) return false;
        Entry e = cached(fid);
        if (e == null || !e.settled) return false;
        boolean changed = false;
        if (!e.title.isEmpty() && !e.title.equals(track.title)) {
            track.title = e.title;
            changed = true;
        }
        if (!e.artist.isEmpty() && !e.artist.equals(track.artist)) {
            track.artist = e.artist;
            changed = true;
        }
        if (!e.album.isEmpty() && !e.album.equals(track.album)) {
            track.album = e.album;
            changed = true;
        }
        if (e.durationMs > 0 && track.duration != e.durationMs) {
            track.duration = e.durationMs;
            changed = true;
        }
        track.durationPending = false;
        if (!e.coverUri.isEmpty() && !e.coverUri.equals(track.cover)) {
            boolean hadCover = track.cover != null && !track.cover.isEmpty();
            track.cover = e.coverUri;
            if (!hadCover) track.coverBackfilled = true;
            changed = true;
        }
        if (changed || track.coverPending) {
            // 已 settle 却没内嵌图：pending 必须收口，行才走平台图／素图，不许永久空位
            if (e.coverUri.isEmpty()) track.coverPending = false;
        }
        return changed;
    }

    /** 曲目的 115 文件 id：rawJson 的 fid（搜索与网盘页同口径）；取不到返回 null */
    public static String fidOf(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null) return null;
            JSONObject o = new JSONObject(track.rawJson);
            if (!Drive115.ID.equals(o.optString("drive"))) return null;
            String fid = o.optString("fid");
            return fid.isEmpty() ? null : fid;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String pickCodeOf(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null) return "";
            return new JSONObject(track.rawJson).optString("pickCode");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 内嵌歌词（缓存口径）：没读过先同步读一次（歌词链在后台线程，可阻塞）；没有返回空串 */
    public static String lyricFor(MusicTrack track) {
        String fid = fidOf(track);
        if (fid == null) return "";
        Entry e = cached(fid);
        if (e == null || !e.settled) {
            e = readOne(track);
        }
        return e == null ? "" : e.lyric;
    }

    // ================= 读取（搜索批量／点播单条） =================

    /**
     * 搜索批量读：先把已缓存的同步兑现、未读的标在途（封面位走玻璃空位、时长走后补），
     * 再限量并行逐条读；每条定案即回填＋通知监听方。全批定案后跑 onAllSettled
     * （调用方在那里补平台封面——内嵌没读到的才轮到平台配）。
     */
    public static void readBatch(final List<MusicTrack> tracks, final Runnable onAllSettled) {
        if (tracks == null || tracks.isEmpty()) {
            if (onAllSettled != null) onAllSettled.run();
            return;
        }
        List<MusicTrack> todo = new ArrayList<>();
        List<MusicTrack> changedNow = new ArrayList<>();
        for (MusicTrack t : tracks) {
            String fid = fidOf(t);
            if (fid == null) continue;
            Entry e = cached(fid);
            if (settledFresh(e)) {
                if (applyTo(t)) changedNow.add(t);
            } else {
                if (t.cover == null || t.cover.isEmpty()) t.coverPending = true;
                if (t.duration <= 0) t.durationPending = true;
                todo.add(t);
            }
        }
        if (!changedNow.isEmpty()) notifyFilled(changedNow);
        if (todo.isEmpty()) {
            if (onAllSettled != null) onAllSettled.run();
            return;
        }
        final java.util.concurrent.atomic.AtomicInteger remain = new java.util.concurrent.atomic.AtomicInteger(todo.size());
        // 一批共用一个 Drive115（cookie 构造时从托管存储恢复），别逐条 new
        for (final MusicTrack t : todo) {
            final String fid = fidOf(t);
            if (fid == null || !INFLIGHT.add(fid)) {
                if (remain.decrementAndGet() == 0 && onAllSettled != null) {
                    POOL.execute(onAllSettled);
                }
                continue;
            }
            POOL.execute(() -> {
                try {
                    readOne(t);
                    List<MusicTrack> one = new ArrayList<>();
                    synchronized (t) {
                        if (applyTo(t)) one.add(t);
                        else {
                            // 没变化也要让行收口（pending 清掉走素图/平台图）
                            t.coverPending = false;
                            t.durationPending = false;
                            one.add(t);
                        }
                    }
                    notifyFilled(one);
                } catch (Throwable ignored) {
                } finally {
                    INFLIGHT.remove(fid);
                    if (remain.decrementAndGet() == 0 && onAllSettled != null) {
                        try {
                            onAllSettled.run();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
    }

    /**
     * 点播兜底（异步版）：搜索那趟没赶上（队列恢复／网盘页直点／标签还在读时就点播），
     * 播时在后台读一次定案、回填本曲目对象，再经 onFilled（主线程）通知服务换封面。
     * 已定案的直接回填不重复读。
     */
    public static void ensureForPlayback(final MusicTrack track, final Runnable onFilled) {
        String fid = fidOf(track);
        if (fid == null) return;
        Entry e = cached(fid);
        if (settledFresh(e)) {
            applyTo(track);
            if (onFilled != null) onFilled.run();
            return;
        }
        if (!INFLIGHT.add(fid)) return;
        POOL.execute(() -> {
            try {
                readOne(track);
                applyTo(track);
            } catch (Throwable ignored) {
            } finally {
                INFLIGHT.remove(fid);
                if (onFilled != null) {
                    try {
                        App.post(onFilled);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    /** 点播单条兜底：搜索那趟没赶上（队列恢复／网盘页直点）时，播时同步读一次定案 */
    public static Entry readOne(MusicTrack track) {
        String fid = fidOf(track);
        if (fid == null) return null;
        Entry e = cached(fid);
        if (settledFresh(e)) return e;
        AudioHeadTags.Result parsed = fetchAndParse(track);
        Entry ne = new Entry();
        if (parsed != null) {
            ne.title = parsed.title == null ? "" : parsed.title;
            ne.artist = parsed.artist == null ? "" : parsed.artist;
            ne.album = parsed.album == null ? "" : parsed.album;
            ne.lyric = parsed.lyric == null ? "" : parsed.lyric;
            ne.durationMs = parsed.durationMs;
        }
        ne.settled = true;
        ne.settledAt = System.currentTimeMillis();
        persist(fid, ne, parsed == null ? null : parsed.picture);
        return ne;
    }

    /** 取头＋解析：窗口递进，直到解析定案或窗口用尽；取链失败按无标签收口（不抛） */
    private static AudioHeadTags.Result fetchAndParse(MusicTrack track) {
        String pickCode = pickCodeOf(track);
        if (TextUtils.isEmpty(pickCode)) return null;
        Drive115 drive = new Drive115();
        String raw;
        try {
            raw = drive.downUrl(pickCode);
        } catch (Throwable t) {
            log(pickCode, "取直链失败 " + t.getClass().getSimpleName());
            return null;
        }
        if (TextUtils.isEmpty(raw)) return null;
        Proxy115 proxy = Proxy115.getInstance(drive.getClient(), drive.getCookieJar());
        int port = proxy.startProxy();
        if (port <= 0) {
            log(pickCode, "代理启动失败");
            return null;
        }
        // 与 Pan115Cover 同一套代理包法（头与播放链一致，115 认 UA/Referer/Cookie 在代理侧绑定）
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 115Browser/27.0.5.7");
        headers.put("Referer", "https://115.com/");
        String cookie = drive.getCookieJar().merged(drive.getCookie());
        if (!TextUtils.isEmpty(cookie)) headers.put("Cookie", cookie);
        String token = proxy.put(headers);
        proxy.bindTarget(token, raw, pickCode);
        String url = "http://127.0.0.1:" + port + "/v?t=" + token + "&u=" + Proxy115.b64e(raw);
        AudioHeadTags.Result last = null;
        for (int w = 0; w < WINDOWS.length; w++) {
            byte[] head = fetchHead(drive, url, WINDOWS[w]);
            // 取流失败不拿半截结果定案（返回 null＝本次不定案，下次搜索/点播可重试）
            if (head == null || head.length == 0) return null;
            // 返回字节少于窗口＝已到文件尾，后面再大的窗口也是同一份内容
            boolean more = w < WINDOWS.length - 1 && head.length >= WINDOWS[w];
            last = AudioHeadTags.parse(head, head.length, more);
            if (last.status != AudioHeadTags.NEED_MORE) {
                log(pickCode, "window=" + WINDOWS[w] + " meta=" + last.anyMeta());
                return last;
            }
        }
        return last;
    }

    /** 范围读文件头：Range 到窗口上限为止，服务端不认 Range 也只读窗口量就关流，不整文件下 */
    private static byte[] fetchHead(Drive115 drive, String url, int window) {
        Request req = new Request.Builder().url(url)
                .header("Range", "bytes=0-" + (window - 1))
                .build();
        try (Response resp = drive.getClient().newCall(req).execute()) {
            ResponseBody body = resp.body();
            if (body == null) return null;
            InputStream in = body.byteStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (out.size() < window && (n = in.read(buf, 0, Math.min(buf.length, window - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String pickCode, String why) {
        try {
            SpiderDebug.log("pan115tag", "115 pc=%s %s", pickCode, why);
        } catch (Throwable ignored) {
        }
    }
}
