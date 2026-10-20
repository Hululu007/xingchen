package com.fongmi.android.tv.ui.lab;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * .lab 包格式 v1（定稿）：
 * zip 根目录 lab.json，其余文件在 files/ 下（解开后合并到包根）。
 *
 * lab.json 字段：
 *   id            包唯一标识（必填）
 *   name          显示名（必填）
 *   desc          一句人话简介
 *   icon          图标字（1-2 个字，如 Py/Js/网/剧）
 *   version       包版本
 *   runtime       python | nodejs | php | mihomo | alist | frp | aria2 | ubuntu | java | bun | ffmpeg | none（必填）
 *   autoStart     默认是否自动启动
 *   commands[]    命令列表：{id,name,desc,command,background,autoExecute,showOutput}
 *                 showOutput（上游 show_output 口径）：前台型命令运行后是否把输出端到眼前，缺省 true
 *                 command 支持占位：{port}（端口自动分配时替换）、{pkgDir}、{filesDir}
 *   output        产出声明：{kind: live|vod|pan|tool, target, port: auto|数字, actionLabel}
 *                 live/vod 的 target 是地址模板（如 http://127.0.0.1:{port}/live.m3u8）
 *                 缺省 output 时，壳子给「当直播源用/当点播源用」两钮让用户选。
 */
public class LabManifest {

    public String id;
    public String name;
    public String desc;
    public String icon;
    public String version;
    public String runtime = "none";
    public boolean autoStart = false;
    public final List<LabCommand> commands = new ArrayList<>();
    /** 包设置项（复用变量表 UI）：{key,name,type,defaultValue,hint,required,...} */
    public final List<LabVar> settings = new ArrayList<>();
    public LabOutput output;

    public static class LabCommand {
        public String id;
        public String name;
        public String desc;
        public String command;
        public boolean background = true;
        public boolean autoExecute = false;
        /** 运行后是否展示输出（上游 show_output，缺省 true；false=只在后台跑、输出只进记录） */
        public boolean showOutput = true;
        /** 包声明最低 App versionCode，不够就提示升级 */
        public int minVersion = 0;
        /** 运行前依赖：先把 url 下到包内 file 再跑 */
        public LabDownload download;
        /** 命令变量表（上游 Variable 口径）：text/text_multiline/number/select/switch/file/directory */
        public final List<LabVar> variables = new ArrayList<>();
    }

    /** 命令变量一项（名字＋必填标记渲染；hint 只做输入框 placeholder，不单独成行） */
    public static class LabVar {
        public String key;
        public String name;
        public String type = "text";
        public String defaultValue;
        public String hint;
        public boolean required;
        public String filter;
        public String min;
        public String max;
        public final List<LabOption> options = new ArrayList<>();
    }

    public static class LabOption {
        public String label;
        public String value;
    }

    /** 命令依赖下载：运行前把 url 落到包内 file（相对包目录），缺了才下 */
    public static class LabDownload {
        public String url;
        public String file;
    }

    public static class LabOutput {
        /** live | vod | pan | tool */
        public String kind = "";
        /** 地址模板，支持 {port} */
        public String target = "";
        /** auto 或固定端口号 */
        public String port = "auto";
        /** 主动作按钮文案，缺省按 kind 取默认 */
        public String actionLabel = "";
    }

    public LabCommand defaultCommand() {
        for (LabCommand c : commands) if (c.autoExecute) return c;
        return commands.isEmpty() ? null : commands.get(0);
    }

    public String actionLabel() {
        if (output != null && !TextUtils.isEmpty(output.actionLabel)) return output.actionLabel;
        String kind = output == null ? "" : output.kind;
        if ("live".equals(kind)) return "变成我的直播源";
        if ("vod".equals(kind)) return "变成我的点播源";
        return "打开看看";
    }

