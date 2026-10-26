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

    /**
     * 收藏变化订阅（共用层）：setFavorite 写库真落盘后才在主线程广播，
     * 订阅方据此重拉/重查。各页不许再在 setFavorite 刚发出时就抢跑重拉——
     * Task 是 5 线程池，写删与重拉读会并行，读赢了就是旧账
     * （2026-10-26 真机实锤：收藏页取消收藏、吐司报了已取消、歌还赖在列表里）。
     */
    public interface FavoritesListener {
        void onFavoritesChanged();
    }

    private static final java.util.Set<FavoritesListener> FAVORITES_LISTENERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void addFavoritesListener(FavoritesListener l) {
        if (l != null) FAVORITES_LISTENERS.add(l);
    }

    public static void removeFavoritesListener(FavoritesListener l) {
        if (l != null) FAVORITES_LISTENERS.remove(l);
    }

    /** 只许在写库成功的主线程回调里调（调用点在 setFavorite 的 post 块内）。 */
    private static void notifyFavoritesChanged() {
        for (FavoritesListener l : new java.util.ArrayList<>(FAVORITES_LISTENERS)) {
            try {
                l.onFavoritesChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 歌单变化订阅（共用层，与收藏同口径）：歌单写库（删单）真落盘后才在主线程广播，
     * 订阅方据此重拉。各页不许再在删除刚发出时就抢跑重拉——Task 是 5 线程池，
     * 写删与重拉读并行时读赢了就是旧账（歌单列表批量删除竞态与收藏案 02779cdd 同型）。
     */
    public interface PlaylistsListener {
        void onPlaylistsChanged();
    }

    private static final java.util.Set<PlaylistsListener> PLAYLISTS_LISTENERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void addPlaylistsListener(PlaylistsListener l) {
        if (l != null) PLAYLISTS_LISTENERS.add(l);
    }

    public static void removePlaylistsListener(PlaylistsListener l) {
        if (l != null) PLAYLISTS_LISTENERS.remove(l);
    }

    /** 只许在歌单写库成功的主线程回调里调。 */
    private static void notifyPlaylistsChanged() {
        for (PlaylistsListener l : new java.util.ArrayList<>(PLAYLISTS_LISTENERS)) {
            try {
                l.onPlaylistsChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 批量写收藏的结果：成功/失败双计数，完成必回一次，不许靠单首 done 递减挂死。 */
    public static class BatchResult {
        public int succeeded;
        public int failed;
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
        // 插件段落列（与收藏同口径）：唯一键带插件段后，同 id 同 platform 异插件不再互相顶掉
        i.setPlugin(normPlugin(MusicIdentity.pluginOf(track.rawJson)));
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

    /** 插件段归一：无戳存空串，与落列口径一致 */
    private static String normPlugin(String plugin) {
        return plugin == null ? "" : plugin;
    }

    /** 某行与给定插件戳是否同一来源：行 plugin 列有值认列、没值（43 版前存量行）认 rawJson 戳 */
    private static boolean samePlugin(MusicFavorite row, String plugin) {
        String rowPlugin = row.getPlugin();
        if (rowPlugin == null) rowPlugin = MusicIdentity.pluginOf(row.getRawJson());
        return normPlugin(rowPlugin).equals(normPlugin(plugin));
    }

    /**
     * 按三段身份找收藏行（扫描 M3）：先 (trackId, platform, plugin) 精确找；再退
     * (trackId, platform)／裸 id 找候选行，但必须 platform 归一相等且插件戳同源才认
     * （同 id 异插件的那行是另一首歌，不许认错）；认到的存量行顺手回填 plugin 列。
     */
    private static MusicFavorite findFavoriteRow(String trackId, String platform, String rawJson) {
        if (trackId == null) return null;
        String plugin = MusicIdentity.pluginOf(rawJson);
        com.fongmi.android.tv.db.dao.MusicFavoriteDao dao = AppDatabase.get().getMusicFavoriteDao();
        if (platform != null) {
            MusicFavorite row = dao.find(trackId, platform, normPlugin(plugin));
            if (row != null) return row;
            MusicFavorite byPlatform = dao.find(trackId, platform);
            if (byPlatform != null && samePlugin(byPlatform, plugin)) return backfillPlugin(dao, byPlatform, plugin);
        }
        MusicFavorite any = dao.find(trackId);
        if (any != null && normPlatform(any.getPlatform()).equals(normPlatform(platform)) && samePlugin(any, plugin)) return backfillPlugin(dao, any, plugin);
        return null;
    }

    private static MusicFavorite backfillPlugin(com.fongmi.android.tv.db.dao.MusicFavoriteDao dao, MusicFavorite row, String plugin) {
        if (row.getPlugin() == null) {
            try {
                row.setPlugin(normPlugin(plugin));
                dao.update(row);
            } catch (Throwable ignored) {
            }
        }
        return row;
    }

    /** 复合身份版：调用方手里有整首歌时用它，裸 id 版会把同 id 异来源的歌误判已收藏 */
    public static void isFavorite(MusicTrack track, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean fav = false;
            try {
                fav = track != null && findFavoriteRow(track.id, track.platform, track.rawJson) != null;
            } catch (Throwable ignored) {
            }
            boolean f = fav;
            post(() -> cb.onResult(f));
        });
    }

    /** 单首收藏写（同步版，只许在 Task 线程内调）：真落盘才算成功，口径与 setFavorite 原实现逐项同。
     *  扫描 M9：track 为空/id 为空即失败，不许什么都没写却报成功；
     *  扫描 M3：真插看 insert 返回 -1 判失败（跨插件同 id 被唯一键吞掉不许报成功）。 */
    private static boolean setFavoriteSync(MusicTrack track, boolean fav) {
        boolean ok = track != null && track.id != null;
        try {
            if (ok) {
                if (fav) {
                    // 已收藏同首（三段身份命中）是幂等成功，不重复插
                    if (findFavoriteRow(track.id, track.platform, track.rawJson) == null) {
                        MusicFavorite f = new MusicFavorite();
                        f.setTrackId(track.id);
                        f.setTitle(track.title);
                        f.setArtist(track.artist);
                        f.setAlbum(track.album);
                        f.setDuration(track.duration);
                        f.setCover(track.cover);
                        f.setPlatform(track.platform);
                        f.setPlugin(normPlugin(MusicIdentity.pluginOf(track.rawJson)));
                        f.setRawJson(track.rawJson);
                        // 已下载歌必须落 localPath，回读时 LocalMusicSource 只认它取链
                        f.setLocalPath(track.localPath);
                        f.setCreateTime(System.currentTimeMillis());
                        Long rowId = AppDatabase.get().getMusicFavoriteDao().insert(f);
                        if (rowId == null || rowId == -1L) ok = false;
                    }
                } else {
                    // 三段身份删：先找到本人的那一行、按主键删；
                    // 裸 id 删会连坐同 id 的别家歌，按主键删也不会被 NULL platform 卡住
                    MusicFavorite row = findFavoriteRow(track.id, track.platform, track.rawJson);
                    if (row != null) AppDatabase.get().getMusicFavoriteDao().deleteById(row.getId());
                }
            }
        } catch (Throwable t) {
            ok = false;
        }
        return ok;
    }

    public static void setFavorite(MusicTrack track, boolean fav, Runnable done) {
        Task.execute(() -> {
            boolean success = setFavoriteSync(track, fav);
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                // 先广播后 done：订阅方（收藏页/播放页爱心）重拉时写已落盘，读到的必是新账
                notifyFavoritesChanged();
                if (done != null) done.run();
            });
        });
    }

    /**
     * 批量写收藏（共用层完成追踪）：一个 Task 内串行逐首写，成功/失败逐首计数，
     * 全部跑完才在主线程回一次 BatchResult——任一首失败只进 failed 计数，不会像旧口径
     * 靠每首 done 回调递减那样永不归零（收藏多选删除、登录导入喜欢歌单同步收藏同病）。
     * 有成功落盘才广播收藏变化（订阅方重拉读到的必是新账）。
     */
    public static void setFavoriteBatch(List<MusicTrack> tracks, boolean fav, DataCallback<BatchResult> cb) {
        Task.execute(() -> {
            BatchResult r = new BatchResult();
            if (tracks != null) {
                for (MusicTrack t : tracks) {
                    if (setFavoriteSync(t, fav)) r.succeeded++;
                    else r.failed++;
                }
            }
            post(() -> {
                if (r.succeeded > 0) notifyFavoritesChanged();
                if (cb != null) cb.onResult(r);
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

    /** 删单同步版（只许在 Task 线程内调）：Room 两表之外，附属一并清——自定义封面文件
     *  与映射、简介、收藏标记（MusicStore 三处 Prefers），不清就是孤儿残留、id 复用还会串单。 */
    private static boolean deletePlaylistSync(long id) {
        try {
            AppDatabase.get().getMusicPlaylistItemDao().deleteByPlaylist(id);
            AppDatabase.get().getMusicPlaylistDao().delete(id);
        } catch (Throwable t) {
            return false;
        }
        try {
            String cover = MusicStore.getSheetCover(id);
            if (cover != null && !cover.isEmpty()) {
                try {
                    new java.io.File(cover).delete();
                } catch (Throwable ignored) {
                }
            }
            MusicStore.setSheetCover(id, null);
            MusicStore.setSheetDesc(id, null);
            MusicStore.setSheetFav(id, false);
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void deletePlaylist(long id, Runnable done) {
        Task.execute(() -> {
            boolean success = deletePlaylistSync(id);
            post(() -> {
                if (!success) {
                    postWriteError();
                    return;
                }
                // 先广播后 done：订阅方重拉时删已落盘，读到的必是新账
                notifyPlaylistsChanged();
                if (done != null) done.run();
            });
        });
    }

    /**
     * 批量删歌单（共用层完成追踪）：一个 Task 内串行逐张删，全部落盘后才广播＋回一次结果。
     * 旧口径逐张发 deletePlaylist(id,null) 后立刻重拉，5 线程池里读可能赢过未落盘的删除、
     * 歌单删了又回列表且无第二次重拉（与收藏竞态 02779cdd 同型）。失败张数进 BatchResult.failed，
     * 调用方据此如实提示，不许把部分失败报成全删完。
     */
    public static void deletePlaylists(java.util.Collection<Long> ids, DataCallback<BatchResult> cb) {
        Task.execute(() -> {
            BatchResult r = new BatchResult();
            if (ids != null) {
                for (Long id : ids) {
                    if (id != null && deletePlaylistSync(id)) r.succeeded++;
                    else r.failed++;
                }
            }
            post(() -> {
                if (r.succeeded > 0) notifyPlaylistsChanged();
                if (cb != null) cb.onResult(r);
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
                // 更新时间只在真加进去时刷：「已在歌单」（insert 撞唯一键 -1）没改动任何行，
                // 旧实现照样刷时间，歌单列表排序与「更新于」被一次未成功的添加改掉
                if (added) {
                    MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(playlistId);
                    if (p != null) {
                        p.setUpdateTime(System.currentTimeMillis());
                        AppDatabase.get().getMusicPlaylistDao().update(p);
                    }
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

    /** 歌单条目与目标歌是否同一首：三段身份（trackId＋platform＋插件戳）全对上才认
     *  （扫描 M4：旧实现只比裸 trackId 首命中，同歌单跨源同 id 时换错/改错行） */
    private static boolean sameItemIdentity(MusicPlaylistItem i, MusicTrack t) {
        if (i == null || t == null || t.id == null || !t.id.equals(i.getTrackId())) return false;
        if (!normPlatform(i.getPlatform()).equals(normPlatform(t.platform))) return false;
        return normPlugin(MusicIdentity.pluginOf(i.getRawJson())).equals(normPlugin(MusicIdentity.pluginOf(t.rawJson)));
    }

    /** 换源/刷新：把歌单里某首原位换成另一首（位置与加入时间保留，歌单更新时间刷新） */
    public static void replaceTrackInPlaylist(long playlistId, MusicTrack oldTrack, MusicTrack replacement, DataCallback<Boolean> cb) {
        Task.execute(() -> {
            boolean ok = false;
            Throwable error = null;
            try {
                if (replacement != null && replacement.id != null) {
                    MusicPlaylistItem found = null;
                    for (MusicPlaylistItem i : AppDatabase.get().getMusicPlaylistItemDao().findByPlaylist(playlistId)) {
                        if (sameItemIdentity(i, oldTrack)) {
                            found = i;
                            break;
                        }
                    }
                    if (found != null) {
                        // 按行主键删：同歌单里同 trackId 异来源可并存，按 (playlistId, trackId) 删会误删别家那行
                        AppDatabase.get().getMusicPlaylistItemDao().deleteById(found.getId());
                        Long newId = AppDatabase.get().getMusicPlaylistItemDao().insert(
                                toItem(playlistId, replacement, found.getPosition(), found.getCreateTime()));
                        if (newId == null || newId == -1L) {
                            // 撞唯一键（替换曲已在本歌单另一行）＝新曲没进：把原行原样插回，
                            // 不许丢歌还报成功（旧实现不查返回值，原曲删了、新曲没进仍报「已换源」）
                            try {
                                AppDatabase.get().getMusicPlaylistItemDao().insert(found);
                            } catch (Throwable ignored) {
                            }
                        } else {
                            MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(playlistId);
                            if (p != null) {
                                p.setUpdateTime(System.currentTimeMillis());
                                AppDatabase.get().getMusicPlaylistDao().update(p);
                            }
                            ok = true;
                        }
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

    /** 重新匹配封面：只改歌单里某首的封面地址，其它字段不动；
     *  扫描 M9：目标行找不到也是失败——旧实现 ok 恒 true，没改任何行也照跑 done 报「封面已更新」 */
    public static void updateTrackCover(long playlistId, MusicTrack track, String cover, Runnable done) {
        Task.execute(() -> {
            boolean ok = false;
            try {
                for (MusicPlaylistItem i : AppDatabase.get().getMusicPlaylistItemDao().findByPlaylist(playlistId)) {
                    if (sameItemIdentity(i, track)) {
                        i.setCover(cover);
                        AppDatabase.get().getMusicPlaylistItemDao().update(i);
                        ok = true;
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

    /**
     * 导入外部歌单：建新歌单＋整批写入，position 按传入顺序；主线程回结果。
     * 原子性：条目批写抛错（或歌单行没落下）时在同一 Task 内回滚——删已写的条目与
     * 歌单行再走 onError，不许留一张空「导入的歌单」残留在列表（旧实现两步无回收，
     * 错误路径无人清；成功路径 inserted<=0 的清理由调用方做，与此不冲突）。
     */
    public static void importPlaylist(String name, List<MusicTrack> tracks, DataCallback<ImportResult> cb) {
        Task.execute(() -> {
            ImportResult r = new ImportResult();
            Throwable error = null;
            long pid = -1;
            try {
                long now = System.currentTimeMillis();
                MusicPlaylist p = new MusicPlaylist();
                p.setName(name == null || name.trim().isEmpty() ? "导入的歌单" : name.trim());
                p.setCreateTime(now);
                p.setUpdateTime(now);
                pid = AppDatabase.get().getMusicPlaylistDao().insert(p);
                if (pid <= 0) throw new IllegalStateException("playlist insert failed");
                p.setId(pid);
                r.playlist = p;
                if (tracks != null && !tracks.isEmpty()) {
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
                // 回滚：本次建的歌单行与已写条目一并清掉（pid<=0＝歌单行就没落下，无可清）
                if (pid > 0) {
                    try {
                        AppDatabase.get().getMusicPlaylistItemDao().deleteByPlaylist(pid);
                        AppDatabase.get().getMusicPlaylistDao().delete(pid);
                    } catch (Throwable ignored) {
                    }
                }
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
}
