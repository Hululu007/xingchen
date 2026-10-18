package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * .lab 包安装链：选文件（或 .lab 文件点开拉起）→ 解析校验 lab.json →
 * 解压到 files/lab/packages/<id>/ → 登记注册表 → 首页出卡。
 * 校验失败只报人话，不抛技术栈。
 */
public final class LabPackageInstaller {

    public interface Listener {
        void onProgress(String text);

        void onOk(LabRegistry.Svc pkg);

        void onFail(String reason);
    }

    private LabPackageInstaller() {
    }

    /** 从 content/file Uri 安装 */
    public static void installFromUri(Context ctx, Uri uri, Listener listener) {
        new Thread(() -> {
            try {
                File tmp = new File(ctx.getCacheDir(), "lab-install-" + System.currentTimeMillis() + ".lab");
                copyStream(ctx.getContentResolver().openInputStream(uri), tmp, null);
                installFromFile(ctx.getApplicationContext(), tmp, listener);
                tmp.delete();
            } catch (Exception e) {
                fail(listener, "读不到这个包文件：" + human(e));
            }
        }).start();
    }

    /** 从本地 zip 文件安装（.lab） */
    public static void installFromFile(Context ctx, File zip, Listener listener) {
        new Thread(() -> {
            try {
                progress(listener, "正在读包说明…");
                String manifestJson = readManifestJson(zip);
                LabManifest manifest = LabManifest.parse(manifestJson);
                File dest = LabPaths.packageDir(ctx, manifest.id);
                progress(listener, "正在解压…");
                unzip(zip, dest);
                chmod755(dest);
                LabRegistry.Svc pkg = new LabRegistry.Svc();
                pkg.id = manifest.id;
                pkg.name = manifest.name;
                pkg.desc = manifest.desc;
                pkg.icon = manifest.icon;
                pkg.version = manifest.version;
                pkg.runtime = manifest.runtime;
                pkg.autoStartDefault = manifest.autoStart;
                pkg.installTime = System.currentTimeMillis();
                LabRegistry.get(ctx).upsertSvc(ctx, pkg);
                ok(listener, pkg);
            } catch (Exception e) {
                fail(listener, human(e));
            }
        }).start();
    }

    /** 卸载：先停服务，再删目录、注销 */
    public static void uninstall(Context ctx, String id) {
        try {
            LabRunner.stop(ctx, id);
        } catch (Exception ignored) {
        }
        deleteDir(LabPaths.packageDir(ctx, id));
        LabRegistry.get(ctx).removeSvc(ctx, id);
    }

    /** 预读包说明（安装确认页用），不安装 */
    public static LabManifest peekManifest(File zip) throws Exception {
        return LabManifest.parse(readManifestJson(zip));
    }

    private static String readManifestJson(File zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry e = zf.getEntry("lab.json");
            if (e == null) e = zf.getEntry("files/lab.json");
            if (e == null) throw new Exception("包里没有 lab.json，这不是 .lab 包");
            try (InputStream in = zf.getInputStream(e)) {
                return readAll(in);
            }
        }
    }

    private static void unzip(File zip, File dest) throws Exception {
        deleteDir(dest);
        dest.mkdirs();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                // 防 zip-slip
                File out = new File(dest, name);
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) continue;
                out.getParentFile().mkdirs();
                try (BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zin.read(buf)) > 0) bout.write(buf, 0, n);
                }
                zin.closeEntry();
            }
        }
    }

    private static void chmod755(File dir) {
        try {
            // 整目录递归 755（参照物优化过：逐文件 chmod 在 node 这类大包上会卡）
            new ProcessBuilder("chmod", "-R", "755", dir.getAbsolutePath()).start().waitFor();
        } catch (Exception ignored) {
        }
    }

    static void deleteDir(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteDir(k);
        }
        f.delete();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void copyStream(InputStream in, File dest, Listener listener) throws Exception {
        if (in == null) throw new Exception("打不开这个文件");
        try (BufferedInputStream bin = new BufferedInputStream(in);
             BufferedOutputStream bout = new BufferedOutputStream(new FileOutputStream(dest))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bin.read(buf)) > 0) bout.write(buf, 0, n);
        }
    }

    private static String human(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "这个包打不开，换一个试试";
        return m;
    }

    private static void progress(Listener l, String s) {
        if (l != null) l.onProgress(s);
    }

    private static void ok(Listener l, LabRegistry.Svc p) {
        if (l != null) l.onOk(p);
    }

    private static void fail(Listener l, String s) {
        if (l != null) l.onFail(s);
    }
}
