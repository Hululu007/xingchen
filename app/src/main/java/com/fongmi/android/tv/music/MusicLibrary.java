package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.MusicFavorite;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.MusicPlaylistItem;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 音乐曲库：收藏、自建歌单。Room 存取，子线程跑，主线程回。
 */
public class MusicLibrary {

    public interface DataCallback<T> {
        void onResult(T data);
    }

    private static MusicTrack toTrack(MusicFavorite f) {
        MusicTrack t = new MusicTrack();
        t.id = f.getTrackId();
        t.title = f.getTitle();
        t.artist = f.getArtist();
        t.album = f.getAlbum();
        t.duration = f.getDuration();
        t.cover = f.getCover();
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
        return t;
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

    public static void setFavorite(MusicTrack track, boolean fav, Runnable done) {
        Task.execute(() -> {
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
                        f.setCreateTime(System.currentTimeMillis());
                        AppDatabase.get().getMusicFavoriteDao().insert(f);
                    } else {
                        AppDatabase.get().getMusicFavoriteDao().delete(track.id);
                    }
                }
            } catch (Throwable ignored) {
            }
            post(() -> {
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
            try {
                p.setName(name == null || name.trim().isEmpty() ? "新建歌单" : name.trim());
                long now = System.currentTimeMillis();
                p.setCreateTime(now);
                p.setUpdateTime(now);
                long id = AppDatabase.get().getMusicPlaylistDao().insert(p);
                p.setId(id);
            } catch (Throwable ignored) {
            }
            post(() -> cb.onResult(p));
        });
    }

    public static void renamePlaylist(long id, String name, Runnable done) {
        Task.execute(() -> {
            try {
                MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(id);
                if (p != null) {
                    p.setName(name);
                    p.setUpdateTime(System.currentTimeMillis());
                    AppDatabase.get().getMusicPlaylistDao().update(p);
                }
            } catch (Throwable ignored) {
            }
            post(() -> {
                if (done != null) done.run();
            });
        });
    }

    public static void deletePlaylist(long id, Runnable done) {
        Task.execute(() -> {
            try {
                AppDatabase.get().getMusicPlaylistItemDao().deleteByPlaylist(id);
                AppDatabase.get().getMusicPlaylistDao().delete(id);
            } catch (Throwable ignored) {
            }
            post(() -> {
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
            try {
                Integer max = AppDatabase.get().getMusicPlaylistItemDao().maxPosition(playlistId);
                MusicPlaylistItem i = new MusicPlaylistItem();
                i.setPlaylistId(playlistId);
                i.setTrackId(track.id);
                i.setTitle(track.title);
                i.setArtist(track.artist);
                i.setAlbum(track.album);
                i.setDuration(track.duration);
                i.setCover(track.cover);
                i.setPosition(max == null ? 0 : max + 1);
                i.setCreateTime(System.currentTimeMillis());
                added = AppDatabase.get().getMusicPlaylistItemDao().insert(i) != -1;
                MusicPlaylist p = AppDatabase.get().getMusicPlaylistDao().find(playlistId);
                if (p != null) {
                    p.setUpdateTime(System.currentTimeMillis());
                    AppDatabase.get().getMusicPlaylistDao().update(p);
                }
            } catch (Throwable ignored) {
            }
            boolean r = added;
            post(() -> cb.onResult(r));
        });
    }

    public static void removeFromPlaylist(long itemId, Runnable done) {
        Task.execute(() -> {
            try {
                AppDatabase.get().getMusicPlaylistItemDao().deleteById(itemId);
            } catch (Throwable ignored) {
            }
            post(() -> {
                if (done != null) done.run();
            });
        });
    }
}
