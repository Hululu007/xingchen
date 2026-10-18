package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.legado.LegadoException;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.read.ReadingStore;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.tts.EdgeVoice;
import com.fongmi.android.tv.tts.EdgeVoiceCatalog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.ui.minibar.ListenMiniBar;
import com.fongmi.android.tv.ui.minibar.ListenSession;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 听书播放页（阶段二·甲最小面）：
 * type 1 音频源出口。复用现有链——PlaybackService（后台播放＋锁屏控制）＋
 * PlayerManager（倍速/进度）。断点续播/书架复用 ReadingStore。精修稿另案。
 */
public class AudioBookActivity extends Activity implements ServiceConnection {

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private LegadoAudio.AudioAlbum album;
    private PlaybackService mService;
    private boolean bound;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle, tvChapter, tvPos, tvDur;
    private android.widget.LinearLayout tvSpeedVal, tvTimerVal;
    private android.widget.SeekBar seekBar;
    private TextView btnPlay;
    private android.widget.LinearLayout voiceRow;
    private boolean seeking;
    private int speedIndex = 1;
    private long sleepAt = 0;
    private int endedGuard = -1;
    private long resumeMs = 0;
    /** 用户点了关闭（真结束）；released=页面已销毁（后台可能还在播）。 */
    private boolean closed;
    private boolean released;
    /** 迷你条会话（与 TtsReadActivity 共用一套条）。 */
    private ListenSession miniSession;
    private static final String MINI_ID = "audiobook";

