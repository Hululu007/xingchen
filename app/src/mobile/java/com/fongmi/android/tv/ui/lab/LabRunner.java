package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务起停（照参照物 LabRunner 的做法）：
 * ProcessBuilder("/system/bin/setsid","/system/bin/sh","-c",命令)，
 * cwd=包目录，PATH/LD_LIBRARY_PATH 指向环境与包内 bin；
 * pid 落盘＋/proc 恢复；输出进内存（上限）＋每包一份日志文件。
 */
public final class LabRunner {

    public interface Listener {
        void onOutput(String text);

        void onExit(int code);
    }

    /** 日志内存上限（参照物：最近 20 万字符） */
    private static final int LOG_CAP = 200_000;

    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, StringBuilder> LOGS = new ConcurrentHashMap<>();
    private static final Map<String, java.io.OutputStream> STDIN = new ConcurrentHashMap<>();
    /** 最近一次启动失败的人话原因（成功启动后清掉） */
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();

    public static String lastError(String pkgId) {
        return ERRORS.get(keyOf(pkgId));
    }

    private LabRunner() {
    }

    // ---------------- 状态 ----------------

    public static boolean isRunning(Context c, String pkgId) {
        String key = keyOf(pkgId);
        Process p = RUNNING.get(key);
        if (LabProc.alive(p)) return true;
        return LabProc.recoveredAliveKeys(c).contains(key);
    }

