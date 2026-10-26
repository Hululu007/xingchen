package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fongmi.android.tv.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 音乐首页暗磨砂接线（2026-10-27 试点，只服务音乐首页一页）：
 * 把首页树里原液态玻璃口径的背景件按资源识别换成 MusicFrostDrawable（暗磨砂，
 * 钉死深灰、糊底直贴），件内字与图标同步翻白系。其它页面不走本类、仍走
 * MusicGlassWire 同源液态玻璃，一字未动。
 *
 * 识别表：首页布局已改挂 bg_home_frost_* 暗磨砂 XML 件作可见下限（首帧即暗面），
 * 表内同时保留旧玻璃资源 id（bg_music_glass_* 等）作兜底识别——任何一处漏改
 * 布局也不会把液态玻璃留在首页。圆角逐件与原 shape 对应。
 * 滚动跟随：首页是 ScrollView，滚动时登记件的位置变了、取样窗须重算——
 * 与 MusicGlassWire 同法（滚动回调＋绘制前比对滚动位双保险，同步 invalidate
 * 赶上本帧重录），只是登记表是本页自己的磨砂件。
 */
public final class MusicFrostWire {

    private MusicFrostWire() {
    }

    // ---------- 识别表：资源 id → 圆角 dp ----------
    private static final int[] IDS = {
            R.drawable.bg_home_frost_24,
            R.drawable.bg_home_frost_20,
            R.drawable.bg_home_frost_16,
            R.drawable.bg_home_frost_12,
            R.drawable.bg_home_frost_circle,
            // 兜底识别（旧玻璃件 id，布局漏改处也不许留液态玻璃在首页）
            R.drawable.bg_music_glass_search,
            R.drawable.bg_music_glass_white_card,
            R.drawable.bg_music_glass_entry,
            R.drawable.home_bg_card_pink,
            R.drawable.home_bg_card_teal,
            R.drawable.home_bg_card_lime,
            R.drawable.bg_music_glass_quick,
            R.drawable.home_bg_notif_strip,
            R.drawable.bg_music_glass_circle,
            R.drawable.bg_music_continue_play,
    };
    private static final float[] RADII_DP = {
            24f,   // bg_home_frost_24（搜索胶囊）
            20f,   // bg_home_frost_20（三彩卡/继续卡/心情卡）
            16f,   // bg_home_frost_16（五快捷片）
            12f,   // bg_home_frost_12（通知提示条）
            999f,  // bg_home_frost_circle（⋮ 钮/圆播放键）
            24f,   // bg_music_glass_search
            20f,   // bg_music_glass_white_card
            20f,   // bg_music_glass_entry
            20f,   // home_bg_card_pink
            20f,   // home_bg_card_teal
            20f,   // home_bg_card_lime
            16f,   // bg_music_glass_quick
            12f,   // home_bg_notif_strip
            999f,  // bg_music_glass_circle
            999f,  // bg_music_continue_play
    };

    private static Map<Drawable.ConstantState, Float> sTable;

    private static synchronized Map<Drawable.ConstantState, Float> table(Context ctx) {
        if (sTable != null) return sTable;
        Map<Drawable.ConstantState, Float> m = new HashMap<>();
        for (int i = 0; i < IDS.length; i++) {
            Drawable d = ctx.getResources().getDrawable(IDS[i], ctx.getTheme());
            if (d != null && d.getConstantState() != null) m.put(d.getConstantState(), RADII_DP[i]);
        }
        sTable = m;
        return m;
    }

    /** 主题不一致时的兜底探查（与 MusicGlassWire 同因：ConstantState 按主题分实例）。查过即缓存。 */
    private static final Map<Drawable.ConstantState, Float> sProbeCache = new HashMap<>();

