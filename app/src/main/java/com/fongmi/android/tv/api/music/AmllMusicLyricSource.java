package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.player.lyrics.LyricsRequest;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.player.lyrics.TtmlClient;

/**
 * AMLL 歌词来源（B8）：音乐侧自己的 AMLL 接入。取数复用视频侧 TtmlClient（网易搜歌＋
 * amll-ttml-db TTML 取数＋增强 LRC 转换），不复制取数逻辑；音乐链只做一层包装：
 * MusicTrack → LyricsRequest → MusicLyric（rawLrc＝增强 LRC，format＝"AMLL"，sourceName＝"AMLL"）。
 * 无 AMLL 命中时返回空，不冒充 AMLL 档。
 */
public class AmllMusicLyricSource {

    private final TtmlClient client = new TtmlClient();

    /** AMLL 歌词：命中返回带档位元数据的 MusicLyric；无命中返回空 */
    public MusicLyric lyricRich(MusicTrack track) {
        if (track == null) return null;
        String title = track.title == null ? "" : track.title.trim();
        String artist = track.artist == null ? "" : track.artist.trim();
        // 本地歌同样清洗：文件名后缀／发行组后缀＋占位歌手不进 AMLL 查询
        if (com.fongmi.android.tv.utils.LocalTrackMeta.isLocalPlatform(track.platform)) {
            title = com.fongmi.android.tv.utils.LocalTrackMeta.cleanTitle(title);
            artist = com.fongmi.android.tv.utils.LocalTrackMeta.realArtist(artist);
        }
        if (title.isEmpty()) return null;
        try {
            LyricsResult r = client.find(new LyricsRequest("", "", title, artist, "", track.duration));
            if (r == null || r.getLyrics() == null || r.getLyrics().trim().isEmpty()) return null;
            MusicLyric lyric = new MusicLyric(r.getLyrics());
            lyric.format = "AMLL";
            lyric.sourceName = "AMLL";
            return lyric;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
