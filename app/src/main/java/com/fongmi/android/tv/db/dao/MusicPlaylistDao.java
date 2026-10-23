package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.MusicPlaylist;

import java.util.List;

@Dao
public abstract class MusicPlaylistDao extends BaseDao<MusicPlaylist> {

    @Query("SELECT * FROM MusicPlaylist ORDER BY updateTime DESC")
    public abstract List<MusicPlaylist> findAll();

    @Query("SELECT * FROM MusicPlaylist WHERE id = :id LIMIT 1")
    public abstract MusicPlaylist find(long id);

    @Query("DELETE FROM MusicPlaylist WHERE id = :id")
    public abstract void delete(long id);

    @Query("DELETE FROM MusicPlaylist")
    public abstract void deleteAll();
}
