package com.fongmi.android.tv.db.dao;

import androidx.room.Dao;
import androidx.room.Query;

import com.fongmi.android.tv.bean.MusicDownload;

import java.util.List;

@Dao
public abstract class MusicDownloadDao extends BaseDao<MusicDownload> {

    @Query("SELECT * FROM MusicDownload ORDER BY createTime DESC")
    public abstract List<MusicDownload> findAll();

    @Query("SELECT * FROM MusicDownload WHERE trackId = :trackId LIMIT 1")
    public abstract MusicDownload findByTrackId(String trackId);

    @Query("DELETE FROM MusicDownload WHERE trackId = :trackId")
    public abstract void deleteByTrackId(String trackId);

    @Query("SELECT COUNT(*) FROM MusicDownload")
    public abstract int count();
}
