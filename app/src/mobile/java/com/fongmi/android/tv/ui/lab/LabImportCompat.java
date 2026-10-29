package com.fongmi.android.tv.ui.lab;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实验室四路导入兼容：把外面流通的三种服务写法（默影视命令条目 / Termux 服务清单 / 裸脚本文件）
 * 归一到实验室自己的服务模型（LabManifest）。
 *
 * 纯逻辑类，不碰 android.*，可离线单测。
 * 路径占位约定（LabRunner.expand 会替换）：{port} {pkgDir} {filesDir} {hostPort}
 */
public final class LabImportCompat {

    public enum Kind { WEBHTV_COMMAND, TERMUX_LIST, BARE_SCRIPT, LAB_JSON, SITE_SOURCE, WEBHTV_ITEM, NODE_PROJECT, NODE_BUNDLE }

    /** 归一后的一个服务（安装前形态） */
    public static class ImportItem {
        public String id = "";
        public String name = "";
        public String desc = "";
        public String version = "1.0";
        /** 上游 Item.group（分组 id，如 network；上游 LabModels.Item 同名字段），无分组为空串 */
        public String group = "";
        public String runtime = "none";      // python | nodejs | php | mihomo | frp | alist | aria2 | ubuntu | ffmpeg | none
        public String command = "";          // 含 {port}/{pkgDir}/{filesDir} 占位
        public boolean background = true;
        public boolean autoStart = false;
        /** 上游 show_output：运行后是否把输出端到眼前（缺省 true；false=后台静默跑） */
        public boolean showOutput = true;
        /** 包声明最低 App versionCode */
        public int minVersion = 0;
        /** 命令变量表（上游 variables/settings 口径） */
        public final List<LabManifest.LabVar> variables = new ArrayList<>();
        /** 包声明预建目录（上游 Item.mkdir，安装时真建） */
        public final List<String> mkdirs = new ArrayList<>();
        /** 包声明环境变量（上游 Item.var_path Map 原形，运行时注入进程环境） */
        public final Map<String, String> varPath = new LinkedHashMap<>();
        /** 运行前依赖下载（上游 download 口径；Item 级 downloads[] 选中的那条也落这里） */
        public LabManifest.LabDownload download;
        public String portPolicy = "auto";   // auto 或固定端口数字
        public String outputKind = "";       // live | vod | pan | tool，空=未声明
        public String outputTarget = "";     // 地址模板，含 {port}
        /** 映射不了、要用户人工确认的人话，一条一行 */
        public String needsReview = "";
        public Kind kind;
        /** 裸脚本路：脚本文件名＋内容，安装时拷进包目录 */
        public String assetName = "";
        public byte[] assetBytes;
        /** 7z 离线包 binary/ 条目：相对路径 → 内容，安装时落进包目录 */
        public Map<String, byte[]> extraFiles;
    }

    public static class ParseResult {
        public Kind kind;
        public final List<ImportItem> items = new ArrayList<>();
        /** 人话：认出来是哪种格式 */
        public String note = "";
    }

    private LabImportCompat() {
    }

    // ---------------- Node bundle 引导脚本（宿主生成） ----------------

    /** 引导脚本文件名：随 bundle 一起落进包目录，启动命令先跑它、由它拉起真入口 */
    public static final String NODE_BOOT_NAME = "lab-node-boot.js";

    /**
     * 引导脚本内容（宿主生成，与点播链 tv.node.NodeBoot 同口径）：
     * 这类 bundle 的预期启动形态是宿主 require 后调 start(conf)，监听端口读
     * process.env.DEV_HTTP_PORT（不给即随机口），数据根读 NODE_PATH，还会用
     * 两个宿主全局 catServerFactory / catDartServerPort——裸 node <main> 直跑
     * 三样全缺，包要么起不来、要么听在随机口上和服务页登记的分配口对不上。
     * 本脚本补齐这一层：端口由启动命令以 DEV_HTTP_PORT={port} 注入（分配口
     * 由 LabRunner.allocPort 先探空闲再定，不是猜）、宿主口以 LAB_HOST_PORT
     * 注入（{hostPort} 展开成 App 的 Nano 服务口，与点播链 NodeBoot 传
     * Proxy.getPort() 同一个值）、NODE_PATH 指到入口同目录的 data/、同目录
     * 配置（<入口名>.config.js，命名规则与 nodeBundleConfigName 一致）读作
     * conf 传给 start()。与 NodeBoot 的一处有意差异：不落盘发布实际端口——
     * 点播链要靠落盘在一堆候选服务里认真正的猫源口，实验室这边端口是宿主
     * 分配并强制注入的，服务页登记的口就是它，没有第二个读者。
     */
    public static String nodeBootSource() {
        return "'use strict';\n"
                + "const http = require('http');\n"
                + "const fs = require('fs');\n"
                + "const path = require('path');\n"
                // fastify 的 serverFactory 契约：拿到 handler 返回一个 http.Server（同 NodeBoot）
                + "globalThis.catServerFactory = (handler) => http.createServer(handler);\n"
                // bundle 会 POST 到 http://127.0.0.1:<port>/msg 回调宿主，口由命令注入
                + "const hostPort = parseInt(process.env.LAB_HOST_PORT || '0', 10);\n"
                + "globalThis.catDartServerPort = () => hostPort;\n"
                + "process.on('uncaughtException', (e) => console.error('uncaught', e));\n"
                + "process.on('unhandledRejection', (e) => console.error('unhandled', e));\n"
                + "(async () => {\n"
                + "  try {\n"
                + "    const main = process.argv[2];\n"
                + "    if (!main) throw new Error('no bundle entry given');\n"
                // NODE_PATH 是 bundle 约定的数据根目录（db.json、vod_cache 落这儿），
                // 指到入口同目录的 data/，与 NodeBoot 的 bundle 同级 data 口径一致
                + "    const data = path.join(path.dirname(main), 'data');\n"
                + "    try { fs.mkdirSync(data, { recursive: true }); } catch (e) {}\n"
                + "    process.env.NODE_PATH = data;\n"
                + "    const mod = require(main);\n"
                + "    const start = mod.start || (mod.default && mod.default.start);\n"
                + "    if (typeof start !== 'function') throw new Error('bundle has no start()');\n"
                // 配置必须传真实内容：各站点从 server.config.<key> 取参数，空对象会让它们抛 undefined
                + "    let conf = {};\n"
                + "    const cfgPath = /\\.js$/i.test(main)\n"
                + "      ? main.replace(/\\.js$/i, '.config.js')\n"
                + "      : path.join(path.dirname(main), 'index.config.js');\n"
                + "    if (fs.existsSync(cfgPath)) {\n"
                + "      try {\n"
                + "        const raw = require(cfgPath);\n"
                + "        conf = raw && raw.default ? raw.default : (raw || {});\n"
                + "        console.log('config keys: ' + Object.keys(conf).length);\n"
                + "      } catch (e) { console.error('config load failed', e.message); }\n"
                + "    }\n"
                + "    await start(conf);\n"
                // listen 之后留心跳撑住事件循环，与 NodeBoot 同口径
                + "    setInterval(() => {}, 60000);\n"
                + "    console.log('lab node bundle started on ' + (process.env.DEV_HTTP_PORT || 'random'));\n"
                + "  } catch (e) {\n"
                + "    console.error('lab node bundle failed', e && e.stack ? e.stack : e);\n"
                + "  }\n"
                + "})();\n";
    }

    /** 引导脚本字节（安装落盘用） */
    public static byte[] nodeBootBytes() {
        try {
            return nodeBootSource().getBytes("UTF-8");
        } catch (Exception e) {
            return nodeBootSource().getBytes();
        }
    }

    // ---------------- 识别＋分发 ----------------

    /**
     * 从粘贴文本或文本文件内容识别格式并解析。
     *
     * @param text     文本内容
     * @param fileName 文件名（选文件时带后缀给裸脚本定环境；粘贴时可传 null）
     */
    public static ParseResult parseText(String text, String fileName) throws Exception {
        if (text == null) throw new Exception("内容是空的");
        String t = text.replace("\uFEFF", "").trim();
        if (t.isEmpty()) throw new Exception("内容是空的，粘点东西进来");
        if (t.startsWith("{") || t.startsWith("[")) {
            return parseJson(t);
        }
        return parseScript(t, fileName);
    }

