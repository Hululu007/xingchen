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

    /**
     * 相册封面存在性判定（纯逻辑，可单测）：albumId 无效→无；
     * 相册表查到了（artAlbums 非 null）就以它为准——MediaStore 的 Albums.ALBUM_ART
     * 是媒体扫描器给「专辑确有封面」留的官方记录，表里没有就是真没封面，
     * 这种歌封面地址必须留空（走素图），不许拼个死地址占着「有地址」的名头；
     * 相册表查询失败（artAlbums 为 null）时回退旧口径（albumId 有效就算有）。
     */
    static boolean hasAlbumArt(long albumId, Set<Long> artAlbums) {
        if (albumId <= 0) return false;
        return artAlbums == null || artAlbums.contains(albumId);
    }

    /** 查相册表，收集确有封面的专辑 id；查询失败返回 null（上层回退旧口径）。 */
    private static Set<Long> queryArtAlbums(ContentResolver cr) {
        try (Cursor c = cr.query(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM_ART},
                null, null, null)) {
            if (c == null) return null;
            Set<Long> set = new HashSet<>();
            int iId = c.getColumnIndex(MediaStore.Audio.Albums._ID);
            int iArt = c.getColumnIndex(MediaStore.Audio.Albums.ALBUM_ART);
            while (c.moveToNext()) {
                String art = iArt >= 0 ? c.getString(iArt) : null;
                if (art != null && !art.isEmpty()) set.add(c.getLong(iId));
            }
            return set;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void scanMediaStore(Context ctx, List<MusicTrack> out) {
        ContentResolver cr = ctx.getContentResolver();
        Set<Long> artAlbums = queryArtAlbums(cr);
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
                    // 第 12 项：GZ 封面决议优先级（LocalMusicScanner.resolveCoverUri）——内嵌 ＞ 同目录旁路 ＞ 无；
                    // 星辰旧链只有专辑 art 一路：有内嵌图却无专辑 art 记录的歌就丢了封面（用户 2 首就是这类）。
                    // 此处内嵌／旁路优先，专辑 art 退为最后兜底（GZ 未用专辑 art 路，但星辰已有且有效，留作兜底不删）。
                    String albumArt = hasAlbumArt(albumId, artAlbums) ? ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), albumId).toString() : null;
                    String data = iData >= 0 ? c.getString(iData) : null;
                    t.cover = embeddedCover(ctx, id, data);
                    if (t.cover == null) t.cover = sidecarCover(data);
                    if (t.cover == null) t.cover = albumArt;
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

    /**
     * 第 12 项：GZ 内嵌封面（LocalMusicScanner.inspectAudio／getEmbeddedPicture）——提出来落盘成文件当封面地址，
     * 不存 bitmap。缓存：filesDir/cover_cache/&lt;mediaId&gt;.jpg（GZ 同款目录与命名），同大小不重写。
     * 文件不存在／提不到图返回 null（上层再试旁路／专辑 art／留空走素图）。
     */
    private static String embeddedCover(Context ctx, long mediaId, String data) {
        if (data == null || data.isEmpty()) return null;
        File audio = new File(data);
        if (!audio.isFile()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(data);
            byte[] pic = r.getEmbeddedPicture();
            if (pic == null || pic.length == 0) return null;
            File dir = new File(ctx.getFilesDir(), "cover_cache");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File art = new File(dir, mediaId + ".jpg");
            if (!art.isFile() || art.length() != pic.length) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(art);
                try {
                    fos.write(pic);
                } finally {
                    fos.close();
                }
            }
            return art.isFile() ? Uri.fromFile(art).toString() : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 第 12 项：GZ 同目录旁路封面（LocalMusicScanner.findSidecarCover）——同目录下依次找
     * &lt;歌名&gt;.jpg／.png／.jpeg／.jpg.png／.jpg.jpeg，存在、可读、长度大于 0 才算。
     */
    private static String sidecarCover(String data) {
        if (data == null || data.isEmpty()) return null;
        File audio = new File(data);
        File parent = audio.getParentFile();
        if (parent == null || !parent.isDirectory()) return null;
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String[] suffixes = {".jpg", ".png", ".jpeg", ".jpg.png", ".jpg.jpeg"};
        for (String suf : suffixes) {
            File cand = new File(parent, stem + suf);
            if (cand.isFile() && cand.canRead() && cand.length() > 0) {
                return Uri.fromFile(cand).toString();
            }
        }
        return null;
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
                    // 内嵌封面：提取出来落缓存文件当封面地址；没有内嵌图就留空（走素图）
                    try {
                        byte[] pic = r.getEmbeddedPicture();
                        if (pic != null && pic.length > 0) {
                            File art = embeddedCoverFile(ctx, f);
                            if (art != null) {
                                if (!art.isFile() || art.length() != pic.length) {
                                    java.io.FileOutputStream fos = new java.io.FileOutputStream(art);
                                    try {
                                        fos.write(pic);
                                    } finally {
                                        fos.close();
                                    }
                                }
                                if (art.isFile()) t.cover = Uri.fromFile(art).toString();
                            }
                        }
                    } catch (Throwable ignored) {
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

    /** 内嵌封面缓存文件：music_library/.covers/<文件名>.jpg（同名同大小不重写）。 */
    private static File embeddedCoverFile(Context ctx, File audio) {
        try {
            File dir = new File(libraryDir(ctx), ".covers");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String base = audio.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            return new File(dir, base + ".jpg");
        } catch (Throwable t) {
            return null;
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
