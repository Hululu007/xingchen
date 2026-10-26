package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 已下载的歌曲：本地文件登记 */
@Entity(indices = {@Index(value = {"trackId"}, unique = true)})
public class MusicDownload {

    @PrimaryKey(autoGenerate = true)
    private long id;
    @NonNull
    private String trackId = "";
    private String title;
    private String artist;
    private String album;
    private long duration;
    private String cover;
    private String filePath;
    private long createTime;
    /** 下载当下那首歌的音质标（快照 qualityTag，如 FLAC／320k）：重启回读已下载列表时回显用，旧行留空不显 */
    private String qualityTag;
    /** 歌词旁路／内嵌写入失败且策略为「写入字段」时存下的歌词原文（回读兜底，见 MusicDownloadStore.readSidecarLyric） */
    private String lyric;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(String trackId) {
        this.trackId = trackId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public String getCover() {
        return cover;
    }

    public void setCover(String cover) {
        this.cover = cover;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public String getQualityTag() {
        return qualityTag;
    }

    public void setQualityTag(String qualityTag) {
        this.qualityTag = qualityTag;
    }

    public String getLyric() {
        return lyric;
    }

    public void setLyric(String lyric) {
        this.lyric = lyric;
    }
}
