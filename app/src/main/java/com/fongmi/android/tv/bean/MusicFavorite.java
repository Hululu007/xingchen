package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 音乐收藏（红心） */
@Entity(indices = {@Index(value = {"trackId", "platform", "plugin"}, unique = true)})
public class MusicFavorite {

    @PrimaryKey(autoGenerate = true)
    private long id;
    @NonNull
    private String trackId = "";
    private String title;
    private String artist;
    private String album;
    private long duration;
    private String cover;
    private String platform;
    /** 插件来源（rawJson 的 _plugin 戳落列，扫描 M3）：FreeJS 各插件 platform 同为
     *  freejs、id 是插件内编号，唯一键不带插件段时跨插件同 id 收藏互相顶掉/吞掉。
     *  无戳的源存空串；43 版前存量行为 null，读路径按 rawJson 戳比对并回填。 */
    private String plugin;
    private String rawJson;
    /** 本地文件路径（已下载歌的取链只认它，缺了就是死条目）；在线歌为空 */
    private String localPath;
    private long createTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getTrackId() {
        return trackId;
    }

    public void setTrackId(@NonNull String trackId) {
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

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPlugin() {
        return plugin;
    }

    public void setPlugin(String plugin) {
        this.plugin = plugin;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
