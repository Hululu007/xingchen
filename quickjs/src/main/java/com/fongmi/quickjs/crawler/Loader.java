package com.fongmi.quickjs.crawler;

import com.whl.quickjs.android.QuickJSLoader;

import dalvik.system.DexClassLoader;

public class Loader {

    public Loader() {
        QuickJSLoader.init();
    }

    public Spider spider(String api, DexClassLoader dex) {
        return new Spider(api, dex);
    }

    /** 新格式宿主型 JS 源（wvB/WvSpider 类）走独立通道，与普通源互不影响。 */
    public WvSpider wvSpider(String api) {
        return new WvSpider(api);
    }
}
