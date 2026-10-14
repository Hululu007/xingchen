package com.fongmi.android.tv.player;

import android.net.Uri;

import androidx.media3.common.MimeTypes;

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
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    private void addCallable(Iterator<Episode> iterator, List<Callable<List<Episode>>> items) {
        String url = iterator.next().getUrl();
        if (Thunder.Parser.match(url)) {
            items.add(Thunder.Parser.get(url));
            iterator.remove();
        } else if (Youtube.Parser.match(url)) {
            items.add(Youtube.Parser.get(url));
            iterator.remove();
        } else if (Pan115.Parser.match(url)) {
            items.add(Pan115.Parser.get(url));
            iterator.remove();
        } else if (PanQuark.Parser.match(url)) {
            items.add(PanQuark.Parser.get(url));
            iterator.remove();
        }
    }

    public void parse(Vod vod) throws Exception {
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            for (Flag flag : vod.getFlags()) {
                List<Callable<List<Episode>>> items = new ArrayList<>();
                Iterator<Episode> iterator = flag.getEpisodes().iterator();
                while (iterator.hasNext()) addCallable(iterator, items);
                // 磁力走 DHT 拉种子元数据，国内网络经常超过 30 秒，单独放宽到 60 秒
                long timeout = hasThunderParser(items) ? 60 : 30;
                for (Future<List<Episode>> future : executor.invokeAll(items, timeout, TimeUnit.SECONDS)) {
                    try {
                        flag.getEpisodes().addAll(future.get());
                    } catch (CancellationException e) {
                        SpiderDebug.log("source-parse", "解析超时（>%ds），该选集保持原地址", timeout);
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        SpiderDebug.log("source-parse", "解析失败：" + cause.getMessage());
                    }
                }
            }
        }
    }

    private boolean hasThunderParser(List<Callable<List<Episode>>> items) {
        for (Callable<List<Episode>> item : items) if (item instanceof Thunder.Parser) return true;
        return false;
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
