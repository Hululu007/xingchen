package com.fongmi.android.tv.ui.lab;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 实验室 UI 设计语言：定稿白皮纹卡片（~/workspace/lab-ui-mockup/，lab-env/service/log/settings/store 五张）。
 * 白卡 72%＋细黑描边；字深、点缀黄 #F2B33D 只做按钮。
 * 页面底跟星辰全局壁纸走（ThemeManager 给窗口铺壁纸，实验室页内容根透明；
 * 壁纸没贴上前的兜底底色见 Theme.XingChen.LabPage 的 windowBackground＝#FBFBFA）。
 * 独立成套，不依赖其它模块的皮肤。布局/条目/顺序一律不动，只出皮。
 */
public final class LabSkin {

    /** 点缀黄（只做按钮底） */
    public static final int YELLOW = 0xFFF2B33D;
    /** 主字（标题/名称）#1E1E22 */
    public static final int TEXT_MAIN = 0xFF1E1E22;
    /** 正文 #2C2C30 */
    public static final int TEXT_BODY = 0xFF2C2C30;
    /** 次字（返回箭头/状态栏）#55555E */
    public static final int TEXT_SUB = 0xFF55555E;
    /** 暗字（徽标/说明）#6D6D78 */
    public static final int TEXT_DIM = 0xFF6D6D78;
    /** 绿字（运行中/已装）#1E7A4C */
    public static final int GREEN = 0xFF1E7A4C;
    /** 绿点 #22B45E */
    public static final int GREEN_DOT = 0xFF22B45E;
    /** 出错红 #F87171 */
    public static final int RED = 0xFFF87171;
    /** 白底上的琥珀字（状态/进度文案）#C9870F */
    public static final int AMBER = 0xFFC9870F;
    /** 黄钮上的字 #171310 */
    public static final int ON_YELLOW = 0xFF171310;

    private LabSkin() {
    }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** 状态栏融合：透明栏＋深色图标，内容延伸到栏下（跟 App 其它页同一写法，浅色版） */
    public static void edgeToEdge(AppCompatActivity a) {
        EdgeToEdge.enable(a,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            a.getWindow().setStatusBarContrastEnforced(false);
            a.getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : 0;
    }

    /** 卡面：白 72%＋1px 黑 6% 描边＋12dp 圆角（定稿卡） */
    public static GradientDrawable cardBackground(Context c, boolean running) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xB8FFFFFF); // rgba(255,255,255,.72)
        d.setCornerRadius(dp(c, 12));
        d.setStroke(dp(c, 1), running ? 0x804ADE80 : 0x0F000000); // 运行中绿描边 rgba(74,222,128,.5)
        return d;
    }

    /** 状态徽：灰（未装/已停止） */
    public static GradientDrawable tagGray(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x0D000000); // rgba(0,0,0,.05)
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 状态徽：绿（运行中） */
    public static GradientDrawable tagGreen(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x334ADE80); // rgba(74,222,128,.20)
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 黄色主钮（只做点缀） */
    public static GradientDrawable buttonYellow(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(YELLOW);
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 灰色次钮 */
    public static GradientDrawable buttonGray(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x12000000); // rgba(0,0,0,.07)
        d.setCornerRadius(dp(c, 999));
        return d;
    }

    /** 小圆点（运行中） */
    public static GradientDrawable dot(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        int s = dp(c, 8);
        d.setSize(s, s);
        return d;
    }
}
