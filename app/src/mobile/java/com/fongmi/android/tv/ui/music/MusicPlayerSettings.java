package com.fongmi.android.tv.ui.music;

import com.fongmi.android.tv.music.MusicStore;
import com.github.catvod.utils.Prefers;

/**
 * 播放页专属设置持有层（重做批二从零重写，规格 S4 §3）。
 * 与 MusicStore 读写同一批 Prefers 键、逐字对齐不许改名，存量用户值不丢。
 * 分工：主题／高级动效／流体速度是本类自有键（直接 Prefers 读写；流体开关写在本类、
 * 读委托总仓单一事实源）；其余歌词样式项是委托键，getter 直接转调 MusicStore 同名方法
 * （同键、同钳制、同迁移口径都在总仓里），本类不另存一份。
 * 默认值出处：瓜子施工图 §0（PlayerViewModel 初始值）；有意偏离一行见方法注
 * （流体默认关——星辰定案）。主题默认 2026-10-25 经用户纠正改回瓜子原值 classic
 * （瓜子 PlayerViewModel.java:1018 存档回落 "classic" 亲核；此前误设封面卡片已废）。
 */
public class MusicPlayerSettings {

    // ===== 主题 id（播放页唯一认的枚举集，7 个） =====
    public static final String THEME_CLASSIC = "classic";
    public static final String THEME_COVER_IMMERSIVE = "cover_immersive";
    public static final String THEME_APPLE_MUSIC = "apple_music";
    public static final String THEME_LUMINOUS_CD = "luminous_cd";
    public static final String THEME_LUMINOUS_CD_DARK = "luminous_cd_dark";
    public static final String THEME_LUMINOUS_CD_AUTO = "luminous_cd_auto";
    public static final String THEME_RECORD_SHELF = "record_shelf";

    // ===== 封面歌词模糊档 =====
    public static final int BLUR_OFF = 0;
    public static final int BLUR_LIGHT = 1;
    public static final int BLUR_MEDIUM = 2;

    // ===== 流体速度档 =====
    public static final int FLUID_SPEED_SLOW = 0;
    public static final int FLUID_SPEED_NORMAL = 1;
    public static final int FLUID_SPEED_FAST = 2;

    // ===== 自有键（与 MusicStore 同字符串、逐字对齐不许改名） =====
    private static final String K_THEME = "music_player_theme";
    private static final String K_FLUID_BG = "music_fluid_bg";
    private static final String K_ADVANCED_FX = "music_advanced_fx";
    /** 流体速度键只登记在本类：MusicStore 与工具面板都没有它，不许去总仓找。 */
    private static final String K_FLUID_SPEED = "music_fluid_speed";

    private MusicPlayerSettings() {
    }

    // ===== #1 主题（自有） =====

    public static boolean isKnownTheme(String theme) {
        return THEME_CLASSIC.equals(theme) || THEME_COVER_IMMERSIVE.equals(theme)
                || THEME_APPLE_MUSIC.equals(theme) || THEME_LUMINOUS_CD.equals(theme)
                || THEME_LUMINOUS_CD_DARK.equals(theme) || THEME_LUMINOUS_CD_AUTO.equals(theme)
                || THEME_RECORD_SHELF.equals(theme);
    }

    private static final String K_THEME_RESET_1436 = "music_theme_reset_1436";

    /**
     * 未存／空／非法值一律回落经典（瓜子默认，PlayerViewModel.java:1018 回落 "classic"）。
     * 1.4.36 播放页重做一次性作废旧存量：旧版本默认误设成封面卡片，受害用户存量里
     * 躺着 cover_immersive——首次读时清掉存量主题并落标记，之后用户自选照常保留。
     */
    public static String getTheme() {
        if (!Prefers.getBoolean(K_THEME_RESET_1436, false)) {
            Prefers.put(K_THEME_RESET_1436, true);
            Prefers.remove(K_THEME);
            return THEME_CLASSIC;
        }
        String theme = Prefers.getString(K_THEME, THEME_CLASSIC);
        return isKnownTheme(theme) ? theme : THEME_CLASSIC;
    }

    /** 先过 7 id 白名单，非法值写经典。 */
    public static void setTheme(String theme) {
        Prefers.put(K_THEME, isKnownTheme(theme) ? theme : THEME_CLASSIC);
    }

    // ===== #2 全屏歌词字号（委托；键 music_lyric_font_size，默认 24，钳 16–40） =====

    public static float getFullLyricFontSize() {
        return MusicStore.getLyricFontSize();
    }

    // ===== #3 封面歌词字号（委托；键 music_lyric_font_size_cover，默认 22，钳 14–32，带一次性存量迁移） =====

    public static float getCoverLyricFontSize() {
        return MusicStore.getCoverLyricFontSize();
    }

    // ===== #4 封面歌词模糊档（委托读档、本类算系数；默认 2 中档） =====

    public static int getCoverLyricBlurLevel() {
        return MusicStore.getCoverLyricBlurLevel();
    }

