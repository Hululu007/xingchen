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

    /** 合并扫描：MediaStore 歌曲 ＋ music_library 目录文件。
     * 第二波：应用扫描偏好——最小时长过滤（瓜子档）与文件夹排除（命中目录及其子目录的歌不进库，文件不动）。 */
    public static List<MusicTrack> scan(Context ctx) {
        return scan(ctx, true);
    }

    /** 真全集扫描（不应用最小时长／文件夹排除过滤，路径去重照常）：文件夹排除弹窗专用——
     * 弹窗若拿过滤后的结果当数据源，被完全排除的文件夹根本不在列表里、永远勾不回来。 */
    public static List<MusicTrack> scanAll(Context ctx) {
        return scan(ctx, false);
    }

    private static List<MusicTrack> scan(Context ctx, boolean applyPrefs) {
        long minDuration = applyPrefs ? MusicStore.getDeviceMinDuration() : 0;
        Set<String> excluded = applyPrefs ? MusicStore.getDeviceExcludedFolders()
                : java.util.Collections.emptySet();
        List<MusicTrack> out = new ArrayList<>();
        scanMediaStore(ctx, out);
        scanLibraryDir(ctx, out);
        // 按路径去重（一首一行）：music_library 在 App 专属外部目录下、同样会被
        // MediaStore 扫到，导入歌在两路各入账一次（一为 device:<mediaId>、一为
        // device:lib:<文件名>），列表里同一文件出现两行。MediaStore 路先行、
        // 保留先到者（它带 mediaId 内容 URI 与专辑 art 决议），同路径后到者丢弃；
        // 路径为空的条目无从判同，不参与去重、照常保留。
        Set<String> seenPaths = new HashSet<>();
        List<MusicTrack> deduped = new ArrayList<>();
        for (MusicTrack t : out) {
            if (t == null) continue;
            String path = t.localPath;
            if (path != null && !path.isEmpty() && !seenPaths.add(path)) continue;
            deduped.add(t);
        }
        out = deduped;
        if ((minDuration <= 0 && excluded.isEmpty())) return out;
        List<MusicTrack> kept = new ArrayList<>();
        for (MusicTrack t : out) {
            if (t == null) continue;
            if (minDuration > 0 && t.duration > 0 && t.duration < minDuration) continue;
            if (!excluded.isEmpty() && isExcluded(t.localPath, excluded)) continue;
            kept.add(t);
        }
        return kept;
    }

    /** 路径是否落在任一排除目录内（等于该目录或在其子目录下；路径为空不算排除） */
    static boolean isExcluded(String path, Set<String> excluded) {
        if (path == null || path.isEmpty() || excluded == null || excluded.isEmpty()) return false;
        String dir = new File(path).getParent();
        if (dir == null) return false;
        for (String ex : excluded) {
            if (ex == null || ex.isEmpty()) continue;
            if (dir.equals(ex) || dir.startsWith(ex + File.separator)) return true;
        }
        return false;
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
                    String rawTitle = c.getString(iTitle);
                    t.title = rawTitle == null ? null : com.fongmi.android.tv.utils.LocalTrackMeta.cleanTitle(rawTitle);
                    t.artist = c.getString(iArtist);
                    if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.artist)) {
                        String tagArtist = iData >= 0 ? tagArtist(c.getString(iData)) : null;
                        if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(tagArtist)) tagArtist = tagArtistByUri(ctx, id);
                        if (!com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(tagArtist)) t.artist = tagArtist;
                    }
                    t.album = c.getString(iAlbum);
                    if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderAlbum(t.album)) {
                        String tagAlbum = iData >= 0 ? tagAlbum(c.getString(iData)) : null;
                        if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderAlbum(tagAlbum)) tagAlbum = tagAlbumByUri(ctx, id);
                        if (!com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderAlbum(tagAlbum)) t.album = tagAlbum;
                    }
                    // 兜底全落空时占位值不许留在数据里（<unknown> 会一路印到播放页/队列）：
                    // 歌手/专辑清成空，界面走各自的空态，在线对歌补全（onlineMeta）也只认空缺。
                    if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderArtist(t.artist)) t.artist = "";
                    if (com.fongmi.android.tv.utils.LocalTrackMeta.isPlaceholderAlbum(t.album)) t.album = "";
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

    private static String tagArtist(String data) {
        if (data == null || data.isEmpty()) return null;
        java.io.File audio = new java.io.File(data);
        if (!audio.isFile()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(data);
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 专辑文件标签兜底（跟 tagArtist 同口径：MediaStore 专辑占位时才读文件标签） */
    private static String tagAlbum(String data) {
        if (data == null || data.isEmpty()) return null;
        java.io.File audio = new java.io.File(data);
        if (!audio.isFile()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(data);
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 分区存储兜底：文件路径拿不到（DATA 列为空／无权直读）时走内容 URI 读标签，安卓 10＋ 上这路才通。 */
    private static String tagArtistByUri(Context ctx, long mediaId) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId));
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String tagAlbumByUri(Context ctx, long mediaId) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId));
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
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
                t.title = com.fongmi.android.tv.utils.LocalTrackMeta.cleanTitle(f.getName());
                t.platform = DeviceMusicSource.PLATFORM;
                t.localPath = f.getAbsolutePath();
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getAbsolutePath());
                    String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                    String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (title != null && !title.trim().isEmpty()) t.title = com.fongmi.android.tv.utils.LocalTrackMeta.cleanTitle(title);
                    if (artist != null && !artist.trim().isEmpty()) t.artist = artist.trim();
                    if (album != null && !album.trim().isEmpty()) t.album = album.trim();
                    if (dur != null) {
                        try {
                            t.duration = Long.parseLong(dur.trim());
                        } catch (Throwable ignored) {
                        }
                    }
                    // 内嵌封面：提取出来落缓存文件当封面地址；
                    // 第三波 P-8b：内嵌取不到时复用同目录旁路图 sidecarCover(文件路径)，再留空（走素图）。
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
                if (t.cover == null || t.cover.isEmpty()) {
                    try {
                        String sidecar = sidecarCover(f.getAbsolutePath());
                        if (sidecar != null && !sidecar.isEmpty()) t.cover = sidecar;
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
}
