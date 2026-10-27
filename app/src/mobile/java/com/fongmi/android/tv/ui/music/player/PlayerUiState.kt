package com.fongmi.android.tv.ui.music.player

import android.graphics.Bitmap
import com.fongmi.android.tv.bean.music.MusicTrack
import com.fongmi.android.tv.music.MusicQueue
import com.fongmi.android.tv.music.RichLyrics
import com.fongmi.android.tv.utils.CoverColor

/**
 * 播放器一份状态快照（瓜子渲染组织法：一棵树一份状态，见 ~/workspace/gz-render-tree-map.md）。
 * 数据真源永远是 MusicController / MusicPlaybackService，本快照只是它们的汇聚镜像，
 * 不建第二套数据层：条、经典页、后续主题与面板都只 collect 这一份。
 *
 * 慢变量（切歌/播放态/队列/模式/音质/收藏/封面与底色）走 state；
 * 快变量（进度，服务 250ms 推一次）单独走 progress，避免整树被进度拖着重组。
 */
data class PlayerUiState(
    val track: MusicTrack? = null,
    val isPlaying: Boolean = false,
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val playMode: Int = MusicQueue.MODE_SEQUENTIAL,
    /** 请求档位（MusicStore 档）：只是请求上限，永不直接进显示。 */
    val qualityTier: Int = 0,
    /** 实测音质显示文（MusicAudioInfo.describe()：无损只给编码名、有损给编码＋真实码率）；测不到为 null，不垫场。 */
    val audioLabel: String? = null,
    val isFavorite: Boolean = false,
    val canNext: Boolean = false,
    /** 当前封面位图：与 coverUrl 成对发布，url 对不上不画，防挂上一首的脸。 */
    val coverBitmap: Bitmap? = null,
    /** 前后曲封面位图（唱片架三槽快照用；按队列线性 ±1、无则 null 不垫图）。 */
    val prevCoverBitmap: Bitmap? = null,
    val nextCoverBitmap: Bitmap? = null,
    /** 氛围层小图：同一封面的 64×64 缩图（瓜子 BlurredCoverAmbient 的取图口径），与 coverBitmap 同批发布。 */
    val ambientBitmap: Bitmap? = null,
    val coverUrl: String? = null,
    /** 外壳底色：CoverColor.shellBaseColor（瓜子 CoverAmbientKt 的 mk1 链 a 分量），在中枢算一次全场共用。 */
    val baseColor: Int = CoverColor.FALLBACK_COLOR,
    /**
     * 封面 tone 色：CoverColor.cdToneColor→toneAdjust（瓜子 o()＋d() 链），与外壳底色同批算；
     * 流光 CD 色板与唱片架 h0 的封面色入参。无封面/未就绪为 null，主题侧各自回落
     * （CD 回落 palette.base、唱片架 h0 回落 Ink，瓜子各自回落口径）。
     */
    val toneColor: Int? = null,
    /**
     * 当前曲歌词（现行管线同源配方：MusicLyricCache → LyricsParser.parseWordLyric/parseTimed
     * → RichLyrics.align，与通知卡同一口）；空表＝暂无歌词。切歌先清、备好后整表换上。
     */
    val lyricLines: List<RichLyrics.Line> = emptyList(),
    /** 纯文本无时间轴（LyricsParser.isUntimed）：无当前行、静态显示。 */
    val lyricUntimed: Boolean = false
)

/** 进度快照（快变量）：服务 ticker 250ms 一推。 */
data class PlayerProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0
)