    /** 档→系数照瓜子：off 0／light 0.45／medium 1.0。 */
    public static float blurCoefficient() {
        switch (getCoverLyricBlurLevel()) {
            case BLUR_LIGHT:
                return 0.45f;
            case BLUR_MEDIUM:
                return 1.0f;
            default:
                return 0f;
        }
    }

    // ===== #5 显示翻译（委托；默认开） =====

    public static boolean isShowTranslation() {
        return MusicStore.isShowTranslation();
    }

    // ===== #6 显示音译（委托；默认关） =====

    public static boolean isShowRomanization() {
        return MusicStore.isShowRomanization();
    }

    // ===== #7 歌词时间偏移 ms（委托；默认 0，钳 ±3000，带符号迁移；消费口径＝显示位置＋offset） =====

    public static int getLyricOffsetMs() {
        return MusicStore.getLyricOffsetMs();
    }

    // ===== #8 歌词字重档（委托；0 标准(700)／1 加粗(800)／2 特粗(900)，默认 0） =====

    public static int getLyricWeight() {
        return MusicStore.getLyricWeight();
    }

    // ===== #9 歌词对齐（委托；0 左／1 中／2 右，默认 0 左） =====

    public static int getLyricAlign() {
        return MusicStore.getLyricAlign();
    }

    // ===== #10 主歌词（委托；0 原文／1 翻译／2 音译，默认 0） =====

    public static int getLyricPrimary() {
        return MusicStore.getLyricPrimary();
    }

    // ===== #11 高级动效（自有直读 Prefers；默认开。MusicStore 同键同默认另有一份读写，两处读同一键不冲突） =====

    public static boolean isAdvancedFx() {
        return Prefers.getBoolean(K_ADVANCED_FX, true);
    }

    // ===== #12 流体背景开关（读委托总仓单一事实源、默认关＝星辰定案；写在本类直写同键） =====

    public static boolean isFluidBg() {
        return MusicStore.isFluidBg();
    }

    public static void setFluidBg(boolean on) {
        Prefers.put(K_FLUID_BG, on);
    }

    // ===== #13 流体速度（自有；0 slow／1 normal／2 fast，默认 1，钳 0–2） =====

    public static int getFluidSpeed() {
        return Math.max(FLUID_SPEED_SLOW, Math.min(FLUID_SPEED_FAST,
                Prefers.getInt(K_FLUID_SPEED, FLUID_SPEED_NORMAL)));
    }

    public static void setFluidSpeed(int speed) {
        Prefers.put(K_FLUID_SPEED, Math.max(FLUID_SPEED_SLOW, Math.min(FLUID_SPEED_FAST, speed)));
    }

    /** 速度倍率：slow 0.6／normal 1.0／fast 2.5。 */
    public static float fluidSpeedFactor() {
        switch (getFluidSpeed()) {
            case FLUID_SPEED_SLOW:
                return 0.6f;
            case FLUID_SPEED_FAST:
                return 2.5f;
            default:
                return 1.0f;
        }
    }

    // ===== #14 流体节拍开关（委托总仓，瓜子 playerFluidBeatEnabled 默认开） =====

    public static boolean isFluidBeat() {
        return MusicStore.isFluidBeat();
    }

    public static void setFluidBeat(boolean on) {
        MusicStore.setFluidBeat(on);
    }

    // ===== #15 经典页内歌词锚点（自有；"top"/"center"/"bottom"，默认 "top"——瓜子 lyricAnchor 默认） =====

    private static final String K_LYRIC_ANCHOR = "music_lyric_anchor";
    public static final String ANCHOR_TOP = "top";
    public static final String ANCHOR_CENTER = "center";
    public static final String ANCHOR_BOTTOM = "bottom";

    public static String getLyricAnchor() {
        String a = Prefers.getString(K_LYRIC_ANCHOR, ANCHOR_TOP);
        return ANCHOR_CENTER.equals(a) || ANCHOR_BOTTOM.equals(a) ? a : ANCHOR_TOP;
    }

    public static void setLyricAnchor(String anchor) {
        Prefers.put(K_LYRIC_ANCHOR, ANCHOR_CENTER.equals(anchor) || ANCHOR_BOTTOM.equals(anchor) ? anchor : ANCHOR_TOP);
    }

    /** 锚点→行内对齐 fraction（瓜子 alignmentFraction：top 0.0／center 0.5／bottom 1.0）。 */
    public static float lyricAnchorFraction() {
        switch (getLyricAnchor()) {
            case ANCHOR_CENTER:
                return 0.5f;
            case ANCHOR_BOTTOM:
                return 1.0f;
            default:
                return 0.0f;
        }
    }

    /** 节拍响应倍率（喂包络的 beatScale）：slow 0.7／normal 1.0／fast 1.6。 */
    public static float fluidBeatFactor() {
        switch (getFluidSpeed()) {
            case FLUID_SPEED_SLOW:
                return 0.7f;
            case FLUID_SPEED_FAST:
                return 1.6f;
            default:
                return 1.0f;
        }
    }
}
