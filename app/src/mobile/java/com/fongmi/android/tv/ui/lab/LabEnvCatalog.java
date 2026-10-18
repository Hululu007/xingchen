package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境包公开仓目录（上游 lab_template 口径的星辰版）：
 * assets/lab_env_catalog.json —— 11 个环境的 id/name/version/downloads[]，
 * downloads[] 每项 {arch, url, size, mirrors[]}。
 * 目录还没生成时全空，安装回退旧下载地址口径（见 LabRuntimeManager）。
 *
 * 对接点：仓代理产出 ~/workspace/lab-assets-manifest.json 后，主会话转成此资产文件打包。
 */
public final class LabEnvCatalog {

    public static class Download {
        public String arch = "";
        public String url = "";
        public long size = 0;
        public final List<String> mirrors = new ArrayList<>();

        /** 主地址＋镜像依次试 */
        public List<String> allUrls() {
            List<String> out = new ArrayList<>();
            if (!TextUtils.isEmpty(url)) out.add(url);
            out.addAll(mirrors);
            return out;
        }
    }

    public static class EnvEntry {
        public String id = "";
        public String name = "";
        public String version = "";
        public final List<Download> downloads = new ArrayList<>();
    }

    private LabEnvCatalog() {
    }

    private static Map<String, EnvEntry> cache;

    private static synchronized Map<String, EnvEntry> all(Context c) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        try (InputStream in = c.getAssets().open("lab_env_catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            JSONArray envs = root.optJSONArray("envs");
            if (envs != null) {
                for (int i = 0; i < envs.length(); i++) {
                    JSONObject e = envs.optJSONObject(i);
                    if (e == null) continue;
                    EnvEntry entry = new EnvEntry();
                    entry.id = e.optString("id", "");
                    entry.name = e.optString("name", entry.id);
                    entry.version = e.optString("version", "");
                    JSONArray ds = e.optJSONArray("downloads");
                    if (ds != null) {
                        for (int j = 0; j < ds.length(); j++) {
                            JSONObject d = ds.optJSONObject(j);
                            if (d == null) continue;
                            Download dl = new Download();
                            dl.arch = d.optString("arch", "").trim().toLowerCase();
                            dl.url = d.optString("url", "");
                            dl.size = d.optLong("size", 0);
                            JSONArray ms = d.optJSONArray("mirrors");
                            if (ms != null) {
                                for (int k = 0; k < ms.length(); k++) {
                                    String m = ms.optString(k, "");
                                    if (!TextUtils.isEmpty(m)) dl.mirrors.add(m);
                                }
                            }
                            if (!TextUtils.isEmpty(dl.url)) entry.downloads.add(dl);
                        }
                    }
                    if (!TextUtils.isEmpty(entry.id)) cache.put(entry.id, entry);
                }
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    public static EnvEntry entry(Context c, String id) {
        // 运行时 id 是 nodejs、仓目录 id 是 node，进门先对齐，别让 Node 查无此包
        if ("nodejs".equals(id)) id = "node";
        return all(c).get(id);
    }

    /**
     * 架构匹配选包（上游 LabEnv.arch 口径）：按本机 ABI 挑 downloads[]，
     * 挑不到返回 null（调用方报「没有你手机架构的包」）。
     */
    public static Download chooseDownload(EnvEntry entry) {
        if (entry == null) return null;
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String a = abi.toLowerCase();
            for (Download d : entry.downloads) {
                if (d.arch.isEmpty() || a.contains(d.arch) || d.arch.contains(a)) return d;
            }
        }
        return null;
    }

    /** 版本比较：v1>v2 返回正数（上游 LabEnv.compareVersions 口径） */
    public static int compareVersions(String v1, String v2) {
        String[] a = norm(v1).split("[.\\-]");
        String[] b = norm(v2).split("[.\\-]");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? num(a[i]) : 0;
            int y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static String norm(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase().replaceAll("^v", "");
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
