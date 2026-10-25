package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 播放页占位（重做第 0 步清场承接）：批二播放页本体已整片删除、从零重做中。
 * 本类只保留同类名与 start 契约，让全树入口（首页/迷你条/各列表页点播、通知点开）
 * 照常调起不崩；页面本身只给一行「播放页重做中」空页，不接播放服务、不动队列。
 */
public class MusicPlayerActivity extends Activity {

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("播放页重做中");
        text.setTextSize(16f);
        text.setTextColor(Color.DKGRAY);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
    }
}
