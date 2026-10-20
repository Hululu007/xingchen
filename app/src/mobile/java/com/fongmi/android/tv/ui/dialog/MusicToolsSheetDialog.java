package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.DialogMusicToolsBinding;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 音乐底部工具面板（浅色，照 GZ 本体转写）：主面板＝信息头（面板底上裸行）＋快捷格（白方格＋下方标签）
 * ＋模式行（一张白卡四段）＋音量卡（喇叭＋红细线滑杆＋百分比同行）＋设置卡（白卡单行列表），
 * ＋三子面板（播放页主题/定时/歌词设置），同 Dialog 内换内容区切换。
 * 令牌照 GZ 参数表第 7 节浅色套（SheetTokensKt）：面板底 #F3F3F3、卡面白、主字 #1C1C1E、
 * 次字 #9A9AA0、中字 #8E8E93、分割线/描边黑 0.06、拖动条黑 0.14、强调红 #FA243C；
 * 面板内边距 16dp 底 10dp（GlassSheetPanelKt）、区块间隔 20dp、子面板头 44dp/圆钮 40dp。
 */
public class MusicToolsSheetDialog extends BaseBottomSheetDialog {

    public interface Listener {
        void onThemeChanged();

        void onLyricStyleChanged();

        void onSearchLyric();

        void onAddToPlaylist();

        void onDownload();
    }

    private static final int PANEL_MAIN = 0;
    private static final int PANEL_THEME = 1;
    private static final int PANEL_SLEEP = 2;
    private static final int PANEL_LYRIC = 3;

    // GZ SheetTokensKt 浅色套（sheetPanelBg/sheetTextHigh/sheetTextLow/textMedium/listBg）
    private static final int COLOR_PANEL = 0xFFF3F3F3;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_ACCENT = 0xFFFA243C;
    private static final int COLOR_TEXT = 0xFF1C1C1E;
    private static final int COLOR_LOW = 0xFF9A9AA0;
    private static final int COLOR_MEDIUM = 0xFF8E8E93;
    private static final int COLOR_DIVIDER = 0x0F000000;
    private static final int COLOR_BORDER = 0x0F000000;
    private static final int COLOR_INSET = 0x0A000000;

    private static final String[] THEME_IDS = {
            MusicStore.THEME_CLASSIC, MusicStore.THEME_APPLE_MUSIC, MusicStore.THEME_COVER_IMMERSIVE,
            MusicStore.THEME_LUMINOUS_CD, MusicStore.THEME_RECORD_SHELF};
    private static final int[] THEME_NAMES = {
            R.string.music_theme_classic, R.string.music_theme_apple, R.string.music_theme_cover,
            R.string.music_theme_cd, R.string.music_theme_vinyl};
    private static final int[][] THEME_COLORS = {
            {0xFF3A4A5F, 0xFF10151C},
            {0xFFF5F5F7, 0xFFFA243C},
            {0xFF7B4DFF, 0xFFFF8A3D},
            {0xFF59E3FF, 0xFFFF5DA2, 0xFFFFD35D},
            {0xFF4A3220, 0xFF14100C}};

    private DialogMusicToolsBinding binding;
    private Listener listener;
    private TextView lyricPreview;
    private TextView lyricSizeValue;

    public static void show(Activity activity, Listener listener) {
        if (!(activity instanceof FragmentActivity)) return;
        MusicToolsSheetDialog d = new MusicToolsSheetDialog();
        d.listener = listener;
        d.show(((FragmentActivity) activity).getSupportFragmentManager(), "music_tools_sheet");
    }

