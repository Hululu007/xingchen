package com.fongmi.android.tv.ui.minibar;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.minibar.MiniBarPolicy;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.ReadingSkin;
import com.fongmi.android.tv.utils.ImgUtil;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 听书迷你条总控（单例，常驻应用级）：
 * 一条悬浮条跟人走（贴各页底部、全局底栏上方）；槽位仲裁同一时刻只露一条；
 * 「消失＝关闭」三条真关闭路径；暂停超 10 秒自动关；声音绝不能没主。
 *
 * 纯加法：不改任何现有播放逻辑，只在外层包一层条。
 */
public class ListenMiniBar implements MiniBarPolicy.Actions {

    private static ListenMiniBar instance;

    public static synchronized ListenMiniBar get(Context ctx) {
        if (instance == null) {
            instance = new ListenMiniBar(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final MiniBarPolicy policy;
    private final Map<String, ListenSession> sessions = new LinkedHashMap<>();
    private final Map<String, WeakReference<Activity>> pages = new LinkedHashMap<>();
    /** 会话页至少 resume 过一次（注册到 onResume 前不露条，防旧页闪现）。 */
    private final java.util.Set<String> pageSeen = new java.util.HashSet<>();

    private PlaybackService svc;
    private boolean bound;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            svc = ((PlaybackService.LocalBinder) binder).getService();
            refreshPlayIcon();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            svc = null;
        }
    };

    private View barView;
    private ImageView ivCover;
    private TextView tvTitle;
    private ImageView btnPlay;
    private TextView btnNext;
    private TextView btnPrev;
    private WeakReference<Activity> hostRef;
    private String lastVisible;

    // ================= 音乐会话（独立音乐播放器，id="music"） =================

    /** 音乐迷你条会话：只实现槽位 id，文案/封面实时从 MusicController 取 */
    private static class MusicMiniSession implements MiniBarPolicy.Session {
        @Override
        public String id() {
            return "music";
        }
    }

    private MusicMiniSession musicSession;
    /** 当前条是否为音乐样式（浅色胶囊：圆封面旋转＋外圈进度环＋歌名＋下一首＋大圆播放键） */
    private boolean barMusicMode;
    private ImageView ivVinyl;
    // 以下成员只服务音乐分支（buildMusicBar/bindMusicSession），听书条不读写
    private HomeProgressRingView homeRing;
    private ImageView homeNextBtn;
    private ObjectAnimator homeSpin;
    private Handler homeHandler;
    private final Runnable homeProgressTick = new Runnable() {
        @Override
        public void run() {
            updateHomeProgress();
            if (barMusicMode && barView != null && barView.getParent() != null && homeHandler != null) {
                homeHandler.postDelayed(this, 500);
            }
        }
    };

    private ListenMiniBar(Context app) {
        this.app = app;
        Handler h = new Handler(Looper.getMainLooper());
        this.policy = new MiniBarPolicy(this, System::currentTimeMillis,
                new MiniBarPolicy.Timer() {
                    private Runnable pending;

                    @Override
                    public void schedule(long delayMs, Runnable task) {
                        cancel();
                        pending = task;
                        h.postDelayed(task, delayMs);
                    }

                    @Override
                    public void cancel() {
                        if (pending != null) h.removeCallbacks(pending);
                        pending = null;
                    }
                });
    }

    // ================= 会话管理（页调用） =================

    /** 页 onCreate：注册会话（后来者占位，先来者让位不关闭）。 */
    public synchronized void register(ListenSession s, Activity page) {
        sessions.put(s.id(), s);
        pages.put(s.id(), new WeakReference<>(page));
        pageSeen.remove(s.id());
        policy.register(s);
        ensureBound();
        refreshBar();
    }

    /** 真关闭后内部调用（不触发回调，避免递归）。 */
    private synchronized void unregisterLocked(String id) {
        sessions.remove(id);
        pages.remove(id);
        policy.unregister(id);
        if (sessions.isEmpty()) releaseBind();
        refreshBar();
    }

    public void pagePaused(String id) {
        policy.pagePaused(id);
        refreshBar();
    }

    public void pageResumed(String id) {
        pageSeen.add(id);
        policy.pageResumed(id);
        refreshBar();
    }

    public void playingChanged(String id, boolean playing) {
        policy.playingChanged(id, playing);
        refreshPlayIcon();
    }

    /** 更新条上文案（切章/切集时页调）。 */
    public void updateMeta(String id, String title, String sub) {
        ListenSession s = sessions.get(id);
        if (s == null) return;
        s.setMeta(title, sub, s.getCoverUrl());
        if (tvTitle != null && id.equals(policy.visibleId())) {
            tvTitle.setText(oneLine(s));
        }
    }

    /** 封面点击 / 页内关闭钮：真关闭（停播＋落盘）。 */
    public void closeSession(String id) {
        policy.closeSession(id);
    }

    /** 进入音乐页：听书真关闭（用户定的关闭路径 3）；音乐自己的条不动 */
    public void musicPageEnter() {
        if ("music".equals(policy.visibleId())) return;
        policy.musicPageEnter();
    }

    /** 注册音乐会话（幂等）：服务有队列就露条 */
    public synchronized void registerMusic() {
        if (musicSession == null) {
            musicSession = new MusicMiniSession();
            pages.put("music", new WeakReference<>(null));
            pageSeen.add("music");
            policy.register(musicSession);
        }
        refreshBar();
        refreshMusic();
    }

    /** 注销音乐会话（队列清空/服务停止时） */
    public synchronized void unregisterMusic() {
        if (musicSession == null) return;
        musicSession = null;
        pages.remove("music");
        policy.unregister("music");
        refreshBar();
    }

    /** 第三波 M2：把音乐播放态变化喂给策略层，让暂停 10 秒自动收真生效
     * （策略 playingChanged 只在会话已注册时起作用；未注册时为空操作）。
     * 音乐会话的 pageForeground 从未被置 true（音乐条只在首页露、页引用为空），
     * 所以暂停计时不受“在播放页”门控影响；onCloseSession 对音乐仅藏条，见下。 */
    public void musicPlayingChanged(boolean playing) {
        try {
            policy.playingChanged("music", playing);
        } catch (Throwable ignored) {
        }
    }

    /** 刷新音乐条文案与图标 */
    public void refreshMusic() {
        if (!"music".equals(policy.visibleId())) return;
        if (barView != null && barMusicMode) {
            bindMusicSession();
            refreshPlayIcon();
        } else {
            refreshBar();
        }
    }

    private com.fongmi.android.tv.music.MusicController musicCtl() {
        return com.fongmi.android.tv.music.MusicController.get();
    }

    // ================= MiniBarPolicy.Actions =================

    @Override
    public void onCloseSession(String id) {
        // 音乐真关闭：暂停＋落盘（断点在暂停时已存）＋注销条，队列保留
        if ("music".equals(id)) {
            try {
                musicCtl().pause();
            } catch (Throwable ignored) {
            }
            synchronized (this) {
                unregisterMusic();
            }
            return;
        }
        ListenSession s;
        synchronized (this) {
            s = sessions.get(id);
        }
        if (s != null) s.performClose(app, svc);
        synchronized (this) {
            unregisterLocked(id);
        }
    }

    @Override
    public void onVisibleChanged(String visibleId) {
        lastVisible = visibleId;
        refreshBar();
    }

    // ================= 宿主生命周期（App 回调） =================

    public void onActivityResumed(Activity activity) {
        refreshBar();
    }

    public void onActivityPaused(Activity activity) {
        Activity host = hostRef == null ? null : hostRef.get();
        if (host == activity) detachBar();
    }

    // ================= 条的显示 =================

    private synchronized void refreshBar() {
        String visible = policy.visibleId();
        Activity host = currentHost();
        if (visible == null || host == null || isSessionPage(host, visible)
                || isMusicOffHomePage(host, visible)) {
            hideBar();
            return;
        }
        // 会话页尚在启动中（注册了但还没 onResume）：不露条，防旧页闪现
        WeakReference<Activity> ref = pages.get(visible);
        if (ref != null && ref.get() != null && !pageSeen.contains(visible)) {
            hideBar();
            return;
        }
        attachBar(host, visible);
    }

    /** 当前前台 Activity（App 维护的引用）。 */
    private Activity currentHost() {
        try {
            return com.fongmi.android.tv.App.activity();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isSessionPage(Activity host, String visibleId) {
        WeakReference<Activity> ref = pages.get(visibleId);
        Activity page = ref == null ? null : ref.get();
        return page == host;
    }

    /**
     * 音乐条白名单（潇洒哥亲点「只在首页显示」）：音乐会话只许在音乐首页
     * （ui.activity.MusicActivity）露条，其它任何页面——音乐列表/搜索、播放页、
     * 视频各页、星辰首页——一律不露。音乐会话在 registerMusic 里存的是空页引用
     * （WeakReference(null)），isSessionPage 认不出当前页；pageResumed 又常在会话
     * 注册前到达被策略丢掉，所以只能按页类名判定（沿用 b9fbda83 的兜底机制，
     * 只改口径：从「排除播放页」改成「只认音乐首页」）。判定挂在 refreshBar
     * 必经之路上：进列表页时新页 resume 必触发 refreshBar 把条收掉，回首页再露；
     * 会话注销同样走 refreshBar，首页不会残留。
     */
    private boolean isMusicOffHomePage(Activity host, String visibleId) {
        return "music".equals(visibleId)
                && !"com.fongmi.android.tv.ui.activity.MusicActivity"
                        .equals(host.getClass().getName());
    }

    private void attachBar(Activity host, String visibleId) {
        boolean music = "music".equals(visibleId);
        ListenSession s = music ? null : sessions.get(visibleId);
        if (!music && s == null) return;
        Activity old = hostRef == null ? null : hostRef.get();
        if (old != host) detachBar();
        if (barView == null || barMusicMode != music) {
            detachBar();
            barMusicMode = music;
            if (music) buildMusicBar();
            else buildBar();
        }
        if (music) bindMusicSession();
        else bindSession(s);
        // 音乐条重新挂载（切页回来）后恢复进度环轮询；听书分支不走这里
        if (music) startHomeProgress();
        ViewGroup decor;
        try {
            decor = (ViewGroup) host.getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        if (barView.getParent() != null) {
            if (barView.getParent() == decor) {
                hostRef = new WeakReference<>(host);
                // 可能正处在淡出中：取消并恢复可见
                try {
                    barView.animate().cancel();
                    barView.setAlpha(1f);
                } catch (Throwable ignored) {
                }
                return;
            }
            detachBar();
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        int side = ReadingSkin.dp(host, 16);
        lp.leftMargin = side;
        lp.rightMargin = side;
        lp.bottomMargin = bottomMargin(host);
        decor.addView(barView, lp);
        hostRef = new WeakReference<>(host);
        // 条自身淡入（200ms；页面级过渡这批不做，等海阔试水后统一上）
        try {
            barView.animate().cancel();
            barView.setAlpha(0f);
            barView.animate().alpha(1f).setDuration(200).start();
        } catch (Throwable ignored) {
        }
    }

    private int bottomMargin(Activity host) {
        int nav = 0;
        try {
            androidx.core.graphics.Insets in = ViewCompat.getRootWindowInsets(
                    host.getWindow().getDecorView())
                    .getInsets(WindowInsetsCompat.Type.navigationBars());
            nav = in.bottom;
        } catch (Throwable ignored) {
        }
        int extra = 0;
        if (host instanceof MiniBarHost) {
            try {
                extra = ((MiniBarHost) host).miniBarBottomExtraPx();
            } catch (Throwable ignored) {
            }
        }
        return nav + extra + ReadingSkin.dp(host, 12);
    }

    private void detachBar() {
        // 条摘下时停掉音乐封面旋转与进度轮询；听书条从不设置这些成员，此调用对它为空操作
        stopHomeSpin();
        try {
            if (barView != null) barView.animate().cancel();
            if (barView != null && barView.getParent() instanceof ViewGroup) {
                ((ViewGroup) barView.getParent()).removeView(barView);
            }
        } catch (Throwable ignored) {
        }
        hostRef = null;
    }

    /** 条自身淡出（200ms）后摘除。 */
    private void hideBar() {
        Activity host = hostRef == null ? null : hostRef.get();
        if (barView == null || !(barView.getParent() instanceof ViewGroup)) {
            hostRef = null;
            return;
        }
        final ViewGroup parent = (ViewGroup) barView.getParent();
        final View v = barView;
        hostRef = null;
        try {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                try {
                    parent.removeView(v);
                } catch (Throwable ignored) {
                }
                v.setAlpha(1f);
            }).start();
        } catch (Throwable t) {
            detachBar();
        }
    }

    // ================= 条的构建与绑定 =================

    /**
     * 听书条外观（用户定稿）：矮条、简洁、书的形态——
     * 长方形小封面（无圆盘，一眼分清是书不是歌）＋一行标题（书名/集名单行）
     * ＋暂停主钮＋下一集小图标，不堆控件。行为规矩不变。
     */
    private void buildBar() {
        Context c = app;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int hpad = ReadingSkin.dp(c, 12);
        int vpad = ReadingSkin.dp(c, 8);
        bar.setPadding(hpad, vpad, hpad, vpad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD914141C);
        bg.setCornerRadius(ReadingSkin.dp(c, 18));
        bg.setStroke(ReadingSkin.dp(c, 2), 0x2EFFFFFF);
        bar.setBackground(bg);
        try {
            bar.setElevation(ReadingSkin.dp(c, 10));
        } catch (Throwable ignored) {
        }

        // 书形小封面：长方形，无圆盘
        ivCover = new ImageView(c);
        int cw = ReadingSkin.dp(c, 36);
        int ch = ReadingSkin.dp(c, 48);
        LinearLayout.LayoutParams clp =
                new LinearLayout.LayoutParams(cw, ch);
        clp.rightMargin = ReadingSkin.dp(c, 10);
        ivCover.setLayoutParams(clp);
        ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ReadingSkin.roundClip(ivCover, c, 8);
        ivCover.setOnClickListener(v -> {
            // 用户语义：点封面＝关闭听书（真关闭，停播＋落盘）
            String vid = policy.visibleId();
            if (vid != null) closeSession(vid);
        });
        bar.addView(ivCover);

        // 一行标题：书名/集名单行
        tvTitle = new TextView(c);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(ReadingSkin.TEXT);
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvTitle.setLayoutParams(tlp);
        bar.addView(tvTitle);

        // 下一集：小图标，不堆控件
        btnNext = new TextView(c);
        btnNext.setText("\u23ed");
        btnNext.setTextSize(16);
        btnNext.setTextColor(ReadingSkin.SUB);
        btnNext.setGravity(Gravity.CENTER);
        int nbHit = ReadingSkin.dp(c, 48);
        btnNext.setLayoutParams(new LinearLayout.LayoutParams(nbHit, nbHit));
        btnNext.setOnClickListener(v -> {
            String vid = policy.visibleId();
            ListenSession s = vid == null ? null : sessions.get(vid);
            ListenSession.Control ctl = s == null ? null : s.control();
            if (ctl != null && ctl.canNext()) ctl.next();
        });
        bar.addView(btnNext);

        // 上一集：小图标（用户要求上下集都要有，保持矮条简洁）
        btnPrev = new TextView(c);
        btnPrev.setText("\u23ee");
        btnPrev.setTextSize(16);
        btnPrev.setTextColor(ReadingSkin.SUB);
        btnPrev.setGravity(Gravity.CENTER);
        btnPrev.setLayoutParams(new LinearLayout.LayoutParams(nbHit, nbHit));
        btnPrev.setOnClickListener(v -> {
            String vid = policy.visibleId();
            ListenSession s = vid == null ? null : sessions.get(vid);
            ListenSession.Control ctl = s == null ? null : s.control();
            if (ctl != null && ctl.canPrev()) ctl.prev();
        });
        bar.addView(btnPrev);

        // 暂停主钮：矢量图标（原 TextView 的 ⏸ 字符被荣耀 emoji 字体渲成橙方块；
        // 圆底/尺寸/点击行为都不动，只换中间的图标）
        btnPlay = new ImageView(c);
        int bpad = ReadingSkin.dp(c, 11);
        btnPlay.setPadding(bpad, bpad, bpad, bpad);
        btnPlay.setImageResource(R.drawable.ic_music_play);
        btnPlay.setImageTintList(ColorStateList.valueOf(0xFF1A1408));
        int ps = ReadingSkin.dp(c, 44);
        LinearLayout.LayoutParams plp =
                new LinearLayout.LayoutParams(ps, ps);
        plp.leftMargin = ReadingSkin.dp(c, 2);
        btnPlay.setLayoutParams(plp);
        btnPlay.setBackground(ReadingSkin.goldCircle(c));
        btnPlay.setOnClickListener(v -> {
            String vid = policy.visibleId();
            ListenSession s = vid == null ? null : sessions.get(vid);
            if (s == null) return;
            ListenSession.Control ctl = s.control();
            if (ctl != null) ctl.toggle();
            else s.detachedToggle(svc);
            refreshPlayIcon();
        });
        bar.addView(btnPlay);

        // 点条身（非按钮区）回播放页
        bar.setOnClickListener(v -> {
            String vid = policy.visibleId();
            ListenSession s = vid == null ? null : sessions.get(vid);
            if (s == null) return;
            Activity host = hostRef == null ? null : hostRef.get();
            Context ctx = host != null ? host : app;
            s.reopen(ctx);
        });

        barView = bar;
    }

    /** 一行标题：书名/集名单行（用户定的简洁口径）。 */
    private static String oneLine(ListenSession s) {
        String t = s.getTitle() == null ? "" : s.getTitle();
        String sub = s.getSub() == null ? "" : s.getSub().trim();
        return sub.isEmpty() ? t : t + " · " + sub;
    }

    private void bindSession(ListenSession s) {
        tvTitle.setText(oneLine(s));
        loadCover(s);
        if (btnNext != null) btnNext.setVisibility(s.canNext() ? View.VISIBLE : View.GONE);
        if (btnPrev != null) btnPrev.setVisibility(s.canPrev() ? View.VISIBLE : View.GONE);
        refreshPlayIcon();
    }

    private void loadCover(ListenSession s) {
        if (!TextUtils.isEmpty(s.getCoverUrl())) {
            try {
                ImgUtil.load(s.getTitle(), s.getCoverUrl(), ivCover, true);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 无封面：深色书形占位（长方形，无圆盘）
        GradientDrawable ph = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A2F3D, 0xFF1B1E28});
        ph.setCornerRadius(ReadingSkin.dp(app, 8));
        ph.setStroke(ReadingSkin.dp(app, 1), 0x40FFFFFF);
        ivCover.setImageDrawable(ph);
    }

    private void refreshPlayIcon() {
        if (btnPlay == null) return;
        String vid = policy.visibleId();
        boolean playing = false;
        if ("music".equals(vid)) {
            try {
                playing = musicCtl().isPlaying();
            } catch (Throwable ignored) {
            }
        } else {
            ListenSession s = vid == null ? null : sessions.get(vid);
            if (s != null) {
                ListenSession.Control ctl = s.control();
                if (ctl != null) {
                    try {
                        playing = ctl.isPlaying();
                    } catch (Throwable ignored) {
                    }
                } else {
                    playing = s.detachedPlaying(svc);
                }
            }
        }
        btnPlay.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        // 封面旋转只跟音乐分支：播放转、暂停停（听书条 ivVinyl/homeSpin 恒为 null，不受影响）
        if (barMusicMode) updateHomeSpin(playing);
    }

    /** 第三波 M3：摘条时记下的封面转角（重建后恢复，不从头转）。 */
    private float savedHomeRotation;

    /** 音乐封面旋转：播放时匀速转，暂停保留角度停住；条重建时从记下的转角续转，不复位。 */
    private void updateHomeSpin(boolean playing) {
        if (ivVinyl == null) return;
        if (playing) {
            if (homeSpin == null) {
                float start = ivVinyl.getRotation();
                homeSpin = ObjectAnimator.ofFloat(ivVinyl, View.ROTATION, start, start + 360f);
                homeSpin.setDuration(12000);
                homeSpin.setRepeatCount(ObjectAnimator.INFINITE);
                homeSpin.setInterpolator(new LinearInterpolator());
            }
            if (!homeSpin.isStarted()) homeSpin.start();
            else if (homeSpin.isPaused()) homeSpin.resume();
        } else if (homeSpin != null && homeSpin.isRunning()) {
            homeSpin.pause();
        }
    }

    private void stopHomeSpin() {
        // 第三波 M3：摘条／重建时只记下当前转角、不复位到 0；重建后 buildMusicBar
        // 把记下的转角写回新封面视图，updateHomeSpin 再从该角度续转。
        if (ivVinyl != null) {
            try {
                savedHomeRotation = ivVinyl.getRotation();
            } catch (Throwable ignored) {
            }
        }
        if (homeSpin != null) {
            try {
                homeSpin.cancel();
            } catch (Throwable ignored) {
            }
            homeSpin = null;
        }
        if (homeHandler != null) homeHandler.removeCallbacks(homeProgressTick);
    }

    /** 进度环跟随 MusicController.position()/duration()（音乐分支专用）。 */
    private void updateHomeProgress() {
        if (homeRing == null || !barMusicMode) return;
        try {
            long pos = musicCtl().position();
            long dur = musicCtl().duration();
            homeRing.setFraction(dur > 0 ? (float) pos / (float) dur : 0f);
        } catch (Throwable ignored) {
        }
    }

    private void startHomeProgress() {
        if (homeHandler == null) homeHandler = new Handler(Looper.getMainLooper());
        homeHandler.removeCallbacks(homeProgressTick);
        homeHandler.post(homeProgressTick);
    }

    // ================= 音乐条：浅色胶囊（圆封面旋转＋外圈进度环＋歌名＋下一首＋大圆播放键） =================

    /**
     * 音乐条外观（定案形态，2026-10-21）：底部悬浮浅色圆角胶囊——
     * 左侧圆形封面（播放旋转、进度围封面外圈走）＋歌名＋下一首小图标＋右侧大圆浅色软凸起播放键。
     * 布局在 home_minibar_music.xml；点条身进播放页（现行行为保留）；
     * 条上无关闭口；暂停超 10 秒自动收现已真生效（控制器播放态变化时调
     * musicPlayingChanged 喂策略层，见 MusicController；音乐真关闭仅藏条不误停）。
     * 本方法只被 attachBar 的音乐分支调用，听书条走 buildBar()，互不影响。
     */
    private void buildMusicBar() {
        stopHomeSpin();
        Context c = app;
        View bar = LayoutInflater.from(c).inflate(R.layout.home_minibar_music, null, false);
        ivVinyl = bar.findViewById(R.id.homeCover);
        // 第三波 M3：新封面视图先恢复摘条时记下的转角，不从头转。
        if (ivVinyl != null) ivVinyl.setRotation(savedHomeRotation);
        homeRing = bar.findViewById(R.id.homeRing);
        tvTitle = bar.findViewById(R.id.homeTitle);
        homeNextBtn = bar.findViewById(R.id.homeNext);
        if (homeNextBtn != null) {
            homeNextBtn.setImageTintList(ColorStateList.valueOf(0xFF3A3A3A));
            // 第三波 M1：队尾下一首设防——无下一首时不发 next（bindMusicSession 同步置灰／不可点）。
            homeNextBtn.setOnClickListener(v -> {
                try {
                    if (musicCtl().canNext()) musicCtl().next();
                } catch (Throwable ignored) {
                }
            });
        }
        btnNext = null;
        btnPrev = null;
        btnPlay = bar.findViewById(R.id.homePlay);
        if (btnPlay != null) {
            btnPlay.setImageTintList(ColorStateList.valueOf(0xFF3A3A3A));
            btnPlay.setOnClickListener(v -> {
                try {
                    musicCtl().toggle();
                } catch (Throwable ignored) {
                }
                refreshPlayIcon();
            });
        }
        // 点条身进播放页
        bar.setOnClickListener(v -> openMusicPlayer());
        barView = bar;
        startHomeProgress();
    }

    private void openMusicPlayer() {
        try {
            Activity host = hostRef == null ? null : hostRef.get();
            Context ctx = host != null ? host : app;
            Intent it = new Intent(ctx, Class.forName("com.fongmi.android.tv.ui.activity.MusicPlayerActivity"));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable ignored) {
        }
    }

    private void bindMusicSession() {
        try {
            com.fongmi.android.tv.bean.music.MusicTrack t = musicCtl().current();
            tvTitle.setText(t == null ? "" : t.displayTitle());
            if (t != null && t.cover != null && !t.cover.isEmpty() && ivVinyl != null) {
                ImgUtil.loadCircle(t.displayTitle(), t.cover, ivVinyl);
            } else if (ivVinyl != null) {
                // 封面地址真为空才上素图（素灰底＋歌名首字，小圆排不下整名）；
                // 有地址的走加载、失败也透明，不在这里兜底（封面规矩按有无地址分）
                ivVinyl.setImageDrawable(new com.fongmi.android.tv.utils.CoverTextDrawable(
                        t == null ? "" : t.displayTitle(), true, -1));
            }
        } catch (Throwable ignored) {
        }
        // 第三波 M1：队尾下一首置灰／不可点（循环模式下 hasNext 恒 true，队尾仍可点，见 canNext 注释）。
        if (homeNextBtn != null) {
            boolean canNext = false;
            try {
                canNext = musicCtl().canNext();
            } catch (Throwable ignored) {
            }
            homeNextBtn.setEnabled(canNext);
            homeNextBtn.setAlpha(canNext ? 1f : 0.35f);
        }
        updateHomeProgress();
        refreshPlayIcon();
    }

    // ================= 服务绑定（detached 控制用） =================

    private void ensureBound() {
        if (bound) return;
        try {
            Intent it = new Intent(app, PlaybackService.class);
            app.startService(it);
            bound = app.bindService(new Intent(app, PlaybackService.class)
                    .setAction(PlaybackService.LOCAL_BIND_ACTION), conn,
                    Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            bound = false;
        }
    }

    private void releaseBind() {
        if (!bound) return;
        try {
            app.unbindService(conn);
        } catch (Throwable ignored) {
        }
        bound = false;
        svc = null;
    }

    /** 供页内 DetachedOps 兜底读取播放器（包内）。 */
    PlaybackService service() {
        return svc;
    }

    /** 当前是否有存活会话（壳层自查用）。 */
    public boolean hasSessions() {
        return policy.hasSessions();
    }

    /** 当前可见会话 id（自查用）。 */
    public String visibleSessionId() {
        return policy.visibleId();
    }

    /** drawable 小工具：金圆心（封面占位用）。 */
    static Drawable goldDot(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ReadingSkin.GOLD);
        int s = ReadingSkin.dp(c, 14);
        d.setSize(s, s);
        return d;
    }
}
