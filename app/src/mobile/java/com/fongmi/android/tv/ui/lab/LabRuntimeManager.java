package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 运行环境管理：python / nodejs / php / mihomo / alist / frp / aria2 / ubuntu / java / bun / ffmpeg。
 * 照参照物：装到 files/lab/runtimes/<name>/，二进制归位 bin/，整目录 chmod 755。
 * 本轮只做「从本地 zip 文件导入」；下载地址留一处集中常量（公开仓未建，先留空）。
 * ubuntu 例外：rootfs 不是 bin/ 布局，proot 跑法基座还没接（见报告），卡先上、装与跑待后续。
 */
public final class LabRuntimeManager {

    public static final List<String> RUNTIMES = Arrays.asList(
            "python", "nodejs", "php", "mihomo", "alist", "frp", "aria2",
            "ubuntu", "java", "bun", "ffmpeg");

    /** 环境包下载地址（公开仓未建，先留空；建好后填这里，全 App 只这一处） */
    public static final Map<String, String> DOWNLOAD_URLS = new HashMap<>();

    public interface Listener {
        void onProgress(String text);

        void onOk();

        void onFail(String reason);
    }

    private LabRuntimeManager() {
    }

    public static String label(String runtime) {
        if ("python".equals(runtime)) return "Python 环境";
        if ("nodejs".equals(runtime)) return "Node 环境";
        if ("php".equals(runtime)) return "PHP 环境";
        if ("mihomo".equals(runtime)) return "mihomo";
        if ("alist".equals(runtime)) return "alist";
        if ("frp".equals(runtime)) return "frp";
        if ("aria2".equals(runtime)) return "aria2";
        if ("ubuntu".equals(runtime)) return "Ubuntu 环境";
        if ("java".equals(runtime)) return "Java 环境";
        if ("bun".equals(runtime)) return "Bun 环境";
        if ("ffmpeg".equals(runtime)) return "ffmpeg";
        return runtime;
    }

    public static String desc(String runtime) {
        if ("python".equals(runtime)) return "跑脚本的底子，很多服务靠它";
        if ("nodejs".equals(runtime)) return "JS 服务的底子";
        if ("php".equals(runtime)) return "PHP 程序的底子";
        return "";
    }

    public static String iconText(String runtime) {
        if ("python".equals(runtime)) return "Py";
        if ("nodejs".equals(runtime)) return "Js";
        if ("php".equals(runtime)) return "PH";
        return "?";
    }

    public static boolean isInstalled(Context c, String runtime) {
        File bin = new File(LabPaths.runtimeDir(c, runtime), "bin");
        return bin.isDirectory() && bin.list() != null && bin.list().length > 0;
    }

    /** 从本地 zip 导入环境包 */
    public static void importFromUri(Context ctx, String runtime, Uri uri, Listener listener) {
        new Thread(() -> {
            try {
                progress(listener, "正在准备环境…");
                File tmp = new File(ctx.getCacheDir(), "lab-rt-" + runtime + "-" + System.currentTimeMillis() + ".zip");
                copyUri(ctx, uri, tmp);
                File dest = LabPaths.runtimeDir(ctx, runtime);
                LabPackageInstaller.deleteDir(dest);
                dest.mkdirs();
                unzip(tmp, dest, listener);
                chmod755(dest);
                tmp.delete();
                if (!isInstalled(ctx, runtime)) throw new Exception("环境包里没有 bin，换一个压缩包试试");
                LabRegistry.Runtime r = new LabRegistry.Runtime();
                r.name = runtime;
                r.version = "";
                r.installTime = System.currentTimeMillis();
                LabRegistry.get(ctx).upsertRuntime(ctx, r);
                ok(listener);
            } catch (Exception e) {
                String m = e.getMessage();
                fail(listener, m == null || m.isEmpty() ? "环境装不上，换一个压缩包试试" : m);
            }
        }).start();
    }

    /** 环境 bin 目录：给服务进程拼 PATH 用 */
    public static File binDir(Context c, String runtime) {
        return new File(LabPaths.runtimeDir(c, runtime), "bin");
    }

    private static void unzip(File zip, File dest, Listener listener) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            long total = 0;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File out = new File(dest, entry.getName());
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) continue;
                out.getParentFile().mkdirs();
                try (BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        bout.write(buf, 0, n);
                        total += n;
                    }
                }
                zin.closeEntry();
                if (total % (8 * 1024 * 1024) < 8192) progress(listener, "正在准备环境…");
            }
        }
    }

    private static void chmod755(File dir) {
        try {
            new ProcessBuilder("chmod", "-R", "755", dir.getAbsolutePath()).start().waitFor();
        } catch (Exception ignored) {
        }
    }

    private static void copyUri(Context ctx, Uri uri, File dest) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(uri));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void progress(Listener l, String s) {
        if (l != null) l.onProgress(s);
    }

    private static void ok(Listener l) {
        if (l != null) l.onOk();
    }

    private static void fail(Listener l, String s) {
        if (l != null) l.onFail(s);
    }
}
