package com.fongmi.android.tv.hongguo.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 更多面板与长按手势的纯逻辑（无 Android 依赖，离线套件可直接验）：
 * 中间带判定、倍速档表、清晰度档序、定时关闭状态文案。
 *
 * <p>口径来源：中间带＝官方 F6 的水平居中带（本地缺省带宽比 1/3，
 * dig-morepanel §一）；倍速档表照他 2026-10-28 实拍（0.75/1/1.25/1.5/2/3，
 * 不是 dig 的本地缺省表——实拍优先）；清晰度档名照官方 tq3/b 定版
 * （纯数字 P 档、高→低）；定时四档照官方 ScheduledStopPlayOptionType
 * （CLOSED/PLAY_CURRENT_SERIES/FIXED_TIME 30 分钟/DIY_TIME 自定义）。</p>
 */
public final class HgPanelLogic {

    private HgPanelLogic() {
    }

    // ---------- 长按中间带 ----------

    /** 中间带带宽占屏宽比（官方 MorePanelLongPressV715 缺省 mode 的 d()＝1/3）。 */
    public static final float CENTER_BAND_RATIO = 1f / 3f;

    /**
     * 点 x 是否落在水平居中带内：带＝[(W−带宽)/2, (W+带宽)/2]（官方 F6 公式）。
     * width 无效（≤0）时判带外，落到快进分支，不开面板。
     */
    public static boolean inCenterBand(float x, float width) {
        if (width <= 0) return false;
        float band = width * CENTER_BAND_RATIO;
        float from = (width - band) / 2f;
        float to = band + from;
        return x >= from && x <= to;
    }

    // ---------- 倍速 ----------

    /** 倍速档表（他实拍定版，升序）。长按快进的 2x 是临时覆盖、不入此表语义。 */
    public static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    /** 横屏页倍速面板档表（官方 xr3.f 定版、自上而下）：与竖屏实拍档表是两张表，不混。 */
    public static final float[] LAND_SPEEDS = {2f, 1.75f, 1.5f, 1.25f, 1f, 0.75f};

    /** 长按快进的临时倍速（官方 SpeedTipsView「2倍速快进中」定版）。 */
    public static final float BOOST_SPEED = 2f;

    /** 档位标签：整数档去小数（1x/2x/3x），小数档直拼（0.75x/1.25x/1.5x）。 */
    public static String speedLabel(float speed) {
        if (speed == (int) speed) return (int) speed + "x";
        return Float.toString(speed) + "x";
    }

    /**
     * 横屏条倍速位文案（官方 ca_ 的 hls 位口径，kr3/t0.x0 定版）：1 倍速显
     * 「倍速」二字（与布局静态文本同），其余显浮点原样＋x（2.0x/1.25x/0.75x），
     * 与 speedLabel 的整数去小数口径是两回事，不混用。
     */
    public static String landSpeedLabel(float speed) {
        if (speed == 1f) return "倍速";
        return Float.toString(speed) + "x";
    }

