package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.fongmi.android.tv.bean.music.LyricVersion
import com.fongmi.android.tv.bean.music.MusicTrack
import com.fongmi.android.tv.music.MusicController
import com.fongmi.android.tv.music.MusicLibrary
import com.fongmi.android.tv.music.MusicLyricCache
import com.fongmi.android.tv.music.MusicRepository
import com.fongmi.android.tv.music.MusicStore
import com.fongmi.android.tv.music.RichLyrics
import com.fongmi.android.tv.player.lyrics.LyricsParser
import com.fongmi.android.tv.utils.CoverColor
import com.fongmi.android.tv.utils.ImgUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * 播放界面状态中枢（照瓜子 PlayerViewModel 的位置）：进程内一份，
 * 播放条（首页）与播放页（独立 Activity）订同一份 StateFlow——两处内容
 * 永远同源同值，这是条⇄页开合「封面/歌名/进度全程连续」的结构根。
 *
 * 数据只从保留区来，不建第二套：状态订 MusicController（它转达
 * MusicPlaybackService 的事件），进度由本中枢 500ms 轮询现拉（与旧迷你条
 * 同一驱动法，也是瓜子 VM ticker 的组织法）；封面位图经 ImgUtil 取一次，
 * 主色经 CoverColor.shellBaseColor（瓜子 mk1 链）在状态层算一次全场喂；
 * 歌词读 MusicLyricCache 的既有产出、用 LyricsParser 解析成行。
 * 命令出口全部转发 MusicController，本中枢不碰播放器本体。
 */
object PlayerStateHolder {

    private const val TICK_MS = 500L

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /**
     * 设置修订号（歌词样式/外观/窗口旗这类 Prefers 值的变更信号）：Prefers 本身
     * 不是 Compose 状态，面板写完存档后 bump 一次，读设置的面（歌词引擎快照、
     * 氛围层、播放页窗口旗）以它为 remember 键重读——「改了当场生效」靠这条，
     * 不另建第二套设置数据层。
     */
    private val _settingsRev = MutableStateFlow(0)
    val settingsRev: StateFlow<Int> = _settingsRev.asStateFlow()

    fun bumpSettings() {
        _settingsRev.value += 1
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val colorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PlayerCoverColor").apply { isDaemon = true }
    }

    @Volatile
    private var attached = false

    /** 封面加载代次：切歌后旧图的回调一律作废，不许迟到写回（防挂旧脸）。 */
    @Volatile
    private var coverSeq = 0

    /** 已收到「词备好」信号的曲目键集合口径（单键记账）：与缓存对读区分「找词中／确无词」。 */
    @Volatile
    private var lyricReadyKey: String? = null

    private val listener = object : MusicController.Listener {
        override fun onTrackChanged(track: MusicTrack?) {
            lyricReadyKey = null
            refresh()
        }

        override fun onPlayingChanged(playing: Boolean) {
            _state.value = _state.value.copy(isPlaying = playing)
        }

        override fun onProgress(position: Long, duration: Long) {
            pushProgress(position, duration)
        }

        override fun onQueueChanged() {
            refresh()
        }

        override fun onModeChanged(mode: Int) {
            _state.value = _state.value.copy(mode = mode)
        }

        override fun onSleepChanged(sleep: Int) {
            refreshSleep()
        }

        override fun onAudioInfoChanged() {
            refresh()
        }

        override fun onTrackReady(track: MusicTrack?) {
            refresh()
        }

        override fun onLyricReady(track: MusicTrack?) {
            lyricReadyKey = track?.let { MusicLyricCache.key(it) }
            refreshLyrics()
        }

        override fun onCoverChanged(track: MusicTrack?) {
            refresh()
        }
    }

    @Volatile
    private var ticking = false

