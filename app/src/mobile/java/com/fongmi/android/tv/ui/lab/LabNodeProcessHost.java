package com.fongmi.android.tv.ui.lab;

import android.content.Context;

import com.github.catvod.utils.NodeProcessHost;

import java.io.File;

/**
 * Node 内置桥的进程宿主实现（桥接设计第 3 步接缝）：把实验室现成的
 * 路径推导（LabRuntimeManager.binDir/libDir，与第 1 步 LabNodeHost 的
 * binary 推导同源）、进程跟踪（LabProc，桥专用键 node-bridge）与
 * 第 1 步风险 2 登记（mark/clearNodeBridgeInUse）接到 catvod 的
 * NodeProcessHost 登记口上。监护版 NodeService 在 main 源集，只认
 * 登记口；本类在 mobile 源集，由 NodeService 按类名触发加载，
 * 静态块自登记（类名靠 proguard-rules.pro 的 keep 保住）。
 *
 * 每个方法自兜底：实验室判据抛任何异常都收成不在/空/no-op，
 * 绝不把判据异常打穿进桥的起进程链。
 */
public final class LabNodeProcessHost {

    /** 桥在实验室进程跟踪里的专用键：环境与桥都只有一份，键也只有一个。 */
    private static final String TRACK_KEY = "node-bridge";

    static {
        register();
    }

    private LabNodeProcessHost() {
    }

    public static void register() {
        NodeProcessHost.register(new NodeProcessHost.Host() {
            @Override
            public File binDir(Context context) {
                try {
                    return LabRuntimeManager.binDir(context, "nodejs");
                } catch (Throwable e) {
                    return null;
                }
            }

            @Override
            public File libDir(Context context) {
                try {
                    return LabRuntimeManager.libDir(context, "nodejs");
                } catch (Throwable e) {
                    return null;
                }
            }

            @Override
            public void markInUse(Context context, int pid) {
                try {
                    LabRuntimeManager.markNodeBridgeInUse(context, pid);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void clearInUse(Context context, int pid) {
                try {
                    LabRuntimeManager.clearNodeBridgeInUse(context, pid);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public File logFile(Context context) {
                try {
                    return LabProc.logFile(context, TRACK_KEY);
                } catch (Throwable e) {
                    return null;
                }
            }

            @Override
            public void track(Context context, int pid, String logPath) {
                try {
                    LabProc.track(context, TRACK_KEY, pid, logPath);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void untrack(Context context) {
                try {
                    LabProc.untrack(context, TRACK_KEY);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void reapLeftover(Context context) {
                try {
                    // 实验室现成口径：recoveredAliveKeys 按 pid＋启动时间核对
                    // 仍活着的才留在表里，命中桥的键才按进程组收（风险 1）。
                    if (LabProc.recoveredAliveKeys(context).contains(TRACK_KEY)) {
                        LabProc.stopByState(context, TRACK_KEY);
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void killTree(int pid) {
                try {
                    LabProc.killTree(pid);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public int pidOf(Process process) {
                return LabProc.pidOf(process);
            }
        });
    }
}
