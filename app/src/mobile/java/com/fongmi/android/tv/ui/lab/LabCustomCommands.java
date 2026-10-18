package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义命令：按包增/改/删，存包目录 custom.json（上游 LabCustomCommands 口径的精简版）。
 * custom=true 的命令走与包命令同一套运行链（变量表/依赖下载/前后台），卸载包时随目录一起删。
 * 包设置值：包目录 settings.json，{key:value}，命令里的同名变量优先取这里。
 */
public final class LabCustomCommands {

    /** 自定义命令（字段与 LabManifest.LabCommand 对齐的子集） */
    public static class CustomCommand {
        public String id;
        public String name;
        public String desc;
        public String command;
        public boolean background = true;
        public boolean autoExecute = false;
        public int minVersion = 0;
        public final List<LabManifest.LabVar> variables = new ArrayList<>();
    }

    private LabCustomCommands() {
    }

    private static File customFile(Context c, String pkgId) {
        return new File(LabPaths.packageDir(c, pkgId), "custom.json");
    }

    private static File settingsFile(Context c, String pkgId) {
        return new File(LabPaths.packageDir(c, pkgId), "settings.json");
    }

    /** 自定义命令列表（按添加顺序） */
    public static synchronized List<CustomCommand> list(Context c, String pkgId) {
        List<CustomCommand> out = new ArrayList<>();
        File f = customFile(c, pkgId);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Type t = new TypeToken<List<CustomCommand>>() {
            }.getType();
            List<CustomCommand> saved = new Gson().fromJson(r, t);
            if (saved != null) for (CustomCommand cc : saved) {
                if (cc != null && !TextUtils.isEmpty(cc.command)) out.add(cc);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void add(Context c, String pkgId, CustomCommand cmd) {
        List<CustomCommand> all = list(c, pkgId);
        if (TextUtils.isEmpty(cmd.id)) cmd.id = "custom" + System.currentTimeMillis();
        all.add(cmd);
        save(c, pkgId, all);
    }

    public static synchronized void update(Context c, String pkgId, CustomCommand cmd) {
        List<CustomCommand> all = list(c, pkgId);
        for (int i = 0; i < all.size(); i++) {
            if (cmd.id != null && cmd.id.equals(all.get(i).id)) {
                all.set(i, cmd);
                break;
            }
        }
        save(c, pkgId, all);
    }

    public static synchronized void remove(Context c, String pkgId, String cmdId) {
        List<CustomCommand> all = list(c, pkgId);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (cmdId != null && cmdId.equals(all.get(i).id)) all.remove(i);
        }
        save(c, pkgId, all);
    }

    private static synchronized void save(Context c, String pkgId, List<CustomCommand> all) {
        try {
            File f = customFile(c, pkgId);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f);
                 OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                new Gson().toJson(all, w);
            }
        } catch (Exception ignored) {
        }
    }

    /** 包设置值读 */
    public static synchronized Map<String, String> settingsOf(Context c, String pkgId) {
        Map<String, String> out = new HashMap<>();
        File f = settingsFile(c, pkgId);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f);
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Type t = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> saved = new Gson().fromJson(r, t);
            if (saved != null) out.putAll(saved);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 包设置值写 */
    public static synchronized void saveSettings(Context c, String pkgId, Map<String, String> values) {
        try {
            File f = settingsFile(c, pkgId);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(f);
                 OutputStreamWriter w = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
                new Gson().toJson(values == null ? new HashMap<String, String>() : values, w);
            }
        } catch (Exception ignored) {
        }
    }

    /** 变量值缓存：按包＋变量 key 落盘（上游变量值缓存复用口径） */
    public static synchronized Map<String, String> varCache(Context c, String pkgId) {
        return settingsOf(c, pkgId);
    }

    /** 变量值缓存写：合并进包设置存储（读侧 varCache 即 settingsOf） */
    public static synchronized void saveVarCache(Context c, String pkgId, Map<String, String> values) {
        if (values == null || values.isEmpty()) return;
        Map<String, String> cur = settingsOf(c, pkgId);
        cur.putAll(values);
        saveSettings(c, pkgId, cur);
    }

    /** 自定义命令转成 LabManifest.LabCommand 形态，复用运行链 */
    public static LabManifest.LabCommand toCommand(CustomCommand cc) {
        LabManifest.LabCommand cmd = new LabManifest.LabCommand();
        cmd.id = cc.id;
        cmd.name = cc.name;
        cmd.desc = cc.desc == null ? "" : cc.desc;
        cmd.command = cc.command;
        cmd.background = cc.background;
        cmd.autoExecute = cc.autoExecute;
        cmd.minVersion = cc.minVersion;
        cmd.variables.addAll(cc.variables);
        return cmd;
    }
}
