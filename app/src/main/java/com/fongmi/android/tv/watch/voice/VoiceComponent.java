package com.fongmi.android.tv.watch.voice;

import android.os.Handler;
import android.os.Looper;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dalvik.system.DexClassLoader;

/**
 * 实时语音组件（用时下载）：WebRTC 太大不进安装包，开合唱时才下。
 * 包里是 classes.dex + arm64 的 libjingle .so；下载后先验哈希再解包，
 * dex 置只读（安卓 14+ 动态加载要求），最后用 DexClassLoader 加载。
 */
public final class VoiceComponent {

    public interface Callback {
        void onReady(VoiceEngine engine, String nativeLibPath);

        void onStatus(String text);

        void onError(String message);
    }

    // 下载源按顺序试：jsDelivr（仓库直出，已验通）→ Pages 托管（同仓，部署生效后可用）
    private static final String[] ZIP_URLS = {
            "https://cdn.jsdelivr.net/gh/XingChenApp/xingchen-asr/voice/voice-arm64-v1.zip",
            "https://xingchen-dl.pages.dev/voice/voice-arm64-v1.zip"
    };
    private static final String EXPECTED_SHA256 = "1650246e7875a71f13183236c95f518dd7f66ac09ab8c0c6a5ff7427beccd321";
    private static final String IMPL_CLASS = "com.fongmi.android.tv.watch.voice.VoiceEngineImpl";

    private static volatile VoiceEngine cachedEngine;
    private static volatile String cachedSoPath;

    private VoiceComponent() {
    }

    private static File dir() {
        File d = new File(App.get().getFilesDir(), "watch-voice");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static boolean installed(File d) {
        return new File(d, "voice.dex").exists()
                && new File(d, "libjingle_peerconnection_so.so").exists()
                && new File(d, "installed-v1.ok").exists();
    }

    public static void ensureReady(Callback cb) {
        if (cachedEngine != null) {
            cb.onReady(cachedEngine, cachedSoPath);
            return;
        }
        new Thread(() -> {
            Handler main = new Handler(Looper.getMainLooper());
            try {
                File d = dir();
                if (!installed(d)) {
                    main.post(() -> cb.onStatus("正在下载语音组件（约 5 兆）…"));
                    downloadAndInstall(d);
                }
                File dex = new File(d, "voice.dex");
                File so = new File(d, "libjingle_peerconnection_so.so");
                File opt = new File(d, "opt");
                if (!opt.exists()) opt.mkdirs();
                DexClassLoader loader = new DexClassLoader(
                        dex.getAbsolutePath(), opt.getAbsolutePath(), null,
                        VoiceComponent.class.getClassLoader());
                Class<?> cls = loader.loadClass(IMPL_CLASS);
                VoiceEngine engine = (VoiceEngine) cls.getDeclaredConstructor().newInstance();
                cachedEngine = engine;
                cachedSoPath = so.getAbsolutePath();
                main.post(() -> cb.onReady(engine, cachedSoPath));
            } catch (Throwable t) {
                SpiderDebug.log("voice", "component failed: %s", String.valueOf(t));
                main.post(() -> cb.onError("语音组件没准备好：" + t.getMessage()));
            }
        }, "voice-component").start();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static void downloadTo(String url, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        if (dest.length() == 0) throw new IllegalStateException("空文件");
    }

    private static void downloadAndInstall(File d) throws Exception {
        File tmpZip = new File(d, "voice.zip.tmp");
        Exception last = null;
        for (String url : ZIP_URLS) {
            try {
                downloadTo(url, tmpZip);
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                SpiderDebug.log("voice", "component source failed host=%s err=%s",
                        hostOf(url), String.valueOf(e));
            }
        }
        if (last != null) throw last;
        String sha = sha256(tmpZip);
        if (!EXPECTED_SHA256.equalsIgnoreCase(sha)) {
            tmpZip.delete();
            throw new IllegalStateException("组件校验没过");
        }
        File dexTmp = new File(d, "voice.dex.tmp");
        File soTmp = new File(d, "lib.tmp");
        boolean gotDex = false, gotSo = false;
        try (ZipInputStream zin = new ZipInputStream(new java.io.FileInputStream(tmpZip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                File target = null;
                if (e.getName().equals("classes.dex")) target = dexTmp;
                else if (e.getName().endsWith("libjingle_peerconnection_so.so")) target = soTmp;
                if (target == null) continue;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (target == dexTmp) gotDex = true;
                else gotSo = true;
            }
        }
        tmpZip.delete();
        if (!gotDex || !gotSo) throw new IllegalStateException("组件包不完整");
        File dex = new File(d, "voice.dex");
        File so = new File(d, "libjingle_peerconnection_so.so");
        if (dex.exists()) {
            dex.setWritable(true);
            dex.delete();
        }
        if (so.exists()) so.delete();
        if (!dexTmp.renameTo(dex) || !soTmp.renameTo(so)) throw new IllegalStateException("组件落盘失败");
        dex.setReadOnly();
        try (FileOutputStream marker = new FileOutputStream(new File(d, "installed-v1.ok"))) {
            marker.write(1);
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
