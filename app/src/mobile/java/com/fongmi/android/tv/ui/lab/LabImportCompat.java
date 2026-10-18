package com.fongmi.android.tv.ui.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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

    public enum Kind { WEBHTV_COMMAND, TERMUX_LIST, BARE_SCRIPT, LAB_JSON }

    /** 归一后的一个服务（安装前形态） */
    public static class ImportItem {
        public String id = "";
        public String name = "";
        public String desc = "";
        public String runtime = "none";      // python | nodejs | php | none
        public String command = "";          // 含 {port}/{pkgDir}/{filesDir} 占位
        public boolean background = true;
        public boolean autoStart = false;
        public String portPolicy = "auto";   // auto 或固定端口数字
        public String outputKind = "";       // live | vod | pan | tool，空=未声明
        public String outputTarget = "";     // 地址模板，含 {port}
        /** 映射不了、要用户人工确认的人话，一条一行 */
        public String needsReview = "";
        public Kind kind;
        /** 裸脚本路：脚本文件名＋内容，安装时拷进包目录 */
        public String assetName = "";
        public byte[] assetBytes;
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
            throw new Exception("这个 JSON 数组认不出来：里面不是服务命令条目");
        }
        JSONObject o = new JSONObject(t);
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
        if (o.has("commands") || (o.has("id") && o.has("runtime"))) {
            r.kind = Kind.LAB_JSON;
            r.note = "认出来了：实验室自己的包说明";
            r.items.add(fromLabJson(o));
            return r;
        }
        throw new Exception("这段 JSON 认不出来：不是服务清单、不是命令条目，也不是实验室包说明");
    }

    // ---------------- 路1：默影视命令条目 ----------------

    private static ImportItem fromWebhtv(JSONObject o, int idx) {
        ImportItem it = new ImportItem();
        it.kind = Kind.WEBHTV_COMMAND;
        String rawId = o.optString("id", "").trim();
        it.id = sanitize(rawId.isEmpty() ? "webhtv" + idx : rawId);
        it.name = o.optString("name", it.id).trim();
        it.desc = o.optString("description", o.optString("desc", "")).trim();
        it.background = o.optBoolean("background", true);
        it.autoStart = o.optBoolean("auto_execute", o.optBoolean("autoExecute", false));
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

    // ---------------- 小工具 ----------------

    /** 从命令里猜环境：先看路径里带没带 runtimes 关键字，再看二进制名 */
    static String runtimeFromCommand(String cmd) {
        if (cmd == null) return "none";
        String c = cmd.toLowerCase();
        if (c.contains("nodejs")) return "nodejs";
        if (c.contains("php")) return "php";
        if (c.contains("python")) return "python";
        if (Pattern.compile("(^|[\\s;/])node(\\s|$|/)").matcher(c).find()) return "nodejs";
        return "none";
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
        m.version = "1.0";
        m.runtime = it.runtime == null ? "none" : it.runtime;
        m.autoStart = it.autoStart;
        LabManifest.LabCommand c = new LabManifest.LabCommand();
        c.id = "main";
        c.name = it.name;
        c.desc = "";
        c.command = it.command;
        c.background = it.background;
        c.autoExecute = true;
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
