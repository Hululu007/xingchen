package com.fongmi.android.tv.ui.music;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.MusicPlaylist;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.music.MusicAudioInfo;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.service.MusicPlaybackService;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 播放页工具面板（照瓜子 PlayerToolsSheet 复刻）：底部弹层，深面。
 * 主面板＝歌曲信息头→快捷行（下载/歌词/定时/加到歌单；瓜子的「一起听」星辰不搬）→
 * 播放模式四段→系统音量卡→设置卡（歌曲信息行＋格子：均衡器/播放页主题/外观设置
 * ＋不熄屏开关）。子面板：播放页主题（五张 112dp 迷你预览卡）、外观（流体/速度/律动）、
 * 歌词样式（全屏套＋封面套）、定时（分钟档＋本首结束＋自定义）、加到歌单。
 * 瓜子格子第四项「自定义封面」星辰无对应能力，不放假按钮（对照表标注）。
 */
public final class PlayerToolsDialog {

    public enum Panel {MAIN, THEME, SLEEP, LYRIC_STYLE, APPEARANCE, PLAYLIST}

    private static final int SHEET_BG = 0xFF1C1C1E;
    private static final int CARD_BG = 0xFF2C2C2E;
    private static final int SELECTED_BG = 0xFF3A3A3C;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_55 = 0x8CFFFFFF;
    private static final int TEXT_40 = 0x66FFFFFF;
    private static final int ACCENT = 0xFFFA243C;

    private PlayerToolsDialog() {
    }

    public static void show(Activity activity) {
        show(activity, Panel.MAIN);
    }

    public static void show(Activity activity, Panel panel) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new Session(activity, panel).open();
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static final class Session {
        private final Activity activity;
        private final BottomSheetDialog dialog;
        private final LinearLayout content;
        private Panel panel;

        Session(Activity activity, Panel start) {
            this.activity = activity;
            this.panel = start;
            dialog = new BottomSheetDialog(activity);
            ScrollView scroll = new ScrollView(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(SHEET_BG);
            bg.setCornerRadii(new float[]{dp(activity, 24), dp(activity, 24), dp(activity, 24), dp(activity, 24),
                    0, 0, 0, 0});
            scroll.setBackground(bg);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 26));
            scroll.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.setContentView(scroll);
        }

        void open() {
            render();
            dialog.show();
        }

        private void go(Panel next) {
            panel = next;
            render();
        }

        private void render() {
            content.removeAllViews();
            switch (panel) {
                case THEME:
                    renderTheme();
                    break;
                case SLEEP:
                    renderSleep();
                    break;
                case LYRIC_STYLE:
                    renderLyricStyle();
                    break;
                case APPEARANCE:
                    renderAppearance();
                    break;
                case PLAYLIST:
                    renderPlaylist();
                    break;
                case MAIN:
                default:
                    renderMain();
                    break;
            }
        }

        private MusicTrack track() {
            return MusicController.get().current();
        }

