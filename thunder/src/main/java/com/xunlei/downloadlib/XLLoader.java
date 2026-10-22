package com.xunlei.downloadlib;

import com.github.catvod.utils.RuntimeModule;
import com.xunlei.downloadlib.parameter.BtIndexSet;
import com.xunlei.downloadlib.parameter.BtSubTaskDetail;
import com.xunlei.downloadlib.parameter.GetDownloadLibVersion;
import com.xunlei.downloadlib.parameter.GetFileName;
import com.xunlei.downloadlib.parameter.GetTaskId;
import com.xunlei.downloadlib.parameter.ThunderUrlInfo;
import com.xunlei.downloadlib.parameter.TorrentInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;
import com.xunlei.downloadlib.parameter.XLTaskLocalUrl;

class XLLoader {

    // 双形态过渡桥（只动构造，不动 native 声明）：
    // 外置包里被 PluginLoader 载入时，走上游原文 RuntimeModule.THUNDER.load(path -> System.load(path))，
    // 从插件 APK 内 "…!/lib/<abi>/*.so" 直载——loadLibrary 在这种类加载器下找不到插件的库；
    // 宿主内置的过渡编包里没有外置包，require() 抛 MissingException，立刻回落
    // System.loadLibrary（宿主 APK 自带的库）。外置接通且直连删除后，构造里
    // 只剩 RuntimeModule.load 一行，回落段删掉，不留双形态当永久口径。
    public XLLoader() {
        try {
            RuntimeModule.THUNDER.load(path -> System.load(path));
        } catch (RuntimeException | LinkageError | ExceptionInInitializerError e) {
            System.loadLibrary("xl_stat");
            System.loadLibrary("xl_thunder_sdk");
        }
    }

    public native int createBtMagnetTask(String str, String str2, String str3, GetTaskId getTaskId);

    public native int createBtTask(String str, String str2, int i, int i2, int i3, GetTaskId getTaskId);

    public native int createEmuleTask(String str, String str2, String str3, int i, int i2, GetTaskId getTaskId);

    public native int createP2spTask(String str, String str2, String str3, String str4, String str5, String str6, String str7, int i, int i2, GetTaskId getTaskId);

    public native int deselectBtSubTask(long j, BtIndexSet btIndexSet);

    public native int getBtSubTaskInfo(long j, int i, BtSubTaskDetail btSubTaskDetail);

    public native int getDownloadLibVersion(GetDownloadLibVersion getDownloadLibVersion);

    public native int getFileNameFromUrl(String str, GetFileName getFileName);

    public native int getLocalUrl(String str, XLTaskLocalUrl xLTaskLocalUrl);

    public native int getTaskInfo(long j, int i, XLTaskInfo xLTaskInfo);

    public native int getTorrentInfo(String str, TorrentInfo torrentInfo);

    public native int init(String str, String str2, String str3, String str4, String str5, String str6, String str7, String str8, int i, int i2, int i3);

    public native int parserThunderUrl(String str, ThunderUrlInfo thunderUrlInfo);

    public native int releaseTask(long j);

    public native int setDownloadTaskOrigin(long j, String str);

    public native int setLocalProperty(String str, String str2);

    public native int setMiUiVersion(String str);

    public native int setOriginUserAgent(long j, String str);

    public native int setStatReportSwitch(boolean z);

    public native int setSpeedLimit(long j, long j2);

    public native int setTaskGsState(long j, int i, int i2);

    public native int startTask(long j);

    public native int stopTask(long j);

    public native int unInit();
}
