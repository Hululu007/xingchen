package com.fongmi.android.tv.tts;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Edge 大声朗读合成客户端（「阅读」生态在线朗读同款通路）。
 * <p>
 * 协议：wss://speech.platform.bing.com/.../readaloud/edge/v1 WebSocket，
 * 先发 speech.config，再发 ssml；音频以二进制帧回传，turn.end 收尾。
 * 自 2024 年起服务端要求 Sec-MS-GEC 头（时间戳挑战），算法见 secMsGec()。
 * <p>
 * 失败一律抛 TtsException（人话），调用方不许回退系统机械声。
 */
public class EdgeTtsClient {

    /** 给人看的失败文案（统一口径）。 */
    public static final String ERR_OFFLINE = "朗读服务连不上，请检查网络后重试";

    public static final String EDGE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    private static final String WS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
                    + "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String ORIGIN =
            "chrome-extension://jdiccldimpmpmfdpnnlhnhihkbnijdbbip";
    private static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    public static class TtsException extends Exception {
        public TtsException(String message) {
            super(message);
        }
    }

    /**
     * Sec-MS-GEC 时间戳挑战：Windows file time（100ns）向下取整到 5 分钟粒度，
     * 拼 TrustedClientToken 后 SHA256，十六进制大写。
     */
    public static String secMsGec() {
        return secMsGec(System.currentTimeMillis());
    }

    /** 纯函数版本（可单测）。 */
    public static String secMsGec(long nowMs) {
        try {
            long ticks = (nowMs / 1000L + 11644473600L) * 10_000_000L;
            long rounded = ticks - (ticks % 3_000_000_000L);
            String src = rounded + TRUSTED_CLIENT_TOKEN;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(src.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format(Locale.US, "%02X", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 语速倍率 → SSML prosody rate（如 1.25 → +25%）。 */
    public static String rateTag(float rate) {
        int pct = Math.round((rate - 1f) * 100f);
        return (pct >= 0 ? "+" : "") + pct + "%";
    }

    public static String ssml(String text, String voiceId, float rate) {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' "
                + "xml:lang='zh-CN'><voice name='" + voiceId + "'>"
                + "<prosody pitch='+0Hz' rate='" + rateTag(rate) + "' volume='+0%'>"
                + escapeXml(text) + "</prosody></voice></speak>";
    }

    private static String wsDate() {
        try {
            SimpleDateFormat f = new SimpleDateFormat(
                    "EEE MMM dd yyyy HH:mm:ss 'GMT'Z '(Coordinated Universal Time)'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.format(new Date());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String noDashes() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 合成一段文本 → mp3 字节。阻塞调用（调方自开线程），单段 45 秒超时。
     * 抛 TtsException（人话），绝不回退系统 TTS。
     */
    public byte[] synthesize(String text, String voiceId, float rate) throws TtsException {
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(voiceId)) {
            throw new TtsException("没有可合成的文本");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final AtomicReference<String> failure = new AtomicReference<>();
        final String date = wsDate();
        final String connectionId = noDashes();
        final String requestId = noDashes();

        Request req = new Request.Builder().url(WS_URL)
                .header("Origin", ORIGIN)
                .header("User-Agent", EDGE_UA)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Sec-MS-GEC", secMsGec())
                .header("Sec-MS-GEC-Version", "1")
                .header("X-ConnectionId", connectionId)
                .build();

        WebSocket ws = CLIENT.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                String config = "X-Timestamp:" + date + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                        + "{\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":true},"
                        + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";
                webSocket.send(config);
                String msg = "X-RequestId:" + requestId + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "X-Timestamp:" + date + "\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml(text, voiceId, rate);
                webSocket.send(msg);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (text != null && text.contains("Path:turn.end")) {
                    done.countDown();
                } else if (text != null && text.contains("Path:turn.start")) {
                    // 合成开始，无动作
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                try {
                    byte[] raw = bytes.toByteArray();
                    if (raw.length < 2) return;
                    int headerLen = ByteBuffer.wrap(raw, 0, 2)
                            .order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
                    int bodyStart = 2 + headerLen;
                    if (bodyStart < raw.length) {
                        synchronized (audio) {
                            audio.write(raw, bodyStart, raw.length - bodyStart);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                failure.compareAndSet(null, ERR_OFFLINE);
                done.countDown();
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                done.countDown();
            }
        });

        try {
            boolean ok = done.await(45, TimeUnit.SECONDS);
            if (!ok) {
                failure.compareAndSet(null, ERR_OFFLINE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, ERR_OFFLINE);
        } finally {
            try {
                ws.close(1000, null);
            } catch (Throwable ignored) {
            }
        }
        if (failure.get() != null) throw new TtsException(failure.get());
        byte[] out;
        synchronized (audio) {
            out = audio.toByteArray();
        }
        if (out.length == 0) throw new TtsException("朗读服务返回了空音频，换个音色试试");
        return out;
    }
}
