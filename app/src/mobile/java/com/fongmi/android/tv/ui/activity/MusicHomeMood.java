package com.fongmi.android.tv.ui.activity;

import android.graphics.Color;

/**
 * 音乐首页心情电台的数据表与颜色公式（首页收官新写，2026-10-25）。
 *
 * 数据照瓜子 HomeScreenKt:314 抠值（12 心情：名＋文案＋tone 色，顺序即源码顺序）；
 * 卡渐变三段原始值同出该处（电子无专属组走默认组），向白混合比 0.08/0.20/0.32、
 * 播放钮渐变终色向白混合 0.22、选中 pill 底 tone 0.18，均为瓜子公式；
 * 混色公式 mix = c + (255-c)*ratio（向白）与两色线性混合各一份，纯静态无状态。
 */
public final class MusicHomeMood {

    /** 默认选中心情（队列标签反推不到时取它，瓜子口径） */
    public static final String DEFAULT = "怀旧";

    /** 心情表：{名，文案，tone}，顺序即瓜子 HomeScreenKt:314 代码顺序 */
    private static final String[][] TABLE = {
            {"怀旧", "让旧时光重新播放", "#D88445"}, {"清新", "给耳朵一阵清风", "#20B996"},
            {"浪漫", "把心动写进旋律", "#DB5C98"}, {"伤感", "把没说完的话听完", "#7388DB"},
            {"治愈", "让旋律轻轻接住你", "#EAAF4B"}, {"放松", "把紧绷慢慢放下", "#9476D8"},
            {"夜晚", "留一盏灯给深夜", "#747AD1"}, {"清晨", "用好心情叫醒今天", "#F5AC4D"},
            {"散步", "跟着晚风慢慢走", "#5AAF7B"}, {"运动", "让节拍推你向前", "#ED795F"},
            {"民谣", "听见故事里的远方", "#D99353"}, {"电子", "让脉冲点亮此刻", "#8874E7"},
    };

    private MusicHomeMood() {
    }

    public static String[][] table() {
        return TABLE;
    }

    public static boolean isMood(String name) {
        if (name == null) return false;
        for (String[] m : TABLE) if (m[0].equals(name)) return true;
        return false;
    }

    /** tone 色（表第三列，恒为 #RRGGBB）；查不到回落怀旧 tone */
    public static int tone(String mood) {
        for (String[] m : TABLE) if (m[0].equals(mood)) return Color.parseColor(m[2]);
        return Color.parseColor("#D88445");
    }

    /** 文案（表第二列）；查不到回空串 */
    public static String copy(String mood) {
        for (String[] m : TABLE) if (m[0].equals(mood)) return m[1];
        return "";
    }

    /** 卡渐变三段原始值（左→右，瓜子 HomeScreenKt:314 前后渐变组；电子无专属组走默认） */
    public static String[] gradient(String mood) {
        if (mood == null) return new String[]{"#EAE5FF", "#D7CEFF", "#C3D8FF"};
        switch (mood) {
            case "伤感": return new String[]{"#E8ECFF", "#D6DEFF", "#C6D3FA"};
            case "夜晚": return new String[]{"#E5E4FF", "#CDCFFA", "#BABFF2"};
            case "怀旧": return new String[]{"#FFEDD5", "#FFDDBA", "#FFCAA5"};
            case "放松": return new String[]{"#EFE7FF", "#E2D5FF", "#CCDFFF"};
            case "散步": return new String[]{"#E6F7DE", "#D1F0C9", "#BFEADB"};
            case "治愈": return new String[]{"#FFF3D6", "#FFE9B9", "#FFD9B5"};
            case "民谣": return new String[]{"#FFF0DA", "#FFE2C0", "#FAD2AF"};
            case "浪漫": return new String[]{"#FFE7F2", "#FFCFE8", "#F4BBE6"};
            case "清新": return new String[]{"#DDF9F1", "#C5F3E3", "#DAF6AC"};
            case "清晨": return new String[]{"#FFF5D9", "#FFE8AB", "#FFD3B4"};
            case "运动": return new String[]{"#FFE7DA", "#FFCFBB", "#FFBFAE"};
            default: return new String[]{"#EAE5FF", "#D7CEFF", "#C3D8FF"};
        }
    }

    /** 颜色向白混合：mix = c + (255-c)*ratio（瓜子渐变／播放钮混白公式） */
    public static int mixWhite(int color, float ratio) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Color.rgb(Math.round(r + (255 - r) * ratio),
                Math.round(g + (255 - g) * ratio), Math.round(b + (255 - b) * ratio));
    }

    /** 两色线性混合（ratio=0 取 a，=1 取 b）。 */
    public static int mixColors(int a, int b, float ratio) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * ratio),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * ratio),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * ratio));
    }

    /** 给颜色套 alpha（高 8 位替换，其余不变）。 */
    public static int alphaColor(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0x00FFFFFF);
    }
}
