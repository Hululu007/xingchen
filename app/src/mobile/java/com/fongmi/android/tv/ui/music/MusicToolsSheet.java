package com.fongmi.android.tv.ui.music;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.utils.ImgUtil;

/**
 * 播放工具面板（瓜子第九节，星辰落地：深色玻璃底，不用白底）：
 * 主面板＝歌曲 Hero＋快捷瓷砖（下载/歌词/定时/加到歌单）＋播放模式四段＋设置卡
 * （歌曲信息／均衡器／播放页主题／外观设置／不熄屏开关／系统音量）；
 * 子面板＝主题（横滑真预览卡＋选中勾）／睡眠定时／封面歌词样式／外观设置／歌曲信息。
 * 付费锁、「一起听」、自定义封面不搬（他定）。
 */
public class MusicToolsSheet {

    public interface Callback {
        void onSettingsChanged();

        void onOpenLyricPage();
    }

    private static final int PANEL_BG = 0xF21C1C1E;
    private static final int CARD_BG = 0xFF2C2C2E;
    private static final int TEXT_HIGH = 0xFFF2F2F7;
    private static final int TEXT_MED = 0x99FFFFFF;
    private static final int TEXT_LOW = 0x99FFFFFF;
    private static final int ACCENT = 0xFFFA243C;

    private final AppCompatActivity mActivity;
    private final Callback mCallback;
    private final float mDensity;
    /** 直达歌词样式子面板开场（全屏歌词页的样式键专用）：返回＝关面板、「全屏歌词」入口行不出现。 */
    private final boolean mStartAtLyricStyle;
    private Dialog mDialog;
    private LinearLayout mPanelBox;
    private boolean mPanelBuilt;
    private Bitmap mCoverBitmap;
    /** 依赖封面的预览视图登记（迷你卡＋全貌预览）：封面异步晚到时逐个 invalidate，
     * 此前 loadCover 回来只存位图、预览恒灰块到重进面板（体检低·主题预览封面竞态）。 */
    private final java.util.List<View> mCoverViews = new java.util.ArrayList<>();

    private MusicToolsSheet(AppCompatActivity activity, Callback cb) {
        this(activity, cb, false);
    }

    private MusicToolsSheet(AppCompatActivity activity, Callback cb, boolean startAtLyricStyle) {
        mActivity = activity;
        mCallback = cb;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mStartAtLyricStyle = startAtLyricStyle;
    }

    public static void show(AppCompatActivity activity, Callback cb) {
        if (activity == null) return;
        new MusicToolsSheet(activity, cb).open();
    }

    /** 全屏歌词页「歌词样式」键专用入口：面板直接开在歌词样式子面板（瓜子歌词样式面板本就在歌词页内）。 */
    public static void showLyricStyleOnly(AppCompatActivity activity, Callback cb) {
        if (activity == null) return;
        new MusicToolsSheet(activity, cb, true).open();
    }

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    private int dp(float v) {
        return (int) (v * mDensity);
    }

    private MusicTrack track() {
        return MusicController.get().current();
    }

