package com.fongmi.android.tv.ui.custom;

/**
 * 唱片架舞台几何（S4）：瓜子 RecordShelfPlayerKt 舞台公式链的纯 Java 转写。
 * 输入：舞台约束宽 dp。瓜子侧该宽是 BoxWithConstraints 的 maxWidth（rj0.c()＝qg1.h＝maxWidth，
 * 由 qg1.k 的 toString 字段序 minWidth/maxWidth/minHeight/maxHeight 钉死）；星辰侧舞台占满屏宽，
 * 故取屏宽 dp，与瓜子约束宽同义。
 * 输出：全部架项几何量皆由宽算出，不写死固定 dp。下列瓜子源码自带固定值除外（非星辰自定）：
 * 舞台钳制 180/300dp、槽位减 36dp、架板高 6dp、袖 shape 圆角 1.5dp、封面本体圆角 2dp、图标 8dp。
 *
 * 瓜子源码出处（jadx 反编译，包 com.gzmusic.app 2.1.8）：
 * 舞台 RecordShelfPlayerKt:537：stage＝clamp(宽×0.66, 180, 300)；ka7.A 已核实为 clamp。
 * 槽位 lambda（RecordShelfPlayerKt:1706–1717）：f2＝min(宽×0.5, stage−36)；封套 f3＝f2×0.95；
 * 中心参考 c＝宽×0.59；非当前缩放 f4＝(f2×0.78)/f3（≈0.821，代码算出，不写死 0.82）。
 * 槽位变换（RecordShelfPlayerKt:1806–1816；ij1.a(f,f2,f3,f4)＝(f−f2)×f3＋f4）：
 * v＝clamp(1−|slot|, 0, 1)；缩放＝f4＋(1−f4)×v；变换原点 (0.5, 1)；
 * 位移 x＝(1−v)×density×f3×0.24×缩放＋density×c×slot；变换内无 alpha 调用（非当前项无压暗）。
 * 封套 ShelfSleeve（RecordShelfPlayerKt:3001 起；舞台调用点:1791 传 f3、i3＝32）：
 * 调用点 i3＝32 置位默认位（i5＝i3&32≠0 → z9＝true，调用处传的 false 只是占位哑值），
 * 故舞台调用点外 Box 宽＝1.48×f3、圆盘分支执行（纠正 round4“倾向 f 一侧”的暂定读法）。
 * 圆盘 ShelfRecord（RecordShelfPlayerKt:2479 起；袖内调用:3306 传 0.97×f3）：本体 0.97×f3、圆形裁剪、阴影 5dp；
 * 中心图 0.22×盘、中心点 0.065×盘与 0.025×盘、图标分支偏移 −0.13×盘、图标尺寸 8dp。
 * 架板与封面层（袖内:3270、:3321–3330）：架板宽 1.1×f3、高 6dp、偏移 (−0.05×f3, f3−2dp)；
 * 封套阴影 4dp、封面层阴影 3dp（同 1.5dp shape）；封面本体圆角 2dp（playerCoverTarget(…, 2.0f)），
 * 与 1.5dp shape 分属两层，不得混为一值。
 *
 * 星辰是横向列表、不是居中舞台，故有三处有据改编（不是偷工，见各处注释）：
 * 1. 槽位位移只取第一项 (1−v)×f3×0.24×缩放：第二项 density×c×slot 是舞台把居中槽位摊开的量，
 *    列表布局本身已按项宽排布，再加会双倍错位，故不取。
 * 2. 槽位变换只在绑定时取端点（当前项 slot＝0→1.0，相邻项 |slot|＝1→f4）：
 *    列表当前项贴左（20dp padding 起排），不是居中舞台，连续 slot＝(子项中心−列表中心)/槽位宽不成立。
 * 3. 外 Box 宽 1.48×f3、架板、盘心图／点、0.18×f3 图标只出系数、不渲染（原因见各常量注释）；
 *    项根宽仍为封套宽，否则列表项距与 B1 弹簧槽位宽（＝封套宽）错位。
 */
public final class ShelfStageGeometry {

    /** 舞台约束宽 dp（瓜子 maxWidth；星辰侧＝屏宽 dp）。 */
    public final float widthDp;
    /** 舞台基准：clamp(宽×0.66, 180, 300)。 */
    public final float stageDp;
    /** 舞台高＝stage−23dp（瓜子舞台 Box 高，RecordShelfPlayerKt:1397 `f - 23.0f`；星辰架列高取此值，不再随项高 wrap_content，否则面顶比瓜子高约 49dp）。 */
    public final float stageHeightDp;
    /** 槽位基尺寸 f2＝min(宽×0.5, stage−36)。 */
    public final float slotBaseDp;
    /** 封套尺寸 f3＝f2×0.95。星辰项根宽、弹簧槽位宽皆取此值。 */
    public final float sleeveDp;
    /** 槽位中心参考 c＝宽×0.59（舞台摊位项，列表不取，见类注释）。 */
    public final float centerRefDp;
    /** 非当前项缩放 f4＝(f2×0.78)/f3。 */
    public final float nonCurrentScale;
    /** 圆盘尺寸＝0.97×封套。 */
    public final float discDp;
    /** 袖外 Box 宽＝1.48×封套（舞台居中布局属性，列表项根不取，见类注释）。 */
    public final float outerWidthDp;
    /** 架板宽＝1.1×封套（板体未渲染，见 BOARD_NOT_RENDERED）。 */
    public final float boardWidthDp;
    /** 架板横向偏移＝−0.05×封套。 */
    public final float boardOffsetXDp;
    /** 架板纵向偏移＝封套−2dp。 */
    public final float boardOffsetYDp;

