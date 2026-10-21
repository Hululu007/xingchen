package com.fongmi.android.tv.ui.activity;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.SeekBar;

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
 * 黑胶转速 20 秒／圈、外圈屏宽×0.7 上限 320dp 均为星辰侧自定，瓜子侧未抠到，不写成瓜子值。
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
    // 模式泄漏修：进页 playWithMode 把全局模式写成顺序，离页恢复队列时必须把旧模式写回去；
    // 默认值只防快照失败——快照失败时 prevHadQueue=false，恢复提前返回，哑值走不到恢复
    private int prevMode = MusicQueue.MODE_SEQUENTIAL;
    /** 本页实际播过的随机队列（洗后顺序），恢复时用它判定服务队列是否仍是本页的队 */
    private List<MusicTrack> randomQueue;
    private boolean randomReplacedQueue;

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

    // 随心听沉浸页底 #0A0E16 深色，白色图标（BaseActivity 默认已改深色图标，这里盖回）。
    @Override
    protected boolean useDarkSystemIcons() {
        return false;
    }

    protected ViewBinding getBinding() {
        binding = ActivityMusicRandomBinding.inflate(getLayoutInflater());
        return binding;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        pageAlive = true;
        // 黑胶尺寸：外圈＝屏宽×0.7、上限 320dp（GZ 绝对 dp 未抠到）；内圈封面 0.68f、中心黑点 0.053f、fallback 图标 0.24f
        int sw = getResources().getDisplayMetrics().widthPixels;
        int d = (int) Math.min(sw * 0.7f, dp(320));
        ViewGroup.LayoutParams lp = binding.discWrap.getLayoutParams();
        lp.width = d;
        lp.height = d;
        binding.discWrap.setLayoutParams(lp);
        setSize(binding.ivDiscCover, (int) (d * 0.68f));
        setSize(binding.discDot, (int) (d * 0.053f));
        setSize(binding.ivDiscFallback, (int) (d * 0.24f));
        binding.btnPrev.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        binding.btnNext.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        binding.ivDiscFallback.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        discAnim = ObjectAnimator.ofFloat(binding.discWrap, "rotation", 0f, 360f);
        discAnim.setDuration(20000);
        discAnim.setInterpolator(new LinearInterpolator());
        discAnim.setRepeatCount(ObjectAnimator.INFINITE);
        binding.btnPrev.setOnClickListener(v -> manualSwitch(() -> MusicController.get().prev()));
        binding.btnNext.setOnClickListener(v -> manualSwitch(() -> MusicController.get().next()));
        binding.btnPlayPause.setOnClickListener(v -> MusicController.get().toggle());
        // 不合口味＝切下一首（星辰无偏好学习引擎，学习效果不假装）
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
            prevHadQueue = prevQueue != null && !prevQueue.isEmpty() && prevIndex >= 0 && prevIndex < prevQueue.size();
        } catch (Throwable ignored) {
            prevHadQueue = false;
        }
    }

    /**
     * R2 页内＋清理轮 R-C2：离页恢复。只在同时满足时恢复：本页确实换过队、服务队列仍是本页随机队、
     * 且进页前／离页时播放态一致。都在播用 playFrom（起播续播）；都暂停才用 loadQueuePaused
     * （只换队不起播）；态不一致时不恢复——当前在播而快照是暂停时尤其不许调 loadQueuePaused，
     * 那会切断现播音频；快照在播而当前已暂停时也不用 playFrom 擅自起播。
     */
    private void restorePreviousQueue() {
        if (!randomReplacedQueue || !prevHadQueue || prevQueue == null || prevQueue.isEmpty()
                || prevIndex < 0 || prevIndex >= prevQueue.size()) return;
        try {
            boolean nowPlaying = MusicController.get().isPlaying();
            if (prevPlaying != nowPlaying) return;
            List<MusicTrack> cur = MusicController.get().queue();
            if (!sameTrackSequence(cur, randomQueue)) return;
            if (prevPlaying) {
                MusicController.get().playFrom(prevQueue, prevIndex, prevPosition, prevSource, prevRoute, prevRouteParams);
            } else {
                MusicController.get().loadQueuePaused(prevQueue, prevIndex, prevPosition, prevSource, prevRoute, prevRouteParams);
            }
            // 模式恢复只在恢复分支内：playFrom／loadQueuePaused 本身不改模式，进页被
            // playWithMode 写成顺序的全局模式要在这里写回旧模式；提前返回（态不一致／
            // 队已不是本页随机队）时不碰模式——此时现队已不是快照队，单改模式会改错队。
            // runOnService 按调用顺序执行，setMode 落在换队之后，不串到旧队上。
            MusicController.get().setMode(prevMode);
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
        // 本页不另调 setMode，离页恢复走原快照的 playFrom／loadQueuePaused，不改模式。
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
        if (failedTrack == null || failedTrack.id == null) return;
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
        failedTrack = failed;
        failedState = true;
        setFinding(false);
        freezeProgress();
        binding.tvShuffleTitle.setText(failed.displayTitle());
        binding.tvShuffleTitle.setVisibility(View.VISIBLE);
        setMetaLine(failed);
        binding.tvShuffleSub.setVisibility(View.VISIBLE);
        binding.tvShuffleFinding.setVisibility(View.GONE);
        binding.failedBlock.setVisibility(View.VISIBLE);
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
        binding.failedBlock.setVisibility(View.GONE);
    }

    private void setEmpty(boolean on) {
        binding.emptyBlock.setVisibility(on ? View.VISIBLE : View.GONE);
        // 空态下不给“再随机一次”空转同一条空链的口子；有队时恢复
        binding.tvRediscover.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void showTrack(MusicTrack track) {
        setFinding(false);
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
        binding.tvShuffleTitle.setVisibility(View.VISIBLE);
        setMetaLine(track);
        binding.tvShuffleSub.setVisibility(View.VISIBLE);
        if (track.cover == null || track.cover.isEmpty()) {
            try {
                com.bumptech.glide.Glide.with(binding.ivDiscCover).clear(binding.ivDiscCover);
            } catch (Throwable ignored) {
            }
            binding.ivDiscCover.setImageDrawable(null);
            binding.ivDiscFallback.setVisibility(View.VISIBLE);
        } else {
            binding.ivDiscFallback.setVisibility(View.GONE);
            ImgUtil.loadCircle(track.displayTitle(), track.cover, binding.ivDiscCover);
        }
        refreshFavButton();
        unfreezeProgress();
        updateState();
        updatePlaying(MusicController.get().isPlaying());
    }

    private void showEmptyTrack() {
        binding.tvShuffleTitle.setText("");
        binding.tvShuffleTitle.setVisibility(View.VISIBLE);
        binding.tvShuffleMeta.setVisibility(View.GONE);
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
            binding.tvShuffleMeta.setVisibility(View.GONE);
        } else {
            binding.tvShuffleMeta.setText(sb.toString());
            binding.tvShuffleMeta.setVisibility(View.VISIBLE);
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
        binding.btnPlayPause.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF1C1C1E));
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
    }

    private void setFinding(boolean on) {
        finding = on;
        binding.tvShuffleFinding.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            binding.tvShuffleFinding.setText(R.string.random_finding);
            binding.tvShuffleTitle.setVisibility(View.GONE);
            binding.tvShuffleMeta.setVisibility(View.GONE);
            binding.tvShuffleSub.setVisibility(View.GONE);
            freezeProgress();
        } else {
            if (!failedState) unfreezeProgress();
        }
        if (on) updateState();
    }

    /** R10／R17 页内：取链期显“加载中”，标题不动、进度冻结淡化、拖动禁用 */
    private void applyResolvingUi() {
        binding.tvShuffleFinding.setVisibility(View.VISIBLE);
        binding.tvShuffleFinding.setText(R.string.random_loading);
        freezeProgress();
        updateState();
    }

    private void freezeProgress() {
        binding.seekBar.setAlpha(0.45f);
        binding.seekBar.setEnabled(false);
    }

    private void unfreezeProgress() {
        binding.seekBar.setAlpha(1f);
        binding.seekBar.setEnabled(true);
    }

    private void refreshFavButton() {
        MusicTrack cur = MusicController.get().current();
        if (cur == null || cur.id == null) {
            binding.btnFav.setText("收藏这首");
            binding.btnFav.setTextColor(0xFFFFFFFF);
            return;
        }
        MusicLibrary.isFavorite(cur.id, fav -> {
            if (!pageAlive) return;
            binding.btnFav.setText(fav ? "已收藏这首" : "收藏这首");
            binding.btnFav.setTextColor(fav ? 0xFFFA243C : 0xFFFFFFFF);
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
        MusicLibrary.isFavorite(cur.id, fav -> {
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