    public static LabManifest parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        LabManifest m = new LabManifest();
        m.id = o.optString("id", "").trim();
        m.name = o.optString("name", "").trim();
        m.desc = o.optString("desc", "");
        m.icon = o.optString("icon", "包");
        m.version = o.optString("version", "1.0");
        m.runtime = o.optString("runtime", "none").trim().toLowerCase();
        m.autoStart = o.optBoolean("autoStart", false);
        if (TextUtils.isEmpty(m.id)) throw new Exception("lab.json 缺 id");
        if (TextUtils.isEmpty(m.name)) throw new Exception("lab.json 缺 name");
        JSONArray cs = o.optJSONArray("commands");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.getJSONObject(i);
                LabCommand cmd = new LabCommand();
                cmd.id = c.optString("id", "cmd" + i);
                cmd.name = c.optString("name", cmd.id);
                cmd.desc = c.optString("desc", "");
                cmd.command = c.optString("command", "");
                cmd.background = c.optBoolean("background", true);
                cmd.autoExecute = c.optBoolean("autoExecute", false);
                // 两种写法都吃："show_output"（上游原名）与 "showOutput"（Gson 落盘回读的字段名）
                cmd.showOutput = c.has("show_output") ? c.optBoolean("show_output", true)
                        : c.optBoolean("showOutput", true);
                cmd.minVersion = c.optInt("min_version", 0);
                JSONObject dl = c.optJSONObject("download");
                if (dl != null) {
                    LabDownload d = new LabDownload();
                    d.url = dl.optString("url", "");
                    d.file = dl.optString("file", "");
                    if (!TextUtils.isEmpty(d.url)) cmd.download = d;
                }
                cmd.variables.addAll(parseVars(c.optJSONArray("variables")));
                m.commands.add(cmd);
            }
        }
        if (m.commands.isEmpty()) throw new Exception("lab.json 没有可运行的命令");
        m.settings.addAll(parseVars(o.optJSONArray("settings")));
        JSONObject out = o.optJSONObject("output");
        if (out != null) {
            LabOutput op = new LabOutput();
            op.kind = out.optString("kind", "").trim().toLowerCase();
            op.target = out.optString("target", "").trim();
            op.port = out.optString("port", "auto").trim().toLowerCase();
            op.actionLabel = out.optString("actionLabel", "");
            m.output = op;
        }
        return m;
    }

    /** 解析变量表（上游 Variable 口径，子集） */
    private static List<LabVar> parseVars(JSONArray arr) {
        List<LabVar> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.optJSONObject(i);
            if (v == null) continue;
            LabVar var = new LabVar();
            var.key = v.optString("key", "").trim();
            var.name = v.optString("name", var.key);
            var.type = v.optString("type", "text").trim().toLowerCase();
            // 兼容两种写法："default"（手写 lab.json）与 "defaultValue"（Gson 落盘时的字段名）
            var.defaultValue = v.has("default") ? v.optString("default", null)
                    : (v.has("defaultValue") ? v.optString("defaultValue", null) : null);
            var.hint = v.optString("hint", null);
            var.required = v.optBoolean("required", false);
            var.filter = v.optString("filter", null);
            var.min = v.has("min") ? v.optString("min", null) : null;
            var.max = v.has("max") ? v.optString("max", null) : null;
            JSONArray opts = v.optJSONArray("options");
            if (opts != null) {
                for (int j = 0; j < opts.length(); j++) {
                    JSONObject o2 = opts.optJSONObject(j);
                    if (o2 == null) continue;
                    LabOption op = new LabOption();
                    op.label = o2.optString("label", "");
                    op.value = o2.optString("value", op.label);
                    var.options.add(op);
                }
            }
            if (!TextUtils.isEmpty(var.key)) out.add(var);
        }
        return out;
    }

    /** 人话地址：http://127.0.0.1:{port}/live.m3u8 →「手机自己 : 8787 /live.m3u8」 */
    public String humanAddress(int port) {
        if (output == null || TextUtils.isEmpty(output.target)) return "";
        String t = output.target.replace("{port}", String.valueOf(port));
        String path = t.replaceFirst("^[a-zA-Z]+://[^/]*", "");
        if (TextUtils.isEmpty(path)) path = "/";
        return "手机自己 : " + port + " " + path;
    }
}
