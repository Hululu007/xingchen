package com.fongmi.android.tv.player.danmaku.live;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源无关识别层（设计第四节）：只认带房号的强信号，弱信号（CDN 域名/标题）永不单独定罪。
 * 本步（施工序第 2 步）只上斗鱼强信号：vod_id 前缀 douyu_{rid} 与 live.douyu.com/{rid} 链接形态；
 * 识别不出返回 empty＝不接，绝不拿标题反查房间。
 */
public final class LiveRoomResolver {

    private static final Pattern DOUYU_PREFIX = Pattern.compile("^douyu_(\\d{1,15})$");
    private static final Pattern DOUYU_LINK = Pattern.compile("live\\.douyu\\.com/(\\d{1,15})(?:[/?#]|$)");

    private LiveRoomResolver() {
    }

    /**
     * 起播装配点的信号快照（取自 PlaySpec 与其源 Result 的 Vod，全部可空）。
     * siteKey/name/playFrom 只作加权证据，本步不单独成立任何判定。
     */
    public record Snapshot(String siteKey, String vodId, String playUrl, String playFrom, String name, String streamUrl) {

        public static final Snapshot EMPTY = new Snapshot("", "", "", "", "", "");

        public Snapshot {
            siteKey = siteKey == null ? "" : siteKey;
            vodId = vodId == null ? "" : vodId;
            playUrl = playUrl == null ? "" : playUrl;
            playFrom = playFrom == null ? "" : playFrom;
            name = name == null ? "" : name;
            streamUrl = streamUrl == null ? "" : streamUrl;
        }
    }

    public static Optional<RoomRef> resolve(Snapshot snapshot) {
        if (snapshot == null) return Optional.empty();
        Optional<RoomRef> douyu = resolveDouyu(snapshot);
        if (douyu.isPresent()) return douyu;
        return Optional.empty();
    }

    private static Optional<RoomRef> resolveDouyu(Snapshot snapshot) {
        Matcher prefix = DOUYU_PREFIX.matcher(snapshot.vodId().trim());
        if (prefix.matches()) return Optional.of(new RoomRef(RoomRef.Platform.DOUYU, prefix.group(1)));
        String link = findDouyuLink(snapshot.vodId());
        if (link != null) return Optional.of(new RoomRef(RoomRef.Platform.DOUYU, link));
        link = findDouyuLink(snapshot.playUrl());
        if (link != null) return Optional.of(new RoomRef(RoomRef.Platform.DOUYU, link));
        return Optional.empty();
    }

    private static String findDouyuLink(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = DOUYU_LINK.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
