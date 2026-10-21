package com.fongmi.android.tv.music;

import android.net.Uri;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

/**
 * 设备本地音乐源：platform 固定 "device"（跟已下载的 "local" 分开，不许混）。
 * 列表不在 sites 里（MediaStore 扫描归 DeviceMusicStore + 本地音乐页），
 * 本类只供播放取链（content URI）与同目录同名 .lrc 歌词；拿不到歌词返回 null，
 * 走 MusicRepository 现有兜底找词链。
 */
public class DeviceMusicSource implements MusicSource {

    public static final String PLATFORM = "device";

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
        if (track == null) return null;
        String uri = str(raw(track), "uri");
        if (uri != null && !uri.isEmpty()) return uri;
        if (track.localPath != null && !track.localPath.isEmpty()) {
            File f = new File(track.localPath);
            if (f.exists()) return Uri.fromFile(f).toString();
        }
        return null;
    }

    /** 同目录同名 .lrc 优先；没有或为空再读内嵌歌词（GZ SIDECAR_FIRST 默认口径），都没有返回 null 给兜底链 */
    @Override
    public String lyric(MusicTrack track) {
        if (track == null) return null;
        String path = track.localPath;
        if (path == null || path.isEmpty()) path = str(raw(track), "path");
        if (path == null || path.isEmpty()) return null;
        File audio = new File(path);
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = audio.getParentFile();
        if (parent != null) {
            File lrc = new File(parent, base + ".lrc");
            if (lrc.isFile()) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    try (FileInputStream in = new FileInputStream(lrc)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    }
                    byte[] bytes = bos.toByteArray();
                    String text = new String(bytes, Charset.forName("UTF-8"));
                    if (text.contains("�")) text = new String(bytes, Charset.forName("GB18030"));
                    if (!text.trim().isEmpty()) return text;
                } catch (Throwable ignored) {
                }
            }
        }
        // 内嵌歌词（ID3 USLT/SYLT、FLAC Vorbis、MP4 ©lyr，播放时现读；SYLT 已转 LRC，USLT 等无时间轴文本走静态显示）
        try {
            String embedded = com.fongmi.android.tv.music.EmbeddedLyricsReader.read(audio);
            if (embedded != null && !embedded.trim().isEmpty()) return embedded;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JsonObject raw(MusicTrack track) {
        try {
            if (track == null || track.rawJson == null || track.rawJson.isEmpty()) return new JsonObject();
            return JsonParser.parseString(track.rawJson).getAsJsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
