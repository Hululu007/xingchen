package com.fongmi.android.tv.bean.music;

/**
 * 歌词版本（B2）：一次聚合拿到的一个真实歌词版本。只筛实际拿到的版本，不编版本。
 * kind 由解析结果判定：任一行带逐字时间＝逐字；任一行带时间戳＝逐行；否则纯文本。
 */
public class LyricVersion {

    private final MusicLyric lyric;
    private final String source;
    private final boolean wordTimed;
    private final boolean lineTimed;

    public LyricVersion(MusicLyric lyric, String source, boolean wordTimed, boolean lineTimed) {
        this.lyric = lyric;
        this.source = source;
        this.wordTimed = wordTimed;
        this.lineTimed = lineTimed;
    }

    public MusicLyric getLyric() {
        return lyric;
    }

    public String getSource() {
        return source;
    }

    public boolean isWordTimed() {
        return wordTimed;
    }

    public boolean isLineTimed() {
        return lineTimed;
    }

    /** 版本档位名：逐字／逐行／纯文本（与播放页歌词档位同一口径） */
    public String kindLabel() {
        if (wordTimed) return "逐字";
        if (lineTimed) return "逐行";
        return "纯文本";
    }

    /** 筛选条上的显示名：来源·档位（来源空时只显档位） */
    public String displayLabel() {
        if (source == null || source.isEmpty()) return kindLabel();
        return source + "·" + kindLabel();
    }
}
