package com.fongmi.android.tv.music;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;

import com.fongmi.android.tv.bean.music.MusicTrack;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设备本地音乐扫描：MediaStore（手机里的音频）＋ App 专属目录 music_library/
 * （加密导入转完的标准音频落这里）。阻塞调用，请在子线程调。
 * <p>
 * 注意：platform="device" 跟已下载的 "local" 分开（各管各的列表，不许混）。
 */
public class DeviceMusicStore {

    /** music_library 里认的音频后缀（加密后缀不在此列，导入时转完再说） */
    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "ape", "wv", "aiff"));

    /** App 专属导入目录：加密歌曲解密后输出标准音频落这儿 */
    public static File libraryDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        return new File(base, "music_library");
    }

    /** 合并扫描：MediaStore 歌曲 ＋ music_library 目录文件 */
    public static List<MusicTrack> scan(Context ctx) {
        List<MusicTrack> out = new ArrayList<>();
        scanMediaStore(ctx, out);
        scanLibraryDir(ctx, out);
        return out;
    }

    private static void scanMediaStore(Context ctx, List<MusicTrack> out) {
        ContentResolver cr = ctx.getContentResolver();
        Uri ext = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DATA
        };
        try (Cursor c = cr.query(ext, proj, MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                MediaStore.Audio.Media.TITLE + " ASC")) {
            if (c == null) return;
            int iId = c.getColumnIndex(MediaStore.Audio.Media._ID);
            int iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int iAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
            int iData = c.getColumnIndex(MediaStore.Audio.Media.DATA);
            while (c.moveToNext()) {
                try {
                    long id = c.getLong(iId);
                    MusicTrack t = new MusicTrack();
                    t.id = DeviceMusicSource.PLATFORM + ":" + id;
                    t.title = c.getString(iTitle);
                    t.artist = c.getString(iArtist);
                    t.album = c.getString(iAlbum);
                    t.duration = c.getLong(iDur);
                    t.platform = DeviceMusicSource.PLATFORM;
                    long albumId = c.getLong(iAlbumId);
                    if (albumId > 0) {
                        t.cover = ContentUris.withAppendedId(
                                Uri.parse("content://media/external/audio/albumart"), albumId).toString();
                    }
                    String data = iData >= 0 ? c.getString(iData) : null;
                    t.localPath = data == null ? "" : data;
                    JsonObject raw = new JsonObject();
                    raw.addProperty("uri", ContentUris.withAppendedId(ext, id).toString());
                    if (!t.localPath.isEmpty()) raw.addProperty("path", t.localPath);
                    t.rawJson = raw.toString();
                    out.add(t);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            SpiderDebug.log("music", "mediastore scan error %s", t.getMessage());
        }
    }

    private static void scanLibraryDir(Context ctx, List<MusicTrack> out) {
        File dir = libraryDir(ctx);
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !isAudio(f.getName())) continue;
            try {
                MusicTrack t = new MusicTrack();
                t.id = DeviceMusicSource.PLATFORM + ":lib:" + f.getName();
                t.title = stripExt(f.getName());
                t.platform = DeviceMusicSource.PLATFORM;
                t.localPath = f.getAbsolutePath();
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (title != null && !title.trim().isEmpty()) t.title = title.trim();
                    if (artist != null && !artist.trim().isEmpty()) t.artist = artist.trim();
                    if (album != null && !album.trim().isEmpty()) t.album = album.trim();
                    if (dur != null) {
                        try {
                            t.duration = Long.parseLong(dur.trim());
                        } catch (Throwable ignored) {
                        }
                    }
                } finally {
                    try {
                        r.release();
                    } catch (Throwable ignored) {
                    }
                }
                JsonObject raw = new JsonObject();
                raw.addProperty("uri", Uri.fromFile(f).toString());
                raw.addProperty("path", f.getAbsolutePath());
                t.rawJson = raw.toString();
                out.add(t);
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isAudio(String name) {
        int p = name == null ? -1 : name.lastIndexOf('.');
        if (p < 0) return false;
        return AUDIO_EXT.contains(name.substring(p + 1).toLowerCase());
    }

    private static String stripExt(String name) {
        int p = name.lastIndexOf('.');
        return p > 0 ? name.substring(0, p) : name;
    }
}
