package com.fongmi.android.tv.music;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicDownload;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.MusicDownloadDao;
import com.fongmi.android.tv.update.UpdateIdentity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.download.DownloadManager;
import com.fongmi.android.tv.utils.download.DownloadTask;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 音乐下载登记：发起下载走现成的 DownloadManager（不另造下载实现），
 * 下载完成回调里把本地文件登记进 MusicDownload 表；platform="local" 的
 * MusicTrack 由 LocalMusicSource 解析成本地文件直链，播放链不动。
 */
public class MusicDownloadStore {

    public interface DataCallback<T> {
        void onResult(T data);
    }

    /**
     * 本地曲目 id：local:＋全局身份键（platform|插件来源|原id）。
     * 旧口径只拼裸原 id：两个插件各有一首 id 相同的歌时登记键撞车，后下的顶掉先下的、
     * 删歌连坐。身份键口径见 {@link MusicIdentity}；旧键存量行由 save／已下载检查处
     * 命中后改键自愈。
     */
    public static String localId(MusicTrack track) {
        if (track == null) return LocalMusicSource.PLATFORM + ":";
        String identity = MusicIdentity.keyOf(track);
        if (identity != null) return LocalMusicSource.PLATFORM + ":" + identity;
        return LocalMusicSource.PLATFORM + ":" + (track.id == null ? "" : track.id);
    }

