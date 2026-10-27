package com.fongmi.android.tv.ui.music.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.request.transition.Transition
import com.fongmi.android.tv.bean.music.MusicAudioInfo
import com.fongmi.android.tv.bean.music.MusicTrack
import com.fongmi.android.tv.impl.CustomTarget
import com.fongmi.android.tv.music.MusicController
import com.fongmi.android.tv.music.MusicLibrary
import com.fongmi.android.tv.utils.CoverColor
import com.fongmi.android.tv.utils.ImgUtil
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放器共享状态中枢（瓜子组织法里 VM 的位置）：订 MusicController.Listener，
 * 把散在服务/仓库里的状态汇聚成一份 PlayerUiState 快照分发给条与各主题页。
 * 中枢只镜像、不存第二份真源；命令面（toggle/next/seek…）只转发控制器。
 *
 * 封面与外壳底色照瓜子在状态层算一次：ImgUtil 取图 → CoverColor.shellBaseColor
 * （mk1 链 a 分量）在单线程后台算完，与 url 成对发布；切歌先清旧图防挂旧脸。
 */
object PlayerStateHolder {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress

    @Volatile
    private var attached = false

    private val colorExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PlayerCoverColor").apply { isDaemon = true }
    }

    /** 封面加载代次：切歌后旧图晚到直接丢弃，不许盖新歌的脸。 */
    @Volatile
    private var coverSeq = 0

    private val controllerListener = object : MusicController.Listener {
        override fun onTrackChanged(track: MusicTrack?) {
            refreshAll()
        }

        override fun onPlayingChanged(playing: Boolean) {
            _state.update { it.copy(isPlaying = playing) }
        }

        override fun onProgress(position: Long, duration: Long) {
            _progress.value = PlayerProgress(position, duration)
        }

        override fun onQueueChanged() {
            val ctl = MusicController.get()
            _state.update {
                it.copy(queue = ctl.queue(), queueIndex = ctl.index(), canNext = ctl.canNext())
            }
        }

        override fun onModeChanged(mode: Int) {
            _state.update { it.copy(playMode = mode) }
        }

        override fun onAudioInfoChanged(info: MusicAudioInfo?) {
            _state.update { it.copy(audioLabel = info?.describe()) }
        }

        override fun onCoverChanged(track: MusicTrack?) {
            val current = _state.value.track
            if (track != null && current != null && track.id == current.id) {
                loadCover(track.cover)
            }
        }

        override fun onTrackReady(track: MusicTrack?) {
            val ctl = MusicController.get()
            _state.update { it.copy(audioLabel = ctl.audioInfo()?.describe()) }
            _progress.value = PlayerProgress(ctl.position(), ctl.duration())
        }
    }

    private val favoritesListener = MusicLibrary.FavoritesListener {
        refreshFavorite(_state.value.track)
    }

    /** 幂等挂接：条（ListenMiniBar 建条时）与播放页 onCreate 都会调，只生效一次。 */
    fun attach(context: Context) {
        if (attached) return
        synchronized(this) {
            if (attached) return
            attached = true
        }
        MusicController.get().addListener(controllerListener)
        MusicLibrary.addFavoritesListener(favoritesListener)
        refreshAll()
    }

    /** 全量现拉（挂接、切歌时）：所有字段只从控制器/服务现读，不留本地副本。 */
    private fun refreshAll() {
        val ctl = MusicController.get()
        val track = ctl.current()
        _state.update {
            it.copy(
                track = track,
                isPlaying = ctl.isPlaying(),
                queue = ctl.queue(),
                queueIndex = ctl.index(),
                playMode = ctl.getMode(),
                qualityTier = ctl.getQuality(),
                audioLabel = ctl.audioInfo()?.describe(),
                canNext = ctl.canNext()
            )
        }
        _progress.value = PlayerProgress(ctl.position(), ctl.duration())
        loadCover(track?.cover)
        refreshFavorite(track)
    }

    private fun loadCover(url: String?) {
        if (url == _state.value.coverUrl) return
        val seq = ++coverSeq
        if (url.isNullOrEmpty()) {
            _state.update {
                it.copy(
                    coverUrl = null, coverBitmap = null, ambientBitmap = null,
                    baseColor = CoverColor.FALLBACK_COLOR
                )
            }
            return
        }
        // 先记新址、清旧图、底色回落：切歌瞬间不许挂上一首的脸（瓜子切歌挂旧脸的 bug 不学）
        _state.update {
            it.copy(
                coverUrl = url, coverBitmap = null, ambientBitmap = null,
                baseColor = CoverColor.FALLBACK_COLOR
            )
        }
        ImgUtil.load(url, object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                colorExecutor.execute {
                    val color = try {
                        CoverColor.shellBaseColor(resource)
                    } catch (t: Throwable) {
                        CoverColor.FALLBACK_COLOR
                    }
                    val ambient = try {
                        Bitmap.createScaledBitmap(resource, 64, 64, true)
                    } catch (t: Throwable) {
                        null
                    }
                    if (seq == coverSeq && url == _state.value.coverUrl) {
                        _state.update {
                            it.copy(coverBitmap = resource, ambientBitmap = ambient, baseColor = color)
                        }
                    }
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) {
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                // 加载失败保持无图（有地址不垫素图），底色维持回落
            }
        })
    }

    private fun refreshFavorite(track: MusicTrack?) {
        if (track == null) {
            _state.update { it.copy(isFavorite = false) }
            return
        }
        MusicLibrary.isFavorite(track, MusicLibrary.DataCallback<Boolean> { fav ->
            val current = _state.value.track
            if (current != null && (current === track || current.id == track.id)) {
                _state.update { it.copy(isFavorite = fav == true) }
            }
        })
    }

    // ================= 命令面（只转发控制器/曲库，不自存状态） =================

    fun toggle() = MusicController.get().toggle()

    fun next() = MusicController.get().next()

    fun prev() = MusicController.get().prev()

    fun playAt(index: Int) = MusicController.get().playAt(index)

    fun seekTo(positionMs: Long) {
        MusicController.get().seekTo(positionMs)
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun cycleMode() = MusicController.get().cycleMode()

    fun setMode(mode: Int) = MusicController.get().setMode(mode)

    fun setQuality(tier: Int) {
        MusicController.get().setQuality(tier)
        _state.update { it.copy(qualityTier = tier) }
    }

    fun toggleFavorite() {
        val track = _state.value.track ?: return
        val target = !_state.value.isFavorite
        _state.update { it.copy(isFavorite = target) }
        MusicLibrary.setFavorite(track, target, Runnable { })
    }
}
