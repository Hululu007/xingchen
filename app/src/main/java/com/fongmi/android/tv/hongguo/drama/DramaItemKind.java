package com.fongmi.android.tv.hongguo.drama;

/**
 * 红果条目类型（普查总图卡 6/7/8 三型分家，账 hongguo-batch1-item-types
 * -20261029）：官方三型是三套数据模型——正剧（剧集/album 属性）、UGC
 * 短片（SaaSUgcPostData，右列顶是作者关注）、合集（UGC album 独立实体）。
 * 类型只在解析入口按协议字段定一次（见 DramaParser.asSeriesItem 的定型
 * 口径），定下后随条目走，消费端只读本枚举分发，不许再拿 content_type
 * 原值或正交布尔旗在消费端拼装推断。
 */
public enum DramaItemKind {
    /** 正剧条目：剧集模型；右列星钮＝追这部剧、底栏＝本剧条。 */
    SERIES,
    /** UGC 短片：作者帖；右列顶＝作者头像＋关注钮（对象是人）、底栏＝所属合集条。 */
    UGC_CLIP,
    /** 合集条目：UGC album 独立实体，身份是 album id；落点＝合集详情（自家以专辑名单承接）。 */
    ALBUM
}
