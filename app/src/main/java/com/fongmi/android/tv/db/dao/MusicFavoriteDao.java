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

    /** 复合身份查找：id 只是源内编号，同 id 不同来源是不同的歌 */
    @Query("SELECT * FROM MusicFavorite WHERE trackId = :trackId AND platform = :platform LIMIT 1")
    public abstract MusicFavorite find(String trackId, String platform);

    @Query("DELETE FROM MusicFavorite WHERE trackId = :trackId")
    public abstract void delete(String trackId);

    /** 复合身份删除：只删这一来源的那条，不误伤同 id 的别家歌 */
    @Query("DELETE FROM MusicFavorite WHERE trackId = :trackId AND platform = :platform")
    public abstract void delete(String trackId, String platform);

    /** 按主键删：platform 为 NULL 的存量行等值条件删不掉，按行删最准 */
    @Query("DELETE FROM MusicFavorite WHERE id = :id")
    public abstract void deleteById(long id);

    @Query("DELETE FROM MusicFavorite")
    public abstract void deleteAll();

    @Query("SELECT COUNT(*) FROM MusicFavorite")
    public abstract int count();
}
