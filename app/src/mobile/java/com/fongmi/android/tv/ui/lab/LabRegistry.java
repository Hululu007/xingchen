package com.fongmi.android.tv.ui.lab;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验室注册表（两层）：files/lab/registry.json。
 * 第一层＝环境（python / nodejs / php / mihomo / alist / frp / aria2 七张卡平铺）；
 * 第二层＝服务，带 runtime 归属字段，只出现在所属环境页里。
 * lab.json 里的 icon 字段保留（格式 v1 定死），UI 本轮不画头像位。
 */
public final class LabRegistry {

    /** 十一个环境，平铺十一张卡 */
    public static final List<String> ENVS = Arrays.asList(
            "python", "nodejs", "php", "mihomo", "alist", "frp", "aria2",
            "ubuntu", "java", "bun", "ffmpeg");

    /** 服务记录 */
    public static class Svc {
        public String id;
        public String name;
        public String desc;
        public String icon;
        public String version;
        /** python | nodejs | php | mihomo | alist | frp | aria2 | ubuntu | java | bun | ffmpeg | none（none 归 tool，兼容老数据） */
        public String runtime;
        public boolean autoStartDefault;
        /** null=随包声明，true/false=用户手动覆盖 */
        public Boolean autoStart;
        public long installTime;
        /** 分配到的端口（port=auto 时由壳子分配并记住） */
        public int port;
        /** 兼容导入时映射不了、要用户人工确认的人话（一条一行），空=无 */
        public String needsReview;
        /** 单服务节点开关：开着则启动命令时给进程注入本机节点代理（mihomo 服务本身不注入）；
         *  基本类型，老数据没这字段 Gson 读出来默认关，不用补迁移 */
        public boolean nodeProxy;
    }

    public static class Runtime {
        public String name;
        public String version;
        public long installTime;
    }

    private final Map<String, Svc> svcs = new LinkedHashMap<>();
    private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    /** 实验室总开关（自动启动） */
    public boolean masterAutoStart = true;

    private static LabRegistry cache;

    private LabRegistry() {
    }

    public static synchronized LabRegistry get(Context c) {
        if (cache == null) cache = load(c);
        return cache;
    }

    /** 服务归属的环境 id：none → tool（兼容老数据；首页不设卡） */
    public static String envOf(Svc s) {
        String r = s == null || s.runtime == null ? "none" : s.runtime;
        if ("python".equals(r)) return "python";
        if ("nodejs".equals(r)) return "nodejs";
        if ("php".equals(r)) return "php";
        if ("mihomo".equals(r)) return "mihomo";
        if ("alist".equals(r)) return "alist";
        if ("frp".equals(r)) return "frp";
        if ("aria2".equals(r)) return "aria2";
        if ("ubuntu".equals(r)) return "ubuntu";
        if ("java".equals(r)) return "java";
        if ("bun".equals(r)) return "bun";
        if ("ffmpeg".equals(r)) return "ffmpeg";
        return "tool";
    }

    public static String envLabel(String env) {
        if ("python".equals(env)) return "Python 环境";
        if ("nodejs".equals(env)) return "Node 环境";
        if ("php".equals(env)) return "PHP 环境";
        if ("mihomo".equals(env)) return "mihomo";
        if ("alist".equals(env)) return "alist";
        if ("frp".equals(env)) return "frp";
        if ("aria2".equals(env)) return "aria2";
        if ("ubuntu".equals(env)) return "Ubuntu 环境";
        if ("java".equals(env)) return "Java 环境";
        if ("bun".equals(env)) return "Bun 环境";
        if ("ffmpeg".equals(env)) return "ffmpeg";
        return "工具";
    }

    /** 某环境是否就绪：tool 恒为 true（兼容），其余看环境包装没装 */
    public static boolean envReady(Context c, String env) {
        if ("tool".equals(env)) return true;
        return LabRuntimeManager.isInstalled(c, env);
    }