    // 内容自带浅色底（布局 bg_sheet_panel）：弹窗容器/窗口必须透明，
    // 否则 Material 底部弹窗默认的浅色层会从顶部漏出一条白带。
    @Override
    protected boolean transparent() {
        return true;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogMusicToolsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        // 右缘防裁切：窗口宽强制 MATCH_PARENT（旧版不设宽，个别机型 BottomSheet 按内容宽
        // 排版会把行值/开关挤出右缘，实拍 112767 即此症）
        try {
            if (getDialog() != null && getDialog().getWindow() != null) {
                getDialog().getWindow().setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable ignored) {
        }
        showPanel(PANEL_MAIN);
    }

    // ================= 面板切换 =================

    private void showPanel(int panel) {
        if (binding == null) return;
        lyricPreview = null;
        lyricSizeValue = null;
        binding.container.removeAllViews();
        switch (panel) {
            case PANEL_THEME:
                buildThemePanel();
                break;
            case PANEL_SLEEP:
                buildSleepPanel();
                break;
            case PANEL_LYRIC:
                buildLyricPanel();
                break;
            default:
                buildMainPanel();
                break;
        }
        binding.scroll.scrollTo(0, 0);
    }

    // ================= 主面板 =================

    private void buildMainPanel() {
        LinearLayout box = binding.container;
        box.addView(buildHero());
        box.addView(buildQuickGrid(), marginTop(dp(20)));
        box.addView(buildModeSegment(), marginTop(dp(20)));
        box.addView(buildVolumeCard(), marginTop(dp(20)));
        box.addView(buildSettingsCard(), marginTop(dp(20)));
    }

    /** ① 歌曲信息头（GZ SongInfoHero 转写）：封面 48dp 圆角 8＋歌名 16sp/行高 21/字距 −0.3＋歌手 12sp，面板底上裸行，右端关闭钮 */
    private View buildHero() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cover = new ImageView(getContext());
        cover.setBackground(cardBg(0x1F8E8E93, 8));
        row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = new LinearLayout(getContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("", 16, COLOR_TEXT, true);
        title.setLetterSpacing(-0.02f);
        TextView artist = text("", 12, COLOR_MEDIUM, false);
        artist.setPadding(0, dp(2), 0, 0);
        texts.addView(title);
        texts.addView(artist);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        row.addView(texts, tlp);
        ImageView close = new ImageView(getContext());
        close.setImageResource(R.drawable.ic_music_close);
        close.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setContentDescription(getString(R.string.music_close));
        close.setOnClickListener(v -> dismissAllowingStateLoss());
        attachPress(close, 0.9f);
        row.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        MusicTrack track = MusicController.get().current();
        if (track == null) {
            title.setText(R.string.music_no_track);
        } else {
            title.setText(track.displayTitle());
            artist.setText(track.displayArtist());
            ImgUtil.loadRounded(track.displayTitle(), track.cover, cover);
        }
        return row;
    }

    /** ② 快捷格一排 4（GZ 快捷格行转写）：行高 84dp、白方格圆角 8dp＋下方标签、格间距 20dp；
     * 保留星辰四格（下载/歌词/定时/加入歌单），不加「一起听」（已删功能） */
    private View buildQuickGrid() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(quickCell(R.drawable.ic_music_download, R.string.music_download, v -> {
            if (listener != null) listener.onDownload();
            dismissAllowingStateLoss();
        }), quickLp(0));
        row.addView(quickCell(R.drawable.ic_music_note, R.string.music_tab_lyric, v -> showPanel(PANEL_LYRIC)), quickLp(dp(20)));
        row.addView(quickCell(R.drawable.ic_music_timer, R.string.music_sleep, v -> showPanel(PANEL_SLEEP)), quickLp(dp(20)));
        row.addView(quickCell(R.drawable.ic_music_plus, R.string.music_add_to_playlist, v -> {
            if (listener != null) listener.onAddToPlaylist();
            dismissAllowingStateLoss();
        }), quickLp(dp(20)));
        return row;
    }

