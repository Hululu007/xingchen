package com.fongmi.android.tv.music;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drivepikpak.DrivePikPak;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网盘音乐源：把网盘曲目解析成播放直链，并供音乐搜索的「115 网盘」一段。
 * 网盘页（MusicCloudActivity）自己调 Drive 列表筛音频进队列，不走这里的 search()；
 * 音乐搜索走 searchPage：Drive115.searchFiles 全盘搜、只收音频（文件名后缀＋ico
 * 类型双判，视频一律不进段），结果自成一段（搜索页按源分段展示、失败只标本段）。
 * 115 走本机代理（Drive115.getPlayUrl 自带 Proxy115），播放器不直连 115；
 * 夸克取下载直链直接播（与 CloudDriveActivity 视频路子一致）。
 * PikPak 用 fid 取链（DrivePikPak.getPlayUrl 只认 file id，不依赖 pickCode；
 * pickCode 在 PikPak 列表里复用存 mime_type，透传无害）。
 * 标签与歌词（115）：搜索结果出来后由 Pan115Tags 一趟读全文件头标签
 * （内嵌封面／内嵌歌词／时长／专辑／标签歌名歌手，按文件 id 缓存）；
 * lyric 先查这份内嵌词——文件自带的词天然对版，命中就不出门配；
 * 没有才返回空、走播放器既有取词链（Worker 优先）。夸克／PikPak 不出词。
 */
public class CloudMusicSource implements MusicSource {

    public static final String PLATFORM = "pan";

