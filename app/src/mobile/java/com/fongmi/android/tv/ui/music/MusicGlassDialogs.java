package com.fongmi.android.tv.ui.music;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 音乐弹窗统一壳（2026-10-26 他令「全换玻璃」）：音乐内系统 Material/框架
 * AlertDialog 家族（58 处提及、47 个 Material 构造点＋云盘/猜你喜欢 7 个框架构造点）
 * 的共享收口——面板换同源材料件，字按 MusicGlassWire 的翻字口径
 * 翻成白系（主 #F1F4F5／副 白.60），不许再出浅色面板＋黑字。
 *
 * 2026-10-27 材料推广：面板材料随全页推广换成暗磨砂 MusicFrostDrawable
 * （弹窗窗内无背景 View 可取样，走磨砂件的下限面——钉死深灰近不透明满铺，成形口径
 * 与页面磨砂件一致），本壳的翻字/染色口径不变。
 *
 * 接法两条：
 * 1) Material 构造点走 MusicGlassDialogBuilder（create 时调 attach）；
 * 2) 自带 OnShowListener 的站点在自己的监听里手动调 apply（attach 的监听会被它覆盖）。
 *
 * attach 只换窗底、挂显示回调与窗口淡入淡出动画，不动任何交互（按钮监听/取消口径/列表回调原样）。
 */
public final class MusicGlassDialogs {

    private MusicGlassDialogs() {
    }

    /** 中心弹窗圆角：音乐自有中心弹窗先例（落雪密钥/参数均衡根）同为 20dp。 */
    private static final float RADIUS_DP = 20f;

    // 翻字口径与 MusicGlassWire.flipForeground 同一套（样张实测＋WCAG 核算，见彼处注）
    private static final int FG_MAIN = 0xFFF1F4F5;
    private static final int FG_SUB = 0x99FFFFFF; // 白 .60
    private static final int FG_ICON = 0xFFF1F3F5;

