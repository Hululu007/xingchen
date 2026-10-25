package com.fongmi.android.tv.ui.music;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

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
            R.drawable.home_bg_card_pink,
            R.drawable.home_bg_card_teal,
            R.drawable.home_bg_card_lime,
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
            20f,   // home_bg_card_pink（三彩卡，与原 shape 同角）
            20f,   // home_bg_card_teal
            20f,   // home_bg_card_lime
    };

    private static Map<Drawable.ConstantState, Float> sTable; // 懒建（ConstantState → 圆角 dp）

    // 选中族（样张口径：选中态是一颗带颜色的玻璃）：这三件的身份是「选中/当前」，
    // 换成同源玻璃件时同步挂 tone 还原带色——色取样张选中青 #305F67 家族，alpha 0.55 读得出色。
    // （XML 下限里它们原是粉色身份；样张定案后以样张青为准，XML 下限未及同步、仅取样失败时短暂可见。）
    private static final int[] TONE_IDS = {
            R.drawable.bg_music_glass_chip_selected,
            R.drawable.bg_music_glass_row_current,
            R.drawable.card_music_current,
    };
    private static final int SELECTED_TONE_COLOR = 0xFF305F67;
    private static final float SELECTED_TONE_ALPHA = 0.55f;

    // 三彩卡（2026-10-25 他点定也做成液态玻璃）：带色玻璃——tone 取各卡渐变中段的身份色，
    // alpha 0.50（大面积卡面，颜色要读得出又留玻璃透底）；卡面是浅粉彩玻璃、深字才看得清，
    // 不走选中族的翻白（「字体一定要看得清」铁律优先），单独登记不翻前景集合。
    private static final int[] CARD_TONE_IDS = {
            R.drawable.home_bg_card_pink,
            R.drawable.home_bg_card_teal,
            R.drawable.home_bg_card_lime,
    };
    private static final int[] CARD_TONE_COLORS = {
            0xFFFFC5DC,   // 粉（猜你喜欢渐变中段）
            0xFFADE9F3,   // 蓝绿（排行榜渐变中段）
            0xFFCFF2B8,   // 绿黄（随心听渐变中段）
    };
    private static final float CARD_TONE_ALPHA = 0.50f;

    private static Map<Drawable.ConstantState, Integer> sToneTable; // 懒建（ConstantState → tone 色）
    private static Map<Drawable.ConstantState, Float> sToneAlphaTable; // 懒建（ConstantState → tone alpha）
    private static java.util.Set<Drawable.ConstantState> sNoFlipTable; // 懒建（换玻璃但不翻前景）

    private static synchronized Map<Drawable.ConstantState, Integer> toneTable(Context ctx) {
        if (sToneTable != null) return sToneTable;
        Map<Drawable.ConstantState, Integer> m = new HashMap<>();
        Map<Drawable.ConstantState, Float> a = new HashMap<>();
        java.util.Set<Drawable.ConstantState> nf = new java.util.HashSet<>();
        for (int id : TONE_IDS) {
            Drawable d = ctx.getResources().getDrawable(id, ctx.getTheme());
            if (d != null && d.getConstantState() != null) {
                m.put(d.getConstantState(), SELECTED_TONE_COLOR);
                a.put(d.getConstantState(), SELECTED_TONE_ALPHA);
            }
        }
        for (int i = 0; i < CARD_TONE_IDS.length; i++) {
            Drawable d = ctx.getResources().getDrawable(CARD_TONE_IDS[i], ctx.getTheme());
            if (d != null && d.getConstantState() != null) {
                m.put(d.getConstantState(), CARD_TONE_COLORS[i]);
                a.put(d.getConstantState(), CARD_TONE_ALPHA);
                nf.add(d.getConstantState());
            }
        }
        sToneTable = m;
        sToneAlphaTable = a;
        sNoFlipTable = nf;
        return m;
    }
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

    /** 单件换玻璃：背景命中识别表才换，返回是否换了。已是玻璃件不重复包。选中族同步挂 tone 成带色玻璃。 */
    public static boolean glassifyView(View v) {
        if (v == null) return false;
        Drawable bg = v.getBackground();
        if (bg == null || bg instanceof MusicGlassDrawable) return false;
        Float r = table(v.getContext()).get(bg.getConstantState());
        if (r == null) return false;
        MusicGlassDrawable glass = new MusicGlassDrawable(v.getContext(), r);
        Integer tone = toneTable(v.getContext()).get(bg.getConstantState());
        if (tone != null) {
            Float ta = sToneAlphaTable != null ? sToneAlphaTable.get(bg.getConstantState()) : null;
            glass.setToneColor(tone, ta != null ? ta : SELECTED_TONE_ALPHA);
        }
        v.setBackground(glass);
        boolean noFlip = sNoFlipTable != null && sNoFlipTable.contains(bg.getConstantState());
        if (!noFlip) flipForeground(v);
        return true;
    }

    /** 单件按指定圆角直换（手接点用：Holder/绑定点已知件型时）。 */
    public static void glassifyView(View v, float radiusDp) {
        if (v == null || v.getBackground() instanceof MusicGlassDrawable) return;
        v.setBackground(new MusicGlassDrawable(v.getContext(), radiusDp));
        flipForeground(v);
    }

    // ---------- 件内前景翻白（样张口径：玻璃件上的字与图标一律白系） ----------
    // 主字 #F1F4F5、图标 #F1F3F5（样张逐区实测）；副字样张实测白 .45，但星辰合成面经
    // WCAG 口径核算 .45→2.46–2.72、.55→tone 面 2.93 均不过 3:1 门，逐级提到 .60 才全过
    // （三面 3.18–3.60，见 w2-glass.md 第八节）。
    // 边界：只翻「已换玻璃底的件」内部；页面底（浅色）上的标题、非玻璃区域文字不动。
    private static final int FG_MAIN = 0xFFF1F4F5;
    private static final int FG_SUB = 0x99FFFFFF; // 白 .60
    private static final int FG_ICON = 0xFFF1F3F5;

    /**
     * 换玻璃成功后同步翻件内前景：子树内最大字号的 TextView 为主字、其余为副字；
     * 自带独立背景的 TextView（角标/小片，有自己的面）跳过不翻；ImageView 只转已有
     * tint 的单色字形图标，无 tint 的全彩图（封面、彩色快捷图标）不动。
     */
    private static void flipForeground(View root) {
        java.util.List<TextView> texts = new java.util.ArrayList<>();
        java.util.List<ImageView> icons = new java.util.ArrayList<>();
        collectForeground(root, texts, icons);
        float max = 0;
        for (TextView t : texts) max = Math.max(max, t.getTextSize());
        for (TextView t : texts) {
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
            if (own == null || own instanceof MusicGlassDrawable) texts.add((TextView) v);
        }
        if (v instanceof ImageView) icons.add((ImageView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectForeground(g.getChildAt(i), texts, icons);
        }
    }

    /** tone 染色接口：v 的背景是玻璃件时把 tone 色交给它平铺（alpha 0.16 在件内），返回是否吃上。 */
    public static boolean setTone(View v, int color) {
        if (v == null) return false;
        Drawable bg = v.getBackground();
        if (bg instanceof MusicGlassDrawable) {
            ((MusicGlassDrawable) bg).setToneColor(color);
            return true;
        }
        return false;
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
        flipForeground(root);
        if (root instanceof ViewGroup) glassifyChildren((ViewGroup) root);
    }

    /** 整树走一遍：命中识别表的背景全换同源玻璃；RecyclerView 挂滚动监听顶版重裁；页根再做漏底结构归一。 */
    public static void glassifyTree(View root) {
        if (root == null) return;
        glassifyView(root);
        if (root instanceof RecyclerView) hookScroll((RecyclerView) root);
        if (root instanceof ViewGroup) glassifyChildren((ViewGroup) root);
        normalizeFitsSkeleton(root);
    }

    /**
     * 漏底页结构归一（共享层一处，各音乐页在 initView 调 glassifyTree 时自动受益）：
     * 根带 fitsSystemWindows 的页，根吃到系统栏 padding 后，直挂根下的背景 View 被压进
     * padding 内侧，状态栏一条只剩根的纯白兜底 home_bg_page 露出来＝漏底；首页根无 fits、
     * 背景直铺整屏、内容层顶距由代码按状态栏高补，是正确样本。
     * 上一版「insets 监听复刻 fits＋按根 padding 给背景负边距」的修法在真机零效果，已整段摘除：
     * 它唯一的活线是监听能读回根 padding，而多数页 glassifyTree 落在 getBinding() 重复
     * inflate 的游离树上（不在显示树上，监听永不触发）；落在真树上的页，读回的根 padding
     * 也从未成为内容定位的那份 padding（内容位置在该版前后分毫未动即为据）。
     * 本法纯静态结构、不依赖任何回调时机，initView 里执行完即成形、首帧就是终态：
     *   1) 根关掉 fits——背景 View 恢复 match_parent 直铺整屏，与首页同构；
     *   2) 内容容器（根下除背景外第一个 match_parent 的 ViewGroup）的让位改由代码补：
     *      paddingTop += 状态栏高、paddingBottom += 导航栏高（系统 dimen，与首页
     *      MusicActivity 的 statusBarHeight() 同一取值口径），内容位置与修前像素级一致。
     * fits 标志本身即一次性门：归一过的根 fits 已关，重复调用直接返回，不会重复补 padding。
     * 骨架对不上（无背景 View／背景非根直挂／找不到内容容器）一概不碰，维持原状。
     */
    private static void normalizeFitsSkeleton(View root) {
        if (!(root instanceof ViewGroup) || !root.getFitsSystemWindows()) return;
        MusicGlassBackgroundView bg = MusicGlassBackgroundView.findInTree(root);
        if (bg == null || bg.getParent() != root) return;
        ViewGroup g = (ViewGroup) root;
        View content = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == bg) continue;
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (c instanceof ViewGroup && lp != null
                    && lp.width == ViewGroup.LayoutParams.MATCH_PARENT
                    && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                content = c;
                break;
            }
        }
        if (content == null) return;
        root.setFitsSystemWindows(false);
        Context ctx = root.getContext();
        int status = systemDimenPx(ctx, "status_bar_height");
        int nav = systemDimenPx(ctx, "navigation_bar_height");
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop() + status,
                content.getPaddingRight(), content.getPaddingBottom() + nav);
    }

    /** 系统栏尺寸（dimen 口径，与首页 statusBarHeight() 同源：状态栏/导航栏高）。 */
    private static int systemDimenPx(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
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
