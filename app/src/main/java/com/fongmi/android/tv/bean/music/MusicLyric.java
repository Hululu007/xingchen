package com.fongmi.android.tv.bean.music;

/** 歌词：FreeJS getLyric 返回。 */
public class MusicLyric {

    /** 原始歌词（lrc 文本） */
    public String rawLrc;
    /** 翻译歌词，可空 */
    public String translation;

    public MusicLyric() {
    }

    public MusicLyric(String rawLrc) {
        this.rawLrc = rawLrc;
    }

    public boolean hasLyric() {
        return (rawLrc != null && !rawLrc.isEmpty()) || (translation != null && !translation.isEmpty());
    }
}
