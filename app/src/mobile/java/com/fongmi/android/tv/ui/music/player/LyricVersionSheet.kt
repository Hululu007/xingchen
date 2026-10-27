package com.fongmi.android.tv.ui.music.player

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fongmi.android.tv.R
import com.fongmi.android.tv.api.music.CfLyricClient
import com.fongmi.android.tv.bean.music.LyricVersion
import com.fongmi.android.tv.music.GzLyricMatch
import com.fongmi.android.tv.music.LyricVersionsCache
import com.fongmi.android.tv.music.MusicLyricCache
import com.fongmi.android.tv.music.MusicRepository
import com.fongmi.android.tv.player.lyrics.LyricsParser

/**
 * 歌词版本面板（同树 sheet，照瓜子 PlayerVersionSheet 口径＋本项目旧面板已验的取数/合并逻辑）：
 * 数据走保留区 MusicRepository.lyricVersions（阶段一 Worker 快照→阶段二全量聚合，
 * LyricVersionsCache 管缓存/在飞合并/总时限）；打开先上在播词条种子（displayedVersion），
 * 聚合回来按同文去重合并（有同文以聚合版为准、种子不重复列）。
 * 行内容：来源、行数、候选歌名·歌手、档位/翻译/音译/偏差标签、匹配度（有瓜子定级才显，不冒充）。
 * 点选经 PlayerStateHolder.applyLyricVersion 生效并记住（存手动选择＋写缓存），不是开关翻译。
 */

// 面板令牌统一走 PlayerSheets.kt 的共享声明（同包同名私有声明会与之冲突，值全等）

private val FILTERS = listOf("全部", "逐字", "逐行", "翻译", "音译", "文本")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricVersionSheet(
    state: PlayerUiState,
    onPick: (LyricVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val track = state.track
    val seed = remember(track) {
        runCatching {
            MusicRepository.displayedVersion(
                MusicLyricCache.get(track),
                MusicRepository.sourceDisplayName(track?.platform),
            )
        }.getOrNull()
    }
    val currentRaw = remember(track) {
        runCatching { MusicLyricCache.get(track)?.rawLrc }.getOrNull()
    }
    var versions by remember(track) { mutableStateOf<List<LyricVersion>>(emptyList()) }
    var settled by remember(track) { mutableStateOf(false) }
    var workerEmpty by remember(track) { mutableStateOf(false) }
    var partialBest by remember(track) { mutableStateOf<List<LyricVersion>?>(null) }
    var filter by remember(track) { mutableIntStateOf(0) }

    fun merge(incoming: List<LyricVersion>): List<LyricVersion> {
        val s = seed ?: return incoming
        val seedRaw = s.lyric?.rawLrc ?: return incoming
        val hasSame = incoming.any { it.lyric?.rawLrc?.trim() == seedRaw.trim() }
        return if (hasSame) incoming else listOf(s) + incoming
    }

    DisposableEffect(track) {
        if (track == null) {
            versions = emptyList()
            settled = true
            return@DisposableEffect onDispose { }
        }
        if (seed != null) versions = listOf(seed)
        val mainHandler = Handler(Looper.getMainLooper())
        var disposed = false
        val callback = object : LyricVersionsCache.Callback {
            override fun onPartial(vs: List<LyricVersion>) {
                mainHandler.post {
                    if (disposed) return@post
                    if (vs.isNotEmpty()) {
                        partialBest = vs
                        versions = merge(vs)
                    } else {
                        workerEmpty = true
                    }
                }
            }

            override fun onSettled(vs: List<LyricVersion>, complete: Boolean) {
                mainHandler.post {
                    if (disposed) return@post
                    val first = partialBest
                    val use = if (first != null && vs.size < first.size) first else vs
                    versions = merge(use)
                    settled = true
                }
            }
        }
        val thread = Thread {
            runCatching { MusicRepository.lyricVersions(track, false, callback) }
        }
        thread.start()
        onDispose { disposed = true }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            // 头：标题＋关闭（瓜子 PlayerVersionSheet 同构）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "歌词版本",
                    color = TextHigh,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "×", color = TextMedium, fontSize = 18.sp)
                }
            }

            // 歌曲行＋状态行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF8E8E93).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = state.coverBitmap
                    if (bmp != null && state.coverBitmapUrl == state.coverUrl) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title.ifEmpty { "未在播放" },
                        color = TextHigh,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.artist.isNotEmpty()) {
                        Text(
                            text = state.artist,
                            color = TextMedium,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = statusText(versions.size, versions.count { it.hasGzScore() }, settled, workerEmpty),
                        color = TextLow,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 筛选条（全部/逐字/逐行/翻译/音译/文本，瓜子 LyricVersionFilter 同名目）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SheetInset)
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FILTERS.forEachIndexed { index, label ->
                    val selected = filter == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) AccentRed.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { filter = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (selected) TextHigh else TextMedium,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val shown = versions.filter { passesFilter(it, filter) }
            when {
                shown.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 430.dp)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        items(shown) { version ->
                            VersionCard(
                                version = version,
                                current = isCurrent(version, currentRaw),
                                fallbackTitle = state.title,
                                fallbackArtist = state.artist,
                                onClick = { onPick(version) },
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
                versions.isNotEmpty() -> {
                    EmptyHint(text = "暂无${FILTERS[filter]}版本，可切换全部查看")
                }
                settled -> {
                    EmptyHint(text = emptyText())
                }
                else -> {
                    EmptyHint(text = "正在搜索歌词候选")
                }
            }
        }
    }
}

