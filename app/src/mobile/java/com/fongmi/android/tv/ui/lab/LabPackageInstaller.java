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
        installFromUri(ctx, uri, null, listener);
    }

    /**
     * 从 content/file Uri 安装：defaultRuntime（环境页「＋」进来时传当前环境）只在
     * 包自己没声明运行时（runtime 为 none）时用，声明了的听声明。
     */
    public static void installFromUri(Context ctx, Uri uri, String defaultRuntime, Listener listener) {
        new Thread(() -> {
            try {
                File tmp = new File(ctx.getCacheDir(), "lab-install-" + System.currentTimeMillis() + ".lab");
                copyStream(ctx.getContentResolver().openInputStream(uri), tmp, null);
                // installFromFile 是另起线程异步跑的，临时文件只能在终态回调里删，
                // 当场删会跟安装线程抢跑，安装那边打开就是 ENOENT。
                Listener wrapped = new Listener() {
                    @Override
                    public void onProgress(String text) {
                        listener.onProgress(text);
                    }

                    @Override
                    public void onOk(LabRegistry.Svc pkg) {
                        tmp.delete();
                        listener.onOk(pkg);
                    }

                    @Override
                    public void onFail(String reason) {
                        tmp.delete();
                        listener.onFail(reason);
                    }
                };
                installFromFile(ctx.getApplicationContext(), tmp, defaultRuntime, wrapped);
            } catch (Exception e) {
                fail(listener, "读不到这个包文件：" + human(e));
            }
        }).start();
    }

    /** 从本地 zip 文件安装（.lab） */
    public static void installFromFile(Context ctx, File zip, Listener listener) {
        installFromFile(ctx, zip, null, listener);
    }

    /** 同上，带默认运行时（包没声明时才用）；声明的环境没装会自动先装环境 */
    public static void installFromFile(Context ctx, File zip, String defaultRuntime, Listener listener) {
        new Thread(() -> {
            try {
                progress(listener, "正在读包说明…");
                String manifestJson = readManifestJson(zip);
                LabManifest manifest = LabManifest.parse(manifestJson);
                ensureRuntime(ctx.getApplicationContext(), manifest.runtime, defaultRuntime,
                        new RuntimeEnsure() {
                            @Override
                            public void onEnvProgress(String runtime, String text) {
                                progress(listener, "正在装「"
                                        + LabRuntimeManager.label(runtime) + "」：" + text);
                            }

                            @Override
                            public void onReady(String runtime, String hint) {
                                try {
                                    if (hint != null) progress(listener, hint);
                                    manifest.runtime = runtime;
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
                            }

                            @Override
                            public void onFail(String reason) {
                                fail(listener, reason);
                            }
                        });
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

    /** 环境自检回调：导入/添加服务时读包声明的运行时 */
    public interface RuntimeEnsure {
        /** 需要的环境没装、正在自动走在线安装：text 是安装进度人话 */
        void onEnvProgress(String runtime, String text);

        /** 环境就绪：runtime 为最终运行时；hint 为兜底提示（可空，调 progress 透给用户） */
        void onReady(String runtime, String hint);

        void onFail(String reason);
    }

    /**
     * 读包里声明的运行时：该环境没装就自动走在线安装（调用方弹安装中弹窗），
     * 装好服务直接就位能跑。声明缺失/认不出来才落回 defaultRuntime（环境页当前环境），
     * 并给一句人话提示；连兜底都没有就按 "none" 走老路。
     */
    public static void ensureRuntime(Context ctx, String runtime, String defaultRuntime, RuntimeEnsure cb) {
        final Context app = ctx.getApplicationContext();
        String rt = runtime;
        String hint = null;
        if (rt == null || rt.isEmpty() || "none".equals(rt) || !LabRuntimeManager.RUNTIMES.contains(rt)) {
            if (defaultRuntime != null && LabRuntimeManager.RUNTIMES.contains(defaultRuntime)) {
                hint = "包里没写要什么环境，先放在「" + LabRuntimeManager.label(defaultRuntime) + "」里";
                rt = defaultRuntime;
            } else {
                cb.onReady("none", null);
                return;
            }
        }
        final String need = rt;
        final String readyHint = hint;
        if (LabRuntimeManager.isInstalled(app, need)) {
            cb.onReady(need, readyHint);
            return;
        }
        LabRuntimeManager.installOnline(app, need, new LabRuntimeManager.Listener() {
            @Override
            public void onProgress(String text) {
                cb.onEnvProgress(need, text);
            }

            @Override
            public void onOk() {
                cb.onReady(need, readyHint);
            }

            @Override
            public void onFail(String reason) {
                cb.onFail("「" + LabRuntimeManager.label(need) + "」没装上：" + reason);
            }
        });
    }

    /** 兼容导入（默影视命令 / Termux 清单 / 裸脚本 / lab.json 粘贴）：归一后的条目直接落盘 */
    public interface CompatListener {
        void onProgress(String text);

        void onDone(int count);

        void onFail(String reason);
    }

    public static void installCompat(Context ctx, java.util.List<LabImportCompat.ImportItem> items, CompatListener listener) {
        installCompat(ctx, items, null, listener);
    }

    /** 同上，带默认运行时（条目没声明时才用）；每条先保证环境就绪再落盘 */
    public static void installCompat(Context ctx, java.util.List<LabImportCompat.ImportItem> items, String defaultRuntime, CompatListener listener) {
        new Thread(() -> installCompatNext(ctx.getApplicationContext(), items, 0, defaultRuntime, listener, 0)).start();
    }

    /** 逐条链式安装：前一条的环境没装就先自动装好，再装下一条 */
    private static void installCompatNext(Context ctx, java.util.List<LabImportCompat.ImportItem> items,
                                          int idx, String defaultRuntime, CompatListener listener, int okCount) {
        if (idx >= items.size()) {
            if (listener != null) listener.onDone(okCount);
            return;
        }
        LabImportCompat.ImportItem it = items.get(idx);
        progress2(listener, "正在装：" + it.name + "…");
        final LabManifest m = LabImportCompat.toManifest(it);
        ensureRuntime(ctx, m.runtime, defaultRuntime, new RuntimeEnsure() {
            @Override
            public void onEnvProgress(String runtime, String text) {
                progress2(listener, "正在装「" + LabRuntimeManager.label(runtime) + "」：" + text);
            }

            @Override
            public void onReady(String runtime, String hint) {
                try {
                    if (hint != null) progress2(listener, hint);
                    m.runtime = runtime;
                    File dest = LabPaths.packageDir(ctx, m.id);
                    deleteDir(dest);
                    dest.mkdirs();
                    writeJson(new File(dest, "lab.json"), m);
                    if (it.assetBytes != null && it.assetBytes.length > 0
                            && it.assetName != null && !it.assetName.isEmpty()) {
                        File f = new File(dest, it.assetName);
                        if (f.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) {
                            try (FileOutputStream out = new FileOutputStream(f)) {
                                out.write(it.assetBytes);
                            }
                        }
                    }
                    chmod755(dest);
                    LabRegistry.Svc pkg = new LabRegistry.Svc();
                    pkg.id = m.id;
                    pkg.name = m.name;
                    pkg.desc = m.desc;
                    pkg.icon = m.icon;
                    pkg.version = m.version;
                    pkg.runtime = m.runtime;
                    pkg.autoStartDefault = m.autoStart;
                    pkg.installTime = System.currentTimeMillis();
                    pkg.needsReview = it.needsReview;
                    LabRegistry.get(ctx).upsertSvc(ctx, pkg);
                    installCompatNext(ctx, items, idx + 1, defaultRuntime, listener, okCount + 1);
                } catch (Exception e) {
                    if (listener != null) listener.onFail(human(e));
                }
            }

            @Override
            public void onFail(String reason) {
                if (listener != null) listener.onFail(reason);
            }
        });
    }

    private static void writeJson(File f, LabManifest m) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f);
             java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            new com.google.gson.Gson().toJson(m, w);
        }
    }

    private static void progress2(CompatListener l, String s) {
        if (l != null) l.onProgress(s);
    }

    /** 预读包说明（安装确认页用），不安装 */
    public static LabManifest peekManifest(File zip) throws Exception {
        return LabManifest.parse(readManifestJson(zip));
    }

    private static String readManifestJson(File zip) throws Exception {
        assertZip(zip);
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry e = zf.getEntry("lab.json");
            if (e == null) e = zf.getEntry("files/lab.json");
            if (e == null) throw new Exception("包里没有 lab.json，这不是 .lab 包");
            try (InputStream in = zf.getInputStream(e)) {
                return readAll(in);
            }
        }
    }

    /** 魔数预检：7z/rar 等非 zip 包给人话提示，不抛技术栈 */
    private static void assertZip(File zip) throws Exception {
        try (FileInputStream in = new FileInputStream(zip)) {
            byte[] magic = new byte[6];
            int n = in.read(magic);
            if (n >= 6 && magic[0] == 0x37 && magic[1] == 0x7A
                    && magic[2] == (byte) 0xBC && magic[3] == (byte) 0xAF) {
                throw new Exception("这是 7z 打的包，星辰只认 zip 打的 .lab 包");
            }
            if (n >= 4 && magic[0] == 'R' && magic[1] == 'a' && magic[2] == 'r' && magic[3] == '!') {
                throw new Exception("这是 rar 包，星辰只认 zip 打的 .lab 包");
            }
            if (n >= 2 && !(magic[0] == 'P' && magic[1] == 'K')) {
                throw new Exception("这不是 zip 包，装不了");
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

    /** 导出回调：fileName 为下载目录里的文件名 */
    public interface ExportListener {
        void onOk(String fileName);

        void onFail(String reason);
    }

    /**
     * 导出包（导入的对）：把包目录打成 .lab 包，写进下载目录/星辰实验室。
     * Q+ 走 MediaStore，Q 以下走 Download 文件（需 WRITE_EXTERNAL_STORAGE，maxSdkVersion=28）。
     */
    public static void export(Context ctx, String pkgId, ExportListener listener) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                LabRegistry.Svc pkg = LabRegistry.get(c).svc(pkgId);
                if (pkg == null) throw new Exception("这个包已经不在了");
                File src = LabPaths.packageDir(c, pkgId);
                if (!src.exists()) throw new Exception("包目录找不到了");
                String safeName = (pkg.name == null ? pkgId : pkg.name).replaceAll("[\\\\/:*?\"<>|]", "_");
                String fileName = safeName + ".lab";
                File tmp = new File(c.getCacheDir(), "lab-export-" + System.currentTimeMillis() + ".lab");
                zipDir(src, tmp);
                boolean ok = writeToDownload(c, fileName, tmp);
                tmp.delete();
                if (!ok) throw new Exception("写不进下载目录");
                if (listener != null) listener.onOk(fileName);
            } catch (Exception e) {
                if (listener != null) listener.onFail("导出失败：" + human(e));
            }
        }).start();
    }

    private static void zipDir(File src, File dest) throws Exception {
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(dest);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                     new BufferedOutputStream(fos))) {
            zipRec(src, src, zos);
        }
    }

    private static void zipRec(File root, File f, java.util.zip.ZipOutputStream zos) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) zipRec(root, k, zos);
            return;
        }
        String rel = root.toURI().relativize(f.toURI()).getPath();
        zos.putNextEntry(new ZipEntry(rel));
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
    }

    private static boolean writeToDownload(Context c, String fileName, File src) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/星辰实验室");
                android.net.Uri uri = c.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (java.io.InputStream in = new FileInputStream(src);
                     java.io.OutputStream out = c.getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                return true;
            }
            File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "星辰实验室");
            if (!dir.exists() && !dir.mkdirs()) return false;
            try (java.io.InputStream in = new FileInputStream(src);
                 java.io.OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