    /** 速度在档表中的下标；不在表内回落 1x 的下标（官方找不到回落下标 1 同口径）。 */
    public static int speedIndex(float speed) {
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == speed) return i;
        }
        return 1;
    }

    // ---------- 清晰度 ----------

    /** 官方档位序（高→低），与 HgVideoModel.firstPlayableEntry 的序一致。 */
    public static final String[] DEFINITION_ORDER = {"1080p", "720p", "540p", "480p", "360p"};

    /** 档名显示：纯数字 P 档（720p→720P），官方 tq3/b 定版、无「超清」类文案。 */
    public static String definitionLabel(String definition) {
        return definition == null ? "" : definition.toUpperCase(Locale.US);
    }

    /**
     * 可用档按官方序高→低排：已知档按 DEFINITION_ORDER，未知档保留
     * 出现顺序跟在后面；去重、跳空。
     */
    public static List<String> orderedDefinitions(Collection<String> definitions) {
        LinkedHashSet<String> rest = new LinkedHashSet<>();
        if (definitions != null) {
            for (String d : definitions) if (d != null && !d.isEmpty()) rest.add(d);
        }
        List<String> out = new ArrayList<>();
        for (String known : DEFINITION_ORDER) {
            if (rest.remove(known)) out.add(known);
        }
        out.addAll(rest);
        return out;
    }

    // ---------- 定时关闭 ----------

    public static final int TIMER_OFF = 0;
    /** 播完本剧：本剧最后一集播完时停播退出（不自动连播/不进剧）。 */
    public static final int TIMER_SERIES_END = 1;
    /** 固定 30 分钟（官方 FIXED_TIME 定版）。 */
    public static final int TIMER_FIXED = 2;
    /** 自定义分钟（官方 DIY_TIME）。 */
    public static final int TIMER_CUSTOM = 3;

    public static final int FIXED_MINUTES = 30;

    /** 面板定时行右侧的状态文案：未设显「关闭」，其余显档名/分钟数。 */
    public static String timerStateText(int mode, int minutes) {
        switch (mode) {
            case TIMER_SERIES_END:
                return "播完本剧";
            case TIMER_FIXED:
                return FIXED_MINUTES + "分钟";
            case TIMER_CUSTOM:
                return minutes > 0 ? minutes + "分钟" : "自定义";
            case TIMER_OFF:
            default:
                return "关闭";
        }
    }

    /** 计时档的延迟毫秒（FIXED/CUSTOM）；非计时档（关闭/播完本剧）返回 -1。 */
    public static long timerDelayMs(int mode, int minutes) {
        if (mode == TIMER_FIXED) return FIXED_MINUTES * 60_000L;
        if (mode == TIMER_CUSTOM && minutes > 0) return minutes * 60_000L;
        return -1;
    }

    // ---------- 集数与条文案（2026-10-30 定稿口径，全局统一） ----------

    /** 集数短文案：完结「全N集」、未完结「更新至N集」；总数未知返回空串。 */
    public static String countText(boolean finished, int total) {
        if (total <= 0) return "";
        return finished ? "全" + total + "集" : "更新至" + total + "集";
    }

    /**
     * 首页观看完整条文案：短剧/漫剧按 contentType 分（1004=漫剧，与
     * DramaItem 侧判读同值），合集走「观看完整视频」；带集数后缀。
     */
    public static String watchBarText(boolean album, int contentType,
                                      boolean finished, int total) {
        String head = album ? "观看完整视频"
                : contentType == 1004 ? "观看完整漫剧" : "观看完整短剧";
        String count = countText(finished, total);
        return count.isEmpty() ? head : head + " · " + count;
    }

    /** 播放页选集条文案：选集 · 已完结 · 全N集 ／ 选集 · 更新至N集。 */
    public static String episodeBarText(boolean finished, int total) {
        if (total <= 0) return "选集";
        return finished ? "选集 · 已完结 · 全" + total + "集"
                : "选集 · 更新至" + total + "集";
    }

    // ---------- 弹幕设置（竖屏：面板弹幕开后出的「弹幕设置」行 → 设置单） ----------
    // 构成照官方 DanmakuOptionDialog 竖屏形态定版（dig 账
    // panel-danmaku-settings-20261028.md）：不透明度/移动速度/显示区域/
    // 字体大小四滑杆＋恢复默认，常显。批三 C 卡 37 补注：屏蔽彩色与清屏
    // 展示两项的「设置行」在官方由服务端旗标门控，但两键本身与缺省值在
    // 官方包内有据（jadx2 kmp_video_danmaku/config/a.java：
    // key_colorful_danmaku_blocked/key_show_danmaku_in_immersive 均缺省
    // false），本批按功能键落码（HgStore 新键＋FeedActivity 生效点），
    // 设置行在咱家弹层常显——与官方旗标门控的显隐差记批三 C 账。

    /** 不透明度百分比界：官方滑杆 max 80＋偏移 20 → 20%–100%；缺省 100%（存储 alpha 255）。 */
    public static final int DM_OPACITY_MIN = 20;
    public static final int DM_OPACITY_MAX = 100;
    public static final int DM_OPACITY_DEFAULT = 100;

    /** 移动速度五档标签（官方串数组：慢/较慢/适中/较快/快）。 */
    public static final String[] DM_SPEED_LABELS = {"慢", "较慢", "适中", "较快", "快"};

    /** 移动速度五档倍率（官方 DanmakuSpeed 定版：0.5/0.7/1.0/1.25/1.5），下标与标签表同序。 */
    public static final float[] DM_SPEED_FACTORS = {0.5f, 0.7f, 1f, 1.25f, 1.5f};

    /** 移动速度缺省档下标（适中＝NORMAL 1.0x，官方存储缺省）。 */
    public static final int DM_SPEED_DEFAULT_INDEX = 2;

    /** 字体大小五档标签（官方串数组：小/较小/适中/较大/大）。 */
    public static final String[] DM_FONT_LABELS = {"小", "较小", "适中", "较大", "大"};

    /** 字体大小五档字号（官方 DanmakuTextSize 定版：12/14/16/18/20），下标与标签表同序。 */
    public static final int[] DM_FONT_SIZES = {12, 14, 16, 18, 20};

    /** 字体大小缺省档下标（适中＝DEFAULT 16，官方存储缺省）。 */
    public static final int DM_FONT_DEFAULT_INDEX = 2;

    /** 显示区域行数界（官方竖屏 DanmakuLineCount：1–4 行）。 */
    public static final int DM_LINES_MIN = 1;
    public static final int DM_LINES_MAX = 4;

    /**
     * 显示区域缺省行数＝3（自家定）：官方缺省走宿主注入配置（wp1.g），
     * 官方包内无静态值可抠（留痕见汇总账）；3 与咱家渲染层此前实际
     * 生效的缺省行数同值，换口径不跳变。
     */
    public static final int DM_LINES_DEFAULT = 3;

    /** 不透明度百分比收界（20–100）。 */
    public static int clampDanmakuOpacity(int percent) {
        return Math.max(DM_OPACITY_MIN, Math.min(DM_OPACITY_MAX, percent));
    }

    /** 档下标收界（[0, count)）；count 非法回 0。 */
    public static int clampDanmakuIndex(int index, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(count - 1, index));
    }

    /** 显示区域行数收界（1–4）。 */
    public static int clampDanmakuLines(int lines) {
        return Math.max(DM_LINES_MIN, Math.min(DM_LINES_MAX, lines));
    }

    /** 不透明度百分比 → 渲染层透明度（0＝不透明、1＝全透明）：1 − percent/100。 */
    public static float danmakuTransparency(int percent) {
        return 1f - clampDanmakuOpacity(percent) / 100f;
    }

    /** 移动速度档下标 → 倍率。 */
    public static float danmakuSpeedFactor(int index) {
        return DM_SPEED_FACTORS[clampDanmakuIndex(index, DM_SPEED_FACTORS.length)];
    }

    /** 移动速度档下标 → 标签。 */
    public static String danmakuSpeedLabel(int index) {
        return DM_SPEED_LABELS[clampDanmakuIndex(index, DM_SPEED_LABELS.length)];
    }

    /** 字体大小档下标 → 标签。 */
    public static String danmakuFontLabel(int index) {
        return DM_FONT_LABELS[clampDanmakuIndex(index, DM_FONT_LABELS.length)];
    }

    /** 字体大小档下标 → 相对缩放（以适中 16 为 1.0 基准：0.75/0.875/1/1.125/1.25）。 */
    public static float danmakuFontScale(int index) {
        return DM_FONT_SIZES[clampDanmakuIndex(index, DM_FONT_SIZES.length)] / 16f;
    }

    /** 显示区域值文案（官方「%d行」定版）。 */
    public static String danmakuAreaText(int lines) {
        return clampDanmakuLines(lines) + "行";
    }

    // ---------- 显示区域横屏档（批三 C 卡 37：官方 key_line_space） ----------
    // 官方 DanmakuLineSpace 枚举（jadx2 一手）：SINGLE=1/QUARTER=2/
    // HALF=3/THREE_QUARTER=4，存储缺省 QUARTER；语义是横屏显示区高度
    // 档。官方设置弹层同一根「显示区域」滑杆：竖屏读写行数（1–4）、
    // 横屏读写此档（DanmakuOptionDialog:712 起横竖分工实证）。咱家横屏
    // 行数口径未抠到（分册二 #11），横屏只走此档、不编行数。

    /** 显示区域横屏档标签（下标 0–3 对应 SINGLE/QUARTER/HALF/THREE_QUARTER）。 */
    public static final String[] DM_AREA_LABELS = {"单行", "1/4屏", "1/2屏", "3/4屏"};

    /** 显示区域横屏档缺省下标（QUARTER，官方存储缺省）。 */
    public static final int DM_AREA_DEFAULT_INDEX = 1;

    /** 显示区域档下标 → 标签。 */
    public static String danmakuAreaLabel(int index) {
        return DM_AREA_LABELS[clampDanmakuIndex(index, DM_AREA_LABELS.length)];
    }

    /**
     * 显示区域档下标 → 引擎滚动区高度比（DanmakuConfig.scrollAreaRatio
     * 口径：0–1）。SINGLE 档返回 -1＝单行模式哨兵（消费侧改走
     * maxScrollLines＝1），其余三档返回 0.25/0.5/0.75。
     */
    public static float danmakuAreaRatio(int index) {
        switch (clampDanmakuIndex(index, DM_AREA_LABELS.length)) {
            case 0:
                return -1f;
            case 2:
                return 0.5f;
            case 3:
                return 0.75f;
            case 1:
            default:
                return 0.25f;
        }
    }

    /**
     * 「彩色弹幕」判定（屏蔽彩色项的过滤口径）：带颜色且非纯白即彩色。
     * 咱家弹幕项缺省色是白（解析层 HgCommentParser 缺省 #FFFFFF），
     * 官方彩色弹幕＝服务端下发的非白彩字，同口径。
     */
    public static boolean isColorfulDanmaku(String colorHex) {
        if (colorHex == null) return false;
        String c = colorHex.trim();
        if (c.isEmpty()) return false;
        if (c.startsWith("#")) c = c.substring(1);
        return !c.equalsIgnoreCase("FFFFFF") && !c.equalsIgnoreCase("FFF");
    }
}
