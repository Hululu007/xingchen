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
                pending.remove(task.getId());
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

    /** 删除本地歌曲：删登记＋删文件 */
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
                        new File(d.getFilePath()).delete();
                    }
                }
            } catch (Throwable ignored) {
            }
            post(() -> {
                if (done != null) done.run();
            });
        });
    }
}
