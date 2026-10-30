package com.fongmi.android.tv.hongguo.ui;

/**
 * 红果横屏页（官方 ShortSeriesLandActivity 的咱家对应件）：真独立
 * Activity——清单声明 userLandscape＋singleTop＋configChanges 全套
 * ＋PiP 支持（照抠查账 hongguo-orientation-dig-20261030.md §一）。
 * 与竖屏页 HongguoFeedActivity 同类共体（页面构成与事件链只有一
 * 套，装配在基类 initView 按 {@link #isLandPage()} 分家：横屏面走
 * 状态条/横排控制条/锁定/右侧抽屉，竖屏件全不显），不靠旋转重建、
 * 不靠 intent 旗冒充。
 *
 * <p>播放连续性走 HgPlayerPool：进页时领走竖屏页泊出的同一播放器
 * 续播（不重拉模型、不重开流），退出（返回键/传感器转竖）时写回
 * 交接单泊回池，栈下竖屏页领回落原条目续播（横屏里换过集则落到
 * 那一集）。横屏锁只活在本页实例内（官方锁是跨页单例＋退出清理，
 * 咱家等价近似的差异记在施工账 hongguo-land-align-20261030.md）。</p>
 */
public class HongguoLandActivity extends HongguoFeedActivity {

    @Override
    protected boolean isLandPage() {
        return true;
    }
}
