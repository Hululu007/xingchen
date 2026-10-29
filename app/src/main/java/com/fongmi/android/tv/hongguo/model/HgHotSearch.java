package com.fongmi.android.tv.hongguo.model;

import java.util.Collections;
import java.util.List;

/**
 * 搜索落地页热搜面整包（plan/v1 from=search_input_page 回包解析结果，
 * 照 openhongguo sdk HotSearch，账 hongguo-hotsearch-dig-20261030 §2）：
 * guesses＝「猜你想搜」cell 的 video_data 行，boards＝榜单容器 cell 的
 * cell_data 逐板。guessName 是猜面 cell 的 cell_name 原文（段头文案
 * 取它，回落由消费侧定）。两面皆空＝本面不可用，消费侧静默不显
 * （不摆空壳占位）。
 */
public final class HgHotSearch {

    private final String guessName;
    private final List<HgHotItem> guesses;
    private final List<HgHotBoard> boards;

    public HgHotSearch(String guessName, List<HgHotItem> guesses, List<HgHotBoard> boards) {
        this.guessName = guessName;
        this.guesses = guesses == null ? Collections.emptyList() : guesses;
        this.boards = boards == null ? Collections.emptyList() : boards;
    }

    public String getGuessName() { return guessName; }
    public List<HgHotItem> getGuesses() { return guesses; }
    public List<HgHotBoard> getBoards() { return boards; }

    /** 两面皆空（回包无热搜面／解析全丢）时为 true。 */
    public boolean isEmpty() {
        return guesses.isEmpty() && boards.isEmpty();
    }
}
