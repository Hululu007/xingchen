package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.music.MusicStore;
import com.github.catvod.utils.Prefers;

/**
 * 播放页设置持有层（批 1）：与 MusicStore 读写同一批 Prefers 键，存量用户值不丢；
 * 默认值按施工图 §0（瓜子 PlayerViewModel 初始值）。MusicStore 本体待其最后消费方
 * （首页／列表／设置页）重建批次再删，本类只收播放页要读的项。
 */
public final class MusicPlayerSettings {

    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_COVER_IMMERSIVE = "cover_immersive";
    public static final String THEME_APPLE_MUSIC = "apple_music";
    public static final String THEME_LUMINOUS_CD = "luminous_cd";
    public static final String THEME_LUMINOUS_CD_DARK = "luminous_cd_dark";
    public static final String THEME_LUMINOUS_CD_AUTO = "luminous_cd_auto";
    public static final String THEME_RECORD_SHELF = "record_shelf";

    /** 歌词模糊档位（与 MusicStore 同值域）：0 关 1 轻 2 中。 */
    public static final int BLUR_OFF = 0;
    public static final int BLUR_LIGHT = 1;
    public static final int BLUR_MEDIUM = 2;

    /** 流体速度档（§0：slow 0.6 / normal 1.0 / fast 2.5）。 */
    public static final int FLUID_SPEED_SLOW = 0;
    public static final int FLUID_SPEED_NORMAL = 1;
    public static final int FLUID_SPEED_FAST = 2;

    // 与 MusicStore 相同的 Prefers 键（逐字对齐，不许改名）
    private static final String K_PLAYER_THEME = "music_player_theme";
    private static final String K_FLUID_BG = "music_fluid_bg";
    private static final String K_ADVANCED_FX = "music_advanced_fx";
    // 树内此前没有流体速度键（MusicStore／工具面板均未存），批 1 新登记此键，默认 normal。
    private static final String K_FLUID_SPEED = "music_fluid_speed";

    private MusicPlayerSettings() {
    }

    private static boolean isKnownTheme(String theme) {
        return THEME_CLASSIC.equals(theme) || THEME_COVER_IMMERSIVE.equals(theme)
                || THEME_APPLE_MUSIC.equals(theme) || THEME_LUMINOUS_CD.equals(theme)
                || THEME_LUMINOUS_CD_DARK.equals(theme) || THEME_LUMINOUS_CD_AUTO.equals(theme)
                || THEME_RECORD_SHELF.equals(theme);
    }

    /**
     * 播放页主题 id：未存值＝星辰定案默认封面卡片 cover_immersive（压过瓜子 classic 默认）；
     * 已存非法值回落 classic（与 MusicStore.getPlayerTheme 读侧口径一致）。
     * 流光 CD 的 dark/auto 是独立主题 id（§4），MusicStore 的五 id 校验不认，本类放行。
     */
    public static String getTheme() {
        String theme = Prefers.getString(K_PLAYER_THEME, THEME_COVER_IMMERSIVE);
        if (theme == null || theme.isEmpty()) return THEME_COVER_IMMERSIVE;
        return isKnownTheme(theme) ? theme : THEME_CLASSIC;
    }

    public static void setTheme(String theme) {
        Prefers.put(K_PLAYER_THEME, isKnownTheme(theme) ? theme : THEME_CLASSIC);
    }

    /** 全屏歌词字号 sp（§0 默认 24，钳 16–40；与 MusicStore 同键同迁移口径，直接走它）。 */
    public static float getFullLyricFontSize() {
        return MusicStore.getLyricFontSize();
    }

    /** 封面（内嵌）歌词字号 sp（§0 默认 22，钳 14–32；与 MusicStore 同键同迁移口径）。 */
    public static float getCoverLyricFontSize() {
        return MusicStore.getCoverLyricFontSize();
    }

    /** 封面歌词模糊档（§0 默认 medium；与 MusicStore 同键）。 */
    public static int getCoverLyricBlurLevel() {
        return MusicStore.getCoverLyricBlurLevel();
    }

    /** 歌词模糊系数（§6.1：off 0 / light 0.45 / medium 1.0）。 */
    public static float blurCoefficient() {
        int level = getCoverLyricBlurLevel();
        if (level == BLUR_OFF) return 0f;
        if (level == BLUR_LIGHT) return 0.45f;
        return 1.0f;
    }

    /** 显示翻译副行（§0 默认开；与 MusicStore 同键）。 */
    public static boolean isShowTranslation() {
        return MusicStore.isShowTranslation();
    }

    /** 显示罗马音副行（§0 默认关；与 MusicStore 同键）。 */
    public static boolean isShowRomanization() {
        return MusicStore.isShowRomanization();
    }

    /** 歌词时间偏移 ms（§0 默认 0；与 MusicStore 同键同符号迁移口径）。 */
    public static int getLyricOffsetMs() {
        return MusicStore.getLyricOffsetMs();
    }

    /** 歌词字重（批 7 补线：0 标准／1 加粗／2 特粗；键 music_lyric_weight 在 MusicStore，此前只进了工具面板预览）。 */
    public static int getLyricWeight() {
        return MusicStore.getLyricWeight();
    }

    /** 歌词对齐（批 7 补线：0 左／1 中／2 右；键 music_lyric_align 在 MusicStore）。 */
    public static int getLyricAlign() {
        return MusicStore.getLyricAlign();
    }

    /** 主歌词（批 7 补线：0 原文为主／1 翻译为主／2 音译为主；键 music_lyric_primary 在 MusicStore）。 */
    public static int getLyricPrimary() {
        return MusicStore.getLyricPrimary();
    }

    /** 歌词高级动效（§0 默认开；与 MusicStore 同键）。 */
    public static boolean isAdvancedFx() {
        return Prefers.getBoolean(K_ADVANCED_FX, true);
    }

    /** 流体背景开关：单一事实源＝MusicStore.isFluidBg()（同键 music_fluid_bg、默认 false，线上已交付口径）；§0 瓜子默认开未采用，存量行为承接。 */
    public static boolean isFluidBg() {
        return MusicStore.isFluidBg();
    }

    public static void setFluidBg(boolean on) {
        Prefers.put(K_FLUID_BG, on);
    }

    /** 流体速度档（§0 默认 normal）。 */
    public static int getFluidSpeed() {
        int speed = Prefers.getInt(K_FLUID_SPEED, FLUID_SPEED_NORMAL);
        return Math.max(FLUID_SPEED_SLOW, Math.min(FLUID_SPEED_FAST, speed));
    }

    public static void setFluidSpeed(int speed) {
        Prefers.put(K_FLUID_SPEED, Math.max(FLUID_SPEED_SLOW, Math.min(FLUID_SPEED_FAST, speed)));
    }

    /** 流体速度倍率（§0：slow 0.6 / normal 1.0 / fast 2.5）。 */
    public static float fluidSpeedFactor() {
        int speed = getFluidSpeed();
        if (speed == FLUID_SPEED_SLOW) return 0.6f;
        if (speed == FLUID_SPEED_FAST) return 2.5f;
        return 1.0f;
    }
}
