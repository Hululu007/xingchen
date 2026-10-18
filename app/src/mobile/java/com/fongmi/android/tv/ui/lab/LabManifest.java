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
 *   commands[]    命令列表：{id,name,desc,command,background,autoExecute}
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
    public LabOutput output;

    public static class LabCommand {
        public String id;
        public String name;
        public String desc;
        public String command;
        public boolean background = true;
        public boolean autoExecute = false;
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
                m.commands.add(cmd);
            }
        }
        if (m.commands.isEmpty()) throw new Exception("lab.json 没有可运行的命令");
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

    /** 人话地址：http://127.0.0.1:{port}/live.m3u8 →「手机自己 : 8787 /live.m3u8」 */
    public String humanAddress(int port) {
        if (output == null || TextUtils.isEmpty(output.target)) return "";
        String t = output.target.replace("{port}", String.valueOf(port));
        String path = t.replaceFirst("^[a-zA-Z]+://[^/]*", "");
        if (TextUtils.isEmpty(path)) path = "/";
        return "手机自己 : " + port + " " + path;
    }
}