    /** 槽位位移第一项因子 0.24（星辰侧亦作圆盘右探位移因子，沿用既有映射）。 */
    public static final float PROTRUDE_FACTOR = 0.24f;
    /** 封套 shape 圆角 1.5dp（袖层，与封面本体 2dp 分属两层；星辰单层封面用本体值）。 */
    public static final float SLEEVE_CORNER_DP = 1.5f;
    /** 封面本体圆角 2dp（已应用）。 */
    public static final float COVER_CORNER_DP = 2f;
    /** 封套阴影 4dp（星辰封面 elevation 现用此值）。 */
    public static final float SLEEVE_SHADOW_DP = 4f;
    /** 封面层阴影 3dp（单层封面未分层，未应用）。 */
    public static final float COVER_SHADOW_DP = 3f;
    /**
     * 圆盘阴影 5dp（未用 elevation 应用：Android elevation 会把盘抬到封面之上，
     * 盘面与封面重叠区会被盘盖住；瓜子 Compose 层阴影不争图层顺序，故盘面保持无 elevation、
     * 位于封面之下，只露右探部分）。
     */
    public static final float DISC_SHADOW_DP = 5f;
    /** 架板高 6dp（瓜子源码固定值；板体未渲染，见下）。 */
    public static final float BOARD_HEIGHT_DP = 6f;
    /** 圆盘中心图＝0.22×盘（星辰盘面是封面图、非黑胶纹理＋中心标，不适用）。 */
    public static final float DISC_CENTER_FACTOR = 0.22f;
    /** 圆盘中心点之一＝0.065×盘（同上，不适用）。 */
    public static final float DISC_DOT1_FACTOR = 0.065f;
    /** 圆盘中心点之二＝0.025×盘（同上，不适用）。 */
    public static final float DISC_DOT2_FACTOR = 0.025f;
    /** 圆盘图标分支偏移＝−0.13×盘（同上，不适用）。 */
    public static final float DISC_ICON_OFFSET_FACTOR = -0.13f;
    /** 圆盘图标尺寸 8dp（瓜子源码固定值；同上，不适用）。 */
    public static final float DISC_ICON_SIZE_DP = 8f;
    /**
     * 袖内图标尺寸＝0.18×封套（袖内:3345 一带 si3.a 行，Icon 形态可辨、图标本体 tf2.w() 未识别，
     * 未渲染；编造图标本体即违反等比对齐，故只留系数）。
     */
    public static final float SLEEVE_ICON_FACTOR = 0.18f;
    /**
     * 架板未渲染原因：架板填充色在袖内:3270 一带未定位（er5.t 绘制 lambda 未拆出色值），
     * 以臆测色画板即违反等比对齐；几何量已全部算出，色值定位后方可渲染。
     */
    public static final boolean BOARD_NOT_RENDERED = true;

    private ShelfStageGeometry(float widthDp) {
        this.widthDp = widthDp;
        this.stageDp = Math.max(180f, Math.min(300f, widthDp * 0.66f));
        this.stageHeightDp = stageDp - 23f;
        this.slotBaseDp = Math.min(widthDp * 0.5f, stageDp - 36f);
        this.sleeveDp = slotBaseDp * 0.95f;
        this.centerRefDp = widthDp * 0.59f;
        this.nonCurrentScale = (slotBaseDp * 0.78f) / sleeveDp;
        this.discDp = sleeveDp * 0.97f;
        this.outerWidthDp = sleeveDp * 1.48f;
        this.boardWidthDp = sleeveDp * 1.1f;
        this.boardOffsetXDp = -0.05f * sleeveDp;
        this.boardOffsetYDp = sleeveDp - 2f;
    }

    public static ShelfStageGeometry fromWidthDp(float widthDp) {
        return new ShelfStageGeometry(widthDp);
    }

    /** 槽位连续缩放：v＝clamp(1−|slot|, 0, 1)，缩放＝f4＋(1−f4)×v。 */
    public float scaleForSlot(float slot) {
        float v = Math.max(0f, Math.min(1f, 1f - Math.abs(slot)));
        return nonCurrentScale + (1f - nonCurrentScale) * v;
    }

    /**
     * 槽位位移 dp（列表改编版）：只取瓜子第一项 (1−v)×封套×0.24×缩放；
     * 瓜子第二项 density×c×slot 由列表布局本身承担，不再叠加。
     */
    public float translationXDp(float slot) {
        float v = Math.max(0f, Math.min(1f, 1f - Math.abs(slot)));
        return (1f - v) * sleeveDp * PROTRUDE_FACTOR * scaleForSlot(slot);
    }
}
