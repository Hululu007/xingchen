package com.fongmi.nodejs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 桥子进程拼装（桥接设计映射表 3/12）：把「监护线程怎么起 bin/node」
 * 的纯拼装从 NodeService 里拆出来——只依赖 java.*，离线单测能逐字段
 * 对映射表；NodeService 本体带 Android 依赖，不进单测。
 *
 * argv 契约：插件 JNI 形态是 [node, bootstrap, index, config, data,
 * bridgePort, token, runtimePath]，bootstrap 按下标 2..7 取参。桥的
 * 命令只在前面加一层 setsid（让子进程自成进程组，LabProc.killTree
 * 的进程组收尾才扫得动它再拉起的子进程，实验室 LabRunner 同款），
 * node 实际看到的 argv[0] 是 exec 的二进制本体路径、argv[1..7] 与
 * 插件逐字同；runtimePath 按映射表 12 换成实验室 bin/node 绝对路径
 * （bootstrap 只把它透传进 serverStarted，Client 侧 recordLoaded
 * 按第 1 步改判认这个本体）。
 *
 * env 口径（设计 3.2，照实验室 LabRunner.applyEnv）：PATH 头插运行时
 * bin 目录、尾接 /system/bin 等；LD_LIBRARY_PATH＝运行时 bin＋lib
 * （bin/node 的 RUNPATH 写死 Termux 前缀在星辰不存在，靠这一行找到
 * 同目录与 lib 软链目录里的 .so）。bootstrap 自己写死的
 * CATVOD_DISABLE_AUTOSTART/HOST/PORT/HOME 与 chdir 在脚本内，不在
 * 进程 env 里加。本拼装不设任何超时：桥按长驻设计（设计风险 4），
 * 调用分档超时（15/60/45 秒）在 NodeClient 里，不在本件。
 */
final class NodeProcessSpec {

    static final String SETSID = "/system/bin/setsid";

    private NodeProcessSpec() {
    }

    static List<String> command(String binary, String bootstrap, String index, String config, String data, int bridgePort, String token) {
        List<String> command = new ArrayList<>(9);
        command.add(SETSID);
        command.add(binary);
        command.add(bootstrap);
        command.add(index);
        command.add(config);
        command.add(data);
        command.add(String.valueOf(bridgePort));
        command.add(token);
        command.add(binary);
        return command;
    }

    /** 只产出要覆盖的两项（PATH、LD_LIBRARY_PATH），其余环境继承监护进程。 */
    static Map<String, String> environment(String binDir, String libDir, Map<String, String> inherited) {
        Map<String, String> env = new LinkedHashMap<>();
        LinkedHashSet<String> path = new LinkedHashSet<>();
        if (binDir != null && !binDir.isEmpty()) path.add(binDir);
        String oldPath = inherited == null ? null : inherited.get("PATH");
        if (oldPath != null) {
            for (String part : oldPath.split(":")) if (!part.isEmpty()) path.add(part);
        }
        path.add("/system/bin");
        path.add("/system/xbin");
        path.add("/vendor/bin");
        env.put("PATH", String.join(":", path));
        StringBuilder ld = new StringBuilder();
        if (binDir != null && !binDir.isEmpty()) ld.append(binDir);
        if (libDir != null && !libDir.isEmpty()) {
            if (ld.length() > 0) ld.append(File.pathSeparator);
            ld.append(libDir);
        }
        String oldLd = inherited == null ? null : inherited.get("LD_LIBRARY_PATH");
        if (oldLd != null && !oldLd.isEmpty()) {
            if (ld.length() > 0) ld.append(File.pathSeparator);
            ld.append(oldLd);
        }
        env.put("LD_LIBRARY_PATH", ld.toString());
        return env;
    }

    static ProcessBuilder builder(String binary, String bootstrap, String index, String config, String data, int bridgePort, String token, File dataDir, File logFile, String binDir, String libDir, Map<String, String> inheritedEnv) {
        ProcessBuilder builder = new ProcessBuilder(command(binary, bootstrap, index, config, data, bridgePort, token));
        builder.directory(dataDir);
        // 子进程输出必须有落点：默认管道没人读会写满堵死 node，
        // 合流落日志文件（实验室 logFile 口径，调用方给）。
        builder.redirectErrorStream(true);
        if (logFile != null) builder.redirectOutput(logFile);
        builder.environment().putAll(environment(binDir, libDir, inheritedEnv));
        return builder;
    }
}
