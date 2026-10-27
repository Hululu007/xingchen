package com.fongmi.android.tv.ui.base;

import android.os.Bundle;

import com.fongmi.android.tv.R;

/**
 * 音乐各页共用基类（切页过渡统一收口）：进出统一走页签交叉淡入淡出
 * （250ms＋Material 标准缓动，xc_tab 对），覆盖 BaseActivity 的全 App 通用
 * 200ms 线性 xc_fade 对。他亲令：「切页就淡入的」「音乐所有页面都这样」
 * 「硬切不舒服」——音乐域一处改、全页生效，不逐页各写一套。
 * 星辰本体其它页不继承本类、仍走 BaseActivity 原口径；播放页
 * （MusicPlayerActivity）不继承本类——它跟播放条开合，是唯一例外。
 */
public abstract class MusicBaseActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(R.anim.xc_tab_fade_in, R.anim.xc_tab_fade_out);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.xc_tab_fade_in, R.anim.xc_tab_fade_out);
    }
}