    private static synchronized Float probeRadius(Context ctx, Drawable.ConstantState state) {
        if (sProbeCache.containsKey(state)) return sProbeCache.get(state);
        Float found = null;
        try {
            for (int i = 0; i < IDS.length; i++) {
                Drawable d = ctx.getResources().getDrawable(IDS[i], ctx.getTheme());
                if (d != null && d.getConstantState() == state) {
                    found = RADII_DP[i];
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        sProbeCache.put(state, found);
        return found;
    }

    // ---------- 已换磨砂的件登记（滚动时逐件 invalidate 逼重裁取样窗） ----------
    private static final Set<View> sFrostViews =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static void registerFrost(View v) {
        synchronized (sFrostViews) {
            sFrostViews.add(v);
        }
    }

    private static boolean sRefreshing;

    static void refreshFrostViews() {
        if (sRefreshing) return;
        sRefreshing = true;
        try {
            java.util.List<View> copy;
            synchronized (sFrostViews) {
                copy = new java.util.ArrayList<>(sFrostViews);
            }
            android.graphics.Rect visible = new android.graphics.Rect();
            for (View v : copy) {
                if (!v.isAttachedToWindow() || !v.isShown()) continue;
                if (!v.getGlobalVisibleRect(visible)) continue;
                v.invalidate();
            }
        } finally {
            sRefreshing = false;
        }
    }

    // ---------- 真外投影（分层）：与 MusicGlassWire.applyFloatShadow 同法 ----------
    public static void applyFloatShadow(View v, final float radiusDp) {
        final float density = v.getResources().getDisplayMetrics().density;
        final float rPx = radiusDp >= 999f ? Float.MAX_VALUE : radiusDp * density;
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                int w = view.getWidth(), h = view.getHeight();
                if (w <= 0 || h <= 0) {
                    outline.setEmpty();
                    return;
                }
                outline.setRoundRect(0, 0, w, h, Math.min(rPx, Math.min(w, h) / 2f));
            }
        });
        float elevDp = radiusDp >= 999f ? 5f : radiusDp >= 20f ? 8f : radiusDp >= 16f ? 6f : 2.5f;
        v.setElevation(elevDp * density);
    }

    // ---------- 换件 ----------
    /** 单件换磨砂：背景命中识别表才换，返回是否换了。已是磨砂件只重翻前景（复用/重绑后字色被写回时收口）。 */
    public static boolean frostifyView(View v) {
        if (v == null) return false;
        Drawable bg = v.getBackground();
        if (bg instanceof MusicFrostDrawable) {
            flipForeground(v);
            return false;
        }
        if (bg == null) return false;
        Float r = table(v.getContext()).get(bg.getConstantState());
        if (r == null) r = probeRadius(v.getContext(), bg.getConstantState());
        if (r == null) return false;
        v.setBackground(new MusicFrostDrawable(v.getContext(), r));
        applyFloatShadow(v, r);
        registerFrost(v);
        flipForeground(v);
        return true;
    }

    /** 单件按指定圆角直换（代码直建件用：心情片/播放钮/菜单图标框）。已是磨砂件只重翻前景。 */
    public static void frostifyView(View v, float radiusDp) {
        if (v == null) return;
        if (v.getBackground() instanceof MusicFrostDrawable) {
            flipForeground(v);
            return;
        }
        v.setBackground(new MusicFrostDrawable(v.getContext(), radiusDp));
        applyFloatShadow(v, radiusDp);
        registerFrost(v);
        flipForeground(v);
    }

    /** 整树走一遍：命中识别表的背景全换暗磨砂；ScrollView 挂滚动钩子；页根再翻漏网字。 */
    public static void frostifyTree(View root) {
        if (root == null) return;
        frostifyView(root);
        if (root instanceof ScrollView) hookScrollView((ScrollView) root);
        if (root instanceof ViewGroup) frostifyChildren((ViewGroup) root);
        flipPageTexts(root);
    }