        private TextView header(String title, boolean back) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (back) {
                TextView backTv = text("返回", 13, 400, TEXT_55);
                backTv.setPadding(0, dp(activity, 6), dp(activity, 14), dp(activity, 6));
                backTv.setOnClickListener(v -> go(Panel.MAIN));
                row.addView(backTv);
            }
            TextView titleTv = text(title, 16, 700, TEXT);
            row.addView(titleTv);
            content.addView(row);
            return titleTv;
        }

        private TextView text(String s, float sp, int weight, int color) {
            return GzViews.text(activity, s, sp, weight, color);
        }

        private GradientDrawable cardBg(int color, float radiusDp) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(dp(activity, radiusDp));
            return bg;
        }

        private void renderMain() {
            MusicTrack t = track();
            LinearLayout hero = new LinearLayout(activity);
            hero.setOrientation(LinearLayout.HORIZONTAL);
            hero.setGravity(Gravity.CENTER_VERTICAL);
            hero.setBackground(cardBg(CARD_BG, 16));
            hero.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
            content.addView(hero, lp(MATCH, WRAP, 0, 0, 0, 14));
            ImageView cover = new ImageView(activity);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable coverBg = new GradientDrawable();
            coverBg.setColor(0xFF3A3A3C);
            coverBg.setCornerRadius(dp(activity, 8));
            cover.setBackground(coverBg);
            cover.setClipToOutline(true);
            hero.addView(cover, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            if (t != null && t.cover != null && !t.cover.isEmpty()) {
                Glide.with(activity).load(t.cover).override(128, 128).into(cover);
            }
            LinearLayout heroText = new LinearLayout(activity);
            heroText.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            htLp.leftMargin = dp(activity, 12);
            hero.addView(heroText, htLp);
            TextView heroTitle = text(t == null ? "" : t.displayTitle(), 15, 600, TEXT);
            heroTitle.setSingleLine(true);
            heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heroText.addView(heroTitle);
            TextView heroArtist = text(t == null ? "" : t.displayArtist(), 13, 400, TEXT_55);
            heroArtist.setSingleLine(true);
            heroArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heroText.addView(heroArtist);
            MusicAudioInfo info = MusicController.get().audioInfo();
            TextView heroInfo = text(info == null ? "" : info.describe(), 11, 400, TEXT_40);
            heroInfo.setSingleLine(true);
            heroInfo.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heroText.addView(heroInfo);

            LinearLayout quick = new LinearLayout(activity);
            quick.setOrientation(LinearLayout.HORIZONTAL);
            quick.setBackground(cardBg(CARD_BG, 16));
            LinearLayout.LayoutParams quickLp = lp(MATCH, dp(activity, 84), 0, 0, 0, 14);
            content.addView(quick, quickLp);
            quick.addView(quickItem(R.drawable.ic_gz_download, "下载", () -> {
                MusicTrack cur = track();
                if (cur != null) {
                    MusicDownloadStore.download(cur);
                    Toast.makeText(activity, "已开始下载", Toast.LENGTH_SHORT).show();
                }
            }));
            quick.addView(quickItem(R.drawable.ic_gz_lyric, "歌词", () -> go(Panel.LYRIC_STYLE)));
            quick.addView(quickItem(R.drawable.ic_gz_timer, "定时", () -> go(Panel.SLEEP)));
            quick.addView(quickItem(R.drawable.ic_gz_add, "加到歌单", () -> go(Panel.PLAYLIST)));

            content.addView(sectionLabel("播放模式"));
            content.addView(modeSegmented(), lp(MATCH, dp(activity, 40), 0, 4, 0, 14));

            content.addView(sectionLabel("系统音量"));
            content.addView(volumeCard(), lp(MATCH, dp(activity, 52), 0, 4, 0, 14));

            content.addView(sectionLabel("设置"));
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBg(CARD_BG, 16));
            card.setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), dp(activity, 6));
            content.addView(card, lp(MATCH, WRAP, 0, 4, 0, 0));
            card.addView(infoRow(t, info));
            card.addView(gridRow());
            card.addView(switchRow("播放页不熄屏", MusicStore.isPlayerKeepScreenOn(), on -> {
                MusicStore.setPlayerKeepScreenOn(on);
                if (on) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }));
        }

        private View infoRow(MusicTrack t, MusicAudioInfo info) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            TextView label = text("歌曲信息", 13, 500, TEXT);
            row.addView(label);
            TextView detail = text(info == null ? "" : info.describe(), 12, 400, TEXT_55);
            detail.setGravity(Gravity.RIGHT);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            dLp.leftMargin = dp(activity, 12);
            row.addView(detail, dLp);
            return row;
        }

        private View gridRow() {
            LinearLayout grid = new LinearLayout(activity);
            grid.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(gridItem(R.drawable.ic_gz_quality, "均衡器", () -> {
                dialog.dismiss();
                MusicController.get().openEqualizer(activity);
            }));
            grid.addView(gridItem(R.drawable.ic_gz_info, "播放页主题", () -> go(Panel.THEME)));
            grid.addView(gridItem(R.drawable.ic_gz_surface, "外观设置", () -> go(Panel.APPEARANCE)));
            return grid;
        }

        private View gridItem(int res, String label, Runnable action) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(activity, 64), 1f);
            item.setLayoutParams(lp);
            ImageView iv = GzViews.icon(activity, res, 20, 1f);
            item.addView(iv);
            TextView tv = text(label, 13, 400, TEXT);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(WRAP, WRAP);
            tLp.topMargin = dp(activity, 5);
            item.addView(tv, tLp);
            item.setOnClickListener(v -> action.run());
            GzViews.pressScale(item, 0.98f);
            return item;
        }

        private View quickItem(int res, String label, Runnable action) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH, 1f));
            ImageView iv = GzViews.icon(activity, res, 24, 1f);
            item.addView(iv);
            TextView tv = text(label, 11, 400, TEXT_55);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(WRAP, WRAP);
            tLp.topMargin = dp(activity, 5);
            item.addView(tv, tLp);
            item.setOnClickListener(v -> action.run());
            GzViews.pressScale(item, 0.96f);
            return item;
        }

        private View modeSegmented() {
            String[] labels = {"顺序", "循环", "单曲", "随机"};
            int[] modes = {MusicQueue.MODE_SEQUENTIAL, MusicQueue.MODE_REPEAT_ALL,
                    MusicQueue.MODE_REPEAT_ONE, MusicQueue.MODE_SHUFFLE};
            LinearLayout seg = new LinearLayout(activity);
            seg.setOrientation(LinearLayout.HORIZONTAL);
            seg.setBackground(cardBg(CARD_BG, 12));
            seg.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
            int current = MusicController.get().getMode();
            for (int i = 0; i < labels.length; i++) {
                final int mode = modes[i];
                TextView tv = text(labels[i], 13, 500, mode == current ? TEXT : TEXT_55);
                tv.setGravity(Gravity.CENTER);
                if (mode == current) tv.setBackground(cardBg(SELECTED_BG, 9));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                seg.addView(tv, lp);
                tv.setOnClickListener(v -> {
                    MusicController.get().setMode(mode);
                    render();
                });
            }
            return seg;
        }

        private View volumeCard() {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(cardBg(CARD_BG, 16));
            card.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);
            AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            ImageView low = GzViews.icon(activity, R.drawable.ic_gz_vol_low, 20, 0.7f);
            card.addView(low);
            VolumeSliderView slider = new VolumeSliderView(activity);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(activity, 24), 1f);
            sLp.leftMargin = dp(activity, 10);
            sLp.rightMargin = dp(activity, 10);
            card.addView(slider, sLp);
            ImageView high = GzViews.icon(activity, R.drawable.ic_gz_vol_high, 20, 0.7f);
            card.addView(high);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                slider.setFraction(max > 0 ? cur / (float) max : 0f);
                slider.setListener(fraction -> am.setStreamVolume(
                        AudioManager.STREAM_MUSIC, Math.round(fraction * max), 0));
            }
            return card;
        }

        private void renderTheme() {
            header("播放页主题", true);
            TextView note = text("选择播放详情页的整体布局，新增主题会自动出现在这里。", 11, 400, TEXT_40);
            LinearLayout.LayoutParams nLp = lp(MATCH, WRAP, 0, 6, 0, 12);
            content.addView(note, nLp);
            String[] ids = {MusicPlayerSettings.THEME_CLASSIC, MusicPlayerSettings.THEME_APPLE_MUSIC,
                    MusicPlayerSettings.THEME_COVER_IMMERSIVE, MusicPlayerSettings.THEME_LUMINOUS_CD,
                    MusicPlayerSettings.THEME_RECORD_SHELF};
            String[] names = {"经典", "Apple Music", "封面卡片", "流光 CD", "黑胶唱片架"};
            String current = MusicPlayerSettings.getTheme();
            MusicTrack t = track();
            for (int i = 0; i < ids.length; i++) {
                final String id = ids[i];
                boolean selected = id.equals(current)
                        || (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(id)
                        && current != null && current.startsWith("luminous_cd"));
                FrameLayout card = new FrameLayout(activity);
                card.setBackground(cardBg(selected ? SELECTED_BG : CARD_BG, 16));
                card.setOnClickListener(v -> {
                    MusicPlayerSettings.setTheme(id);
                    if (activity instanceof MusicPlayerActivity) {
                        ((MusicPlayerActivity) activity).recreateTheme();
                    }
                    render();
                });
                GzViews.pressScale(card, 0.98f);
                LinearLayout.LayoutParams cLp = lp(MATCH, dp(activity, 112), 0, 0, 0, 14);
                content.addView(card, cLp);
                ThemePreviewView preview = new ThemePreviewView(activity, id,
                        t == null ? null : t.cover);
                FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(MATCH, MATCH);
                card.addView(preview, pLp);
                TextView name = text(names[i], 12, 500, TEXT);
                FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(WRAP, WRAP);
                nameLp.gravity = Gravity.LEFT | Gravity.BOTTOM;
                nameLp.leftMargin = dp(activity, 9);
                nameLp.bottomMargin = dp(activity, 8);
                name.setLayoutParams(nameLp);
                card.addView(name);
                if (selected) {
                    FrameLayout badge = new FrameLayout(activity);
                    GradientDrawable badgeBg = new GradientDrawable();
                    badgeBg.setColor(ACCENT);
                    badgeBg.setShape(GradientDrawable.OVAL);
                    badge.setBackground(badgeBg);
                    FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                            dp(activity, 20), dp(activity, 20));
                    bLp.gravity = Gravity.RIGHT | Gravity.TOP;
                    bLp.rightMargin = dp(activity, 10);
                    bLp.topMargin = dp(activity, 10);
                    badge.setLayoutParams(bLp);
                    ImageView check = GzViews.icon(activity, R.drawable.ic_gz_check, 12, 1f);
                    FrameLayout.LayoutParams chLp = new FrameLayout.LayoutParams(WRAP, WRAP);
                    chLp.gravity = Gravity.CENTER;
                    check.setLayoutParams(chLp);
                    badge.addView(check);
                    card.addView(badge);
                }
            }
        }

        private void renderSleep() {
            header("定时关闭", true);
            String[] labels = {"关闭", "本首结束", "10 分钟", "15 分钟", "20 分钟", "30 分钟",
                    "40 分钟", "60 分钟", "90 分钟", "自定义"};
            int[] codes = {MusicPlaybackService.SLEEP_OFF, MusicPlaybackService.SLEEP_AFTER_CURRENT,
                    MusicPlaybackService.SLEEP_10, MusicPlaybackService.SLEEP_15,
                    MusicPlaybackService.SLEEP_20, MusicPlaybackService.SLEEP_30,
                    MusicPlaybackService.SLEEP_40, MusicPlaybackService.SLEEP_60,
                    MusicPlaybackService.SLEEP_90, -1};
            int current = MusicController.get().getSleep();
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams listLp = lp(MATCH, WRAP, 0, 10, 0, 0);
            content.addView(list, listLp);
            for (int i = 0; i < labels.length; i++) {
                final int code = codes[i];
                boolean selected = code >= 0 && code == current
                        || code == -1 && current >= MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE;
                TextView row = text(labels[i], 14, 500, selected ? TEXT : TEXT_55);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
                row.setBackground(cardBg(selected ? SELECTED_BG : CARD_BG, 12));
                LinearLayout.LayoutParams rLp = lp(MATCH, dp(activity, 46), 0, 0, 0, 8);
                list.addView(row, rLp);
                row.setOnClickListener(v -> {
                    if (code == -1) {
                        askCustomMinutes();
                    } else {
                        MusicController.get().setSleep(code);
                        render();
                    }
                });
            }
            long remain = MusicController.get().sleepRemainingMs();
            if (remain > 0) {
                TextView info = text("剩余约 " + Math.max(1, remain / 60000) + " 分钟", 12, 400, TEXT_40);
                content.addView(info, lp(MATCH, WRAP, 0, 2, 0, 0));
            }
        }

        private void askCustomMinutes() {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setHint("分钟（1-999）");
            input.setTextColor(TEXT);
            input.setHintTextColor(TEXT_40);
            new AlertDialog.Builder(activity)
                    .setTitle("自定义定时")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始", (d, w) -> {
                        try {
                            int minutes = Integer.parseInt(input.getText().toString().trim());
                            if (minutes >= 1 && minutes <= 999) {
                                MusicController.get().setSleep(
                                        MusicPlaybackService.SLEEP_CUSTOM_MINUTES_BASE + minutes);
                                render();
                            }
                        } catch (Throwable ignored) {
                        }
                    })
                    .show();
        }

        private void renderAppearance() {
            header("外观设置", true);
            LinearLayout card = cardColumn();
            card.addView(switchRow("流体背景", MusicPlayerSettings.isFluidBg(), on -> {
                MusicPlayerSettings.setFluidBg(on);
                syncBeatFlag();
                refreshHostAmbient();
            }));
            card.addView(chipRow("流速", new String[]{"慢", "常", "快"},
                    MusicPlayerSettings.getFluidSpeed(), idx -> {
                        MusicPlayerSettings.setFluidSpeed(idx);
                        refreshHostAmbient();
                    }));
            card.addView(switchRow("律动", MusicPlayerSettings.isFluidBeat(), on -> {
                MusicStore.setFluidBeat(on);
                syncBeatFlag();
                refreshHostAmbient();
            }));
        }

        private void syncBeatFlag() {
            com.fongmi.android.tv.service.MusicBeatState.requestEnabled =
                    MusicPlayerSettings.isFluidBg() && MusicPlayerSettings.isFluidBeat();
        }

        private void refreshHostAmbient() {
            if (activity instanceof MusicPlayerActivity) {
                ((MusicPlayerActivity) activity).recreateTheme();
            }
            render();
        }

        private void renderLyricStyle() {
            header("歌词设置", true);
            LinearLayout card = cardColumn();
            card.addView(sliderRow("全屏字号", 16, 40, Math.round(MusicStore.getLyricFontSize()),
                    value -> {
                        MusicStore.setLyricFontSize(value);
                        refreshLyricPage();
                    }));
            card.addView(chipRow("字重", new String[]{"常规", "粗", "黑"},
                    MusicStore.getLyricWeight(), idx -> {
                        MusicStore.setLyricWeight(idx);
                        refreshLyricPage();
                        render();
                    }));
            card.addView(chipRow("对齐", new String[]{"左", "中", "右"},
                    MusicStore.getLyricAlign(), idx -> {
                        MusicStore.setLyricAlign(idx);
                        refreshLyricPage();
                        render();
                    }));
            card.addView(chipRow("主显", new String[]{"原文", "译文", "音译"},
                    MusicStore.getLyricPrimary(), idx -> {
                        MusicStore.setLyricPrimary(idx);
                        refreshLyricPage();
                        render();
                    }));
            card.addView(switchRow("显示译文", MusicStore.isShowTranslation(), on -> {
                MusicStore.setShowTranslation(on);
                refreshLyricPage();
            }));
            card.addView(switchRow("显示音译", MusicStore.isShowRomanization(), on -> {
                MusicStore.setShowRomanization(on);
                refreshLyricPage();
            }));
            card.addView(switchRow("高级动效", MusicStore.isAdvancedFx(), on -> {
                MusicStore.setAdvancedFx(on);
                refreshLyricPage();
            }));
            card.addView(sliderRow("封面字号", 14, 32, Math.round(MusicStore.getCoverLyricFontSize()),
                    value -> MusicStore.setCoverLyricFontSize(value)));
            card.addView(chipRow("封面模糊", new String[]{"关", "轻", "中"},
                    MusicStore.getCoverLyricBlurLevel(), idx -> {
                        MusicStore.setCoverLyricBlurLevel(idx);
                        render();
                    }));
        }

        private void refreshLyricPage() {
            if (MusicLyricActivity.isShowing()) MusicLyricActivity.reloadLyrics();
        }

        private void renderPlaylist() {
            header("加到歌单", true);
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            content.addView(list, lp(MATCH, WRAP, 0, 10, 0, 0));
            TextView create = text("＋ 新建歌单", 14, 500, TEXT);
            create.setGravity(Gravity.CENTER_VERTICAL);
            create.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
            create.setBackground(cardBg(CARD_BG, 12));
            list.addView(create, lp(MATCH, dp(activity, 46), 0, 0, 0, 8));
            create.setOnClickListener(v -> askNewPlaylist(list));
            MusicLibrary.playlists(playlists -> activity.runOnUiThread(() -> {
                if (dialog.isShowing()) fillPlaylists(list, playlists);
            }));
        }

        private void fillPlaylists(LinearLayout list, List<MusicPlaylist> playlists) {
            while (list.getChildCount() > 1) list.removeViewAt(1);
            if (playlists == null) return;
            for (MusicPlaylist playlist : playlists) {
                String label = playlist.getName()
                        + (playlist.trackCount >= 0 ? " · " + playlist.trackCount + " 首" : "");
                TextView row = text(label, 14, 500, TEXT);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
                row.setBackground(cardBg(CARD_BG, 12));
                list.addView(row, lp(MATCH, dp(activity, 46), 0, 0, 0, 8));
                row.setOnClickListener(v -> {
                    MusicTrack cur = track();
                    if (cur == null) return;
                    MusicLibrary.addToPlaylist(playlist.getId(), cur, ok -> activity.runOnUiThread(() ->
                            Toast.makeText(activity, Boolean.TRUE.equals(ok) ? "已加入歌单" : "加入失败",
                                    Toast.LENGTH_SHORT).show()));
                });
            }
        }

        private void askNewPlaylist(LinearLayout list) {
            EditText input = new EditText(activity);
            input.setHint("歌单名称");
            input.setTextColor(TEXT);
            input.setHintTextColor(TEXT_40);
            new AlertDialog.Builder(activity)
                    .setTitle("新建歌单")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("创建", (d, w) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) return;
                        MusicLibrary.createPlaylist(name, created -> activity.runOnUiThread(() -> {
                            MusicTrack cur = track();
                            if (created != null && cur != null) {
                                MusicLibrary.addToPlaylist(created.getId(), cur, ok -> { });
                                Toast.makeText(activity, "已创建并加入", Toast.LENGTH_SHORT).show();
                            }
                            if (dialog.isShowing()) render();
                        }));
                    })
                    .show();
        }

        private LinearLayout cardColumn() {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(cardBg(CARD_BG, 16));
            card.setPadding(dp(activity, 16), dp(activity, 6), dp(activity, 16), dp(activity, 6));
            content.addView(card, lp(MATCH, WRAP, 0, 10, 0, 0));
            return card;
        }

        private TextView sectionLabel(String s) {
            TextView tv = text(s, 13, 600, TEXT_55);
            LinearLayout.LayoutParams lp = lp(WRAP, WRAP, 0, 2, 0, 0);
            tv.setLayoutParams(lp);
            return tv;
        }

        private interface BoolAction {
            void run(boolean on);
        }

        private interface IntAction {
            void run(int value);
        }

        private View switchRow(String label, boolean on, BoolAction action) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            TextView tv = text(label, 13, 500, TEXT);
            row.addView(tv, new LinearLayout.LayoutParams(0, WRAP, 1f));
            FrameLayout pill = new FrameLayout(activity);
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(on ? ACCENT : 0xFF48484A);
            pillBg.setCornerRadius(dp(activity, 14));
            pill.setBackground(pillBg);
            pill.setLayoutParams(new LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 28)));
            View knob = new View(activity);
            GradientDrawable knobBg = new GradientDrawable();
            knobBg.setColor(Color.WHITE);
            knobBg.setShape(GradientDrawable.OVAL);
            knob.setBackground(knobBg);
            FrameLayout.LayoutParams kLp = new FrameLayout.LayoutParams(dp(activity, 24), dp(activity, 24));
            kLp.gravity = (on ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
            kLp.leftMargin = dp(activity, 2);
            kLp.rightMargin = dp(activity, 2);
            knob.setLayoutParams(kLp);
            pill.addView(knob);
            row.addView(pill);
            final boolean[] state = {on};
            row.setOnClickListener(v -> {
                state[0] = !state[0];
                action.run(state[0]);
                GradientDrawable nb = new GradientDrawable();
                nb.setColor(state[0] ? ACCENT : 0xFF48484A);
                nb.setCornerRadius(dp(activity, 14));
                pill.setBackground(nb);
                FrameLayout.LayoutParams nk = (FrameLayout.LayoutParams) knob.getLayoutParams();
                nk.gravity = (state[0] ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
                knob.setLayoutParams(nk);
            });
            return row;
        }

        private View chipRow(String label, String[] options, int selected, IntAction action) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            TextView tv = text(label, 13, 500, TEXT);
            row.addView(tv, new LinearLayout.LayoutParams(dp(activity, 64), WRAP));
            for (int i = 0; i < options.length; i++) {
                final int idx = i;
                TextView chip = text(options[i], 12, 500, i == selected ? TEXT : TEXT_55);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
                if (i == selected) chip.setBackground(cardBg(SELECTED_BG, 50));
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(WRAP, WRAP);
                if (i > 0) cLp.leftMargin = dp(activity, 8);
                row.addView(chip, cLp);
                chip.setOnClickListener(v -> action.run(idx));
            }
            return row;
        }

        private View sliderRow(String label, int min, int max, int value, IntAction action) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
            TextView tv = text(label, 13, 500, TEXT);
            row.addView(tv, new LinearLayout.LayoutParams(dp(activity, 64), WRAP));
            SeekBar bar = new SeekBar(activity);
            bar.setMax(max - min);
            bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                bar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            }
            row.addView(bar, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView val = text(String.valueOf(value), 12, 500, TEXT_55);
            val.setGravity(Gravity.RIGHT);
            row.addView(val, new LinearLayout.LayoutParams(dp(activity, 30), WRAP));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    val.setText(String.valueOf(min + progress));
                    if (fromUser) action.run(min + progress);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            return row;
        }

        private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
        private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

        private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = dp(activity, l);
            lp.topMargin = dp(activity, t);
            lp.rightMargin = dp(activity, r);
            lp.bottomMargin = dp(activity, b);
            return lp;
        }
    }

    /** 主题迷你预览卡（照瓜子 ThemeMiniPreview 活画）：按主题画简化构图＋当前封面。 */
    private static final class ThemePreviewView extends View {
        private final String themeId;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private Bitmap cover;
        private String coverUrl;

        ThemePreviewView(Context context, String themeId, String coverUrl) {
            super(context);
            this.themeId = themeId;
            this.coverUrl = coverUrl;
            density = context.getResources().getDisplayMetrics().density;
            if (coverUrl != null && !coverUrl.isEmpty()) {
                Glide.with(context).asBitmap().load(coverUrl).override(256, 256)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                                                        @Nullable Transition<? super Bitmap> transition) {
                                cover = resource;
                                invalidate();
                            }

                            @Override
                            public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                            }
                        });
            }
        }

        private float d(float v) {
            return v * density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            boolean shelf = MusicPlayerSettings.THEME_RECORD_SHELF.equals(themeId);
            paint.setColor(shelf ? 0xFFF1F0ED : 0xFF26262A);
            rect.set(0, 0, w, h);
            canvas.drawRect(rect, paint);
            float cx = w / 2f;
            if (MusicPlayerSettings.THEME_LUMINOUS_CD.equals(themeId)) {
                paint.setColor(0xFFEDEAE2);
                canvas.drawCircle(cx, h * 0.44f, d(26), paint);
                if (cover != null) {
                    canvas.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addCircle(cx, h * 0.44f, d(23), android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    rect.set(cx - d(23), h * 0.44f - d(23), cx + d(23), h * 0.44f + d(23));
                    canvas.drawBitmap(cover, null, rect, paint);
                    canvas.restore();
                }
                bar(canvas, cx, h * 0.82f, d(70), 0x8C3A3A3C);
            } else if (shelf) {
                paint.setColor(0xFFD8D4C8);
                float sw = d(34);
                rect.set(cx - sw * 1.6f, h * 0.2f, cx - sw * 0.6f, h * 0.62f);
                canvas.drawRoundRect(rect, d(3), d(3), paint);
                rect.set(cx + sw * 0.6f, h * 0.2f, cx + sw * 1.6f, h * 0.62f);
                canvas.drawRoundRect(rect, d(3), d(3), paint);
                paint.setColor(0xFF17171A);
                canvas.drawCircle(cx, h * 0.3f, d(17), paint);
                paint.setColor(0xFFE7E3D8);
                rect.set(cx - sw * 0.74f, h * 0.18f, cx + sw * 0.74f, h * 0.62f);
                canvas.drawRoundRect(rect, d(3), d(3), paint);
                paint.setColor(0xFFFFFFFF);
                rect.set(0, h * 0.66f, w, h);
                canvas.drawRect(rect, paint);
                bar(canvas, cx, h * 0.78f, d(80), 0x33252522);
                bar(canvas, cx, h * 0.9f, d(110), 0xFF252522);
            } else if (MusicPlayerSettings.THEME_COVER_IMMERSIVE.equals(themeId)) {
                float cw = Math.min(w * 0.62f, h * 1.1f);
                rect.set(cx - cw / 2f, h * 0.1f, cx + cw / 2f, h * 0.78f);
                if (cover != null) {
                    canvas.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(rect, d(8), d(8), android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    canvas.drawBitmap(cover, null, rect, paint);
                    canvas.restore();
                } else {
                    paint.setColor(0xFF3A3A3C);
                    canvas.drawRoundRect(rect, d(8), d(8), paint);
                }
                paint.setColor(0x401E1E22);
                rect.set(cx - cw / 2f + d(6), h * 0.6f, cx + cw / 2f - d(6), h * 0.74f);
                canvas.drawRoundRect(rect, d(10), d(10), paint);
            } else {
                float cs = MusicPlayerSettings.THEME_APPLE_MUSIC.equals(themeId) ? d(46) : d(40);
                rect.set(cx - cs / 2f, h * 0.08f, cx + cs / 2f, h * 0.08f + cs);
                if (cover != null) {
                    canvas.save();
                    android.graphics.Path clip = new android.graphics.Path();
                    clip.addRoundRect(rect, d(5), d(5), android.graphics.Path.Direction.CW);
                    canvas.clipPath(clip);
                    canvas.drawBitmap(cover, null, rect, paint);
                    canvas.restore();
                } else {
                    paint.setColor(0xFF3A3A3C);
                    canvas.drawRoundRect(rect, d(5), d(5), paint);
                }
                bar(canvas, cx, h * 0.68f, d(64), 0xF2FFFFFF);
                bar(canvas, cx, h * 0.78f, d(44), 0x73FFFFFF);
                bar(canvas, cx, h * 0.9f, d(90),
                        MusicPlayerSettings.THEME_APPLE_MUSIC.equals(themeId) ? 0x94FFFFFF : 0x33FFFFFF);
            }
        }

        private void bar(Canvas canvas, float cx, float y, float width, int color) {
            paint.setColor(color);
            rect.set(cx - width / 2f, y - d(3.5f), cx + width / 2f, y + d(3.5f));
            canvas.drawRoundRect(rect, d(3.5f), d(3.5f), paint);
        }
    }
}
