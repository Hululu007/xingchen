package com.fongmi.android.tv.ui.music.view;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 播放页封面（经典）：就是封面图本体，不带任何渐隐。
 * 此前这里有一条自编的底部 DST_OUT 溶带（0.78 起擦），依据是「瓜子封面在底层、
 * 氛围盖在上面溶掉下段」的误读——2026-10-26 回瓜子源码复核订正：PlayerScreen 的
 * 栈序是氛围在下、内容列在上（氛围先于内容列发射）；CoverArt 静态图路径里没有
 * 任何底部渐隐/罩（zIndex −2 是封面内部兜底层、与页面栈无关）。瓜子的「一体感」
 * 来自氛围层本身：同图糊化＋三团色洗＋取色罩在封面盒下方连续延展，封面下缘是
 * 图像自身的硬边。溶带已摘除，与 CoverAmbientView 的氛围口径配套。
 * 交叉淡化的一对封面（进/出）同用本类。
 */
public class FadingCoverView extends AppCompatImageView {

    public FadingCoverView(Context context) {
        super(context);
    }

    public FadingCoverView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
