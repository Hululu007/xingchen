package com.fongmi.android.tv.bean.music;

/**
 * 音乐曲目（一期源抽象契约，另一代理在定；播放器只按此契约取数）。
 * 字段以外的运行时信息（platform 供直链解析、qualityTag 供 SQ/HR 徽章）为播放器自有扩展。
 */
public class MusicTrack {

    public String id;
    public String title;
    public String artist;
    public String album;
    /** 时长毫秒 */
    public long duration;
    public String cover;

    /** 来源平台标识（直链解析用） */
    public String platform;
    /** 本地文件绝对路径（platform="local" 时有效，LocalMusicSource 解析用） */
    public String localPath;
    /** 音质徽章：SQ / HQ / HR，可空 */
    public String qualityTag;

    /**
     * 源层回传信息：{_plugin: 插件 srcUrl, _item: 插件原始条目}。
     * 取链/歌词时桥按此原样重建 musicItem 再调插件，播放器侧不用管。
     */
    public String rawJson;

    public MusicTrack() {
    }

    public MusicTrack(String id, String title, String artist, String album, long duration, String cover) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration;
        this.cover = cover;
    }

    public String displayArtist() {
        return artist == null ? "" : artist;
    }

    public String displayTitle() {
        return title == null ? "" : title;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MusicTrack)) return false;
        MusicTrack t = (MusicTrack) o;
        return id != null && id.equals(t.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
