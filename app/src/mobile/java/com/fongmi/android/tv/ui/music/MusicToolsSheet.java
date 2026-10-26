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
    private static final int TEXT_MED = 0xFF8E8E93;
    private static final int TEXT_LOW = 0xFF636366;
    private static final int ACCENT = 0xFFFA243C;

    private final AppCompatActivity mActivity;
    private final Callback mCallback;
    private final float mDensity;
    private Dialog mDialog;
    private LinearLayout mPanelBox;
    private Bitmap mCoverBitmap;

    private MusicToolsSheet(AppCompatActivity activity, Callback cb) {
        mActivity = activity;
        mCallback = cb;
        mDensity = activity.getResources().getDisplayMetrics().density;
    }

    public static void show(AppCompatActivity activity, Callback cb) {
        if (activity == null) return;
        new MusicToolsSheet(activity, cb).open();
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
        }
        loadCover();
        showMain();
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
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }
        });
    }

    private void clearPanel() {
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
    private LinearLayout subHeader(String title, Runnable onBack) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        ImageView back = circleButton(R.drawable.ic_music_chevron_left);
        back.setOnClickListener(v -> onBack.run());
        row.addView(back);
        TextView tv = new TextView(mActivity);
        tv.setText(title);
        tv.setTextColor(TEXT_HIGH);
        tv.setTextSize(17);
        tv.setGravity(Gravity.CENTER);
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
        if (t != null && !TextUtils.isEmpty(t.cover)) ImgUtil.load(t.displayTitle(), t.cover, cover);
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
                MusicDownloadStore.download(t);
                Toast.makeText(mActivity, "已加入下载", Toast.LENGTH_SHORT).show();
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
                () -> MusicController.get().openEqualizer(mActivity)));
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
    private static final String[] THEME_IDS = {
            MusicPlayerSettings.THEME_CLASSIC, MusicPlayerSettings.THEME_APPLE_MUSIC,
            MusicPlayerSettings.THEME_COVER_IMMERSIVE, MusicPlayerSettings.THEME_LUMINOUS_CD,
            MusicPlayerSettings.THEME_LUMINOUS_CD_AUTO, MusicPlayerSettings.THEME_LUMINOUS_CD_DARK,
            MusicPlayerSettings.THEME_RECORD_SHELF};
    private static final String[] THEME_NAMES = {
            "经典", "Apple Music", "封面卡片", "流光 CD", "流光 CD·自动", "流光 CD·深色", "黑胶唱片架"};

    private static String themeName(String id) {
        for (int i = 0; i < THEME_IDS.length; i++) if (THEME_IDS[i].equals(id)) return THEME_NAMES[i];
        return "经典";
    }

    private void showTheme() {
        clearPanel();
        mPanelBox.addView(subHeader("播放页主题", this::showMain));
        TextView desc = new TextView(mActivity);
        desc.setText("选择播放详情页的整体布局，新增主题会自动出现在这里。");
        desc.setTextColor(TEXT_LOW);
        desc.setTextSize(11);
        desc.setLineSpacing(0, 16f / 11f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(4);
        dlp.topMargin = dp(6);
        dlp.bottomMargin = dp(10);
        desc.setLayoutParams(dlp);
        mPanelBox.addView(desc);

        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(mActivity);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String cur = MusicPlayerSettings.getTheme();
        for (int i = 0; i < THEME_IDS.length; i++) {
            final String id = THEME_IDS[i];
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
            ThemePreviewView preview = new ThemePreviewView(mActivity, id, () -> mCoverBitmap);
            previewBox.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (id.equals(cur)) {
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
            nm.setText(THEME_NAMES[i]);
            nm.setTextColor(TEXT_HIGH);
            nm.setTextSize(12);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, dp(8), 0, dp(9));
            card.addView(nm);
            card.setOnClickListener(v -> {
                MusicPlayerSettings.setTheme(id);
                showTheme();
                if (mCallback != null) mCallback.onSettingsChanged();
            });
            row.addView(card);
        }
        hs.addView(row);
        mPanelBox.addView(hs);
    }

    /** 主题迷你真预览：按主题形态用当前封面画简化版式（照瓜子 ThemeMiniPreview 口径）。 */
    static class ThemePreviewView extends View {
        interface CoverSupplier {
            Bitmap get();
        }

        private final String mTheme;
        private final CoverSupplier mCover;
        private final android.graphics.Paint mPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

        ThemePreviewView(android.content.Context ctx, String theme, CoverSupplier cover) {
            super(ctx);
            mTheme = theme;
            mCover = cover;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            Bitmap cover = mCover.get();
            boolean shelf = MusicPlayerSettings.THEME_RECORD_SHELF.equals(mTheme);
            boolean cd = mTheme != null && mTheme.startsWith("luminous_cd");
            // 背景
            if (shelf) {
                mPaint.setColor(0xFFF1F0ED);
                canvas.drawRect(0, 0, w, h, mPaint);
            } else if (cd && !MusicPlayerSettings.THEME_LUMINOUS_CD.equals(mTheme)) {
                mPaint.setColor(0xFF14161C);
                canvas.drawRect(0, 0, w, h, mPaint);
            } else if (cd) {
                mPaint.setColor(0xFFF4F2EE);
                canvas.drawRect(0, 0, w, h, mPaint);
            } else {
                mPaint.setColor(0xFF2C2C2E);
                canvas.drawRect(0, 0, w, h, mPaint);
            }
            float pad = 8 * d;
            if (cd) {
                float r = Math.min(w, h) * 0.32f;
                if (cover != null && !cover.isRecycled()) {
                    canvas.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addCircle(w / 2f, h * 0.42f, r, android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    canvas.drawBitmap(cover, null, new android.graphics.RectF(
                            w / 2f - r, h * 0.42f - r, w / 2f + r, h * 0.42f + r), mPaint);
                    canvas.restore();
                } else {
                    mPaint.setColor(0xFF3A3A3C);
                    canvas.drawCircle(w / 2f, h * 0.42f, r, mPaint);
                }
                bar(canvas, w * 0.2f, h * 0.78f, w * 0.6f, shelf || cd && MusicPlayerSettings.THEME_LUMINOUS_CD.equals(mTheme) ? 0xFF252522 : 0xFFF2F2F7, 0.8f, d);
                bar(canvas, w * 0.3f, h * 0.88f, w * 0.4f, 0xFF8E8E93, 0.7f, d);
                return;
            }
            if (shelf) {
                float bw = w * 0.52f, bh = bw * 0.9f;
                drawCoverRect(canvas, cover, (w - bw) / 2f, h * 0.16f, bw, bh, 4 * d);
                bar(canvas, w * 0.2f, h * 0.72f, w * 0.6f, 0xFF252522, 0.85f, d);
                bar(canvas, w * 0.15f, h * 0.86f, w * 0.7f, 0xFF86857E, 0.9f, d);
                return;
            }
            if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(mTheme)) {
                float bw = w - pad * 2, bh = bw * 0.65f;
                drawCoverRect(canvas, cover, pad, h * 0.2f, bw, bh, 8 * d);
                bar(canvas, pad, h * 0.1f, w * 0.55f, 0xFFF2F2F7, 0.95f, d);
                return;
            }
            // classic / apple：上封面＋下文线（apple 封面更大）
            boolean apple = MusicPlayerSettings.THEME_APPLE_MUSIC.equals(mTheme);
            float bh = h * (apple ? 0.52f : 0.44f);
            drawCoverRect(canvas, cover, pad, pad, w - pad * 2, bh, 6 * d);
            bar(canvas, pad, pad + bh + 8 * d, w * 0.55f, 0xFFF2F2F7, 0.95f, d);
            bar(canvas, pad, pad + bh + 18 * d, w * 0.4f, 0xFF8E8E93, 0.8f, d);
            bar(canvas, pad, h - 12 * d, w - pad * 2, 0xFFF2F2F7, 0.6f, d);
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
                canvas.drawRoundRect(new android.graphics.RectF(l, t, l + w, t + h), radius, radius, mPaint);
            }
        }

        private void bar(Canvas canvas, float l, float t, float w, int color, float alpha, float d) {
            mPaint.setColor(color);
            mPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawRoundRect(new android.graphics.RectF(l, t, l + w, t + 5 * d), 2.5f * d, 2.5f * d, mPaint);
            mPaint.setAlpha(255);
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
            remain.setText(cur == MusicPlaybackService.SLEEP_AFTER_CURRENT ? "本首结束"
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
        mPanelBox.addView(subHeader("歌词样式", this::showMain));

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
        pv1.setTextSize(MusicPlayerSettings.getCoverLyricFontSize());
        pv1.getPaint().setTextSkewX(-0.12f);
        pv1.setMaxLines(1);
        pv1.setEllipsize(TextUtils.TruncateAt.END);
        preview.addView(pv1);
        TextView pv2 = new TextView(mActivity);
        pv2.setText(t == null ? "" : t.displayArtist());
        pv2.setTextColor(0x4DFFFFFF);
        pv2.setTextSize(MusicPlayerSettings.getCoverLyricFontSize());
        pv2.getPaint().setTextSkewX(-0.12f);
        pv2.setMaxLines(1);
        preview.addView(pv2);
        mPanelBox.addView(preview);

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
        val.setText(String.valueOf(Math.round(MusicPlayerSettings.getCoverLyricFontSize())));
        val.setTextColor(0xBFFFFFFF);
        val.setTextSize(11);
        val.setGravity(Gravity.END);
        sizeRow.addView(val, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        SeekBar sizeBar = new SeekBar(mActivity);
        sizeBar.setMax(18);
        sizeBar.setProgress(Math.round(MusicPlayerSettings.getCoverLyricFontSize()) - 14);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                float v = 14 + progress;
                MusicStore.setCoverLyricFontSize(v);
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
            MusicStore.setCoverLyricFontSize(22f);
            MusicStore.setLyricWeight(1);
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
        if (t != null && !TextUtils.isEmpty(t.cover)) ImgUtil.load(t.displayTitle(), t.cover, cover);
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
            // 音质只报实测（他的口径：取不到就不显示，永不拿所选档名垫场）
            card.addView(infoRow("音质", info != null && info.describe() != null && !info.describe().isEmpty()
                    ? info.describe() : ""));
            if (!TextUtils.isEmpty(t.platform)) {
                card.addView(divider());
                card.addView(infoRow("来源", t.platform));
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
