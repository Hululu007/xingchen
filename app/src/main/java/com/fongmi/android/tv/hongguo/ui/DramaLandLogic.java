package com.fongmi.android.tv.hongguo.ui;

/**
 * 横屏进/退判定（纯逻辑、无 Android 依赖，供 JVM harness 直测真值表）。
 * 口径照抠查账 hongguo-orientation-dig-20261030.md：进横屏统一过
 * 总闸（滚动 idle／已起播／非倍速长按中／无弹窗）＋横版门
 * isSupportShowLandIcon（只放行横版画幅）；「全屏观看」钮对弹窗门
 * 豁免、传感器路不豁免；退横屏由传感器竖带触发、横屏锁开时拦截。
 * 咱家差异（已知、记账）：横屏锁只活在横屏页实例内（官方为跨页单例）。
 */
public final class DramaLandLogic {

    private DramaLandLogic() {
    }

    /**
     * 横版门：只有已知横版画幅（宽&gt;高&gt;0）放行。尺寸未知（0）一律
     * 不放行——官方判定读条目真实横竖，没有画幅信息就没有横屏去向。
     */
    public static boolean isLandEligible(int videoWidth, int videoHeight) {
        return videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight;
    }

    /** 朝向带：1＝横带（60–120／240–300）、0＝竖带（±30／150–210）、-1＝中间带。 */
    public static int orientationBand(int deg) {
        if (deg < 0) return -1;
        if (deg >= 60 && deg <= 120) return 1;
        if (deg >= 240 && deg <= 300) return 1;
        if (deg <= 30 || deg >= 330) return 0;
        if (deg >= 150 && deg <= 210) return 0;
        return -1;
    }

    /**
     * 进横屏总闸：滚动未 idle／播放器没起播／倍速长按中任一成立即拒；
     * 弹窗在场只对传感器路（fromButton=false）算拒，按钮路豁免。
     * 横版门不在本函数内，由调用方先把关。
     */
    public static boolean canEnterLand(boolean scrollIdle, boolean playerStarted,
                                       boolean speedBoosting, boolean popupShowing,
                                       boolean fromButton) {
        if (!scrollIdle) return false;
        if (!playerStarted) return false;
        if (speedBoosting) return false;
        return fromButton || !popupShowing;
    }

    /** 传感器退横屏：物理朝向已回竖带、且横屏锁未开，才退。 */
    public static boolean shouldSensorExitLand(boolean portraitBand, boolean locked) {
        return portraitBand && !locked;
    }

    // ---------- 交接领回判定（DramaPlayerPool 交接单 → 竖屏页 onResume 落点） ----------

    /** 对不上剧：交接单不归本页，整池释放、走常规回前台。 */
    public static final int RECLAIM_MISMATCH = 0;
    /** 集流同集：play() 领回引擎走迁移续播（同流不重开）。 */
    public static final int RECLAIM_SERIES_SAME = 1;
    /** 集流换过集：按交接单集序号跳到那一集续播。 */
    public static final int RECLAIM_SERIES_JUMP = 2;
    /** 流页同一条：当场领回同一播放器原地续播。 */
    public static final int RECLAIM_FEED_SAME = 3;
    /** 流页但横屏里换过集：领回引擎重开本条流。 */
    public static final int RECLAIM_FEED_DIFF = 4;

    /**
     * 领回判定真值表（纯函数，harness 直测）：
     * @param seriesContext 领回方是集流页（true）还是频道流页（false）
     * @param bagSeriesId   交接单的剧 id（null＝交接单损坏，按对不上处理）
     * @param ctxSeriesId   领回方当前语境的剧 id
     * @param bagVid        交接单里播放器装着的 vid
     * @param ctxVid        领回方当前条的 vid（流页判定用）
     * @param bagEpisodeIndex 交接单集序号（横屏退出时所在集）
     * @param ctxEpisodeIndex 领回方当前条集序号（集流判定用，&lt;0＝未定）
     */
    public static int reclaimDecision(boolean seriesContext, String bagSeriesId,
                                      String ctxSeriesId, String bagVid, String ctxVid,
                                      int bagEpisodeIndex, int ctxEpisodeIndex) {
        if (bagSeriesId == null || ctxSeriesId == null
                || !bagSeriesId.equals(ctxSeriesId)) {
            return RECLAIM_MISMATCH;
        }
        if (seriesContext) {
            return bagEpisodeIndex > 0 && ctxEpisodeIndex >= 0
                    && bagEpisodeIndex != ctxEpisodeIndex
                    ? RECLAIM_SERIES_JUMP : RECLAIM_SERIES_SAME;
        }
        return bagVid != null && bagVid.equals(ctxVid)
                ? RECLAIM_FEED_SAME : RECLAIM_FEED_DIFF;
    }
}
