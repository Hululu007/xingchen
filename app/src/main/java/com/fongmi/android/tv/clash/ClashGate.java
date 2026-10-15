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
                return ClashSettings.isEnabled(app) && MihomoManager.get().isRunning();
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
    }
}