    private static ParseResult parseJson(String t) throws Exception {
        ParseResult r = new ParseResult();
        if (t.startsWith("[")) {
            JSONArray a = new JSONArray(t);
            if (a.length() == 0) throw new Exception("这是个空数组，里面没服务");
            // 数组：逐项看，第一项有 command 即当默影视命令列表
            JSONObject first = a.getJSONObject(0);
            if (first.has("command")) {
                r.kind = Kind.WEBHTV_COMMAND;
                r.note = "认出来了：默影视命令列表（" + a.length() + " 条）";
                for (int i = 0; i < a.length(); i++) r.items.add(fromWebhtv(a.getJSONObject(i), i));
                return r;
            }
            if (isSiteSource(first)) {
                r.kind = Kind.SITE_SOURCE;
                r.note = "认出来了：站源片段（" + a.length() + " 个），归到 Python 环境";
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && isSiteSource(o)) r.items.add(fromSiteSource(o));
                }
                return r;
            }
            throw new Exception("这个 JSON 数组认不出来：里面不是服务命令条目");
        }
        JSONObject o = new JSONObject(t);
        // 整份默影视实验室清单（目录文件形态）：包都在 lists[] 里，逐个包走 Item 转换；
        // 上游标了不可用（available=false）或不显示（show=false）的包不导，数记进说明里不装哑巴
        if (o.has("lists") && o.opt("lists") instanceof JSONArray) {
            r.kind = Kind.WEBHTV_ITEM;
            JSONArray lists = o.getJSONArray("lists");
            int skipped = 0;
            for (int i = 0; i < lists.length(); i++) {
                JSONObject item = lists.optJSONObject(i);
                if (item == null || !item.has("commands")) continue;
                if (!item.optBoolean("available", true) || !item.optBoolean("show", true)) {
                    skipped++;
                    continue;
                }
                r.items.addAll(fromWebhtvItem(item));
            }
            if (r.items.isEmpty()) {
                throw new Exception("这个清单里的包都不可用或不显示，没导进来");
            }
            r.note = "认出来了：默影视实验室清单（" + r.items.size() + " 条命令）"
                    + (skipped > 0 ? "，另有 " + skipped + " 个包上游标了不可用/不显示，没导" : "");
            return r;
        }
        if (o.has("services") && o.opt("services") instanceof JSONArray) {
            r.kind = Kind.TERMUX_LIST;
            JSONArray svcs = o.getJSONArray("services");
            int skippedOff = 0;
            for (int i = 0; i < svcs.length(); i++) {
                JSONObject s = svcs.getJSONObject(i);
                // enabled:false 是作者明说停用的服务（G-5）：此前不读这个字段，
                // 停用的照样装进来占位、还可能被自启拉起来
                if (!s.optBoolean("enabled", true)) {
                    skippedOff++;
                    continue;
                }
                r.items.add(fromTermux(s));
            }
            if (r.items.isEmpty()) {
                throw new Exception("这份清单里的服务全被标了停用（enabled:false），没导进来");
            }
            r.note = "认出来了：Termux 服务清单（" + r.items.size() + " 个服务）"
                    + (skippedOff > 0 ? "，另有 " + skippedOff + " 个标了停用，没导" : "");
            return r;
        }
        if (o.has("command")) {
            r.kind = Kind.WEBHTV_COMMAND;
            r.note = "认出来了：默影视命令条目";
            r.items.add(fromWebhtv(o, 0));
            return r;
        }
        // 上游实验室包（Item）：有 commands 但无 id/runtime——必须排在自家 lab.json 前面，
        // 否则会被误判成 LAB_JSON 然后抛「缺 id」
        if (o.has("commands") && !o.has("id") && !o.has("runtime") && o.has("name")) {
            r.kind = Kind.WEBHTV_ITEM;
            List<ImportItem> items = fromWebhtvItem(o);
            if (items.isEmpty()) throw new Exception("这个实验室包里没有命令");
            r.note = "认出来了：默影视实验室包（" + items.size() + " 条命令）";
            r.items.addAll(items);
            return r;
        }
        if (o.has("commands") || (o.has("id") && o.has("runtime"))) {
            r.kind = Kind.LAB_JSON;
            r.note = "认出来了：实验室自己的包说明";
            r.items.add(fromLabJson(o));
            return r;
        }
        if (isSiteSource(o)) {
            r.kind = Kind.SITE_SOURCE;
            r.note = "认出来了：站源片段（py），归到 Python 环境";
            r.items.add(fromSiteSource(o));
            return r;
        }
        throw new Exception("这段 JSON 认不出来：不是服务清单、不是命令条目，也不是实验室包说明");
    }

    // ---------------- 站源片段（key/name/type/api，api 指向 .py） ----------------

    /** 站源片段：有 api（指向 .py）且有 key 或 name；归到 Python 环境下登记 */
    private static boolean isSiteSource(JSONObject o) {
        if (o == null || !o.has("api")) return false;
        String api = o.optString("api", "").trim();
        int q = api.indexOf('?');
        if (q >= 0) api = api.substring(0, q);
        int h = api.indexOf('#');
        if (h >= 0) api = api.substring(0, h);
        if (!api.trim().toLowerCase().endsWith(".py")) return false;
        return o.has("key") || o.has("name");
    }

    private static ImportItem fromSiteSource(JSONObject o) {
        ImportItem it = new ImportItem();
        it.kind = Kind.SITE_SOURCE;
        String key = o.optString("key", "").trim();
        String name = o.optString("name", "").trim();
        it.id = "site_" + sanitize(key.isEmpty() ? name : key);
        it.name = name.isEmpty() ? it.id : name;
        it.desc = "";
        it.runtime = "python";
        it.command = "";
        it.background = true;
        it.autoStart = false;
        it.portPolicy = "auto";
        it.needsReview = addLine(it.needsReview, "这是站源脚本（py 插件源），已归到 Python 环境下登记；它不是后台服务，没有启动命令");
        return it;
    }

    // ---------------- 路1：默影视命令条目 ----------------

    private static ImportItem fromWebhtv(JSONObject o, int idx) {
        ImportItem it = new ImportItem();
        it.kind = Kind.WEBHTV_COMMAND;
        String rawId = o.optString("id", "").trim();
        it.id = sanitize(rawId.isEmpty() ? "webhtv" + idx : rawId);
        it.name = o.optString("name", it.id).trim();
        it.desc = o.optString("description", o.optString("desc", "")).trim();
        // background 缺省 true（后台型）：与自家 lab.json 缺省（LabManifest）一致；
        // 曾改成 false，未写 background 的存量上游包导入后全变前台型、运行即弹
        // 输出页，已恢复 true
        it.background = o.optBoolean("background", true);
        it.autoStart = o.optBoolean("auto_execute", o.optBoolean("autoExecute", false));
        it.showOutput = o.optBoolean("show_output", o.optBoolean("showOutput", true));
        it.minVersion = upstreamMinVersion(o.optInt("min_version", 0), it);
        String cmd = o.optString("command", "");
        it.runtime = runtimeFromCommand(cmd);
        it.command = translateWebhtvCommand(cmd, it);
        // 描述里常带「(端口 9528)」：拿它当端口声明
        String p = findPortInText(it.desc + " " + it.name);
        if (p != null) {
            it.portPolicy = p;
            if (it.command.contains(p)) it.command = it.command.replace(p, "{port}");
        }
        if (it.command == null || it.command.trim().isEmpty()) {
            it.needsReview = addLine(it.needsReview, "这条没有启动命令，装完也跑不起来");
        }
        return it;
    }

    /**
     * 把默影视命令里的宿主变量翻成星辰实验室的占位。
     * 样本：PACKAGE=$(pm list packages --user 0 | grep -v "com.android" | head -1 | cut -d: -f2);
     *       /data/data/$PACKAGE/files/lab/nodejs/bin/node /storage/emulated/0/WebHTV/Proxy/酷我音乐/kuwo_server.js
     */
    static String translateWebhtvCommand(String cmd, ImportItem it) {
        if (cmd == null) return "";
        String c = cmd;
        // 1. 扔掉开头的 PACKAGE=$(pm list packages …); ——星辰自己就是宿主，不用查包名
        c = c.replaceFirst("(?s)^\\s*PACKAGE=\\$\\(pm list packages[^;]*\\);?\\s*", "");
        // 2. /data/data/$PACKAGE/files/lab → {filesDir}/lab；兜底 /data/data/$PACKAGE → {filesDir}
        c = c.replace("/data/data/$PACKAGE/files/lab", "{filesDir}/lab");
        c = c.replace("/data/data/$PACKAGE", "{filesDir}");
        // 2b. 默影视把环境直接放 files/lab/<env>/，星辰是 files/lab/runtimes/<env>/：补上 runtimes
        c = c.replaceAll("\\{filesDir\\}/lab/(nodejs|python|php)(?=/|$)", "{filesDir}/lab/runtimes/$1");
        // 3. 还剩 $PACKAGE 说明有没翻干净的
        if (c.contains("$PACKAGE")) {
            it.needsReview = addLine(it.needsReview, "命令里还有宿主变量 $PACKAGE 没翻干净，启动前请确认");
        }
        // 4. 脚本在手机公共目录：路径保留，但必须明示（文件不在包里）
        Matcher m = Pattern.compile("/storage/emulated/0/[^\\s;\"']+").matcher(c);
        if (m.find()) {
            it.needsReview = addLine(it.needsReview,
                    "脚本文件在手机公共目录（" + m.group() + "），请确认它在你手机上有；没有的话从本地添加把脚本一起拷进来");
        }
        return c.trim();
    }

    // ---------------- 路6：默影视实验室包（上游 LabModels.Item） ----------------

    /**
     * 上游 Item → 每条命令转成一条 ImportItem（一条命令 = 一个星辰服务）。
     * 上游 Item 无 id/runtime：id 取 name 加 wh_ 前缀防撞；runtime 从命令推断。
     */
    private static List<ImportItem> fromWebhtvItem(JSONObject o) {
        List<ImportItem> out = new ArrayList<>();
        String itemName = o.optString("name", "").trim();
        String stem = itemName.isEmpty() ? "item" : itemName;
        String info = o.optString("info", "").trim();
        // 包级 description（历史自创清单形态用它当包说明）：info 没写时拿它兜底，
        // 此前只认 info，包说明整段丢（G-5/§12）
        if (info.isEmpty()) info = o.optString("description", "").trim();
        // 包级开关兜底（同上形态）：auto_execute/show_output 写在包级时套到
        // 没单独声明的命令上，此前整段没读、三个开关静默失效（G-5/§12）
        boolean pkgAutoExec = o.optBoolean("auto_execute", false);
        Boolean pkgShowOutput = o.opt("show_output") instanceof Boolean
                ? (Boolean) o.opt("show_output") : null;
        // 包级预建目录与环境变量（上游 mkdir/var_path 原形）：解析一次套到每条命令，
        // mkdir 安装时真建、var_path 运行时真注入，不再只留一句提示（G-4/G-5）
        List<String> pkgMkdirs = mkdirListOf(o.opt("mkdir"));
        Map<String, String> pkgVarPath = new LinkedHashMap<>();
        JSONObject vpo = o.optJSONObject("var_path");
        if (vpo != null) {
            java.util.Iterator<String> keys = vpo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String v = vpo.optString(k, "").trim();
                if (!k.isEmpty() && !v.isEmpty()) pkgVarPath.put(k, v);
            }
        }
        String version = o.optString("version", "1.0").trim();
        // 上游 Item.group：包级分组 id，套到每条命令（此前整段没读，分组信息静默丢失）
        String group = o.optString("group", "").trim();
        boolean autoInstall = o.optBoolean("auto_install", false);
        // 上游 Item 级 min_version：命令没写时兜底（尺度归一在 upstreamMinVersion）
        int itemMinVersion = o.optInt("min_version", 0);
        // Item 级 downloads[]（靠下载落二进制的服务，如 lxserver/drpy-node）：解析一次套到每条命令。
        // 此前整段没读，装完没二进制还不吭声；读不通的也必须进 needsReview 明说
        ItemDownloads itemDls = parseItemDownloads(o);
        // 包级 settings（上游按环境变量注入）：并入每条命令的变量表，语义差异如实提示
        List<LabManifest.LabVar> pkgVars = parseLabVarList(o.optJSONArray("settings"));
        JSONArray cmds = o.optJSONArray("commands");
        int n = cmds == null ? 0 : cmds.length();
        for (int i = 0; i < n; i++) {
            JSONObject c = cmds.optJSONObject(i);
            if (c == null) continue;
            ImportItem it = new ImportItem();
            it.kind = Kind.WEBHTV_ITEM;
            String cmdId = c.optString("id", "cmd" + i).trim();
            it.id = "wh_" + sanitize(stem + "_" + cmdId);
            it.name = c.optString("name", cmdId).trim();
            if (it.name.isEmpty()) it.name = it.id;
            it.desc = c.optString("description", c.optString("desc", "")).trim();
            if (it.desc.isEmpty()) it.desc = info;
            it.version = version.isEmpty() ? "1.0" : version;
            it.group = group;
            String cmd = c.optString("command", "");
            it.runtime = runtimeFromCommand(cmd);
            it.command = translateWebhtvCommand(cmd, it);
            // background 缺省 true，同命令条目路
            it.background = c.optBoolean("background", true);
            // show_output：命令自己写了听自己的，没写拿包级兜底，都没写缺省 true
            it.showOutput = (c.has("show_output") || c.has("showOutput"))
                    ? c.optBoolean("show_output", c.optBoolean("showOutput", true))
                    : (pkgShowOutput != null ? pkgShowOutput : true);
            // 自启来源：命令自己的 auto_execute，或包级 auto_install（上游语义：
            // 声明自动安装的包，装上即按自动运行对待），或包级 auto_execute
            // （历史自创清单形态的包级自启）。曾收窄为仅 auto_execute，
            // auto_install 的包导入后不再自启，已恢复并取
            it.autoStart = c.optBoolean("auto_execute", false) || autoInstall || pkgAutoExec;
            it.mkdirs.addAll(pkgMkdirs);
            it.varPath.putAll(pkgVarPath);
            // 命令没写 min_version 时用包级的（上游 Item 级此前整段没读，G-5）
            int cmdMin = c.optInt("min_version", 0);
            it.minVersion = upstreamMinVersion(cmdMin > 0 ? cmdMin : itemMinVersion, it);
            it.variables.addAll(parseLabVarList(c.optJSONArray("variables")));
            it.variables.addAll(pkgVars);
            JSONObject dl = c.optJSONObject("download");
            if (dl != null) {
                String url = dl.optString("url", "").trim();
                LabManifest.LabDownload d = new LabManifest.LabDownload();
                d.url = url;
                // 落盘键两种都吃：save_to（上游）/ file（自家 LabManifest 口径），两路对齐
                d.file = dl.has("file") ? dl.optString("file", "").trim()
                        : dl.optString("save_to", "").trim();
                // 上游 CommandDownload 的其余字段此前整段没读：check_file（判备好没）、
                // extract_to（整包解压落点）、required——丢了它们下载只会原样落盘、
                // 压缩件永远解不开（G-1）。这里全接上，运行期 LabDepends 按声明走
                d.checkFile = dl.optString("check_file", dl.optString("checkFile", "")).trim();
                d.extractTo = dl.optString("extract_to", dl.optString("extractTo", "")).trim();
                d.required = dl.optBoolean("required", false);
                if (!url.isEmpty()) {
                    it.download = d;
                } else if (!d.checkFile.isEmpty()) {
                    // 地址留空＋check_file（如 mihomo 的订阅配置）：文件要用户先备好，
                    // 没得下；把声明带上，缺文件时运行日志里能对上是哪一份
                    it.download = d;
                    it.needsReview = addLine(it.needsReview,
                            "这条命令要用的文件（" + d.checkFile + "）不在包里、地址也是空的（多半是订阅类配置），先把文件备到包里或公共目录它该在的位置");
                } else {
                    it.needsReview = addLine(it.needsReview,
                            "这条命令声明了下载（" + dl.optString("title", "下载文件") + "），但没给下载地址");
                }
            }
            // 命令自己没声明能下的下载时，套 Item 级 downloads[] 选中的那条；
            // 命令声明了但地址为空（check_file 型，如订阅配置）的不算能下——
            // 它什么都不拉，不能把 Item 级的二进制下载挤掉（mihomo 就长这样）
            if ((it.download == null || it.download.url == null || it.download.url.isEmpty())
                    && itemDls.download != null) it.download = itemDls.download;
            for (String note : itemDls.notes) it.needsReview = addLine(it.needsReview, note);
            if (!pkgVars.isEmpty()) {
                it.needsReview = addLine(it.needsReview, "包设置已并入变量表（上游是按环境变量注入，语义可能有差异）");
            }
            if (autoInstall) {
                it.needsReview = addLine(it.needsReview, "上游包声明自动安装，星辰转成了自动启动，语义不完全一样");
            }
            JSONArray clicks = c.optJSONArray("clicks");
            if (clicks != null && clicks.length() > 0) {
                it.needsReview = addLine(it.needsReview,
                        "默影视的「后续操作」按钮有 " + clicks.length() + " 个转不过来（打开网页/二维码/复制等），这部分功能没有了");
            }
            // 描述里常带「(端口 9528)」：拿它当端口声明（同命令条目路）
            String p = findPortInText(it.desc + " " + it.name);
            if (p != null) {
                it.portPolicy = p;
                if (it.command.contains(p)) it.command = it.command.replace(p, "{port}");
            }
            if (it.command == null || it.command.trim().isEmpty()) {
                it.needsReview = addLine(it.needsReview, "这条没有启动命令，装完也跑不起来");
            }
            out.add(it);
        }
        return out;
    }

    /**
     * 上游 min_version 尺度归一（G-2）：上游拿它跟默影视自己的 versionCode 比
     * （mihomo 真样本写 110），星辰的 versionCode（1.4.25=108）不是一个尺度，
     * 原样带过去会被 LabRunner 的版本门批量误杀。故上游来源一律不带这个门
     * （归零），声明进 needsReview 明说；自家 .lab 的 min_version 不走这里，
     * 那是星辰自己的尺度、版本门照常生效。
     */
    private static int upstreamMinVersion(int declared, ImportItem it) {
        if (declared <= 0) return 0;
        it.needsReview = addLine(it.needsReview,
                "包声明最低版本 min_version=" + declared + "（默影视的版本号口径），跟星辰的版本号不是一个尺度、没按它拦");
        return 0;
    }

    /** Item 级下载声明的解析结果：选中的一条下载＋要带给用户看的提示 */
    private static class ItemDownloads {
        LabManifest.LabDownload download;
        final List<String> notes = new ArrayList<>();
    }

    /**
     * 上游 Item 级 downloads[]：按本机架构挑一条直链，接成命令的运行前下载
     *（落点按 binary_path，没写 binary_path 就用地址里的文件名，LabRunner 缺文件时才下）。
     * 挑不到/只有网盘地址时不装哑巴——提示进 needsReview，装完跑不起来得让用户先知道。
     */
    private static ItemDownloads parseItemDownloads(JSONObject o) {
        ItemDownloads out = new ItemDownloads();
        JSONArray arr = o.optJSONArray("downloads");
        if (arr == null || arr.length() == 0) return out;
        String binaryPath = o.optString("binary_path", "").trim();
        String myArch = archFamily(System.getProperty("os.arch", ""));
        String chosenUrl = "";
        boolean anyUrl = false;
        boolean hasPan = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            if (!d.optString("pan", "").trim().isEmpty()) hasPan = true;
            String url = downloadUrlOf(d);
            if (url.isEmpty()) continue;
            anyUrl = true;
            String fam = archFamily(d.optString("arch", ""));
            if (chosenUrl.isEmpty() && (fam.isEmpty() || fam.equals(myArch))) chosenUrl = url;
        }
        if (!chosenUrl.isEmpty()) {
            LabManifest.LabDownload dl = new LabManifest.LabDownload();
            dl.url = chosenUrl;
            // Item 级 downloads 是整包压缩件（.gz/.tar.gz/rootfs），上游在安装时
            // 整包解压进包目录：这里归一成 extract 模式——普通包解到包根，
            // rootfs 包（Item 带 rootfs:true）解进包内 rootfs（命令按
            // {envRootPath}/rootfs 引用它）；binary_path 作归位目标，
            // 解压后由 LabDepends 按名/单文件归位到 bin/ 下（G-1）
            dl.extractTo = o.optBoolean("rootfs", false) ? "rootfs" : ".";
            if (!binaryPath.isEmpty()) {
                dl.file = binaryPath.endsWith("/") ? binaryPath + urlFileName(chosenUrl) : binaryPath;
                out.notes.add("包声明二进制放 binary_path（" + binaryPath + "），已按这个位置接上 downloads 的下载（整包先解压再归位）");
            }
            out.download = dl;
        } else if (!anyUrl) {
            out.notes.add(hasPan
                    ? "这个包的二进制只给了网盘地址（downloads 里是 pan），星辰下不了网盘，装完没二进制、多半跑不起来"
                    : "这个包声明了二进制下载（downloads）但没给能用的地址，没接上，装完可能跑不起来");
        } else {
            out.notes.add("这个包声明的二进制（downloads）没有你手机架构的版本，没接上，装完可能跑不起来");
        }
        // var_path 与 mkdir 不在这里出提示了：var_path 上游是 Map，此前按 String
        // 读永远读不出东西、提示也永不触发；现在两者都在 fromWebhtvItem 按原形
        // 解析并真执行（var_path 注入环境、mkdir 安装时建），无须再提示
        return out;
    }

    /** 上游 mkdir 双形态（LabModels.mkdirList 口径）：字符串＝单个目录、数组＝字符串元素列表；
     * 此前只认数组，frp/mihomo 的字符串形态连提示都不出、静默丢失 */
    private static List<String> mkdirListOf(Object mkdir) {
        List<String> list = new ArrayList<>();
        if (mkdir instanceof String) {
            String s = ((String) mkdir).trim();
            if (!s.isEmpty()) list.add(s);
        } else if (mkdir instanceof JSONArray) {
            JSONArray arr = (JSONArray) mkdir;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) list.add(s);
            }
        }
        return list;
    }

    /** 下载条目里取直链：url 优先，其次 mirrors[0]、liburl；只有 pan（网盘）时返回空 */
    private static String downloadUrlOf(JSONObject d) {
        String url = d.optString("url", "").trim();
        if (!url.isEmpty()) return url;
        JSONArray mirrors = d.optJSONArray("mirrors");
        if (mirrors != null) {
            for (int i = 0; i < mirrors.length(); i++) {
                Object mv = mirrors.opt(i);
                String mu = mv instanceof JSONObject
                        ? ((JSONObject) mv).optString("url", "").trim()
                        : (mv == null ? "" : String.valueOf(mv).trim());
                if (!mu.isEmpty() && !"null".equals(mu)) return mu;
            }
        }
        return d.optString("liburl", "").trim();
    }

    /** 架构归一成家族名（arm64/arm/x64/x86），认不出返回空（视作通用条目） */
    private static String archFamily(String arch) {
        if (arch == null) return "";
        String a = arch.toLowerCase();
        if (a.contains("arm64") || a.contains("aarch64") || a.contains("armv8")) return "arm64";
        if (a.contains("x86_64") || a.contains("amd64") || a.contains("x64")) return "x64";
        if (a.contains("x86") || a.contains("i686") || a.contains("i386")) return "x86";
        if (a.contains("arm")) return "arm";
        return "";
    }

    private static String urlFileName(String url) {
        String u = url.split("\\?")[0];
        int i = u.lastIndexOf('/');
        return i >= 0 && i < u.length() - 1 ? u.substring(i + 1) : "dep.bin";
    }

    /** 上游 variables/settings 口径 → LabVar（字段与 LabManifest.parseVars 对齐） */
    static List<LabManifest.LabVar> parseLabVarList(JSONArray arr) {
        List<LabManifest.LabVar> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            LabManifest.LabVar var = new LabManifest.LabVar();
            var.key = v.optString("key", "").trim();
            if (var.key.isEmpty()) continue;
            var.name = v.optString("name", var.key).trim();
            var.type = v.optString("type", "text").trim().toLowerCase();
            // 与 LabManifest.parseVars 同口径双吃："default"（手写）与 "defaultValue"（Gson 落盘名），
            // 只读 default 时回读自家落盘的变量表会丢默认值
            var.defaultValue = v.has("default") ? v.optString("default", null)
                    : (v.has("defaultValue") ? v.optString("defaultValue", null) : null);
            var.hint = v.optString("hint", null);
            var.required = v.optBoolean("required", false);
            if (v.opt("editable") instanceof Boolean) var.editable = (Boolean) v.opt("editable");
            var.filter = v.has("filter") ? v.optString("filter", null) : null;
            var.min = v.has("min") ? v.optString("min", null) : null;
            var.max = v.has("max") ? v.optString("max", null) : null;
            JSONArray opts = v.optJSONArray("options");
            if (opts != null) {
                for (int j = 0; j < opts.length(); j++) {
                    JSONObject o2 = opts.optJSONObject(j);
                    if (o2 == null) continue;
                    LabManifest.LabOption op = new LabManifest.LabOption();
                    op.label = o2.optString("label", "");
                    op.value = o2.optString("value", op.label);
                    var.options.add(op);
                }
            }
            out.add(var);
        }
        return out;
    }

    // ---------------- 路2：Termux 服务清单 ----------------

    private static ImportItem fromTermux(JSONObject s) {
        ImportItem it = new ImportItem();
        it.kind = Kind.TERMUX_LIST;
        String rawId = s.optString("id", "").trim();
        it.id = "termux_" + sanitize(rawId.isEmpty() ? "svc" : rawId);
        it.name = s.optString("name", it.id).trim();
        it.background = s.optBoolean("is_daemon", true);
        it.autoStart = s.optBoolean("autostart", false);
        String cmd = s.optString("cmd", "");
        it.runtime = runtimeFromCommand(cmd);
        // 端口：清单 port 字段优先
        String port = s.optString("port", "").trim();
        if (port.matches("\\d+")) it.portPolicy = port;
        it.command = translateTermuxCmd(cmd, it);
        // web_url → 产出声明（网页类服务按 tool 走：打开看看）
        String web = s.optString("web_url", "").trim();
        if (!web.isEmpty()) {
            it.outputKind = "tool";
            it.outputTarget = web.replaceFirst(":\\d+(/|$)", ":{port}$1");
            if (it.outputTarget.equals(web) && it.portPolicy.matches("\\d+")) {
                // 地址里没端口但清单给了端口，拼上
                it.outputTarget = web.replaceFirst("(/|$)", ":{port}$1");
            }
        }
        if (it.command == null || it.command.trim().isEmpty()) {
            it.needsReview = addLine(it.needsReview, "这条没有启动命令，装完也跑不起来");
        }
        return it;
    }

    /**
     * Termux 命令翻译：
     * - cd /storage/emulated/0/<x> → cd {pkgDir}/<x>（私有目录规则），并明示要人工确认
     * - 字面端口（如 8080）→ {port}，端口策略取清单 port 字段
     */
    static String translateTermuxCmd(String cmd, ImportItem it) {
        if (cmd == null) return "";
        String c = cmd;
        Matcher m = Pattern.compile("cd\\s+/storage/emulated/0/([^\\s;]*)").matcher(c);
        StringBuffer sb = new StringBuffer();
        boolean mapped = false;
        String rel = "";
        while (m.find()) {
            mapped = true;
            rel = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement("cd {pkgDir}/" + rel));
        }
        m.appendTail(sb);
        c = sb.toString();
        if (mapped) {
            it.needsReview = addLine(it.needsReview,
                    "工作目录从手机公共目录映射到服务包内（" + (rel.isEmpty() ? "根" : rel)
                            + "）：请把原来的文件拷进包里，或确认路径");
        } else if (c.contains("/storage/emulated/0/")) {
            it.needsReview = addLine(it.needsReview, "命令里引用了手机公共目录的路径，包里没有这些文件，启动前请确认");
        }
        if (it.portPolicy.matches("\\d+") && c.contains(it.portPolicy)) {
            c = c.replace(it.portPolicy, "{port}");
        }
        return c.trim();
    }

    // ---------------- 路3：裸脚本文件 ----------------

    private static ParseResult parseScript(String t, String fileName) throws Exception {
        // Node bundle 的配置文件（*.config.js）单独喂进来时收口：它只是整包的
        // 一份配置、自己起不了服务，此前会被当裸脚本装成一个跑不起来的服务。
        // 必须整包导（目录 / zip / .md5 链接）
        if (fileName != null && fileName.toLowerCase().endsWith(".config.js")) {
            throw new Exception("这是 Node 服务包的配置文件，不能单独导入；"
                    + "把整个包导进来（粘 .js.md5 链接、选整包 zip，或粘包文件夹的本地路径）");
        }
        String suffix = "";
        if (fileName != null) {
            String fn = fileName.toLowerCase();
            if (fn.endsWith(".php")) suffix = "php";
            else if (fn.endsWith(".js")) suffix = "js";
            else if (fn.endsWith(".py")) suffix = "py";
        }
        if (suffix.isEmpty()) {
            // 粘贴路：从内容嗅探
            if (t.startsWith("<?php") || t.startsWith("<?=")) suffix = "php";
            else if (t.startsWith("#!/usr/bin/env python") || t.startsWith("#!/usr/bin/python")
                    || Pattern.compile("(?m)^import \\w+|^from \\w+ import").matcher(t).find()
                    && t.contains("def ")) suffix = "py";
            else if (t.contains("module.exports") || t.contains("require(")) suffix = "js";
        }
        if (suffix.isEmpty()) {
            throw new Exception("认不出来：这段既不是服务配置，也看不出是 PHP/JS/Python 脚本。"
                    + "如果是脚本文件，选文件时带上 .php/.js/.py 后缀");
        }
        ParseResult r = new ParseResult();
        r.kind = Kind.BARE_SCRIPT;
        r.note = "认出来了：单个脚本文件";
        r.items.add(fromBareScript(suffix, fileName, t.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return r;
    }

    static ImportItem fromBareScript(String suffix, String fileName, byte[] content) {
        ImportItem it = new ImportItem();
        it.kind = Kind.BARE_SCRIPT;
        String stem = fileName == null ? "script" : fileName.replaceFirst("\\.[^.]+$", "");
        int slash = Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('\\'));
        if (slash >= 0) stem = stem.substring(slash + 1);
        it.id = "script_" + sanitize(stem);
        it.name = humanize(stem);
        it.assetName = (fileName == null ? "service." + suffix : fileName);
        int s2 = Math.max(it.assetName.lastIndexOf('/'), it.assetName.lastIndexOf('\\'));
        if (s2 >= 0) it.assetName = it.assetName.substring(s2 + 1);
        it.assetBytes = content;
        it.background = true;
        it.autoStart = false;
        it.portPolicy = "auto";
        String f = it.assetName;
        if ("php".equals(suffix)) {
            it.runtime = "php";
            // php -S 挂包目录当网站根，脚本按文件名访问
            it.command = "{filesDir}/lab/runtimes/php/bin/php -S 127.0.0.1:{port} -t {pkgDir}";
        } else if ("js".equals(suffix)) {
            it.runtime = "nodejs";
            it.command = "{filesDir}/lab/runtimes/nodejs/bin/node {pkgDir}/" + f;
        } else {
            it.runtime = "python";
            it.command = "{filesDir}/lab/runtimes/python/bin/python {pkgDir}/" + f;
        }
        it.needsReview = addLine(it.needsReview, "只装了这一个脚本文件；它引用的同目录其他文件不在这次导入里，缺了会跑不起来");
        return it;
    }

    /** lab.json 粘贴：转成一条（取默认命令）。上游形态的说明常不写 id，
     * 按包名派生（上游身份就是包名），名也没有才按缺 id 报错 */
    private static ImportItem fromLabJson(JSONObject o) throws Exception {
        LabManifest m = LabManifest.parse(o.toString(), sanitize(o.optString("name", "")));
        ImportItem it = new ImportItem();
        it.kind = Kind.LAB_JSON;
        it.id = sanitize(m.id);
        it.name = m.name;
        it.desc = m.desc;
        it.runtime = m.runtime;
        LabManifest.LabCommand c = m.defaultCommand();
        if (c == null) throw new Exception("这份包说明里没有可运行的命令");
        it.command = c.command;
        it.background = c.background;
        it.autoStart = m.autoStart;
        if (m.output != null) {
            it.portPolicy = m.output.port == null || m.output.port.isEmpty() ? "auto" : m.output.port;
            it.outputKind = m.output.kind;
            it.outputTarget = m.output.target;
        }
        return it;
    }

    // ---------------- 路7：整目录 Node 项目（压缩包里的 npm package.json） ----------------

    /** npm 的 package.json：有 name/main/scripts，且不是默影视 Item（commands 形态） */
    public static boolean isNpmPackageJson(JSONObject o) {
        if (o == null || o.has("commands")) return false;
        return o.has("scripts") || o.has("main") || o.has("name");
    }

    /**
     * 在压缩包条目里定位 npm package.json：根目录优先，其次一层目录下；
     * node_modules 里的、深于一层的都不算。找不到返回 null。
     */
    public static String findNodePackageJson(List<String> entryNames) {
        String nested = null;
        for (String n : entryNames) {
            if (n == null) continue;
            String name = n.replace('\\', '/');
            if (name.equals("package.json")) return name;
            if (nested == null && name.endsWith("/package.json")) {
                String dir = name.substring(0, name.length() - "package.json".length());
                if (!dir.contains("node_modules") && dir.indexOf('/') == dir.length() - 1) nested = name;
            }
        }
        return nested;
    }

    /** package.json 条目名 → 项目根前缀（"" 或 "drpy-node/"） */
    static String nodeRootPrefix(String pkgEntry) {
        int i = pkgEntry.lastIndexOf('/');
        return i < 0 ? "" : pkgEntry.substring(0, i + 1);
    }

    /**
     * 整目录 Node 项目 → 一条服务。成立前提：安装器把压缩包全部条目原样解进包目录，
     * 项目文件一个不少。命令先 cd 到项目根再跑（LabRunner 的 cwd 是包目录，靠 cd 落到项目根）：
     * scripts.start 原样优先，其次 main，最后项目根的 index.js；
     * 三样都没有 → command 为空，调用方据此报人话。
     */
    public static ImportItem fromNodeProject(JSONObject pkg, String pkgEntry, List<String> entryNames) {
        ImportItem it = new ImportItem();
        it.kind = Kind.NODE_PROJECT;
        String prefix = nodeRootPrefix(pkgEntry);
        String folder = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
        int fs = folder.lastIndexOf('/');
        if (fs >= 0) folder = folder.substring(fs + 1);
        String name = pkg.optString("name", "").trim();
        it.id = "node_" + sanitize(!name.isEmpty() ? name : (!folder.isEmpty() ? folder : "project"));
        it.name = !name.isEmpty() ? name : (!folder.isEmpty() ? folder : "Node 项目");
        it.desc = pkg.optString("description", "").trim();
        it.version = pkg.optString("version", "1.0").trim();
        if (it.version.isEmpty()) it.version = "1.0";
        it.runtime = "nodejs";
        it.background = true;
        it.autoStart = false;
        it.portPolicy = "auto";
        String start = "";
        JSONObject scripts = pkg.optJSONObject("scripts");
        if (scripts != null) start = scripts.optString("start", "").trim();
        String main = pkg.optString("main", "").trim();
        if (main.startsWith("./")) main = main.substring(2);
        String run = "";
        if (!start.isEmpty()) run = start;
        else if (!main.isEmpty()) run = "node " + main;
        else if (entryNames.contains(prefix + "index.js")) run = "node index.js";
        if (!run.isEmpty()) {
            it.command = "cd {pkgDir}" + (prefix.isEmpty() ? "" : "/" + folder) + " && " + run;
        }
        it.needsReview = addLine(it.needsReview, "整个项目已原样装进服务目录，命令按 package.json 生成、在项目根目录里跑");
        it.needsReview = addLine(it.needsReview,
                "项目依赖（node_modules）在包里才能直接跑；包里没有的话，先开这个服务的终端执行 npm install");
        return it;
    }

    // ---------------- 路8：远程 Node bundle（.js.md5 指针形态） ----------------

    /**
     * Node bundle 指针形态：网址以 .js.md5 结尾（如 …/cat/index.js.md5），本体只是
     * 32 位 MD5，真身是同目录去掉 .md5 的那个 .js。链接路在 LabPasteImportActivity
     * 里据此跟随指针拉整包，这里只管判定与造条目（纯逻辑，可离线单测）。
     */
    public static boolean isNodeBundleUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        return u.toLowerCase().endsWith(".js.md5");
    }

    /** 指针本体判定：去掉空白后第一段是 32 位十六进制 MD5（有的写法哈希后还跟文件名） */
    public static boolean isMd5Pointer(String content) {
        if (content == null) return false;
        String t = content.trim();
        if (t.isEmpty()) return false;
        return t.split("\\s+")[0].matches("(?i)[0-9a-f]{32}");
    }

    /** MD5 十六进制（小写），指针校验用 */
    public static String md5Hex(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md.digest(data == null ? new byte[0] : data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** 指针网址 → 真身文件名（index.js.md5 → index.js） */
    public static String nodeBundleMainName(String url) {
        String seg = lastUrlSegment(url);
        if (seg.toLowerCase().endsWith(".md5")) seg = seg.substring(0, seg.length() - 4);
        return seg.isEmpty() ? "index.js" : seg;
    }

    /** 真身文件名 → 同目录配置文件名（index.js → index.config.js） */
    public static String nodeBundleConfigName(String mainName) {
        if (mainName != null && mainName.toLowerCase().endsWith(".js")) {
            return mainName.substring(0, mainName.length() - 3) + ".config.js";
        }
        return "index.config.js";
    }

    /** 指针网址 → 同目录基址（以 / 结尾），拼真身/配置地址用 */
    public static String nodeBundleBaseUrl(String url) {
        String u = url == null ? "" : url.trim();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(0, i + 1) : u + "/";
    }

    private static String lastUrlSegment(String url) {
        if (url == null) return "";
        String u = url.trim();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(i + 1) : u;
    }

    private static String urlHost(String url) {
        if (url == null) return "";
        String u = url.trim();
        int p = u.indexOf("://");
        if (p >= 0) u = u.substring(p + 3);
        int s = u.indexOf('/');
        if (s >= 0) u = u.substring(0, s);
        int c = u.indexOf(':');
        if (c >= 0) u = u.substring(0, c);
        return u;
    }

    /**
     * Node bundle → 一条服务。成立前提：调用方已按指针把真身拉下并验过 MD5。
     * 启动走宿主生成的引导脚本（nodeBootSource）：包认的端口变量是
     * DEV_HTTP_PORT 不是 PORT，且包要宿主 require 后调 start(conf)＋宿主
     * 全局＋NODE_PATH，裸 node <main> 直跑三样全无（node-retire 阶段二定根）。
     * 产出声明随引导层落值：端口由引导层强制注入为分配口、config 端点是
     * 这类包对宿主的固定形态（点播链 tv.node.NodeRuntime.configUrl 同形
     * http://127.0.0.1:{port}/config），故 kind=vod、target 指向它——
     * 与 fromTermux 从 web_url 语义推导 tool/target 同路：值来自服务形态
     * 本身，不是导入数据里抄来的声明，也不是凭空自创。
     * 同目录配置一并落盘，引导脚本启动时读作 conf 传给 start()。
     */
    public static ImportItem fromNodeBundle(String url, byte[] mainBytes, byte[] configBytes) {
        ImportItem it = new ImportItem();
        it.kind = Kind.NODE_BUNDLE;
        String mainName = nodeBundleMainName(url);
        String host = urlHost(url);
        it.id = "nodebundle_" + sanitize((host.isEmpty() ? mainName : host + "_" + mainName).replace('.', '_'));
        it.name = "Node 服务包" + (host.isEmpty() ? "" : "（" + host + "）");
        it.desc = "";
        it.runtime = "nodejs";
        it.background = true;
        it.autoStart = false;
        it.portPolicy = "auto";
        it.outputKind = "vod";
        it.outputTarget = "http://127.0.0.1:{port}/config";
        it.assetName = mainName;
        it.assetBytes = mainBytes;
        it.extraFiles = new LinkedHashMap<>();
        if (configBytes != null && configBytes.length > 0) {
            it.extraFiles.put(nodeBundleConfigName(mainName), configBytes);
        }
        it.extraFiles.put(NODE_BOOT_NAME, nodeBootBytes());
        it.command = "DEV_HTTP_PORT={port} LAB_HOST_PORT={hostPort} "
                + "{filesDir}/lab/runtimes/nodejs/bin/node {pkgDir}/" + NODE_BOOT_NAME
                + " {pkgDir}/" + mainName;
        it.needsReview = addLine(it.needsReview,
                "这是按 .md5 指针拉下来的远程 Node 服务包，整包已部署成本地服务；同目录的配置文件也一并放进包里，启动时由引导脚本读作配置传给包");
        return it;
    }

    /** Node bundle 的识别结果（一包一条服务） */
    public static ParseResult nodeBundleResult(String url, byte[] mainBytes, byte[] configBytes) {
        ParseResult r = new ParseResult();
        r.kind = Kind.NODE_BUNDLE;
        r.items.add(fromNodeBundle(url, mainBytes, configBytes));
        r.note = "认出来了：Node 服务包（" + nodeBundleMainName(url) + "，整包按 .md5 指针拉下来的）";
        return r;
    }

    /**
     * clan:// 归一成本地路径，与点播侧 JarLoader 的口径一致（同一条规则不许
     * 两套写法）：clan://localhost/<p> 与 clan://<p> 都落到 /<p>（G-8）。
     * 不是 clan 写法返回 null。
     */
    public static String clanToLocalPath(String s) {
        if (s == null) return null;
        if (!s.toLowerCase().startsWith("clan://")) return null;
        String rest = s.substring(7).replaceFirst("(?i)^localhost/", "");
        return rest.startsWith("/") ? rest : "/" + rest;
    }

    // ---------------- 路8b：本地 Node bundle（目录 / zip / 7z 里的裸包，G-7 余路） ----------------

    /**
     * 在一堆条目名里找 Node bundle 入口：根目录或一层子目录下有 index.js，
     * 且同层没有 package.json / lab.json（有的话归 npm 项目路 / 清单路）。
     * 命中返回前缀（根目录 ""，子目录以 / 结尾），不中返回 null。
     */
    public static String findNodeBundleEntry(java.util.Collection<String> names) {
        if (names == null) return null;
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String n : names) if (n != null) set.add(n.replace('\\', '/'));
        List<String> candidates = new ArrayList<>();
        if (set.contains("index.js")) candidates.add("");
        for (String n : set) {
            if (n.endsWith("/index.js")) {
                String prefix = n.substring(0, n.length() - "index.js".length());
                String dir = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (!dir.isEmpty() && !dir.contains("/")) candidates.add(prefix);
            }
        }
        for (String prefix : candidates) {
            if (set.contains(prefix + "package.json")) continue;
            if (set.contains(prefix + "lab.json")) continue;
            if (set.contains("lab.json")) continue;
            return prefix;
        }
        return null;
    }

    /**
     * 本地 Node bundle → 一条服务（与网址路 fromNodeBundle 同一类目收口）。
     * 启动同样走引导脚本（nodeBootSource）、产出声明同样落 vod＋/config，
     * 依据见 fromNodeBundle 的注释。keepPrefix=false（7z/本地目录路）：
     * 文件由安装器按 asset/extraFiles 落到包根、引导脚本随 extraFiles 一起
     * 落；keepPrefix=true（zip 路）：文件由安装器整包解压原样落盘、引导
     * 脚本不在包里，由安装器在解压后按命令引用补写到包根（LabPackageInstaller），
     * 命令按前缀指到真入口。
     */
    public static ImportItem fromNodeBundleLocal(String displayName, Map<String, byte[]> files,
                                                 String prefix, boolean keepPrefix) {
        ImportItem it = new ImportItem();
        it.kind = Kind.NODE_BUNDLE;
        String base = displayName == null ? "" : displayName;
        int sl = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (sl >= 0) base = base.substring(sl + 1);
        for (String ext : new String[]{".zip", ".lab", ".7z"}) {
            if (base.toLowerCase().endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }
        if (base.isEmpty() || base.startsWith("labpkg")) base = "bundle";
        it.id = "nodebundle_" + sanitize(base.replace('.', '_'));
        it.name = base.matches(".*[a-zA-Z0-9].*") ? "Node 服务包（" + base + "）" : "Node 服务包（本地）";
        it.desc = "";
        it.runtime = "nodejs";
        it.background = true;
        it.autoStart = false;
        it.portPolicy = "auto";
        it.outputKind = "vod";
        it.outputTarget = "http://127.0.0.1:{port}/config";
        if (keepPrefix) {
            it.command = "DEV_HTTP_PORT={port} LAB_HOST_PORT={hostPort} "
                    + "{filesDir}/lab/runtimes/nodejs/bin/node {pkgDir}/" + NODE_BOOT_NAME
                    + " {pkgDir}/" + prefix + "index.js";
        } else {
            it.assetName = "index.js";
            Map<String, byte[]> extras = new LinkedHashMap<>();
            if (files != null) {
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    String n = e.getKey().replace('\\', '/');
                    if (!n.startsWith(prefix)) continue;
                    String rel = n.substring(prefix.length());
                    if (rel.isEmpty() || rel.endsWith("/")) continue;
                    if (rel.equals("index.js")) it.assetBytes = e.getValue();
                    else extras.put(rel, e.getValue());
                }
            }
            // 引导脚本由宿主生成、随包文件一起落盘；包里万一有同名文件以宿主为准
            extras.put(NODE_BOOT_NAME, nodeBootBytes());
            it.extraFiles = extras;
            it.command = "DEV_HTTP_PORT={port} LAB_HOST_PORT={hostPort} "
                    + "{filesDir}/lab/runtimes/nodejs/bin/node {pkgDir}/" + NODE_BOOT_NAME
                    + " {pkgDir}/index.js";
        }
        it.needsReview = addLine(it.needsReview,
                "本地 Node 服务包：整包文件已部署成本地服务，启动经引导脚本拉起 index.js；这类包没有清单，端口自动分配，起来后在服务页可直接当点播源接入");
        return it;
    }

    /** 本地 Node bundle 的识别结果（一包一条服务） */
    public static ParseResult nodeBundleLocalResult(String displayName, Map<String, byte[]> files, String prefix) {
        ParseResult r = new ParseResult();
        r.kind = Kind.NODE_BUNDLE;
        r.items.add(fromNodeBundleLocal(displayName, files, prefix, false));
        r.note = "认出来了：Node 服务包（本地整包，入口 index.js）";
        return r;
    }

    // ---------------- .lab.7z 离线包（上游导出布局） ----------------

    /** zip 判定：魔数 PK\x03\x04 或文件名后缀（.zip/.lab） */
    public static boolean isZip(byte[] data, String fileName) {
        if (data != null && data.length >= 4
                && data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            return true;
        }
        if (fileName != null) {
            String f = fileName.toLowerCase();
            int q = f.indexOf('?');
            if (q >= 0) f = f.substring(0, q);
            if (f.endsWith(".zip") || f.endsWith(".lab")) return true;
        }
        return false;
    }

    private static final byte[] SEVENZ_MAGIC = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    /** 7z 判定：魔数或文件名后缀（.lab.7z/.7z） */
    public static boolean isSevenZ(byte[] data, String fileName) {
        if (data != null && data.length >= 6) {
            boolean ok = true;
            for (int i = 0; i < 6; i++) {
                if (data[i] != SEVENZ_MAGIC[i]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        if (fileName != null) {
            String f = fileName.toLowerCase();
            if (f.endsWith(".lab.7z") || f.endsWith(".7z")) return true;
        }
        return false;
    }

    /**
     * 读上游 .lab.7z：package.json 是默影视 Item JSON 时走 Item 转换，
     * binary/ 条目挂到每条服务的 extraFiles（安装时落进包目录）；
     * 是 npm 项目说明时走整目录 Node 项目（全部条目原样落盘）；
     * commands.json（用户自建命令）转不过来，如实提示；manifest.json 忽略。
     */
    public static ParseResult parseSevenZ(byte[] data) throws Exception {
        File tmp = File.createTempFile("labpkg", ".7z");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            return parseSevenZFile(tmp);
        } finally {
            tmp.delete();
        }
    }

    /**
     * 流式入口：归档边读边落临时文件再解析，整包字节不进内存——原先调用方先把
     * 整个 .7z（上限 30MB）读成 byte[] 再传进来，大离线包内存峰值白白翻倍。
     * binary/ 条目内容仍按条目留内存（安装器要逐个落盘，与 zip 路同口径）。
     */
    public static ParseResult parseSevenZ(java.io.InputStream in) throws Exception {
        File tmp = File.createTempFile("labpkg", ".7z");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return parseSevenZFile(tmp);
        } finally {
            tmp.delete();
        }
    }

    private static ParseResult parseSevenZFile(File tmp) throws Exception {
        String pkgJson = null;
        List<String> names = new ArrayList<>();
        Map<String, byte[]> pkgJsonFiles = new LinkedHashMap<>();
        Map<String, byte[]> bins = new LinkedHashMap<>();
        boolean hasUserCmds = false;
        // 第一遍只记条目名，只把 package.json 与 binary/ 的字节留内存——其余
        // 条目不读入（此前全条目驻留，大包导入内存峰值白白翻倍）。确诊是
        // npm 整目录项目后才第二遍整包读入（见下），普通离线包不付这个代价。
        // 注：new SevenZFile(File) 在 1.27 标废弃，但 builder API 要拖 commons-io（App 没这个依赖），故沿用它
        try (SevenZFile sz = new SevenZFile(tmp)) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                names.add(name);
                if (name.equals("package.json") || name.endsWith("/package.json")) {
                    byte[] b = readSzEntryBytes(sz);
                    pkgJsonFiles.put(name, b);
                    if (pkgJson == null) pkgJson = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                } else if (name.equals("commands.json") || name.endsWith("/commands.json")) {
                    hasUserCmds = true;
                } else {
                    int bi = name.indexOf("binary/");
                    if (bi >= 0) {
                        String rel = name.substring(bi + 7);
                        while (rel.startsWith("/")) rel = rel.substring(1);
                        if (!rel.isEmpty() && !rel.contains("..")) {
                            bins.put(rel, readSzEntryBytes(sz));
                        }
                    }
                }
            }
        }
        // package.json 若是 npm 项目说明（整目录 Node 项目）：全部条目原样落进包目录，
        // 命令按 package.json 生成，在项目根里跑（与 zip 项目路同一套口径）
        String npmEntry = findNodePackageJson(names);
        if (npmEntry != null && pkgJsonFiles.containsKey(npmEntry)) {
            JSONObject pj = null;
            try {
                pj = new JSONObject(new String(pkgJsonFiles.get(npmEntry), java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            if (pj != null && isNpmPackageJson(pj)) {
                ImportItem it = fromNodeProject(pj, npmEntry, names);
                if (it.command == null || it.command.trim().isEmpty()) {
                    throw new Exception("这个 Node 项目找不到入口：package.json 里没写 start/main，项目根也没有 index.js");
                }
                // 第二遍：整目录项目才需要全部条目落盘，这时才整包读入
                Map<String, byte[]> allFiles = new LinkedHashMap<>();
                try (SevenZFile sz2 = new SevenZFile(tmp)) {
                    SevenZArchiveEntry e2;
                    while ((e2 = sz2.getNextEntry()) != null) {
                        if (e2.isDirectory()) continue;
                        allFiles.put(e2.getName().replace('\\', '/'), readSzEntryBytes(sz2));
                    }
                }
                it.extraFiles = new LinkedHashMap<>(allFiles);
                ParseResult r = new ParseResult();
                r.kind = Kind.NODE_PROJECT;
                r.note = "认出来了：Node 项目离线包（整个项目 1 个服务）";
                r.items.add(it);
                return r;
            }
        }
        // 没有 package.json、也没有 lab.json，但根/一层目录下有 index.js：
        // 是本地 Node bundle 裸包（G-7 余路）——整包文件落盘、命令跑 index.js，
        // 与网址 .md5 路、zip 路同一类目（Kind.NODE_BUNDLE）收口
        if (pkgJson == null) {
            String bundlePrefix = findNodeBundleEntry(names);
            if (bundlePrefix != null) {
                Map<String, byte[]> allFiles = new LinkedHashMap<>();
                try (SevenZFile sz2 = new SevenZFile(tmp)) {
                    SevenZArchiveEntry e2;
                    while ((e2 = sz2.getNextEntry()) != null) {
                        if (e2.isDirectory()) continue;
                        allFiles.put(e2.getName().replace('\\', '/'), readSzEntryBytes(sz2));
                    }
                }
                return nodeBundleLocalResult(tmp.getName(), allFiles, bundlePrefix);
            }
            throw new Exception("无效的离线包：里面没有 package.json");
        }
        String nm = new JSONObject(pkgJson).optString("name", "");
        if (nm.contains("/") || nm.contains("..")) throw new Exception("包名非法");
        ParseResult r = parseJson(pkgJson);
        for (ImportItem it : r.items) {
            if (!bins.isEmpty()) it.extraFiles = new LinkedHashMap<>(bins);
            if (hasUserCmds) it.needsReview = addLine(it.needsReview, "包里的自建命令（commands.json）没有导入");
        }
        r.note = "认出来了：默影视实验室离线包（" + r.items.size() + " 条命令）";
        return r;
    }

    private static byte[] readSzEntryBytes(SevenZFile sz) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = sz.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---------------- 小工具 ----------------

    /** 从命令里猜环境：先看路径里带没带 runtimes 关键字，再看二进制名 */
    static String runtimeFromCommand(String cmd) {
        if (cmd == null) return "none";
        String c = cmd.toLowerCase();
        if (c.contains("proot")) return "ubuntu";
        if (c.contains("nodejs")) return "nodejs";
        if (c.contains("php")) return "php";
        if (c.contains("python")) return "python";
        // 上游命令直接调环境二进制：mihomo/frpc/alist/aria2c/ffmpeg
        if (binToken(c, "mihomo")) return "mihomo";
        if (binToken(c, "frpc") || binToken(c, "frp")) return "frp";
        if (binToken(c, "alist")) return "alist";
        if (binToken(c, "aria2c")) return "aria2";
        if (binToken(c, "ffmpeg")) return "ffmpeg";
        if (Pattern.compile("(^|[\\s;/])node(\\s|$|/)").matcher(c).find()) return "nodejs";
        return "none";
    }

    /** 命令里是否以独立 token 出现某个二进制名（防子串误伤，如 frp 不匹配 frpc） */
    static boolean binToken(String c, String bin) {
        return Pattern.compile("(^|[\\s;/\"'])" + Pattern.quote(bin) + "([\\s\"']|$|[/;])").matcher(c).find();
    }

    /** 描述里找「(端口 9528)」这类端口声明 */
    static String findPortInText(String t) {
        if (t == null) return null;
        Matcher m = Pattern.compile("端口\\s*(\\d{2,5})").matcher(t);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(?i)port\\s*[:=]?\\s*(\\d{2,5})").matcher(t);
        if (m.find()) return m.group(1);
        return null;
    }

    static String sanitize(String s) {
        if (s == null) return "svc";
        String r = s.replaceAll("[^a-zA-Z0-9_.-]", "_");
        r = r.replaceAll("_+", "_").replaceAll("^[_.]+|[_.]+$", "");
        return r.isEmpty() ? "svc" : r;
    }

    /** 文件名转人话：kankan-live_2_kegv → Kankan Live（机械转换，真名翻译不了） */
    static String humanize(String stem) {
        if (stem == null) return "脚本服务";
        String s = stem.replaceFirst("(_\\d+(_[a-z0-9]+)?)$", ""); // 去 _2_kegv 这类尾巴
        s = s.replaceAll("[-_]+", " ").trim();
        if (s.isEmpty()) return "脚本服务";
        StringBuilder out = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.isEmpty()) continue;
            out.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) out.append(w.substring(1));
            out.append(' ');
        }
        return out.toString().trim();
    }

    static String addLine(String base, String line) {
        if (base == null || base.isEmpty()) return line;
        if (base.contains(line)) return base;
        return base + "\n" + line;
    }

    /** ImportItem → LabManifest（安装用） */
    public static LabManifest toManifest(ImportItem it) {
        LabManifest m = new LabManifest();
        m.id = it.id;
        m.name = it.name;
        m.desc = it.desc == null ? "" : it.desc;
        m.icon = "服";
        m.version = it.version == null || it.version.isEmpty() ? "1.0" : it.version;
        m.group = it.group == null ? "" : it.group;
        m.runtime = it.runtime == null ? "none" : it.runtime;
        m.autoStart = it.autoStart;
        m.mkdir.addAll(it.mkdirs);
        m.varPath.putAll(it.varPath);
        LabManifest.LabCommand c = new LabManifest.LabCommand();
        c.id = "main";
        c.name = it.name;
        c.desc = "";
        c.command = it.command;
        c.background = it.background;
        c.showOutput = it.showOutput;
        c.autoExecute = true;
        c.minVersion = it.minVersion;
        c.variables.addAll(it.variables);
        c.download = it.download;
        m.commands.add(c);
        if (it.outputKind != null && !it.outputKind.isEmpty()) {
            LabManifest.LabOutput o = new LabManifest.LabOutput();
            o.kind = it.outputKind;
            o.target = it.outputTarget == null ? "" : it.outputTarget;
            o.port = it.portPolicy == null || it.portPolicy.isEmpty() ? "auto" : it.portPolicy;
            m.output = o;
        } else if (it.portPolicy != null && it.portPolicy.matches("\\d+")) {
            // 没产出声明、只有固定端口时也要把端口带进清单（G-3）：此前这里
            // 直接丢掉，运行时 allocPort 只能随机分口，命令里已换成 {port} 的
            // 字面端口跟着跑偏，配置里写死同口的对端永远连不上（WebHome 形态
            // 必中）。合成一个只有端口的 output：kind/target 都空，所有消费点
            // 都以 kind/target 非空为门，行为与无产出时一致，只多了端口策略
            LabManifest.LabOutput o = new LabManifest.LabOutput();
            o.kind = "";
            o.target = "";
            o.port = it.portPolicy;
            m.output = o;
        }
        return m;
    }
}
