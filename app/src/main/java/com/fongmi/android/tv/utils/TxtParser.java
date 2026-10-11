package com.fongmi.android.tv.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * txt 混合链接解析：提取 magnet:/ed2k:// /http(s):// 等可播链接，生成播放列表。
 * 需求（2026-10-10）：txt 文件导入播放，内混磁力/HTTP/m3u8/ed2k 等链接。
 */
public class TxtParser {

    public static class Link {
        public final String name;
        public final String url;

        public Link(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /** 与 Sniffer.AI_PUSH 同源：匹配 http(s)/thunder/magnet/video 及 ed2k 链接 */
    private static final Pattern LINK = Pattern.compile("(https?|thunder|magnet|video):\\S+|ed2k://\\|file\\|[^|]*\\|\\d+\\|[0-9a-fA-F]{32}\\|/(\\|sources,[^|]*\\|/)?");

    private static final int MAX_LINKS = 500;

    /**
     * 混合链接文件判定：含 magnet:/ed2k:// 即走播放列表；
     * 否则按直播列表处理（兼容旧行为，纯 http 的 txt 仍进直播）。
     */
    public static boolean isMixedLinks(String text) {
        return text != null && (text.contains("magnet:") || text.contains("ed2k://"));
    }

    public static List<Link> parse(String text) {
        Map<String, String> dedup = new LinkedHashMap<>();
        if (text == null) return new ArrayList<>();
        int n = 0;
        for (String line : text.split("\\r?\\n")) {
            Matcher m = LINK.matcher(line);
            while (m.find()) {
                if (dedup.size() >= MAX_LINKS) break;
                String raw = m.group(0);
                String url = raw.replaceAll("[.,;!?\"'\\)\\]]+$", "");
                if (url.length() < 8 || dedup.containsKey(url)) continue;
                String name = line.replace(raw, "").trim();
                if (name.length() > 24) name = name.substring(0, 24);
                if (name.isEmpty()) name = "链接" + (++n);
                dedup.put(url, name);
            }
        }
        List<Link> list = new ArrayList<>(dedup.size());
        for (Map.Entry<String, String> e : dedup.entrySet()) list.add(new Link(e.getValue(), e.getKey()));
        return list;
    }
}
