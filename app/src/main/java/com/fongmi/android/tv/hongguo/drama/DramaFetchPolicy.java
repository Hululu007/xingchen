package com.fongmi.android.tv.hongguo.drama;

/**
 * 取模型身份策略（2026-10-30 从零重建）：共享优先＋冷却降级，
 * 代替旧的永久锁存（定根账 hongguo-1462-triage-20261030 件二嫌疑三：
 * 锁存单向持久——共享被拒一次即永久打入冷宫、老档案烧空后永久 E1）。
 *
 * <p>纯逻辑（时钟与存储都可注入），harness 真值表在本类口径上验：</p>
 * <ul>
 *   <li>常态走共享；共享回身份拒绝（110001）或限流空体（-2）→ 进冷却
 *       （{@link #COOLDOWN_MS}），本发改走自家兜底。</li>
 *   <li>冷却期内一律自家优先；冷却到期自动回共享重试（无手动解锁
 *       概念；抽屉「刷新设备档案」另行清自家档案，与冷却无关）。</li>
 *   <li>共享传输失败（网络/HTTP 非 200）→ 本发改走自家，但不进冷却
 *       （瞬时故障不算身份信号）。</li>
 *   <li>共享回条目级业务码（如 101000）→ 原样报错：不兜底、不冷却
 *       （那是条目自身被拒，不是档案问题）。</li>
 *   <li>共享成功 → 清冷却。</li>
 * </ul>
 */
public final class DramaFetchPolicy {

    /** 共享被拒/空体后的冷却时长：15 分钟（配额回补是小时级，冷却只防连环白发，期满即重试共享）。 */
    public static final long COOLDOWN_MS = 15L * 60 * 1000;

    /** 身份层拒绝业务码（模型口 BaseResp 信封 Code/StatusCode）。 */
    public static final int CODE_IDENTITY_REJECT = 110001;
    /** 限流空体归一码（200＋零字节/无 model）。 */
    public static final int CODE_EMPTY = -2;
    /** 传输失败归一码（网络异常；HTTP 非 200 由调用方以正码传入、单独判）。 */
    public static final int CODE_TRANSPORT = -1;

    public interface Clock {
        long nowMillis();
    }

    public interface Store {
        long readCooldownUntil();

        void writeCooldownUntil(long untilMillis);
    }

    /** 一次取数该先用哪份身份。 */
    public enum Route {SHARED, OWN}

    /** 共享一发的结局分类（调用方按回包形状归类后喂进来）。 */
    public enum SharedOutcome {
        /** 出货。 */
        SUCCESS,
        /** 110001 身份层拒绝。 */
        IDENTITY_REJECT,
        /** 空体（限流形状）。 */
        EMPTY,
        /** 传输/HTTP 失败（瞬时）。 */
        TRANSPORT_FAIL,
        /** 条目级业务码拒绝（与档案无关）。 */
        ENTRY_REJECT
    }

    /** 结局处置：是否改走自家兜底重发本发。 */
    public enum Action {DELIVER, FALLBACK_OWN, FAIL_AS_IS}

    private final Clock clock;
    private final Store store;

    public DramaFetchPolicy(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
    }

    /** 本发先行身份：冷却中走自家，否则共享。 */
    public Route route() {
        return clock.nowMillis() < store.readCooldownUntil() ? Route.OWN : Route.SHARED;
    }

    /** 共享结局 → 处置（并按口径推进冷却状态）。 */
    public Action onSharedOutcome(SharedOutcome outcome) {
        switch (outcome) {
            case SUCCESS:
                if (store.readCooldownUntil() != 0) store.writeCooldownUntil(0);
                return Action.DELIVER;
            case IDENTITY_REJECT:
            case EMPTY:
                store.writeCooldownUntil(clock.nowMillis() + COOLDOWN_MS);
                return Action.FALLBACK_OWN;
            case TRANSPORT_FAIL:
                return Action.FALLBACK_OWN;
            case ENTRY_REJECT:
            default:
                return Action.FAIL_AS_IS;
        }
    }

    /** 回包形状 → 结局分类（业务码口径与定根账一致）。 */
    public static SharedOutcome classify(int errorCode) {
        if (errorCode == CODE_IDENTITY_REJECT) return SharedOutcome.IDENTITY_REJECT;
        if (errorCode == CODE_EMPTY) return SharedOutcome.EMPTY;
        // HTTP 状态码（三位数）与网络异常同归传输失败；业务码都是六位数段。
        if (errorCode == CODE_TRANSPORT || (errorCode >= 100 && errorCode <= 599)) {
            return SharedOutcome.TRANSPORT_FAIL;
        }
        return SharedOutcome.ENTRY_REJECT;
    }
}
