package com.fongmi.android.tv.bean;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/** 自建歌单 */
@Entity
public class MusicPlaylist {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private String name;
    private long createTime;
    private long updateTime;
    /** 运行时：曲目数（不落库） */
    @Ignore
    public int trackCount = -1;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}
