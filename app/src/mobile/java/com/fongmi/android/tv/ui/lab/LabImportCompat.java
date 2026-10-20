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
 * 路径占位约定（LabRunner.expand 会替换）：{port} {pkgDir} {filesDir}
 */
public final class LabImportCompat {

    public enum Kind { WEBHTV_COMMAND, TERMUX_LIST, BARE_SCRIPT, LAB_JSON, SITE_SOURCE, WEBHTV_ITEM, NODE_PROJECT }

    /** 归一后的一个服务（安装前形态） */
    public static class ImportItem {
        public String id = "";
        public String name = "";
        public String desc = "";
        public String version = "1.0";
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
        /** 运行前依赖下载（上游 download 口径） */
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
            r.note = "认出来了：Termux 服务清单（" + svcs.length() + " 个服务）";
            for (int i = 0; i < svcs.length(); i++) r.items.add(fromTermux(svcs.getJSONObject(i)));
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
        // background 缺省按上游口径取 false（上游 Command.background 是原生 boolean，
        // 没写就是前台型）；星辰自家 lab.json 的缺省 true 在 LabManifest 里，不受这里影响
        it.background = o.optBoolean("background", false);
        it.autoStart = o.optBoolean("auto_execute", o.optBoolean("autoExecute", false));
        it.showOutput = o.optBoolean("show_output", o.optBoolean("showOutput", true));
        it.minVersion = o.optInt("min_version", 0);
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
        String version = o.optString("version", "1.0").trim();
        boolean autoInstall = o.optBoolean("auto_install", false);
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
            String cmd = c.optString("command", "");
            it.runtime = runtimeFromCommand(cmd);
            it.command = translateWebhtvCommand(cmd, it);
            // background 缺省按上游口径取 false（没写=前台型），同命令条目路
            it.background = c.optBoolean("background", false);
            it.showOutput = c.optBoolean("show_output", c.optBoolean("showOutput", true));
            // auto_execute 才是「自动运行」的来源；包级 auto_install 是上游装目录时
            // 自动把这个包装上，不等于把每条命令都设成自启，不再折进来（见下面提示行）
            it.autoStart = c.optBoolean("auto_execute", false);
            it.minVersion = c.optInt("min_version", 0);
            it.variables.addAll(parseLabVarList(c.optJSONArray("variables")));
            it.variables.addAll(pkgVars);
            JSONObject dl = c.optJSONObject("download");
            if (dl != null) {
                String url = dl.optString("url", "").trim();
                if (!url.isEmpty()) {
                    LabManifest.LabDownload d = new LabManifest.LabDownload();
                    d.url = url;
                    d.file = dl.optString("save_to", "").trim();
                    it.download = d;
                } else {
                    it.needsReview = addLine(it.needsReview,
                            "这条命令声明了下载（" + dl.optString("title", "下载文件") + "），但没给下载地址");
                }
            }
            if (!pkgVars.isEmpty()) {
                it.needsReview = addLine(it.needsReview, "包设置已并入变量表（上游是按环境变量注入，语义可能有差异）");
            }
            if (autoInstall) {
                it.needsReview = addLine(it.needsReview, "上游包声明自动安装（装目录时自动装上这个包）；星辰的环境和包都是按需装的，这条没有转成自动启动");
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
            var.defaultValue = v.has("default") ? v.optString("default", null) : null;
            var.hint = v.optString("hint", null);
            var.required = v.optBoolean("required", false);
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

    /** lab.json 粘贴：转成一条（取默认命令） */
    private static ImportItem fromLabJson(JSONObject o) throws Exception {
        LabManifest m = LabManifest.parse(o.toString());
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

    // ---------------- .lab.7z 离线包（上游导出布局） ----------------

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
            String pkgJson = null;
            List<String> names = new ArrayList<>();
            Map<String, byte[]> allFiles = new LinkedHashMap<>();
            Map<String, byte[]> bins = new LinkedHashMap<>();
            boolean hasUserCmds = false;
            // 注：new SevenZFile(File) 在 1.27 标废弃，但 builder API 要拖 commons-io（App 没这个依赖），故沿用它
            try (SevenZFile sz = new SevenZFile(tmp)) {
                SevenZArchiveEntry e;
                while ((e = sz.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName().replace('\\', '/');
                    names.add(name);
                    byte[] b = readSzEntryBytes(sz);
                    allFiles.put(name, b);
                    if (name.equals("package.json") || name.endsWith("/package.json")) {
                        if (pkgJson == null) pkgJson = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                    } else if (name.equals("commands.json") || name.endsWith("/commands.json")) {
                        hasUserCmds = true;
                    } else {
                        int bi = name.indexOf("binary/");
                        if (bi >= 0) {
                            String rel = name.substring(bi + 7);
                            while (rel.startsWith("/")) rel = rel.substring(1);
                            if (!rel.isEmpty() && !rel.contains("..")) {
                                bins.put(rel, b);
                            }
                        }
                    }
                }
            }
            // package.json 若是 npm 项目说明（整目录 Node 项目）：全部条目原样落进包目录，
            // 命令按 package.json 生成，在项目根里跑（与 zip 项目路同一套口径）
            String npmEntry = findNodePackageJson(names);
            if (npmEntry != null && allFiles.containsKey(npmEntry)) {
                JSONObject pj = null;
                try {
                    pj = new JSONObject(new String(allFiles.get(npmEntry), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
                if (pj != null && isNpmPackageJson(pj)) {
                    ImportItem it = fromNodeProject(pj, npmEntry, names);
                    if (it.command == null || it.command.trim().isEmpty()) {
                        throw new Exception("这个 Node 项目找不到入口：package.json 里没写 start/main，项目根也没有 index.js");
                    }
                    it.extraFiles = new LinkedHashMap<>(allFiles);
                    ParseResult r = new ParseResult();
                    r.kind = Kind.NODE_PROJECT;
                    r.note = "认出来了：Node 项目离线包（整个项目 1 个服务）";
                    r.items.add(it);
                    return r;
                }
            }
            if (pkgJson == null) throw new Exception("无效的离线包：里面没有 package.json");
            String nm = new JSONObject(pkgJson).optString("name", "");
            if (nm.contains("/") || nm.contains("..")) throw new Exception("包名非法");
            ParseResult r = parseJson(pkgJson);
            for (ImportItem it : r.items) {
                if (!bins.isEmpty()) it.extraFiles = new LinkedHashMap<>(bins);
                if (hasUserCmds) it.needsReview = addLine(it.needsReview, "包里的自建命令（commands.json）没有导入");
            }
            r.note = "认出来了：默影视实验室离线包（" + r.items.size() + " 条命令）";
            return r;
        } finally {
            tmp.delete();
        }
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
        m.runtime = it.runtime == null ? "none" : it.runtime;
        m.autoStart = it.autoStart;
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
        }
        return m;
    }
}
