package com.fongmi.android.tv.bean;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 歌单里的歌 */
@Entity(indices = {@Index("playlistId"), @Index(value = {"playlistId", "trackId", "platform", "plugin"}, unique = true)})
public class MusicPlaylistItem {

    @PrimaryKey(autoGenerate = true)
    private long id;
    private long playlistId;
    private String trackId;
    private String title;
    private String artist;
    private String album;
    private long duration;
    private String cover;
    private String platform;
    /** 插件来源（rawJson 的 _plugin 戳落列，与收藏 MusicFavorite.plugin 同口径）：
     *  FreeJS 各插件 platform 同为 freejs、trackId 是插件内编号，唯一键不带插件段时
     *  同 id 同 platform 异插件的另一首会被判「已在歌单」拒入／互相顶掉。
     *  无戳的源存空串；44 版前存量行为 null，读路径按 rawJson 戳比对（sameItemIdentity）。 */
    private String plugin;
    private String rawJson;
    /** 本地文件路径（已下载歌的取链只认它，缺了就是死条目）；在线歌为空 */
    private String localPath;
    private int position;
    private long createTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getPlaylistId() {
        return playlistId;
    }

    public void setPlaylistId(long playlistId) {
        this.playlistId = playlistId;
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

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}
