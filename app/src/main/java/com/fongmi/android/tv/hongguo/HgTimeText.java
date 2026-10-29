package com.fongmi.android.tv.hongguo;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 评论时间短格式（官方实拍 113610 两样本夹逼：5 天前的评论显「10-24」
 * 日期、昨天的显「1天前」；包内 strings 实证 "%d分钟前"/"%d小时前"
 * 两档存在）：
 *   <60 秒「刚刚」；<60 分「N分钟前」；<24 时「N小时前」；满 1 天
 *   「1天前」；≥2 天同年「MM-dd」、跨年「yyyy-MM-dd」。
 * 「刚刚」与 1 天界为字节系通行惯例推断（账 comment-page-completion
 * 标明），其余档有实证锚点。
 */
public final class HgTimeText {

    private HgTimeText() {
    }

    /** @param epochSec 评论创建时间（秒）；非法值返回空串。 */
    public static String commentTime(long epochSec) {
        return commentTime(epochSec, System.currentTimeMillis() / 1000L);
    }

    static String commentTime(long epochSec, long nowSec) {
        if (epochSec <= 0) return "";
        long diff = nowSec - epochSec;
        if (diff < 0) diff = 0;
        if (diff < 60) return "刚刚";
        if (diff < 3600) return (diff / 60) + "分钟前";
        if (diff < 86400) return (diff / 3600) + "小时前";
        if (diff < 2 * 86400) return "1天前";
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(epochSec * 1000L);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowSec * 1000L);
        boolean sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR);
        SimpleDateFormat fmt = new SimpleDateFormat(sameYear ? "MM-dd" : "yyyy-MM-dd", Locale.US);
        return fmt.format(new Date(epochSec * 1000L));
    }
}
