package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.WebView;

/**
 * 腾讯 X5（TBS）内核管理器，对齐 8.83 模式：
 * - APK 只带 TBS SDK stub（app/libs/tbssdk.aar，约463KB）；
 * - X5 内核（约30MB）由用户手动确认后下载，不强制；
 * - 未下载/用户拒绝时用系统 WebView（smtt WebView 内部自动 fallback）。
 *
 * 隐私合规：TBS SDK 会收集设备信息用于内核适配，App 隐私政策中需披露已集成腾讯 TBS SDK。
 * 初始化在 Application.onCreate 经 preInit() 轻量执行，不触发下载。
 */
public class X5Manager {

    private static final String PREFS = "x5_prefs";
    private static final String KEY_DECLINED = "x5_declined";
    private static final String KEY_PROMPTED = "x5_prompted_version";

    private static volatile boolean preInited = false;

    /** Application.onCreate 调用：轻量预初始化，不下载内核。 */
    public static void preInit(Context context) {
        if (preInited) return;
        preInited = true;
        try {
            // 允许非 WiFi 下下载（用户点下载后，移动数据也可下；8.83 同理不限制）
            QbSdk.setDownloadWithoutWifi(true);
            ensureTbsListener();
            QbSdk.preInit(context.getApplicationContext(), new QbSdk.PreInitCallback() {
                @Override
                public void onViewInitFinished(boolean isX5Core) {
                    android.util.Log.d("X5Manager", "preInit onViewInitFinished isX5Core=" + isX5Core);
                }

                @Override
                public void onCoreInitFinished() {
                    android.util.Log.d("X5Manager", "preInit onCoreInitFinished");
                }
            });
        } catch (Throwable e) {
            android.util.Log.w("X5Manager", "preInit failed: " + e.getMessage());
        }
    }

    /** 下载状态：未下载 / 下载中 / 已下载 */
    public static final int STATUS_NOT_DOWNLOADED = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_DOWNLOADED = 2;

    /** 下载进度回调（设置页下载管理用）。 */
    public interface DownloadListener {
        void onProgress(int progress); // 0-100
        void onFinish(boolean success);
    }

    private static volatile DownloadListener downloadListener;
    private static volatile int lastProgress = -1; // -1=未在下载
    private static volatile boolean tbsListenerSet = false;

    public static void setDownloadListener(DownloadListener l) {
        downloadListener = l;
    }

    /** 注册 TBS 下载监听（preInit 时调用一次）。 */
    private static void ensureTbsListener() {
        if (tbsListenerSet) return;
        tbsListenerSet = true;
        try {
            QbSdk.setTbsListener(new com.tencent.smtt.sdk.TbsListener() {
                @Override
                public void onDownloadFinish(int i) {
                    lastProgress = 100;
                    postFinish(true);
                }

                @Override
                public void onInstallFinish(int i) {
                    lastProgress = 100;
                    try { QbSdk.unForceSysWebView(); } catch (Throwable ignored) {}
                    postFinish(true);
                }

                @Override
                public void onDownloadProgress(int progress) {
                    lastProgress = progress;
                    final DownloadListener l = downloadListener;
                    if (l != null) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try { l.onProgress(progress); } catch (Throwable ignored) {}
                        });
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void postFinish(boolean success) {
        final DownloadListener l = downloadListener;
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try { l.onFinish(success); } catch (Throwable ignored) {}
            });
        }
    }

    /** 综合状态：已下载 > 下载中 > 未下载 */
    public static int getStatus(Context context) {
        try {
            if (isX5Downloaded(context)) return STATUS_DOWNLOADED;
        } catch (Throwable ignored) {}
        if (lastProgress >= 0 && lastProgress < 100) return STATUS_DOWNLOADING;
        return STATUS_NOT_DOWNLOADED;
    }

    /** 当前下载进度（-1=未在下载）。 */
    public static int getLastProgress() {
        return lastProgress;
    }

