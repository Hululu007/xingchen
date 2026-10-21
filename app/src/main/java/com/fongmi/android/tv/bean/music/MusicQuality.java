package com.fongmi.android.tv.bean.music;

/**
 * 音乐音质四档：key 是给 FreeJS 插件的 quality 参数（不变），
 * label 是显示名（真规格词）：128k／320k／FLAC／母带。
 * 落雪映射在 LxMusicSource.lxQuality：128k/320k/flac/flac24bit（母带＝flac24bit）。
 */
public enum MusicQuality {

    LOW("low", "128k"),
    STANDARD("standard", "320k"),
    HIGH("high", "FLAC"),
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

    /** 本枚举 → 播放器侧音质档位（MusicStore.QUALITY_*，与 fromInt 互逆） */
    public int toInt() {
        switch (this) {
            case LOW:
                return 0;
            case HIGH:
                return 2;
            case SUPER:
                return 3;
            default:
                return 1;
        }
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

    /**
     * 源声明的档字符串 → 本枚举（面板“按歌列档”用；认不出的返回 null，调用方决定回落）。
     * 归一与 FreeJsBridge.qualityTagOf 同款：小写、去 -/_ 分隔符；判定顺序不能乱——
     * 24bit（含 flac24bit）与 hires 先于 flac，否则 flac24bit 会被误认成 HIGH。
     * 覆盖：落雪 qualitys（128k／320k／flac／flac24bit）、MusicFree 协议键
     * （low／standard／high／super，依次对应 128k／320k／无损／超高）。
     */
    public static MusicQuality fromSourceKey(String raw) {
        if (raw == null) return null;
        String k = raw.toLowerCase().replace("-", "").replace("_", "");
        if (k.isEmpty()) return null;
        if (k.contains("24bit") || k.contains("hires") || k.equals("super")) return SUPER;
        if (k.contains("flac") || k.equals("high")) return HIGH;
        if (k.contains("320") || k.equals("standard")) return STANDARD;
        if (k.contains("128") || k.equals("low")) return LOW;
        return null;
    }
}
