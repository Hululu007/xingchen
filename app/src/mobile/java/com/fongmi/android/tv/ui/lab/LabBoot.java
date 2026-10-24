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
        // Node 内置桥的宿主判据登记（同步做，不等延迟 boot）：模块页检测与
        // 配置加载随时可能调 RuntimeModule.NODEJS.require()，登记必须先于它们。
        LabNodeHost.register();
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
        // 自启的对：自定义命令里开了开机自动运行的，也拉起来（变量值走缓存）
        // 双门槛是有意的：包级 autoStartOf 不开，这个包的任何命令都不自启；
        // 已在命令弹窗里把这一条写明（LabServiceActivity.showCustomCommandDialog），不是暗坑
        for (LabRegistry.Svc p : pkgs) {
            try {
                if (!LabRegistry.autoStartOf(p)) continue;
                List<LabCustomCommands.CustomCommand> customs = LabCustomCommands.list(c, p.id);
                if (customs.isEmpty()) continue;
                java.util.Map<String, String> cached = LabCustomCommands.varCache(c, p.id);
                for (LabCustomCommands.CustomCommand cc : customs) {
                    try {
                        if (!cc.autoExecute) continue;
                        if (LabRunner.isCommandRunning(c, p.id, cc.id)) continue;
                        LabRunner.startCommand(c, p.id, LabCustomCommands.toCommand(cc), cached, null);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        LabKeepAlive.refresh(c);
    }
}
