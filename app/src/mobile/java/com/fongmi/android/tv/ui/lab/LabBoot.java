package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * 开机（App 启动）恢复：照参照物，App 启动后延迟 3 秒——
 * 先恢复还活着的进程状态，再把开了自动启动的包拉起来。
 */
public final class LabBoot {

    private static boolean scheduled;

    private LabBoot() {
    }

    public static synchronized void schedule(Context ctx) {
        if (scheduled) return;
        scheduled = true;
        final Context c = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                boot(c);
            } catch (Exception ignored) {
            }
        }, 3000);
    }

    private static void boot(Context c) {
        // 先恢复进程状态（死了的自动从注册表里清掉）
        LabProc.recoveredAliveKeys(c);
        LabRegistry reg = LabRegistry.get(c);
        if (!reg.masterAutoStart) return;
        List<LabRegistry.Svc> pkgs = reg.allServices();
        for (LabRegistry.Svc p : pkgs) {
            try {
                if (!LabRegistry.autoStartOf(p)) continue;
                if (LabRunner.isRunning(c, p.id)) continue;
                String runtime = p.runtime == null ? "none" : p.runtime;
                if (!"none".equals(runtime) && !LabRuntimeManager.isInstalled(c, runtime)) continue;
                LabRunner.start(c, p.id, null);
            } catch (Exception ignored) {
            }
        }
        LabKeepAlive.refresh(c);
    }
}
