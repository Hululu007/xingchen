package com.fongmi.android.tv.emby;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.view.EmbyHomeView;

/**
 * Emby 首页模式切换：点播首页 ↔ Emby 专属首页。
 *
 * 实现方式（只加逻辑，不动首页布局文件）：
 * enter() 把 UI 层的 EmbyHomeView 挂到 HomeActivity 的根 RelativeLayout 上全屏覆盖
 * （整窗盖满：含状态栏条与胶囊导航周围；z 序插在胶囊导航之下，胶囊仍可见可点）；
 * 两个首页视图常驻、只做显隐切换（setActive），不重建页面；
 * exit() 隐藏专属首页，切回点播首页。
 * 线路管理弹窗的「Emby」分区点选后调 enter/exit（UI 层 ConfigHistoryDialog 接线）。
 */
public class EmbyMode {

    private static final String TAG_VIEW = "EmbyModeHomeView";

    /** 进入 Emby 专属首页。serverId 为空直接返回。 */
    public static void enter(String serverId) {
        if (TextUtils.isEmpty(serverId)) return;
        EmbyStore.EmbyServer s = EmbyStore.getServer(serverId);
        if (s == null || !s.isLogin()) return;
        EmbyStore.setActive(serverId);
        App.post(() -> {
            // 前景页是管理页等非首页时，Fragment 挂载扑空，这里先重绑当前实例兜底
            EmbyHomeView.bindCurrent(serverId);
            attach(true, serverId);
        });
    }

    /** 退出 Emby，切回点播首页。 */
    public static void exit() {
        // 守卫：视图没在展示且无激活服时空转，保证每次 tab 点击都安全调用、
        // 不会凭空创建隐藏视图写偏好；真正退出过一次后再次调用也是空转。
        if (!EmbyHomeView.isShowing() && !isActive()) return;
        EmbyStore.setActive(null);
        App.post(() -> {
            // 兜底：前景页不是 HomeActivity 时 attach 找不到视图，这里直接保证 setActive(false)
            EmbyHomeView.hideCurrent();
            attach(false, null);
        });
    }

    /** 当前是否有服处于激活态。 */
    public static boolean isActive() {
        try {
            return EmbyStore.active() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String activeServerId() {
        try {
            EmbyStore.EmbyServer s = EmbyStore.active();
            return s == null ? "" : s.id;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void attach(boolean show, String serverId) {
        attach(show, serverId, 0);
    }

    private static void attach(boolean show, String serverId, int retry) {
        try {
            Activity act = App.activity();
            if (!(act instanceof FragmentActivity)) return;
            // 挂载点：HomeActivity 根 RelativeLayout（整窗盖满，状态栏条与胶囊导航周围不再透壁纸）
            ViewGroup root = findHomeRoot(act);
            if (root == null) {
                // 时序保护：AI 可能先跳首页再挂载，根布局还没就绪时延时重试
                if (show && retry < 3) App.post(() -> attach(true, serverId, retry + 1), 500);
                return;
            }
            View nav = findNavigation(act);
            View v = root.findViewWithTag(TAG_VIEW);
            EmbyHomeView home;
            if (v instanceof EmbyHomeView) {
                home = (EmbyHomeView) v;
            } else {
                home = new EmbyHomeView(act);
                home.setTag(TAG_VIEW);
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (nav != null && nav.getParent() == root) {
                    // z 序插在胶囊导航之下：胶囊仍在最上层可见可点（退出/切回点播的路）
                    int idx = root.indexOfChild(nav);
                    if (idx >= 0) root.addView(home, idx, lp);
                    else root.addView(home, lp);
                } else {
                    root.addView(home, lp);
                }
            }
            if (show) {
                home.bind(serverId);
                home.setActive(true);
            } else {
                home.setActive(false);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    /** 按资源名找胶囊导航（main 源集不直接引用 mobile 的 R.id）。 */
    private static View findNavigation(Activity act) {
        try {
            int id = act.getResources().getIdentifier("navigation", "id", act.getPackageName());
            if (id != 0) return act.findViewById(id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** HomeActivity 根容器：胶囊导航的父布局。前景页不是首页时返回 null。 */
    private static ViewGroup findHomeRoot(Activity act) {
        try {
            View nav = findNavigation(act);
            if (nav != null && nav.getParent() instanceof ViewGroup) return (ViewGroup) nav.getParent();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
