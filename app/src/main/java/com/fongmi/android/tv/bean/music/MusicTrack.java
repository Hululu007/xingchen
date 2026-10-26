package com.fongmi.android.tv.bean.music;

/**
 * 音乐曲目（一期源抽象契约，另一代理在定；播放器只按此契约取数）。
 * 字段以外的运行时信息（platform 供直链解析、qualityTag 供列表行音质徽章）为播放器自有扩展。
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
    /** 音质徽章：插件声明的最高档真规格词（FLAC／320k／128k），可空 */
    public String qualityTag;

    /**
     * 源层回传信息：{_plugin: 插件 srcUrl, _item: 插件原始条目}。
     * 取链/歌词时桥按此原样重建 musicItem 再调插件，播放器侧不用管。
     */
    public String rawJson;
    /** 运行时标记（不落盘）：封面有异步补图在途（酷狗专辑图后补）——搜索行此时
     * 留玻璃空位、不上素图；补到后 cover 被回填、coverBackfilled 置位，行只淡入一次。 */
    public transient boolean coverPending;
    /** 运行时标记（不落盘）：封面刚由异步补图回填、尚未在任何行上展示过（淡入一次的凭据） */
    public transient boolean coverBackfilled;
    /** 运行时标记（不落盘）：时长有异步补查在途（咪咕 resourceinfo 后补）——行上先 --:--，补到即重绑 */
    public transient boolean durationPending;

    /** 歌单内加入时间（仅歌单曲目有值，供「加入歌单时间」排序；其它来源为 0） */
    public long playlistAddedTime;
    /** 收藏时间（仅收藏曲目有值，供「最近收藏／最早收藏」排序；其它来源为 0） */
    public long favoriteAddedTime;

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
        // 占位歌手（<unknown> 等）一律按空显示，不许露原样（他截图实证：播放页歌手行印出 <unknown>）
        return artist == null ? "" : com.fongmi.android.tv.utils.LocalTrackMeta.realArtist(artist);
    }

    public String displayAlbum() {
        return album == null ? "" : com.fongmi.android.tv.utils.LocalTrackMeta.realAlbum(album);
    }

    public String displayTitle() {
        return com.fongmi.android.tv.utils.LocalTrackMeta.cleanDisplayTitle(title, platform);
    }

    // 身份契约（扫描 M2）：相等只认 MusicIdentity 身份键（platform|插件|id）。
    // 旧实现只比裸 id，跨源同 id 被 contains/indexOf/remove/HashSet 误判成同一首；
    // 无身份（id 为空、keyOf 返回 null）的曲目只与自身相等。
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MusicTrack)) return false;
        MusicTrack t = (MusicTrack) o;
        String a = com.fongmi.android.tv.music.MusicIdentity.keyOf(this);
        String b = com.fongmi.android.tv.music.MusicIdentity.keyOf(t);
        return a != null && a.equals(b);
    }

    @Override
    public int hashCode() {
        String key = com.fongmi.android.tv.music.MusicIdentity.keyOf(this);
        return key == null ? 0 : key.hashCode();
    }
}
