package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.LyricVersion;

/**
 * 版本弹窗排序口径（纯函数，不读设置——优先级模式由调用方传入）：
 * 一层对歌级别、二层逐字／逐行偏好（他既有「在线歌词优先级」设置）、三层星辰对歌级别、
 * 末层保收集序（稳定排序）。瓜子原口径只有「匹配度降序」一键（其列表里不存在未定级
 * 版本）；星辰列表是其超集（一层键要能给未定级版本定序），故一层键取有效级别：
 * 有瓜子定级用瓜子等级；未定级按星辰级别折算排序键（精确→3.0、包含→0.5，低于瓜子
 * 最低入列级 1.0）——折算只定序、不显分数，不冒充瓜子分。瓜子 0 级（被瓜子门淘汰、
 * 星辰链仍收的版本）排最末。
 * 逐行＝行时间且无词时间；AMLL 只认 getSource()＝"AMLL"，不判 format（与既有口径一致）。
 */
public final class LyricVersionOrdering {

    private LyricVersionOrdering() {
    }

    public static int compare(LyricVersion a, LyricVersion b, String priority) {
        int byLevel = Double.compare(sortLevel(b), sortLevel(a));
        if (byLevel != 0) return byLevel;
        int byPriority = Integer.compare(priorityRank(a, priority), priorityRank(b, priority));
        if (byPriority != 0) return byPriority;
        return Integer.compare(b.getMatchLevel(), a.getMatchLevel());
    }

    /** 一层排序键（只定序不显示）：瓜子等级优先；未定级折算——精确 3.0／包含 0.5 */
    static double sortLevel(LyricVersion v) {
        double gz = v.getGzLevel();
        if (!Double.isNaN(gz)) return gz;
        return v.getMatchLevel() >= LyricVersion.MATCH_LEVEL_EXACT ? 3.0 : 0.5;
    }

    /** 在线歌词优先级序位（越小越前；跟随来源全 0，即不参与次序） */
    static int priorityRank(LyricVersion v, String priority) {
        if (MusicStore.LYRIC_PRIORITY_FOLLOW_SOURCE.equals(priority)) return 0;
        boolean word = v.isWordTimed();
        boolean line = v.isLineTimed() && !word;
        if (MusicStore.LYRIC_PRIORITY_AMLL_FIRST.equals(priority)) {
            if ("AMLL".equals(v.getSource())) return 0;
            if (word) return 1;
            if (line) return 2;
            return 3;
        }
        if (MusicStore.LYRIC_PRIORITY_LINE_BY_LINE.equals(priority)) {
            if (line) return 0;
            if (word) return 1;
            return 2;
        }
        // 默认与 wordByWord：逐字优先
        if (word) return 0;
        if (line) return 1;
        return 2;
    }
}