    /** 音乐搜索里 115 一段的站点身份（srcUrl 兼作搜索页分桶键） */
    public static final String SITE_SRC_115 = "pan://115";
    /** 115 搜索原始页大小与每「音乐搜索页」扫描的原始页数：
     * 115 搜索按全类型回包、音频要过滤后才计数，原始页扫宽一点防整页被非音频占满时误判末页 */
    private static final int RAW_PAGE = 30;
    private static final int SCAN_PAGES = 4;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    /** 幂等注册：网盘页打开时调一次即可；服务与页同进程，注册后 playUrl 才能按 platform 找到本源。 */
    public static void ensureRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                MusicRepository.register(new CloudMusicSource());
            } catch (Throwable ignored) {
                REGISTERED.set(false);
            }
        }
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return Collections.emptyList();
    }

    /** 搜索排只出 115 一家、且只在他已登录时（登录态是壳子统一托管的那份）；
     * 未登录就不出现、不摆占位。聚合 search() 仍回空：本源只经搜索页分段路进搜索，
     * 心情电台攒队列等聚合消费方口径不变。 */
    @Override
    public List<MusicSite> searchableSites() {
        List<MusicSite> out = new java.util.ArrayList<>();
        try {
            if (new Drive115().isLogin()) {
                out.add(new MusicSite(PLATFORM, "115网盘", "", SITE_SRC_115));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return Collections.emptyList();
    }

    /**
     * 115 一段的分页搜：Drive115.searchFiles（webapi/files/search 全盘搜）→
     * 只收音频文件（目录跳过；isAudio 是文件名后缀＋ico 类型双判）→ 映射成曲目。
     * 原始结果按 (page-1)*SCAN_PAGES 起连续扫 SCAN_PAGES 页原始包，末页信号认
     * 原始包不满 RAW_PAGE（115 侧到尾），不认过滤后的条数。登录失效在此抛错，
     * 搜索页只标本段失败、别家不受影响。映射后同步兑现已缓存的标签，未读的
     * 交 Pan115Tags 异步读全（列表先行、封面/时长/标签字段随后回填同一批对象），
     * 全批标签定案后再给仍无封面的条目走平台配图兜底（内嵌优先、平台第二）。
     */
    @Override
    public MusicSearchPage searchPage(MusicSite site, String keyword, int page) throws Exception {
        if (site == null || !SITE_SRC_115.equals(site.srcUrl)) {
            return new MusicSearchPage(Collections.emptyList(), true);
        }
        Drive115 drive = new Drive115();
        if (!drive.isLogin()) throw new Exception("115 未登录");
        int p = Math.max(1, page);
        List<MusicTrack> out = new java.util.ArrayList<>();
        boolean exhausted = false;
        for (int i = 0; i < SCAN_PAGES; i++) {
            int offset = ((p - 1) * SCAN_PAGES + i) * RAW_PAGE;
            List<CloudFile> raw = drive.searchFiles(keyword, offset, RAW_PAGE);
            if (raw == null) raw = Collections.emptyList();
            for (CloudFile f : raw) {
                if (f == null || f.isDir() || !f.isAudio()) continue;
                out.add(mapSearchTrack(f));
            }
            if (raw.size() < RAW_PAGE) {
                exhausted = true;
                break;
            }
        }
        final List<MusicTrack> batch = out;
        final String kw = keyword;
        com.fongmi.android.tv.cloud.drive115.Pan115Tags.readBatch(batch, () ->
                com.fongmi.android.tv.utils.Task.largeExecutor().execute(() -> supplementCovers(batch, kw)));
        return new MusicSearchPage(out, exhausted);
    }

    /** 115 搜索结果 → 曲目：身份与网盘页同口径（id=pan115:fid、rawJson 带 drive/fid/name/pickCode，
     * 取链时按 rawJson 现场重建 Drive115，不绑任何页面）；歌名歌手先按文件名如实解析，
     * 标签读到后覆盖（Pan115Tags.applyTo）。封面先用 115 回包自带的缩略图（音频多半没有），
     * 时长回包没有就留 0 等标签后补，都不编造。 */
    private static MusicTrack mapSearchTrack(CloudFile f) {
        String[] parsed = parseFileName(f.getName());
        MusicTrack t = new MusicTrack("pan115:" + f.getId(), parsed[0], parsed[1], "", 0,
                f.getThumbUrl() == null || f.getThumbUrl().isEmpty() ? null : f.getThumbUrl());
        t.platform = PLATFORM;
        try {
            JSONObject o = new JSONObject();
            o.put("drive", Drive115.ID);
            o.put("fid", f.getId());
            o.put("name", f.getName() == null ? "" : f.getName());
            o.put("pickCode", f.getPickCode() == null ? "" : f.getPickCode());
            t.rawJson = o.toString();
        } catch (Exception ignored) {
        }
        return t;
    }

    private static final java.util.regex.Pattern QUALITY_BRACKET = java.util.regex.Pattern.compile(
            "[\\[【（(][^\\]】）)]*(flac|ape|wav|mp3|320|192|128|无损|高音质|hq|sq|母带|hi-?res|24bit|16bit)[^\\]】）)]*[\\]】）)]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern LEADING_INDEX = java.util.regex.Pattern.compile(
            "^\\s*(\\[\\d{1,3}\\]|\\d{1,3}\\s*[.、])\\s*");

    /**
     * 文件名解析歌名／歌手（如实解析、不编造）：去扩展名、去带音质词的括号段
     * （[FLAC]／【无损】类）、去开头序号（01.／[01]），再按「歌手 - 歌名」拆
     * （空格＋连字符＋空格，含 –／— 变体）；拆不出歌手就只留歌名、歌手空。
     * 这是初值——标签（TIT2/TPE1）读到后以标签为准覆盖。
     * @return {歌名, 歌手}
     */
    public static String[] parseFileName(String name) {
        String base = name == null ? "" : name.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = QUALITY_BRACKET.matcher(base).replaceAll(" ").trim();
        base = LEADING_INDEX.matcher(base).replaceFirst("");
        String title = base;
        String artist = "";
        String[] seps = {" - ", " – ", " — "};
        for (String sep : seps) {
            int idx = base.indexOf(sep);
            if (idx > 0 && idx + sep.length() < base.length()) {
                String left = base.substring(0, idx).trim();
                String right = base.substring(idx + sep.length()).trim();
                if (!left.isEmpty() && !right.isEmpty()) {
                    artist = left;
                    title = right;
                }
                break;
            }
        }
        return new String[]{collapseSpaces(title), collapseSpaces(artist)};
    }

    private static String collapseSpaces(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /**
     * 平台配封面兜底（标签定案后跑）：仍无封面的条目拿搜索词在其它在线源对歌
     * （MusicRepository.matchSong 同口径，精确优先），命中同歌才用它的封面——
     * 与 PyMusicSource.supplementCovers 同路。配不上就留空走素图，不拿别首的图凑。
     * 聚合 search 含本源自身（search() 恒空）不构成递归。
     */
    private static void supplementCovers(List<MusicTrack> tracks, String keyword) {
        if (tracks == null || tracks.isEmpty() || keyword == null || keyword.isEmpty()) return;
        boolean anyMissing = false;
        for (MusicTrack t : tracks) {
            if (t != null && (t.cover == null || t.cover.isEmpty())) {
                anyMissing = true;
                break;
            }
        }
        if (!anyMissing) return;
        try {
            List<MusicTrack> pool = MusicRepository.search(keyword);
            if (pool == null || pool.isEmpty()) return;
            List<MusicTrack> changed = new java.util.ArrayList<>();
            for (MusicTrack t : tracks) {
                if (t == null || (t.cover != null && !t.cover.isEmpty())) continue;
                MusicTrack hit = MusicRepository.matchSong(t, pool);
                if (hit != null && hit != t && hit.cover != null && !hit.cover.isEmpty()) {
                    t.cover = hit.cover;
                    t.coverBackfilled = true;
                    changed.add(t);
                }
            }
            if (!changed.isEmpty()) {
                com.fongmi.android.tv.cloud.drive115.Pan115Tags.notifyFilled(changed);
            }
        } catch (Throwable t) {
            com.github.catvod.crawler.SpiderDebug.log("music", "pan115 cover supplement error %s", t.getMessage());
        }
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

    /**
     * 歌词第一路＝文件内嵌词（Pan115Tags 缓存，搜索时已读全；没赶上就在这里
     * 同步读一次定案）：文件自带的词天然对版，命中直接用、不再出门配；
     * 没有（或非 115）返回 null，仓库层照既有取词链往下走（Worker 优先）。
     */
    @Override
    public String lyric(MusicTrack track) {
        if (track == null || track.rawJson == null) return null;
        try {
            JSONObject o = new JSONObject(track.rawJson);
            if (!Drive115.ID.equals(o.optString("drive"))) return null;
        } catch (Throwable ignored) {
            return null;
        }
        String lyric = com.fongmi.android.tv.cloud.drive115.Pan115Tags.lyricFor(track);
        return lyric == null || lyric.trim().isEmpty() ? null : lyric;
    }

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        if (track == null || track.rawJson == null) throw new Exception("网盘曲目信息缺失");
        JSONObject o = new JSONObject(track.rawJson);
        String driveId = o.optString("drive");
        CloudFile f = new CloudFile();
        f.setId(o.optString("fid"));
        f.setName(o.optString("name"));
        f.setPickCode(o.optString("pickCode"));
        CloudDrive drive;
        if (Drive115.ID.equals(driveId)) {
            drive = new Drive115();
        } else if (DriveQuark.ID.equals(driveId)) {
            drive = new DriveQuark();
        } else if (DrivePikPak.ID.equals(driveId)) {
            drive = new DrivePikPak(App.get());
        } else {
            throw new Exception("未知网盘来源");
        }
        String url = drive.getPlayUrl(f);
        if (url == null || url.isEmpty()) throw new Exception("取播放地址失败");
        return url;
    }
}
