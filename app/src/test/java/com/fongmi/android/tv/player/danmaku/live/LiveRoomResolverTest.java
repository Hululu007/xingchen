package com.fongmi.android.tv.player.danmaku.live;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 识别层最小集（本步只上斗鱼强信号）：前缀与链接形态成立，其余一律不接——
 * 裸数字/标题/域名类弱信号永不单独定罪（设计第四节降级口径）。
 */
public class LiveRoomResolverTest {

    private static LiveRoomResolver.Snapshot snapshot(String vodId, String playUrl) {
        return new LiveRoomResolver.Snapshot("site", vodId, playUrl, "", "", "");
    }

    @Test
    public void douyuPrefixResolves() {
        Optional<RoomRef> ref = LiveRoomResolver.resolve(snapshot("douyu_9999", ""));
        assertTrue(ref.isPresent());
        assertEquals(RoomRef.Platform.DOUYU, ref.get().platform());
        assertEquals("9999", ref.get().roomId());
        assertEquals("douyu:9999", ref.get().key());
    }

    @Test
    public void douyuLinkResolvesFromVodIdAndPlayUrl() {
        assertEquals("12345", LiveRoomResolver.resolve(snapshot("https://live.douyu.com/12345", "")).get().roomId());
        assertEquals("777", LiveRoomResolver.resolve(snapshot("", "episode$https://live.douyu.com/777?from=x")).get().roomId());
    }

    @Test
    public void weakSignalsNeverResolve() {
        assertTrue(LiveRoomResolver.resolve(snapshot("9999", "")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(snapshot("", "https://cdn.example.com/douyucdn/9999.flv")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(new LiveRoomResolver.Snapshot("斗鱼源", "", "", "斗鱼直播", "某主播", "")).isEmpty());
    }

    @Test
    public void malformedIdsDoNotResolve() {
        assertTrue(LiveRoomResolver.resolve(snapshot("douyu_", "")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(snapshot("douyu_abc", "")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(snapshot("douyu_1234567890123456", "")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(snapshot("bili_9999", "")).isEmpty());
        assertTrue(LiveRoomResolver.resolve(LiveRoomResolver.Snapshot.EMPTY).isEmpty());
        assertTrue(LiveRoomResolver.resolve(null).isEmpty());
    }
}
