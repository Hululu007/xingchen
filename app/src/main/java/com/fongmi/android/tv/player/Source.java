package com.fongmi.android.tv.player;

import android.net.Uri;

import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.cloud.drive115.Proxy115;
import com.fongmi.android.tv.player.extractor.Force;
import com.fongmi.android.tv.player.extractor.JianPian;
import com.fongmi.android.tv.player.extractor.MpdEdlResolver;
import com.fongmi.android.tv.player.extractor.MpdSanitizer;
import com.fongmi.android.tv.player.extractor.Pan115;
import com.fongmi.android.tv.player.extractor.PanQuark;
import com.fongmi.android.tv.player.extractor.Push;
import com.fongmi.android.tv.player.extractor.Strm;
import com.fongmi.android.tv.player.extractor.TVBus;
import com.fongmi.android.tv.player.extractor.Thunder;
import com.fongmi.android.tv.player.extractor.Video;
import com.fongmi.android.tv.player.extractor.Youtube;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class Source {

    private final List<Extractor> extractors;

    public Source() {
        extractors = new ArrayList<>();
        extractors.add(new Force());
        extractors.add(new JianPian());
        extractors.add(new Pan115());
        extractors.add(new PanQuark());
        extractors.add(new Push());
        extractors.add(new Strm());
        extractors.add(new Thunder());
        extractors.add(new TVBus());
        extractors.add(new Video());
        extractors.add(new Youtube());
    }

    public static Source get() {
        return Loader.INSTANCE;
    }

    private Extractor getExtractor(Uri uri) {
        return extractors.stream().filter(extractor -> extractor.match(uri)).findFirst().orElse(null);
    }

    private static final ExecutorService PARSE_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 详情选集展开（磁力/ed2k/油管/115/夸克链接展开成子选集）。
     * 原实现按线路串行：每条线路 invokeAll 最长阻塞 30 秒（磁力 60 秒），多条线路累加，
     * 详情页要等全部线路展开完才渲染。现改为全线路一次并行提交、共用一个 deadline，
     * 总耗时取最长者而非累加。每条线路内语义不变：原选集在前、解析出的在后、解析按提交顺序；
     * 解析超时/失败的选集保留原地址（之前是直接丢掉）。
     */
    public void parse(Vod vod) throws Exception {
        applyParsed(parseToLists(vod));
    }

    private static void applyParsed(Map<Flag, List<Episode>> parsed) {
        for (Map.Entry<Flag, List<Episode>> e : parsed.entrySet()) {
            e.getKey().getEpisodes().clear();
            e.getKey().getEpisodes().addAll(e.getValue());
        }
    }

    private Map<Flag, List<Episode>> parseToLists(Vod vod) throws Exception {
        Map<Flag, List<Episode>> kept = new LinkedHashMap<>();
        List<Callable<List<Episode>>> all = new ArrayList<>();
        List<Flag> owners = new ArrayList<>();
        List<Episode> raws = new ArrayList<>();
        boolean thunder = false;
        for (Flag flag : vod.getFlags()) {
            List<Episode> raw = new ArrayList<>();
            for (Episode ep : flag.getEpisodes()) {
                String url = ep.getUrl();
                Callable<List<Episode>> task = null;
                if (Thunder.Parser.match(url)) {
                    task = Thunder.Parser.get(url);
                    thunder = true;
                } else if (Youtube.Parser.match(url)) {
                    task = Youtube.Parser.get(url);
                } else if (Pan115.Parser.match(url)) {
                    task = Pan115.Parser.get(url);
                } else if (PanQuark.Parser.match(url)) {
                    task = PanQuark.Parser.get(url);
                }
                if (task == null) raw.add(ep);
                else {
                    all.add(task);
                    owners.add(flag);
                    raws.add(ep);
                }
            }
            kept.put(flag, raw);
        }
        Map<Flag, List<Episode>> merged = new LinkedHashMap<>();
        for (Flag flag : vod.getFlags()) merged.put(flag, new ArrayList<>(kept.get(flag)));
        if (!all.isEmpty()) {
            // 磁力走 DHT 拉种子元数据，国内网络经常超过 30 秒，单独放宽到 60 秒
            long timeout = thunder ? 60 : 30;
            try (ExecutorService executor = Executors.newCachedThreadPool()) {
                List<Future<List<Episode>>> futures = executor.invokeAll(all, timeout, TimeUnit.SECONDS);
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        merged.get(owners.get(i)).addAll(futures.get(i).get());
                    } catch (CancellationException e) {
                        SpiderDebug.log("source-parse", "解析超时（>%ds），该选集保持原地址", timeout);
                        merged.get(owners.get(i)).add(raws.get(i));
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        SpiderDebug.log("source-parse", "解析失败：" + cause.getMessage());
                        merged.get(owners.get(i)).add(raws.get(i));
                    }
                }
            }
        }
        return merged;
    }

    private static boolean needsParse(Vod vod) {
        for (Flag flag : vod.getFlags()) {
            for (Episode ep : flag.getEpisodes()) {
                String url = ep.getUrl();
                if (Thunder.Parser.match(url) || Youtube.Parser.match(url) || Pan115.Parser.match(url) || PanQuark.Parser.match(url)) return true;
            }
        }
        return false;
    }

    /**
     * 详情页先画后展：主数据一到 UI 先渲染，选集展开放后台线程，完成后回主线程一次性换列表并回调。
     * 后台只读选集地址做计算、不碰现存列表；换列表在主线程完成，渲染线程安全。
     * 用独立单线程池，不占详情/搜索共用的 5 线程池，解析再慢也不拖住切源。
     * 回调第二个参数表示是否真的发生了展开；没有可展开的选集时 UI 无需刷新。
     */
    public void parseAsync(Vod vod, BiConsumer<Vod, Boolean> onDone) {
        PARSE_EXECUTOR.execute(() -> {
            Map<Flag, List<Episode>> parsed = null;
            boolean expanded = false;
            try {
                expanded = needsParse(vod);
                if (expanded) parsed = parseToLists(vod);
            } catch (Exception e) {
                SpiderDebug.log("source-parse", "后台展开失败：" + e.getMessage());
            }
            Map<Flag, List<Episode>> result = parsed;
            boolean done = expanded && result != null;
            App.post(() -> {
                if (result != null) applyParsed(result);
                onDone.accept(vod, done);
            });
        });
    }

    public String fetch(Result result) throws Exception {
        return fetch(result, PlayerSetting.getPlayer());
    }

    public String fetch(Result result, int playerType) throws Exception {
        Uri uri = result.getUrl().uri();
        String url = result.getUrl().v();
        int originalParse = result.getParse();
        Extractor extractor = getExtractor(uri);
        if (extractor != null) result.setParse(0);
        if (extractor instanceof Video) result.setParse(1);
        String fetched;
        try {
            fetched = extractor == null ? url : extractor.fetch(result);
        } catch (Exception e) {
            if (!(extractor instanceof Youtube) || originalParse != 1) throw e;
            result.setParse(1);
            return url;
        }
        if (extractor instanceof Youtube && fetched.isEmpty() && originalParse == 1) {
            result.setParse(1);
            return url;
        }
        String sanitized = MpdSanitizer.sanitize(fetched);
        // 115 本机代理的 m3u8 地址：初始格式直接定 m3u8，省掉播放器首轮按普通流
        // 嗅探的一轮误判。走既有格式传递链：Result.format -> PlaySpec.format ->
        // MediaItem.mimeType（与 ExoUtil.getMimeType / MpvPlayerEngine.retryFormat
        // 用的同一 m3u8 标记）。只认本机代理地址形态，其他源的格式推断不动。
        if ((result.getFormat() == null || result.getFormat().isEmpty())
                && Proxy115.isM3u8ProxyUrl(sanitized)) {
            result.setFormat(MimeTypes.APPLICATION_M3U8);
            SpiderDebug.log("player", "115代理地址初始格式定m3u8");
        }
        if (MpdSanitizer.hasLimitedYoutubeAudio(sanitized)) {
            String referer = getHeader(result.getHeader(), "Referer");
            if (referer.contains("youtube.com/watch")) {
                try {
                    sanitized = new Youtube().fetch(referer);
                    SpiderDebug.log("mpd-sanitizer", "replaced limited jar MPD with app YouTube extractor");
                } catch (Exception e) {
                    SpiderDebug.log("mpd-sanitizer", "app YouTube extractor fallback failed: %s", e.getMessage());
                }
            }
        }
        if (MpdSanitizer.isYoutube(sanitized)) {
            Map<String, String> headers = result.getHeader();
            headers.put("User-Agent", "com.google.android.youtube/21.02.35 (Linux; U; Android 14) gzip");
            result.setHeader(headers);
        }
        boolean dash = isDash(result, sanitized);
        if (playerType == PlayerSetting.MPV && dash) {
            String resolved = MpdEdlResolver.resolve(sanitized, result.getHeader());
            if (!resolved.equals(sanitized)) result.setFormat(null);
            sanitized = resolved;
        } else if (dash) {
            sanitized = avoidStaleLocalManifest(sanitized);
        }
        return sanitized;
    }

    private String avoidStaleLocalManifest(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return url;
            return uri.buildUpon().appendQueryParameter("player_ts", Long.toString(System.currentTimeMillis())).build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    private boolean isDash(Result result, String url) {
        String format = result.getFormat();
        if (MimeTypes.APPLICATION_MPD.equals(format) || "application/dash+xml".equalsIgnoreCase(format) || "dash".equalsIgnoreCase(format)) return true;
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("data:application/dash+xml") || lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    private String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public void stop() {
        if (extractors == null) return;
        extractors.forEach(Extractor::stop);
    }

    public void exit() {
        if (extractors == null) return;
        Task.execute(() -> extractors.forEach(Extractor::exit));
    }

    public interface Extractor {

        default String fetch(Result result) throws Exception {
            return fetch(result.getUrl().v());
        }

        String fetch(String url) throws Exception;

        boolean match(Uri uri);

        void stop();

        void exit();
    }

    private static class Loader {
        static volatile Source INSTANCE = new Source();
    }
}
