package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.MusicFavorite;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.MusicPlaylistItem;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐曲库：收藏、自建歌单。Room 存取，子线程跑，主线程回。
 */
public class MusicLibrary {

    public interface DataCallback<T> {
        void onResult(T data);

        /** 写库失败通道（默认空实现，旧调用方不受影响；写方法失败时走这里而不是假回调成功）。 */
        default void onError(Throwable t) {
        }
    }

    /**
     * 写库失败统一出口：不许再 catch 后照常回调成功装作存上了。
     * 失败时提示用户、且不发成功回调（调用方的界面保持旧数据＝真状态）。
     */
    private static void postWriteError() {
        post(() -> Notify.show("曲库写入失败，请重试"));
    }

    private static MusicTrack toTrack(MusicFavorite f) {
        MusicTrack t = new MusicTrack();
        t.id = f.getTrackId();
        t.title = f.getTitle();
        t.artist = f.getArtist();
        t.album = f.getAlbum();
        t.duration = f.getDuration();
        t.cover = f.getCover();
        t.platform = f.getPlatform();
        t.rawJson = f.getRawJson();
        // 已下载歌取链只认 localPath：不回读就是收藏里的死条目（点开解析必失败）
        t.localPath = f.getLocalPath();
        t.favoriteAddedTime = f.getCreateTime();
        return t;
    }

    private static MusicTrack toTrack(MusicPlaylistItem i) {
        MusicTrack t = new MusicTrack();
        t.id = i.getTrackId();
        t.title = i.getTitle();
        t.artist = i.getArtist();
        t.album = i.getAlbum();
        t.duration = i.getDuration();
        t.cover = i.getCover();
        t.platform = i.getPlatform();
        t.rawJson = i.getRawJson();
        // 同收藏：已下载歌不回读 localPath 就是歌单里的死条目
        t.localPath = i.getLocalPath();
        t.playlistAddedTime = i.getCreateTime();
        return t;
    }

    private static MusicPlaylistItem toItem(long playlistId, MusicTrack track, int position, long now) {
        MusicPlaylistItem i = new MusicPlaylistItem();
        i.setPlaylistId(playlistId);
        i.setTrackId(track.id);
        i.setTitle(track.title);
        i.setArtist(track.artist);
        i.setAlbum(track.album);
        i.setDuration(track.duration);
        i.setCover(track.cover);
        i.setPlatform(track.platform);
        i.setRawJson(track.rawJson);
        i.setLocalPath(track.localPath);
        i.setPosition(position);
        i.setCreateTime(now);
        return i;
    }

    private static void post(Runnable r) {
        App.post(r);
    }

    // ================= 收藏 =================

