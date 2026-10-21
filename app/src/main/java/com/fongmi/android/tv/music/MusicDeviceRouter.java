package com.fongmi.android.tv.music;

import android.content.Context;
import android.media.MediaRouter;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放设备路由（B5）：框架 android.media.MediaRouter 的 LIVE_AUDIO 路由枚举与切换。
 * 无新增依赖；MediaRouter 不可用（取不到服务／抛错）时 create 返回空，调用方不画设备入口。
 */
public class MusicDeviceRouter {

    public static class Route {
        public final MediaRouter.RouteInfo info;
        public final String name;
        public final String description;
        public final boolean selected;

        Route(MediaRouter.RouteInfo info, String name, String description, boolean selected) {
            this.info = info;
            this.name = name;
            this.description = description;
            this.selected = selected;
        }
    }

    private final MediaRouter router;
    private final MediaRouter.SimpleCallback callback;

    /** 通道未通返回空——调用方以此为准：空就不画设备入口 */
    public static MusicDeviceRouter create(Context context, Runnable onChanged) {
        if (context == null) return null;
        try {
            MediaRouter mr = (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (mr == null) return null;
            return new MusicDeviceRouter(mr, onChanged);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private MusicDeviceRouter(MediaRouter router, Runnable onChanged) {
        this.router = router;
        this.callback = new MediaRouter.SimpleCallback() {
            @Override
            public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo info) {
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo info) {
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onRouteSelected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
                if (onChanged != null) onChanged.run();
            }

            @Override
            public void onRouteUnselected(MediaRouter router, int type, MediaRouter.RouteInfo info) {
                if (onChanged != null) onChanged.run();
            }
        };
        router.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
    }

    /** 当前可用的 LIVE_AUDIO 路由（已启用才列；当前选中项标 selected） */
    public List<Route> routes() {
        List<Route> out = new ArrayList<>();
        try {
            MediaRouter.RouteInfo selected = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
            int n = router.getRouteCount();
            for (int i = 0; i < n; i++) {
                MediaRouter.RouteInfo r;
                try {
                    r = router.getRouteAt(i);
                } catch (Throwable ignored) {
                    continue;
                }
                if (r == null || !r.isEnabled()) continue;
                CharSequence name = r.getName();
                CharSequence desc = r.getDescription();
                out.add(new Route(r, name == null ? "" : name.toString(),
                        desc == null ? "" : desc.toString(), r == selected));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 当前选中路由名（面板按钮／音量卡显示用；取不到返回空） */
    public String selectedName() {
        try {
            MediaRouter.RouteInfo r = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO);
            if (r == null || r.getName() == null) return "";
            return r.getName().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 切路由：不兼容的路由 selectRoute 会抛，吞掉不换（不做假切换） */
    public void select(Route route) {
        if (route == null || route.info == null) return;
        try {
            router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route.info);
        } catch (Throwable ignored) {
        }
    }

    public void destroy() {
        try {
            router.removeCallback(callback);
        } catch (Throwable ignored) {
        }
    }
}
