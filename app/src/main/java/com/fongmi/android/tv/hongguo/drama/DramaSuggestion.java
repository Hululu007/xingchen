package com.fongmi.android.tv.hongguo.drama;

/**
 * 搜索联想一条（openhongguo ParseSuggest 的线上形状）：text 是显示词，
 * keyword 在 wordType=2 时是剧 id（可直接进剧）、其余是联想词本身；
 * wordType 原样透传服务端 word_type。
 */
public final class DramaSuggestion {

    private final String text;
    private final String keyword;
    private final int wordType;

    public DramaSuggestion(String text, String keyword, int wordType) {
        this.text = text;
        this.keyword = keyword;
        this.wordType = wordType;
    }

    public String getText() { return text; }
    public String getKeyword() { return keyword; }
    public int getWordType() { return wordType; }

    /** 是否直达剧（word_type=2 且 keyword 是纯数字剧 id）。 */
    public boolean isSeriesLink() {
        if (wordType != 2 || keyword == null || keyword.isEmpty()) return false;
        for (int i = 0; i < keyword.length(); i++) {
            if (!Character.isDigit(keyword.charAt(i))) return false;
        }
        return true;
    }
}
