package com.fongmi.android.tv.clash;

import android.content.Context;

import com.github.catvod.net.SourceGate;

/**
 * app 侧把节点策略注册进 catvod 的 SourceGate（模块方向：app → catvod）。
 * 在 Startup（XingChenProxy.apply 同区域）调用一次。
 */
public final class ClashGate {

    private ClashGate() {
    }

    public static void register(Context context) {
        final Context app = context.getApplicationContext();
        SourceGate.register(new SourceGate.Provider() {
            @Override
            public boolean enabled() {
                // 门控 = 内核在跑 且（总开关开 或 任一 Emby 服开了走节点）。
                // Emby 单服走节点不再要求总开关先开：EmbyApi 在发请求前按需拉起内核；
                // 其它源维持原语义（总开关关 → 它们的按源开关不生效）。
                if (!MihomoManager.get().isRunning()) return false;
                return ClashSettings.isEnabled(app)
                        || SourceProxyPolicy.anyEnabled(app, SourceProxyPolicy.KIND_EMBY);
            }

            @Override
            public int port() {
                return ClashSettings.getMixedPort(app);
            }

            @Override
            public boolean globalMode() {
                return ClashSettings.isGlobalMode(app);
            }

            @Override
            public boolean shouldProxy(String kind, String id) {
                return SourceProxyPolicy.shouldProxy(app, kind, id);
            }
        });
        // Emby 按 host 认领：Exo 取流线程、Glide 线程无打点，按请求 host 反查开了走节点的服
        // 阅读按 host 认领：漫画图片（Glide 线程）、听书音频（Exo 取流线程）无打点，
        // 按请求 host 反查图片/音频登记时的书源归属（ReadingProxyGate）。
        // 两路都走同一套 Provider 决策，不另起平行开关。
        ReadingProxyGate.setPolicy(ReadingProxyPolicy.android(app));
        SourceGate.registerHostClaim(new SourceGate.HostClaim() {
            @Override
            public String claim(String host) {
                String e = com.fongmi.android.tv.emby.EmbyProxyGate.claim(host, app);
                if (e != null) return e;
                String r = ReadingProxyGate.claim(host);
                if (r == null) return null;
                try {
                    if (!MihomoManager.get().isRunning()
                            && !MihomoManager.get().ensureRunning(app)) return null;
                } catch (Throwable ignored) {
                    return null;
                }
                return r;
            }
        });
    }
}
