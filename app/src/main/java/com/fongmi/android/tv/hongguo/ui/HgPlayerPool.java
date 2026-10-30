package com.fongmi.android.tv.hongguo.ui;

import com.fongmi.android.tv.player.engine.ExoPlayerEngine;

/**
 * 按 vid 的共享播放器池＋交接单（官方进程级播放器池 qy3.b＋交接单例
 * fullscreen.d／ShortPlayerOrientationDataHelper 的咱家实现，抠查账
 * hongguo-orientation-dig-20261030.md §三/§四）：竖屏页进横屏时把
 * 播放器解绑入池（连同交接单：当前 vid／集序号／进度／播放意愿／
 * 弹幕开关／倍速），横屏页取走同一播放器对象续播——不重拉模型、
 * 不重开流、进度长在播放器里；退出横屏时同路写回，竖屏页领回后
 * 按集序号对位（横屏里换过集就落到那一集续播，官方
 * resumeFromLandPage 同型）。
 *
 * <p>单槽：同一时刻只有一个持有者泊出（进横屏的永远是栈顶实例）。
 * 泊出者负责先摘自己的 Player.Listener 再入池，领走者加自己的
 * listener——回调不许进错实例。池中引擎的归属在领走前归泊出方：
 * 泊出方销毁时不许 release 它、也不许 closeAll 关掉它在播的流；
 * 新泊覆盖旧泊时旧引擎由池释放（防异常路径泄漏）。全部方法只在
 * 主线程调用（Activity 生命周期与点击链），synchronized 只是
 * 防御性收口。</p>
 */
public final class HgPlayerPool {

    /** 交接单：随播放器一起过手的播放/UI 状态快照。 */
    public static final class Bag {
        /** 条目所属剧 id（领回方按它认领，认错剧不接）。 */
        public String seriesId;
        /** 泊出时播放器里正装着的 vid（领走方据此判「同流续播」还是「换集重开」）。 */
        public String vid;
        /** 泊出时所在集的集序号（集流集序号；短片为其所属集序号）。 */
        public int episodeIndex;
        /** 泊出时播放位置（毫秒）：进度本体长在播放器里，此值只作对账/兜底。 */
        public long positionMs;
        /** 泊出时的播放意愿（暂停态进/出横屏保持暂停）。 */
        public boolean playWhenReady;
        /** 泊出时弹幕开关（与 HgStore 同源，此值供领回方对账回刷自家渲染层）。 */
        public boolean danmakuOn;
        /** 泊出时播放器倍速（倍速本就按 vid 随播放器过手，此值作对账）。 */
        public float speed = 1f;
    }

    private static ExoPlayerEngine parkedEngine;
    private static Bag parkedBag;

    private HgPlayerPool() {
    }

    /** 泊入：槽里已有未领走的引擎（异常路径）先释放，不许泄漏。 */
    public static synchronized void park(Bag bag, ExoPlayerEngine engine) {
        if (engine == null) {
            parkedBag = bag;
            return;
        }
        if (parkedEngine != null && parkedEngine != engine) {
            try {
                parkedEngine.release();
            } catch (Throwable ignored) {
            }
        }
        parkedEngine = engine;
        parkedBag = bag;
    }

    /** 只看交接单、不领走（认领判定用）。 */
    public static synchronized Bag peekBag() {
        return parkedBag;
    }

    /** 领走引擎（交接单一并清掉）：没有泊出物时返回 null。 */
    public static synchronized ExoPlayerEngine takeEngine() {
        ExoPlayerEngine e = parkedEngine;
        parkedEngine = null;
        parkedBag = null;
        return e;
    }

    /** 认领失败/对不上剧：连引擎带交接单整清（引擎释放），不留脏状态。 */
    public static synchronized void releaseParked() {
        if (parkedEngine != null) {
            try {
                parkedEngine.release();
            } catch (Throwable ignored) {
            }
        }
        parkedEngine = null;
        parkedBag = null;
    }

    /** 池里有没有泊出物（诊断/防崩扫查用）。 */
    public static synchronized boolean hasParked() {
        return parkedEngine != null || parkedBag != null;
    }
}
