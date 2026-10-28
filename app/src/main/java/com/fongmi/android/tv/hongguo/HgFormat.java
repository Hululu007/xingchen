package com.fongmi.android.tv.hongguo;

/**
 * 红果计数显示格式（官方实证，jadx 抠值）：
 * 计数拼法＝a45/o6 的 toString()＝(计数, 单位) 对——计数 <10000 时单位为
 * 空串直显原数；>=10000 时计数＝(x/1000)/10.0 一位小数、单位「万」。
 * 布局 b98（我的页关系行横排型）与 ct0（作者页数据行竖排型）的三组
 * 计数都走这一拼法（b98 把数与单位分开摆、ct0 拼成一串，数值同源）。
 * o6 构造只见 10000/「万」一档（亿界未抠到，不猜：大数继续按万进位）。
 */
public final class HgFormat {

    private HgFormat() {
    }

    /** @return [计数文本, 单位文本]（单位可为空串）。 */
    public static String[] countUnit(long n) {
        if (n < 10000) return new String[]{String.valueOf(n), ""};
        long t = n / 1000;
        String num = String.format(java.util.Locale.US, "%.1f", t / 10.0);
        return new String[]{num, "万"};
    }

    /** 数与单位拼成一串（ct0 型用）。 */
    public static String countLine(long n) {
        String[] p = countUnit(n);
        return p[0] + p[1];
    }
}