    /** 旧版登记键（local:裸原id）命中的存量行改键到当前身份键；找不到返回 null */
    private static MusicDownload healLegacyRecord(MusicDownloadDao dao, String currentKey, String rawId) {
        try {
            if (rawId == null || rawId.isEmpty() || rawId.startsWith(LocalMusicSource.PLATFORM + ":")) return null;
            MusicDownload legacy = dao.findByTrackId(LocalMusicSource.PLATFORM + ":" + rawId);
            if (legacy == null) return null;
            legacy.setTrackId(currentKey);
            dao.update(legacy);
            return legacy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isLocal(MusicTrack track) {
        return track != null && LocalMusicSource.PLATFORM.equals(track.platform);
    }

    private static MusicTrack toTrack(MusicDownload d) {
        MusicTrack t = new MusicTrack();
        t.id = d.getTrackId();
        t.title = d.getTitle();
        t.artist = d.getArtist();
        t.album = d.getAlbum();
        t.duration = d.getDuration();
        t.cover = d.getCover();
        t.qualityTag = d.getQualityTag();
        t.platform = LocalMusicSource.PLATFORM;
        t.localPath = d.getFilePath();
        // 第三波 P-8c：已下载歌首次加载（列表／队列取数即到这里）时，记录封面为空才
        // 对下载文件跑兜底：同名旁路图优先，其次文件内嵌图落缓存文件当 cover；
        // 缓存文件已存在时不重复提内嵌（只判文件存在，不跑 Retriever）。
        if ((t.cover == null || t.cover.isEmpty()) && t.localPath != null && !t.localPath.isEmpty()) {
            try {
                String resolved = resolveDownloadCover(t);
                if (resolved != null && !resolved.isEmpty()) t.cover = resolved;
            } catch (Throwable ignored) {
            }
        }
        return t;
    }

    /** 第三波 P-8c：已下载歌封面兜底序（下载文件路径口径）：同名旁路图 → 内嵌图落缓存。
     * 旁路后缀与 DeviceMusicStore.sidecarCover 同款（.jpg／.png／.jpeg）；内嵌缓存文件
     * 以 trackId 哈希命名，存 App 缓存 music_embedded 下，旧任务文件不动。 */
    public static String resolveDownloadCover(MusicTrack track) {
        if (track == null || track.localPath == null || track.localPath.isEmpty()) return null;
        File audio = new File(track.localPath);
        if (!audio.isFile()) return null;
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = audio.getParentFile();
        if (parent != null) {
            for (String suf : new String[]{".jpg", ".png", ".jpeg"}) {
                File cand = new File(parent, base + suf);
                if (cand.isFile() && cand.canRead() && cand.length() > 0) {
                    return android.net.Uri.fromFile(cand).toString();
                }
            }
        }
        File dir = new File(App.get().getCacheDir(), "music_embedded");
        String fileName = Math.abs((track.id == null ? track.localPath : track.id).hashCode()) + ".jpg";
        File cached = new File(dir, fileName);
        if (cached.isFile() && cached.length() > 0) return android.net.Uri.fromFile(cached).toString();
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(track.localPath);
            byte[] pic = r.getEmbeddedPicture();
            if (pic == null || pic.length == 0) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cached);
            try {
                fos.write(pic);
            } finally {
                fos.close();
            }
            return cached.isFile() ? android.net.Uri.fromFile(cached).toString() : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 第三波 G-C1：已下载歌同名旁路歌词文本（离线优先读旁路，见播放页 loadLyric）；
     * 文件不存在／空时返回 null，走原在线链。 */
    public static String readSidecarLyric(MusicTrack track) {
        if (track == null) return null;
        if (track.localPath != null && !track.localPath.isEmpty()) {
            try {
                File audio = new File(track.localPath);
                if (audio.isFile()) {
                    String name = audio.getName();
                    int dot = name.lastIndexOf('.');
                    String base = dot > 0 ? name.substring(0, dot) : name;
                    File parent = audio.getParentFile();
                    if (parent != null) {
                        File lrc = new File(parent, base + ".lrc");
                        if (lrc.isFile() && lrc.length() > 0) {
                            byte[] bytes = new byte[(int) Math.min(lrc.length(), 2L * 1024 * 1024)];
                            java.io.FileInputStream fis = new java.io.FileInputStream(lrc);
                            try {
                                int read = fis.read(bytes);
                                if (read > 0) {
                                    return new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8);
                                }
                            } finally {
                                fis.close();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 旁路文件不在时的兜底：下载时歌词写入失败、策略为「写入字段」而存进记录的
        // 歌词原文（downloadSidecars 落的 lyric 列）——没有这层，策略存了也读不回来。
        try {
            if (track.id == null || track.id.isEmpty()) return null;
            MusicDownload d = AppDatabase.get().getMusicDownloadDao().findByTrackId(track.id);
            if (d != null && d.getLyric() != null && !d.getLyric().trim().isEmpty()) return d.getLyric();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 第三波 P-8d：在线补图命中后写回下载记录（播放页 maybeFetchOnlineCover 调用）；
     * 只在记录存在时更新 cover 字段，不动文件与其它字段；本机缓存即记录本身。 */
    public static void updateDownloadCover(String trackId, String coverUrl) {
        if (trackId == null || trackId.isEmpty() || coverUrl == null || coverUrl.isEmpty()) return;
        Task.execute(() -> {
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(trackId);
                if (d != null) {
                    d.setCover(coverUrl);
                    dao.update(d);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static MusicTrack copyForLocal(MusicTrack track) {
        MusicTrack t = new MusicTrack();
        t.id = localId(track);
        t.title = track.title;
        t.artist = track.artist;
        t.album = track.album;
        t.duration = track.duration;
        t.cover = track.cover;
        t.qualityTag = track.qualityTag;
        t.platform = LocalMusicSource.PLATFORM;
        return t;
    }

    private static void post(Runnable r) {
        App.post(r);
    }

    // ================= 查询 =================

    public static void all(DataCallback<List<MusicTrack>> cb) {
        Task.execute(() -> {
            reconcilePending();
            List<MusicTrack> list = new ArrayList<>();
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                for (MusicDownload d : dao.findAll()) {
                    // 幽灵记录清理：文件被外部删掉后记录还回读成「已下载」、大小 0、
                    // 点播无链——回读时验文件存在，不在的当场删记录不再回读。
                    String fp = d.getFilePath();
                    if (fp == null || fp.isEmpty() || !new File(fp).isFile()) {
                        SpiderDebug.log("music", "download ghost record cleaned trackId=%s path=%s", d.getTrackId(), fp);
                        try {
                            dao.deleteByTrackId(d.getTrackId());
                        } catch (Throwable ignored) {
                        }
                        continue;
                    }
                    list.add(toTrack(d));
                }
            } catch (Throwable ignored) {
            }
            post(() -> cb.onResult(list));
        });
    }

    public static void count(DataCallback<Integer> cb) {
        Task.execute(() -> {
            int n = 0;
            try {
                n = AppDatabase.get().getMusicDownloadDao().count();
            } catch (Throwable ignored) {
            }
            int c = n;
            post(() -> cb.onResult(c));
        });
    }

    // ================= 发起下载 =================

    private static final Map<String, MusicTrack> pending = new ConcurrentHashMap<>();
    private static boolean listenerAdded;

    /**
     * pending 落盘（低-01）：只靠进程内回调登记时，进程被杀后由系统/服务拉起的新进程
     * 里把歌下完也没人登记成本地音乐。快照随 pending 增删同步落盘，重启后先恢复再对账。
     */
    private static final String KEY_PENDING = "music_pending_downloads";
    private static volatile boolean pendingLoaded;

    private static void persistPending() {
        try {
            Prefers.put(KEY_PENDING, new Gson().toJson(new java.util.HashMap<>(pending)));
        } catch (Throwable ignored) {
        }
    }

    private static void loadPending() {
        if (pendingLoaded) return;
        pendingLoaded = true;
        try {
            String json = Prefers.getString(KEY_PENDING, "");
            if (json == null || json.isEmpty()) return;
            java.lang.reflect.Type type = new TypeToken<Map<String, MusicTrack>>() {
            }.getType();
            Map<String, MusicTrack> saved = new Gson().fromJson(json, type);
            if (saved != null) pending.putAll(saved);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 对账：把「外进程期间已下完」的任务补登记。
     * 任务表是纯内存的：进程被杀后新进程里 getTask 恒为 null，旧对账永远等不到
     * 任务记录、pending 残条永久滞留。任务查不到时改去下载目录核对实际文件。
     * 成品判据不能只凭「文件存在＋大小>0」：下载器是直写最终文件（无 .part
     * 落盘后改名），进程被杀在下载中途，最终路径上躺着的正是半截文件且大小>0，
     * 只凭大小会把半截歌登记成已下载、还清掉 pending 断了续传。故判成品还须
     * 两个未完标记都不在——.progress 进度档与 .id 身份旁车（下载器开下时落、
     * 成品落定时摘，见 HttpDownloader／UpdateIdentity）。任一标记在＝未下完，
     * 保留 pending 不清不登记；同名文件已被本轮前一条目认领＝同样保留 pending，
     * 不静默丢任务；同名文件不在才清掉残条（旧进程已死，不会再有任务把它下完）。
     * pending 快照不存期望大小，无期望大小可用，故以标记为判据。
     */
    private static void reconcilePending() {
        loadPending();
        if (pending.isEmpty()) return;
        boolean changed = false;
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (Map.Entry<String, MusicTrack> e : new ArrayList<>(pending.entrySet())) {
            try {
                DownloadTask task = DownloadManager.get().getTask(e.getKey());
                if (task != null) {
                    if (task.getStatus() == DownloadTask.STATUS_COMPLETED
                            && task.getFilePath() != null && !task.getFilePath().isEmpty()) {
                        pending.remove(e.getKey());
                        changed = true;
                        save(e.getValue(), task.getFilePath());
                    }
                    continue;
                }
                File hit = findPendingFile(e.getValue(), claimed);
                if (hit != null) {
                    claimed.add(hit.getAbsolutePath());
                    pending.remove(e.getKey());
                    changed = true;
                    save(e.getValue(), hit.getAbsolutePath());
                } else if (hasAnyPendingFile(e.getValue())) {
                    // 同名文件在但没下完（未完标记还在），或已被本轮前一条目
                    // 认领走：保留 pending 不清不登记，下轮对账再看。
                    continue;
                } else {
                    // 同名文件不在：旧进程已死、不会再有任务把它下完，清残条。
                    pending.remove(e.getKey());
                    changed = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (changed) persistPending();
    }

    /** 下载目录（与 DownloadManager.getDownloadDir 同口径：私有 Downloads/XingChen，旧版 filesDir/Download 兼容）。 */
    private static File downloadDir() {
        try {
            Context ctx = App.get();
            if (ctx == null) return null;
            File dir = new File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "XingChen");
            File legacy = new File(ctx.getFilesDir(), "Download");
            if (legacy.exists() && !dir.exists()) return legacy;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按任务名在下载目录里找可补登记的成品文件：文件名（去扩展名）与任务名一致或带 _N 去重后缀、大小>0、未被本轮其他条目认领、且两个下载未完标记都不在。 */
    private static File findPendingFile(MusicTrack track, java.util.Set<String> claimed) {
        if (track == null) return null;
        File dir = downloadDir();
        if (dir == null || !dir.isDirectory()) return null;
        String base = sanitizeFileName(downloadFileName(track));
        if (base.isEmpty()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            if (claimed.contains(f.getAbsolutePath())) continue;
            if (hasDownloadMarkers(f)) continue;
            if (nameMatches(f, base)) return f;
        }
        return null;
    }

    /** 下载目录里有没有同名落盘文件（大小>0）：不看认领、不看标记，只判「在不在」，供对账区分「文件不在可清残条」与「文件在但未下完／已被认领须留 pending」。 */
    private static boolean hasAnyPendingFile(MusicTrack track) {
        if (track == null) return false;
        File dir = downloadDir();
        if (dir == null || !dir.isDirectory()) return false;
        String base = sanitizeFileName(downloadFileName(track));
        if (base.isEmpty()) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f == null || !f.isFile() || f.length() <= 0) continue;
            if (nameMatches(f, base)) return true;
        }
        return false;
    }

    /** 文件名（去扩展名）与任务名一致，或带 _N 去重后缀。 */
    private static boolean nameMatches(File f, String base) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem.equals(base) || isDedupStem(stem, base);
    }

    /**
     * 下载未完标记（与 HttpDownloader 的落盘口径逐一对齐）：
     * ①.progress 进度档＝目标文件绝对路径＋".progress"（多线程分块进度，
     * HttpDownloader 开下时写、成品落定时删）；
     * ②.id 身份旁车＝UpdateIdentity.sidecar（同目录、文件名＋".id"，开下前
     * 先落、成品落定时 clearSidecar 摘掉）。
     * 任一在＝这份文件还没下完，不许当成品登记。
     */
    private static boolean hasDownloadMarkers(File f) {
        if (new File(f.getAbsolutePath() + ".progress").exists()) return true;
        return UpdateIdentity.sidecar(f).exists();
    }

    /** 任务名_N 形态（DownloadManager 避免重名时加的后缀），N 必须全数字 */
    private static boolean isDedupStem(String stem, String base) {
        if (!stem.startsWith(base + "_")) return false;
        String tail = stem.substring(base.length() + 1);
        if (tail.isEmpty()) return false;
        for (int i = 0; i < tail.length(); i++) if (!Character.isDigit(tail.charAt(i))) return false;
        return true;
    }

    /** 与 DownloadManager.sanitizeFileName 同口径的文件名净化 */
    private static String sanitizeFileName(String name) {
        return name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static synchronized void ensureListener() {
        if (listenerAdded) return;
        listenerAdded = true;
        loadPending();
        DownloadManager.get().addListener(task -> {
            if (task.getStatus() == DownloadTask.STATUS_COMPLETED) {
                MusicTrack t = pending.remove(task.getId());
                if (t != null) persistPending();
                if (t != null && task.getFilePath() != null && !task.getFilePath().isEmpty()) {
                    save(t, task.getFilePath());
                }
            } else if (task.getStatus() == DownloadTask.STATUS_ERROR) {
                // 第 7 项尾巴：失败任务留待下载页“清除失败”，不静默丢掉（失败行＋清除失败就靠它）
                // （重试时 downloadWithQuality 会先清掉同曲的失败旧任务，不会一曲两行）
            }
        });
    }

    /** 第三波 S-A2：每次询问选档的文件大小（实测口径，与音质面板 Q3 一致）：
     * 逐档取链后发 HEAD 读 Content-Length；取不到链／HEAD 无长度／异常时该档大小为 -1，
     * 调用方不显示该档大小、不估算（不拿时长×标称码率垫数）。后台线程跑，主线程回 sizes
     * （顺序与传入 values 一致）。 */
    public interface SizeCallback {
        void onResult(long[] sizes);
    }

    public static void probeQualitySizes(MusicTrack track, int[] values, SizeCallback cb) {
        Task.execute(() -> {
            long[] sizes = new long[values == null ? 0 : values.length];
            for (int i = 0; i < sizes.length; i++) sizes[i] = -1;
            if (track != null && values != null) {
                for (int i = 0; i < values.length; i++) {
                    String url = null;
                    try {
                        url = MusicRepository.playUrl(track, values[i]);
                    } catch (Throwable ignored) {
                    }
                    if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) continue;
                    java.net.HttpURLConnection conn = null;
                    try {
                        conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        conn.setRequestMethod("HEAD");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.setInstanceFollowRedirects(true);
                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 400) {
                            long len = conn.getContentLengthLong();
                            if (len > 0) sizes[i] = len;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
            }
            long[] result = sizes;
            post(() -> cb.onResult(result));
        });
    }

    /** download() 的受理结果（扫描 M27）：调用方据此如实提示，不许无条件报「已加入下载」。 */
    public static final int DL_ACCEPTED = 0;
    /** 本地歌曲：无下载动作（原实现静默 return，瓷砖侧却照报已加入下载） */
    public static final int DL_LOCAL = 1;
    /** 每次询问模式：本入口不入队，已另发提示让用户去列表/播放页选档 */
    public static final int DL_ASK = 2;

    /** 歌曲行 ⋮ 菜单「下载」入口（固定／跟随模式）：取直链 → 交 DownloadManager 下载。
     * 每次询问模式下本入口无 Activity 弹不了选档窗，不静默按固定档下载，只提示后返回；
     * 逐首选档走 downloadWithQuality（调用点在 mobile 侧弹 MusicOptionsDialog 后调入），
     * 播放页 downloadCurrent 的 ASK 分支见协调员补丁规格（本文件在 main 源集，不引 mobile 弹窗，
     * 否则 leanback 变体编不过）。返回值见 DL_* 常量；既有调用方忽略返回值不受影响。 */
    public static int download(MusicTrack track) {
        if (track == null || isLocal(track)) return DL_LOCAL;
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            post(() -> Notify.show(R.string.music_dl_ask_no_activity));
            return DL_ASK;
        }
        downloadWithQuality(track, MusicStore.getDownloadQuality());
        return DL_ACCEPTED;
    }

    /** 逐首选档后的下载核心（ASK 弹窗的落点；固定／跟随模式由 download 转调）：取指定档直链 → 交 DownloadManager 下载 */
    public static void downloadWithQuality(MusicTrack track, int quality) {
        if (track == null || isLocal(track)) return;
        // 仅 Wi-Fi 下载（瓜子同名项）：移动网络下新下载一律不启动（手动“继续”已暂停任务不在此限，用户点了就是明确要下）
        if (MusicStore.isWifiOnlyDownload() && isCellular()) {
            post(() -> Notify.show(R.string.music_wifi_only_blocked));
            return;
        }
        ensureListener();
        Task.execute(() -> {
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(localId(track));
                if (d == null) d = healLegacyRecord(dao, localId(track), track.id);
                if (d != null && d.getFilePath() != null && new File(d.getFilePath()).exists()) {
                    post(() -> Notify.show(R.string.music_download_exists));
                    return;
                }
            } catch (Throwable ignored) {
            }
            String url;
            try {
                url = MusicRepository.playUrl(track, quality);
            } catch (Throwable t) {
                url = null;
            }
            if (url == null || url.isEmpty()) {
                post(() -> Notify.show(R.string.music_download_url_empty));
                return;
            }
            // 重试同曲：先清掉该曲的失败旧任务（DownloadManager 删任务＋pending 除名），免得一曲两行
            loadPending();
            for (Map.Entry<String, MusicTrack> e : new ArrayList<>(pending.entrySet())) {
                if (e.getValue() != null && localId(track).equals(e.getValue().id)) {
                    DownloadTask old = DownloadManager.get().getTask(e.getKey());
                    if (old == null || old.getStatus() == DownloadTask.STATUS_ERROR) {
                        if (old != null) DownloadManager.get().delete(e.getKey());
                        pending.remove(e.getKey());
                    }
                }
            }
            persistPending();
            for (DownloadTask t : DownloadManager.get().getTasks()) {
                // 去重含已暂停态：旧口径只查 WAITING/DOWNLOADING，PAUSED 的同 URL 再发起
                // 会再建一个任务、两任务同目标文件并发写。
                if (url.equals(t.getUrl()) && (t.getStatus() == DownloadTask.STATUS_DOWNLOADING
                        || t.getStatus() == DownloadTask.STATUS_WAITING
                        || t.getStatus() == DownloadTask.STATUS_PAUSED)) {
                    post(() -> Notify.show(R.string.music_downloading));
                    return;
                }
            }
            String name = downloadFileName(track);
            MusicTrack snapshot = copyForLocal(track);
            final String taskUrl = url;
            final String taskName = name;
            post(() -> {
                try {
                    DownloadTask task = DownloadManager.get().add(taskUrl, taskName);
                    if (task != null) {
                        pending.put(task.getId(), snapshot);
                        persistPending();
                    }
                } catch (Throwable e) {
                    Notify.show(R.string.music_download_failed);
                }
            });
        });
    }

    /** 下载完成登记：入库（DownloadManager 已提示"下载完成"）。登记失败不许静默吞：
     * 留痕＋明面提示「完成但没登记上」，不许用户只见完成、列表里没歌还无从知道。 */
    private static void save(MusicTrack track, String filePath) {
        Task.execute(() -> {
            boolean saved = false;
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(track.id);
                // track 是本地快照（id=local:身份键）；旧键存量行改键自愈，免得同曲两行
                if (d == null) {
                    int cut = track.id == null ? -1 : track.id.lastIndexOf('|');
                    if (cut > 0) d = healLegacyRecord(dao, track.id, track.id.substring(cut + 1));
                }
                if (d == null) {
                    d = new MusicDownload();
                    d.setTrackId(track.id);
                    d.setTitle(track.title);
                    d.setArtist(track.artist);
                    d.setAlbum(track.album);
                    d.setDuration(track.duration);
                    d.setCover(track.cover);
                    d.setCreateTime(System.currentTimeMillis());
                }
                d.setFilePath(filePath);
                // 音质标落存：下载当下快照的 qualityTag 存进记录，重启回读已下载列表才回显（toTrack 回填）
                if (track.qualityTag != null && !track.qualityTag.isEmpty()) d.setQualityTag(track.qualityTag);
                if (d.getId() == 0) dao.insert(d);
                else dao.update(d);
                saved = true;
            } catch (Throwable t) {
                SpiderDebug.log("music", "download save failed trackId=%s path=%s error=%s",
                        track == null ? "" : track.id, filePath, t.getMessage());
            }
            if (!saved) {
                post(() -> Notify.show("下载完成，但登记失败，列表里可能看不到这首歌"));
                return;
            }
            // 第三波 S-A3／G-C1：下载完成时同下封面＋歌词（只针对本次新任务，旧任务不动）。
            downloadSidecars(track, filePath);
        });
    }

    /** 第三波 S-A3／G-C1：下载完成时同下封面＋歌词，按「封面与歌词」档位真写：
     * 旁路档＝同名旁路文件（封面 stem.jpg、歌词 stem.lrc，与 deleteLocal 同删后缀一致）；
     * 内嵌档＝EmbeddedTagWriter 真写进音频文件（ID3v2.3／FLAC 两格式，不支持的格式回失败）；
     * 两者档＝两路都写。素材只取一次（封面字节／歌词原文），两路共用。
     * 歌词来源：快照曲目走 MusicRepository.lyric 跨源回退（快照已是本地平台副本，
     * 本源 lyric 为空时按歌名＋歌手跨源找）；取不到词＝这首歌本来就没词，不算写入失败。
     * 写入失败策略（封面与歌词一致）：某项两路（档位要求的那一路／两路）全失败才算该项
     * 失败——跳过档只留痕；URL 档把可回读的引用写入下载记录：封面写 cover 字段（URL），
     * 歌词写 lyric 字段（原文，readSidecarLyric 回读兜底消费）。 */
    private static void downloadSidecars(MusicTrack track, String filePath) {
        if (track == null || filePath == null || filePath.isEmpty()) return;
        File audio = new File(filePath);
        if (!audio.isFile()) return;
        int mode = MusicStore.getDlEmbed();
        boolean wantSidecar = mode == MusicStore.DL_EMBED_SIDECAR || mode == MusicStore.DL_EMBED_BOTH;
        boolean wantEmbed = mode == MusicStore.DL_EMBED_EMBEDDED || mode == MusicStore.DL_EMBED_BOTH;
        if (!wantSidecar && !wantEmbed) return;
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = audio.getParentFile();
        if (parent == null) return;
        File coverFile = new File(parent, base + ".jpg");
        File lrcFile = new File(parent, base + ".lrc");
        boolean hasCoverUrl = track.cover != null
                && (track.cover.startsWith("http://") || track.cover.startsWith("https://"));
        // 素材：封面字节（内嵌要字节、旁路要落文件，一次下载两路共用）／歌词原文（取一次）。
        byte[] coverBytes = null;
        if (hasCoverUrl && (wantEmbed || (wantSidecar && !coverFile.isFile()))) {
            try {
                coverBytes = downloadUrlToBytes(track.cover);
            } catch (Throwable ignored) {
            }
        }
        String lrc = null;
        boolean lrcWanted = wantEmbed || (wantSidecar && !lrcFile.isFile());
        if (lrcWanted) {
            try {
                lrc = MusicRepository.lyric(track);
            } catch (Throwable ignored) {
            }
            if (lrc != null && lrc.trim().isEmpty()) lrc = null;
        }
        // 内嵌路：封面与歌词一次写（写入器按项收素材，null 的项不碰）。
        boolean coverEmbedOk = false;
        boolean lyricEmbedOk = false;
        if (wantEmbed) {
            boolean needCover = hasCoverUrl;
            boolean needLyric = lrc != null;
            if (!needCover && !needLyric) {
                coverEmbedOk = true;
                lyricEmbedOk = true;
            } else {
                boolean ok = false;
                try {
                    ok = EmbeddedTagWriter.embed(audio, needCover ? coverBytes : null, needLyric ? lrc : null);
                } catch (Throwable ignored) {
                }
                if (needCover) coverEmbedOk = ok && coverBytes != null;
                if (needLyric) lyricEmbedOk = ok;
                if (!needCover) coverEmbedOk = true;
                if (!needLyric) lyricEmbedOk = true;
            }
        }
        // 旁路路：失败判定以“文件最终是否存在且非空”为准，不以是否抛异常为准。
        boolean coverSideOk = false;
        if (wantSidecar && hasCoverUrl) {
            try {
                if (coverFile.isFile() && coverFile.length() > 0) {
                    coverSideOk = true;
                } else if (coverBytes != null && coverBytes.length > 0) {
                    writeBytesToFile(coverBytes, coverFile);
                    coverSideOk = coverFile.isFile() && coverFile.length() > 0;
                } else {
                    downloadUrlToFile(track.cover, coverFile);
                    coverSideOk = coverFile.isFile() && coverFile.length() > 0;
                }
            } catch (Throwable ignored) {
                coverSideOk = false;
            }
        } else if (wantSidecar) {
            coverSideOk = true;
        }
        boolean lyricSideOk = false;
        if (wantSidecar) {
            try {
                if (lrcFile.isFile() && lrcFile.length() > 0) {
                    lyricSideOk = true;
                } else if (lrc != null) {
                    writeBytesToFile(lrc.getBytes(java.nio.charset.StandardCharsets.UTF_8), lrcFile);
                    lyricSideOk = lrcFile.isFile() && lrcFile.length() > 0;
                } else {
                    lyricSideOk = true; // 没取到词＝无词可写，不算失败
                }
            } catch (Throwable ignored) {
                lyricSideOk = false;
            }
        }
        // 失败策略：某项在档位要求的每一路都没送达才走策略，封面与歌词同一口径
        //（未被档位要求的那一路不算送达，见各路 Ok 的置位点）。
        boolean coverFailed = hasCoverUrl
                && !((wantEmbed && coverEmbedOk) || (wantSidecar && coverSideOk));
        boolean lyricFailed = lrc != null
                && !((wantEmbed && lyricEmbedOk) || (wantSidecar && lyricSideOk));
        if (coverFailed || lyricFailed) applyEmbedFailPolicy(track, coverFailed, lyricFailed, lrc);
    }

    /** 写入失败策略分流（封面与歌词同走）：跳过档只留痕；URL 档把引用写入下载记录——
     * 封面写 cover（URL 本身），歌词写 lyric（原文；歌词没有 URL 可写，原文就是可回读的留存，
     * readSidecarLyric 旁路文件缺失时回读它）。本方法跑在 save() 的后台任务里，与 save 同线程模型。 */
    private static void applyEmbedFailPolicy(MusicTrack track, boolean coverFailed, boolean lyricFailed, String lrc) {
        SpiderDebug.log("music", "download sidecar write failed cover=%s lyric=%s policy=%s trackId=%s",
                coverFailed, lyricFailed, MusicStore.getDlEmbedFail(), track == null ? "" : track.id);
        if (MusicStore.getDlEmbedFail() != MusicStore.DL_EMBED_FAIL_URL) return;
        try {
            MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
            MusicDownload d = dao.findByTrackId(track.id);
            if (d == null) return;
            boolean dirty = false;
            if (coverFailed && track.cover != null && !track.cover.equals(d.getCover())) {
                d.setCover(track.cover);
                dirty = true;
            }
            if (lyricFailed && lrc != null && !lrc.equals(d.getLyric())) {
                d.setLyric(lrc);
                dirty = true;
            }
            if (dirty) dao.update(d);
        } catch (Throwable ignored) {
        }
    }

    private static void writeBytesToFile(byte[] bytes, File out) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        try {
            fos.write(bytes);
        } finally {
            fos.close();
        }
        if (out.length() == 0) out.delete();
    }

    /** 下载到内存（封面素材，内嵌与旁路共用一次下载）：非 2xx／超 10MB／异常返回 null */
    private static byte[] downloadUrlToBytes(String url) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        try {
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 10L * 1024 * 1024) return null;
                }
            } finally {
                in.close();
            }
            return bos.size() == 0 ? null : bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadUrlToFile(String url, File out) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        try {
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 400) return;
            java.io.InputStream in = conn.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
            } finally {
                fos.close();
                in.close();
            }
            if (out.length() == 0) out.delete();
        } finally {
            conn.disconnect();
        }
    }

    /** 删除本地歌曲：删登记＋删文件（GZ「删除本地文件」口径：音频、封面、歌词一并删除，同名旁路文件同删） */
    public static void deleteLocal(MusicTrack track, Runnable done) {
        deleteLocal(track, ok -> {
            if (done != null) done.run();
        });
    }

    /** 删除本地歌曲（真结果版）：音频文件删不动（被占用／分区存储无权）时不删记录、
     * 回 false，调用方据实提示，不许照旧报「已删除」——旧实现弃 delete() 返回值，
     * 记录先删、文件成孤儿，还会被本地库当设备歌扫回来。无记录且无文件＝本就不存在，回 false。 */
    public static void deleteLocal(MusicTrack track, DataCallback<Boolean> done) {
        if (track == null || !isLocal(track)) {
            if (done != null) post(() -> done.onResult(false));
            return;
        }
        Task.execute(() -> {
            boolean ok = false;
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(track.id);
                String path = d != null && d.getFilePath() != null && !d.getFilePath().isEmpty()
                        ? d.getFilePath() : track.localPath;
                File audio = path == null || path.isEmpty() ? null : new File(path);
                if (audio != null && audio.isFile() && !audio.delete()) {
                    SpiderDebug.log("music", "deleteLocal file delete failed trackId=%s path=%s", track.id, path);
                } else {
                    if (d != null) dao.deleteByTrackId(track.id);
                    ok = d != null || audio != null;
                    // 同名旁路：封面图与歌词文件同删（只删同名文件，不碰其它歌的）
                    if (audio != null) {
                        String name = audio.getName();
                        int dot = name.lastIndexOf('.');
                        if (dot > 0) {
                            String base = name.substring(0, dot);
                            File parent = audio.getParentFile();
                            if (parent != null) {
                                for (String e : new String[]{"lrc", "jpg", "png", "jpeg"}) {
                                    new File(parent, base + "." + e).delete();
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "deleteLocal failed trackId=%s error=%s", track.id, t.getMessage());
            }
            boolean result = ok;
            post(() -> {
                if (done != null) done.onResult(result);
            });
        });
    }

    /** 删除二选一之“仅删除记录”：删登记，文件留着 */
    public static void deleteRecordOnly(MusicTrack track, Runnable done) {
        deleteRecordOnly(track, ok -> {
            if (done != null) done.run();
        });
    }

    /** 仅删除记录（真结果版）：记录确实删掉（或本就没有记录可删时按不存在回 false）才回 true */
    public static void deleteRecordOnly(MusicTrack track, DataCallback<Boolean> done) {
        if (track == null || !isLocal(track)) {
            if (done != null) post(() -> done.onResult(false));
            return;
        }
        Task.execute(() -> {
            boolean ok = false;
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(track.id);
                if (d != null) {
                    dao.deleteByTrackId(track.id);
                    ok = dao.findByTrackId(track.id) == null;
                }
            } catch (Throwable t) {
                SpiderDebug.log("music", "deleteRecordOnly failed trackId=%s error=%s", track.id, t.getMessage());
            }
            boolean result = ok;
            post(() -> {
                if (done != null) done.onResult(result);
            });
        });
    }

    /** 下载任务名（瓜子下载命名三档；默认歌名 - 歌手＝现行行为；无歌手时只剩歌名） */
    private static String downloadFileName(MusicTrack track) {
        String title = track.displayTitle();
        String artist = track.artist == null ? "" : track.artist;
        if (title == null || title.isEmpty()) return artist;
        int naming = MusicStore.getDlNaming();
        if (naming == MusicStore.DL_NAMING_TITLE || artist.isEmpty()) return title;
        if (naming == MusicStore.DL_NAMING_ARTIST_TITLE) return artist + " - " + title;
        return title + " - " + artist;
    }

    /** 当前是不是在用移动数据（WiFi/无网都不算；判仅 Wi-Fi 下载拦不拦用，与服务侧同款判法） */
    public static boolean isCellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 仅 Wi-Fi 下载开后：当前在移动网络下，把进行中任务暂停（只停运行中的，不删不取消） */
    public static void pauseRunningIfCellular() {
        if (!MusicStore.isWifiOnlyDownload() || !isCellular()) return;
        pauseAllLive();
    }

    // ================= 第 7 项尾巴：进行中任务（供下载页） =================

    /** 进行中任务快照：pending 登记＋DownloadManager 实时状态拼出来的行（内存数据，可 UI 线程读） */
    public static class LiveTask {
        public String taskId;
        public String name;
        public String url;
        public String filePath;
        public int status;
        public int progress;
        public long totalBytes;
        public long downloadedBytes;
        public String error;

        /** GZ 行内状态文案映射：等待中／下载中 x%／已暂停／失败／合并中；解析链接中无对应态（取链是同步前置步，不产生任务行，见对照表） */
        public String statusText() {
            switch (status) {
                case DownloadTask.STATUS_WAITING:
                    return "等待中";
                case DownloadTask.STATUS_DOWNLOADING:
                    return "下载中 " + progress + "%";
                case DownloadTask.STATUS_PAUSED:
                    return "已暂停";
                case DownloadTask.STATUS_ERROR:
                    return error == null || error.isEmpty() ? "失败" : "失败：" + error;
                case DownloadTask.STATUS_MERGING:
                    return "合并中...";
                default:
                    return "";
            }
        }
    }

    /** 进行中任务：pending 里还有 DownloadManager 任务的（已完成已被监听除名，不会混进来） */
    public static List<LiveTask> liveTasks() {
        loadPending();
        List<LiveTask> out = new ArrayList<>();
        for (Map.Entry<String, MusicTrack> e : pending.entrySet()) {
            DownloadTask t = DownloadManager.get().getTask(e.getKey());
            if (t == null || t.getStatus() == DownloadTask.STATUS_COMPLETED) continue;
            LiveTask lt = new LiveTask();
            lt.taskId = t.getId();
            lt.name = t.getName();
            lt.url = t.getUrl();
            lt.filePath = t.getFilePath();
            lt.status = t.getStatus();
            lt.progress = t.getProgress();
            lt.totalBytes = t.getTotalBytes();
            lt.downloadedBytes = t.getDownloadedBytes();
            lt.error = t.getError();
            out.add(lt);
        }
        return out;
    }

    /** 取消单个进行中任务（DownloadManager 删任务＋pending 除名） */
    public static void cancelLive(String taskId) {
        if (taskId == null) return;
        DownloadManager.get().delete(taskId);
        pending.remove(taskId);
        persistPending();
    }

    /** 全部暂停：只动等待中／下载中的任务 */
    public static void pauseAllLive() {
        for (LiveTask lt : liveTasks()) {
            if (lt.status == DownloadTask.STATUS_WAITING || lt.status == DownloadTask.STATUS_DOWNLOADING) {
                DownloadManager.get().pause(lt.taskId);
            }
        }
    }

    /** 继续：只动已暂停的任务 */
    public static void resumeAllLive() {
        for (LiveTask lt : liveTasks()) {
            if (lt.status == DownloadTask.STATUS_PAUSED) DownloadManager.get().resume(lt.taskId);
        }
    }

    /** 取消：删全部进行中任务（DownloadManager.delete 连未完成文件一起删）＋pending 除名 */
    public static void cancelAllLive() {
        for (LiveTask lt : liveTasks()) {
            DownloadManager.get().delete(lt.taskId);
            pending.remove(lt.taskId);
        }
        persistPending();
    }

    /** 清除失败：只删失败态任务＋pending 除名 */
    public static void clearFailedLive() {
        for (LiveTask lt : liveTasks()) {
            if (lt.status == DownloadTask.STATUS_ERROR) {
                DownloadManager.get().delete(lt.taskId);
                pending.remove(lt.taskId);
            }
        }
        persistPending();
    }
}
