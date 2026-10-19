package com.fongmi.android.tv.bean.music;

/** 音乐音质：FreeJS quality 参数 → 显示规格 */
public enum MusicQuality {

    LOW("low", "128k"),
    STANDARD("standard", "320k"),
    HIGH("high", "无损"),
    SUPER("super", "母带");

    private final String key;
    private final String label;

    MusicQuality(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public static MusicQuality fromKey(String key) {
        if (key == null) return STANDARD;
        for (MusicQuality q : values()) {
            if (q.key.equalsIgnoreCase(key)) return q;
        }
        return STANDARD;
    }

    /** 播放器侧音质档位（MusicStore.QUALITY_*）→ 本枚举 */
    public static MusicQuality fromInt(int quality) {
        switch (quality) {
            case 0:
                return LOW;
            case 2:
                return HIGH;
            case 3:
                return SUPER;
            default:
                return STANDARD;
        }
    }
}