    /** 挂壳：窗底换磨砂＋显示时整片翻字。create() 之后、show() 之前调。 */
    public static void attach(Dialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new MusicFrostDrawable(dialog.getContext(), RADIUS_DP));
            // 进出统一淡入淡出 200ms，与海阔/阅读弹窗同口径（HkDialogFade），进出双向。
            window.setWindowAnimations(R.style.HkDialogFade);
        }
        dialog.setOnShowListener(d -> apply(dialog));
    }

    /** 显示后整片收口：内容钮换玻璃面、文字翻白系、输入框与选择指示器跟上深底。 */
    public static void apply(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        View decor = dialog.getWindow().getDecorView();
        if (decor == null) return;
        Set<View> actionButtons = new HashSet<>();
        collectActionButtons(dialog, actionButtons);
        List<TextView> texts = new ArrayList<>();
        walk(decor, false, actionButtons, texts);
        float max = 0;
        for (TextView t : texts) max = Math.max(max, t.getTextSize());
        for (TextView t : texts) {
            if (isAccentColor(t.getCurrentTextColor())) continue; // 设计强调色保它（Wire 同口径）
            t.setTextColor(t.getTextSize() >= max - 0.5f ? FG_MAIN : FG_SUB);
            if (t instanceof EditText) ((EditText) t).setHintTextColor(FG_SUB);
        }
    }

    private static void collectActionButtons(Dialog dialog, Set<View> out) {
        int[] which = {Dialog.BUTTON_POSITIVE, Dialog.BUTTON_NEGATIVE, Dialog.BUTTON_NEUTRAL};
        try {
            if (dialog instanceof androidx.appcompat.app.AlertDialog) {
                androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) dialog;
                for (int w : which) {
                    Button b = d.getButton(w);
                    if (b != null) out.add(b);
                }
            } else if (dialog instanceof android.app.AlertDialog) {
                android.app.AlertDialog d = (android.app.AlertDialog) dialog;
                for (int w : which) {
                    Button b = d.getButton(w);
                    if (b != null) out.add(b);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 整树走一遍：
     * - 浅色实底面（亮度 >0.55 的纯色/渐变底）子树整片跳过——那是与深字配对的浅盒，不在本壳范围；
     * - 自定义内容里的裸 Button（非弹窗动作钮、非开关类）按落雪密钥弹窗先例换磨砂面（12dp），
     *   其内文字由 glassifyView 自带的 flipForeground 收口，本趟不再重复收；
     * - 其余 TextView 收集起来统一按字号分主/副翻；EditText 补下划线染色；
     * - 单选/多选的指示器（CheckedTextView 的对勾/圆点、CompoundButton 的钮）补染色：
     *   未选态白 .60 保可见，选中态保留主题强调色（从弹窗上下文的 colorAccent 解析，不自选色）。
     */
    private static void walk(View v, boolean inLightBox, Set<View> actionButtons, List<TextView> texts) {
        if (v == null) return;
        boolean light = inLightBox || surfaceLuminance(v.getBackground()) > 0.55f;
        if (!light) {
            if (v instanceof Button && !(v instanceof CompoundButton) && !actionButtons.contains(v)
                    && !(v.getBackground() instanceof MusicFrostDrawable)) {
                MusicGlassWire.glassifyView(v, 12f);
                return; // 磨砂面的子树已由 flipForeground 收口
            }
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                texts.add(t);
                if (t instanceof EditText) tintEditText((EditText) t);
                if (t instanceof CheckedTextView) tintCheckMark((CheckedTextView) t);
                if (t instanceof CompoundButton) tintButton((CompoundButton) t);
            }
            if (v instanceof ImageView) {
                ImageView im = (ImageView) v;
                if (im.getColorFilter() != null) im.setColorFilter(FG_ICON, PorterDuff.Mode.SRC_IN);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), light, actionButtons, texts);
        }
    }

    /** 输入框下划线/框线染色：底是框架默认件（认不出亮度）才染成白 .60；已成形的深/浅盒不动。 */
    private static void tintEditText(EditText et) {
        Drawable bg = et.getBackground();
        if (bg == null || bg instanceof MusicFrostDrawable) return;
        if (surfaceLuminance(bg) >= 0f) return; // 纯色/渐变盒已成形（深浅都配对好了），不洗
        try {
            et.setBackgroundTintList(ColorStateList.valueOf(FG_SUB));
        } catch (Throwable ignored) {
        }
    }

    private static void tintCheckMark(CheckedTextView ctv) {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            ctv.setCheckMarkTintList(indicatorColors(ctv.getContext()));
        } catch (Throwable ignored) {
        }
    }

    private static void tintButton(CompoundButton b) {
        try {
            b.setButtonTintList(indicatorColors(b.getContext()));
        } catch (Throwable ignored) {
        }
    }

    /** 指示器两态：选中＝主题强调色（解析 colorAccent，取不到回落主字白）；未选＝白 .60。 */
    private static ColorStateList indicatorColors(Context ctx) {
        int accent = FG_MAIN;
        try {
            TypedValue tv = new TypedValue();
            if (ctx.getTheme().resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)) {
                accent = tv.data;
            }
        } catch (Throwable ignored) {
        }
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, FG_SUB});
    }

    /** 高饱和色＝设计强调色（Wire isAccentColor 同口径）。 */
    private static boolean isAccentColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 90) return false;
        return (max - min) / (float) max > 0.45f;
    }

    /** 表面亮度（Wire surfaceLuminance 同口径：只认纯色/渐变首色，认不出返回 -1）。 */
    private static float surfaceLuminance(Drawable d) {
        try {
            if (d instanceof android.graphics.drawable.ColorDrawable) {
                return luminanceOf(((android.graphics.drawable.ColorDrawable) d).getColor());
            }
            if (d instanceof android.graphics.drawable.GradientDrawable) {
                int[] colors = ((android.graphics.drawable.GradientDrawable) d).getColors();
                if (colors != null && colors.length > 0) return luminanceOf(colors[0]);
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    private static float luminanceOf(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
    }
}
