package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.player.lyrics.LyricsRequest;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.player.lyrics.TtmlClient;

import java.util.List;

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
            // 取词口径总封顶 18 秒（X-29 补账）：经 LyricsRepository 封顶帮手调，不再直调无封顶
            LyricsRequest request = new LyricsRequest("", "", title, artist, "", track.duration);
            List<LyricsResult> capped = LyricsRepository.runWithDeadline("AMLL TTML",
                    LyricsRepository.deadlineAfter(LyricsRepository.LOAD_TIMEOUT_MS),
                    () -> {
                        LyricsResult found = client.find(request);
                        return found == null ? List.of() : List.of(found);
                    });
            LyricsResult r = capped.isEmpty() ? null : capped.get(0);
            if (r == null || r.getLyrics() == null || r.getLyrics().trim().isEmpty()) return null;
            MusicLyric lyric = new MusicLyric(r.getLyrics());
            lyric.format = "AMLL";
            lyric.sourceName = "AMLL";
            // TTML 自带翻译边轨（TtmlClient.find 已收进结果）：不搬运则 AMLL 版永远无翻译行
            if (r.getTranslation() != null && !r.getTranslation().trim().isEmpty()) lyric.translation = r.getTranslation();
            return lyric;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
