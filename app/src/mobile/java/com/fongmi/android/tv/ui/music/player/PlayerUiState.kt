package com.fongmi.android.tv.ui.music.player

import android.graphics.Bitmap
import com.fongmi.android.tv.bean.music.MusicTrack

/** 歌词一字（绝对时刻：行首＋词相对偏移已在状态层算好，引擎直接吃）。 */
data class LyricUiWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * 歌词一行（引擎与各歌词面共用同一份行模型）：时间＋正文＋逐字＋译/音译整行。
 * 译/音译由状态层经 RichLyrics.align 按时间最近邻挂上（与通知卡同一对齐口径）。
 */
data class LyricUiLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricUiWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
)

/** 歌词状态（照瓜子 LyricsState 的密封形态分档：未起／找词中／确无词／有词）。 */
sealed interface LyricUiState {
    /** 还没起播、无当前曲。 */
    data object Idle : LyricUiState

    /** 兜底链还在找词（缓存未落、就绪信号未到）。 */
    data object Searching : LyricUiState

    /** 找过且确无词。 */
    data object None : LyricUiState

    /** 有词：timed＝行带时间戳；wordLevel＝任一行带逐字；format＝来源档位名（如 AMLL），可空。 */
    data class Content(
        val lines: List<LyricUiLine>,
        val timed: Boolean,
        val wordLevel: Boolean,
        val format: String?,
    ) : LyricUiState
}

/**
 * 播放界面唯一快照（照瓜子 PlayerThemeContext 的组织法）：状态中枢算一次，
 * 播放条与各主题只吃这一份＋回调，谁也不许自己再去问服务、取图、算色。
 * coverBitmap 与 coverBitmapUrl 成对：只有 url 对得上当前曲封面时才许画，
 * 这是「切歌不挂旧脸」的结构性保证（瓜子的 bug 不学，他 2026-10-24 点名）。
 */
data class PlayerUiState(
    val track: MusicTrack? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
    val coverBitmap: Bitmap? = null,
    val coverBitmapUrl: String? = null,
    /** 封面主色（状态层经 CoverColor 的 mk1 链算一次）；无封面回落瓜子同值 #FF1C1C1E。 */
    val dominantColor: Int = FALLBACK_COLOR,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val mode: Int = 0,
    val quality: Int = 0,
    val canNext: Boolean = false,
    val canPrev: Boolean = false,
    val favorite: Boolean = false,
    val lyrics: LyricUiState = LyricUiState.Idle,
    /** 当前歌词行下标（无当前行＝-1，纯文本恒 -1）；由中枢随进度推进独立维护。 */
    val lyricIndex: Int = -1,
) {
    companion object {
        const val FALLBACK_COLOR: Int = 0xFF1C1C1E.toInt()
    }
}