    public synchronized List<Svc> services(String env) {
        List<Svc> out = new ArrayList<>();
        for (Svc s : svcs.values()) {
            if (env.equals(envOf(s))) out.add(s);
        }
        return out;
    }

    public synchronized List<Svc> allServices() {
        return new ArrayList<>(svcs.values());
    }

    /**
     * 与实验室首页同源的可见服务：首页宫格只为 ENVS 十一个环境设卡，
     * runtime 为 none/未知的服务归 tool、首页无卡、平时看不见摸不着。
     * 凡是「给人看的计数」都走这里，跟页面能数出来的对齐；
     * allServices 只留给开机自启这类要全量遍历的内部逻辑。
     */
    public synchronized List<Svc> visibleServices() {
        List<Svc> out = new ArrayList<>();
        for (Svc s : svcs.values()) {
            if (ENVS.contains(envOf(s))) out.add(s);
        }
        return out;
    }

    public synchronized Svc svc(String id) {
        return svcs.get(id);
    }

    public synchronized void upsertSvc(Context c, Svc s) {
        svcs.put(s.id, s);
        save(c);
    }

    public synchronized void removeSvc(Context c, String id) {
        svcs.remove(id);
        save(c);
    }

    public synchronized boolean hasRuntime(String name) {
        return runtimes.containsKey(name);
    }

    public synchronized Runtime runtime(String name) {
        return runtimes.get(name);
    }

    public synchronized void upsertRuntime(Context c, Runtime r) {
        runtimes.put(r.name, r);
        save(c);
    }

    public synchronized void removeRuntime(Context c, String name) {
        runtimes.remove(name);
        save(c);
    }

    public synchronized void setMasterAutoStart(Context c, boolean on) {
        masterAutoStart = on;
        save(c);
    }

    public static boolean autoStartOf(Svc s) {
        if (s.autoStart != null) return s.autoStart;
        return s.autoStartDefault;
    }

    private static LabRegistry load(Context c) {
        LabRegistry r = new LabRegistry();
        File f = LabPaths.registry(c);
        if (!f.exists()) return r;
        try (FileInputStream in = new FileInputStream(f);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            parseRegistry(reader, r);
        } catch (Exception ignored) {
        }
        return r;
    }

    /**
     * 注册表读回（1.3.52 真机崩溃根治）：
     * R8 会剥掉集合字段的泛型签名（proguard 里没有 -keepattributes Signature），
     * Gson 按裸类型读 Map/List 时值全是 LinkedTreeMap，之后强转 bean 必崩。
     * 这里改走 JsonParser 逐层读：顶层按形态分流（对象=服务/环境两张表，布尔=总开关），
     * 每个条目按具体类（Svc/Runtime，无泛型字段）重绑；字段名走 Gson 反射
     * （运行时实际名），不依赖源码名，手机上已存的老数据照读。
     */
    static void parseRegistry(Reader reader, LabRegistry r) {
        Gson g = new Gson();
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (Exception e) {
            return;
        }
        if (root == null || !root.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                // 服务表 / 环境表：逐条看有没有 id 归位（Svc 必有 id，Runtime 没有）
                for (Map.Entry<String, JsonElement> en : v.getAsJsonObject().entrySet()) {
                    if (!en.getValue().isJsonObject()) continue;
                    Svc s = g.fromJson(en.getValue(), Svc.class);
                    if (s != null && s.id != null && !s.id.isEmpty()) {
                        r.svcs.put(en.getKey(), s);
                    } else {
                        Runtime rt = g.fromJson(en.getValue(), Runtime.class);
                        if (rt != null) {
                            if (rt.name == null) rt.name = en.getKey();
                            r.runtimes.put(en.getKey(), rt);
                        }
                    }
                }
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                r.masterAutoStart = v.getAsBoolean();
            }
        }
    }

    private synchronized void save(Context c) {
        try {
            File f = LabPaths.registry(c);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f);
                 OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                new Gson().toJson(this, writer);
            }
        } catch (Exception ignored) {
        }
    }
}
