package com.fongmi.android.tv.hongguo.sign;

import android.app.Application;
import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.hongguo.api.HgDevice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * 原版 metasec 签名（随包 libmetasec_ml.so＋assets/hg-metasec/metasec.dex，
 * 同出官方 7.2.5.32 包、SDK 自报 v04.09.09.01-bugfix）接入自家面签名路：
 * HgSign 仿算组好最终 URL＋头之后，由本类调原生 SDK 重签六头
 * （x-argus/x-gorgon/x-helios/x-khronos/x-ladon/x-medusa）。采纳纪律
 * 是全有或全无（2026-10-31 签名收口，审计账 hongguo-signer-map-
 * 20261031.md 第三节）：原生头须六头成套且 x-khronos 与 URL 的 ts
 * 同字串，才整套替换仿算六头；不成套整发回退全套仿算——一发请求
 * 内不许出现两个实现的头混编（旧逐键采纳已废：混编时 medusa 的
 * 设备快照/内嵌时刻与公参、与其余头可能来自两台设备两个时刻）。
 * 接入设计与逐项证据见
 * metasec-integration-design-20261031.md（用法结构抠自官方 7.3.9.32
 * 反编译树，字面量取 unidbg proof 实证能 init 本 .so 的 7.2.5.32 件套；
 * 反射签名以 metasec-kit/smali 为准，与随包 dex 同源）。
 *
 * <p>回退纪律（死线）：原生未就绪／初始化失败／签名异常／无产出时，
 * {@link #resign} 返回入参头的副本，与现行 HgSign 仿算行为逐字等价，
 * 绝不拦业务；初始化只试一次，失败置 DEAD 不重试风暴；签名期异常
 * 只放弃本次、不置 DEAD。SDK 不假设线程安全：初始化走双重检查锁、
 * 签名全程一把静态锁串行。</p>
 *
 * <p>判断项登记：frameSign 在官方全树零调用点，第二参 int 无任何
 * 官方实值可抄，本类作 getFeatureHash 的后备时传 0（设计表 §3）；
 * MSConfig 的 channel 官方清单 meta-data 未抠到单一实值，取自家面
 * 请求恒呈的档案 channel（HgDevice.signChannel）保持呈现一致；
 * IMsComplianceScene 本代为单法 isComplianceScene，官方两法接口的
 * 对应关系未抠到，Proxy 恒答 false。</p>
 */
public final class HgNativeSign {

    private static final String APP_ID = "8662";

    /** license：sdk-init-his 槽[3]，proof 实证 init 本 .so 成功的那串（设计表 §1）。 */
    private static final String LICENSE =
            "10CS/42gNkYsyh4LRwuHcdVaVjG2i5BUsDfHUqCVBaxa/Bx60+MQutso9T7Ps/7BndgAAaVpcAAujIDjnIJHII740GgosokBd09F6HCAKmqaazJTJ2rc04TNl663tZDc6cEiCsesc73XvCq/XcM0s5BbW8VhXBjzutaaL+a9WT3a5mtor2CRVX3DT5KKaSSVqNXsH9JW+LnbyzIZeLlzHvvhhigsIgRC0K4ANnZAR49HZswPeF73VDxugL2v8aWxtx2jAA0wuFsx6fN5oMDk5aG0RMVzgu0RIyoMGQaF3LK7ezON";

    /** setMsSettingConfig 恒返串：官方 w2.settingConfig，与 sdk-settings-his 逐字相同（设计表 §1）。 */
    private static final String MS_SETTING_CONFIG =
            "fidhWm1xcXVha3ZaZnF3aSc/cXdwYCknYVp2bGJrczBaZnF3aSc/cXdwYCknYVprZHNsWmZxd2knP2NkaXZgeA==";

    private static final String DEX_ASSET = "hg-metasec/metasec.dex";

    private static final String CLS_UTILS = "com.bytedance.mobsec.metasec.ml.MSManagerUtils";
    private static final String CLS_CONFIG = "com.bytedance.mobsec.metasec.ml.MSConfig";
    private static final String CLS_BUILDER = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder";
    private static final String CLS_SETTING = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder$IMsSettingConfig";
    private static final String CLS_FETCHED_DID = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder$IFetchedDid";
    private static final String CLS_FETCH_BY_KEY = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder$IFetchAppSettingsByKey";
    private static final String CLS_CHECK_UPDATED = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder$ICheckAppSettingsUpdated";
    private static final String CLS_COMPLIANCE = "com.bytedance.mobsec.metasec.ml.MSConfig$Builder$IMsComplianceScene";
    /** 类包闭包补全时并入的宿主门面桩（官方 App 私有类，见 kit 桩源）：context() 回它。 */
    private static final String CLS_DRAGON_APP = "com.dragon.read.app.App";

    /** 只采纳的六头（小写口径，与 HgSign 现行键名一致）。 */
    private static final String[] SIX_HEADERS = {
            "x-argus", "x-gorgon", "x-helios", "x-khronos", "x-ladon", "x-medusa"};

    private static final int ST_UNINIT = 0;
    private static final int ST_READY = 1;
    private static final int ST_DEAD = 2;

    private static volatile int state = ST_UNINIT;
    private static final Object INIT_LOCK = new Object();
    private static final Object SIGN_LOCK = new Object();

    // 以下字段只在 state 置 READY 之前写、READY 之后只读（volatile state 兜可见性）。
    private static Object msManager;
    private static Method mGetFeatureHash;
    private static Method mFrameSign;
    private static Method mSetDeviceID;
    private static Method mSetInstallID;
    private static Method mReport;
    private static String filledDid = "";
    private static String filledIid = "";

    private HgNativeSign() {
    }

    /** 原生 SDK 是否已就绪（只报状态，不触发初始化）。 */
    public static boolean isReady() {
        return state == ST_READY;
    }

    /** 上一发 resign 是否整套采纳了原生六头（诊断留痕用，登录链观测点）。 */
    private static volatile boolean lastResignNative;

    public static boolean lastResignWasNative() {
        return lastResignNative;
    }

    /**
     * 预热：主动触发一次初始化并报就绪态（登录链在发请求前调用——
     * 此前 init 全靠 resign 内懒触发，失败一次置 DEAD 后整进程不再
     * 试，登录侧连「原生到底活没活」都没有观测点，审表嫌疑一）。
     */
    public static boolean warmUp() {
        return ensureInit();
    }

    /**
     * 原生重签：返回合并后的新 Map（不改入参）。原生未就绪／失败／
     * 无产出时返回入参的副本（与现行仿算行为逐字等价）。
     *
     * @param url     已组好的最终请求 URL（含查询串，ts= 在其中）
     * @param headers HgSign 组好的最终头
     * @param body    请求体字节，GET 传 null
     */
    public static Map<String, String> resign(String url, Map<String, String> headers, byte[] body) {
        Map<String, String> out = headers == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        if (url == null || url.isEmpty()) return out;
        synchronized (SIGN_LOCK) {
            try {
                if (!ensureInit()) {
                    lastResignNative = false;
                    return out;
                }
                refillIdentityIfChanged();
                Map<String, String> nativeHeads = new LinkedHashMap<>();
                collect(nativeHeads, invokeMap(mGetFeatureHash, url, body));
                if (!nativeHeads.containsKey("x-medusa")) {
                    // 后备路：仅补主路缺的键，不覆盖主路产出（设计表 §3 合并纪律）。
                    // 补完仍按下面的成套闸统一裁决，不单独采纳。
                    Map<String, String> back = new LinkedHashMap<>();
                    collect(back, invokeMap(mFrameSign, url, 0));
                    for (Map.Entry<String, String> e : back.entrySet()) {
                        nativeHeads.putIfAbsent(e.getKey(), e.getValue());
                    }
                }
                // 全有或全无成套闸（2026-10-31 签名收口）：六头齐全且
                // x-khronos 与 URL 查询串 ts= 同字串（防原生时钟与票据
                // 错秒，worker 校验实证、设计表 §3），才整套替换仿算
                // 六头；任一不满足，整发保留仿算头、原生头一个不采。
                if (!isCompleteNativeSet(url, nativeHeads)) {
                    lastResignNative = false;
                    return out;
                }
                for (String h : SIX_HEADERS) {
                    putIgnoreCase(out, h, nativeHeads.get(h));
                }
                lastResignNative = true;
                return out;
            } catch (Throwable t) {
                // 签名期异常只放弃本次：整发保留仿算头，状态不置 DEAD。
                return headers == null
                        ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            }
        }
    }

    // ---------- 初始化 ----------

    private static boolean ensureInit() {
        if (state != ST_UNINIT) return state == ST_READY;
        synchronized (INIT_LOCK) {
            if (state != ST_UNINIT) return state == ST_READY;
            try {
                doInit();
                state = ST_READY;
                return true;
            } catch (Throwable t) {
                // init 期异常置 DEAD：进程内不再重试，恒走仿算兜底。
                state = ST_DEAD;
                return false;
            }
        }
    }

    private static void doInit() throws Exception {
        Context ctx = App.get();
        if (ctx == null) throw new IllegalStateException("App 未就绪");
        File codeCacheDir = ctx.getCodeCacheDir();
        File dexFile = copyDex(ctx, codeCacheDir);
        DexClassLoader loader = new DexClassLoader(dexFile.getAbsolutePath(),
                codeCacheDir.getAbsolutePath(),
                ctx.getApplicationInfo().nativeLibraryDir,
                HgNativeSign.class.getClassLoader());

        // 宿主喂入（类包闭包补全的收口）：类包内 dragon App 是桩，其
        // context() 是 SDK 旁路（网络参数/监控）取宿主 Context 的口子，
        // 必须在 init 之前喂真 Application。喂入失败按 init 失败处理
        // （置 DEAD 走仿算）：类包与本类同包同版，缺面说明包内类包
        // 不是验过的那只，不半接线硬跑。
        Class.forName(CLS_DRAGON_APP, true, loader)
                .getMethod("setHostApplication", Application.class)
                .invoke(null, (Application) ctx);

        Class<?> builderClass = Class.forName(CLS_BUILDER, true, loader);
        Class<?> configClass = Class.forName(CLS_CONFIG, false, loader);
        Object builder = builderClass
                .getConstructor(String.class, String.class, int.class)
                .newInstance(APP_ID, LICENSE, 99999);
        builder = callSetter(builderClass, builder, "setClientType",
                new Class<?>[]{int.class}, 0);
        builder = callSetter(builderClass, builder, "setChannel",
                new Class<?>[]{String.class}, HgDevice.signChannel());
        builder = callSetter(builderClass, builder, "setMsSettingConfig",
                new Class<?>[]{Class.forName(CLS_SETTING, false, loader)},
                newProxy(loader, CLS_SETTING));
        builder = callSetter(builderClass, builder, "setFetchedDidInterface",
                new Class<?>[]{Class.forName(CLS_FETCHED_DID, false, loader)},
                newProxy(loader, CLS_FETCHED_DID));
        builder = callSetter(builderClass, builder, "setFetchAppSettingsByKeyInterface",
                new Class<?>[]{Class.forName(CLS_FETCH_BY_KEY, false, loader)},
                newProxy(loader, CLS_FETCH_BY_KEY));
        builder = callSetter(builderClass, builder, "setCheckAppSettingsUpdatedInterface",
                new Class<?>[]{Class.forName(CLS_CHECK_UPDATED, false, loader)},
                newProxy(loader, CLS_CHECK_UPDATED));
        builder = callSetter(builderClass, builder, "setMsComplianceScene",
                new Class<?>[]{Class.forName(CLS_COMPLIANCE, false, loader)},
                newProxy(loader, CLS_COMPLIANCE));
        Object config = builderClass.getMethod("build").invoke(builder);

        Class<?> utilsClass = Class.forName(CLS_UTILS, true, loader);
        // 官方 init 链返回值丢弃口径（设计表 §1）：成败以随后 get 是否取到实例为准。
        utilsClass.getMethod("init", Context.class, configClass).invoke(null, ctx, config);
        Object manager = utilsClass.getMethod("get", String.class).invoke(null, APP_ID);
        if (manager == null) throw new IllegalStateException("MSManagerUtils.get 返回 null");
        Class<?> managerClass = manager.getClass();
        msManager = manager;
        mGetFeatureHash = managerClass.getMethod("getFeatureHash", String.class, byte[].class);
        mFrameSign = managerClass.getMethod("frameSign", String.class, int.class);
        mSetDeviceID = managerClass.getMethod("setDeviceID", String.class);
        mSetInstallID = managerClass.getMethod("setInstallID", String.class);
        mReport = managerClass.getMethod("report", String.class);
        // 身份后填（设计表 §2）：init 之后同序 setDeviceID/setInstallID/report。
        filledDid = "";
        filledIid = "";
        refillIdentityIfChanged();
    }

    /** assets 的 metasec.dex 复制到 codeCache：文件名带 dex 长度，旧长度缓存不复用。 */
    private static File copyDex(Context ctx, File codeCacheDir) throws Exception {
        byte[] bytes;
        try (InputStream in = ctx.getAssets().open(DEX_ASSET);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            bytes = bos.toByteArray();
        }
        if (bytes.length == 0) throw new IllegalStateException("metasec.dex 为空");
        File out = new File(codeCacheDir, "metasec-" + bytes.length + ".dex");
        if (!out.isFile() || out.length() != bytes.length) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
        }
        return out;
    }

    private static Object callSetter(Class<?> builderClass, Object builder, String name,
                                     Class<?>[] types, Object... args) throws Exception {
        Object r = builderClass.getMethod(name, types).invoke(builder, args);
        return r != null ? r : builder;
    }

    // ---------- 身份后填 ----------

    /** 档案身份与已填身份不一致才重填＋report（覆盖换脸后的档案变更，设计表 §2）。 */
    private static void refillIdentityIfChanged() throws Exception {
        String did = HgDevice.signDeviceId();
        HgDevice.Profile p = HgDevice.profile();
        String iid = p.installId == null ? "" : p.installId;
        if (did.equals(filledDid) && iid.equals(filledIid)) return;
        mSetDeviceID.invoke(msManager, did);
        mSetInstallID.invoke(msManager, iid);
        mReport.invoke(msManager, "did-iid-update");
        filledDid = did;
        filledIid = iid;
    }

    // ---------- 签名合并 ----------

    @SuppressWarnings("unchecked")
    private static Map<String, String> invokeMap(Method m, Object... args) throws Exception {
        Object r = m.invoke(msManager, args);
        return r instanceof Map ? (Map<String, String>) r : null;
    }

    /**
     * 原生头成套判定（全有或全无的「全有」侧）：六头全在、非空，且
     * x-khronos 与 URL 查询串 ts= 同字串。URL 无 ts 时判不成套——
     * 自家面 URL 恒带 ts（HgSign 组串尾加），缺 ts 说明这发不经
     * 仿算打底，不拿原生头去拼一个来路不明的组合。
     */
    private static boolean isCompleteNativeSet(String url, Map<String, String> nativeHeads) {
        for (String h : SIX_HEADERS) {
            String v = nativeHeads.get(h);
            if (v == null || v.isEmpty()) return false;
        }
        String ts = queryValue(url, "ts");
        return ts != null && ts.equals(nativeHeads.get("x-khronos"));
    }

    /** 只收六头中有产出的键（键名大小写不敏感匹配、统一小写；空值视为无产出）。 */
    private static void collect(Map<String, String> dst, Map<String, String> src) {
        if (src == null || src.isEmpty()) return;
        for (Map.Entry<String, String> e : src.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            String key = e.getKey().toLowerCase(Locale.ROOT);
            if (isSixHeader(key)) dst.put(key, e.getValue());
        }
    }

    private static boolean isSixHeader(String lowerKey) {
        for (String h : SIX_HEADERS) if (h.equals(lowerKey)) return true;
        return false;
    }

    /** 写入合并：先摘同名旧键（大小写不敏感），再以小写键写入。 */
    private static void putIgnoreCase(Map<String, String> map, String lowerKey, String value) {
        String old = null;
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(lowerKey)) {
                old = k;
                break;
            }
        }
        if (old != null) map.remove(old);
        map.put(lowerKey, value);
    }

    /** URL 查询串里某参数的字面值（不解码，khronos 闸要同字串比对）；无则 null。 */
    private static String queryValue(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        int frag = query.indexOf('#');
        if (frag >= 0) query = query.substring(0, frag);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
        }
        return null;
    }

    // ---------- 配置回调 Proxy（接口类在 dex 内，以 dex 类加载器实现） ----------

    private static Object newProxy(ClassLoader loader, String ifaceName) throws Exception {
        Class<?> iface = Class.forName(ifaceName, false, loader);
        return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, CALLBACK_HANDLER);
    }

    /**
     * 五个回调的统一答复（设计表 §1）：settingConfig 恒返定版串；
     * fetchedDid 回档案 deviceId；fetchAppSettingsByKey 只有
     * ms_settings_prefix_8 返 {"type":0,"value":1}、余 null；
     * checkAppSettingsUpdated 恒 true；isComplianceScene 恒 false
     * （对应关系未抠到，判断项见类头注）。
     */
    private static final InvocationHandler CALLBACK_HANDLER = (proxy, method, args) -> {
        switch (method.getName()) {
            case "settingConfig":
                return MS_SETTING_CONFIG;
            case "fetchedDid":
                return HgDevice.signDeviceId();
            case "fetchAppSettingsByKey":
                return args != null && args.length > 0
                        && "ms_settings_prefix_8".equals(args[0])
                        ? "{\"type\":0,\"value\":1}" : null;
            case "checkAppSettingsUpdated":
                return Boolean.TRUE;
            case "isComplianceScene":
                return Boolean.FALSE;
            case "toString":
                return "HgNativeSignCallback";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && args.length > 0 && proxy == args[0];
            default:
                return null;
        }
    };
}
