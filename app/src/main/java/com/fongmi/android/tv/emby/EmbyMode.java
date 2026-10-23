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
        EmbyStore.setLastEntered(serverId);
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

    /**
     * 首页重挂盖子：进程重启／主题 recreate 后 HomeActivity 是新实例、根布局里没有
     * 盖子视图，但 active 持久标志还在。initView/onResume 调这里，若仍在 Emby 模式
     * 且当前首页根布局里没有正在展示的盖子，就走与 enter 同一条 attach 路重挂；
     * attach 最终仍失败时由 attach 内的回滚清掉 active，不留半吊子状态。
     * 注意不能只看 EmbyHomeView.isShowing() 的静态旗：旧视图随旧 Activity 销毁后
     * 该旗可能残留 true，必须以当前 Activity 根布局里实际有没有盖子为准。
     */
    public static void reattachIfActive(Activity activity) {
        if (activity == null || !isActive()) return;
        String serverId = activeServerId();
        if (TextUtils.isEmpty(serverId)) return;
        try {
            ViewGroup root = findHomeRoot(activity);
            View v = root == null ? null : root.findViewWithTag(TAG_VIEW);
            if (v instanceof EmbyHomeView && EmbyHomeView.isShowing()
                    && v.getVisibility() == View.VISIBLE) return;
        } catch (Throwable ignored) {
        }
        App.post(() -> {
            EmbyHomeView.bindCurrent(serverId);
            attach(true, serverId);
        });
    }

    /**
     * enter 已写 active／上次进入记录、但盖子最终没挂上时的回滚：只清仍指向
     * 本次 serverId 的记录（期间用户已切到别的服／已退出时不动别人的状态），
     * 保证不出现「模式开着、盖子没了、悬浮球还被 isActive() 压住」的状态。
     */
    private static void rollbackEnter(String serverId) {
        try {
            if (TextUtils.isEmpty(serverId)) return;
            EmbyStore.EmbyServer active = EmbyStore.active();
            if (active != null && serverId.equals(active.id)) EmbyStore.setActive(null);
            EmbyStore.EmbyServer last = EmbyStore.lastEntered();
            if (last != null && serverId.equals(last.id)) EmbyStore.clearLastEntered();
        } catch (Throwable ignored) {
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
                // 重试到底仍找不到首页根布局＝盖子没盖上：回滚 enter 先写的
                // active／上次进入记录，模式干净退出，不许留半吊子状态。
                else if (show) rollbackEnter(serverId);
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
