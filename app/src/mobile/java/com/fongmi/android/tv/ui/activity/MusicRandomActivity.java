package com.fongmi.android.tv.ui.activity;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.music.MusicSourceManager;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.databinding.ActivityMusicRandomBinding;
import com.fongmi.android.tv.music.DeviceMusicSource;
import com.fongmi.android.tv.music.MusicController;
import com.fongmi.android.tv.music.MusicDownloadStore;
import com.fongmi.android.tv.music.MusicLibrary;
import com.fongmi.android.tv.music.MusicQueue;
import com.fongmi.android.tv.music.MusicStore;
import com.fongmi.android.tv.music.PyMusicSource;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.XcFadeTransition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 第 10 项：随心听沉浸页（GZ ShuffleListenScreenKt 结构；队列来源链同首页 shufflePlay：
 * 当前队列 → 最近播放 → 收藏 → 已下载，都为空才给明确出口）。
 * 本页不放自己的返回箭头，走系统返回。
 * 第二波页面侧（R1／R2 页内／R4 页内／R8 页内／R9／R10 页内／R11／R12 页内／R14／R15／R16／R17／R19）：
 * R7 已定不加歌词视图（瓜子 ShuffleListenScreenKt 也无歌词组件，按瓜子不加）。
 * 页内态关键判定（清理轮 R-C1／R-C4：不再靠 onTrackChanged 计次）：
 * 落位以 onTrackReady 为准、失败以 onTrackFailed 为准；onTrackChanged 只分两类——
 * 已落位宣告（服务当前曲＝宣告曲且 isCurrentLoaded）直接显示，受理宣告只进 pending、
 * 标题／进度冻结。自动推进路径受理时不宣告、落位时只发一次 onTrackChanged＋一次
 * onTrackReady，旧计次机等第二次宣告等不到、标题冻结，本轮改后落位即显示。
 * 取链期目标歌被删／移位的队列编辑回滚（Q3③）只发 onTrackChanged（已装载旧歌），
 * 不是失败：页内不清失败态、不进失败态，显示回真正装着的旧歌；null 宣告（停播）
 * 也不反推失败，已有失败态时保持失败显示、无失败态时显空态。
 * 整页照瓜子重做（他亲点，2026-10-26）：浅色底＋唱片卡（瓜子 disc_bg 原图）＋刻度尺＋卡内口号
 * ＋23sp 歌名＋3dp tone 渐变进度＋72dp 黑圆播放钮＋两颗 #F0F0F4 丸子键，数值逐项见布局头注释与
 * 分片报告对照表；盘径改瓜子公式 min(可用宽， 300dp／矮屏 260dp）、转速改瓜子 24000ms／圈线性；
 * 本页不再走 MusicGlassWire 玻璃接线（瓜子此页是浅色发现面，玻璃化会把深字与钮底改坏）。
 * 卡住定根（他截图实证：选歌中＋盘心纯黑＋00:00）：封面只在落位 showTrack 里加载、
 * fallback 图标初始 GONE 也只在 showTrack／showEmptyTrack 点亮，落位宣告不到就全黑；
 * 页内状态机只等 onTrackReady／onTrackFailed，取链迟迟不落位又不失败时没有任何超时出口、
 * 无限期冻在选歌中（服务侧 isPlaying 已是 playWhenReady 真，更坐实卡在取链未落位，不是封面加载坏）。
 * 与模式泄漏不同根（那是离页写回）。修法：找歌／取链态挂 15 秒看门狗（星辰侧自定值），
 * 到期先复查服务当前曲是否已装载（宣告丢失时直接显示），仍未落位转失败态给重试出口。
 */
public class MusicRandomActivity extends BaseActivity {

    private ActivityMusicRandomBinding binding;
    private ObjectAnimator discAnim;
    private boolean discStarted;
    private boolean finding;
    private boolean seeking;
    private boolean pageAlive;
    private boolean listenerAttached;

    /** 取链期：已宣告未落位，标题／进度冻结、拖动禁用 */
    private boolean resolving;
    /** 手动切歌失败态：停在目标曲目，不弹回旧歌 */
    private boolean failedState;
    /** 失败回声守卫：onTrackFailed 后服务手动停住时还会补一次同曲 onTrackChanged（stopAtFailed），
     * 那一次不是用户重播的受理宣告，收到即消费、不许把它当成重试受理；用户后续点播放／重试
     * 前由 manualSwitch／retryFailed 先清掉本标志（见两处），避免错吞真正的受理宣告 */
    private boolean failedEchoExpected;
    private MusicTrack displayedTrack;
    private MusicTrack pendingTrack;
    private MusicTrack failedTrack;
    /** buildQueue 代次：连点“再随机一次”时旧异步链路（近期／收藏／下载）回来直接作废 */
    private int buildSeq;

    /** R2 页内快照：进页前全局队列＋序号＋来源＋断点＋播放态，离页（onDestroy）按条件恢复 */
    private List<MusicTrack> prevQueue;
    private int prevIndex = -1;
    private String prevSource;
    private String prevRoute;
    private Map<String, String> prevRouteParams;
    private long prevPosition;
    private boolean prevHadQueue;
    private boolean prevPlaying;
    // 模式泄漏修：进页 playWithMode 把全局模式写成顺序，离页时必须把旧模式写回去
    //（队列恢复走不走得通都要还，见 restorePreviousQueue）；快照没拍成（prevSnapshotOk=false）
    // 时 prevMode 是哑默认，不许写回
    private int prevMode = MusicQueue.MODE_SEQUENTIAL;
    private boolean prevSnapshotOk;
    /** 本页实际播过的随机队列（洗后顺序），恢复时用它判定服务队列是否仍是本页的队 */
    private List<MusicTrack> randomQueue;
    private boolean randomReplacedQueue;

    /** 取链看门狗：找歌／取链超过此时长仍未落位，转失败态给重试出口（星辰侧自定 15 秒，瓜子无此值） */
    private static final long RESOLVE_WATCHDOG_MS = 15000;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Runnable watchdogRun = this::onResolveWatchdog;
    /** 当前封面 tone 的 accent（tone 向黑混 0.28，瓜子 discoveryAccent 口径）；无封面时为 textHigh */
    private int toneAccent = 0xFF1C1C1E;

    private final MusicController.Listener listener = new MusicController.Listener() {
        @Override
        public void onTrackChanged(MusicTrack track) {
            runOnUiThread(() -> {
                if (!pageAlive) return;
                handleTrackChanged(track);
            });
        }

        @Override
        public void onTrackReady(MusicTrack track) {
            runOnUiThread(() -> {
                if (!pageAlive) return;
                handleTrackReady(track);
            });
        }

        @Override
        public void onTrackFailed(MusicTrack track, int reasonRes) {
            runOnUiThread(() -> {
                if (!pageAlive) return;
                handleTrackFailed(track);
            });
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            runOnUiThread(() -> {
                if (!pageAlive) return;
                updatePlaying(playing);
            });
        }

        @Override
        public void onProgress(long position, long duration) {
            runOnUiThread(() -> {
                if (!pageAlive || seeking || finding || resolving || failedState) return;
                binding.seekBar.setMax((int) duration);
                binding.seekBar.setProgress((int) position);
                binding.tvCur.setText(fmt(position));
                binding.tvDur.setText(fmt(duration));
            });
        }
    };

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, MusicRandomActivity.class));
    }

    // 随心听页已照瓜子改浅色底，深色系统图标（与浅底对比得上）
    @Override
    protected boolean useDarkSystemIcons() {
        return true;
    }

    protected ViewBinding getBinding() {
        binding = ActivityMusicRandomBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        pageAlive = true;
        // 盘径照瓜子公式：min(卡内可用宽， 300dp)，矮屏（高 <740dp）走紧凑支：上限 260、
        // 卡横内距 16／底内距 12、唱片区顶距 10、口号隐藏、卡与面板间距 14、面板块间距 12。
        float density = getResources().getDisplayMetrics().density;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        boolean compact = sh / density < 740f;
        int cardPadH = compact ? 16 : 24;
        binding.cardRoot.setPadding(dp(cardPadH), binding.cardRoot.getPaddingTop(),
                dp(cardPadH), dp(compact ? 12 : 20));
        if (compact) binding.tvSlogan.setVisibility(View.GONE);
        ViewGroup.MarginLayoutParams gapLp = (ViewGroup.MarginLayoutParams) binding.gapCardPanel.getLayoutParams();
        gapLp.height = dp(compact ? 14 : 20);
        binding.gapCardPanel.setLayoutParams(gapLp);
        int panelGap = dp(compact ? 12 : 16);
        setTopMargin(binding.progressBlock, panelGap);
        setTopMargin(binding.transportRow, panelGap);
        // 反馈行他真机亲点「往下挪」（1.4.47）：在统一块间距之外再加一档，与运输行拉开、整行更靠页面下方
        setTopMargin(binding.prefsRow, panelGap + dp(compact ? 12 : 16));
        int innerW = sw - dp(16 * 2) - dp(cardPadH * 2);
        int d = Math.min(innerW, dp(compact ? 260 : 300));
        ViewGroup.LayoutParams lp = binding.discWrap.getLayoutParams();
        lp.width = d;
        lp.height = d;
        ViewGroup.MarginLayoutParams discLp = (ViewGroup.MarginLayoutParams) lp;
        discLp.topMargin = dp(compact ? 10 : 16);
        binding.discWrap.setLayoutParams(lp);
        setSize(binding.ivDiscCover, (int) (d * 0.68f));
        setSize(binding.discDot, (int) (d * 0.053f));
        setSize(binding.ivDiscFallback, (int) (d * 0.24f));
        binding.btnPrev.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
        binding.btnNext.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
        // 无封面 fallback 图标：瓜子口径白 @0.85
        binding.ivDiscFallback.setImageTintList(android.content.res.ColorStateList.valueOf(0xD9FFFFFF));
        applySeekTone(0xFF1C1C1E);
        discAnim = ObjectAnimator.ofFloat(binding.discWrap, "rotation", 0f, 360f);
        discAnim.setDuration(24000);
        discAnim.setInterpolator(new LinearInterpolator());
        discAnim.setRepeatCount(ObjectAnimator.INFINITE);
        binding.btnPrev.setOnClickListener(v -> manualSwitch(() -> MusicController.get().prev()));
        binding.btnNext.setOnClickListener(v -> manualSwitch(() -> MusicController.get().next()));
        binding.btnPlayPause.setOnClickListener(v -> MusicController.get().toggle());
        // 「不合口味」照瓜子原键名落码（他亲点整页照瓜子）；星辰无偏好学习引擎，点击行为仍是
        // 切下一首；此前被定为假许诺的是「正在结合你的收藏与听歌足迹…由你的偏好决定」那段
        // 说明文案——那段不恢复，页内副文案只写星辰真实来源链
        pinBtnIcon(binding.btnDislike, R.drawable.ic_shuffle_dislike, 0, false);
        pinBtnIcon(binding.btnFav, R.drawable.ic_music_heart, 0xFF1C1C1E, true);
        binding.btnDislike.setOnClickListener(v -> manualSwitch(() -> MusicController.get().next()));
        binding.btnFav.setOnClickListener(v -> toggleFav());
        binding.tvRediscover.setOnClickListener(v -> buildQueue());
        binding.btnRetry.setOnClickListener(v -> retryFailed());
        binding.btnEmptyHome.setOnClickListener(v -> finish());
        binding.seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) binding.tvCur.setText(fmt(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                if (finding || resolving || failedState) return;
                MusicController.get().seekTo(seekBar.getProgress());
            }
        });
        // R8 页内：补 bind（此前本页从不 bind），与播放页／音乐首页同口径：创建时 bind、销毁时 unbind
        MusicController.get().bind(this);
        attachListener();
        if (savedInstanceState != null) {
            // R1 重建守卫：转屏已由 Manifest orientation configChanges 兜住不再重建；其它原因重建
            // 也不重洗、不从头重播，只按服务当前态恢复页内显示
            MusicTrack cur = null;
            try {
                cur = MusicController.get().current();
            } catch (Throwable ignored) {
            }
            if (cur != null) showTrack(cur);
            else updateState();
            updatePlaying(MusicController.get().isPlaying());
            return;
        }
        snapshotPreviousQueue();
        // 进页即按随机链起播（同首页随心听卡旧行为，只是落点改成本页）
        buildQueue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        attachListener();
        // 后台回来只刷新已落位态：找歌期／取链期／失败态不被 resumed 刷新冲掉
        if (pageAlive && !finding && !resolving && pendingTrack == null && !failedState) {
            MusicTrack cur = null;
            try {
                cur = MusicController.get().current();
            } catch (Throwable ignored) {
            }
            if (cur != null) showTrack(cur);
            updatePlaying(MusicController.get().isPlaying());
        }
    }

    @Override
    protected void onPause() {
        // R4 页内：后台栈摘监听（后台不摘是互串根子之一）；onResume 再挂回并刷新，见 onResume
        detachListener();
        try {
            if (discAnim != null && discStarted) discAnim.pause();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        pageAlive = false;
        watchdogHandler.removeCallbacks(watchdogRun);
        detachListener();
        MusicController.get().unbind();
        restorePreviousQueue();
        if (discAnim != null) discAnim.cancel();
        super.onDestroy();
    }

    private void attachListener() {
        if (listenerAttached) return;
        MusicController.get().addListener(listener);
        listenerAttached = true;
    }

    private void detachListener() {
        if (!listenerAttached) return;
        MusicController.get().removeListener(listener);
        listenerAttached = false;
    }

    /** R2 页内：进页前快照（只在全新进页做一次，重建不重拍，避免把随机队本身拍成“旧队”） */
    private void snapshotPreviousQueue() {
        try {
            List<MusicTrack> q = MusicController.get().queue();
            int idx = MusicController.get().index();
            prevQueue = q == null ? null : new ArrayList<>(q);
            prevIndex = idx;
            prevSource = MusicController.get().queueSource();
            prevRoute = MusicController.get().queueRoute();
            prevRouteParams = MusicController.get().queueRouteParams();
            prevPosition = MusicController.get().position();
            prevPlaying = MusicController.get().isPlaying();
            prevMode = MusicController.get().getMode();
            prevSnapshotOk = true;
            prevHadQueue = prevQueue != null && !prevQueue.isEmpty() && prevIndex >= 0 && prevIndex < prevQueue.size();
        } catch (Throwable ignored) {
            prevHadQueue = false;
            prevSnapshotOk = false;
        }
    }

    /**
     * R2 页内＋清理轮 R-C2：离页恢复。只在同时满足时恢复：本页确实换过队、服务队列仍是本页随机队、
     * 且进页前／离页时播放态一致。都在播用 playFrom（起播续播）；都暂停才用 loadQueuePaused
     * （只换队不起播）；态不一致时不恢复——当前在播而快照是暂停时尤其不许调 loadQueuePaused，
     * 那会切断现播音频；快照在播而当前已暂停时也不用 playFrom 擅自起播。
     */
    private void restorePreviousQueue() {
        if (!randomReplacedQueue) return;
        try {
            // 队列恢复仍守三条件（换过队／仍是本页随机队／播放态一致），任一不中不换队
            boolean queueOk = prevHadQueue && prevQueue != null && !prevQueue.isEmpty()
                    && prevIndex >= 0 && prevIndex < prevQueue.size();
            if (queueOk) {
                boolean nowPlaying = MusicController.get().isPlaying();
                List<MusicTrack> cur = MusicController.get().queue();
                if (prevPlaying == nowPlaying && sameTrackSequence(cur, randomQueue)) {
                    if (prevPlaying) {
                        MusicController.get().playFrom(prevQueue, prevIndex, prevPosition, prevSource, prevRoute, prevRouteParams);
                    } else {
                        MusicController.get().loadQueuePaused(prevQueue, prevIndex, prevPosition, prevSource, prevRoute, prevRouteParams);
                    }
                }
            }
            // 模式写回不再挂在队列恢复成功上：进页 playWithMode 已把全局模式写成顺序，
            // 这是本页对全局态的改动，与现队归谁无关——此前早退路径（态不一致／队被换）
            // 不写回，用户原模式（如随机）被永久顶掉（体检 A 片）。快照没拍成时 prevMode
            // 是哑默认、不许拿它覆盖真模式，故用 prevSnapshotOk 守卫。
            // runOnService 按调用顺序执行，setMode 落在换队之后，不串序。
            if (prevSnapshotOk) MusicController.get().setMode(prevMode);
        } catch (Throwable ignored) {
        }
    }

    private void manualSwitch(Runnable action) {
        // 手动切歌先清失败态：失败标题仍冻结在页上，新宣告按 pending／落位流程走，不被旧失败态截住；
        // 同时清失败回声守卫，后续受理宣告是真正的用户切歌，不许被当成 stopAtFailed 回声吞掉
        failedEchoExpected = false;
        if (failedState) {
            failedState = false;
            failedTrack = null;
            hideFailed();
        }
        try {
            action.run();
        } catch (Throwable ignored) {
        }
    }

    /** 随机队列来源链：当前队列 → 最近播放 → 收藏 → 已下载；都为空才给明确出口 */
    private void buildQueue() {
        if (!pageAlive) return;
        int seq = ++buildSeq;
        clearFailedState();
        setEmpty(false);
        setFinding(true);
        List<MusicTrack> queue = null;
        try {
            queue = MusicController.get().queue();
        } catch (Throwable ignored) {
        }
        if (queue != null && !queue.isEmpty()) {
            startRandom(new ArrayList<>(queue));
            return;
        }
        // R16：近期放后台读（Prefs JSON＋Gson 解析），回主线程再建队；收藏／下载链本身已是异步回主线程
        Task.execute(() -> {
            List<MusicTrack> recent = new ArrayList<>();
            try {
                recent = MusicStore.loadRecent();
            } catch (Throwable ignored) {
            }
            final List<MusicTrack> fRecent = recent;
            runOnUiThread(() -> {
                if (!pageAlive || seq != buildSeq) return;
                if (fRecent != null && !fRecent.isEmpty()) {
                    startRandom(fRecent);
                    return;
                }
                MusicLibrary.favorites(list -> {
                    if (!pageAlive || seq != buildSeq) return;
                    if (list != null && !list.isEmpty()) {
                        startRandom(new ArrayList<>(list));
                        return;
                    }
                    MusicDownloadStore.all(downloads -> {
                        if (!pageAlive || seq != buildSeq) return;
                        if (downloads != null && !downloads.isEmpty()) {
                            startRandom(new ArrayList<>(downloads));
                        } else {
                            // R14：全空不再只剩 Notify——空态块给明确出口（回音乐首页），
                            // 同时空态下隐藏“再随机一次”，不再让它空转同一条空链
                            setFinding(false);
                            freezeProgress();
                            setEmpty(true);
                            Notify.show(R.string.music_no_track);
                        }
                    });
                });
            });
        });
    }

    private void startRandom(List<MusicTrack> tracks) {
        if (!pageAlive || tracks == null || tracks.isEmpty()) return;
        List<MusicTrack> shuffled = new ArrayList<>(tracks);
        Collections.shuffle(shuffled);
        randomQueue = new ArrayList<>(shuffled);
        randomReplacedQueue = true;
        // 清理轮 R-C3：入页用 playWithMode 一次定序——洗后队列＋MODE_SEQUENTIAL，
        // next() 按洗后顺序走，不走全局 SHUFFLE 的现抽随机；不给队列加新标记位。
        // 注意钩子本身会把存盘的全局模式写成顺序（服务 playQueueWithMode 口径），
        // 本页不另调 setMode；离页时 restorePreviousQueue 无条件写回快照的 prevMode。
        MusicController.get().playWithMode(shuffled, 0, MusicQueue.MODE_SEQUENTIAL,
                getString(R.string.home_shuffle), "home", null);
        // finding 不在这里清：受理宣告只进 pending，等落位事件再清，见 handleTrackChanged／handleTrackReady
    }

    /**
     * 清理轮 R-C1／R-C4：页内 track 宣告状态机（不再计次）。
     * 已落位宣告直接显示：服务当前曲＝宣告曲且 isCurrentLoaded——自动推进落位时
     * 只有这一次 onTrackChanged，随后 onTrackReady 再显示一次（幂等），不再等第二次宣告；
     * 手动路径落位宣告同样走这里，onTrackReady 是第二道显示，两道互为兜底。
     * 其余 onTrackChanged 都是受理宣告：只进 pending、标题／进度冻结。
     * Q3③回滚宣告（已装载旧歌重宣告）走已落位分支：显示回真正装着的旧歌，不进失败态；
     * null 宣告（停播）不反推失败：已有失败态保持失败显示，否则显空态。
     */
    private void handleTrackChanged(MusicTrack track) {
        if (track == null) {
            // 停播宣告：不是失败信号。失败态由 onTrackFailed 定，停播不许把它冲掉；
            // 失败回声守卫随停播宣告过期（自动停机路不发同曲回声，只发 null）。
            failedEchoExpected = false;
            if (!failedState) {
                pendingTrack = null;
                resolving = false;
                displayedTrack = null;
                showEmptyTrack();
            }
            return;
        }
        if (failedState && failedEchoExpected && failedTrack != null && track == failedTrack) {
            // 同对象回声（无 id 曲目也走这里：id 比对不到，但 stopAtFailed 发的就是同一对象）
            failedEchoExpected = false;
            return;
        }
        if (track.id == null) {
            // 无 id 曲目无法做 pending／回滚判定，走旧直显；收藏提示见 R19 toggleFav
            pendingTrack = null;
            resolving = false;
            showTrack(track);
            return;
        }
        if (failedState && failedTrack != null && failedTrack.id != null
                && track.id.equals(failedTrack.id) && failedEchoExpected) {
            // stopAtFailed 手动停住后的同曲回声：不是用户重播受理，消费一次、保持失败显示
            failedEchoExpected = false;
            return;
        }
        if (isLoadedTrack(track)) {
            // 已落位宣告：自动推进单次宣告、手动落位宣告、Q3③回滚宣告、重绑补发都在这里显示
            failedEchoExpected = false;
            pendingTrack = null;
            resolving = false;
            showTrack(track);
            return;
        }
        if (failedState && failedTrack != null && failedTrack.id != null
                && track.id.equals(failedTrack.id)) {
            // 用户在失败态下按播放／重试的受理宣告（回声已消费或已被 manualSwitch／retryFailed 清掉）：
            // 标题已是失败曲目本身，保持标题不动，回到取链期等落位事件
            failedState = false;
            failedTrack = null;
            hideFailed();
            pendingTrack = track;
            resolving = true;
            applyResolvingUi();
            return;
        }
        // 受理宣告：新宣告取代旧 pending（连点切歌）或首个 pending，继续冻结旧标题
        pendingTrack = track;
        resolving = true;
        applyResolvingUi();
    }

    /** 服务当前曲＝宣告曲且播放器里真装着它（暂停已装载也算落位，不只在播才算） */
    private boolean isLoadedTrack(MusicTrack track) {
        if (track == null || track.id == null) return false;
        try {
            MusicTrack cur = MusicController.get().current();
            return cur != null && track.id.equals(cur.id) && MusicController.get().isCurrentLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 清理轮 R-C1：落位事件为准显示。自动推进失败后跳歌成功时，本事件的歌与失败歌不同，
     * showTrack 会清掉失败态；失败歌重试成功时同样清失败态回到正常显示。 */
    private void handleTrackReady(MusicTrack track) {
        if (track == null) return;
        failedEchoExpected = false;
        pendingTrack = null;
        resolving = false;
        showTrack(track);
    }

    /**
     * 清理轮 R-C4：失败事件为准进失败态。reasonRes 是服务失败原因串（页内失败块仍用
     * 现成 random_failed_hint 文案，不另起新串）。这里不做“失败歌是否当前／pending”
     * 相关性过滤：自动推进失败时服务当前曲仍是上一首（序号只在落位时才移），
     * 过滤会把真失败误杀；过期失败本就到不了这里——服务取链结果有 prepareSeq
     * 守卫，被新请求接管的旧取链失败不会发事件，播放器报错也只报当前轨。
     */
    private void handleTrackFailed(MusicTrack track) {
        if (track == null) return;
        pendingTrack = null;
        resolving = false;
        failedEchoExpected = true;
        enterFailedState(track);
    }

    private void retryFailed() {
        if (failedTrack == null || failedTrack.id == null) {
            // 看门狗转的无目标失败态：没有可重试的曲目，整池重洗重新选歌
            clearFailedState();
            buildQueue();
            return;
        }
        // 重试受理宣告不是 stopAtFailed 回声：先清守卫，playAt 的受理宣告走失败态受理分支
        failedEchoExpected = false;
        List<MusicTrack> q = null;
        try {
            q = MusicController.get().queue();
        } catch (Throwable ignored) {
        }
        if (q != null) {
            for (int i = 0; i < q.size(); i++) {
                if (q.get(i) != null && failedTrack.id.equals(q.get(i).id)) {
                    MusicController.get().playAt(i);
                    return;
                }
            }
        }
        Notify.show(R.string.random_retry_gone);
    }

    private void enterFailedState(MusicTrack failed) {
        cancelWatchdog();
        failedTrack = failed;
        failedState = true;
        setFinding(false);
        freezeProgress();
        binding.tvShuffleTitle.setText(failed.displayTitle());
        XcFadeTransition.fadeIn(binding.tvShuffleTitle);
        setMetaLine(failed);
        XcFadeTransition.fadeIn(binding.tvShuffleSub);
        XcFadeTransition.fadeOutThenGone(binding.tvShuffleFinding);
        XcFadeTransition.fadeIn(binding.failedBlock);
        binding.tvFailedHint.setText(R.string.random_failed_hint);
        updateState();
        updatePlaying(MusicController.get().isPlaying());
    }

    private void clearFailedState() {
        if (!failedState && failedTrack == null) return;
        failedState = false;
        failedTrack = null;
        hideFailed();
    }

    private void hideFailed() {
        XcFadeTransition.fadeOutThenGone(binding.failedBlock);
    }

    private void setEmpty(boolean on) {
        if (on) XcFadeTransition.fadeIn(binding.emptyBlock); else XcFadeTransition.fadeOutThenGone(binding.emptyBlock);
        // 空态下不给“再随机一次”空转同一条空链的口子；有队时恢复
        if (on) XcFadeTransition.fadeOutThenGone(binding.tvRediscover); else XcFadeTransition.fadeIn(binding.tvRediscover);
    }

    private void showTrack(MusicTrack track) {
        cancelWatchdog();
        setFinding(false);
        XcFadeTransition.fadeOutThenGone(binding.findingSkeleton);
        displayedTrack = track;
        if (failedState) {
            failedState = false;
            failedTrack = null;
            hideFailed();
        }
        if (track == null) {
            showEmptyTrack();
            return;
        }
        binding.tvShuffleTitle.setText(track.displayTitle());
        XcFadeTransition.fadeIn(binding.tvShuffleTitle);
        setMetaLine(track);
        XcFadeTransition.fadeIn(binding.tvShuffleSub);
        if (track.cover == null || track.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(binding.ivDiscCover).clear(binding.ivDiscCover);
            } catch (Throwable ignored) {
            }
            binding.ivDiscCover.setImageDrawable(null);
            binding.ivDiscFallback.setVisibility(View.VISIBLE);
            applySeekTone(0xFF1C1C1E);
            toneAccent = 0xFF1C1C1E;
            binding.ruler.setToneColor(0x8C8E8E93);
            refreshStateDot();
        } else {
            binding.ivDiscFallback.setVisibility(View.GONE);
            ImgUtil.loadCircleFade(track.displayTitle(), track.cover, binding.ivDiscCover);
            fetchCoverTone(track);
        }
        refreshFavButton();
        unfreezeProgress();
        updateState();
        updatePlaying(MusicController.get().isPlaying());
    }

    private void showEmptyTrack() {
        cancelWatchdog();
        binding.tvShuffleTitle.setText("");
        XcFadeTransition.fadeIn(binding.tvShuffleTitle);
        XcFadeTransition.fadeOutThenGone(binding.tvShuffleMeta);
        binding.ivDiscFallback.setVisibility(View.VISIBLE);
        binding.ivDiscCover.setImageDrawable(null);
        unfreezeProgress();
        updateState();
    }

    /** R11：身份块补歌手／专辑／来源行（字段现成未用；来源查不到不显示，不拿 platform 原始码冒充名） */
    private void setMetaLine(MusicTrack track) {
        StringBuilder sb = new StringBuilder();
        String artist = track.displayArtist() == null ? "" : track.displayArtist().trim();
        String album = track.album == null ? "" : track.album.trim();
        String source = sourceName(track);
        if (!artist.isEmpty()) sb.append(artist);
        if (!album.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(album);
        }
        if (!source.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(source);
        }
        if (sb.length() == 0) {
            XcFadeTransition.fadeOutThenGone(binding.tvShuffleMeta);
        } else {
            binding.tvShuffleMeta.setText(sb.toString());
            XcFadeTransition.fadeIn(binding.tvShuffleMeta);
        }
    }

    /** 来源名：照播放页 sourceName 口径精简——先按 rawJson _plugin 精确对插件，查不到再按 platform 匹配，本地／设备／py 源另行映射，仍查不到返回空（不显示） */
    private String sourceName(MusicTrack track) {
        if (track == null || track.platform == null) return "";
        try {
            String plugin = null;
            if (track.rawJson != null && !track.rawJson.isEmpty()) {
                plugin = com.google.gson.JsonParser.parseString(track.rawJson)
                        .getAsJsonObject().get("_plugin").getAsString();
            }
            List<MusicSite> sites = MusicSourceManager.get().listSites();
            if (plugin != null) {
                for (MusicSite site : sites) {
                    if (site != null && plugin.equals(site.srcUrl)) return site.name == null ? "" : site.name;
                }
            }
            for (MusicSite site : sites) {
                if (site != null && track.platform.equals(site.platform)) return site.name == null ? "" : site.name;
            }
        } catch (Throwable ignored) {
        }
        if ("local".equals(track.platform)) return getString(R.string.music_downloaded);
        if (DeviceMusicSource.PLATFORM.equals(track.platform)) return getString(R.string.music_device);
        if (PyMusicSource.PLATFORM.equals(track.platform)) return PyMusicSource.siteNameOf(track);
        return "";
    }

    private void updatePlaying(boolean playing) {
        if (!pageAlive) return;
        binding.btnPlayPause.setImageResource(playing ? R.drawable.ic_music_pause : R.drawable.ic_music_play);
        // 中央钮已照瓜子改 #1C1C1E 黑圆底，图标用 contentSurface #FAFAFC（深底浅图标，对比得上）
        binding.btnPlayPause.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFAFAFC));
        binding.btnPlayPause.setContentDescription(playing ? "暂停" : "播放");
        if (discAnim == null) {
            updateState();
            return;
        }
        if (playing) {
            if (!discStarted) {
                discAnim.start();
                discStarted = true;
            } else {
                discAnim.resume();
            }
        } else {
            if (discStarted) discAnim.pause();
        }
        updateState();
    }

    /** 状态文案：失败／加载中优先于播放态，取链期不把旧歌播放态当新歌状态 */
    private void updateState() {
        String state;
        if (failedState) state = getString(R.string.random_failed);
        else if (resolving) state = getString(R.string.random_loading);
        else if (finding || MusicController.get().current() == null) state = "选歌中";
        else state = MusicController.get().isPlaying() ? "正在播放" : "等待聆听";
        binding.tvShuffleState.setText(state);
        refreshStateDot();
    }

    private void setFinding(boolean on) {
        finding = on;
        if (on) XcFadeTransition.fadeIn(binding.tvShuffleFinding); else XcFadeTransition.fadeOutThenGone(binding.tvShuffleFinding);
        // 找歌态照瓜子 TrackIdentity：骨架条与找歌文案同显同隐
        if (on) XcFadeTransition.fadeIn(binding.findingSkeleton); else XcFadeTransition.fadeOutThenGone(binding.findingSkeleton);
        if (on) {
            binding.tvShuffleFinding.setText(R.string.random_finding);
            XcFadeTransition.fadeOutThenGone(binding.tvShuffleTitle);
            XcFadeTransition.fadeOutThenGone(binding.tvShuffleMeta);
            XcFadeTransition.fadeOutThenGone(binding.tvShuffleSub);
            freezeProgress();
            scheduleWatchdog();
        } else {
            cancelWatchdog();
            if (!failedState) unfreezeProgress();
        }
        if (on) updateState();
    }

    /** R10／R17 页内：取链期显“加载中”，标题不动、进度冻结淡化、拖动禁用 */
    private void applyResolvingUi() {
        XcFadeTransition.fadeIn(binding.tvShuffleFinding);
        binding.tvShuffleFinding.setText(R.string.random_loading);
        XcFadeTransition.fadeIn(binding.findingSkeleton);
        freezeProgress();
        scheduleWatchdog();
        updateState();
    }

    // ---- 取链看门狗：找歌／取链迟迟不落位时先复查装载态，仍未落位转失败态（定根见类头） ----
    private void scheduleWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRun);
        watchdogHandler.postDelayed(watchdogRun, RESOLVE_WATCHDOG_MS);
    }

    private void cancelWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRun);
    }

    private void onResolveWatchdog() {
        if (!pageAlive || failedState) return;
        if (!finding && !resolving) return;
        MusicTrack cur = null;
        try {
            cur = MusicController.get().current();
        } catch (Throwable ignored) {
        }
        if (cur != null && isLoadedTrack(cur)) {
            // 宣告丢失但服务其实已装载：直接显示，不误报失败
            pendingTrack = null;
            resolving = false;
            showTrack(cur);
            return;
        }
        if (pendingTrack != null) {
            enterFailedState(pendingTrack);
            return;
        }
        // 首曲找歌链卡住、无目标曲目：转无目标失败态，重试钮改走整池重洗（见 retryFailed）
        setFinding(false);
        failedState = true;
        failedTrack = null;
        freezeProgress();
        binding.tvFailedHint.setText(R.string.random_failed_hint);
        XcFadeTransition.fadeIn(binding.failedBlock);
        updateState();
        updatePlaying(MusicController.get().isPlaying());
    }

    /** 封面 tone：进度填充渐变（tone → 向白混 0.22）、状态点与刻度尺的 accent（tone 向黑混 0.28）都从这里出 */
    private void fetchCoverTone(MusicTrack track) {
        final MusicTrack target = track;
        ImgUtil.load(track.cover, new com.fongmi.android.tv.impl.CustomTarget<android.graphics.Bitmap>() {
            @Override
            public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap bmp,
                                        com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                Task.execute(() -> {
                    int dom = com.fongmi.android.tv.utils.CoverColor.dominantColor(bmp);
                    runOnUiThread(() -> {
                        if (!pageAlive || displayedTrack != target) return;
                        toneAccent = androidx.core.graphics.ColorUtils.blendARGB(dom, 0xFF000000, 0.28f);
                        applySeekTone(dom);
                        binding.ruler.setToneColor(androidx.core.graphics.ColorUtils.setAlphaComponent(toneAccent, 140));
                        refreshStateDot();
                    });
                });
            }

            @Override
            public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
            }
        });
    }

    /** 进度条填充按瓜子：封面 tone 渐变（tone → 向白混 0.22）；无 tone 时退 textHigh 实色渐变 */
    private void applySeekTone(int tone) {
        try {
            android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
            track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            track.setColor(0x2E8E8E93);
            track.setCornerRadius(dp(2));
            int light = androidx.core.graphics.ColorUtils.blendARGB(tone, 0xFFFFFFFF, 0.22f);
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{tone, light});
            fill.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            fill.setCornerRadius(dp(2));
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                    fill, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{track, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            int p = binding.seekBar.getProgress();
            int m = binding.seekBar.getMax();
            binding.seekBar.setProgressDrawable(ld);
            binding.seekBar.setMax(m);
            binding.seekBar.setProgress(p);
        } catch (Throwable ignored) {
        }
    }

    /** 状态点：播放且已落位时染封面 accent，其余回默认灰（瓜子口径 accent/默认二态） */
    private void refreshStateDot() {
        boolean active = !failedState && !finding && !resolving && displayedTrack != null
                && MusicController.get().isPlaying();
        binding.dotState.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                active ? toneAccent : 0xFFC7C7CC));
    }

    private void freezeProgress() {
        binding.seekBar.setAlpha(0.45f);
        binding.seekBar.setEnabled(false);
    }

    private void unfreezeProgress() {
        binding.seekBar.setAlpha(1f);
        binding.seekBar.setEnabled(true);
    }

    // 反馈丸子键图标限尺寸（他 1.4.47 真机定罪「框小了，图标都漏出来了」）：两颗图标资源固有
    // 24dp、设计真值 15dp，而 XML 的 drawableStart 与原先的 WithIntrinsicBounds 都按固有尺寸
    // 挂、不设界，真机上图标贴钮左缘被切、图文不成组。统一走这里：显式设界 15dp 再挂，
    // 尺寸再不受资源固有值影响；着色口径与原调用逐字一致（不合口味钮图标自带灰、不着色）。
    private void pinBtnIcon(TextView btn, int resId, int tint, boolean applyTint) {
        Drawable d = getDrawable(resId);
        if (d == null) return;
        int s = dp(15);
        d.setBounds(0, 0, s, s);
        btn.setCompoundDrawablesRelative(d, null, null, null);
        if (applyTint) {
            btn.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(tint));
        }
    }

    private void refreshFavButton() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null || cur.id == null) {
            binding.btnFav.setText("收藏这首");
            binding.btnFav.setTextColor(0xFF1C1C1E);
            pinBtnIcon(binding.btnFav, R.drawable.ic_music_heart, 0xFF1C1C1E, true);
            return;
        }
        MusicLibrary.isFavorite(cur, fav -> {
            if (!pageAlive) return;
            binding.btnFav.setText(fav ? "已收藏这首" : "收藏这首");
            // 浅底丸子键：未收藏 textHigh、已收藏红（星辰既有口径色 #FA243C 保留）
            binding.btnFav.setTextColor(fav ? 0xFFFA243C : 0xFF1C1C1E);
            pinBtnIcon(binding.btnFav,
                    fav ? R.drawable.ic_music_heart_fill : R.drawable.ic_music_heart,
                    fav ? 0xFFFA243C : 0xFF1C1C1E, true);
        });
    }

    private void toggleFav() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null) return;
        // R19：无 id 曲目收藏不再静默失效，给可见提示；被动刷新（refreshFavButton）不弹提示，避免每切歌弹一次
        if (cur.id == null) {
            Notify.show(R.string.random_no_id_fav);
            return;
        }
        MusicLibrary.isFavorite(cur, fav -> {
            if (!pageAlive) return;
            MusicLibrary.setFavorite(cur, !fav, () -> {
                if (!pageAlive) return;
                refreshFavButton();
            });
        });
    }

    private static boolean sameTrackId(MusicTrack a, MusicTrack b) {
        if (a == null || b == null) return false;
        if (a.id != null && b.id != null) return a.id.equals(b.id);
        if (a.id == null && b.id == null) return a.displayTitle().equals(b.displayTitle());
        return false;
    }

    private static boolean sameTrackSequence(List<MusicTrack> a, List<MusicTrack> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!sameTrackId(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private void setTopMargin(View v, int top) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin = top;
        v.setLayoutParams(lp);
    }

    private void setSize(View v, int size) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = size;
        lp.height = size;
        v.setLayoutParams(lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }
}