    private static void frostifyChildren(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            frostifyView(c);
            if (c instanceof ScrollView) hookScrollView((ScrollView) c);
            if (c instanceof ViewGroup) frostifyChildren((ViewGroup) c);
        }
    }

    // ---------- 件内前景翻白（磨砂面口径：字与图标一律白系，看得清铁律） ----------
    private static final int FG_MAIN = 0xFFF1F4F5;
    private static final int FG_SUB = 0x99FFFFFF; // 白 .60
    private static final int FG_ICON = 0xFFF1F3F5;

    /** 换磨砂后同步翻件内前景：子树内最大字号为主字、其余副字；自带独立背景的字（角标/黄钮）跳过；
     * 强调色（高饱和设计色，如「继续播放」红字、三彩卡彩色图标）保留不动；
     * ImageView 只转已有 tint 的单色字形图标，无 tint 的全彩图（封面）不动。 */
    private static void flipForeground(View root) {
        java.util.List<TextView> texts = new java.util.ArrayList<>();
        java.util.List<ImageView> icons = new java.util.ArrayList<>();
        collectForeground(root, texts, icons);
        float max = 0;
        for (TextView t : texts) max = Math.max(max, t.getTextSize());
        for (TextView t : texts) {
            if (isAccentColor(t.getCurrentTextColor())) continue;
            t.setTextColor(t.getTextSize() >= max - 0.5f ? FG_MAIN : FG_SUB);
            if (t instanceof EditText) ((EditText) t).setHintTextColor(FG_SUB);
        }
        for (ImageView im : icons) {
            if (im.getColorFilter() != null) im.setColorFilter(FG_ICON, PorterDuff.Mode.SRC_IN);
        }
    }

    private static void collectForeground(View v, java.util.List<TextView> texts, java.util.List<ImageView> icons) {
        if (v instanceof TextView) {
            Drawable own = v.getBackground();
            if (own == null || own instanceof MusicFrostDrawable) texts.add((TextView) v);
        }
        if (v instanceof ImageView) icons.add((ImageView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectForeground(g.getChildAt(i), texts, icons);
        }
    }

    /** 高饱和色＝设计强调色，翻字时保它（与 MusicGlassWire 同判据）。 */
    private static boolean isAccentColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max <= 90) return false;
        return (max - min) / (float) max > 0.45f;
    }

    /** 中性深色＝旧浅色口径留下的黑/深灰字（低饱和＋低亮度）；白系、浅灰蓝副字、强调色不算。 */
    private static boolean isDarkNeutral(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == 0) return true;
        float sat = (max - min) / (float) max;
        float lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return sat < 0.30f && lum < 0.62f;
    }

    /** 页面级翻字（frostifyTree 收尾）：不在任何磨砂件内、自己没底的中性深色字翻白
     * （首页标题行一类——标题渐变字/金星是强调色，判据自动跳过）。 */
    private static void flipPageTexts(View root) {
        java.util.List<TextView> texts = new java.util.ArrayList<>();
        collectPageTexts(root, false, texts);
        float max = 0;
        for (TextView t : texts) max = Math.max(max, t.getTextSize());
        for (TextView t : texts) {
            t.setTextColor(t.getTextSize() >= max - 0.5f ? FG_MAIN : FG_SUB);
            if (t instanceof EditText) ((EditText) t).setHintTextColor(FG_SUB);
        }
    }

    private static void collectPageTexts(View v, boolean inFrost, java.util.List<TextView> out) {
        boolean frost = inFrost || v.getBackground() instanceof MusicFrostDrawable;
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (!frost && t.getBackground() == null && isDarkNeutral(t.getCurrentTextColor())) out.add(t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectPageTexts(g.getChildAt(i), frost, out);
        }
    }

    // ---------- 滚动钩子（与 MusicGlassWire 同法：回调＋绘制前比对双保险） ----------
    private static final Set<ScrollView> sScrollViewHooked =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> sPreDrawHooked =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, Integer> sLastScrollPos = new WeakHashMap<>();

    private static void hookScrollView(final ScrollView sv) {
        synchronized (sScrollViewHooked) {
            if (!sScrollViewHooked.add(sv)) return;
        }
        hookPreDraw(sv);
        final Runnable idle = MusicFrostWire::refreshFrostViews;
        sv.getViewTreeObserver().addOnScrollChangedListener(() -> {
            refreshFrostViews();
            sv.removeCallbacks(idle);
            sv.postDelayed(idle, 260);
        });
    }

    private static void hookPreDraw(final View container) {
        synchronized (sPreDrawHooked) {
            if (!sPreDrawHooked.add(container)) return;
        }
        container.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (!container.isAttachedToWindow()) return true;
            int pos = container.getScrollY() * 131 + container.getScrollX();
            Integer last = sLastScrollPos.get(container);
            if (last == null || last != pos) {
                sLastScrollPos.put(container, pos);
                refreshFrostViews();
            }
            return true;
        });
    }
}
