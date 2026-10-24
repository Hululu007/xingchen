package com.github.catvod.utils;

import android.content.Context;

import java.io.File;

/**
 * Node 内置桥的进程宿主登记口（桥接设计第 3 步接缝）：监护版 NodeService
 * 在宿主 App 的 main 源集里，起子进程要用的实验室能力（bin/node 路径
 * 推导、进程跟踪落盘、进程组收尾、「使用中」登记）全在 mobile 源集的
 * 实验室包里，main 直引会破 leanback 变体的编译，裸反射又会被 R8 改名
 * 打穿（实验室类没有 keep 规则）——与第 1 步 RuntimeModule.NodeHost
 * 同一个处置：catvod 层只开登记口，实验室侧在进程内登记实现进来，
 * NodeService 只认登记口不认实验室类。
 *
 * 登记时机：实验室实现类被类名加载（Class.forName）时在静态块里自登记，
 * NodeService 起进程前负责触发那一次加载；未登记（leanback 没有实验室、
 * 或登记失败）一律按宿主不在处理，NodeService 据此走 MissingException
 * 老口径（没装），不许吞成空结果假装可用。
 *
 * 实现侧纪律：每个方法内部自兜底（实验室判据抛任何异常都收成
 * 不在/空/no-op），登记口的调用方不再二次判异常。
 */
public final class NodeProcessHost {

    public interface Host {

        /** 实验室 Node 环境 bin 目录（bin/node 本体所在，与第 1 步 NodeHost.binary 的推导同源）；不在返回 null。 */
        File binDir(Context context);

        /** 实验室 Node 环境 lib 目录（.so 软链落点，供 LD_LIBRARY_PATH）；不在返回 null。 */
        File libDir(Context context);

        /** 第 1 步风险 2 登记：桥子进程已起，记下 pid。 */
        void markInUse(Context context, int pid);

        /** 第 1 步风险 2 注销：只注销自己那一代（pid 对不上不动）。 */
        void clearInUse(Context context, int pid);

        /** 桥子进程输出落点（实验室日志口径）；取不到返回 null，调用方回落自有路径。 */
        File logFile(Context context);

        /** 风险 1 落盘：子进程 pid＋启动时间入实验室进程跟踪（供下次启动回收残留）。 */
        void track(Context context, int pid, String logPath);

        /** 子进程已收尾，撤掉它的进程跟踪。 */
        void untrack(Context context);

        /** 风险 1 回收：上一代登记的桥子进程若按 pid＋启动时间核对仍活着，先按进程组收掉。 */
        void reapLeftover(Context context);

        /** 按进程组收尾（子孙先杀），扫子进程可能再拉起的子进程。 */
        void killTree(int pid);

        /** 取 Process 的真实 pid；取不到返回 -1。 */
        int pidOf(Process process);
    }

    private static volatile Host host;

    private NodeProcessHost() {
    }

    public static void register(Host impl) {
        host = impl;
    }

    public static Host get() {
        return host;
    }
}
