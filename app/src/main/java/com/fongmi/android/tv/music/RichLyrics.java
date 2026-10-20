package com.fongmi.android.tv.music;

import com.fongmi.android.tv.player.lyrics.LyricsLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三轨歌词对齐：正文行带逐字词，译/音译只取整行文本。
 * 对齐口径与 LyricsParser 的辅助行一致：按时间最近邻，容差 800ms，
 * 一条译/音译行最多被一条正文行用（先到先得）。
 */
public class RichLyrics {

    private static final long TOLERANCE_MS = 800;

    public static class Line {
        public LyricsLine main;
        public String translation;
        public String romanization;

        public Line() {
        }

        public Line(LyricsLine main, String translation, String romanization) {
            this.main = main;
            this.translation = translation;
            this.romanization = romanization;
        }
    }

    public static List<Line> align(List<LyricsLine> main, List<LyricsLine> trans, List<LyricsLine> roma) {
        List<Line> output = new ArrayList<>();
        if (main == null) return output;
        Set<Integer> usedTrans = new HashSet<>();
        Set<Integer> usedRoma = new HashSet<>();
        for (LyricsLine line : main) {
            Line rich = new Line(line,
                    nearestText(trans, line.getTimeMs(), usedTrans),
                    nearestText(roma, line.getTimeMs(), usedRoma));
            output.add(rich);
        }
        return output;
    }

    private static String nearestText(List<LyricsLine> aux, long timeMs, Set<Integer> used) {
        if (aux == null || aux.isEmpty()) return null;
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < aux.size(); i++) {
            if (used.contains(i)) continue;
            LyricsLine candidate = aux.get(i);
            if (candidate == null) continue;
            long delta = Math.abs(candidate.getTimeMs() - timeMs);
            if (delta <= TOLERANCE_MS && delta < bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        if (best < 0) return null;
        used.add(best);
        String text = aux.get(best).getText();
        return text == null || text.isEmpty() ? null : text;
    }
}