@Composable
private fun VersionCard(
    version: LyricVersion,
    current: Boolean,
    fallbackTitle: String,
    fallbackArtist: String,
    onClick: () -> Unit,
) {
    val lineCount = remember(version) {
        runCatching {
            val raw = version.lyric?.rawLrc
            if (raw.isNullOrEmpty()) 0 else LyricsParser.parseTimed(raw).size
        }.getOrDefault(0)
    }
    val candidateTitle = version.candidateTitle?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    val candidateArtist = version.candidateArtist?.takeIf { it.isNotEmpty() }
        ?: fallbackArtist.takeIf { version.candidateTitle.isNullOrEmpty() }.orEmpty()
    val bg = if (current) {
        Modifier.background(
            Brush.verticalGradient(
                listOf(AccentRed.copy(alpha = 0.12f), AccentRed.copy(alpha = 0.04f)),
            ),
        )
    } else {
        Modifier.background(SheetInset)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (current) AccentRed.copy(alpha = 0.13f) else SheetListBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = if (current) AccentRed else TextHigh,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version.source?.takeIf { it.isNotEmpty() } ?: "歌词",
                    color = TextHigh,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lineCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "$lineCount 行", color = TextLow, fontSize = 10.sp)
                }
            }
            val candidate = if (candidateArtist.isEmpty()) {
                candidateTitle
            } else {
                "$candidateTitle · $candidateArtist"
            }
            if (candidate.isNotEmpty()) {
                Text(
                    text = candidate,
                    color = TextLow,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                VersionTag(text = version.kindLabel(), accent = version.isWordTimed)
                val lyric = version.lyric
                if (!lyric?.translation.isNullOrEmpty()) VersionTag(text = "翻译", accent = false)
                if (!lyric?.romanization.isNullOrEmpty()) VersionTag(text = "音译", accent = false)
                if (version.durationDeltaSec > 0) VersionTag(text = "偏差 ${version.durationDeltaSec}s", accent = false)
            }
        }
        if (version.hasGzScore()) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${GzLyricMatch.percentForLevel(version.gzLevel)}%",
                    color = if (current) AccentRed else TextHigh,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = "匹配度", color = TextLow, fontSize = 9.sp)
                if (current) {
                    Text(text = "在用", color = AccentRed, fontSize = 9.sp)
                }
            }
        } else if (current) {
            Text(text = "在用", color = AccentRed, fontSize = 10.sp)
        }
    }
}

@Composable
private fun VersionTag(text: String, accent: Boolean) {
    Box(
        modifier = Modifier
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) AccentRed.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = if (accent) AccentRed else Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = TextLow, fontSize = 13.sp)
    }
}

private fun statusText(count: Int, graded: Int, settled: Boolean, workerEmpty: Boolean): String = when {
    settled -> "$count 个版本" + if (graded > 0) " · $graded 个有匹配度" else " · 暂无匹配度"
    count > 0 -> "已找到 $count 个版本 · 继续搜索中"
    workerEmpty -> "主来源没回来，正在从其它来源找"
    else -> "正在搜索歌词候选"
}

private fun isCurrent(version: LyricVersion, currentRaw: String?): Boolean {
    val raw = version.lyric?.rawLrc ?: return false
    if (currentRaw == null) return false
    return currentRaw.trim() == raw.trim()
}

private fun passesFilter(version: LyricVersion, filter: Int): Boolean {
    val lyric = version.lyric
    return when (filter) {
        1 -> version.isWordTimed
        2 -> version.isLineTimed && !version.isWordTimed
        3 -> !lyric?.translation.isNullOrEmpty()
        4 -> !lyric?.romanization.isNullOrEmpty()
        5 -> !version.isWordTimed && !version.isLineTimed
        else -> true
    }
}

/** 全量收口后仍为空：带主来源结局（照旧面板口径，不只剩一句无信息的「没有找到」）。 */
private fun emptyText(): String {
    val note = runCatching { MusicRepository.lastCfNote() }.getOrNull()
    return when (note) {
        CfLyricClient.NOTE_TRANSPORT -> "没有找到可用歌词（主来源连接失败）"
        CfLyricClient.NOTE_NON_JSON -> "没有找到可用歌词（主来源应答异常）"
        CfLyricClient.NOTE_OK -> "没有找到可用歌词（主来源暂无此歌版本）"
        else -> "没有找到可用歌词"
    }
}