    private void open() {
        mDialog = new Dialog(mActivity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        mPanelBox = new LinearLayout(mActivity);
        mPanelBox.setOrientation(LinearLayout.VERTICAL);
        mPanelBox.setPadding(dp(16), dp(8), dp(16), dp(10));
        // 面板本体＝液态玻璃（2026-10-26 他点名「工具面板没做好」：此前整张是深灰实底、没走玻璃）
        mPanelBox.setBackground(new MusicGlassDrawable(mActivity, 16, true));
        mPanelBox.setElevation(dp(10));
        scroll.addView(mPanelBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mDialog.setContentView(scroll);
        if (mDialog.getWindow() != null) {
            mDialog.getWindow().setGravity(Gravity.BOTTOM);
            mDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            mDialog.getWindow().setDimAmount(0.45f);
            // 进出统一：滑上滑下＋淡入淡出 200ms（进出双向，不许只进不出）。
            mDialog.getWindow().setWindowAnimations(R.style.XcBottomSheetSlide);
        }
        loadCover();
        if (mStartAtLyricStyle) showLyricStyle();
        else showMain();
        mDialog.show();
    }

    private void loadCover() {
        MusicTrack t = track();
        if (t == null || TextUtils.isEmpty(t.cover)) return;
        final String want = t.cover;
        ImgUtil.load(want, 160, new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                mCoverBitmap = resource;
                for (View v : mCoverViews) v.invalidate();
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    private void clearPanel() {
        // 子页切换（主页↔主题/睡眠/歌词样式）：整盒淡入新内容 200ms，不再直换硬蹦。
        // 首建不走（面板自身的窗口滑＋淡已管开场）；同帧内先沉到 0、新内容同步建完后
        // post 的淡入才起，不闪旧页也不闪空盒。动画被系统关闭时 fadeIn 直达不透明。
        if (mPanelBuilt) {
            mPanelBox.animate().cancel();
            mPanelBox.setAlpha(0f);
            mPanelBox.post(() -> com.fongmi.android.tv.utils.XcFadeTransition.fadeIn(mPanelBox));
        }
        mPanelBuilt = true;
        mCoverViews.clear();
        mPanelBox.removeAllViews();
        // 拖拽把手
        View handle = new View(mActivity);
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x47FFFFFF);
        hb.setCornerRadius(dp(2));
        handle.setBackground(hb);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(8);
        mPanelBox.addView(handle, hlp);
    }

    // ---------- 通用件 ----------
    // 子页头（2026-10-26 他亲点）：‹ 返回箭头删掉、标题顶到最左边——本方法五个子页共用
    // （播放页主题/睡眠定时/歌词样式/外观设置/歌曲信息），一处删全清；× 关闭钮保留不动，
    // 关面板走 × 或面板外，子页之间不再靠箭头回主面板。onBack 参数保留签名兼容各调用点、
    // 不再接线（各 show* 传入的回主面板动作随箭头一并作废）。
    private LinearLayout subHeader(String title, Runnable onBack) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        TextView tv = new TextView(mActivity);
        tv.setText(title);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(17);
        // 扫描 T5：子面板标题改左对齐（此前 Gravity.CENTER 居中，违他的标题左对齐铁律）
        tv.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tv.setLetterSpacing(-0.3f / 17f);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = circleButton(R.drawable.ic_music_close);
        close.setOnClickListener(v -> mDialog.dismiss());
        row.addView(close);
        return row;
    }

    private ImageView circleButton(int iconRes) {
        ImageView iv = new ImageView(mActivity);
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        iv.setBackground(new MusicGlassDrawable(mActivity, 999));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return iv;
    }

    private TextView sectionLabel(String text, int topMarginDp) {
        TextView tv = new TextView(mActivity);
        tv.setText(text);
        tv.setTextColor(TEXT_MED);
        tv.setTextSize(14);
        tv.setLetterSpacing(0.2f / 14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        lp.leftMargin = dp(4);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView chip(String label, boolean selected) {
        TextView tv = new TextView(mActivity);
        tv.setText(label);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), 0, dp(10), 0);
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xA6FFFFFF);
        tv.setTypeface(selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        MusicGlassDrawable chipBg = new MusicGlassDrawable(mActivity, 8);
        if (selected) chipBg.setToneColor(0xFFFFFF, 0.20f);
        tv.setBackground(chipBg);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        return tv;
    }

    private LinearLayout chipRow(String[] labels, int selectedIdx, java.util.function.IntConsumer onPick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = chip(labels[i], i == selectedIdx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> onPick.accept(idx));
            row.addView(c);
        }
        return row;
    }

    /** 自绘开关（瓜子 SleepToggle：轨道 40×24 圆角 12，开＝AccentRed，关＝白 0.16（深底），钮 20 圆左偏 2）。 */
    static class ToggleView extends View {
        private boolean mOn;
        private float mKnobX; // 0..1
        private android.animation.ValueAnimator mAnim;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private Runnable mOnChange;

        ToggleView(android.content.Context ctx, boolean on) {
            super(ctx);
            mOn = on;
            mKnobX = on ? 1f : 0f;
            setOnClickListener(v -> setOn(!mOn, true));
        }

        void setOnChange(Runnable r) {
            mOnChange = r;
        }

        boolean isOn() {
            return mOn;
        }

        void setOn(boolean on, boolean notify) {
            if (mOn == on) return;
            mOn = on;
            if (mAnim != null) mAnim.cancel();
            final float from = mKnobX;
            mAnim = android.animation.ValueAnimator.ofFloat(from, on ? 1f : 0f);
            mAnim.setDuration(180);
            mAnim.addUpdateListener(a -> {
                mKnobX = (float) a.getAnimatedValue();
                invalidate();
            });
            mAnim.start();
            if (notify && mOnChange != null) mOnChange.run();
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            float d = getResources().getDisplayMetrics().density;
            setMeasuredDimension((int) (40 * d), (int) (24 * d));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            // 开关也走液态玻璃口径（他的开关全玻璃令）：半透色面＋整圈亮边＋顶部流光，不再实色填充
            android.graphics.RectF rf = new android.graphics.RectF(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(mOn ? 0x59FA243C : 0x29FFFFFF);
            canvas.drawRoundRect(rf, h / 2f, h / 2f, mPaint);
            mPaint.setStyle(android.graphics.Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, 0.75f * d));
            mPaint.setColor(mOn ? 0x66FFFFFF : 0x40FFFFFF);
            canvas.drawRoundRect(rf, h / 2f, h / 2f, mPaint);
            mPaint.setStyle(android.graphics.Paint.Style.FILL);
            mPaint.setColor(0x30FFFFFF);
            android.graphics.RectF sheen = new android.graphics.RectF(3 * d, 1.5f * d, w - 3 * d, h * 0.42f);
            canvas.drawRoundRect(sheen, h * 0.21f, h * 0.21f, mPaint);
            float knob = 20 * d;
            float x = 2 * d + mKnobX * (w - knob - 4 * d);
            mPaint.setColor(0x33000000);
            canvas.drawCircle(x + knob / 2f, h / 2f + 1 * d, knob / 2f, mPaint);
            mPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(x + knob / 2f, h / 2f, knob / 2f, mPaint);
        }
    }

    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new MusicGlassDrawable(mActivity, 16));
        MusicGlassWire.applyFloatShadow(card, 16);
        return card;
    }

    private View divider() {
        View v = new View(mActivity);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout settingsRow(String title, String tail, Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(12), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        TextView tv = new TextView(mActivity);
        tv.setText(title);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(15);
        tv.setMaxLines(1);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (tail != null) {
            TextView tailTv = new TextView(mActivity);
            tailTv.setText(tail);
            tailTv.setTextColor(TEXT_LOW);
            tailTv.setTextSize(12);
            row.addView(tailTv);
        }
        ImageView chev = new ImageView(mActivity);
        chev.setImageResource(R.drawable.ic_music_chevron);
        chev.setColorFilter(TEXT_LOW);
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(chev);
        if (onClick != null) row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private LinearLayout toggleRow(String title, boolean on, Runnable onToggle) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        TextView tv = new TextView(mActivity);
        tv.setText(title);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(15);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ToggleView toggle = new ToggleView(mActivity, on);
        toggle.setOnChange(onToggle);
        row.addView(toggle);
        return row;
    }

    // ---------- 主面板 ----------
    private void showMain() {
        clearPanel();
        MusicTrack t = track();

        // SongInfoHero
        LinearLayout hero = new LinearLayout(mActivity);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(4), dp(6), dp(4), dp(10));
        ImageView cover = new ImageView(mActivity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(0x1F8E8E93);
        cb.setCornerRadius(dp(12));
        cover.setBackground(cb);
        if (t != null && !TextUtils.isEmpty(t.cover)) ImgUtil.loadFade(t.displayTitle(), t.cover, cover);
        hero.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout heroText = new LinearLayout(mActivity);
        heroText.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mActivity);
        name.setText(t == null ? "未在播放" : t.displayTitle());
        name.setTextColor(TEXT_HIGH);
        name.setTextSize(16);
        name.setLetterSpacing(-0.3f / 16f);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        heroText.addView(name);
        TextView artist = new TextView(mActivity);
        artist.setText(t == null ? "" : t.displayArtist());
        artist.setTextColor(TEXT_MED);
        artist.setTextSize(12);
        artist.setMaxLines(1);
        heroText.addView(artist);
        LinearLayout.LayoutParams htlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        htlp.leftMargin = dp(12);
        hero.addView(heroText, htlp);
        mPanelBox.addView(hero);

        // 快捷瓷砖（下载/歌词/定时/加到歌单）
        LinearLayout tiles = new LinearLayout(mActivity);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(tile(R.drawable.ic_music_download, "下载", () -> {
            if (t != null) {
                // 扫描 M27：按受理结果如实报——本地歌不下载、每次询问模式不入队，
                // 此前无条件弹「已加入下载」是假报（那两条路 download() 直接返回）
                int r = MusicDownloadStore.download(t);
                if (r == MusicDownloadStore.DL_ACCEPTED) {
                    Toast.makeText(mActivity, "已加入下载", Toast.LENGTH_SHORT).show();
                } else if (r == MusicDownloadStore.DL_LOCAL) {
                    Toast.makeText(mActivity, "本地歌曲无需下载", Toast.LENGTH_SHORT).show();
                }
                // DL_ASK：download() 已发「去列表选档」提示，此处不再叠报
            }
        }));
        View tile2 = tile(R.drawable.ic_lyric_wave, "歌词", this::showLyricStyle);
        ((LinearLayout.LayoutParams) tile2.getLayoutParams()).leftMargin = dp(8);
        tiles.addView(tile2);
        View tile3 = tile(R.drawable.ic_music_timer, "定时", this::showSleep);
        ((LinearLayout.LayoutParams) tile3.getLayoutParams()).leftMargin = dp(8);
        tiles.addView(tile3);
        View tile4 = tile(R.drawable.ic_music_playlist_add, "加歌单", () -> {
            if (t != null) MusicPlaylistPicker.show(mActivity, t);
        });
        ((LinearLayout.LayoutParams) tile4.getLayoutParams()).leftMargin = dp(8);
        tiles.addView(tile4);
        mPanelBox.addView(tiles);

        // 播放模式四段
        LinearLayout segBox = new LinearLayout(mActivity);
        segBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(14);
        segBox.setLayoutParams(sblp);
        segBox.addView(buildModeSegmented());
        mPanelBox.addView(segBox);

        // 设置卡
        LinearLayout card = cardBox();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(20);
        card.setLayoutParams(clp);
        card.addView(settingsRow("歌曲信息", null, this::showSongInfo));
        card.addView(divider());
        card.addView(buildToolsGrid());
        card.addView(divider());
        card.addView(toggleRow("播放页不熄屏", MusicStore.isPlayerKeepScreenOn(), () -> {
            MusicStore.setPlayerKeepScreenOn(!MusicStore.isPlayerKeepScreenOn());
            if (mCallback != null) mCallback.onSettingsChanged();
        }));
        card.addView(divider());
        card.addView(buildVolumeRow());
        mPanelBox.addView(card);
    }

    private View tile(int iconRes, String label, Runnable onClick) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.setLayoutParams(blp);
        FrameLayout iconBox = new FrameLayout(mActivity);
        iconBox.setBackground(new MusicGlassDrawable(mActivity, 16));
        MusicGlassWire.applyFloatShadow(iconBox, 16);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        ImageView iv = new ImageView(mActivity);
        iv.setImageResource(iconRes);
        iv.setColorFilter(TEXT_HIGH);
        FrameLayout.LayoutParams ivlp = new FrameLayout.LayoutParams(dp(26), dp(26));
        ivlp.gravity = Gravity.CENTER;
        iconBox.addView(iv, ivlp);
        box.addView(iconBox);
        TextView tv = new TextView(mActivity);
        tv.setText(label);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        box.addView(tv, tlp);
        box.setOnClickListener(v -> {
            v.setScaleX(0.96f);
            v.setScaleY(0.96f);
            v.postDelayed(() -> {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }, 150);
            onClick.run();
        });
        return box;
    }

    /** 功能网格（瓜子 SettingsListCard 内的 ToolsGridEntry：两列、高 48、图标 20、标题 13sp）。
     *  此前把均衡器/主题/外观做成整行列表、还多塞一行歌词样式，与瓜子构成对不上（2026-10-26 他点名布局有问题）。 */
    private LinearLayout buildToolsGrid() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(mActivity);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(gridEntry(R.drawable.ic_music_eq, "均衡器",
                // 体检中2「同名两路」收口：此前走 MusicController.openEqualizer 的系统
                // 均衡器 Intent，与设置页的 App 内 MusicFxDialog 是两套；无系统面板的
                // 机型直接失败。统一到 App 内面板（与 E 片 P7 设置侧口径一致）；
                // 本类在 ui.music 包、MusicFxDialog 在 ui.dialog 包，同模块直调。
                () -> com.fongmi.android.tv.ui.dialog.MusicFxDialog.show(mActivity)));
        row1.addView(gridEntry(R.drawable.ic_player_grid, "播放页主题", this::showTheme));
        box.addView(row1);
        LinearLayout row2 = new LinearLayout(mActivity);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(8);
        row2.setLayoutParams(r2lp);
        row2.addView(gridEntry(R.drawable.ic_music_palette, "外观设置", this::showAppearance));
        row2.addView(new View(mActivity), new LinearLayout.LayoutParams(0, dp(48), 1f));
        box.addView(row2);
        return box;
    }

    private LinearLayout gridEntry(int iconRes, String label, Runnable onClick) {
        LinearLayout cell = new LinearLayout(mActivity);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(16), dp(12), dp(16), dp(12));
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageView iv = new ImageView(mActivity);
        iv.setImageResource(iconRes);
        iv.setColorFilter(TEXT_HIGH);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView tv = new TextView(mActivity);
        tv.setText(label);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(13);
        tv.setMaxLines(1);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(8);
        cell.addView(tv, tlp);
        cell.setOnClickListener(v -> {
            v.setScaleX(0.98f);
            v.setScaleY(0.98f);
            v.postDelayed(() -> {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }, 150);
            onClick.run();
        });
        return cell;
    }

    private static final int[] SEG_MODES = {
            MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
            MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
    private static final String[] SEG_LABELS = {"顺序", "列表循环", "单曲", "随机"};

    private View buildModeSegmented() {
        FrameLayout box = new FrameLayout(mActivity);
        box.setBackground(new MusicGlassDrawable(mActivity, 14));
        MusicGlassWire.applyFloatShadow(box, 14);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        final View indicator = new View(mActivity);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0x24FFFFFF);
        ib.setCornerRadius(dp(12));
        indicator.setBackground(ib);
        box.addView(indicator, new FrameLayout.LayoutParams(0, 0));
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int cur = MusicController.get().getMode();
        int sel = 0;
        for (int i = 0; i < SEG_MODES.length; i++) if (SEG_MODES[i] == cur) sel = i;
        final TextView[] labels = new TextView[SEG_LABELS.length];
        for (int i = 0; i < SEG_LABELS.length; i++) {
            final int idx = i;
            TextView tv = new TextView(mActivity);
            tv.setText(SEG_LABELS[i]);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(i == sel ? TEXT_HIGH : TEXT_MED);
            tv.setTypeface(i == sel ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            labels[i] = tv;
            tv.setOnClickListener(v -> {
                MusicController.get().setMode(SEG_MODES[idx]);
                placeIndicator(box, indicator, idx, true);
                for (int j = 0; j < labels.length; j++) {
                    labels[j].setTextColor(j == idx ? TEXT_HIGH : TEXT_MED);
                    labels[j].setTypeface(j == idx ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                }
            });
        }
        final int selF = sel;
        box.post(() -> placeIndicator(box, indicator, selF, false));
        return box;
    }

    private void placeIndicator(FrameLayout box, View indicator, int idx, boolean animate) {
        int w = box.getWidth();
        if (w <= 0) return;
        int segW = w / SEG_LABELS.length;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
        lp.width = segW - dp(4);
        lp.height = box.getHeight() - dp(4);
        lp.leftMargin = idx * segW + dp(2);
        lp.topMargin = dp(2);
        indicator.setLayoutParams(lp);
        if (animate) {
            indicator.setScaleX(0.92f);
            indicator.animate().scaleX(1f).setDuration(250).start();
        }
    }

    private View buildVolumeRow() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        ImageView iv = new ImageView(mActivity);
        iv.setImageResource(R.drawable.ic_widget_volume_high);
        iv.setColorFilter(TEXT_MED);
        row.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        AudioManager am = (AudioManager) mActivity.getSystemService(android.content.Context.AUDIO_SERVICE);
        int max = am == null ? 100 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
        TextView pct = new TextView(mActivity);
        pct.setTextColor(TEXT_MED);
        pct.setTextSize(12);
        pct.setGravity(Gravity.END);
        SeekBar bar = new SeekBar(mActivity);
        bar.setMax(max);
        bar.setProgress(cur);
        pct.setText(max > 0 ? Math.round(cur * 100f / max) + "%" : "0%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && am != null) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    pct.setText(max > 0 ? Math.round(progress * 100f / max) + "%" : "0%");
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        blp.leftMargin = dp(12);
        row.addView(bar, blp);
        row.addView(pct, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ---------- 主题子面板 ----------
    // 2026-10-27 批二：五主题全部建成，名表放行五张卡（流光 CD 三变体同属一张卡，
    // 变体在 CD 页内表面键切换）。付费锁不搬：瓜子侧封面卡片/Apple 带锁是它的付费墙，
    // 他令「只抄好的、付费墙不搬」——星辰五卡一律可用、不带锁徽。
    private static final String[] THEME_IDS = {
            MusicPlayerSettings.THEME_CLASSIC,
            MusicPlayerSettings.THEME_COVER_IMMERSIVE,
            MusicPlayerSettings.THEME_APPLE_MUSIC,
            MusicPlayerSettings.THEME_LUMINOUS_CD,
            MusicPlayerSettings.THEME_RECORD_SHELF};
    private static final String[] THEME_NAMES = {"经典", "封面卡片", "Apple Music", "流光 CD", "黑胶唱片架"};

    private static String themeName(String id) {
        if (MusicPlayerSettings.isCdTheme(id)) return "流光 CD";
        for (int i = 0; i < THEME_IDS.length; i++) if (THEME_IDS[i].equals(id)) return THEME_NAMES[i];
        return "经典";
    }

    /** 已真正落地的主题才进面板（他的「不摆即将上线占位」令）：批二五主题已全部落地。 */
    private static final String[] BUILT_THEME_IDS = THEME_IDS;

    /** 面板卡与存档的对应：CD 卡认三个变体 id，其余一对一。 */
    private static boolean themeCardIsCurrent(String cardId, String current) {
        if (cardId.equals(current)) return true;
        return MusicPlayerSettings.THEME_LUMINOUS_CD.equals(cardId)
                && MusicPlayerSettings.isCdTheme(current);
    }

    private void showTheme() {
        clearPanel();
        mPanelBox.addView(subHeader("播放页主题", this::showMain));
        // 瓜子原面板有一行说明字，他忌介绍文字，不摆（同加歌单面板口径）

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(mActivity);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String cur = MusicPlayerSettings.getTheme();
        for (int i = 0; i < BUILT_THEME_IDS.length; i++) {
            final String id = BUILT_THEME_IDS[i];
            LinearLayout card = new LinearLayout(mActivity);
            card.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = dp(12);
            card.setLayoutParams(clp);
            card.setBackground(new MusicGlassDrawable(mActivity, 16));
            card.setPadding(dp(8), dp(8), dp(8), dp(4));

            FrameLayout previewBox = new FrameLayout(mActivity);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            ThemePreviewView preview = new ThemePreviewView(mActivity, id, () -> mCoverBitmap, this::track);
            mCoverViews.add(preview);
            previewBox.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (themeCardIsCurrent(id, cur)) {
                ImageView check = new ImageView(mActivity);
                check.setImageResource(R.drawable.ic_music_check_14);
                check.setColorFilter(0xFF1C1C1E);
                check.setPadding(dp(3), dp(3), dp(3), dp(3));
                GradientDrawable ck = new GradientDrawable();
                ck.setShape(GradientDrawable.OVAL);
                ck.setColor(TEXT_HIGH);
                check.setBackground(ck);
                FrameLayout.LayoutParams cklp = new FrameLayout.LayoutParams(dp(20), dp(20));
                cklp.gravity = Gravity.TOP | Gravity.END;
                previewBox.addView(check, cklp);
            }
            card.addView(previewBox);
            TextView nm = new TextView(mActivity);
            nm.setText(themeName(id));
            nm.setTextColor(TEXT_HIGH);
            nm.setTextSize(12);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(8), 0, dp(9));
            card.addView(nm);
            card.setOnClickListener(v -> showThemePreview(id));
            row.addView(card);
        }
        hs.addView(row);
        mPanelBox.addView(hs);
    }

    /**
     * 主题全貌预览（2026-10-26 他亲点：小缩略卡看不清，要看整页再决定）：
     * 瓜子源码定根——ThemePanel 点卡是直接应用（ThemePanel$lambda 回调即 setPlayerTheme）、
     * 卡内只有 previewRes 静态整页图（有图时）或 ThemeMiniPreview 活画迷你卡，没有先预览
     * 后应用这一层；本面按他的口径补：点卡先进整页预览（用当前在播的歌真渲染该主题的
     * 整页样子：真封面/真歌名歌手/真进度），看完点「应用」才写存档，点「返回」不改。
     * 机制主题无关：渲染走 ThemeFullPreviewView 按主题 id 分发，批二新主题建成时
     * 只在该视图加分支（或拆渲染器），本预览/确认流程不用再动。
     */
    private void showThemePreview(final String id) {
        clearPanel();
        mPanelBox.addView(subHeader(themeName(id), this::showTheme));
        FrameLayout box = new FrameLayout(mActivity);
        box.setBackground(new MusicGlassDrawable(mActivity, 16));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(430));
        blp.topMargin = dp(10);
        box.setLayoutParams(blp);
        ThemeFullPreviewView full = new ThemeFullPreviewView(mActivity, id, () -> mCoverBitmap, this::track);
        mCoverViews.add(full);
        box.addView(full, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPanelBox.addView(box);

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, dp(2));
        boolean isCur = themeCardIsCurrent(id, MusicPlayerSettings.getTheme());
        TextView apply = new TextView(mActivity);
        apply.setText(isCur ? "正在使用" : "应用这个主题");
        apply.setTextColor(0xFFFFFFFF);
        apply.setTextSize(13);
        apply.setGravity(Gravity.CENTER);
        apply.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(isCur ? 0x33FFFFFF : ACCENT);
        ab.setCornerRadius(dp(10));
        apply.setBackground(ab);
        if (!isCur) {
            apply.setOnClickListener(v -> {
                MusicPlayerSettings.setTheme(id);
                if (mCallback != null) mCallback.onSettingsChanged();
                showTheme();
            });
        }
        actions.addView(apply, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = new TextView(mActivity);
        back.setText("返回");
        back.setTextColor(TEXT_HIGH);
        back.setTextSize(13);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(14), dp(10), dp(14), dp(10));
        back.setBackground(new MusicGlassDrawable(mActivity, 10));
        LinearLayout.LayoutParams bklp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bklp.leftMargin = dp(8);
        actions.addView(back, bklp);
        back.setOnClickListener(v -> showTheme());
        mPanelBox.addView(actions);
    }

    /**
     * 主题整页真预览：以当前在播歌曲按该主题的整页构成渲染（设计宽 360 等比缩放）。
     * 经典分支＝播放页经典构成（氛围底＋大封面＋歌名/歌手＋一行真歌词＋进度线与真进度）；
     * 其余主题分支随批二建成逐个补（未建成的 id 不会进面板，见 BUILT_THEME_IDS）。
     */
    static class ThemeFullPreviewView extends View {
        interface TrackSupplier {
            MusicTrack get();
        }

        private final String mTheme;
        private final ThemePreviewView.CoverSupplier mCover;
        private final TrackSupplier mTrackSupplier;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Paint mText = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ThemeFullPreviewView(android.content.Context ctx, String theme,
                             ThemePreviewView.CoverSupplier cover, TrackSupplier trackSupplier) {
            super(ctx);
            mTheme = theme;
            mCover = cover;
            mTrackSupplier = trackSupplier;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float u = w / 360f; // 设计单位（px/设计dp）
            Bitmap cover = mCover.get();
            MusicTrack t = mTrackSupplier.get();
            // 非经典主题走各自版式的整页真渲染（批二）
            if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(mTheme)) {
                drawCoverThemeFull(canvas, w, h, u, cover, t);
                return;
            }
            if (MusicPlayerSettings.THEME_APPLE_MUSIC.equals(mTheme)) {
                drawAppleFull(canvas, w, h, u, cover, t);
                return;
            }
            if (MusicPlayerSettings.isCdTheme(mTheme)) {
                drawCdFull(canvas, w, h, u, cover, t);
                return;
            }
            if (MusicPlayerSettings.THEME_RECORD_SHELF.equals(mTheme)) {
                drawShelfFull(canvas, w, h, u, cover, t);
                return;
            }
            // 氛围底：深底＋封面整幅低透垫底（经典页封面氛围同构的简化真渲染）
            mPaint.setColor(0xFF1C1C1E);
            canvas.drawRect(0, 0, w, h, mPaint);
            if (cover != null && !cover.isRecycled()) {
                mPaint.setAlpha(46);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(0, 0, w, h), mPaint);
                mPaint.setAlpha(255);
            }
            float margin = 28 * u;
            float coverSide = w - margin * 2;
            float coverTop = 20 * u;
            if (cover != null && !cover.isRecycled()) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(margin, coverTop, margin + coverSide, coverTop + coverSide),
                        8 * u, 8 * u, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(
                        margin, coverTop, margin + coverSide, coverTop + coverSide), mPaint);
                canvas.restore();
            } else {
                mPaint.setColor(0xFF3A3A3C);
                canvas.drawRoundRect(new android.graphics.RectF(margin, coverTop, margin + coverSide, coverTop + coverSide),
                        8 * u, 8 * u, mPaint);
            }
            float y = coverTop + coverSide + 34 * u;
            String title = t == null ? "未在播放" : t.displayTitle();
            drawLine(canvas, title, margin, y, 17 * u, 0xFFF2F2F7, true, w - margin);
            y += 24 * u;
            drawLine(canvas, t == null ? "" : t.displayArtist(), margin, y, 13 * u, 0x73FFFFFF, false, w - margin);
            // 一行真歌词（缓存有词取首行正文，没词留空不编）
            String lyricLine = "";
            if (t != null) {
                try {
                    com.fongmi.android.tv.bean.music.MusicLyric lyric =
                            com.fongmi.android.tv.music.MusicLyricCache.get(t);
                    if (lyric != null && lyric.rawLrc != null) {
                        for (String raw : lyric.rawLrc.split("\n")) {
                            String s = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
                            if (!s.isEmpty()) {
                                lyricLine = s;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!lyricLine.isEmpty()) {
                y += 40 * u;
                drawLine(canvas, lyricLine, margin, y, 15 * u, 0xD9FFFFFF, false, w - margin);
            }
            // 进度线（真进度/真时长，控制器现读）
            long pos = 0, dur = 0;
            try {
                pos = MusicController.get().position();
                dur = MusicController.get().duration();
            } catch (Throwable ignored) {
            }
            float barY = h - 46 * u;
            mPaint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(new android.graphics.RectF(margin, barY, w - margin, barY + 3 * u),
                    1.5f * u, 1.5f * u, mPaint);
            if (dur > 0 && pos > 0) {
                float frac = Math.max(0f, Math.min(1f, pos * 1f / dur));
                mPaint.setColor(0x73FFFFFF);
                canvas.drawRoundRect(new android.graphics.RectF(margin, barY,
                        margin + (w - margin * 2) * frac, barY + 3 * u), 1.5f * u, 1.5f * u, mPaint);
            }
            mPaint.setColor(0xFFFFFFFF);
        }

        private float progressFrac() {
            try {
                long pos = MusicController.get().position();
                long dur = MusicController.get().duration();
                return dur > 0 ? Math.max(0f, Math.min(1f, pos * 1f / dur)) : 0f;
            } catch (Throwable ignored) {
                return 0f;
            }
        }

        private java.util.List<String> firstLyricLines(MusicTrack t, int n) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (t == null) return out;
            try {
                com.fongmi.android.tv.bean.music.MusicLyric lyric =
                        com.fongmi.android.tv.music.MusicLyricCache.get(t);
                if (lyric != null && lyric.rawLrc != null) {
                    for (String raw : lyric.rawLrc.split("\n")) {
                        String s = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
                        if (!s.isEmpty()) {
                            out.add(s);
                            if (out.size() >= n) break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return out;
        }

        private void drawCoverClipped(Canvas canvas, Bitmap cover, float l, float t,
                                      float w, float h, float radius, int fallback) {
            if (cover != null && !cover.isRecycled()) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(l, t, l + w, t + h),
                        radius, radius, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(l, t, l + w, t + h), mPaint);
                canvas.restore();
            } else {
                mPaint.setColor(fallback);
                canvas.drawRoundRect(new android.graphics.RectF(l, t, l + w, t + h),
                        radius, radius, mPaint);
            }
        }

        private void drawCentered(Canvas canvas, String text, float cx, float baseline,
                                  float size, int color, boolean bold, float maxW) {
            if (text == null) text = "";
            mText.setColor(color);
            mText.setTextSize(size);
            mText.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            if (maxW > 0 && mText.measureText(text) > maxW) {
                while (text.length() > 1 && mText.measureText(text + "…") > maxW) {
                    text = text.substring(0, text.length() - 1);
                }
                text = text + "…";
            }
            canvas.drawText(text, cx - mText.measureText(text) / 2f, baseline, mText);
        }

        private void drawPlayTri(Canvas canvas, float cx, float cy, float r, int color) {
            mPaint.setColor(color);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - r * 0.7f, cy - r);
            p.lineTo(cx - r * 0.7f, cy + r);
            p.lineTo(cx + r, cy);
            p.close();
            canvas.drawPath(p, mPaint);
        }

        private void drawSeek(Canvas canvas, float l, float r, float y, float thick,
                              int baseColor, int fillColor, float frac) {
            mPaint.setColor(baseColor);
            canvas.drawRoundRect(new android.graphics.RectF(l, y - thick / 2f, r, y + thick / 2f),
                    thick / 2f, thick / 2f, mPaint);
            if (frac > 0f) {
                mPaint.setColor(fillColor);
                canvas.drawRoundRect(new android.graphics.RectF(l, y - thick / 2f,
                        l + (r - l) * frac, y + thick / 2f), thick / 2f, thick / 2f, mPaint);
            }
        }

        /** 封面卡片整页：顶部歌名歌手＋宽卡（0.65）＋底部玻璃条（真歌词行＋播放键）。 */
        private void drawCoverThemeFull(Canvas canvas, float w, float h, float u,
                                        Bitmap cover, MusicTrack t) {
            float designH = h / u;
            mPaint.setColor(0xFF1C1C1E);
            canvas.drawRect(0, 0, w, h, mPaint);
            if (cover != null && !cover.isRecycled()) {
                mPaint.setAlpha(40);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(0, 0, w, h), mPaint);
                mPaint.setAlpha(255);
            }
            float margin = 28 * u;
            drawLine(canvas, t == null ? "未在播放" : t.displayTitle(), margin, 66 * u,
                    20 * u, 0xF5FFFFFF, true, w - margin);
            drawLine(canvas, t == null ? "" : t.displayArtist(), margin, 92 * u,
                    14 * u, 0xA6FFFFFF, false, w - margin);
            float cardW = Math.min((360 - 48) * 0.98f, (designH - 260) / 0.65f) * u;
            float cardH = cardW * 0.65f;
            float cardL = (w - cardW) / 2f;
            float cardT = 130 * u;
            drawCoverClipped(canvas, cover, cardL + 8 * u, cardT + 10 * u, cardW, cardH,
                    22 * u, 0xFF2C2C2E);
            mPaint.setColor(0xFF3A3A3C);
            // 错位卡在后（先画占位再画主卡的顺序在画布上以偏移呈现）
            drawCoverClipped(canvas, cover, cardL, cardT, cardW, cardH, 22 * u, 0xFF3A3A3C);
            float barY = h - 74 * u;
            mPaint.setColor(0xFF1E1E22);
            canvas.drawRoundRect(new android.graphics.RectF(24 * u, barY, w - 24 * u, barY + 48 * u),
                    22 * u, 22 * u, mPaint);
            mPaint.setColor(0x30FFFFFF);
            canvas.drawRoundRect(new android.graphics.RectF(24 * u, barY, w - 24 * u, barY + 48 * u),
                    22 * u, 22 * u, mPaint);
            java.util.List<String> lines = firstLyricLines(t, 1);
            drawCentered(canvas, lines.isEmpty() ? "" : lines.get(0), w / 2f, barY + 30 * u,
                    13 * u, 0xEBFFFFFF, false, w - 140 * u);
            drawPlayTri(canvas, w - 52 * u, barY + 24 * u, 9 * u, 0xFFFFFFFF);
        }

        /** Apple 整页：封面铺满上半＋标题列＋粗进度＋运输圆键＋功能点。 */
        private void drawAppleFull(Canvas canvas, float w, float h, float u,
                                   Bitmap cover, MusicTrack t) {
            mPaint.setColor(0xFF1C1C1E);
            canvas.drawRect(0, 0, w, h, mPaint);
            float coverH = h * 0.52f;
            if (cover != null && !cover.isRecycled()) {
                canvas.drawBitmap(cover, null, new android.graphics.RectF(0, 0, w, coverH), mPaint);
            } else {
                mPaint.setColor(0xFF3A3A3C);
                canvas.drawRect(0, 0, w, coverH, mPaint);
            }
            float margin = 24 * u;
            float y = coverH + 26 * u;
            drawLine(canvas, t == null ? "未在播放" : t.displayTitle(), margin, y,
                    20 * u, 0xF5FFFFFF, true, w - 110 * u);
            drawLine(canvas, t == null ? "" : t.displayArtist(), margin, y + 24 * u,
                    14 * u, 0x99FFFFFF, false, w - margin);
            drawSeek(canvas, margin, w - margin, h - 128 * u, 6 * u,
                    0x24FFFFFF, 0xE6FFFFFF, progressFrac());
            float cy = h - 82 * u;
            mPaint.setColor(0xFFF2F2F7);
            android.graphics.Path prev = new android.graphics.Path();
            prev.moveTo(w / 2f - 62 * u, cy);
            prev.lineTo(w / 2f - 44 * u, cy - 11 * u);
            prev.lineTo(w / 2f - 44 * u, cy + 11 * u);
            prev.close();
            canvas.drawPath(prev, mPaint);
            android.graphics.Path next = new android.graphics.Path();
            next.moveTo(w / 2f + 62 * u, cy);
            next.lineTo(w / 2f + 44 * u, cy - 11 * u);
            next.lineTo(w / 2f + 44 * u, cy + 11 * u);
            next.close();
            canvas.drawPath(next, mPaint);
            drawPlayTri(canvas, w / 2f, cy, 17 * u, 0xFFF2F2F7);
            mPaint.setColor(0x80FFFFFF);
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(w / 2f + i * 72 * u, h - 26 * u, 5 * u, mPaint);
            }
        }

        /** 流光 CD 整页（浅档 Pearl）：色板渐变底＋盘面＋居中歌名歌手＋细进度＋运输行。 */
        private void drawCdFull(Canvas canvas, float w, float h, float u,
                                Bitmap cover, MusicTrack t) {
            boolean dark = MusicPlayerSettings.THEME_LUMINOUS_CD_DARK.equals(mTheme);
            int ink = dark ? 0xFFF2F2F7 : 0xFF282D36;
            int muted = dark ? 0xFF8E96A3 : 0xFF747D89;
            mPaint.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    dark ? 0xFF14161C : 0xFFF4F2EE, dark ? 0xFF14161C : 0xFFE9EFF5,
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
            float r = 108 * u;
            float cx = w / 2f, cy = 150 * u;
            mPaint.setColor(dark ? 0xFF2A2D36 : 0xFFE3DED4);
            canvas.drawCircle(cx, cy, r, mPaint);
            if (cover != null && !cover.isRecycled()) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(
                                cx - r * 0.89f, cy - r * 0.89f, cx + r * 0.89f, cy + r * 0.89f),
                        r * 0.045f, r * 0.045f, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(
                        cx - r, cy - r, cx + r, cy + r), mPaint);
                canvas.restore();
            }
            mPaint.setColor(dark ? 0xFF14161C : 0xFFF4F2EE);
            canvas.drawCircle(cx, cy, r * 0.07f, mPaint);
            drawCentered(canvas, t == null ? "未在播放" : t.displayTitle(), cx, cy + r + 36 * u,
                    15 * u, ink, true, w - 60 * u);
            drawCentered(canvas, t == null ? "" : t.displayArtist(), cx, cy + r + 56 * u,
                    11 * u, muted, false, w - 60 * u);
            java.util.List<String> lines = firstLyricLines(t, 1);
            drawCentered(canvas, lines.isEmpty() ? "" : lines.get(0), cx, cy + r + 92 * u,
                    14 * u, ink, false, w - 80 * u);
            drawSeek(canvas, 24 * u, w - 24 * u, h - 108 * u, 2 * u,
                    dark ? 0x1AFFFFFF : 0x1A282D36, ink, progressFrac());
            float ty = h - 56 * u;
            android.graphics.Path prev = new android.graphics.Path();
            prev.moveTo(cx - 66 * u, ty);
            prev.lineTo(cx - 50 * u, ty - 10 * u);
            prev.lineTo(cx - 50 * u, ty + 10 * u);
            prev.close();
            mPaint.setColor(ink);
            canvas.drawPath(prev, mPaint);
            android.graphics.Path next = new android.graphics.Path();
            next.moveTo(cx + 66 * u, ty);
            next.lineTo(cx + 50 * u, ty - 10 * u);
            next.lineTo(cx + 50 * u, ty + 10 * u);
            next.close();
            canvas.drawPath(next, mPaint);
            drawPlayTri(canvas, cx, ty, 15 * u, ink);
        }

        /** 唱片架整页：纸底＋居中标题＋袖套黑胶＋白色歌词面（真歌词行）＋进度与运输。 */
        private void drawShelfFull(Canvas canvas, float w, float h, float u,
                                   Bitmap cover, MusicTrack t) {
            mPaint.setColor(0xFFF1F0ED);
            canvas.drawRect(0, 0, w, h, mPaint);
            drawCentered(canvas, t == null ? "未在播放" : t.displayTitle(), w / 2f, 44 * u,
                    15 * u, 0xFF252522, true, w - 120 * u);
            drawCentered(canvas, t == null ? "" : t.displayArtist(), w / 2f, 64 * u,
                    11 * u, 0xFF86857E, false, w - 120 * u);
            float side = 148 * u;
            float sleeveL = w / 2f - side * 0.72f;
            float sleeveT = 92 * u;
            float discCx = sleeveL + side * 0.98f;
            float discCy = sleeveT + side / 2f;
            mPaint.setColor(0xFF141414);
            canvas.drawCircle(discCx, discCy, side * 0.485f, mPaint);
            if (cover != null && !cover.isRecycled()) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addCircle(discCx, discCy, side * 0.11f, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(
                        discCx - side * 0.11f, discCy - side * 0.11f,
                        discCx + side * 0.11f, discCy + side * 0.11f), mPaint);
                canvas.restore();
            }
            mPaint.setColor(0xFFD4D0C5);
            canvas.drawCircle(discCx, discCy, side * 0.032f, mPaint);
            drawCoverClipped(canvas, cover, sleeveL, sleeveT, side, side, 3 * u, 0xFFDDD9D0);
            mPaint.setColor(0x2E252522);
            canvas.drawRoundRect(new android.graphics.RectF(
                            w / 2f - side * 0.55f, sleeveT + side + 5 * u,
                            w / 2f + side * 0.55f, sleeveT + side + 11 * u),
                    3 * u, 3 * u, mPaint);
            float faceT = sleeveT + side + 34 * u;
            float faceB = h - 128 * u;
            mPaint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(new android.graphics.RectF(16 * u, faceT, w - 16 * u, faceB),
                    24 * u, 24 * u, mPaint);
            java.util.List<String> lines = firstLyricLines(t, 3);
            float ly = faceT + 40 * u;
            for (int i = 0; i < lines.size(); i++) {
                drawLine(canvas, lines.get(i), 40 * u, ly, 14 * u,
                        i == 0 ? 0xFF252522 : 0xB386857E, i == 0, w - 40 * u);
                ly += 30 * u;
            }
            drawSeek(canvas, 28 * u, w - 28 * u, h - 96 * u, 2 * u,
                    0x1F252522, 0xFF252522, progressFrac());
            float ty = h - 44 * u;
            android.graphics.Path prev = new android.graphics.Path();
            prev.moveTo(w / 2f - 60 * u, ty);
            prev.lineTo(w / 2f - 46 * u, ty - 9 * u);
            prev.lineTo(w / 2f - 46 * u, ty + 9 * u);
            prev.close();
            mPaint.setColor(0xFF252522);
            canvas.drawPath(prev, mPaint);
            android.graphics.Path next = new android.graphics.Path();
            next.moveTo(w / 2f + 60 * u, ty);
            next.lineTo(w / 2f + 46 * u, ty - 9 * u);
            next.lineTo(w / 2f + 46 * u, ty + 9 * u);
            next.close();
            canvas.drawPath(next, mPaint);
            drawPlayTri(canvas, w / 2f, ty, 14 * u, 0xFF252522);
        }

        private void drawLine(Canvas canvas, String text, float x, float baseline, float size, int color, boolean bold, float maxRight) {
            if (text == null) text = "";
            mText.setColor(color);
            mText.setTextSize(size);
            mText.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            float avail = maxRight - x;
            if (avail > 0 && mText.measureText(text) > avail) {
                while (text.length() > 1 && mText.measureText(text + "…") > avail) {
                    text = text.substring(0, text.length() - 1);
                }
                text = text + "…";
            }
            canvas.drawText(text, x, baseline, mText);
        }
    }

    /**
     * 主题迷你真预览：拿当前在播歌曲按该主题的版式实渲（真封面裁进主题几何、
     * 真歌名落字、主题自己的底色/构件），不是线框占位图。
     * 构成照瓜子 ThemeMiniPreview 的迷你版式口径，素材全用真数据。
     */
    static class ThemePreviewView extends View {
        interface CoverSupplier {
            Bitmap get();
        }

        private final String mTheme;
        private final CoverSupplier mCover;
        private final ThemeFullPreviewView.TrackSupplier mTrackSupplier;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Paint mText = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ThemePreviewView(android.content.Context ctx, String theme, CoverSupplier cover,
                         ThemeFullPreviewView.TrackSupplier trackSupplier) {
            super(ctx);
            mTheme = theme;
            mCover = cover;
            mTrackSupplier = trackSupplier;
        }

        private String title() {
            try {
                MusicTrack t = mTrackSupplier == null ? null : mTrackSupplier.get();
                return t == null ? "未在播放" : t.displayTitle();
            } catch (Throwable ignored) {
                return "";
            }
        }

        private String artist() {
            try {
                MusicTrack t = mTrackSupplier == null ? null : mTrackSupplier.get();
                return t == null ? "" : t.displayArtist();
            } catch (Throwable ignored) {
                return "";
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            Bitmap cover = mCover.get();
            boolean shelf = MusicPlayerSettings.THEME_RECORD_SHELF.equals(mTheme);
            boolean cd = MusicPlayerSettings.isCdTheme(mTheme);
            // 迷你卡统一以浅档（Pearl）呈流光主题，变体在 CD 页内表面键切换
            if (shelf) {
                mPaint.setColor(0xFFF1F0ED);
                canvas.drawRect(0, 0, w, h, mPaint);
            } else if (cd) {
                mPaint.setColor(0xFFF4F2EE);
                canvas.drawRect(0, 0, w, h, mPaint);
            } else {
                mPaint.setColor(0xFF232326);
                canvas.drawRect(0, 0, w, h, mPaint);
            }
            float pad = 8 * d;
            if (cd) {
                // 盘面：圆盘＋封面圆芯＋盘心点，下方歌名/歌手真字（Ink/Muted）
                float r = Math.min(w, h) * 0.30f;
                float cx = w / 2f, cy = h * 0.38f;
                mPaint.setColor(0xFF282D36);
                canvas.drawCircle(cx, cy, r, mPaint);
                if (cover != null && !cover.isRecycled()) {
                    canvas.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addCircle(cx, cy, r * 0.94f, android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    canvas.drawBitmap(cover, null, new android.graphics.RectF(
                            cx - r, cy - r, cx + r, cy + r), mPaint);
                    canvas.restore();
                }
                mPaint.setColor(0xFFF4F2EE);
                canvas.drawCircle(cx, cy, r * 0.10f, mPaint);
                drawText(canvas, title(), cx, h * 0.80f, 9 * d, 0xFF282D36, true, true, w - pad * 2);
                drawText(canvas, artist(), cx, h * 0.92f, 7.5f * d, 0xFF747D89, false, true, w - pad * 2);
                return;
            }
            if (shelf) {
                // 袖套居左、黑胶圆盘右露一截，下方白色歌词面＋Ink 歌名
                float bw = w * 0.46f, bh = bw;
                float left = w * 0.16f, top = h * 0.10f;
                mPaint.setColor(0xFF141414);
                canvas.drawCircle(left + bw * 0.98f, top + bh / 2f, bh * 0.485f, mPaint);
                drawCoverRect(canvas, cover, left, top, bw, bh, 3 * d);
                mPaint.setColor(0xFFFFFFFF);
                canvas.drawRoundRect(new android.graphics.RectF(pad, h * 0.62f, w - pad, h - pad),
                        5 * d, 5 * d, mPaint);
                drawText(canvas, title(), w / 2f, h * 0.76f, 8.5f * d, 0xFF252522, true, true, w - pad * 4);
                drawText(canvas, artist(), w / 2f, h * 0.88f, 7.5f * d, 0xFF86857E, false, true, w - pad * 4);
                return;
            }
            if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(mTheme)) {
                // 顶部歌名/歌手，中间宽卡（0.65），底部玻璃条（圆角条＋播放三角）
                drawText(canvas, title(), pad, h * 0.13f, 9 * d, 0xFFF2F2F7, true, false, w - pad * 2);
                drawText(canvas, artist(), pad, h * 0.22f, 7.5f * d, 0xA6FFFFFF, false, false, w - pad * 2);
                float bw = w - pad * 2, bh = bw * 0.65f;
                drawCoverRect(canvas, cover, pad, h * 0.30f, bw, bh, 7 * d);
                mPaint.setColor(0x40FFFFFF);
                canvas.drawRoundRect(new android.graphics.RectF(pad, h * 0.86f, w - pad, h * 0.97f),
                        6 * d, 6 * d, mPaint);
                drawPlayTriangle(canvas, w / 2f, h * 0.915f, 4.5f * d, 0xFFFFFFFF);
                return;
            }
            if (MusicPlayerSettings.THEME_APPLE_MUSIC.equals(mTheme)) {
                // 封面铺满上半，下接歌名＋一排运输圆键
                drawCoverRect(canvas, cover, 0, 0, w, h * 0.58f, 0);
                drawText(canvas, title(), pad, h * 0.70f, 9 * d, 0xFFF2F2F7, true, false, w - pad * 2);
                drawText(canvas, artist(), pad, h * 0.79f, 7.5f * d, 0x99FFFFFF, false, false, w - pad * 2);
                transportGlyphs(canvas, w / 2f, h * 0.90f, d, 0xFFF2F2F7);
                return;
            }
            // 经典：大封面＋歌名/歌手＋一行歌词位＋进度线（封面下段溶进暗底的口径以渐隐近似）
            float bh = h * 0.52f;
            drawCoverRect(canvas, cover, pad, pad, w - pad * 2, bh, 6 * d);
            drawText(canvas, title(), pad, pad + bh + 11 * d, 9 * d, 0xFFF2F2F7, true, false, w - pad * 2);
            drawText(canvas, artist(), pad, pad + bh + 21 * d, 7.5f * d, 0xA6FFFFFF, false, false, w - pad * 2);
            mPaint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(new android.graphics.RectF(pad, h - 9 * d, w - pad, h - 6.5f * d),
                    1.2f * d, 1.2f * d, mPaint);
            long pos = 0, dur = 0;
            try {
                pos = MusicController.get().position();
                dur = MusicController.get().duration();
            } catch (Throwable ignored) {
            }
            if (dur > 0 && pos > 0) {
                float frac = Math.max(0f, Math.min(1f, pos * 1f / dur));
                mPaint.setColor(0x8CFFFFFF);
                canvas.drawRoundRect(new android.graphics.RectF(pad, h - 9 * d,
                        pad + (w - pad * 2) * frac, h - 6.5f * d), 1.2f * d, 1.2f * d, mPaint);
            }
        }

        private void drawText(Canvas canvas, String s, float x, float baseline, float size,
                              int color, boolean bold, boolean center, float maxW) {
            if (s == null) s = "";
            mText.setColor(color);
            mText.setTextSize(size);
            mText.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            if (maxW > 0 && mText.measureText(s) > maxW) {
                while (s.length() > 1 && mText.measureText(s + "…") > maxW) {
                    s = s.substring(0, s.length() - 1);
                }
                s = s + "…";
            }
            canvas.drawText(s, center ? x - mText.measureText(s) / 2f : x, baseline, mText);
        }

        private void drawPlayTriangle(Canvas canvas, float cx, float cy, float r, int color) {
            mPaint.setColor(color);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - r * 0.7f, cy - r);
            p.lineTo(cx - r * 0.7f, cy + r);
            p.lineTo(cx + r, cy);
            p.close();
            canvas.drawPath(p, mPaint);
        }

        private void transportGlyphs(Canvas canvas, float cx, float cy, float d, int color) {
            // 上一首｜播放｜下一首 的极简字形（条＋三角）
            mPaint.setColor(color);
            float s = 5 * d;
            android.graphics.Path prev = new android.graphics.Path();
            prev.moveTo(cx - s * 2.2f, cy);
            prev.lineTo(cx - s * 0.9f, cy - s * 0.8f);
            prev.lineTo(cx - s * 0.9f, cy + s * 0.8f);
            prev.close();
            canvas.drawPath(prev, mPaint);
            canvas.drawRect(cx - s * 2.6f, cy - s * 0.8f, cx - s * 2.3f, cy + s * 0.8f, mPaint);
            drawPlayTriangle(canvas, cx, cy, s * 0.95f, color);
            android.graphics.Path next = new android.graphics.Path();
            next.moveTo(cx + s * 2.2f, cy);
            next.lineTo(cx + s * 0.9f, cy - s * 0.8f);
            next.lineTo(cx + s * 0.9f, cy + s * 0.8f);
            next.close();
            canvas.drawPath(next, mPaint);
            canvas.drawRect(cx + s * 2.3f, cy - s * 0.8f, cx + s * 2.6f, cy + s * 0.8f, mPaint);
        }

        private void drawCoverRect(Canvas canvas, Bitmap cover, float l, float t, float w, float h, float radius) {
            if (cover != null && !cover.isRecycled()) {
                canvas.save();
                android.graphics.Path clip = new android.graphics.Path();
                clip.addRoundRect(new android.graphics.RectF(l, t, l + w, t + h), radius, radius, android.graphics.Path.Direction.CW);
                canvas.clipPath(clip);
                canvas.drawBitmap(cover, null, new android.graphics.RectF(l, t, l + w, t + h), mPaint);
                canvas.restore();
            } else {
                mPaint.setColor(0xFF3A3A3C);
                if (radius > 0) {
                    canvas.drawRoundRect(new android.graphics.RectF(l, t, l + w, t + h), radius, radius, mPaint);
                } else {
                    canvas.drawRect(l, t, l + w, t + h, mPaint);
                }
            }
        }
    }

    // ---------- 睡眠定时子面板 ----------
    private void showSleep() {
        clearPanel();
        mPanelBox.addView(subHeader("睡眠定时", this::showMain));
        int cur = MusicController.get().getSleep();
        if (cur != MusicPlaybackService.SLEEP_OFF) {
            LinearLayout status = new LinearLayout(mActivity);
            status.setOrientation(LinearLayout.HORIZONTAL);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(14), dp(12), dp(14), dp(12));
            status.setBackground(new MusicGlassDrawable(mActivity, 14));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(14);
            status.setLayoutParams(slp);
            LinearLayout texts = new LinearLayout(mActivity);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView lab = new TextView(mActivity);
            lab.setText("进行中");
            lab.setTextColor(TEXT_MED);
            lab.setTextSize(11);
            texts.addView(lab);
            TextView remain = new TextView(mActivity);
            long ms = MusicController.get().sleepRemainingMs();
            // 体检中1：等待态（分钟档到点、等本首播完）如实显「本首结束后停止」——
            // 此前等待态剩余已归 -1、回落 sleepName(cur) 把它显示成档名（如「15 分钟」），
            // 看着像还没开始计时。服务侧等待态是真值口径，面板不再自己推断。
            remain.setText(MusicController.get().isSleepWaitingForSongEnd() ? "本首结束后停止"
                    : cur == MusicPlaybackService.SLEEP_AFTER_CURRENT ? "本首结束"
                    : (ms > 0 ? "剩余 " + formatMs(ms) : sleepName(cur)));
            remain.setTextColor(TEXT_HIGH);
            remain.setTextSize(18);
            texts.addView(remain);
            status.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView off = new TextView(mActivity);
            off.setText("关闭");
            off.setTextColor(TEXT_HIGH);
            off.setTextSize(12);
            off.setGravity(Gravity.CENTER);
            off.setPadding(dp(12), dp(7), dp(12), dp(7));
            off.setBackground(new MusicGlassDrawable(mActivity, 8));
            off.setOnClickListener(v -> {
                MusicController.get().setSleep(MusicPlaybackService.SLEEP_OFF);
                showSleep();
            });
            status.addView(off);
            mPanelBox.addView(status);
        }

        mPanelBox.addView(sectionLabel("时长", 16));
        String[] labels = {"关闭", "15 分", "30 分", "45 分", "60 分", "90 分", "本首结束"};
        int[] codes = {MusicPlaybackService.SLEEP_OFF, MusicPlaybackService.SLEEP_15,
                MusicPlaybackService.SLEEP_30, MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + 45,
                MusicPlaybackService.SLEEP_60, MusicPlaybackService.SLEEP_90,
                MusicPlaybackService.SLEEP_AFTER_CURRENT};
        int sel = -1;
        for (int i = 0; i < codes.length; i++) if (codes[i] == cur) sel = i;
        // 网格两行芯片
        LinearLayout grid = new LinearLayout(mActivity);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (r > 0) row.setPadding(0, dp(6), 0, 0);
            for (int c = 0; c < 4; c++) {
                int idx = r * 4 + c;
                if (idx >= labels.length) {
                    row.addView(new View(mActivity), new LinearLayout.LayoutParams(0, dp(34), 1f));
                    continue;
                }
                final int code = codes[idx];
                TextView chip = chip(labels[idx], idx == sel);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
                if (c > 0) lp.leftMargin = dp(6);
                chip.setLayoutParams(lp);
                chip.setOnClickListener(v -> {
                    MusicController.get().setSleep(code);
                    showSleep();
                });
                row.addView(chip);
            }
            grid.addView(row);
        }
        mPanelBox.addView(grid);

        // 自定义分钟
        LinearLayout custom = new LinearLayout(mActivity);
        custom.setOrientation(LinearLayout.HORIZONTAL);
        custom.setGravity(Gravity.CENTER_VERTICAL);
        custom.setPadding(dp(14), dp(10), dp(14), dp(10));
        custom.setBackground(new MusicGlassDrawable(mActivity, 10));
        LinearLayout.LayoutParams culp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        culp.topMargin = dp(8);
        custom.setLayoutParams(culp);
        EditText input = new EditText(mActivity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(TEXT_HIGH);
        input.setTextSize(16);
        input.setHint("自定义");
        input.setHintTextColor(TEXT_LOW);
        input.setBackground(null);
        custom.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView unit = new TextView(mActivity);
        unit.setText("分钟");
        unit.setTextColor(TEXT_MED);
        unit.setTextSize(12);
        custom.addView(unit);
        TextView go = new TextView(mActivity);
        go.setText("开启");
        go.setTextColor(Color.WHITE);
        go.setTextSize(12);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(ACCENT);
        gb.setCornerRadius(dp(8));
        go.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(10);
        custom.addView(go, glp);
        go.setOnClickListener(v -> {
            try {
                int mins = Integer.parseInt(input.getText().toString().trim());
                if (mins > 0 && mins <= 600) {
                    MusicController.get().setSleep(MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + mins);
                    showSleep();
                }
            } catch (Throwable ignored) {
            }
        });
        mPanelBox.addView(custom);

        // 扫描 M19：分钟档「播完再停」开关——服务 armSleep 到点现读 MusicStore
        // 键、等待态分支都已就绪，唯独面板从来没画这个开关，键只读不写、行为永不生效。
        // 体检中1 补接线：关开关时若已在等待态，同步调服务清等待态（接口此前备好零调用）——
        // 不接的话等待态留着、曲终照样停，与开关已关的显示脱钩。开开关不动等待态。
        mPanelBox.addView(toggleRow("播完当前歌曲再停", MusicStore.isSleepFinishCurrent(), () -> {
            boolean on = !MusicStore.isSleepFinishCurrent();
            MusicStore.setSleepFinishCurrent(on);
            if (!on) {
                MusicController.get().clearSleepWaitingForSongEnd();
                showSleep(); // 等待态刚清掉，状态行立即回真（不留「本首结束后停止」旧字）
            }
        }));
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return (s / 60) + " 分 " + (s % 60) + " 秒";
    }

    private static String sleepName(int code) {
        switch (code) {
            case MusicPlaybackService.SLEEP_15:
                return "15 分钟";
            case MusicPlaybackService.SLEEP_30:
                return "30 分钟";
            case MusicPlaybackService.SLEEP_60:
                return "60 分钟";
            case MusicPlaybackService.SLEEP_90:
                return "90 分钟";
            case MusicPlaybackService.SLEEP_AFTER_CURRENT:
                return "本首结束";
            default:
                if (code >= MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE) {
                    return (code - MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE) + " 分钟";
                }
                return "已开启";
        }
    }

    // ---------- 封面歌词样式子面板 ----------
    private void showLyricStyle() {
        clearPanel();
        // 直达开场（从全屏歌词页来）时没有主面板可回，返回＝关面板
        mPanelBox.addView(subHeader("歌词样式", mStartAtLyricStyle ? this::dismiss : this::showMain));

        // 字号分键：从全屏歌词页直达时调全屏键（默认 24、钳 16–40），从播放页进来
        // 时调封面键（默认 22、钳 14–32）——瓜子两页口径本就不同，不许并成一个值；
        // 本面板其余行（粗细/对齐/模糊/主歌词/锚点）仍是两页共用键，不动。
        final boolean fullSize = mStartAtLyricStyle;
        final float sizeMin = fullSize ? 16f : 14f;
        final float sizeMax = fullSize ? 40f : 32f;
        final float sizeNow = fullSize ? MusicPlayerSettings.getFullLyricFontSize()
                : MusicPlayerSettings.getCoverLyricFontSize();

        // 预览卡（当前歌曲真歌词行预览）
        LinearLayout preview = new LinearLayout(mActivity);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xFF1C1C1E);
        pb.setCornerRadius(dp(14));
        preview.setBackground(pb);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        plp.topMargin = dp(12);
        preview.setLayoutParams(plp);
        MusicTrack t = track();
        TextView pv1 = new TextView(mActivity);
        pv1.setText(t == null ? "歌词预览" : t.displayTitle());
        pv1.setTextColor(0xFFFFFFFF);
        pv1.setTextSize(sizeNow);
        pv1.getPaint().setTextSkewX(-0.12f);
        pv1.setMaxLines(1);
        pv1.setEllipsize(TextUtils.TruncateAt.END);
        preview.addView(pv1);
        TextView pv2 = new TextView(mActivity);
        pv2.setText(t == null ? "" : t.displayArtist());
        pv2.setTextColor(0x4DFFFFFF);
        pv2.setTextSize(sizeNow);
        pv2.getPaint().setTextSkewX(-0.12f);
        pv2.setMaxLines(1);
        preview.addView(pv2);
        mPanelBox.addView(preview);

        // 全屏歌词入口（Callback.onOpenLyricPage 的唯一调用点——此前接口定义了却从不调用、
        // 是死回调；从歌词页直达开场时已在歌词页，不再摆此行）
        if (!mStartAtLyricStyle) {
            LinearLayout lyricPageCard = cardBox();
            LinearLayout.LayoutParams lplp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lplp.topMargin = dp(14);
            lyricPageCard.setLayoutParams(lplp);
            lyricPageCard.addView(settingsRow("全屏歌词", null, () -> {
                dismiss();
                if (mCallback != null) mCallback.onOpenLyricPage();
            }));
            mPanelBox.addView(lyricPageCard);
        }

        // 字号
        mPanelBox.addView(sectionLabel("字号", 12));
        LinearLayout sizeRow = new LinearLayout(mActivity);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView a = new TextView(mActivity);
        a.setText("A");
        a.setTextColor(0xB3FFFFFF);
        a.setTextSize(16);
        sizeRow.addView(a, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = new TextView(mActivity);
        val.setText(String.valueOf(Math.round(sizeNow)));
        val.setTextColor(0xBFFFFFFF);
        val.setTextSize(11);
        val.setGravity(Gravity.END);
        sizeRow.addView(val, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sizeBar = new SeekBar(mActivity);
        sizeBar.setMax(Math.round(sizeMax - sizeMin));
        sizeBar.setProgress(Math.round(sizeNow - sizeMin));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                float v = sizeMin + progress;
                if (fullSize) MusicStore.setFullLyricFontSize(v);
                else MusicStore.setCoverLyricFontSize(v);
                val.setText(String.valueOf(Math.round(v)));
                pv1.setTextSize(v);
                pv2.setTextSize(v);
                if (mCallback != null) mCallback.onSettingsChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        LinearLayout.LayoutParams sblp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sblp2.leftMargin = dp(12);
        sizeRow.addView(sizeBar, sblp2);
        mPanelBox.addView(sizeRow);

        mPanelBox.addView(sectionLabel("高亮位置", 10));
        String anchor = MusicPlayerSettings.getLyricAnchor();
        int anchorSel = MusicPlayerSettings.ANCHOR_CENTER.equals(anchor) ? 1
                : MusicPlayerSettings.ANCHOR_BOTTOM.equals(anchor) ? 2 : 0;
        mPanelBox.addView(chipRow(new String[]{"顶部", "居中", "底部"}, anchorSel, idx -> {
            MusicPlayerSettings.setLyricAnchor(idx == 1 ? MusicPlayerSettings.ANCHOR_CENTER
                    : idx == 2 ? MusicPlayerSettings.ANCHOR_BOTTOM : MusicPlayerSettings.ANCHOR_TOP);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        }));

        mPanelBox.addView(sectionLabel("粗细", 10));
        mPanelBox.addView(chipRow(new String[]{"标准", "加粗", "特粗"}, MusicStore.getLyricWeight(), idx -> {
            MusicStore.setLyricWeight(idx);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        }));

        mPanelBox.addView(sectionLabel("对齐", 10));
        mPanelBox.addView(chipRow(new String[]{"靠左", "居中", "靠右"}, MusicStore.getLyricAlign(), idx -> {
            MusicStore.setLyricAlign(idx);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        }));

        mPanelBox.addView(sectionLabel("模糊", 10));
        mPanelBox.addView(chipRow(new String[]{"关闭", "轻度", "中度"}, MusicStore.getCoverLyricBlurLevel(), idx -> {
            MusicStore.setCoverLyricBlurLevel(idx);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        }));

        mPanelBox.addView(sectionLabel("主歌词", 10));
        mPanelBox.addView(chipRow(new String[]{"原文", "翻译", "音译"}, MusicStore.getLyricPrimary(), idx -> {
            MusicStore.setLyricPrimary(idx);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        }));

        LinearLayout card = cardBox();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        card.setLayoutParams(clp);
        card.addView(toggleRow("高级动效", MusicStore.isAdvancedFx(), () -> {
            MusicStore.setAdvancedFx(!MusicStore.isAdvancedFx());
            if (mCallback != null) mCallback.onSettingsChanged();
        }));
        mPanelBox.addView(card);

        TextView reset = new TextView(mActivity);
        reset.setText("恢复默认");
        reset.setTextColor(TEXT_MED);
        reset.setTextSize(11);
        reset.setPadding(dp(12), dp(10), dp(12), dp(6));
        reset.setOnClickListener(v -> {
            // 字号恢复默认跟开场来源走：全屏 24／播放页 22（两页口径不同）
            if (fullSize) MusicStore.setFullLyricFontSize(24f);
            else MusicStore.setCoverLyricFontSize(22f);
            // 扫描 T1：字重默认是 0（标准，MusicStore.LYRIC_WEIGHT_NORMAL），此前写 1（加粗）——
            // 「恢复默认」反而把全 App 歌词字重静默改成加粗
            MusicStore.setLyricWeight(0);
            MusicStore.setLyricAlign(0);
            MusicStore.setCoverLyricBlurLevel(2);
            MusicStore.setLyricPrimary(0);
            MusicPlayerSettings.setLyricAnchor(MusicPlayerSettings.ANCHOR_TOP);
            if (mCallback != null) mCallback.onSettingsChanged();
            showLyricStyle();
        });
        mPanelBox.addView(reset);
    }

    // ---------- 外观设置子面板 ----------
    // 瓜子构成：段标「背景动效」→「动态流体背景」开关＋展开区（「流动速度」标签＋慢速/标准/快速筹）→「音乐律动」开关。
    // 「高级动效」在歌词样式子面板、「播放页不熄屏」在主面板卡内开关行，都不在这里。
    private void showAppearance() {
        clearPanel();
        mPanelBox.addView(subHeader("外观设置", this::showMain));
        mPanelBox.addView(sectionLabel("背景动效", 16));
        LinearLayout card = cardBox();
        card.addView(toggleRow("动态流体背景", MusicPlayerSettings.isFluidBg(), () -> {
            MusicPlayerSettings.setFluidBg(!MusicPlayerSettings.isFluidBg());
            if (mCallback != null) mCallback.onSettingsChanged();
            showAppearance();
        }));
        if (MusicPlayerSettings.isFluidBg()) {
            card.addView(divider());
            LinearLayout speedBox = new LinearLayout(mActivity);
            speedBox.setOrientation(LinearLayout.VERTICAL);
            speedBox.setPadding(dp(16), dp(10), dp(16), dp(12));
            TextView speedLabel = new TextView(mActivity);
            speedLabel.setText("流动速度");
            speedLabel.setTextColor(TEXT_MED);
            speedLabel.setTextSize(12);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.bottomMargin = dp(10);
            speedBox.addView(speedLabel, llp);
            int speed = MusicPlayerSettings.getFluidSpeed();
            int sel = speed == 0 ? 0 : speed == 2 ? 2 : 1;
            speedBox.addView(chipRow(new String[]{"慢速", "标准", "快速"}, sel, idx -> {
                MusicPlayerSettings.setFluidSpeed(idx == 0 ? 0 : idx == 2 ? 2 : 1);
                if (mCallback != null) mCallback.onSettingsChanged();
                showAppearance();
            }));
            card.addView(speedBox);
        }
        card.addView(divider());
        card.addView(toggleRow("音乐律动", MusicPlayerSettings.isFluidBeat(), () -> {
            MusicPlayerSettings.setFluidBeat(!MusicPlayerSettings.isFluidBeat());
            if (mCallback != null) mCallback.onSettingsChanged();
        }));
        mPanelBox.addView(card);
    }

    // ---------- 歌曲信息子面板 ----------
    private void showSongInfo() {
        clearPanel();
        mPanelBox.addView(subHeader("歌曲信息", this::showMain));
        MusicTrack t = track();
        LinearLayout hero = new LinearLayout(mActivity);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(new MusicGlassDrawable(mActivity, 20));
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        hero.setLayoutParams(hlp);
        ImageView cover = new ImageView(mActivity);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (t != null && !TextUtils.isEmpty(t.cover)) ImgUtil.loadFade(t.displayTitle(), t.cover, cover);
        hero.addView(cover, new LinearLayout.LayoutParams(dp(80), dp(80)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(mActivity);
        name.setText(t == null ? "未在播放" : t.displayTitle());
        name.setTextColor(TEXT_HIGH);
        name.setTextSize(20);
        name.setMaxLines(2);
        texts.addView(name);
        TextView sub = new TextView(mActivity);
        sub.setText(t == null ? "" : t.displayArtist());
        sub.setTextColor(TEXT_MED);
        sub.setTextSize(12);
        sub.setMaxLines(1);
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sublp.topMargin = dp(4);
        texts.addView(sub, sublp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(14);
        hero.addView(texts, tlp);
        mPanelBox.addView(hero);

        LinearLayout card = cardBox();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        card.setLayoutParams(clp);
        if (t != null) {
            card.addView(infoRow("歌手", t.displayArtist()));
            card.addView(divider());
            card.addView(infoRow("专辑", TextUtils.isEmpty(t.album) ? "—" : t.album));
            card.addView(divider());
            long dur = MusicController.get().duration() > 0 ? MusicController.get().duration() : t.duration;
            card.addView(infoRow("时长", dur > 0 ? formatMs(dur) : "—"));
            card.addView(divider());
            MusicAudioInfo info = null;
            try {
                info = MusicController.get().audioInfo();
            } catch (Throwable ignored) {
            }
            // 音质只报实测（他的口径：取不到就不显示，永不拿所选档名垫场；
            // 扫描 T4：取不到时整行不加——此前照样摆一行空值「音质」，与口径不符）
            if (info != null && info.describe() != null && !info.describe().isEmpty()) {
                card.addView(divider());
                card.addView(infoRow("音质", info.describe()));
            }
            if (!TextUtils.isEmpty(t.platform)) {
                card.addView(divider());
                card.addView(infoRow("来源", t.platform));
            }
            // 上次播放失败（落槌信号行）：服务失败收口落盘的最近一次（环＋错误大类＋域名），
            // 按 trackKey 认领——不是这首的失败不显，换歌不串账；没失败过不摆行。
            String[] fail = MusicStore.lastMusicFailure();
            if (fail != null && fail[0].equals(com.fongmi.android.tv.music.MusicQueue.trackKey(t))) {
                String ringText = "拉流".equals(fail[1]) ? "地址拿到了但播不出来" : "没取到播放地址";
                String val = ringText + " · " + fail[2] + (fail[3].isEmpty() ? "" : " · " + fail[3]);
                card.addView(divider());
                card.addView(infoRow("上次失败", val));
            }
        }
        mPanelBox.addView(card);
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        TextView lab = new TextView(mActivity);
        lab.setText(label);
        lab.setTextColor(TEXT_MED);
        lab.setTextSize(12);
        row.addView(lab, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = new TextView(mActivity);
        val.setText(value == null ? "" : value);
        val.setTextColor(TEXT_HIGH);
        val.setTextSize(13);
        val.setGravity(Gravity.END);
        val.setMaxLines(1);
        val.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(val, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
