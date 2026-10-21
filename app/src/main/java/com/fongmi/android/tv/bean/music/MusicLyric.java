package com.fongmi.android.tv.bean.music;

/** 歌词：FreeJS getLyric 返回。 */
public class MusicLyric {

    /** 原始歌词（lrc 文本） */
    public String rawLrc;
    /** 翻译歌词，可空 */
    public String translation;
    /** 音译歌词（罗马音），可空 */
    public String romanization;
    /** 歌词档位元数据（B8）：如 "AMLL"；空＝普通 LRC 档，档位名不冒充 */
    public String format;
    /** 歌词来源名（B2 版本筛选条用）：如站点名、"AMLL"；空＝未知 */
    public String sourceName;

    public MusicLyric() {
    }

    public MusicLyric(String rawLrc) {
        this.rawLrc = rawLrc;
    }

    public boolean hasLyric() {
        return notBlank(rawLrc) || notBlank(translation) || notBlank(romanization);
    }

    /**
     * 有正文或译文（不含纯音译）：兜底链的「命中」口径。纯音译也算有收获
     * （hasLyric），但不许它在遍历中途截断后续插件/源——后面的可能有正文；
     * 音译-only 只配作整条链走完后的最后兜底。
     */
    public boolean hasBodyText() {
        return notBlank(rawLrc) || notBlank(translation);
    }

    /** 空白也算无词：与 MusicRepository.hasRaw 的 trim 口径一致，防止纯空白正文冒充有词、堵住后续插件/兜底 */
    private static boolean notBlank(String text) {
        return text != null && !text.trim().isEmpty();
    }
}
