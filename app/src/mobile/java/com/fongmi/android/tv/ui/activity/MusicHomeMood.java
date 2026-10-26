package com.fongmi.android.tv.ui.activity;

/**
 * 音乐首页心情电台卡的数据与颜色公式（从零重写，状态机在 MusicActivity）。
 * 12 心情＝心情词 6（伤感/怀旧/放松/浪漫/治愈/清新）＋时段词 6（清晨/午间/午后/傍晚/夜晚/深夜），
 * 照瓜子 MoodRadioPanel 的构成；渐变三段＝tone 向白混 0.08/0.20/0.32（瓜子公式）。
 * tone 与文案为星辰侧取值（瓜子逐心情色值未抠到，见对照表待真机项）。
 */
public final class MusicHomeMood {

    private MusicHomeMood() {
    }

    public static final class Mood {
        public final String name;
        public final String desc;
        public final int tone;

        Mood(String name, String desc, int tone) {
            this.name = name;
            this.desc = desc;
            this.tone = tone;
        }

        /** 建台搜索词：心情名本身（落雪搜索建台口径）。 */
        public String keyword() {
            return name;
        }
    }

    public static final Mood[] MOODS = {
            new Mood("伤感", "慢慢听，心事自有回声", 0xFF5B8DEF),
            new Mood("怀旧", "老歌里藏着旧时光", 0xFFC98A4B),
            new Mood("放松", "松一口气，歌慢慢来", 0xFF4FB286),
            new Mood("浪漫", "把心动调大一点", 0xFFF2668B),
            new Mood("治愈", "不好的情绪会被歌带走", 0xFF66BB8A),
            new Mood("清新", "像风一样轻的歌", 0xFF39C2C9),
            new Mood("清晨", "新的一天，先听首歌", 0xFFF5A623),
            new Mood("午间", "午休间隙，听会儿歌", 0xFFFF7A59),
            new Mood("午后", "阳光正好，歌也正好", 0xFFB07FE8),
            new Mood("傍晚", "天快黑了，歌陪你回家", 0xFFE86A92),
            new Mood("夜晚", "夜里听歌，声音放轻", 0xFF5A6ACF),
            new Mood("深夜", "还没睡的人，歌陪你", 0xFF3D4A6B),
    };

    public static int count() {
        return MOODS.length;
    }

    public static Mood at(int index) {
        if (index < 0) index = 0;
        if (index >= MOODS.length) index = MOODS.length - 1;
        return MOODS[index];
    }

    public static int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < MOODS.length; i++) {
            if (MOODS[i].name.equals(name)) return i;
        }
        return -1;
    }

    /** 按当前时段给默认心情（时段词优先）：进卡未手动选过时用。 */
    public static int defaultIndex(int hourOfDay) {
        if (hourOfDay >= 5 && hourOfDay < 9) return indexOf("清晨");
        if (hourOfDay >= 9 && hourOfDay < 11) return indexOf("清新");
        if (hourOfDay >= 11 && hourOfDay < 13) return indexOf("午间");
        if (hourOfDay >= 13 && hourOfDay < 17) return indexOf("午后");
        if (hourOfDay >= 17 && hourOfDay < 19) return indexOf("傍晚");
        if (hourOfDay >= 19 && hourOfDay < 23) return indexOf("夜晚");
        return indexOf("深夜");
    }

    /** 向白混：ratio=0 原色、1 纯白。注：原 moodGradient（三段渐变组装）零调用已删（体检 A 片死代码）；
     * 本方法与 mixColors 自此无调用方，先留待裁，不在本片擅自扩删。 */
    public static int mixWhite(int color, float ratio) {
        return mixColors(color, 0xFFFFFFFF, ratio);
    }

    public static int mixColors(int a, int b, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int alpha = Math.round(android.graphics.Color.alpha(a) + (android.graphics.Color.alpha(b) - android.graphics.Color.alpha(a)) * r);
        int red = Math.round(android.graphics.Color.red(a) + (android.graphics.Color.red(b) - android.graphics.Color.red(a)) * r);
        int green = Math.round(android.graphics.Color.green(a) + (android.graphics.Color.green(b) - android.graphics.Color.green(a)) * r);
        int blue = Math.round(android.graphics.Color.blue(a) + (android.graphics.Color.blue(b) - android.graphics.Color.blue(a)) * r);
        return android.graphics.Color.argb(alpha, red, green, blue);
    }

    /** 换 alpha、保 RGB。 */
    public static int alphaColor(int color, float alpha) {
        int a = Math.round(255f * Math.max(0f, Math.min(1f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
