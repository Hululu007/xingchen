package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.clash.ClashSettings;
import com.fongmi.android.tv.clash.MihomoManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        /**
         * 进程已确认起跑：runShell 里 builder.start() 成功、RUNNING 登记之后触发。
         * 给「启动中…」这类乐观态一个翻回「运行中」的时机——onExit 只在进程退出时
         * 才来，常驻服务等不到它。默认空实现，老实现不用改。
         */
        default void onStarted() {
        }
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

    // ---------------- 按命令起停（上游 LabCommandSheet 口径） ----------------

    /** 命令键：pkg/<pkgId>/cmd/<cmdId>（服务默认命令沿用旧 keyOf 语义，不动） */
    public static String commandKey(String pkgId, String cmdId) {
        return "cmd/" + pkgId + "/" + cmdId;
    }

    public static boolean isCommandRunning(Context c, String pkgId, String cmdId) {
        String key = commandKey(pkgId, cmdId);
        Process p = RUNNING.get(key);
        if (LabProc.alive(p)) return true;
        return LabProc.recoveredAliveKeys(c).contains(key);
    }

    public static void stopCommand(Context c, String pkgId, String cmdId) {
        stopKey(c, commandKey(pkgId, cmdId));
    }

    /** 运行中条目（运行中对话框用）：key → 人话名 */
    public static List<String[]> runningEntries(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(RUNNING.keySet());
        keys.addAll(LabProc.recoveredAliveKeys(c));
        for (String key : keys) {
            out.add(new String[]{key, keyLabel(c, key)});
        }
        return out;
    }

    private static String keyLabel(Context c, String key) {
        try {
            if (key.startsWith("cmd/")) {
                String[] parts = key.split("/", 3);
                if (parts.length == 3) {
                    LabRegistry.Svc s = LabRegistry.get(c).svc(parts[1]);
                    String name = s == null ? parts[1] : s.name;
                    return name + " / " + parts[2];
                }
            } else if (key.startsWith("pkg/")) {
                String id = key.substring(4);
                LabRegistry.Svc s = LabRegistry.get(c).svc(id);
                return s == null ? id : s.name;
            } else if (key.startsWith("term/")) {
                String id = key.substring(5);
                LabRegistry.Svc s = LabRegistry.get(c).svc(id);
                return "终端：" + (s == null ? id : s.name);
            }
        } catch (Exception ignored) {
        }
        return key;
    }

    /**
     * 按命令启动（变量表/自定义命令/快捷运行都走这里）。
     *
     * @param cmd    命令（包命令或自定义命令转成的 LabCommand）
     * @param vars   变量值（可为 null）
     */
    public static void startCommand(Context ctx, String pkgId, LabManifest.LabCommand cmd,
                                    Map<String, String> vars, Listener listener) {
        final Context c = ctx.getApplicationContext();
        final String key = commandKey(pkgId, cmd == null || cmd.id == null ? "cmd" : cmd.id);
        new Thread(() -> {
            try {
                LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
                if (pkg == null) throw new Exception("这个包已经不在了");
                String runtime = pkg.runtime == null ? "none" : pkg.runtime;
                if (!"none".equals(runtime) && !LabRuntimeManager.isInstalled(c, runtime)) {
                    throw new Exception("缺" + LabRuntimeManager.label(runtime) + "，先把它装上");
                }
                checkMinVersion(c, cmd);
                stopKey(c, key);
                int port = allocPort(c, pkg, readManifestQuiet(c, pkgId));
                // 包设置值先垫底，同名变量再覆盖（上游变量值缓存复用口径）
                Map<String, String> merged = new java.util.HashMap<>(LabCustomCommands.settingsOf(c, pkgId));
                if (vars != null) merged.putAll(vars);
                ensureDependency(c, pkgId, cmd);
                String command = expand(c, pkgId, cmd.command, port, merged);
                ERRORS.remove(key);
                runShell(c, pkgId, key, command, pkgId, listener);
            } catch (Exception e) {
                String m = e.getMessage();
                String fail = "启动失败：" + (m == null ? "未知原因" : m) + "\n";
                // 正在看这条命令实时输出的页面也要拿到失败原因（总线另一半在 pump）
                LabLiveOutputActivity.LiveBus.emit(key, fail);
                LabLiveOutputActivity.LiveBus.emitExit(key, -1);
                if (listener != null) {
                    listener.onOutput(fail);
                    listener.onExit(-1);
                }
            }
        }).start();
    }

    /** min_version 门控：App 版本不够就提示升级（上游 Command.isSupported 口径） */
    private static void checkMinVersion(Context c, LabManifest.LabCommand cmd) throws Exception {
        if (cmd == null || cmd.minVersion <= 0) return;
        try {
            int vc = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            if (vc < cmd.minVersion) {
                throw new Exception("这条命令要新版星辰才跑得动，先更新 App");
            }
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("新版星辰")) throw e;
        }
    }

    /** 命令依赖下载：download 声明的文件缺了就先下后跑 */
    private static void ensureDependency(Context c, String pkgId, LabManifest.LabCommand cmd) throws Exception {
        if (cmd == null || cmd.download == null || TextUtils.isEmpty(cmd.download.url)) return;
        String rel = TextUtils.isEmpty(cmd.download.file) ? fileNameOf(cmd.download.url) : cmd.download.file;
        File dest = new File(LabPaths.packageDir(c, pkgId), rel);
        if (dest.exists()) return;
        downloadFile(cmd.download.url, dest);
    }

    private static String fileNameOf(String url) {
        String u = url.split("\\?")[0];
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(i + 1) : "dep.bin";
    }

    private static void downloadFile(String url, File dest) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            applyProxyConn(conn);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("依赖下不下来（" + code + "）");
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            try (java.io.InputStream in = new java.io.BufferedInputStream(conn.getInputStream());
                 java.io.OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void applyProxyConn(java.net.HttpURLConnection conn) {
        // 走 JVM 全局代理太重；下载走直连（与既有 importFromUrl 行为一致）
    }

    // ---------------- 交互终端 ----------------

    /**
     * 给包开一个交互 shell（终端页用）：cwd=包目录，环境与服务进程一致。
     * 返回 stdin 输出流写把柄由调用方持有；进程按 term/<pkgId> 跟踪，随「全部停止」一起停。
     */
    public static Process startTerminal(Context ctx, String pkgId) throws Exception {
        final Context c = ctx.getApplicationContext();
        LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
        if (pkg == null) throw new Exception("这个包已经不在了");
        File cwd = LabPaths.packageDir(c, pkgId);
        cwd.mkdirs();
        String key = "term/" + pkgId;
        stopKey(c, key);
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
        builder.directory(cwd);
        builder.redirectErrorStream(true);
        applyEnv(c, pkgId, builder.environment());
        Process p = builder.start();
        RUNNING.put(key, p);
        try {
            STDIN.put(key, p.getOutputStream());
        } catch (Exception ignored) {
        }
        LabProc.track(c, key, LabProc.pidOf(p), LabProc.logFile(c, key).getAbsolutePath());
        LabKeepAlive.refresh(c);
        return p;
    }

    /** 往指定 key 的进程 stdin 写字节（终端页用） */
    public static void writeStdin(String key, byte[] data) throws Exception {
        java.io.OutputStream out = STDIN.get(key);
        if (out == null) throw new Exception("进程不在了");
        out.write(data);
        out.flush();
    }

    /** 停止任意 key（pkg/cmd/term 通用） */
    public static void stopKey(Context c, String key) {
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
        String policy = manifest == null || manifest.output == null ? "auto" : manifest.output.port;
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
        return expand(c, pkgId, command, port, null);
    }

    /**
     * 命令展开（上游 LabRunner.expand 口径的精简版）：
     * 1) {var} 变量替换（vars 优先，包设置值已在调用方合并进来），支持嵌套（最多 5 层），
     *    ${...} 跳过不碰；没值的占位原样保留（缺失变量由调用方先弹变量表补齐）。
     * 2) 特殊占位：{serverPort}＝本次分配端口；{tvPath}{dataPath}{cachePath}
     *    {envRootPath}{wwwroot}{sdcard}；旧 {port}{pkgDir}{filesDir} 照旧。
     */
    private static String expand(Context c, String pkgId, String command, int port,
                                 Map<String, String> vars) {
        if (command == null) return "";
        String out = expandVars(command, vars, 0);
        File pkgDir = LabPaths.packageDir(c, pkgId);
        File labRoot = LabPaths.root(c);
        return out
                .replace("{serverPort}", String.valueOf(port))
                .replace("{tvPath}", new File(c.getFilesDir(), "TV").getAbsolutePath())
                .replace("{dataPath}", new File(pkgDir, "data").getAbsolutePath())
                .replace("{cachePath}", c.getCacheDir().getAbsolutePath())
                .replace("{envRootPath}", pkgDir.getAbsolutePath())
                .replace("{wwwroot}", new File(labRoot, "wwwroot").getAbsolutePath())
                .replace("{sdcard}", "/storage/emulated/0")
                .replace("{port}", String.valueOf(port))
                .replace("{pkgDir}", pkgDir.getAbsolutePath())
                .replace("{filesDir}", c.getFilesDir().getAbsolutePath());
    }

    private static String expandVars(String text, Map<String, String> vars, int depth) {
        if (text == null || depth > 5) return text;
        Matcher m = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}").matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (m.find()) {
            if (m.start() > 0 && text.charAt(m.start() - 1) == '$') {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String val = vars == null ? null : vars.get(m.group(1));
            if (val == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(expandVars(val, vars, depth + 1)));
            changed = true;
        }
        m.appendTail(sb);
        if (!changed) return sb.toString();
        return expandVars(sb.toString(), vars, depth + 1);
    }

    private static void runShell(Context c, String pkgId, String key, String command, String logName, Listener listener) {
        try {
            if (TextUtils.isEmpty(command)) throw new Exception("命令为空");
            File cwd = LabPaths.packageDir(c, pkgId);
            cwd.mkdirs();
            File log = LabProc.logFile(c, logName);
            // 相对路径兜底：接在 expand() 之后（命令已是最终形态，{pkgDir} 等已展开成绝对路径），
            // ProcessBuilder 拼 shell 之前。包根下找不到、files/ 下有的相对路径补上 files/ 前缀。
            String aligned = LabPathAlign.align(cwd, command);
            if (!aligned.equals(command)) {
                String pathNote = "命令路径自动对齐到 files/：原命令里的脚本在包根下找不到、files/ 下有，已补上 files/ 前缀";
                appendLog(key, pathNote + "\n");
                appendFile(log, pathNote + "\n");
                command = aligned;
            }
            ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", command);
            builder.directory(cwd);
            builder.redirectErrorStream(true);
            applyEnv(c, pkgId, builder.environment());
            String nodeNote = applyNodeProxy(c, pkgId, builder.environment());
            Process process = builder.start();
            RUNNING.put(key, process);
            try {
                STDIN.put(key, process.getOutputStream());
            } catch (Exception ignored) {
            }
            // 进程已确认起跑：通知 UI 把「启动中…」翻回「运行中」。
            // （onExit 只在进程退出时才来，常驻服务等不到它。）
            if (listener != null) listener.onStarted();
            if (nodeNote != null) {
                appendLog(key, nodeNote + "\n");
                appendFile(log, nodeNote + "\n");
            }
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
        String lib = LabRuntimeManager.libDir(c, runtime).getAbsolutePath();
        String ld = bin + File.pathSeparator + lib;
        env.put("LD_LIBRARY_PATH", TextUtils.isEmpty(oldLd) ? ld : ld + File.pathSeparator + oldLd);
        // 全局代理（实验室设置页）：开着就给进程 http_proxy/https_proxy/no_proxy
        if (LabSettings.proxyOn(c)) {
            String port = LabSettings.proxyPort(c);
            String proxy = "http://127.0.0.1:" + port;
            env.put("http_proxy", proxy);
            env.put("https_proxy", proxy);
            env.put("HTTP_PROXY", proxy);
            env.put("HTTPS_PROXY", proxy);
            String no = LabSettings.proxyNoProxy(c);
            if (!TextUtils.isEmpty(no)) {
                env.put("no_proxy", no);
                env.put("NO_PROXY", no);
            }
        }
    }

    /**
     * 单服务节点开关：该服务开了开关，启动命令时给进程注入本机节点代理
     * （默影视上游 applyGlobalProxy 口径：大小写变体＋已有值不覆盖＋mihomo 跳过）。
     * 变量清单：http_proxy/https_proxy/HTTP_PROXY/HTTPS_PROXY＝http://127.0.0.1:<混合端口>；
     * all_proxy/ALL_PROXY＝socks5://127.0.0.1:<混合端口+1>；no_proxy/NO_PROXY＝实验室设置里的例外值。
     * 地址取法照壳子现有口径（ClashSettings.localProxyUrl）。
     *
     * @return 节点开关开着但没注入时的人话说明（节点没在跑→直连启动，留行记录）；
     * 开关没开 / mihomo 服务本身 / 注入成功都返回 null。
     */
    static String applyNodeProxy(Context c, String pkgId, Map<String, String> env) {
        LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
        if (!nodeProxyWanted(pkg)) return null;
        if (!MihomoManager.get().isRunning()) {
            return "「节点」开着，但节点还没跑，这次直连启动；等节点起来后下次启动就走节点";
        }
        int mixed = ClashSettings.getMixedPort(c);
        if (mixed <= 0) return null;
        for (Map.Entry<String, String> e : nodeProxyVars(mixed, LabSettings.proxyNoProxy(c)).entrySet()) {
            if (!env.containsKey(e.getKey())) env.put(e.getKey(), e.getValue());
        }
        return null;
    }

    /** 该服务是否要走节点：开了开关、且不是 mihomo 服务本身（防自环） */
    static boolean nodeProxyWanted(LabRegistry.Svc pkg) {
        if (pkg == null || !pkg.nodeProxy) return false;
        return !"mihomo".equals(pkg.runtime) && !"mihomo".equals(pkg.id);
    }

    /** 节点代理变量表（纯函数，便于单测）：混合端口 + socks 端口（混合+1）＋例外域名 */
    static Map<String, String> nodeProxyVars(int mixed, String noProxy) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String http = "http://127.0.0.1:" + mixed;
        String socks = "socks5://127.0.0.1:" + (mixed + 1);
        out.put("http_proxy", http);
        out.put("https_proxy", http);
        out.put("HTTP_PROXY", http);
        out.put("HTTPS_PROXY", http);
        out.put("all_proxy", socks);
        out.put("ALL_PROXY", socks);
        if (!TextUtils.isEmpty(noProxy)) {
            out.put("no_proxy", noProxy);
            out.put("NO_PROXY", noProxy);
        }
        return out;
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
                    // 实时输出页订的是总线：以前只有订阅没有发布，页面永远等不到新输出
                    LabLiveOutputActivity.LiveBus.emit(key, s);
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
            LabLiveOutputActivity.LiveBus.emit(key, end);
            LabLiveOutputActivity.LiveBus.emitExit(key, code);
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
        stopKey(c, keyOf(pkgId));
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

    private static LabManifest readManifestQuiet(Context c, String pkgId) {
        try {
            return readManifest(c, pkgId);
        } catch (Exception e) {
            return null;
        }
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
