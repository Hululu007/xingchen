package com.fongmi.android.tv.ui.hk;

import com.fongmi.android.tv.api.hk.HkImage;
import com.fongmi.android.tv.api.hk.HkJsRuntime;
import com.fongmi.android.tv.api.hk.HkRule;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对应 8.83 JSEngine 进程单例语义（审计 A1）：
 * 运行时按规则名缓存、进程内永不销毁；init 每实例只跑一次。
 * 旧实现每开一页 new+destroy 导致每次全量重初始化，此管理器取代之。
 */
public final class HkEngineManager {

    private static final Map<String, HkJsRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final Set<HkJsRuntime> INITIALIZED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<HkRule> CURRENT_RULE = new ThreadLocal<>();

    private HkEngineManager() {
    }

    /** 规则隔离键：原版按规则名（变量以 "规则名@key" 隔离）；名为空时退化用 url。 */
    public static String ruleKey(HkRule rule) {
        if (rule == null) return "";
        String title = rule.getTitle();
        if (title != null && !title.isEmpty()) return title;
        String url = rule.getUrl();
        return url == null ? "" : url;
    }

    /** 取（或建）规则的常驻运行时；不 init、不销毁。 */
    public static HkJsRuntime runtimeFor(HkRule rule) {
        String key = ruleKey(rule);
        HkJsRuntime rt = RUNTIMES.get(key);
        if (rt == null) {
            HkJsRuntime created = new HkJsRuntime(rule);
            HkJsRuntime prev = RUNTIMES.putIfAbsent(key, created);
            rt = prev == null ? created : prev;
        }
        CURRENT_RULE.set(rule);
        // 图片 @js= 求值靠 HkImage 找当前规则运行时（8.83 是引擎单例天然可用）；
        // 新模块此前从未登记，抓取器拿不到运行时只能把加密原流交回，图全部解码失败
        HkImage.setCurrentRuntime(rt);
        return rt;
    }

    /** W4：只查不建——跨规则变量共享（getMyVar/putMyVar 第三参）读别规则变量表时用，
     * 不在就当没有，不触发创建（避免副作用）。 */
    public static HkJsRuntime runtimeIfPresent(HkRule rule) {
        return RUNTIMES.get(ruleKey(rule));
    }

    /** init 每实例只执行一次（原版 JSEngine 单例 init 一次）；失败不标记，下次重试。 */
    public static void ensureInit(HkJsRuntime rt) throws Exception {
        if (INITIALIZED.contains(rt)) return;
        synchronized (rt) {
            if (INITIALIZED.contains(rt)) return;
            rt.init();
            INITIALIZED.add(rt);
        }
    }

    /** 供无 rule 参数的接缝（如 HkLazyRuleParser.JsRuntime.evalJSForObj）定位当前运行时。 */
    public static HkRule currentRule() {
        return CURRENT_RULE.get();
    }

    public static void setCurrentRule(HkRule rule) {
        CURRENT_RULE.set(rule);
    }
}
