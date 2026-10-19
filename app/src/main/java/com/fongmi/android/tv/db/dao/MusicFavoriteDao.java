package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.MusicFavorite;

import java.util.List;

@Dao
public abstract class MusicFavoriteDao extends BaseDao<MusicFavorite> {

    @Query("SELECT * FROM MusicFavorite ORDER BY createTime DESC")
    public abstract List<MusicFavorite> findAll();

    @Query("SELECT * FROM MusicFavorite WHERE trackId = :trackId LIMIT 1")
    public abstract MusicFavorite find(String trackId);

    @Query("DELETE FROM MusicFavorite WHERE trackId = :trackId")
    public abstract void delete(String trackId);

    @Query("SELECT COUNT(*) FROM MusicFavorite")
    public abstract int count();
}
