package com.fongmi.android.tv.player.danmaku.live.douyin;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DouyinPacketCodec 对拍：录制是 2026-10-24 本机从在播房 974709925783
 * （2026DNF全国格斗大赛秋季赛）按生产口径（webmssdk 签名连入、hb 心跳、needAck 回执）
 * 录的 35 秒原始帧；期望由独立 Python 参照探针（手写 protobuf 解码）同录制产出，
 * 两边逐行比对（昵称/白色/文本/人气/顺序）。
 */
public class DouyinPacketCodecTest {

    @Test
    public void decodeRecordingMatchesIndependentReference() throws Exception {
        StringBuilder actual = new StringBuilder();
        int count = 0;
        for (byte[] frame : readRecording()) {
            DouyinPacketCodec.Decoded decoded = DouyinPacketCodec.decode(frame);
            for (DouyinPacketCodec.Event event : decoded.events()) {
                if (event instanceof DouyinPacketCodec.Event.ChatEvent chatEvent) {
                    DouyinPacketCodec.Chat chat = chatEvent.chat();
                    actual.append(chat.nickname()).append('\t')
                            .append(chat.colorArgb() & 0xFFFFFFFFL).append('\t')
                            .append(chat.text()).append('\n');
                    count++;
                } else if (event instanceof DouyinPacketCodec.Event.OnlineEvent onlineEvent) {
                    actual.append("ONLINE\t").append(onlineEvent.totalUser()).append('\n');
                }
            }
        }
        actual.append("COUNT\t").append(count).append('\n');
        String expected = new String(readResource("/douyin-974709925783-expected.txt"), StandardCharsets.UTF_8);
        assertEquals(expected, actual.toString());
        assertEquals(29, count);
    }

    @Test
    public void needAckFramesProduceWellFormedAck() throws Exception {
        int acks = 0;
        for (byte[] frame : readRecording()) {
            DouyinPacketCodec.Decoded decoded = DouyinPacketCodec.decode(frame);
            if (decoded.ackFrame() == null) continue;
            acks++;
            Map<Integer, List<Object>> fields = DouyinPacketCodec.parse(decoded.ackFrame());
            assertEquals("ack", stringField(fields, 7));
            assertTrue(fields.containsKey(2));
            assertTrue(fields.containsKey(8));
        }
        assertTrue("录制窗内应有 needAck 回执帧", acks > 0);
    }

    @Test
    public void heartbeatFrameIsPayloadTypeHb() {
        Map<Integer, List<Object>> fields = DouyinPacketCodec.parse(DouyinPacketCodec.encodeHeartbeat());
        assertEquals("hb", stringField(fields, 7));
        assertEquals(1, fields.size());
    }

    @Test
    public void garbageFramesDecodeToEmpty() {
        assertEquals(DouyinPacketCodec.Decoded.EMPTY, DouyinPacketCodec.decode(null));
        assertEquals(DouyinPacketCodec.Decoded.EMPTY, DouyinPacketCodec.decode(new byte[0]));
        assertEquals(DouyinPacketCodec.Decoded.EMPTY, DouyinPacketCodec.decode(new byte[]{1, 2, 3}));
        assertEquals(DouyinPacketCodec.Decoded.EMPTY, DouyinPacketCodec.decode("not a push frame".getBytes(StandardCharsets.UTF_8)));
    }

    static List<byte[]> readRecording() throws Exception {
        byte[] raw = readResource("/douyin-974709925783-recording.bin");
        List<byte[]> frames = new ArrayList<>();
        int pos = 0;
        while (pos + 4 <= raw.length) {
            int length = (raw[pos] & 0xFF) << 24 | (raw[pos + 1] & 0xFF) << 16 | (raw[pos + 2] & 0xFF) << 8 | (raw[pos + 3] & 0xFF);
            pos += 4;
            byte[] frame = new byte[length];
            System.arraycopy(raw, pos, frame, 0, length);
            pos += length;
            frames.add(frame);
        }
        return frames;
    }

    static byte[] readResource(String resource) throws Exception {
        try (InputStream stream = DouyinPacketCodecTest.class.getResourceAsStream(resource)) {
            assertNotNull("missing test resource " + resource, stream);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            stream.transferTo(out);
            return out.toByteArray();
        }
    }

    private static String stringField(Map<Integer, List<Object>> fields, int field) {
        List<Object> values = fields.get(field);
        if (values == null || values.isEmpty() || !(values.get(0) instanceof byte[] bytes)) return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
