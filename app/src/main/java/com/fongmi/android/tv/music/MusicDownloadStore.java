package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicDownload;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.MusicDownloadDao;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.download.DownloadManager;
import com.fongmi.android.tv.utils.download.DownloadTask;

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

    /** 本地曲目 id：local:原id */
    public static String localId(MusicTrack track) {
        return LocalMusicSource.PLATFORM + ":" + (track == null || track.id == null ? "" : track.id);
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
        t.platform = LocalMusicSource.PLATFORM;
        t.localPath = d.getFilePath();
        return t;
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
            List<MusicTrack> list = new ArrayList<>();
            try {
                for (MusicDownload d : AppDatabase.get().getMusicDownloadDao().findAll()) list.add(toTrack(d));
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

    private static synchronized void ensureListener() {
        if (listenerAdded) return;
        listenerAdded = true;
        DownloadManager.get().addListener(task -> {
            if (task.getStatus() == DownloadTask.STATUS_COMPLETED) {
                MusicTrack t = pending.remove(task.getId());
                if (t != null && task.getFilePath() != null && !task.getFilePath().isEmpty()) {
                    save(t, task.getFilePath());
                }
            } else if (task.getStatus() == DownloadTask.STATUS_ERROR) {
                // 第 7 项尾巴：失败任务留待下载页“清除失败”，不静默丢掉（失败行＋清除失败就靠它）
                // （重试时 downloadWithQuality 会先清掉同曲的失败旧任务，不会一曲两行）
            }
        });
    }

    /** 歌曲行 ⋮ 菜单「下载」入口（固定／跟随模式）：取直链 → 交 DownloadManager 下载。
     * 每次询问模式下本入口无 Activity 弹不了选档窗，不静默按固定档下载，只提示后返回；
     * 逐首选档走 downloadWithQuality（调用点在 mobile 侧弹 MusicOptionsDialog 后调入），
     * 播放页 downloadCurrent 的 ASK 分支见协调员补丁规格（本文件在 main 源集，不引 mobile 弹窗，
     * 否则 leanback 变体编不过）。 */
    public static void download(MusicTrack track) {
        if (track == null || isLocal(track)) return;
        if (MusicStore.getDownloadMode() == MusicStore.DL_MODE_ASK) {
            post(() -> Notify.show(R.string.music_dl_ask_no_activity));
            return;
        }
        downloadWithQuality(track, MusicStore.getDownloadQuality());
    }

    /** 逐首选档后的下载核心（ASK 弹窗的落点；固定／跟随模式由 download 转调）：取指定档直链 → 交 DownloadManager 下载 */
    public static void downloadWithQuality(MusicTrack track, int quality) {
        if (track == null || isLocal(track)) return;
        ensureListener();
        Task.execute(() -> {
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(localId(track));
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
            for (Map.Entry<String, MusicTrack> e : new ArrayList<>(pending.entrySet())) {
                if (e.getValue() != null && localId(track).equals(e.getValue().id)) {
                    DownloadTask old = DownloadManager.get().getTask(e.getKey());
                    if (old == null || old.getStatus() == DownloadTask.STATUS_ERROR) {
                        if (old != null) DownloadManager.get().delete(e.getKey());
                        pending.remove(e.getKey());
                    }
                }
            }
            for (DownloadTask t : DownloadManager.get().getTasks()) {
                if (url.equals(t.getUrl()) && (t.getStatus() == DownloadTask.STATUS_DOWNLOADING
                        || t.getStatus() == DownloadTask.STATUS_WAITING)) {
                    post(() -> Notify.show(R.string.music_downloading));
                    return;
                }
            }
            String name = track.displayTitle();
            if (track.artist != null && !track.artist.isEmpty()) name += " - " + track.artist;
            MusicTrack snapshot = copyForLocal(track);
            final String taskUrl = url;
            final String taskName = name;
            post(() -> {
                try {
                    DownloadTask task = DownloadManager.get().add(taskUrl, taskName);
                    if (task != null) pending.put(task.getId(), snapshot);
                } catch (Throwable e) {
                    Notify.show(R.string.music_download_failed);
                }
            });
        });
    }

    /** 下载完成登记：静默入库（DownloadManager 已提示"下载完成"） */
    private static void save(MusicTrack track, String filePath) {
        Task.execute(() -> {
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(track.id);
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
                if (d.getId() == 0) dao.insert(d);
                else dao.update(d);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 删除本地歌曲：删登记＋删文件（GZ「删除本地文件」口径：音频、封面、歌词一并删除，同名旁路文件同删） */
    public static void deleteLocal(MusicTrack track, Runnable done) {
        if (track == null || !isLocal(track)) {
            if (done != null) post(done);
            return;
        }
        Task.execute(() -> {
            try {
                MusicDownloadDao dao = AppDatabase.get().getMusicDownloadDao();
                MusicDownload d = dao.findByTrackId(track.id);
                if (d != null) {
                    dao.deleteByTrackId(track.id);
                    if (d.getFilePath() != null && !d.getFilePath().isEmpty()) {
                        File audio = new File(d.getFilePath());
                        audio.delete();
                        // 同名旁路：封面图与歌词文件同删（只删同名文件，不碰其它歌的）
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
            } catch (Throwable ignored) {
            }
            post(() -> {
                if (done != null) done.run();
            });
        });
    }

    /** 删除二选一之“仅删除记录”：删登记，文件留着 */
    public static void deleteRecordOnly(MusicTrack track, Runnable done) {
        if (track == null || !isLocal(track)) {
            if (done != null) post(done);
            return;
        }
        Task.execute(() -> {
            try {
                AppDatabase.get().getMusicDownloadDao().deleteByTrackId(track.id);
            } catch (Throwable ignored) {
            }
            post(() -> {
                if (done != null) done.run();
            });
        });
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
    }

    /** 清除失败：只删失败态任务＋pending 除名 */
    public static void clearFailedLive() {
        for (LiveTask lt : liveTasks()) {
            if (lt.status == DownloadTask.STATUS_ERROR) {
                DownloadManager.get().delete(lt.taskId);
                pending.remove(lt.taskId);
            }
        }
    }
}
