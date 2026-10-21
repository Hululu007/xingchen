package com.fongmi.android.tv.music;

import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三轨歌词对齐：正文行带逐字词，译/音译取整行文本＋逐词表并存（M-02）。
 * 对齐口径与 LyricsParser 的辅助行一致：按时间最近邻，容差 800ms，
 * 一条译/音译行最多被一条正文行用（先到先得）；逐词表空时 KLV 侧回落整行提亮。
 */
public class RichLyrics {

    private static final long TOLERANCE_MS = 800;

    public static class Line {
        public LyricsLine main;
        public String translation;
        public String romanization;
        /** 翻译逐词（相对行首偏移；空表＝无逐词）。 */
        public List<LyricsWord> translationWords;
        /** 音译逐词（相对行首偏移；空表＝无逐词）。 */
        public List<LyricsWord> romanizationWords;

        public Line() {
        }

        public Line(LyricsLine main, String translation, String romanization) {
            this(main, translation, romanization, null, null);
        }

        public Line(LyricsLine main, String translation, String romanization,
                    List<LyricsWord> translationWords, List<LyricsWord> romanizationWords) {
            this.main = main;
            this.translation = translation;
            this.romanization = romanization;
            this.translationWords = translationWords == null ? Collections.emptyList() : translationWords;
            this.romanizationWords = romanizationWords == null ? Collections.emptyList() : romanizationWords;
        }
    }

    public static List<Line> align(List<LyricsLine> main, List<LyricsLine> trans, List<LyricsLine> roma) {
        // 不变量：输出行数恒等于正文行数——对齐只给正文行挂译/音，失败挂 null，永不吞正文行
        List<Line> output = new ArrayList<>();
        if (main == null) return output;
        Set<Integer> usedTrans = new HashSet<>();
        Set<Integer> usedRoma = new HashSet<>();
        for (LyricsLine line : main) {
            LyricsLine transLine = nearest(trans, line.getTimeMs(), usedTrans);
            LyricsLine romaLine = nearest(roma, line.getTimeMs(), usedRoma);
            Line rich = new Line(line,
                    transLine == null ? null : transLine.getText(),
                    romaLine == null ? null : romaLine.getText(),
                    transLine == null ? null : transLine.getWords(),
                    romaLine == null ? null : romaLine.getWords());
            output.add(rich);
        }
        return output;
    }

    private static LyricsLine nearest(List<LyricsLine> aux, long timeMs, Set<Integer> used) {
        if (aux == null || aux.isEmpty()) return null;
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < aux.size(); i++) {
            if (used.contains(i)) continue;
            LyricsLine candidate = aux.get(i);
            if (candidate == null) continue;
            // 空文本译/音行不参与对齐、更不许占用名额把同窗的真译行挤掉
            if (candidate.getText() == null || candidate.getText().isEmpty()) continue;
            long delta = Math.abs(candidate.getTimeMs() - timeMs);
            if (delta <= TOLERANCE_MS && delta < bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        if (best < 0) return null;
        used.add(best);
        return aux.get(best);
    }
}