    private val tick = object : Runnable {
        override fun run() {
            var hasTrack = false
            try {
                val ctl = MusicController.get()
                pushProgress(ctl.position(), ctl.duration())
                refreshSleep()
                hasTrack = ctl.current() != null
            } catch (t: Throwable) {
                // 服务未起时静默，下一拍再试
            }
            // 没当前曲就停拍（不许空转耗电）：下次 refresh 见到曲目再 startTick 拉起
            if (hasTrack) {
                mainHandler.postDelayed(this, TICK_MS)
            } else {
                ticking = false
            }
        }
    }

    private fun startTick() {
        if (ticking) return
        ticking = true
        mainHandler.removeCallbacks(tick)
        mainHandler.post(tick)
    }

    /** 幂等挂接：播放条建条与播放页 onCreate 都调，先到者立中枢，后到者只订流。 */
    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        attached = true
        try {
            MusicController.get().addListener(listener)
        } catch (t: Throwable) {
            // 控制器未就绪时不崩，refresh 仍可拉到当下值
        }
        refresh()
        startTick()
    }

    // ---------------- 命令出口（全转发保留区） ----------------

    fun toggle() {
        runCatching { MusicController.get().toggle() }
    }

    fun next() {
        runCatching { if (MusicController.get().canNext()) MusicController.get().next() }
    }

    fun prev() {
        runCatching { MusicController.get().prev() }
    }

    fun seekTo(ms: Long) {
        runCatching { MusicController.get().seekTo(ms) }
    }

    fun playAt(index: Int) {
        runCatching { MusicController.get().playAt(index) }
    }

    fun cycleMode() {
        runCatching { MusicController.get().cycleMode() }
    }

    /** 播放模式直设（工具面板四段分段控件）：走控制器既有口，服务侧回 onModeChanged 对齐快照。 */
    fun setMode(mode: Int) {
        runCatching { MusicController.get().setMode(mode) }
    }

    /** 音质请求档直设：控制器 setQuality 已含写存档＋递服务按新档重取当前曲，面板只递值。 */
    fun setQuality(quality: Int) {
        runCatching { MusicController.get().setQuality(quality) }
        refresh()
    }

    /** 睡眠定时设档（档值口径见 MusicPlaybackService.setSleep：含 1000＋自定义分钟编码）。 */
    fun setSleep(sleep: Int) {
        runCatching { MusicController.get().setSleep(sleep) }
        refreshSleep()
    }

    /** 关「播完整首后再停止」且已在等待态时，清服务侧等待态（定时彻底失效，保留区 B4 口径）。 */
    fun clearSleepWaitingForSongEnd() {
        runCatching { MusicController.get().clearSleepWaitingForSongEnd() }
        refreshSleep()
    }

    /** 清空队列（队列面板）：走控制器既有口，清后服务回 onQueueChanged 对齐快照。 */
    fun clearQueue() {
        runCatching { MusicController.get().clearQueue() }
    }

    /** 系统音效面板用音频会话 id（均衡器瓷砖）；服务未起回 0，调用方据此提示。 */
    fun audioSessionId(): Int {
        return runCatching { MusicController.get().audioSessionId() }.getOrDefault(0)
    }

    /**
     * 收藏切换（主题件的爱心/收藏键/唱片架点槽共用）：走保留区 MusicLibrary.setFavorite
     * 真落盘，成功回调里再对齐当前曲的收藏旗与快照（收藏显示源仍是既有口径
     * track.favoriteAddedTime，本中枢的当前曲实例与服务队列同引用，对齐后 refresh 不回滚）。
     */
    fun toggleFavorite() {
        val track = _state.value.track ?: return
        val target = !_state.value.favorite
        runCatching {
            MusicLibrary.setFavorite(track, target) {
                track.favoriteAddedTime = if (target) System.currentTimeMillis() else 0L
                _state.value = _state.value.copy(favorite = target)
            }
        }
    }

    /**
     * 版本面板点选生效（照旧面板同链：存手动选择→写歌词缓存→本中枢重读上屏；
     * 存档由 MusicRepository.saveManualLyricChoice 管，下次同曲自动恢复）。
     */
    fun applyLyricVersion(version: LyricVersion) {
        val track = _state.value.track ?: return
        runCatching {
            MusicRepository.saveManualLyricChoice(track, version)
            MusicLyricCache.put(track, version.lyric)
            lyricReadyKey = MusicLyricCache.key(track)
            refreshLyrics()
        }
    }

    // ---------------- 状态汇聚 ----------------

    private fun pushProgress(position: Long, duration: Long) {
        val s = _state.value
        val idx = computeLyricIndex(s.lyrics, position)
        if (s.positionMs == position && s.durationMs == duration && s.lyricIndex == idx) return
        _state.value = s.copy(positionMs = position, durationMs = duration, lyricIndex = idx)
    }

    private fun refresh() {
        val ctl = runCatching { MusicController.get() }.getOrNull() ?: return
        val track = runCatching { ctl.current() }.getOrNull()
        val prev = _state.value
        val coverUrl = track?.cover?.takeIf { it.isNotEmpty() }
        val sameCover = coverUrl != null && coverUrl == prev.coverUrl
        val queue = runCatching { ctl.queue() }.getOrNull()?.toList() ?: emptyList()
        _state.value = prev.copy(
            track = track,
            title = track?.displayTitle().orEmpty(),
            artist = track?.displayArtist().orEmpty(),
            album = track?.displayAlbum().orEmpty(),
            coverUrl = coverUrl,
            coverBitmap = if (sameCover) prev.coverBitmap else null,
            coverBitmapUrl = if (sameCover) prev.coverBitmapUrl else null,
            dominantColor = if (sameCover) prev.dominantColor else PlayerUiState.FALLBACK_COLOR,
            isPlaying = runCatching { ctl.isPlaying() }.getOrDefault(prev.isPlaying),
            queue = queue,
            queueIndex = runCatching { ctl.index() }.getOrDefault(-1),
            queueSource = runCatching { ctl.queueSource() }.getOrNull()?.takeIf { it.isNotEmpty() },
            queueRoute = runCatching { ctl.queueRoute() }.getOrNull()?.takeIf { it.isNotEmpty() },
            mode = runCatching { ctl.getMode() }.getOrDefault(prev.mode),
            quality = runCatching { ctl.getQuality() }.getOrDefault(prev.quality),
            sleepMode = runCatching { ctl.getSleep() }.getOrDefault(prev.sleepMode),
            sleepRemainingMs = runCatching { ctl.sleepRemainingMs() }.getOrDefault(prev.sleepRemainingMs),
            sleepWaiting = runCatching { ctl.isSleepWaitingForSongEnd() }.getOrDefault(prev.sleepWaiting),
            sleepFinishCurrent = runCatching { MusicStore.isSleepFinishCurrent() }.getOrDefault(prev.sleepFinishCurrent),
            canNext = runCatching { ctl.canNext() }.getOrDefault(false),
            canPrev = prev.queueIndex > 0,
            favorite = (track?.favoriteAddedTime ?: 0L) > 0L,
            durationMs = runCatching { ctl.duration() }.getOrDefault(prev.durationMs),
            positionMs = runCatching { ctl.position() }.getOrDefault(prev.positionMs),
        )
        if (!sameCover) {
            coverSeq++
            if (coverUrl != null) loadCover(coverUrl, coverSeq)
        }
        if (track != null) startTick()
        refreshLyrics()
    }

    /** 睡眠定时四值对齐（档位/剩余/等待态/播完再停开关）：值不变不写快照，免得面板开着时无谓重组。 */
    private fun refreshSleep() {
        val ctl = runCatching { MusicController.get() }.getOrNull() ?: return
        val s = _state.value
        val mode = runCatching { ctl.getSleep() }.getOrDefault(s.sleepMode)
        val remaining = runCatching { ctl.sleepRemainingMs() }.getOrDefault(s.sleepRemainingMs)
        val waiting = runCatching { ctl.isSleepWaitingForSongEnd() }.getOrDefault(s.sleepWaiting)
        val finish = runCatching { MusicStore.isSleepFinishCurrent() }.getOrDefault(s.sleepFinishCurrent)
        if (s.sleepMode == mode && s.sleepRemainingMs == remaining &&
            s.sleepWaiting == waiting && s.sleepFinishCurrent == finish
        ) {
            return
        }
        _state.value = s.copy(
            sleepMode = mode,
            sleepRemainingMs = remaining,
            sleepWaiting = waiting,
            sleepFinishCurrent = finish,
        )
    }

    private fun loadCover(url: String, seq: Int) {
        try {
            ImgUtil.load(url, object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (seq != coverSeq) return
                    colorExecutor.execute {
                        val color = runCatching { CoverColor.shellBaseColor(resource) }
                            .getOrDefault(PlayerUiState.FALLBACK_COLOR)
                        mainHandler.post {
                            if (seq != coverSeq) return@post
                            val s = _state.value
                            if (s.coverUrl != url) return@post
                            _state.value = s.copy(
                                coverBitmap = resource,
                                coverBitmapUrl = url,
                                dominantColor = color,
                            )
                        }
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // 有址失败：保持透明（他的封面占位令），色留回落值，不上素图
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
        } catch (t: Throwable) {
            // 取图通路异常不许带崩中枢
        }
    }

    private fun refreshLyrics() {
        val s = _state.value
        val track = s.track
        val next: LyricUiState = when {
            track == null -> LyricUiState.Idle
            else -> {
                val lyric = runCatching { MusicLyricCache.get(track) }.getOrNull()
                if (lyric == null || !lyric.hasLyric()) {
                    if (lyricReadyKey == runCatching { MusicLyricCache.key(track) }.getOrNull()) {
                        LyricUiState.None
                    } else {
                        LyricUiState.Searching
                    }
                } else {
                    val parsed = runCatching {
                        LyricsParser.parseWordLyric(lyric.rawLrc ?: "")
                    }.getOrDefault(emptyList())
                    // 译/音译按时间最近邻对齐挂行（RichLyrics.align，与通知卡同一口径，行数恒等于正文行数）
                    val trans = lyric.translation?.takeIf { it.isNotEmpty() }?.let {
                        runCatching { LyricsParser.parseTimed(it) }.getOrNull()
                    }
                    val roma = lyric.romanization?.takeIf { it.isNotEmpty() }?.let {
                        runCatching { LyricsParser.parseTimed(it) }.getOrNull()
                    }
                    val aligned = runCatching { RichLyrics.align(parsed, trans, roma) }
                        .getOrDefault(emptyList())
                    val lines = aligned.map { rich ->
                        val m = rich.main
                        LyricUiLine(
                            timeMs = m.timeMs,
                            text = m.text.orEmpty(),
                            words = m.words.map { w ->
                                val start = m.timeMs + w.startOffsetMs
                                LyricUiWord(
                                    startMs = start,
                                    endMs = start + w.durationMs,
                                    text = w.text.orEmpty(),
                                )
                            },
                            translation = rich.translation?.takeIf { it.isNotEmpty() },
                            romanization = rich.romanization?.takeIf { it.isNotEmpty() },
                        )
                    }
                    LyricUiState.Content(
                        lines = lines,
                        timed = lines.any { it.timeMs > 0 },
                        wordLevel = parsed.any { it.hasWords() },
                        format = lyric.format?.takeIf { it.isNotEmpty() },
                    )
                }
            }
        }
        if (next != s.lyrics) {
            _state.value = _state.value.copy(
                lyrics = next,
                lyricIndex = computeLyricIndex(next, _state.value.positionMs),
            )
        }
    }

    /** 当前行＝时间 ≤ 当前位置的最后一行；无时间行/纯文本恒 -1（瓜子同口径：纯文本不滚动不均分）。 */
    private fun computeLyricIndex(lyrics: LyricUiState, positionMs: Long): Int {
        if (lyrics !is LyricUiState.Content || !lyrics.timed) return -1
        val lines = lyrics.lines
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
