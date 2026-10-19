package com.fongmi.android.tv.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 自建歌单 */
@Entity
public class MusicPlaylist {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private String name;
    private long createTime;
    private long updateTime;

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
