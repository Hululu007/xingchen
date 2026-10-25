package com.fongmi.android.tv.bean.music;

/**
 * 歌词版本（B2）：一次聚合拿到的一个真实歌词版本。只筛实际拿到的版本，不编版本。
 * kind 由解析结果判定：任一行带逐字时间＝逐字；任一行带时间戳＝逐行；否则纯文本。
 * matchLevel 是星辰对歌级别（数值与 MusicRepository.MATCH_* 同义：2 精确／1 包含／0 不中）：
 * 聚合产出时由二-8 对歌判定（matchSong 口径）随版本带下来；产出链拿不到判定的版本
 * （如内部已自行对歌的聚合来源、老构造入口）一律按包含级兜底，不许冒充精确。
 * gzLevel 是瓜子对歌等级（GzLyricMatch 照瓜子 sq4.t 移植：3.0／2.0／1.5／1.4／1.0，
 * 0＝被瓜子门淘汰，NaN＝未定级）：跨源版本在命中当场按瓜子输入（目标曲与候选歌的
 * 标题／歌手／时长）定级；本源自有词与在用词条照瓜子自有候选口径记 3.0（构造分 1.0）；
 * 拿不到候选歌元数据的产出步（插件／Worker／平台直连等）记 NaN，不硬算不冒充。
 * 两级都只供版本弹窗的排序与匹配度展示，不参与自动取词的首选判定。
 */
public class LyricVersion {

    /** 对歌级别：精确级（与 MusicRepository.MATCH_EXACT 同值） */
    public static final int MATCH_LEVEL_EXACT = 2;
    /** 对歌级别：包含级（与 MusicRepository.MATCH_CONTAINS 同值）；拿不到判定的兜底值 */
    public static final int MATCH_LEVEL_CONTAINS = 1;
    /** 瓜子等级未定（产出步拿不到候选歌元数据） */
    public static final double GZ_LEVEL_UNKNOWN = Double.NaN;

    private final MusicLyric lyric;
    private final String source;
    private final boolean wordTimed;
    private final boolean lineTimed;
    private final int matchLevel;
    private final double gzLevel;
    /** 候选歌自身的标题／歌手（瓜子 LyricsCandidate.songTitle/songArtist）：只有跨源命中当场
     * 拿得到候选歌元数据的产出步才带，其余为 null——弹窗行显候选自己的歌名·歌手，空则回落当前歌曲。 */
    private final String candidateTitle;
    private final String candidateArtist;
    /** 候选歌与目标曲的时长差（秒，瓜子 LyricsCandidate.durationDeltaSec）：0＝无差或未知，弹窗 >0 才显「偏差 Ns」。 */
    private final int durationDeltaSec;

    public LyricVersion(MusicLyric lyric, String source, boolean wordTimed, boolean lineTimed) {
        this(lyric, source, wordTimed, lineTimed, MATCH_LEVEL_CONTAINS, GZ_LEVEL_UNKNOWN);
    }

    public LyricVersion(MusicLyric lyric, String source, boolean wordTimed, boolean lineTimed, int matchLevel) {
        this(lyric, source, wordTimed, lineTimed, matchLevel, GZ_LEVEL_UNKNOWN);
    }

    public LyricVersion(MusicLyric lyric, String source, boolean wordTimed, boolean lineTimed,
                        int matchLevel, double gzLevel) {
        this(lyric, source, wordTimed, lineTimed, matchLevel, gzLevel, null, null, 0);
    }

    public LyricVersion(MusicLyric lyric, String source, boolean wordTimed, boolean lineTimed,
                        int matchLevel, double gzLevel, String candidateTitle, String candidateArtist,
                        int durationDeltaSec) {
        this.lyric = lyric;
        this.source = source;
        this.wordTimed = wordTimed;
        this.lineTimed = lineTimed;
        this.matchLevel = matchLevel;
        this.gzLevel = gzLevel;
        this.candidateTitle = candidateTitle;
        this.candidateArtist = candidateArtist;
        this.durationDeltaSec = durationDeltaSec;
    }

    /** 候选歌标题（可空，见字段注） */
    public String getCandidateTitle() {
        return candidateTitle;
    }

    /** 候选歌歌手（可空，见字段注） */
    public String getCandidateArtist() {
        return candidateArtist;
    }

    /** 时长差（秒）：0＝无差或未知 */
    public int getDurationDeltaSec() {
        return durationDeltaSec;
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

    /** 对歌级别（MATCH_LEVEL_* 口径）：聚合产出时带下来的对歌判定，弹窗排序与匹配度用 */
    public int getMatchLevel() {
        return matchLevel;
    }

    public boolean isExactMatch() {
        return matchLevel >= MATCH_LEVEL_EXACT;
    }

    /** 瓜子对歌等级（3.0／2.0／1.5／1.4／1.0／0；NaN＝未定级，见类头） */
    public double getGzLevel() {
        return gzLevel;
    }

    /** 有瓜子定级且过了瓜子门（＞0）才有匹配度可显示；0 级与未定级都不显分数，不冒充 */
    public boolean hasGzScore() {
        return gzLevel > 0;
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
