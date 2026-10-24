# Gson TypeToken 泛型签名保留（1.3.52／1.4.8 LinkedTreeMap 崩同源）：匿名 TypeToken 子类的
# 泛型参数只存在于 Signature 属性里，R8 剥掉它则 TypeToken 退化为裸类型读回；
# 显式 TypeToken 定型（榜单／歌单）只有签保留才生效，故两混淆文件都要补齐。
-keepattributes Signature
# TV
-keep class androidx.leanback.widget.** { *; }
-keep class com.fongmi.quickjs.method.** { *; }
# PY 蜘蛛以完整类名反射调 WebActivity 的静态面（open/openWeb/getUserAgent），类名方法名不许改
-keep class com.fongmi.android.tv.ui.activity.WebActivity { *; }
-keep class com.fongmi.android.tv.bean.** { *; }
# 海阔详情短时缓存落盘结构（2026-10-17 崩溃修法保险丝：R8 曾把 Entry 的 List<HkItem>
# 字段泛型签名剥掉、读回全变 LinkedTreeMap 直接崩；现在 Entry 只存字符串无泛型可剥，
# 这里再加一道保险，防 R8 对缓存结构动手脚）
-keep class com.fongmi.android.tv.api.hk.HkDetailCache$Entry { *; }
# 音乐链落盘结构（2026-10-24 批 7 R8 审计 R1–R4）：这四件不在 bean 包、无 keep 时
# R8 改字段名/裁未读字段，而读写两侧一侧反射字段名、一侧字面键，不对称即静默丢数据
# （AudioPlaylistStore 已在 1.4.26 成品 dex 实证打坏：字段改名 a/b/c、同版往返即丢）。
-keep class com.fongmi.android.tv.ui.activity.MusicActivity$HomeChartSnapshot { *; }
-keep class com.fongmi.android.tv.player.lyrics.AudioPlaylistStore$* { *; }
-keep class com.fongmi.android.tv.music.ParametricEq$Band { *; }
-keep class com.fongmi.android.tv.player.lyrics.LyricsResult { *; }

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
-keep class com.fongmi.android.tv.legado.** { *; }

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
# 宿主面全包保名（2026-10-23 jar 全灭实锤补）：jar 里的爬虫（含加密载荷）在
# 编译期按 com.github.catvod.** 标准宿主面链接，成品里任一类被 R8 改名或裁掉，
# 载荷一执行到引用点就 NoClassDefFoundError、整 jar 失效。1.4.16 成品 dex 实测：
# 只保了 Proxy/crawler/net，utils 整包（Util/Json/Path/Prefers/Auth/Shell 等 12 类）
# ＋顶层 Init/Runtime＋bean 3 类＋runtime 2 类共 19 个类原名消失。此行与 FongMi
# catvod/consumer-rules.pro 的 `-keep class com.github.catvod.** { *; }` 同口径，
# 全包保原名与成员；下面三条旧规则已被此行覆盖，保留不动。
-keep class com.github.catvod.** { *; }
-keep class com.github.catvod.Proxy { *; }
-keep class com.github.catvod.crawler.** { *; }
-keep class com.github.catvod.net.** { *; }
-keep class * extends com.github.catvod.crawler.Spider

# jar 按名引用的宿主配置门面（2026-10-23 全量对差 appclass-keep-sweep 补）：
# vox.jar 的 merge 框架运行时按类名构造 com.fongmi.android.tv.api.config.VodConfig
# （类名在 jar 内为加密字符串池，本地仓拆解 jadx 解密实证；本批字节直查为密文形态）。
# 1.4.16 成品类清单实测：api.config 整包 5 类（BaseConfig/LiveConfig/RuleConfig/
# VodConfig/WallConfig）原名全缺、同包同命运，故按小包粒度整包保原名与成员。
-keep class com.fongmi.android.tv.api.config.** { *; }

# QuickJS 桥面全包保名（同批对差补）：FongMi 由 quickjs/consumer-rules.pro
# （com.fongmi.quickjs.*）与 runtime/proguard-rules.pro（com.fongmi.quickjs.**）
# 两处保名；星辰此前只有 method 子包一条，1.4.16 成品实测 bean 4 类＋utils 6 类
# ＋crawler/Loader 共 11 类原名缺失。按三家并集口径全包补齐（与本文件第 7 行
# 既有的 method.** 规则重叠无副作用）。
-keep class com.fongmi.quickjs.** { *; }

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

# 8.83 宿主类同包名补齐：规则 JS 在任意作用域按全名直调
# com.example.hikerview.utils.FileUtil.*（列表期解密封面等），无 Java 侧引用，
# R8 不保名会把类/方法改名或整类裁掉、类路径解析当场落空。整类保名。
-keep class com.example.hikerview.utils.FileUtil { *; }

# 海阔模型：Gson 按字段名序列化/反序列化（MY_RULE、hiker://page 规则体），字段名不能改
-keep class com.fongmi.android.tv.api.hk.HkRule { *; }
-keep class com.fongmi.android.tv.api.hk.HkPageRule { *; }
-keep class com.fongmi.android.tv.api.hk.HkPageParser$HkPage { *; }
-keep class com.fongmi.android.tv.api.hk.HkViewCollection { *; }
-keep class com.fongmi.android.tv.api.hk.HkSubscribeRecord { *; }
-keep class com.fongmi.android.tv.api.hk.HkPlayData { *; }

# Node 桥进程宿主登记（第 3 步接缝）：NodeService 在 main 源集按类名
# Class.forName 触发 LabNodeProcessHost 加载自登记（main 不能直引
# mobile 源集的实验室类），类名被 R8 改名会让登记静默失效、桥误判
# 没装。整类保名。
-keep class com.fongmi.android.tv.ui.lab.LabNodeProcessHost { *; }

# Node 桥版本登记口（第 4 步接线）：NodeLabInfo 在 main 源集按类名
# Class.forName 触发 LabNodeInfo 加载自登记（同第 3 步口径），类名被
# R8 改名会让模块页 NODEJS 行的版本登记静默失效、误报没装。整类保名。
-keep class com.fongmi.android.tv.ui.lab.LabNodeInfo { *; }

# Node 桥内置入口（第 1 步内置分支）：RuntimeModule 按字面类名
# Class.forName("com.fongmi.nodejs.RuntimeEntry") 加载内置桥入口，
# 类名被 R8 改名会让 entry() 在正式包里必抛「加载不了、重启 App」。
# 前三步未落此条，第 4 步接线时补上；只需保类名与无参构造。
-keep class com.fongmi.nodejs.RuntimeEntry {
    public <init>();
}
