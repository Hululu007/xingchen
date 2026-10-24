package com.fongmi.nodejs;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Process;

import androidx.annotation.Keep;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.NodeProcessHost;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.RuntimeModule;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Keep
public class NodeService extends ContextWrapper {

    public static final String ACTION_START = "com.fongmi.nodejs.START";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_BRIDGE_PORT = "bridgePort";
    public static final String EXTRA_TOKEN = "token";
    private static final String HEADER_TOKEN = "X-CatVod-Token";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = OkHttp.client().newBuilder().proxy(Proxy.NO_PROXY).connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.SECONDS).build();

    private final AtomicBoolean started = new AtomicBoolean();

    public NodeService(Context context) {
        super(context);
    }

    public void start(Intent intent) {
        if (intent == null || !ACTION_START.equals(intent.getAction()) || !started.compareAndSet(false, true)) return;
        String index = intent.getStringExtra(EXTRA_INDEX);
        String config = intent.getStringExtra(EXTRA_CONFIG);
        String data = intent.getStringExtra(EXTRA_DATA);
        int bridgePort = intent.getIntExtra(EXTRA_BRIDGE_PORT, 0);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        new Thread(() -> start(index, config, data, bridgePort, token), "nodejs-runtime").start();
    }

    private void start(String index, String config, String data, int bridgePort, String token) {
        String message;
        RuntimeModule.MissingException missing = null;
        try {
            File indexFile = requirePrivateFile(index);
            File configFile = requirePrivateFile(config);
            File dataDir = requirePrivateDirectory(data);
            int code = startRuntime(indexFile, configFile, dataDir, bridgePort, token);
            message = "Node exited with code " + code;
        } catch (Throwable e) {
            missing = RuntimeModule.MissingException.find(e);
            message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        reportError(bridgePort, token, message, missing);
        Process.killProcess(Process.myPid());
    }

    // 桥接缝（设计映射表 3/12/16，全桥唯一新写面）：插件形态在这里
    // clean()+load() 把 libnode.so 装进监护进程、再 JNI node::Start
    // 阻塞到脚本退出；桥改成监护线程 exec 实验室 bin/node 子进程并
    // waitFor——argv 契约逐字不变（拼装在 NodeProcessSpec），退出码
    // 照旧回给 start() 走 nodeError 上报＋监护进程自尽，净语义与插件
    // 一致（脚本退 0 同样报 "Node exited with code 0"）。
    private int startRuntime(File indexFile, File configFile, File dataDir, int bridgePort, String token) throws IOException {
        File bootstrap = extractBootstrap();
        NodeProcessHost.Host host = processHost();
        // 本体路径只认实验室登记口的推导（与第 1 步 recordLoaded 改判
        // 认的 bin/node 同源），不另起推导；登记不在/本体不在＝实验室
        // 没装，抛插件老路同一个 MissingException（start() 带
        // missingModule 上报），不许吞成空结果假装可用。
        File binDir = host == null ? null : host.binDir(this);
        File binary = binDir == null ? null : new File(binDir, "node");
        if (binary == null || !binary.isFile()) throw new RuntimeModule.MissingException(RuntimeModule.NODEJS);
        // 风险 1：起新一代之前，先按 pid＋启动时间回收上一代残留孤儿
        // （监护进程被强杀/自尽时子进程没人收，靠落盘跟踪在下次启动补收）。
        host.reapLeftover(this);
        File libDir = host.libDir(this);
        File log = host.logFile(this);
        if (log == null) {
            File runtimeDir = new File(new File(getFilesDir(), "nodejs"), "runtime");
            if (!runtimeDir.isDirectory()) runtimeDir.mkdirs();
            log = new File(runtimeDir, "node-output.log");
        }
        ProcessBuilder builder = NodeProcessSpec.builder(binary.getAbsolutePath(), bootstrap.getAbsolutePath(),
                indexFile.getAbsolutePath(), configFile.getAbsolutePath(), dataDir.getAbsolutePath(),
                bridgePort, token, dataDir, log,
                binDir.getAbsolutePath(), libDir == null ? null : libDir.getAbsolutePath(), System.getenv());
        java.lang.Process process = builder.start();
        int pid = host.pidOf(process);
        if (pid > 0) {
            // 风险 2 登记（实验室重装/卸载时连桥一起停）＋风险 1 落盘跟踪。
            host.markInUse(this, pid);
            host.track(this, pid, log.getAbsolutePath());
        }
        try {
            // 桥不给子进程写 stdin：关掉写端，读 stdin 的脚本直接得 EOF。
            process.getOutputStream().close();
        } catch (Exception ignored) {
        }
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Node process wait interrupted", e);
        } finally {
            // 退出收尾：子进程可能再拉子进程，按进程组扫干净；
            // 登记与跟踪只销自己这一代。
            if (pid > 0) {
                host.killTree(pid);
                host.clearInUse(this, pid);
                host.untrack(this);
            }
        }
    }

    /**
     * 进程宿主登记口：实验室实现（mobile 源集 LabNodeProcessHost）在
     * 类加载时自登记，本类在 main 源集不能直引它，按类名触发一次加载
     * （与 AiActionExecutor 加载 LabHomeActivity 同款口径；类名靠
     * proguard keep 保住）。取不到＝宿主不在，调用方按没装处理。
     */
    private static NodeProcessHost.Host processHost() {
        NodeProcessHost.Host host = NodeProcessHost.get();
        if (host == null) {
            try {
                Class.forName("com.fongmi.android.tv.ui.lab.LabNodeProcessHost");
            } catch (Throwable ignored) {
            }
            host = NodeProcessHost.get();
        }
        return host;
    }

    private File requirePrivateFile(String path) throws IOException {
        File file = requirePrivatePath(path);
        if (!file.isFile()) throw new IOException("Node bundle file is missing");
        return file;
    }

    private File requirePrivateDirectory(String path) throws IOException {
        File file = requirePrivatePath(path);
        if (!file.isDirectory() && !file.mkdirs() && !file.isDirectory()) throw new IOException("Unable to create Node data directory");
        return file;
    }

    private File requirePrivatePath(String path) throws IOException {
        if (path == null || path.isEmpty()) throw new IOException("Node path is empty");
        File root = new File(getFilesDir(), "nodejs").getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("Node path is outside app storage");
        return file;
    }

    private File extractBootstrap() throws IOException {
        File target = new File(getFilesDir(), "nodejs/runtime/bootstrap-v1.js");
        String bootstrap = RuntimeModule.NODEJS.readAsset("nodejs/bootstrap.js");
        Path.writeAtomically(target, bootstrap.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private void reportError(int port, String token, String message, RuntimeModule.MissingException missing) {
        if (port <= 0) return;
        try (Response ignored = CLIENT.newCall(errorRequest(port, token, message, missing)).execute()) {
        } catch (Exception ignored) {
        }
    }

    private Request errorRequest(int port, String token, String message, RuntimeModule.MissingException missing) throws JSONException {
        JSONObject options = new JSONObject().put("token", token).put("message", message);
        if (missing != null) options.put("missingModule", missing.module.id);
        JSONObject body = new JSONObject().put("action", "nodeError").put("opt", options);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        return new Request.Builder().url("http://127.0.0.1:" + port + "/msg").header(HEADER_TOKEN, token).post(RequestBody.create(bytes, JSON)).build();
    }

}