    private LinearLayout.LayoutParams quickLp(int startMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1f);
        lp.setMarginStart(startMargin);
        return lp;
    }

    private View quickCell(int icon, int label, View.OnClickListener click) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout boxTile = new FrameLayout(getContext());
        boxTile.setBackgroundResource(R.drawable.bg_sheet_quick);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(COLOR_TEXT));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        ivLp.gravity = Gravity.CENTER;
        boxTile.addView(iv, ivLp);
        cell.addView(boxTile, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView tv = text(getString(label), 12, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        cell.addView(tv, tlp);
        cell.setOnClickListener(click);
        attachPress(cell, 0.96f);
        return cell;
    }

    /** ③ 播放模式行（GZ PlayModeSegmented 转写）：一张白卡四段，选中＝浅粉底＋AccentRed 图标/文字，
     * 未选灰字；点击即时调 MusicController 切模式并迁移高亮（重建主面板） */
    private View buildModeSegment() {
        String[] labels = {getString(R.string.music_mode_seq), getString(R.string.music_mode_repeat_all),
                getString(R.string.music_mode_repeat_one), getString(R.string.music_mode_shuffle)};
        int[] icons = {R.drawable.ic_music_sequential, R.drawable.ic_music_repeat,
                R.drawable.ic_music_repeat_one, R.drawable.ic_music_shuffle};
        int[] values = {MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
                MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
        int cur = MusicController.get().getMode();
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = values[i] == cur;
            LinearLayout segCell = new LinearLayout(getContext());
            segCell.setOrientation(LinearLayout.VERTICAL);
            segCell.setGravity(Gravity.CENTER);
            if (sel) segCell.setBackgroundResource(R.drawable.bg_sheet_mode_selected);
            ImageView iv = new ImageView(getContext());
            iv.setImageResource(icons[i]);
            iv.setImageTintList(ColorStateList.valueOf(sel ? COLOR_ACCENT : COLOR_LOW));
            segCell.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView tv = text(labels[i], 11, sel ? COLOR_ACCENT : COLOR_LOW, sel);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(4);
            segCell.addView(tv, tlp);
            segCell.setOnClickListener(v -> {
                MusicController.get().setMode(values[idx]);
                showPanel(PANEL_MAIN);
            });
            attachPress(segCell, 0.96f);
            card.addView(segCell, new LinearLayout.LayoutParams(0, dp(52), 1f));
        }
        return card;
    }

    /** ④ 系统音量卡（GZ SystemVolumeCard 转写）：白卡单行——喇叭图标＋红细线滑杆＋百分比同行靠右（12sp），滑杆区高 48dp */
    private View buildVolumeCard() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(dp(16), 0, dp(16), 0);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        ImageView speaker = new ImageView(getContext());
        speaker.setImageResource(R.drawable.ic_widget_volume_medium);
        speaker.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        card.addView(speaker, new LinearLayout.LayoutParams(dp(20), dp(20)));
        SeekBar bar = new SeekBar(getContext());
        bar.setProgressDrawable(getContext().getDrawable(R.drawable.seekbar_sheet_volume));
        bar.setThumb(getContext().getDrawable(R.drawable.seekbar_sheet_volume_thumb));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        blp.setMarginStart(dp(12));
        blp.setMarginEnd(dp(12));
        card.addView(bar, blp);
        TextView pct = text("", 12, COLOR_MEDIUM, false);
        pct.setGravity(Gravity.END);
        card.addView(pct, new LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView cast = new ImageView(getContext());
        cast.setImageResource(R.drawable.ic_player_cast);
        cast.setImageTintList(ColorStateList.valueOf(COLOR_MEDIUM));
        LinearLayout.LayoutParams castLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        castLp.setMarginStart(dp(10));
        card.addView(cast, castLp);
        AudioManager am = audioManager();
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            bar.setMax(Math.max(1, max));
            bar.setProgress(cur);
            pct.setText(volumePct(cur, max));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    AudioManager m = audioManager();
                    if (m == null) return;
                    m.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                    pct.setText(volumePct(progress, seekBar.getMax()));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        return card;
    }

    private String volumePct(int cur, int max) {
        if (max <= 0) return "0%";
        return Math.round(cur * 100f / max) + "%";
    }

    private AudioManager audioManager() {
        try {
            Context c = getContext();
            return c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** ⑤ 设置卡（GZ SettingsListCard 转写）：白卡内单行列表，行高 56dp、行内边距 16dp，
     * 「名字＋值」同一行、值右对齐＋雪佛龙；分割线只在卡内行间、左右各收 16dp 与行边距对齐 */
    private View buildSettingsCard() {
        LinearLayout card = cardBox();
        MusicTrack track = MusicController.get().current();
        addRow(card, getString(R.string.music_equalizer), null, v -> {
            FragmentActivity host = getActivity();
            dismissAllowingStateLoss();
            if (host != null) MusicFxDialog.show(host);
        }, true);
        addRow(card, getString(R.string.music_player_theme), themeName(MusicStore.getPlayerTheme()),
                v -> showPanel(PANEL_THEME), false);
        addRow(card, getString(R.string.music_speed), speedName(), v -> showSpeedDialog(), false);
        card.addView(switchRow(getString(R.string.music_keep_screen_on), MusicStore.isPlayerKeepScreenOn(),
                on -> MusicStore.setPlayerKeepScreenOn(on)));
        addRow(card, getString(R.string.music_search_lyric), null, v -> {
            if (listener != null) listener.onSearchLyric();
            dismissAllowingStateLoss();
        }, false);
        addRow(card, getString(R.string.music_play_next), null, v -> {
            if (track != null) {
                MusicController.get().insertNext(track);
                Notify.show(R.string.music_play_next);
            }
            dismissAllowingStateLoss();
        }, false);
        addRow(card, getString(R.string.music_add_queue), null, v -> {
            if (track != null) {
                MusicController.get().appendTrack(track);
                Notify.show(R.string.music_added_queue);
            }
            dismissAllowingStateLoss();
        }, false);
        return card;
    }

    private void showSpeedDialog() {
        FragmentActivity host = getActivity();
        if (host == null) return;
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        List<String> opts = new ArrayList<>();
        int checked = 2;
        float cur = MusicController.get().getSpeed();
        for (int i = 0; i < speeds.length; i++) {
            opts.add(speedLabel(speeds[i]));
            if (Math.abs(speeds[i] - cur) < 0.01f) checked = i;
        }
        dismissAllowingStateLoss();
        MusicOptionsDialog.show(host, getString(R.string.music_speed), opts, checked,
                idx -> MusicController.get().setSpeed(speeds[idx]));
    }

    private String speedName() {
        return speedLabel(MusicController.get().getSpeed());
    }

    private static String speedLabel(float s) {
        return (s == Math.floor(s) ? String.format("%.1f", s) : String.valueOf(s)) + "×";
    }

    // ================= 主题子面板 =================

    private void buildThemePanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_player_theme)));
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        String cur = MusicStore.getPlayerTheme();
        for (int i = 0; i < THEME_IDS.length; i++) {
            final String id = THEME_IDS[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(10));
            row.addView(themeCard(id, THEME_NAMES[i], THEME_COLORS[i], id.equals(cur), v -> {
                MusicStore.setPlayerTheme(id);
                if (listener != null) listener.onThemeChanged();
                showPanel(PANEL_THEME);
            }), lp);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(14);
        box.addView(scroll, slp);
        // 选中流光 CD 时才出表面三段：浅色/深色/自动
        if (MusicStore.THEME_LUMINOUS_CD.equals(MusicStore.getPlayerTheme())) {
            String[] labels = {getString(R.string.music_surface_light), getString(R.string.music_surface_dark),
                    getString(R.string.music_surface_auto)};
            View seg = segment(labels, MusicStore.getCdSurface(), idx -> {
                MusicStore.setCdSurface(idx);
                if (listener != null) listener.onThemeChanged();
                showPanel(PANEL_THEME);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            box.addView(seg, lp);
        }
    }

    /** 主题卡：色块示意＋主题名＋选中勾；选中卡背景 150ms 渐变到浅粉（GZ 主题选中卡背景动画 150ms 转写） */
    private View themeCard(String id, int nameRes, int[] colors, boolean selected, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), COLOR_BORDER);
        if (selected) bg.setStroke(dp(2), COLOR_ACCENT);
        card.setBackground(bg);
        View block = new View(getContext());
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        grad.setCornerRadius(dp(8));
        block.setBackground(grad);
        card.addView(block, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView name = text(getString(nameRes), 13, selected ? COLOR_ACCENT : COLOR_TEXT, selected);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        card.addView(name, nlp);
        TextView check = text("✓", 14, COLOR_ACCENT, true);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        card.addView(check, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(20)));
        card.setContentDescription(getString(nameRes));
        card.setOnClickListener(click);
        attachPress(card, 0.96f);
        if (selected) {
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofObject(
                    new android.animation.ArgbEvaluator(), 0xFFFFFFFF, 0xFFFFEFF1);
            anim.setDuration(150);
            anim.addUpdateListener(a -> bg.setColor((int) a.getAnimatedValue()));
            anim.start();
        }
        return card;
    }

    private String themeName(String id) {
        for (int i = 0; i < THEME_IDS.length; i++) {
            if (THEME_IDS[i].equals(id)) return getString(THEME_NAMES[i]);
        }
        return getString(R.string.music_theme_classic);
    }

    // ================= 定时子面板 =================

    private void buildSleepPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_sleep)));
        LinearLayout card = cardBox();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(12);
        box.addView(card, clp);
        int[] values = {MusicPlaybackService.SLEEP_OFF, MusicPlaybackService.SLEEP_AFTER_CURRENT,
                MusicPlaybackService.SLEEP_15, MusicPlaybackService.SLEEP_30,
                MusicPlaybackService.SLEEP_60, MusicPlaybackService.SLEEP_90};
        int cur = MusicController.get().getSleep();
        for (int i = 0; i < values.length; i++) {
            final int value = values[i];
            boolean selected = value == cur;
            if (i > 0) addDivider(card);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            TextView label = text(MusicPlaybackService.sleepName(value), 15,
                    selected ? COLOR_ACCENT : COLOR_TEXT, selected);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));
            TextView check = text("✓", 15, COLOR_ACCENT, true);
            check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            row.addView(check);
            row.setOnClickListener(v -> {
                MusicController.get().setSleep(value);
                showPanel(PANEL_MAIN);
            });
            attachPress(row, 0.98f);
            card.addView(row);
        }
    }

    // ================= 歌词设置子面板 =================

    private void buildLyricPanel() {
        LinearLayout box = binding.container;
        box.addView(subHeader(getString(R.string.music_lyric_settings)));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(132));
        previewLp.topMargin = dp(12);
        box.addView(buildLyricPreview(), previewLp);
        box.addView(buildLyricSizeRow(), marginTop(dp(16)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_weight),
                new String[]{getString(R.string.music_weight_normal), getString(R.string.music_weight_bold),
                        getString(R.string.music_weight_black)},
                MusicStore.getLyricWeight(), idx -> {
                    MusicStore.setLyricWeight(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_align),
                new String[]{getString(R.string.music_align_left), getString(R.string.music_align_center),
                        getString(R.string.music_align_right)},
                MusicStore.getLyricAlign(), idx -> {
                    MusicStore.setLyricAlign(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_blur),
                new String[]{getString(R.string.music_blur_off), getString(R.string.music_blur_light),
                        getString(R.string.music_blur_medium)},
                MusicStore.getLyricBlur(), idx -> {
                    MusicStore.setLyricBlur(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        box.addView(lyricSegRow(getString(R.string.music_lyric_primary),
                new String[]{getString(R.string.music_primary_original), getString(R.string.music_primary_translation),
                        getString(R.string.music_primary_romanization)},
                MusicStore.getLyricPrimary(), idx -> {
                    MusicStore.setLyricPrimary(idx);
                    fireLyricChanged();
                    showPanel(PANEL_LYRIC);
                }), marginTop(dp(14)));
        // 主歌词不可用提示（GZ：10sp、alpha 0.5）：选了译/音作主时才显示
        if (MusicStore.getLyricPrimary() != MusicStore.LYRIC_PRIMARY_ORIGINAL) {
            TextView hint = text(getString(R.string.music_lyric_primary_fallback_hint), 10, COLOR_MEDIUM, false);
            hint.setAlpha(0.5f);
            box.addView(hint, marginTop(dp(8)));
        }
        LinearLayout card = cardBox();
        card.addView(switchRow(getString(R.string.music_show_translation), MusicStore.isShowTranslation(),
                on -> {
                    MusicStore.setShowTranslation(on);
                    fireLyricChanged();
                }));
        addDivider(card);
        card.addView(switchRow(getString(R.string.music_show_romanization), MusicStore.isShowRomanization(),
                on -> {
                    MusicStore.setShowRomanization(on);
                    fireLyricChanged();
                }));
        addDivider(card);
        // 高级动效行高 60dp（GZ 歌词设置行高口径）
        card.addView(switchRow(getString(R.string.music_advanced_fx), MusicStore.isAdvancedFx(), 60,
                on -> {
                    MusicStore.setAdvancedFx(on);
                    fireLyricChanged();
                }));
        box.addView(card, marginTop(dp(16)));
        TextView restore = text(getString(R.string.music_restore_defaults), 13, COLOR_ACCENT, false);
        restore.setGravity(Gravity.CENTER);
        restore.setBackgroundResource(R.drawable.bg_sheet_card);
        restore.setOnClickListener(v -> {
            MusicStore.setLyricFontSize(24f);
            MusicStore.setLyricWeight(MusicStore.LYRIC_WEIGHT_NORMAL);
            MusicStore.setLyricAlign(MusicStore.LYRIC_ALIGN_LEFT);
            MusicStore.setLyricBlur(MusicStore.LYRIC_BLUR_LIGHT);
            MusicStore.setLyricPrimary(MusicStore.LYRIC_PRIMARY_ORIGINAL);
            MusicStore.setShowTranslation(true);
            MusicStore.setShowRomanization(false);
            MusicStore.setAdvancedFx(true);
            fireLyricChanged();
            showPanel(PANEL_LYRIC);
        });
        attachPress(restore, 0.96f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        rlp.topMargin = dp(16);
        box.addView(restore, rlp);
    }

    /** 顶部预览区（GZ 转写）：高 132dp、内边 14/8，一行样例歌词按当前样式实时渲染 */
    private View buildLyricPreview() {
        FrameLayout frame = new FrameLayout(getContext());
        frame.setBackgroundResource(R.drawable.bg_sheet_card);
        frame.setPadding(dp(14), dp(8), dp(14), dp(8));
        lyricPreview = text(getString(R.string.music_lyric_preview_sample), 24, COLOR_TEXT, false);
        lyricPreview.setMaxLines(2);
        lyricPreview.setEllipsize(TextUtils.TruncateAt.END);
        frame.addView(lyricPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyPreview();
        return frame;
    }

    private void applyPreview() {
        if (lyricPreview == null) return;
        lyricPreview.setTextSize(MusicStore.getLyricFontSize());
        int w = MusicStore.getLyricWeight();
        lyricPreview.setTypeface(w == MusicStore.LYRIC_WEIGHT_NORMAL ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        lyricPreview.getPaint().setFakeBoldText(w == MusicStore.LYRIC_WEIGHT_BLACK);
        lyricPreview.getPaint().setTextSkewX(-0.12f);
        int a = MusicStore.getLyricAlign();
        int horizontal = a == MusicStore.LYRIC_ALIGN_CENTER ? Gravity.CENTER_HORIZONTAL
                : a == MusicStore.LYRIC_ALIGN_RIGHT ? Gravity.END : Gravity.START;
        lyricPreview.setGravity(Gravity.CENTER_VERTICAL | horizontal);
    }

    /** 字号滑杆 16–40（取引擎钳制范围；GZ 面板 14–32 是其封面歌词专用滑杆，星辰字号统一一键故用 16–40） */
    private View buildLyricSizeRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.music_lyric_font_size), 14, COLOR_TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lyricSizeValue = text(String.valueOf(Math.round(MusicStore.getLyricFontSize())), 13, COLOR_LOW, false);
        head.addView(lyricSizeValue);
        row.addView(head);
        SeekBar bar = new SeekBar(getContext());
        bar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(COLOR_ACCENT));
        bar.setMax(40 - 16);
        float cur = Math.max(16f, Math.min(40f, MusicStore.getLyricFontSize()));
        bar.setProgress(Math.round(cur) - 16);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float v = progress + 16f;
                if (lyricPreview != null) lyricPreview.setTextSize(v);
                if (lyricSizeValue != null) lyricSizeValue.setText(String.valueOf(Math.round(v)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                MusicStore.setLyricFontSize(seekBar.getProgress() + 16f);
                if (lyricSizeValue != null) {
                    lyricSizeValue.setText(String.valueOf(Math.round(MusicStore.getLyricFontSize())));
                }
                applyPreview();
                fireLyricChanged();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        row.addView(bar, blp);
        return row;
    }

    private View lyricSegRow(String label, String[] options, int selected, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(text(label, 14, COLOR_TEXT, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.addView(segment(options, selected, onPick), lp);
        return row;
    }

    private void fireLyricChanged() {
        if (listener != null) listener.onLyricStyleChanged();
    }

    // ================= 通用构建件 =================

    /** 子面板头（GlassSheetPanelKt SheetSubPanelHeader 转写）：高 44dp，圆形钮 40dp（按压缩放 0.9）＋标题（字距 −0.3）；返回回主面板 */
    private View subHeader(String title) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        TextView back = text("‹", 22, COLOR_TEXT, false);
        back.setGravity(Gravity.CENTER);
        back.setBackgroundResource(R.drawable.bg_sheet_circle_btn);
        back.setContentDescription(getString(R.string.music_back));
        back.setOnClickListener(v -> showPanel(PANEL_MAIN));
        attachPress(back, 0.9f);
        row.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tv = text(title, 16, COLOR_TEXT, true);
        tv.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(8));
        row.addView(tv, tlp);
        return row;
    }

    /** 通用段控（粗细/对齐/模糊/主歌词/CD 表面共用）：轨道 inset 底、格高 40dp、选中白格＋主字加粗 */
    private LinearLayout segment(String[] labels, int selected, IntConsumer onPick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.bg_sheet_seg_track);
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            TextView tv = text(labels[i], 13, sel ? COLOR_TEXT : COLOR_LOW, sel);
            tv.setGravity(Gravity.CENTER);
            if (sel) tv.setBackgroundResource(R.drawable.bg_sheet_seg_selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            if (i > 0) lp.setMarginStart(dp(3));
            tv.setOnClickListener(v -> onPick.accept(idx));
            attachPress(tv, 0.96f);
            row.addView(tv, lp);
        }
        return row;
    }

    /** 分组白卡容器：内边距归零，行自带 16dp 横向内边距（分割线收边与行边距对齐靠此口径） */
    private LinearLayout cardBox() {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_sheet_card);
        card.setPadding(0, 0, 0, 0);
        return card;
    }

    /**
     * 设置行（GZ SettingsListRow 转写）：单行横排——标签 weight 1、值右对齐同排、雪佛龙 20dp。
     * 旧版根因（实拍 112767 值掉行）：旧 addRow 给标签 TextView 设固定高 52dp 且文本顶对齐、
     * 值 TextView 无重力约束居中错位，看着像两行；且旧行没有横向内边距、值贴右缘被窗口裁切。
     * 现行：整行固定高 56dp＋整行 gravity 居中，标签/值同处一行中线。
     */
    private void addRow(LinearLayout card, String label, String value, View.OnClickListener click, boolean first) {
        if (!first) addDivider(card);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView tv = text(label, 15, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, new LinearLayout.LayoutParams(0, dp(56), 1f));
        if (value != null) {
            TextView vv = text(value, 14, COLOR_LOW, false);
            vv.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(vv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));
        }
        ImageView chevron = new ImageView(getContext());
        chevron.setImageResource(R.drawable.ic_music_chevron);
        chevron.setImageTintList(ColorStateList.valueOf(COLOR_LOW));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(20), dp(20));
        clp.setMarginStart(dp(6));
        row.addView(chevron, clp);
        row.setOnClickListener(click);
        attachPress(row, 0.98f);
        card.addView(row);
    }

    private View switchRow(String label, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        return switchRow(label, checked, 56, onChange);
    }

    private View switchRow(String label, boolean checked, int heightDp, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        TextView tv = text(label, 15, COLOR_TEXT, false);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
        SwitchCompat sw = new SwitchCompat(getContext());
        sw.setChecked(checked);
        sw.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{COLOR_ACCENT, 0xFFD9D9DE}));
        sw.setOnCheckedChangeListener((button, on) -> onChange.accept(on));
        row.addView(sw);
        return row;
    }

    /** 分割线（GZ SettingsListDivider 转写）：只出现在卡内行间，高 0.5dp、色黑 0.06、左右各收 16dp 与行内边距对齐、通卡等长 */
    private void addDivider(LinearLayout card) {
        View div = new View(getContext());
        div.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.5f * density())));
        lp.setMarginStart(dp(16));
        lp.setMarginEnd(dp(16));
        card.addView(div, lp);
    }

    /** GZ 通用按压反馈（MotionKt：260ms、缩放 0.96；圆钮 0.9 由调用方传参）：按下缩、松开/取消回 */
    private void attachPress(View view, float scale) {
        view.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(260).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
            }
            return false;
        });
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView tv = new TextView(getContext());
        tv.setText(content);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private GradientDrawable cardBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private float density() {
        Context c = getContext();
        return c == null ? 1f : c.getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return Math.round(v * density());
    }
}
