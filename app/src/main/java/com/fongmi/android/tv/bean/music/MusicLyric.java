package com.fongmi.android.tv.bean.music;

/** 歌词：FreeJS getLyric 返回。 */
public class MusicLyric {

    /** 原始歌词（lrc 文本） */
    public String rawLrc;
    /** 翻译歌词，可空 */
    public String translation;
    /** 音译歌词（罗马音），可空 */
    public String romanization;

    public MusicLyric() {
    }

    public MusicLyric(String rawLrc) {
        this.rawLrc = rawLrc;
    }

    public boolean hasLyric() {
        return notBlank(rawLrc) || notBlank(translation) || notBlank(romanization);
    }

    /** 空白也算无词：与 MusicRepository.hasRaw 的 trim 口径一致，防止纯空白正文冒充有词、堵住后续插件/兜底 */
    private static boolean notBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }
}
