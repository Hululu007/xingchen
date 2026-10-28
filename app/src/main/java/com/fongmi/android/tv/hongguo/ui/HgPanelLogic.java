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

    /** 长按快进的临时倍速（官方 SpeedTipsView「2倍速快进中」定版）。 */
    public static final float BOOST_SPEED = 2f;

    /** 档位标签：整数档去小数（1x/2x/3x），小数档直拼（0.75x/1.25x/1.5x）。 */
    public static String speedLabel(float speed) {
        if (speed == (int) speed) return (int) speed + "x";
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
}
