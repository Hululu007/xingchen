package com.fongmi.android.tv.hongguo.ui;

import android.app.Activity;
import android.graphics.Color;
import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 新短剧各页窗口的统一口径（共享层，各页 onCreate 只调这一处）：
 * 边到边沉浸＋状态栏/导航栏全透明（页面自己的背景/画面透上去，不许出现
 * 单独一条异色带）＋系统栏图标明暗按本页明暗显式钉死——不许吃主题缺省：
 * HkPage 主题带 windowLightStatusBar=true（黑图标），深色顶的页面吃缺省
 * 就是黑图标压深底、看不清；浅色页同理必须显式声明。
 */
public final class HgWindowStyle {

    private HgWindowStyle() {
    }

    /**
     * @param lightStatusBars true=状态栏区域是浅底、用深色图标；false=深底/视频、用白图标
     * @param lightNavBars    同上，对导航栏区域
     */
    public static void apply(Activity activity, boolean lightStatusBars, boolean lightNavBars) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat ctl = new WindowInsetsControllerCompat(
                window, window.getDecorView());
        ctl.setAppearanceLightStatusBars(lightStatusBars);
        ctl.setAppearanceLightNavigationBars(lightNavBars);
    }
}