    /** 已下载内核体积文本，如 "32MB"；未下载返回 ""。 */
    public static String getCoreSizeText(Context context) {
        try {
            String path = QbSdk.getTbsResourcesPath(context.getApplicationContext());
            if (path == null || path.isEmpty()) return "";
            java.io.File dir = new java.io.File(path);
            if (!dir.exists()) return "";
            long size = dirSize(dir);
            if (size <= 0) return "";
            return (size / 1024 / 1024) + "MB";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static long dirSize(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        java.io.File[] files = f.listFiles();
        if (files != null) for (java.io.File child : files) total += dirSize(child);
        return total;
    }

    /**
     * 卸载 X5 内核：QbSdk.reset() 清状态 + 删残留文件 + 切回系统 WebView。
     * 释放约 30MB 空间。卸载后允许再次提示下载。
     */
    public static void uninstallCore(Context context) {
        Context appCtx = context.getApplicationContext();
        String corePath = null;
        try { corePath = QbSdk.getTbsResourcesPath(appCtx); } catch (Throwable ignored) {}
        try { QbSdk.reset(appCtx); } catch (Throwable ignored) {}
        try {
            if (corePath != null && !corePath.isEmpty()) deleteRecursively(new java.io.File(corePath));
        } catch (Throwable ignored) {}
        try { QbSdk.forceSysWebView(); } catch (Throwable ignored) {}
        lastProgress = -1;
        setDeclined(appCtx, false);
        android.util.Log.d("X5Manager", "X5 core uninstalled");
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] files = f.listFiles();
            if (files != null) for (java.io.File child : files) deleteRecursively(child);
        }
        try { f.delete(); } catch (Throwable ignored) {}
    }

    /** X5 内核是否已加载可用。 */
    public static boolean isX5Ready() {
        try {
            return QbSdk.isTbsCoreInited();
        } catch (Throwable e) {
            return false;
        }
    }

    /** X5 内核是否已下载到本地（getTbsVersion > 0）。 */
    public static boolean isX5Downloaded(Context context) {
        try {
            if (QbSdk.getTbsVersion(context) <= 0) return false;
            String path = QbSdk.getTbsResourcesPath(context.getApplicationContext());
            return path != null && !path.isEmpty() && new java.io.File(path).exists();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 用户是否已拒绝下载。 */
    public static boolean hasDeclined(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getBoolean(KEY_DECLINED, false);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void setDeclined(Context context, boolean declined) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_DECLINED, declined).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 按 8.83 模式弹窗提示下载 X5 内核。
     * 未下载且用户未拒绝时弹一次；用户选"下载"则触发内核下载，选"不用"则强制系统 WebView 并记住。
     */
    public static void promptDownloadIfNeeded(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final Context appCtx = activity.getApplicationContext();
        try {
            if (isX5Downloaded(appCtx) || isX5Ready()) return;
            if (hasDeclined(appCtx)) {
                // 已拒绝：确保用系统 WebView
                QbSdk.forceSysWebView();
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (activity.isFinishing()) return;
                try {
                    new AlertDialog.Builder(activity)
                            .setTitle("下载 X5 内核")
                            .setMessage("检测到 X5 浏览内核未下载。下载后（约30MB）小程序里的 H5 页面、小游戏加载更快更稳定。是否现在下载？")
                            .setPositiveButton("下载", (d, w) -> {
                                setDeclined(appCtx, false);
                                startDownload(appCtx, null);
                                d.dismiss();
                            })
                            .setNegativeButton("不用", (d, w) -> {
                                setDeclined(appCtx, true);
                                try { QbSdk.forceSysWebView(); } catch (Throwable ignored) {}
                                d.dismiss();
                            })
                            .setCancelable(true)
                            .show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 开始下载/加载 X5 内核（用户确认后调用）。 */
    public static void startDownload(Context context, final Runnable onDone) {
        try {
            QbSdk.initX5Environment(context.getApplicationContext(), new QbSdk.PreInitCallback() {
                @Override
                public void onViewInitFinished(boolean isX5Core) {
                    android.util.Log.d("X5Manager", "initX5 onViewInitFinished isX5Core=" + isX5Core);
                    if (onDone != null) {
                        new Handler(Looper.getMainLooper()).post(onDone);
                    }
                }

                @Override
                public void onCoreInitFinished() {
                    android.util.Log.d("X5Manager", "initX5 onCoreInitFinished");
                }
            });
        } catch (Throwable e) {
            android.util.Log.w("X5Manager", "initX5Environment failed: " + e.getMessage());
        }
    }

    /** 创建 WebView：X5 可用时用 X5 内核，否则 smtt 内部自动 fallback 到系统 WebView。 */
    public static WebView createWebView(Context context) {
        return new WebView(context);
    }

    /** 强制使用系统 WebView（对应 8.83 X5Extra.isDisableX5() 场景）。 */
    public static void forceSysWebView() {
        try { QbSdk.forceSysWebView(); } catch (Throwable ignored) {}
    }

    /** 取消强制系统 WebView。 */
    public static void unForceSysWebView() {
        try { QbSdk.unForceSysWebView(); } catch (Throwable ignored) {}
    }
}