    public static void isFavorite(String trackId, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean fav = false;
            try {
                fav = trackId != null && AppDatabase.get().getMusicFavoriteDao().find(trackId) != null;
            } catch (Throwable ignored) {
            }
            boolean f = fav;
            post(() -> cb.onResult(f));
        });
    }

    /** 平台归一：null 与空串同义（存量旧行 platform 可能为空） */
    private static String normPlatform(String platform) {
        return platform == null ? "" : platform;
    }

    /**
     * 按复合身份找收藏行：先 (trackId, platform) 精确找；找不到再按裸 id 找一行、
     * 仅当其 platform 与给定值归一后相等才认（同 id 异来源的那行是另一首歌，不许认错）。
     */
    private static MusicFavorite findFavoriteRow(String trackId, String platform) {
        if (trackId == null) return null;
        com.fongmi.android.tv.db.dao.MusicFavoriteDao dao = AppDatabase.get().getMusicFavoriteDao();
        MusicFavorite row = dao.find(trackId, platform);
        if (row != null) return row;
        MusicFavorite any = dao.find(trackId);
        if (any != null && normPlatform(any.getPlatform()).equals(normPlatform(platform))) return any;
        return null;
    }

    /** 复合身份版：调用方手里有整首歌时用它，裸 id 版会把同 id 异来源的歌误判已收藏 */
    public static void isFavorite(MusicTrack track, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean fav = false;
            try {
                fav = track != null && findFavoriteRow(track.id, track.platform) != null;
            } catch (Throwable ignored) {
            }
            boolean f = fav;
            post(() -> cb.onResult(f));
        });
    }

    public static void setFavorite(MusicTrack track, boolean fav, Runnable done) {
        Task.execute(() -> {
            boolean ok = true;
            try {
                if (track != null && track.id != null) {
                    if (fav) {
                        MusicFavorite f = new MusicFavorite();
                        f.setTrackId(track.id);
                        f.setTitle(track.title);
                        f.setArtist(track.artist);
                        f.setAlbum(track.album);
                        f.setDuration(track.duration);
                        f.setCover(track.cover);
                        f.setPlatform(track.platform);
                        f.setRawJson(track.rawJson);
                        // 已下载歌必须落 localPath，回读时 LocalMusicSource 只认它取链
                        f.setLocalPath(track.localPath);
                        f.setCreateTime(System.currentTimeMillis());
                        AppDatabase.get().getMusicFavoriteDao().insert(f);
                    } else {
                        // 复合身份删：先按 (trackId, platform) 找到本人的那一行、按主键删；
                        // 裸 id 删会连坐同 id 的别家歌，按主键删也不会被 NULL platform 卡住
                        MusicFavorite row = findFavoriteRow(track.id, track.platform);
                        if (row != null) AppDatabase.get().getMusicFavoriteDao().deleteById(row.getId());
                    }
                }
            } catch (Throwable t) {
                ok = false;
            }
            boolean success = ok;
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                if (done != null) done.run();
            });
        });
    }

    public static void favorites(DataCallback<List<MusicTrack>> cb) {
        Task.execute(() -> {
            List<MusicTrack> list = new ArrayList<>();
            try {
                for (MusicFavorite f : AppDatabase.get().getMusicFavoriteDao().findAll()) list.add(toTrack(f));
            } catch (Throwable ignored) {
            }
            post(() -> cb.onResult(list));
        });
    }

    public static void favoriteCount(DataCallback<Integer> cb) {
        Task.execute(() -> {
            int n = 0;
            try {
                n = AppDatabase.get().getMusicFavoriteDao().count();
            } catch (Throwable ignored) {
            }
            int c = n;
            post(() -> cb.onResult(c));
        });
    }

    // ================= 歌单 =================

    public static void playlists(DataCallback<List<MusicPlaylist>> cb) {
        Task.execute(() -> {
            List<MusicPlaylist> list = new ArrayList<>();
            try {
                list = AppDatabase.get().getMusicPlaylistDao().findAll();
            } catch (Throwable ignored) {
            }
            List<MusicPlaylist> r = list;
            post(() -> cb.onResult(r));
        });
    }

    public static void createPlaylist(String name, DataCallback<MusicPlaylist> cb) {
        Task.execute(() -> {
            MusicPlaylist p = new MusicPlaylist();
            Throwable error = null;
            try {
                p.setName(name == null || name.trim().isEmpty() ? "新建歌单" : name.trim());
                long now = System.currentTimeMillis();
                p.setCreateTime(now);
                p.setUpdateTime(now);
                long id = AppDatabase.get().getMusicPlaylistDao().insert(p);
                p.setId(id);
            } catch (Throwable t) {
                error = t;
            }
            Throwable err = error;
            post(() -> {
                if (err != null) {
                    cb.onError(err);
                    postWriteError();
                    return;
                }
                cb.onResult(p);
            });
        });
    }

    public static void renamePlaylist(long id, String name, Runnable done) {
        Task.execute(() -> {
            boolean ok = true;
            try {
                MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(id);
                if (p != null) {
                    p.setName(name);
                    p.setUpdateTime(System.currentTimeMillis());
                    AppDatabase.get().getMusicPlaylistDao().update(p);
                }
            } catch (Throwable t) {
                ok = false;
            }
            boolean success = ok;
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                if (done != null) done.run();
            });
        });
    }

    public static void deletePlaylist(long id, Runnable done) {
        Task.execute(() -> {
            boolean ok = true;
            try {
                AppDatabase.get().getMusicPlaylistItemDao().deleteByPlaylist(id);
                AppDatabase.get().getMusicPlaylistDao().delete(id);
            } catch (Throwable t) {
                ok = false;
            }
            boolean success = ok;
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                if (done != null) done.run();
            });
        });
    }

    public static void playlistTracks(long playlistId, DataCallback<List<MusicTrack>> cb) {
        Task.execute(() -> {
            List<MusicTrack> list = new ArrayList<>();
            try {
                for (MusicPlaylistItem i : AppDatabase.get().getMusicPlaylistItemDao().findByPlaylist(playlistId)) list.add(toTrack(i));
            } catch (Throwable ignored) {
            }
            post(() -> cb.onResult(list));
        });
    }

    public static void playlistTrackCount(long playlistId, DataCallback<Integer> cb) {
        Task.execute(() -> {
            int n = 0;
            try {
                n = AppDatabase.get().getMusicPlaylistItemDao().countByPlaylist(playlistId);
            } catch (Throwable ignored) {
            }
            int c = n;
            post(() -> cb.onResult(c));
        });
    }

    /** 加歌进歌单，已在则返回 false */
    public static void addToPlaylist(long playlistId, MusicTrack track, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean added = false;
            Throwable error = null;
            try {
                Integer max = AppDatabase.get().getMusicPlaylistItemDao().maxPosition(playlistId);
                MusicPlaylistItem i = toItem(playlistId, track, max == null ? 0 : max + 1, System.currentTimeMillis());
                added = AppDatabase.get().getMusicPlaylistItemDao().insert(i) != -1;
                MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(playlistId);
                if (p != null) {
                    p.setUpdateTime(System.currentTimeMillis());
                    AppDatabase.get().getMusicPlaylistDao().update(p);
                }
            } catch (Throwable t) {
                error = t;
            }
            boolean r = added;
            Throwable err = error;
            post(() -> {
                // 与其余写路同口径：真异常走 onError＋写入失败提示，不许吞成假的 false
                // （false 只留给「已在歌单」这种业务结果）
                if (err != null) {
                    cb.onError(err);
                    postWriteError();
                    return;
                }
                cb.onResult(r);
            });
        });
    }

    /** 换源/刷新：把歌单里某首原位换成另一首（位置与加入时间保留，歌单更新时间刷新） */
    public static void replaceTrackInPlaylist(long playlistId, String oldTrackId, MusicTrack replacement, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean ok = false;
            Throwable error = null;
            try {
                if (replacement != null && replacement.id != null) {
                    MusicPlaylistItem found = null;
                    for (MusicPlaylistItem i : AppDatabase.get().getMusicPlaylistItemDao().findByPlaylist(playlistId)) {
                        if (oldTrackId != null && oldTrackId.equals(i.getTrackId())) {
                            found = i;
                            break;
                        }
                    }
                    if (found != null) {
                        // 按行主键删：同歌单里同 trackId 异来源可并存，按 (playlistId, trackId) 删会误删别家那行
                        AppDatabase.get().getMusicPlaylistItemDao().deleteById(found.getId());
                        AppDatabase.get().getMusicPlaylistItemDao().insert(
                                toItem(playlistId, replacement, found.getPosition(), found.getCreateTime()));
                        MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(playlistId);
                        if (p != null) {
                            p.setUpdateTime(System.currentTimeMillis());
                            AppDatabase.get().getMusicPlaylistDao().update(p);
                        }
                        ok = true;
                    }
                }
            } catch (Throwable t) {
                error = t;
            }
            boolean r = ok;
            Throwable err = error;
            post(() -> {
                // 与其余写路同口径：真异常走 onError＋写入失败提示，不许吞成假的 false
                // （false 只留给「没找到原曲/替换曲无效」这种业务结果）
                if (err != null) {
                    cb.onError(err);
                    postWriteError();
                    return;
                }
                cb.onResult(r);
            });
        });
    }

    /** 重新匹配封面：只改歌单里某首的封面地址，其它字段不动 */
    public static void updateTrackCover(long playlistId, String trackId, String cover, Runnable done) {
        Task.execute(() -> {
            boolean ok = true;
            try {
                for (MusicPlaylistItem i : AppDatabase.get().getMusicPlaylistItemDao().findByPlaylist(playlistId)) {
                    if (trackId != null && trackId.equals(i.getTrackId())) {
                        i.setCover(cover);
                        AppDatabase.get().getMusicPlaylistItemDao().update(i);
                        break;
                    }
                }
            } catch (Throwable t) {
                ok = false;
            }
            boolean success = ok;
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                if (done != null) done.run();
            });
        });
    }

    /** 导入结果：建好的歌单＋真实落库的条数（歌单内重复歌会被唯一索引跳过） */
    public static class ImportResult {
        public MusicPlaylist playlist;
        public int inserted;
    }

    /** 导入外部歌单：建新歌单＋整批写入，position 按传入顺序；主线程回结果 */
    public static void importPlaylist(String name, List<MusicTrack> tracks, DataCallback<ImportResult> cb) {
        Task.execute(() -> {
            ImportResult r = new ImportResult();
            Throwable error = null;
            try {
                long now = System.currentTimeMillis();
                MusicPlaylist p = new MusicPlaylist();
                p.setName(name == null || name.trim().isEmpty() ? "导入的歌单" : name.trim());
                p.setCreateTime(now);
                p.setUpdateTime(now);
                long pid = AppDatabase.get().getMusicPlaylistDao().insert(p);
                p.setId(pid);
                r.playlist = p;
                if (pid > 0 && tracks != null && !tracks.isEmpty()) {
                    List<MusicPlaylistItem> items = new ArrayList<>();
                    for (int i = 0; i < tracks.size(); i++) {
                        MusicTrack t = tracks.get(i);
                        if (t == null || t.id == null) continue;
                        items.add(toItem(pid, t, items.size(), now));
                    }
                    List<Long> ids = AppDatabase.get().getMusicPlaylistItemDao().insert(items);
                    int n = 0;
                    for (Long id : ids) if (id != null && id != -1) n++;
                    r.inserted = n;
                }
            } catch (Throwable t) {
                error = t;
            }
            Throwable err = error;
            post(() -> {
                if (err != null) {
                    cb.onError(err);
                    postWriteError();
                    return;
                }
                cb.onResult(r);
            });
        });
    }

    public static void removeFromPlaylist(long itemId, Runnable done) {
        Task.execute(() -> {
            boolean ok = true;
            try {
                AppDatabase.get().getMusicPlaylistItemDao().deleteById(itemId);
            } catch (Throwable t) {
                ok = false;
            }
            boolean success = ok;
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                if (done != null) done.run();
            });
        });
    }
}
