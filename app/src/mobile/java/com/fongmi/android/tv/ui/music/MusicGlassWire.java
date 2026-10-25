package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 音乐玻璃共用接线（W2 材料层从零重写）：把各页/各行现用的半透填充件（无糊底的 shape 家族）
 * 按资源识别统一换成同源液态玻璃 MusicGlassDrawable，圆角逐件与原 shape 的 corners 对应（见识别表）。
 * 页面在 initView 走树一遍（glassifyTree），行在 Holder/绑定点单换（glassifyView）。
 *
 * 识别表口径（照旧口径恢复，w2-glass.md 对照表同表）：
 * 只识别 shape 家族件；StateList/selector 包裹的背景 ConstantState 对不上、自动跳过不误伤；
 * 本表外的有色设计件不动。badge_music（彩色角标）、bg_playlist_mosaic（封面拼图占位）
 * 不是玻璃件，不入表、不换。
 * XML 件本身同时是可见下限：未被接线换掉时，shape 的半透明渐变＋描边照样成形（回归清单第 1 条双保险）。
 */
public final class MusicGlassWire {

    private MusicGlassWire() {
    }

    // ---------- 识别表：资源 id → 圆角 dp（与原 shape corners 逐件对应） ----------
    private static final int[] IDS = {
            R.drawable.bg_music_glass_card,
            R.drawable.bg_music_glass_chip,
            R.drawable.bg_music_glass_chip_selected,
            R.drawable.bg_music_glass_circle,
            R.drawable.bg_music_glass_drag,
            R.drawable.bg_music_glass_entry,
            R.drawable.bg_music_glass_panel,
            R.drawable.bg_music_glass_preview,
            R.drawable.bg_music_glass_quick,
            R.drawable.bg_music_glass_row,
            R.drawable.bg_music_glass_row_current,
            R.drawable.bg_music_glass_search,
            R.drawable.bg_music_glass_seg,
            R.drawable.bg_music_glass_white_card,
            R.drawable.card_music,
            R.drawable.card_music_current,
            R.drawable.card_music_glass_24,
            R.drawable.home_bg_search,
            R.drawable.bg_search_capsule,
            R.drawable.bg_pill_overview,
    };
    private static final float[] RADII_DP = {
            16f,   // bg_music_glass_card
            18f,   // bg_music_glass_chip
            18f,   // bg_music_glass_chip_selected（与 chip 同族同角）
            999f,  // bg_music_glass_circle
            12f,   // bg_music_glass_drag
            20f,   // bg_music_glass_entry
            16f,   // bg_music_glass_panel
            14f,   // bg_music_glass_preview
            16f,   // bg_music_glass_quick
            14f,   // bg_music_glass_row
            14f,   // bg_music_glass_row_current（与 row 同族同角）
            24f,   // bg_music_glass_search
            14f,   // bg_music_glass_seg
            20f,   // bg_music_glass_white_card
            18f,   // card_music
            18f,   // card_music_current（与 card_music 同族同角）
            24f,   // card_music_glass_24
            24f,   // home_bg_search
            999f,  // bg_search_capsule（胶囊全圆）
            12f,   // bg_pill_overview
    };

    private static Map<Drawable.ConstantState, Float> sTable; // 懒建（ConstantState → 圆角 dp）
    private static final Set<RecyclerView> sScrollHooked =
            Collections.newSetFromMap(new WeakHashMap<>());

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

    /** 识别表单查：给对照表与自验用（资源数、圆角数须与 IDS/RADII 等长）。 */
    public static int tableSize() {
        return IDS.length;
    }

    /** 单件换玻璃：背景命中识别表才换，返回是否换了。已是玻璃件不重复包。 */
    public static boolean glassifyView(View v) {
        if (v == null) return false;
        Drawable bg = v.getBackground();
        if (bg == null || bg instanceof MusicGlassDrawable) return false;
        Float r = table(v.getContext()).get(bg.getConstantState());
        if (r == null) return false;
        v.setBackground(new MusicGlassDrawable(v.getContext(), r));
        return true;
    }

    /** 单件按指定圆角直换（手接点用：Holder/绑定点已知件型时）。 */
    public static void glassifyView(View v, float radiusDp) {
        if (v == null || v.getBackground() instanceof MusicGlassDrawable) return;
        v.setBackground(new MusicGlassDrawable(v.getContext(), radiusDp));
    }

    /** 消费面契约（未重写页面的既有调用点）：单件同源换玻璃，等同 glassifyView 的识别换。 */
    public static void glassify(View v) {
        glassifyView(v);
    }

    /** 消费面契约（未重写页面的既有调用点）：列表行一框——先按识别表换，未命中按行圆角 14dp 直换。 */
    public static void frameRow(View row) {
        if (row == null || row.getBackground() instanceof MusicGlassDrawable) return;
        if (!glassifyView(row)) glassifyView(row, 14f);
    }

    /** 面板根底：仅顶角玻璃（贴底无缺口），再走树换内件。radiusDp 与原面板 shape 顶角对应。 */
    public static void glassifyPanel(View root, float radiusDp) {
        if (root == null) return;
        root.setBackground(new MusicGlassDrawable(root.getContext(), radiusDp, true));
        if (root instanceof ViewGroup) glassifyChildren((ViewGroup) root);
    }

    /** 整树走一遍：命中识别表的背景全换同源玻璃；RecyclerView 挂滚动监听顶版重裁。 */
    public static void glassifyTree(View root) {
        if (root == null) return;
        glassifyView(root);
        if (root instanceof RecyclerView) hookScroll((RecyclerView) root);
        if (root instanceof ViewGroup) glassifyChildren((ViewGroup) root);
    }

    private static void glassifyChildren(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            glassifyView(c);
            if (c instanceof RecyclerView) hookScroll((RecyclerView) c);
            if (c instanceof ViewGroup) glassifyChildren((ViewGroup) c);
        }
    }

    /** 滚动时宿主位置变了：顶版令全件重裁，再令列表重绘（糊底跟随滚动）。 */
    private static void hookScroll(final RecyclerView rv) {
        synchronized (sScrollHooked) {
            if (!sScrollHooked.add(rv)) return;
        }
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                MusicGlassDrawable.noteScrollChanged();
                recyclerView.invalidate();
            }
        });
    }
}
