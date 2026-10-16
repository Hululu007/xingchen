package com.fongmi.android.tv.emby;

import android.app.Activity;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.ui.view.EmbyHomeView;

/**
 * Emby 首页模式切换：点播首页 ↔ Emby 专属首页。
 *
 * 实现方式（只加逻辑，不动首页布局文件）：
 * enter() 把 UI 层的 EmbyHomeView 挂到点播首页 Fragment（VodFragment）根容器上做全屏覆盖，
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
            Fragment vod = findVodFragment((FragmentActivity) act);
            if (vod == null || !(vod.getView() instanceof ViewGroup)) {
                // 时序保护：AI 可能先跳首页再挂载，Fragment 还没就绪时延时重试
                if (show && retry < 3) App.post(() -> attach(true, serverId, retry + 1), 500);
                return;
            }
            ViewGroup root = (ViewGroup) vod.getView();
            View v = root.findViewWithTag(TAG_VIEW);
            EmbyHomeView home;
            if (v instanceof EmbyHomeView) {
                home = (EmbyHomeView) v;
            } else {
                home = new EmbyHomeView(act);
                home.setTag(TAG_VIEW);
                root.addView(home, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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

    /** 按类名找点播首页 Fragment（main 源集不直接引用 mobile 的 VodFragment 类）。 */
    private static Fragment findVodFragment(FragmentActivity act) {
        try {
            for (Fragment f : act.getSupportFragmentManager().getFragments()) {
                if (f != null && "VodFragment".equals(f.getClass().getSimpleName()) && f.getView() != null) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
