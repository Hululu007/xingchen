package com.fongmi.android.tv.hongguo.drama;

import java.util.ArrayList;
import java.util.List;

/**
 * 官方短剧筛选面（distribution landpage 面板）的一行选择器：
 * key＝维度键（category_dim_theme 主题／category_dim_role 设定／
 * category_dim_epoch 背景等），name＝行名（全部主题…），options＝
 * 该行可选值（name 显示名、value＝selector_item_id，形如 cate_165）。
 */
public final class DramaFilterRow {

    public String key = "";
    public String name = "";
    public final List<Option> options = new ArrayList<>();

    public static final class Option {
        public final String name;
        public final String value;

        public Option(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
