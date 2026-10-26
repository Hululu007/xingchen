package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicLyric;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.player.lyrics.KugouClient;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsParser;
import com.fongmi.android.tv.player.lyrics.LyricsRepository;
import com.fongmi.android.tv.player.lyrics.LyricsRequest;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.player.lyrics.NeteaseClient;
import com.fongmi.android.tv.player.lyrics.QqMusicClient;
import com.github.catvod.crawler.SpiderDebug;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台直连回落源（Worker 聚合不可用时才走）：把视频侧已有的平台词级客户端
 * （酷狗 KRC／QQ QRC／网易云 YRC）接进音乐取词链。只认词级（正文解析后任一
 * 行带词时间），行级不收；无词级命中返回空，不冒充。顺序：酷狗 → QQ → 网易云；
 * AMLL 仍在链尾兜底，不在本类里。
 */
public class PlatformWordLyricSource {

    private final KugouClient kugou = new KugouClient();
    private final QqMusicClient qq = new QqMusicClient();
    private final NeteaseClient netease = new NeteaseClient();

    /** 首个词级命中（快路径用）；无词级返回空。三源串行共用一个总预算（X-29 补账：
     * 取词口径 18 秒整链封顶，剩余预算逐源递减，不是每源一份）。
     * 时长缺失收紧（py 歌词打架三修之二）：本路是自动取词链——目标歌时长缺失
     * （0／未知）时，候选结果自带歌名／歌手，过不了 GzLyricMatch 收紧对版关
     * （歌名全等＋歌手双对上）的跳过不收，宁可无词不许拿异版本词垫；
     * 全量聚合 lyricRichAll 是版本面板口径，不设此关、全收由用户自选。 */
    public MusicLyric lyricRich(MusicTrack track) {
        LyricsRequest request = requestOf(track);
        if (request == null) return null;
        long deadline = LyricsRepository.deadlineAfter(LyricsRepository.LOAD_TIMEOUT_MS);
        MusicLyric lyric = firstWord(safeFind(request, deadline, req -> kugou.findAll(req, 8), "Kugou"), "酷狗", "KRC", track);
        if (lyric != null) return lyric;
        lyric = firstWord(safeFind(request, deadline, req -> qq.findAll(req, 8), "QqMusic"), "QQ音乐", "QRC", track);
        if (lyric != null) return lyric;
        return firstWord(safeFind(request, deadline, req -> netease.findAll(req, 8), "Netease"), "网易云", "YRC", track);
    }

    /** 每平台各收一词级版（版本聚合用；行级不收）。三源串行共用一个总预算
     * （X-29 补账：全量聚合口径 22 秒整链封顶，剩余预算逐源递减）。 */
    public List<MusicLyric> lyricRichAll(MusicTrack track) {
        List<MusicLyric> out = new ArrayList<>();
        LyricsRequest request = requestOf(track);
        if (request == null) return out;
        long deadline = LyricsRepository.deadlineAfter(LyricsRepository.SEARCH_TIMEOUT_MS);
        addWord(out, safeFind(request, deadline, req -> kugou.findAll(req, 8), "Kugou"), "酷狗", "KRC");
        addWord(out, safeFind(request, deadline, req -> qq.findAll(req, 8), "QqMusic"), "QQ音乐", "QRC");
        addWord(out, safeFind(request, deadline, req -> netease.findAll(req, 8), "Netease"), "网易云", "YRC");
        return out;
    }

    private interface Finder {
        List<LyricsResult> find(LyricsRequest request);
    }

    private List<LyricsResult> safeFind(LyricsRequest request, long deadline, Finder finder, String name) {
        try {
            List<LyricsResult> results = LyricsRepository.runWithDeadline(name, deadline, () -> finder.find(request));
            return results == null ? new ArrayList<>() : results;
        } catch (Throwable t) {
            SpiderDebug.log("music", "platform word find error client=%s %s", name, t.getMessage());
            return new ArrayList<>();
        }
    }

    private void addWord(List<MusicLyric> out, List<LyricsResult> results, String sourceName, String format) {
        MusicLyric lyric = firstWord(results, sourceName, format, null);
        if (lyric != null) out.add(lyric);
    }

    /** gateTrack 非空且其时长缺失时启用收紧对版关（仅快路径传，聚合路传 null 不设关） */
    private MusicLyric firstWord(List<LyricsResult> results, String sourceName, String format, MusicTrack gateTrack) {
        if (results == null) return null;
        for (LyricsResult r : results) {
            if (r == null) continue;
            if (gateTrack != null && gateTrack.duration <= 0 && !com.fongmi.android.tv.music.GzLyricMatch
                    .strictTitleArtistMatch(gateTrack.title, gateTrack.artist, r.getTrackName(), r.getArtistName())) {
                continue;
            }
            String text = r.getLyrics();
            if (text == null || text.trim().isEmpty()) continue;
            if (!hasWord(text)) continue;
            MusicLyric lyric = new MusicLyric(text);
            lyric.format = format;
            lyric.sourceName = sourceName;
            return lyric;
        }
        return null;
    }

    /** 词级判定：与 MusicRepository 词级口径一致（任一行带词时间） */
    private boolean hasWord(String text) {
        try {
            for (LyricsLine line : LyricsParser.parseWordLyric(text)) {
                if (line != null && line.hasWords()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private LyricsRequest requestOf(MusicTrack track) {
        if (track == null) return null;
        String title = track.title == null ? "" : track.title.trim();
        String artist = track.artist == null ? "" : track.artist.trim();
        if (title.isEmpty()) return null;
        return new LyricsRequest("", "", title, artist, "", track.duration);
    }
}
