# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
-keep class com.fongmi.android.tv.bean.** { *; }
# 海阔详情短时缓存落盘结构（2026-10-17 崩溃修法保险丝：R8 曾把 Entry 的 List<HkItem>
# 字段泛型签名剥掉、读回全变 LinkedTreeMap 直接崩；现在 Entry 只存字符串无泛型可剥，
# 这里再加一道保险，防 R8 对缓存结构动手脚）
-keep class com.fongmi.android.tv.api.hk.HkDetailCache$Entry { *; }

# MPV JNI bridge
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$* { *; }

# libplayer.so resolves this class and these methods by their literal JNI names.
-keep class com.fongmi.android.tv.player.iso.IsoSessionManager {
    public static long length(long);
    public static int readAt(long, long, java.nio.ByteBuffer, int);
    public static void close(long);
    public static void prepareTrackMetadata(long, int);
}

# MPV owns one process-wide native context. Keep its lifecycle code intact so
# release inlining does not amplify timing-sensitive create/destroy transitions.
-keep,allowobfuscation class androidx.media3.mpvplayer.MpvPlayer { *; }
-keep,allowobfuscation class androidx.media3.mpvplayer.MpvPlayer$* { *; }
-keep,allowobfuscation class com.fongmi.android.tv.player.engine.MpvPlayerEngine { *; }

# Gson
-keep class com.google.gson.** { *; }
-keep class com.fongmi.android.tv.remote.** { *; }
-keep class com.fongmi.android.tv.gitcloud.** { *; }

# SimpleXML
-keep interface org.simpleframework.xml.core.Label { public *; }
-keep class * implements org.simpleframework.xml.core.Label { public *; }
-keep interface org.simpleframework.xml.core.Parameter { public *; }
-keep class * implements org.simpleframework.xml.core.Parameter { public *; }
-keep interface org.simpleframework.xml.core.Extractor { public *; }
-keep class * implements org.simpleframework.xml.core.Extractor { public *; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Path <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Root <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Text <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Element <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.Attribute <fields>; }
-keepclassmembers,allowobfuscation class * { @org.simpleframework.xml.ElementList <fields>; }

# OkHttp
-dontwarn okhttp3.**
-keep class okio.** { *; }
-keep class okhttp3.** { *; }

# Kotlin
-keeppackagenames kotlin.**
-keep class kotlin.** { *; }

# JGit
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn org.eclipse.jgit.**
-keep class org.eclipse.jgit.** { *; }
-keeppackagenames org.slf4j.**
-keep class org.slf4j.** { *; }

# CatVod
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class com.github.catvod.net.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# Jianpian
-keep class com.p2p.** { *; }

# JUPnP
-dontwarn org.jupnp.**
-keep class org.jupnp.** { *; }
-keep class javax.xml.** { *; }

# Nano
-keep class fi.iki.elonen.** { *; }

# NewPipeExtractor
-keep class javax.script.** { *; }
-keep class jdk.dynalink.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.protos.** { *; }
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Sardine
-keep class com.thegrizzlylabs.sardineandroid.** { *; }

# TVBus
-keep class com.tvbus.engine.** { *; }

# XunLei
-keep class com.xunlei.downloadlib.** { *; }

# Zxing
-keep class com.google.zxing.** { *; }

# 海阔引擎 JS 桥：HkJsRuntime 靠反射扫描 @JSAnnotation 方法生成同名 JS 函数
# （getAllFunctions），方法名即规则调用的 API 名；R8 改名后真机上 getUaObject
# 等全部 ReferenceError（本地未混淆测不出）。整类保名，含内部注解类型。
-keep class com.fongmi.android.tv.api.hk.HkJsRuntime { *; }
-keep class com.fongmi.android.tv.api.hk.HkJsRuntime$* { *; }

# 图片解密桥：crypto-java.js 按名调 FileUtil.toBytes（即本类），R8 改名后真机
# TypeError 找不到函数、封面空流（1.3.7 真机日志实锤）。整类保名。
-keep class com.fongmi.android.tv.api.hk.HkCryptoFileUtil { *; }

# 海阔模型：Gson 按字段名序列化/反序列化（MY_RULE、hiker://page 规则体），字段名不能改
-keep class com.fongmi.android.tv.api.hk.HkRule { *; }
-keep class com.fongmi.android.tv.api.hk.HkPageRule { *; }
-keep class com.fongmi.android.tv.api.hk.HkPageParser$HkPage { *; }
-keep class com.fongmi.android.tv.api.hk.HkViewCollection { *; }
-keep class com.fongmi.android.tv.api.hk.HkSubscribeRecord { *; }
-keep class com.fongmi.android.tv.api.hk.HkPlayData { *; }
