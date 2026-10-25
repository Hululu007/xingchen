package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;

import java.util.HashMap;
import java.util.Map;

/**
 * 音乐玻璃共用接线：把各页／各行现用的半透填充件（无糊底的 shape 家族）按资源识别后
 * 统一换成同源液态玻璃 MusicGlassDrawable，圆角逐件与原 shape 的 corners 对齐。
 * 识别走 Drawable.ConstantState 同一性（资源装载的 Drawable 共享 ConstantState）——
 * 页面在视图树建好后 glassifyTree 走一遍；列表行在适配器创建行时 glassify(itemView)，
 * 滚动时位置守卫自裁、再加 RecyclerView 滚动监听顶版本号双保险。
 * 已是 MusicGlassDrawable 的跳过（行复用重复绑定安全）；StateList/selector 包裹的识别不了、跳过不误伤。
 */
public final class MusicGlassWire {

    private MusicGlassWire() {
    }

    // 资源 id → 原圆角 dp（与各 shape corners 逐一对齐；oval 圆件记 999 走胶囊圆角）
    private static final int[][] MAP = {
            {R.drawable.bg_music_glass_card, 16},
            {R.drawable.bg_music_glass_chip, 18},
            {R.drawable.bg_music_glass_circle, 999},
            {R.drawable.bg_music_glass_drag, 12},
            {R.drawable.bg_music_glass_entry, 20},
            {R.drawable.bg_music_glass_preview, 14},
            {R.drawable.bg_music_glass_quick, 16},
            {R.drawable.bg_music_glass_search, 24},
            {R.drawable.bg_music_glass_seg, 14},
            {R.drawable.bg_music_glass_white_card, 20},
            {R.drawable.card_music, 18},
            {R.drawable.bg_pill_overview, 12},
            {R.drawable.card_music_glass_24, 24},
            {R.drawable.bg_chart_card, 16},
            {R.drawable.bg_chart_focus, 20},
            {R.drawable.bg_guess_hero, 20},
            {R.drawable.bg_search_capsule, 8},
            {R.drawable.bg_circle_light, 999},
            {R.drawable.xc_setcard_v2, 10},
            {R.drawable.bg_music_quality_row, 14},
            {R.drawable.home_bg_search, 24},
    };

    /** 列表行一框（用户点名：一行一框、不许裸行）：行根统一换液态玻璃框，圆角 18 与原行卡系列对齐；已是玻璃跳过（行复用安全）。 */
    public static void frameRow(View itemView) {
        if (itemView == null || itemView.getBackground() instanceof MusicGlassDrawable) return;
        itemView.setBackground(new MusicGlassDrawable(itemView.getContext(), 18));
    }

    private static volatile Map<Drawable.ConstantState, Integer> sByState;

    private static Map<Drawable.ConstantState, Integer> table(Context ctx) {
        Map<Drawable.ConstantState, Integer> t = sByState;
        if (t == null) {
            synchronized (MusicGlassWire.class) {
                t = sByState;
                if (t == null) {
                    t = new HashMap<>();
                    Context app = ctx.getApplicationContext();
                    for (int[] e : MAP) {
                        Drawable d = ContextCompat.getDrawable(app, e[0]);
                        if (d != null && d.getConstantState() != null) t.put(d.getConstantState(), e[1]);
                    }
                    sByState = t;
                }
            }
        }
        return t;
    }

    /** 单件接线：当前背景是已知半透件才换，已是真玻璃或不认识的原样不动。返回是否换了。 */
    public static boolean glassify(View v) {
        if (v == null) return false;
        Drawable bg = v.getBackground();
        if (bg == null || bg instanceof MusicGlassDrawable || bg.getConstantState() == null) return false;
        Integer radius = table(v.getContext()).get(bg.getConstantState());
        if (radius == null) return false;
        v.setBackground(new MusicGlassDrawable(v.getContext(), radius));
        return true;
    }

    /** 整树接线：页面视图建好后走一遍；树里的 RecyclerView 顺带挂滚动顶版（行内玻璃滚动自裁双保险）。 */
    public static void glassifyTree(View root) {
        if (root == null) return;
        glassify(root);
        if (root instanceof RecyclerView) {
            ((RecyclerView) root).addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView rv, int dx, int dy) {
                    if (dy != 0) MusicGlassBackgroundView.noteScrollChanged();
                }
            });
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) glassifyTree(g.getChildAt(i));
        }
    }
}
