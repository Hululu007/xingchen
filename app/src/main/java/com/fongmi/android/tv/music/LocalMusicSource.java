package com.fongmi.android.tv.music;

import android.net.Uri;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * 本地音乐源：platform 固定 "local"。
 * 已下载歌曲登记为 platform="local" 的 MusicTrack，播放链（MusicRepository.playUrl →
 * MusicPlaybackService.prepareTrack）不改一行，直接走本地文件起播，封面/歌名/歌手
 * 等元信息沿用登记时存的字段。
 */
public class LocalMusicSource implements MusicSource {

    public static final String PLATFORM = "local";

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        return Collections.emptyList();
    }

    @Override
    public String playUrl(MusicTrack track, int quality) {
        if (track == null || track.localPath == null || track.localPath.isEmpty()) return null;
        File f = new File(track.localPath);
        if (!f.exists()) return null;
        return Uri.fromFile(f).toString();
    }

    /**
     * 已下载歌的本源词（播放链步 0 消费，此前恒回 null、下载时写下的词从不被读）：
     * ①同名旁路 .lrc／下载记录 lyric 列（MusicDownloadStore.readSidecarLyric，D 片 P5
     * 落的回读兜底，此前全树零调用——本方法就是它的播放侧消费端）；
     * ②音频内嵌词（内嵌档下载写进文件的 USLT／Vorbis／©lyr，EmbeddedLyricsReader 现读）。
     * 两路都没有才回 null 走跨源兜底链；断网时①②都在本地，词不丢。
     */
    @Override
    public String lyric(MusicTrack track) {
        if (track == null) return null;
        try {
            String sidecar = MusicDownloadStore.readSidecarLyric(track);
            if (sidecar != null && !sidecar.trim().isEmpty()) return sidecar;
        } catch (Throwable ignored) {
        }
        if (track.localPath != null && !track.localPath.isEmpty()) {
            try {
                String embedded = EmbeddedLyricsReader.read(new File(track.localPath));
                if (embedded != null && !embedded.trim().isEmpty()) return embedded;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