    public static void start(Activity activity, String sessionId) {
        Intent i = new Intent(activity, AudioBookActivity.class);
        i.putExtra("session", sessionId);
        activity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ReadingSkin.fitWindowBackground(this, ReadingSkin.PAGE_BG_TOP, true);
        album = LegadoAudio.take(getIntent().getStringExtra("session"));
        if (album == null || album.chapters.isEmpty()) {
            Toast.makeText(this, "专辑数据丢失", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUi();
        // 续播：同源同书的 AUDIO 记录带回位置
        ReadingStore.Record r = findRecord();
        if (r != null && r.chapterIndex >= 0 && r.chapterIndex < album.chapters.size()) {
            album.index = r.chapterIndex;
            resumeMs = Math.max(0, r.position);
        }
        bindPlaybackService();
        setupMiniBar();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            ListenMiniBar.get(this).pagePaused(MINI_ID);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            ListenMiniBar.get(this).pageResumed(MINI_ID);
        } catch (Throwable ignored) {
        }
    }

    // ================= 迷你条（与 TtsReadActivity 共用一套条） =================

    private void setupMiniBar() {
        miniSession = new ListenSession(MINI_ID);
        miniSession.setKeyPrefix("audiobook:");
        miniSession.setMeta(album.title, miniSub(), album.coverUrl);
        miniSession.setReopenIntent(new Intent(this, AudioBookActivity.class));
        final LegadoAudio.AudioAlbum snap = album;
        miniSession.setControl(new ListenSession.Control() {
            @Override public boolean isPlaying() {
                return mService != null && player().isPlaying();
            }

            @Override public void toggle() {
                AudioBookActivity.this.toggle();
            }

            @Override public void next() {
                playDelta(1);
            }

            @Override public boolean canNext() {
                return true;
            }

            @Override public void prev() {
                playDelta(-1);
            }

            @Override public boolean canPrev() {
                return true;
            }

            @Override public void saveProgress() {
                AudioBookActivity.this.saveProgress();
            }

            @Override public void stopPlayback() {
                try {
                    if (mService != null) player().stop();
                } catch (Throwable ignored) {
                }
            }

            @Override public void onClosed() {
                unregisterNav();
            }
        });
        miniSession.setDetached(new ListenSession.DetachedOps() {
            @Override public boolean isPlaying(PlaybackService svc) {
                try {
                    return svc.player().isPlaying()
                            && svc.player().getKey() != null
                            && svc.player().getKey().startsWith("audiobook:");
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override public void toggle(PlaybackService svc) {
                try {
                    if (svc.player().isPlaying()) svc.player().pause();
                    else svc.player().play();
                } catch (Throwable ignored) {
                }
            }

            @Override public void close(Context ctx, PlaybackService svc) {
                // 真关闭：停播＋进度落盘（只用快照 POJO，不碰已销毁的页）
                int pos = 0;
                try {
                    pos = (int) svc.player().getPosition();
                } catch (Throwable ignored) {
                }
                saveDetached(ctx.getApplicationContext(), snap, pos);
                try {
                    svc.player().stop();
                } catch (Throwable ignored) {
                }
                try {
                    svc.setNavigationCallback(null,
                            "audiobook:" + snap.legadoKey.hashCode()
                                    + ":" + snap.title.hashCode());
                } catch (Throwable ignored) {
                }
            }

            @Override public void reopen(Context ctx) {
                try {
                    String id = LegadoAudio.put(snap);
                    AudioBookActivity.start((Activity) ctx, id);
                } catch (Throwable ignored) {
                }
            }
        });
        try {
            ListenMiniBar.get(this).register(miniSession, this);
        } catch (Throwable ignored) {
        }
    }

    private String miniSub() {
        LegadoAudio.Chapter c = album.current();
        String name = c == null ? "" : c.name;
        return "第 " + (album.index + 1) + " / " + album.chapters.size()
                + " 集" + (name.isEmpty() ? "" : " · " + name);
    }

    /** detached 真关闭时的落盘（静态，只用快照＋位置，不碰页实例）。 */
    static void saveDetached(Context ctx, LegadoAudio.AudioAlbum a, int pos) {
        try {
            LegadoAudio.Chapter c = a.current();
            if (c == null) return;
            ReadingStore.Record r = null;
            for (ReadingStore.Record x : ReadingStore.list()) {
                if ("AUDIO".equals(x.kind) && a.legadoKey.equals(x.legadoKey)
                        && a.title.equals(x.bookTitle)) {
                    r = x;
                    break;
                }
            }
            if (r == null) r = new ReadingStore.Record();
            r.kind = "AUDIO";
            r.sourceName = a.sourceName;
            r.bookTitle = a.title;
            r.coverUrl = a.coverUrl;
            r.chapterName = c.name;
            r.chapterIndex = a.index;
            r.chapterUrl = c.url;
            r.legadoKey = a.legadoKey;
            r.position = Math.max(0, pos);
            r.updatedAt = System.currentTimeMillis();
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    // ================= UI（精修稿 audiobook：深色毛玻璃＋金点缀） =================

    private void buildUi() {
        android.widget.LinearLayout root =
                new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackground(ReadingSkin.pageBackground(this));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        android.widget.LinearLayout body =
                new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        sv.addView(body);
        root.addView(sv);

        // 顶栏：居中标题＋副；右上保留关闭（功能保留，稿未画）
        android.widget.FrameLayout topWrap =
                new android.widget.FrameLayout(this);
        body.addView(topWrap, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout titles =
                new android.widget.LinearLayout(this);
        titles.setOrientation(android.widget.LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_HORIZONTAL);
        titles.setPadding(dp(20), dp(16), dp(20), 0);
        topWrap.addView(titles, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tvTitle = new TextView(this);
        tvTitle.setText(album.title);
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(ReadingSkin.TEXT);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setGravity(Gravity.CENTER);
        titles.addView(tvTitle);
        tvChapter = new TextView(this);
        tvChapter.setTextSize(13);
        tvChapter.setTextColor(ReadingSkin.SUB);
        tvChapter.setGravity(Gravity.CENTER);
        tvChapter.setPadding(0, dp(6), 0, 0);
        titles.addView(tvChapter);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(16);
        close.setTextColor(ReadingSkin.FAINT);
        close.setPadding(dp(16), dp(16), dp(16), dp(16));
        close.setOnClickListener(v -> onClose());
        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END | Gravity.TOP;
        topWrap.addView(close, clp);
        ReadingSkin.applyTopInset(topWrap, this, 8);

        // 专辑大封面
        android.widget.FrameLayout coverWrap =
                new android.widget.FrameLayout(this);
        android.widget.LinearLayout.LayoutParams cwlp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(190), dp(190));
        cwlp.gravity = Gravity.CENTER_HORIZONTAL;
        cwlp.topMargin = dp(18);
        body.addView(coverWrap, cwlp);
        android.widget.ImageView cover = new android.widget.ImageView(this);
        cover.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        cover.setScaleType(
                android.widget.ImageView.ScaleType.CENTER_CROP);
        ReadingSkin.roundClip(cover, this, 22);
        coverWrap.addView(cover);
        if (!android.text.TextUtils.isEmpty(album.coverUrl)) {
            try {
                ImgUtil.load(album.title, album.coverUrl, cover, true);
            } catch (Throwable ignored) {
            }
        } else {
            cover.setBackground(coverPlaceholder());
            TextView ct = new TextView(this);
            ct.setText(album.title);
            ct.setTextSize(22);
            ct.setTextColor(0xFFFFFFFF);
            ct.getPaint().setFakeBoldText(true);
            ct.setGravity(Gravity.CENTER);
            coverWrap.addView(ct, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 金进度条＋两端时间
        android.widget.LinearLayout prog =
                new android.widget.LinearLayout(this);
        prog.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams plp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(28);
        plp.rightMargin = dp(28);
        plp.topMargin = dp(22);
        body.addView(prog, plp);
        seekBar = new android.widget.SeekBar(this);
        ReadingSkin.styleGoldSeekBar(this, seekBar);
        prog.addView(seekBar, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        seekBar.setOnSeekBarChangeListener(
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            android.widget.SeekBar s, int p, boolean fromUser) {
                        if (fromUser && mService != null) player().seekTo(p);
                    }

                    @Override
                    public void onStartTrackingTouch(
                            android.widget.SeekBar s) {
                        seeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(
                            android.widget.SeekBar s) {
                        seeking = false;
                    }
                });
        android.widget.LinearLayout times =
                new android.widget.LinearLayout(this);
        times.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        times.setPadding(0, dp(6), 0, 0);
        tvPos = timeLabel();
        tvDur = timeLabel();
        tvDur.setGravity(Gravity.END);
        times.addView(tvPos, new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        times.addView(tvDur, new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        prog.addView(times);

        // 上一集 / 暂停 / 下一集
        android.widget.LinearLayout ctrls =
                new android.widget.LinearLayout(this);
        ctrls.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        ctrls.setGravity(Gravity.CENTER);
        ctrls.setPadding(0, dp(20), 0, 0);
        TextView prev = ctrlCircle("⏮", dp(60), false);
        prev.setOnClickListener(v -> playDelta(-1));
        btnPlay = ctrlCircle("⏸", dp(88), true);
        btnPlay.setOnClickListener(v -> toggle());
        TextView next = ctrlCircle("⏭", dp(60), false);
        next.setOnClickListener(v -> playDelta(1));
        android.widget.LinearLayout.LayoutParams gap =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = dp(18);
        gap.rightMargin = dp(18);
        ctrls.addView(prev);
        ctrls.addView(btnPlay, gap);
        ctrls.addView(next);
        body.addView(ctrls);

        // 倍速 / 定时 / 自动连播
        android.widget.LinearLayout opts =
                new android.widget.LinearLayout(this);
        opts.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        opts.setGravity(Gravity.CENTER);
        opts.setPadding(0, dp(18), 0, 0);
        tvSpeedVal = optCapsule("倍速", "1x");
        tvSpeedVal.setOnClickListener(v -> cycleSpeed());
        tvTimerVal = optCapsule("定时", "定时关");
        tvTimerVal.setOnClickListener(v -> pickTimer());
        opts.addView(tvSpeedVal);
        opts.addView(tvTimerVal);
        opts.addView(autoCapsule());
        body.addView(opts);

        // 音色
        body.addView(ReadingSkin.sectionHeader(this, "音色",
                null, null));
        TextView vhint = new TextView(this);
        vhint.setText("神经人声 · 机械声不进这儿");
        vhint.setTextSize(12);
        vhint.setTextColor(ReadingSkin.FAINT);
        android.widget.LinearLayout.LayoutParams hlp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        // 标题行右上角：单独一行右对齐
        vhint.setGravity(Gravity.END);
        hlp.leftMargin = dp(20);
        hlp.rightMargin = dp(20);
        hlp.bottomMargin = dp(8);
        body.addView(vhint, hlp);
        android.widget.HorizontalScrollView hsv =
                new android.widget.HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        voiceRow = new android.widget.LinearLayout(this);
        voiceRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        voiceRow.setPadding(dp(20), 0, dp(20), 0);
        hsv.addView(voiceRow);
        body.addView(hsv);
        buildVoiceCards();
        TextView vnote = new TextView(this);
        vnote.setText("挑顺耳的定成默认，听哪本都用它；另有方言音色在更多里。");
        vnote.setTextSize(12);
        vnote.setTextColor(ReadingSkin.FAINT);
        vnote.setGravity(Gravity.CENTER);
        vnote.setLineSpacing(dp(4), 1f);
        android.widget.LinearLayout.LayoutParams nlp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = dp(20);
        nlp.rightMargin = dp(20);
        nlp.topMargin = dp(10);
        body.addView(vnote, nlp);

        // 底部：选集 / 回书架（毛玻璃底；听书历史走书架在看列表，稿的历史按钮无对应功能位，未加）
        android.widget.LinearLayout sheet =
                new android.widget.LinearLayout(this);
        sheet.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sheet.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable sheetBg =
                new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(0xB8121218);
        sheetBg.setCornerRadii(new float[]{
                dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        sheetBg.setStroke(dp(2), 0x2EFFFFFF);
        sheet.setBackground(sheetBg);
        sheet.setPadding(0, dp(14), 0, dp(10));
        ReadingSkin.applyBottomInset(sheet, this, 10);
        root.addView(sheet, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(sheetItem("☰", "选集", v -> showToc()));
        sheet.addView(sheetItem("↩", "回书架", v -> {
            ReadingShelfActivity.start(this);
            finish();
        }));

        setContentView(root);
        refreshChapterLabel();
    }

    private android.graphics.drawable.Drawable coverPlaceholder() {
        return new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1D2A3A, 0xFF33506B, 0xFF7FA3C4});
    }

    private TextView timeLabel() {
        TextView t = new TextView(this);
        t.setText("00:00");
        t.setTextSize(12);
        t.setTextColor(ReadingSkin.SUB);
        return t;
    }

    /** 控制圆钮：gold=true 中央大金钮（深色字），否则深色圆钮。 */
    private TextView ctrlCircle(String s, int sizeDp, boolean gold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(gold ? 26 : 20);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                sizeDp, sizeDp));
        if (gold) {
            t.setTextColor(0xFF1A1408);
            t.setBackground(ReadingSkin.goldCircle(this));
            try {
                t.setElevation(dp(8));
            } catch (Throwable ignored) {
            }
        } else {
            t.setTextColor(ReadingSkin.TEXT);
            t.setBackground(ReadingSkin.darkCircle(this));
        }
        return t;
    }

    /** 选项胶囊：左灰 label＋右金 value。返回整颗（value 另存）。 */
    private android.widget.LinearLayout optCapsule(String label,
                                                   String value) {
        android.widget.LinearLayout box =
                new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x12FFFFFF);
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(2), 0x2EFFFFFF);
        box.setBackground(bg);
        int hp = dp(16), vp = dp(10);
        box.setPadding(hp, vp, hp, vp);
        android.widget.LinearLayout.LayoutParams blp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(6);
        blp.rightMargin = dp(6);
        box.setLayoutParams(blp);
        TextView l = new TextView(this);
        l.setText(label + " ");
        l.setTextSize(14);
        l.setTextColor(ReadingSkin.TEXT);
        box.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(ReadingSkin.GOLD);
        v.getPaint().setFakeBoldText(true);
        box.addView(v);
        box.setTag(v);
        box.setOnClickListener(null);
        return box;
    }

    /** 自动连播：代码里恒开（无开关逻辑），只做状态展示，不可点。 */
    private android.widget.LinearLayout autoCapsule() {
        android.widget.LinearLayout box = optCapsule("自动连播下一集", "开");
        box.setOnClickListener(null);
        box.setClickable(false);
        box.setFocusable(false);
        return box;
    }

    private void setOptValue(android.widget.LinearLayout capsule,
                             String value) {
        TextView v = (TextView) capsule.getTag();
        if (v != null) v.setText(value);
    }

    /** 底部三键其二：图标＋灰字。 */
    private android.widget.LinearLayout sheetItem(String icon,
                                                  String label,
                                                  View.OnClickListener onClick) {
        android.widget.LinearLayout item =
                new android.widget.LinearLayout(this);
        item.setOrientation(android.widget.LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(dp(28), dp(6), dp(28), dp(6));
        item.setOnClickListener(onClick);
        TextView i = new TextView(this);
        i.setText(icon);
        i.setTextSize(22);
        i.setTextColor(0xA6F4F2EC);
        i.setGravity(Gravity.CENTER);
        item.addView(i);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(11);
        l.setTextColor(ReadingSkin.SUB);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, dp(4), 0, 0);
        item.addView(l);
        return item;
    }

    // ================= 音色卡（真名单，不写死假数据） =================

    /**
     * 音色卡数据以 EdgeVoiceCatalog 真实名单为准：
     * 稿里 4 张（晓晓/云扬/云健/晓伊）都是内置真音色，按 id 取；
     * 取不到则顺延补齐。选中态＝当前默认音色。
     */
    private void buildVoiceCards() {
        voiceRow.removeAllViews();
        java.util.List<EdgeVoice> all = EdgeVoiceCatalog.builtIn();
        String[] want = {"zh-CN-XiaoxiaoNeural", "zh-CN-YunyangNeural",
                "zh-CN-YunjianNeural", "zh-CN-XiaoyiNeural"};
        java.util.List<EdgeVoice> show = new java.util.ArrayList<>();
        for (String id : want) {
            EdgeVoice v = EdgeVoiceCatalog.find(all, id);
            if (v != null) show.add(v);
        }
        for (EdgeVoice v : all) {
            if (show.size() >= 4) break;
            if (!show.contains(v) && v.locale.startsWith("zh-CN")
                    && !v.name.contains("（")) show.add(v);
        }
        String cur = EdgeVoiceCatalog.defaultVoice();
        for (EdgeVoice v : show) {
            voiceRow.addView(voiceCard(v, v.id.equals(cur)));
        }
    }

    private android.widget.LinearLayout voiceCard(EdgeVoice v,
                                                  boolean selected) {
        android.widget.LinearLayout card =
                new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        dp(108), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        card.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        if (selected) {
            bg.setColor(0x38F2B33D);
            bg.setStroke(dp(2), ReadingSkin.GOLD);
        } else {
            bg.setColor(0x0DFFFFFF);
            bg.setStroke(dp(2), 0x24FFFFFF);
        }
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setOnClickListener(vv -> {
            EdgeVoiceCatalog.setDefaultVoice(v.id);
            buildVoiceCards();
        });
        TextView n = new TextView(this);
        n.setText(v.name);
        n.setTextSize(15);
        n.getPaint().setFakeBoldText(true);
        n.setTextColor(selected ? ReadingSkin.GOLD : ReadingSkin.TEXT);
        card.addView(n);
        TextView d = new TextView(this);
        d.setText(v.portrait);
        d.setTextSize(11);
        d.setTextColor(ReadingSkin.SUB);
        d.setPadding(0, dp(6), 0, 0);
        d.setGravity(Gravity.CENTER);
        card.addView(d);
        // 画像自带「讲故事感」标签：数据驱动，不写死
        if (v.portrait != null && v.portrait.contains("讲故事感")) {
            TextView tag = new TextView(this);
            tag.setText("讲故事感");
            tag.setTextSize(10);
            tag.setTextColor(0xFF0F0D06);
            tag.getPaint().setFakeBoldText(true);
            tag.setPadding(dp(8), dp(4), dp(8), dp(4));
            android.graphics.drawable.GradientDrawable tb =
                    new android.graphics.drawable.GradientDrawable();
            tb.setColor(ReadingSkin.GOLD);
            tb.setCornerRadius(dp(6));
            tag.setBackground(tb);
            android.widget.LinearLayout.LayoutParams tlp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(8);
            card.addView(tag, tlp);
        }
        return card;
    }

    private int dp(int v) {
        return ReadingSkin.dp(this, v);
    }

    // ================= 服务绑定（复用 PlaybackService） =================

    private void bindPlaybackService() {
        if (bound) return;
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class)
                .setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        bound = true;
    }

    private PlayerManager player() {
        return mService.player();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((PlaybackService.LocalBinder) binder).getService();
        mService.setPlaybackForeground(true);
        mService.setNavigationCallback(navCallback, playbackKey());
        playChapter(album.index, resumeMs);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }

    private String playbackKey() {
        return "audiobook:" + album.legadoKey.hashCode() + ":" + album.title.hashCode();
    }

    private final PlaybackService.NavigationCallback navCallback = new PlaybackService.NavigationCallback() {
        @Override public void onPrev() { if (!released) playDelta(-1); }
        @Override public void onNext() { if (!released) playDelta(1); }
        @Override public void onStop() { if (!released) onClose(); }
    };

    /** 注销锁屏回调：只在听书真结束（用户关闭）时调；后台续播时保留。 */
    private void unregisterNav() {
        try {
            if (mService != null) mService.setNavigationCallback(null, playbackKey());
        } catch (Throwable ignored) {
        }
    }

    // ================= 播放 =================

    private void playDelta(int d) {
        int n = album.index + d;
        if (n < 0 || n >= album.chapters.size()) {
            Toast.makeText(this, d > 0 ? "已经是最后一集" : "已经是第一集",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveProgress();
        playChapter(n, 0);
    }

    private void playChapter(int index, long resume) {
        album.index = index;
        endedGuard = -1;
        refreshChapterLabel();
        new Thread(() -> {
            String url;
            try {
                LegadoAudio.Chapter c = album.current();
                com.fongmi.android.tv.legado.LegadoSource src =
                        com.fongmi.android.tv.legado.LegadoStore.get(album.legadoKey);
                url = LegadoAudio.fetchAudioUrl(src, c.url, null);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    String m = t instanceof LegadoException ? t.getMessage() : "音频地址解析失败";
                    Toast.makeText(AudioBookActivity.this, "源级错误：" + m,
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            final String audioUrl = url;
            runOnUiThread(() -> {
                if (released || mService == null) return;
                LegadoAudio.Chapter c = album.current();
                MediaMetadata meta = new MediaMetadata.Builder()
                        .setTitle(c.name)
                        .setArtist(album.title)
                        .setArtworkUri(album.coverUrl.isEmpty() ? null
                                : Uri.parse(album.coverUrl))
                        .build();
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/120.0 Mobile Safari/537.36");
                player().browse(PlaySpec.from(playbackKey(), audioUrl, headers, meta));
                if (resume > 0) player().seekTo(resume);
            });
        }).start();
    }

    private void toggle() {
        if (mService == null) return;
        if (player().isPlaying()) player().pause();
        else player().play();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        float s = SPEEDS[speedIndex];
        if (mService != null) player().setSpeed(s);
        String label = String.format(Locale.US, "%.2f", s)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
        setOptValue(tvSpeedVal, label + "x");
        Toast.makeText(this, "倍速 " + label + "x", Toast.LENGTH_SHORT).show();
    }

    private void pickTimer() {
        String[] items = {"15 分钟", "30 分钟", "60 分钟", "关闭定时"};
        new AlertDialog.Builder(this).setTitle("定时关闭")
                .setItems(items, (d, which) -> {
                    long[] ms = {15 * 60000L, 30 * 60000L, 60 * 60000L, 0};
                    sleepAt = ms[which] == 0 ? 0 : System.currentTimeMillis() + ms[which];
                    setOptValue(tvTimerVal, ms[which] == 0 ? "定时关"
                            : "剩" + items[which]);
                    if (ms[which] == 0) Toast.makeText(this, "已关闭定时",
                            Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showToc() {
        String[] names = new String[album.chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = album.chapters.get(i).name;
        new AlertDialog.Builder(this).setTitle(album.title + "（" + names.length + " 集）")
                .setItems(names, (d, which) -> {
                    saveProgress();
                    playChapter(which, 0);
                }).show();
    }

    private void refreshChapterLabel() {
        LegadoAudio.Chapter c = album.current();
        if (c != null && tvChapter != null) {
            tvChapter.setText("第 " + (album.index + 1) + " / "
                    + album.chapters.size() + " 集 · " + c.name);
            try {
                ListenMiniBar.get(this).updateMeta(MINI_ID, album.title,
                        miniSub());
            } catch (Throwable ignored) {
            }
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try {
                if (mService != null) {
                    Player p = player().getPlayer();
                    long pos = player().getPosition();
                    long dur = player().getDuration();
                    if (!seeking && dur > 0) {
                        seekBar.setMax((int) dur);
                        seekBar.setProgress((int) Math.min(pos, dur));
                    }
                    tvPos.setText(fmt(pos));
                    tvDur.setText(fmt(dur));
                    btnPlay.setText(player().isPlaying() ? "⏸" : "▶");
                    // 迷你条：播放状态变化上报（恢复播放取消 10 秒计时）
                    try {
                        ListenMiniBar.get(AudioBookActivity.this)
                                .playingChanged(MINI_ID,
                                        player().isPlaying());
                    } catch (Throwable ignored) {
                    }
                    // 睡眠定时
                    if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                        sleepAt = 0;
                        setOptValue(tvTimerVal, "定时关");
                        player().pause();
                        Toast.makeText(AudioBookActivity.this, "定时到，已暂停",
                                Toast.LENGTH_SHORT).show();
                    }
                    // 本集播完自动下一集
                    if (p != null && p.getPlaybackState() == Player.STATE_ENDED
                            && endedGuard != album.index) {
                        endedGuard = album.index;
                        if (album.index + 1 < album.chapters.size()) {
                            saveProgress();
                            playChapter(album.index + 1, 0);
                        } else {
                            Toast.makeText(AudioBookActivity.this, "专辑播完",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, 500);
        }
    };

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    // ================= 进度/书架（复用 ReadingStore） =================

    private ReadingStore.Record findRecord() {
        try {
            for (ReadingStore.Record r : ReadingStore.list()) {
                if ("AUDIO".equals(r.kind) && album.legadoKey.equals(r.legadoKey)
                        && album.title.equals(r.bookTitle)) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void saveProgress() {
        try {
            LegadoAudio.Chapter c = album.current();
            if (c == null) return;
            ReadingStore.Record r = findRecord();
            if (r == null) r = new ReadingStore.Record();
            r.kind = "AUDIO";
            r.sourceName = album.sourceName;
            r.bookTitle = album.title;
            r.coverUrl = album.coverUrl;
            r.chapterName = c.name;
            r.chapterIndex = album.index;
            r.chapterUrl = c.url;
            r.legadoKey = album.legadoKey;
            r.position = mService == null ? 0 : (int) player().getPosition();
            r.updatedAt = System.currentTimeMillis();
            ReadingStore.save(r);
        } catch (Throwable ignored) {
        }
    }

    private void onClose() {
        closed = true;
        // 真关闭走迷你条策略：停播＋落盘＋注销（performClose 调页 control）
        try {
            ListenMiniBar.get(this).closeSession(MINI_ID);
        } catch (Throwable t) {
            saveProgress();
            try {
                if (mService != null) player().stop();
            } catch (Throwable ignored) {
            }
            unregisterNav();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        released = true;
        saveProgress();
        handler.removeCallbacks(ticker);
        // 页销毁：控制权交给条（detached 兜底），会话不注销——后台续播时条就是主；
        // 真关闭（10秒/音乐页/点封面）才停播。不注销＝条还在，声音就有主。
        if (miniSession != null) {
            miniSession.setControl(null);
        }
        // 后台续播：页面销毁但播放继续，保留锁屏回调（服务 tryShutdown 会因此放行，
        // 且服务层 saveProgress 有 audiobook: 隔离，不会污染点播历史）。
        // 用户点了关闭才注销回调、放行服务回收。
        if (closed) unregisterNav();
        if (bound) {
            try {
                unbindService(this);
            } catch (Throwable ignored) {
            }
            bound = false;
        }
        mService = null;
        super.onDestroy();
    }
}
