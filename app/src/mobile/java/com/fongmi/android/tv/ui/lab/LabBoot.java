package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/**
 * 开机（App 启动）恢复：先恢复还活着的进程状态，再按实验室总开关拉起服务。
 * 自启门槛只有总开关一道（1.4.27 改定）：总开关开着，已部署服务一律拉起——
 * 每服务自己的「自启」标记（包声明 autoStartDefault／用户覆盖 autoStart）此前
 * 是第二道门，他的两个服务都没开过，于是总开关拨了跟没拨一样、静默不拉起，
 * 且没有任何提示。从本版起 boot 不再读 autoStartOf：标记字段与环境页开关照旧
 * 存读（存量数据不动），但不再决定起不起；总开关关着则一台都不拉。
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

    /**
     * 立即补跑一遍自启。两个调用点：①App.onCreate（主源集经反射调进来，
     * 抢在 HomeActivity 首读已选配置之前——已选配置若是实验室服务的本地
     * 回环地址，服务没起来时首读必然连不上、必弹「配置取得失败」）；
     * ②实验室首页把总开关拨上时（LabHomeActivity）。
     * 门槛只有总开关＋环境已装＋没在跑，失败经 LabRunner 落运行记录。
     * Node 宿主登记同步先做：配置加载随时可能调 RuntimeModule.NODEJS，
     * 登记必须先于它（与 schedule 同口径，重复登记同义覆盖）。
     */
    public static void kick(Context ctx) {
        LabNodeHost.register();
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                boot(c);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static void boot(Context c) {
        // 先恢复进程状态（死了的自动从注册表里清掉）
        LabProc.recoveredAliveKeys(c);
        LabRegistry reg = LabRegistry.get(c);
        if (!reg.masterAutoStart) return;
        List<LabRegistry.Svc> pkgs = reg.allServices();
        for (LabRegistry.Svc p : pkgs) {
            try {
                if (LabRunner.isRunning(c, p.id)) continue;
                String runtime = p.runtime == null ? "none" : p.runtime;
                if (!"none".equals(runtime) && !LabRuntimeManager.isInstalled(c, runtime)) continue;
                LabRunner.start(c, p.id, null);
            } catch (Exception ignored) {
            }
        }
        // 自定义命令里开了开机自动运行的，也拉起来（变量值走缓存）。
        // 与服务本体同一道门：只看总开关＋命令自己的 autoExecute，
        // 不再叠包级 autoStartOf（命令弹窗的说明已同步改，见 LabServiceActivity）。
        for (LabRegistry.Svc p : pkgs) {
            try {
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