    public static int runningCount(Context c) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Process> e : RUNNING.entrySet()) {
            if (LabProc.alive(e.getValue())) keys.add(e.getKey());
        }
        keys.addAll(LabProc.recoveredAliveKeys(c));
        return keys.size();
    }

    /** 包最后一次分配的端口（port=auto 时记住，下次复用） */
    public static int portOf(Context c, String pkgId) {
        LabRegistry.Svc p = LabRegistry.get(c).svc(pkgId);
        return p == null ? 0 : p.port;
    }

    // ---------------- 启动 ----------------

    /**
     * 启动包的默认命令。先查环境（缺了抛人话，由 UI 引导装环境）。
     */
    public static void start(Context ctx, String pkgId, Listener listener) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
                if (pkg == null) throw new Exception("这个包已经不在了");
                LabManifest manifest = readManifest(c, pkgId);
                LabManifest.LabCommand cmd = manifest.defaultCommand();
                if (cmd == null || TextUtils.isEmpty(cmd.command)) throw new Exception("这个包没有可运行的命令");
                String runtime = pkg.runtime == null ? "none" : pkg.runtime;
                if (!"none".equals(runtime) && !LabRuntimeManager.isInstalled(c, runtime)) {
                    throw new Exception("缺" + LabRuntimeManager.label(runtime) + "，先把它装上");
                }
                ERRORS.remove(keyOf(pkgId));
                stop(c, pkgId);
                int port = allocPort(c, pkg, manifest);
                String command = expand(c, pkgId, cmd.command, port);
                runShell(c, pkgId, keyOf(pkgId), command, pkgId, listener);
                if (listener != null) listener.onOutput("");
            } catch (Exception e) {
                String m = e.getMessage();
                ERRORS.put(keyOf(pkgId), m == null ? "未知原因" : m);
                if (listener != null) {
                    listener.onOutput("启动失败：" + (m == null ? "未知原因" : m) + "\n");
                    listener.onExit(-1);
                }
            }
        }).start();
    }

    private static int allocPort(Context c, LabRegistry.Svc pkg, LabManifest manifest) {
        String policy = manifest.output == null ? "auto" : manifest.output.port;
        if (policy != null && policy.matches("\\d+")) {
            int fixed = Integer.parseInt(policy);
            if (pkg.port != fixed) {
                pkg.port = fixed;
                LabRegistry.get(c).upsertSvc(c, pkg);
            }
            return fixed;
        }
        if (pkg.port > 0 && portFree(pkg.port)) return pkg.port;
        int port = findFreePort();
        pkg.port = port;
        LabRegistry.get(c).upsertSvc(c, pkg);
        return port;
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (Exception e) {
            return 18080 + (int) (Math.random() * 4000);
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String expand(Context c, String pkgId, String command, int port) {
        File pkgDir = LabPaths.packageDir(c, pkgId);
        return command.replace("{port}", String.valueOf(port))
                .replace("{pkgDir}", pkgDir.getAbsolutePath())
                .replace("{filesDir}", c.getFilesDir().getAbsolutePath());
    }

    private static void runShell(Context c, String pkgId, String key, String command, String logName, Listener listener) {
        try {
            if (TextUtils.isEmpty(command)) throw new Exception("命令为空");
            File cwd = LabPaths.packageDir(c, pkgId);
            cwd.mkdirs();
            ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", command);
            builder.directory(cwd);
            builder.redirectErrorStream(true);
            applyEnv(c, pkgId, builder.environment());
            Process process = builder.start();
            RUNNING.put(key, process);
            try {
                STDIN.put(key, process.getOutputStream());
            } catch (Exception ignored) {
            }
            File log = LabProc.logFile(c, logName);
            ERRORS.remove(key);
            LabProc.track(c, key, LabProc.pidOf(process), log.getAbsolutePath());
            LabKeepAlive.refresh(c);
            pump(c, key, process, log, listener);
        } catch (Exception e) {
            if (listener != null) {
                String m = e.getMessage();
                listener.onOutput("启动失败：" + (m == null ? "未知原因" : m) + "\n");
                listener.onExit(-1);
            }
        }
    }

    private static void applyEnv(Context c, String pkgId, Map<String, String> env) {
        LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
        String runtime = pkg == null || pkg.runtime == null ? "none" : pkg.runtime;
        Set<String> path = new LinkedHashSet<>();
        if (!"none".equals(runtime)) path.add(LabRuntimeManager.binDir(c, runtime).getAbsolutePath());
        path.add(new File(LabPaths.packageDir(c, pkgId), "bin").getAbsolutePath());
        String old = env.get("PATH");
        if (old != null) path.add(old);
        path.add("/system/bin");
        path.add("/system/xbin");
        path.add("/vendor/bin");
        env.put("PATH", join(path, ":"));
        String bin = new File(LabPaths.packageDir(c, pkgId), "bin").getAbsolutePath();
        String oldLd = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", TextUtils.isEmpty(oldLd) ? bin : bin + ":" + oldLd);
    }

    private static String join(Set<String> s, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(x);
        }
        return sb.toString();
    }

    private static void pump(Context c, String key, Process process, File log, Listener listener) {
        appendLog(key, "$ 启动\n");
        appendFile(log, "$ 启动\n");
        new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) {
                    String s = new String(buf, 0, n);
                    appendLog(key, s);
                    appendFile(log, s);
                    if (listener != null) listener.onOutput(s);
                }
            } catch (Exception ignored) {
            }
            int code = -1;
            try {
                code = process.waitFor();
            } catch (Exception ignored) {
            }
            String end = "\n[进程退出，code=" + code + "]\n";
            appendLog(key, end);
            appendFile(log, end);
            RUNNING.remove(key);
            STDIN.remove(key);
            LabProc.untrack(c, key);
            LabKeepAlive.refresh(c);
            if (listener != null) listener.onExit(code);
        }).start();
    }

    private static void appendLog(String key, String s) {
        StringBuilder sb = LOGS.computeIfAbsent(key, k -> new StringBuilder());
        synchronized (sb) {
            sb.append(s);
            if (sb.length() > LOG_CAP) sb.delete(0, sb.length() - LOG_CAP);
        }
    }

    private static void appendFile(File log, String s) {
        try {
            if (log.getParentFile() != null) log.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(log, true);
                 OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                w.write(s);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- 停止 ----------------

    public static void stop(Context c, String pkgId) {
        String key = keyOf(pkgId);
        Process p = RUNNING.remove(key);
        java.io.OutputStream stdin = STDIN.remove(key);
        if (stdin != null) {
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
        }
        if (p != null) {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
            LabProc.killTree(LabProc.pidOf(p));
        }
        LabProc.stopByState(c, key);
        LabKeepAlive.refresh(c);
    }

    public static void stopAll(Context c) {
        List<String> keys = new ArrayList<>(RUNNING.keySet());
        for (String key : keys) stop(c, pkgIdOf(key));
        LabKeepAlive.refresh(c);
    }

    // ---------------- 日志 ----------------

    /** 每包一份自己的日志：内存＋落盘，留全 */
    public static String getLog(Context c, String pkgId) {
        String key = keyOf(pkgId);
        StringBuilder sb = LOGS.get(key);
        if (sb != null && sb.length() > 0) return sb.toString();
        File log = LabProc.logFile(c, pkgId);
        if (log.exists()) {
            try (FileInputStream in = new FileInputStream(log)) {
                byte[] buf = new byte[(int) Math.min(log.length(), 512 * 1024)];
                int n = in.read(buf);
                if (n > 0) return new String(buf, 0, n, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    public static void clearLog(Context c, String pkgId) {
        LOGS.remove(keyOf(pkgId));
        File log = LabProc.logFile(c, pkgId);
        if (log.exists()) log.delete();
    }

    // ---------------- 内部 ----------------

    private static String keyOf(String pkgId) {
        return "pkg/" + pkgId;
    }

    private static String pkgIdOf(String key) {
        return key.startsWith("pkg/") ? key.substring(4) : key;
    }

    private static LabManifest readManifest(Context c, String pkgId) throws Exception {
        File f = LabPaths.manifestOf(c, pkgId);
        if (!f.exists()) throw new Exception("包说明丢了，重装这个包试试");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return LabManifest.parse(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
    }
}
