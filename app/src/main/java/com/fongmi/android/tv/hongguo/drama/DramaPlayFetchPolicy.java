package com.fongmi.android.tv.hongguo.drama;

/**
 * 播放取模型身份策略（2026-10-30 晚，他亲令反转）：播放要链改自家
 * 身份先行，与浏览面 {@link DramaFetchPolicy}（共享先行＋冷却降级）
 * 分家——浏览/搜索/剧场/详情等读面不动，仍走 DramaFetchPolicy。
 *
 * <p>反转理由（定根账 hongguo-fetch-quota-dig2-20261030＋他真机对照）：
 * 配额按设备档案计、官方也烧只是正常看烧不见底；共用脸是全网共用
 * 的一份档案、排第一要链先被限流回空。播放是唯一吃配额的面，改由
 * 本机注册的自家档案先行（配批量＋缓存压消耗），共用脸降为兜底。</p>
 *
 * <p>纯逻辑（时钟与存储都可注入），口径与 DramaFetchPolicy 对称：</p>
 * <ul>
 *   <li>常态走自家；自家回身份拒绝（110001）或限流空体（-2）→ 自家
 *       进冷却（{@link #COOLDOWN_MS}），本发改走共享兜底。</li>
 *   <li>冷却期内播放直接走共享；冷却到期自动回自家重试（无手动
 *       解锁概念）。</li>
 *   <li>自家传输失败（网络/HTTP 非 200/注册失败）→ 本发改走共享，
 *       但不进冷却（瞬时故障不算身份信号）。</li>
 *   <li>自家回条目级业务码（如 101000）→ 原样报错：不兜底、不冷却
 *       （那是条目自身被拒，不是档案问题）。</li>
 *   <li>自家成功 → 清自家冷却。</li>
 *   <li>共享兜底在本链恒可直发（不读浏览面 POLICY 的冷却门），故
 *       不存在两面同时锁死：自家冷却中走共享、自家失败当场切共享，
 *       共享再失败即按其码收口。</li>
 * </ul>
 */
public final class DramaPlayFetchPolicy {

    /** 自家被拒/空体后的冷却时长：15 分钟（与浏览面同值；配额回补是小时级，冷却只防连环白发，期满即重试自家）。 */
    public static final long COOLDOWN_MS = 15L * 60 * 1000;

    public interface Clock {
        long nowMillis();
    }

    public interface Store {
        long readCooldownUntil();

        void writeCooldownUntil(long untilMillis);
    }

    /** 一次取模型该先用哪份身份。 */
    public enum Route {OWN, SHARED}

    /** 自家一发的结局分类（调用方按回包形状归类后喂进来）。 */
    public enum OwnOutcome {
        /** 出货。 */
        SUCCESS,
        /** 110001 身份层拒绝。 */
        IDENTITY_REJECT,
        /** 空体（限流形状）。 */
        EMPTY,
        /** 传输/HTTP/注册失败（瞬时）。 */
        TRANSPORT_FAIL,
        /** 条目级业务码拒绝（与档案无关）。 */
        ENTRY_REJECT
    }

    /** 结局处置：是否改走共享兜底重发本发。 */
    public enum Action {DELIVER, FALLBACK_SHARED, FAIL_AS_IS}

    private final Clock clock;
    private final Store store;

    public DramaPlayFetchPolicy(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
    }

    /** 本发先行身份：自家冷却中走共享，否则自家。 */
    public Route route() {
        return clock.nowMillis() < store.readCooldownUntil() ? Route.SHARED : Route.OWN;
    }

    /** 自家结局 → 处置（并按口径推进自家冷却状态）。 */
    public Action onOwnOutcome(OwnOutcome outcome) {
        switch (outcome) {
            case SUCCESS:
                if (store.readCooldownUntil() != 0) store.writeCooldownUntil(0);
                return Action.DELIVER;
            case IDENTITY_REJECT:
            case EMPTY:
                store.writeCooldownUntil(clock.nowMillis() + COOLDOWN_MS);
                return Action.FALLBACK_SHARED;
            case TRANSPORT_FAIL:
                return Action.FALLBACK_SHARED;
            case ENTRY_REJECT:
            default:
                return Action.FAIL_AS_IS;
        }
    }

    /** 回包形状 → 结局分类（业务码口径与 DramaFetchPolicy 共用一份）。 */
    public static OwnOutcome classify(int errorCode) {
        switch (DramaFetchPolicy.classify(errorCode)) {
            case IDENTITY_REJECT:
                return OwnOutcome.IDENTITY_REJECT;
            case EMPTY:
                return OwnOutcome.EMPTY;
            case TRANSPORT_FAIL:
                return OwnOutcome.TRANSPORT_FAIL;
            case ENTRY_REJECT:
            default:
                return OwnOutcome.ENTRY_REJECT;
        }
    }
}
