package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.MusicPlaylistItem;

import java.util.List;

@Dao
public abstract class MusicPlaylistItemDao extends BaseDao<MusicPlaylistItem> {

    @Query("SELECT * FROM MusicPlaylistItem")
    public abstract List<MusicPlaylistItem> findAll();

    @Query("SELECT * FROM MusicPlaylistItem WHERE playlistId = :playlistId ORDER BY position ASC, createTime ASC")
    public abstract List<MusicPlaylistItem> findByPlaylist(long playlistId);

    @Query("SELECT * FROM MusicPlaylistItem WHERE playlistId = :playlistId AND trackId = :trackId LIMIT 1")
    public abstract MusicPlaylistItem find(long playlistId, String trackId);

    @Query("SELECT COUNT(*) FROM MusicPlaylistItem WHERE playlistId = :playlistId")
    public abstract int countByPlaylist(long playlistId);

    @Query("SELECT MAX(position) FROM MusicPlaylistItem WHERE playlistId = :playlistId")
    public abstract Integer maxPosition(long playlistId);

    @Query("DELETE FROM MusicPlaylistItem WHERE playlistId = :playlistId AND trackId = :trackId")
    public abstract void delete(long playlistId, String trackId);

    @Query("DELETE FROM MusicPlaylistItem WHERE id = :id")
    public abstract void deleteById(long id);

    @Query("DELETE FROM MusicPlaylistItem WHERE playlistId = :playlistId")
    public abstract void deleteByPlaylist(long playlistId);

    @Query("DELETE FROM MusicPlaylistItem")
    public abstract void deleteAll();
}
