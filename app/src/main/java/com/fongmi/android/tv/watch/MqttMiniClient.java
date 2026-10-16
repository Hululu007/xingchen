package com.fongmi.android.tv.watch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * 一起看通道：手写 MQTT 3.1.1 over WebSocket（不引新依赖）。
 * 只做房间需要的子集：CONNECT（带遗嘱）/SUBSCRIBE/PUBLISH(QoS0/1)/PINGREQ/DISCONNECT。
 * 编解码部分是纯字节逻辑（Codec），桌面可单测。
 */
public final class MqttMiniClient {

    public interface Listener {
        void onOpen();

        void onMessage(String topic, byte[] payload, boolean retained);

        void onClosed(String reason);

        void onFailure(String error);
    }

    private final String url;
    private final String clientId;
    private final String willTopic;
    private final byte[] willPayload;
    private final Listener listener;
    private final AtomicInteger packetId = new AtomicInteger(1);
    private final List<String> pendingSubs = new ArrayList<>();

    private OkHttpClient http;
    private WebSocket ws;
    private ScheduledExecutorService pinger;
    private volatile boolean open;
    private volatile boolean closedByUser;

    public MqttMiniClient(String url, String clientId, String willTopic, byte[] willPayload, Listener listener) {
        this.url = url;
        this.clientId = clientId;
        this.willTopic = willTopic;
        this.willPayload = willPayload;
        this.listener = listener;
    }

    public synchronized void connect() {
        closedByUser = false;
        http = new OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .build();
        Request req = new Request.Builder().url(url)
                .addHeader("Sec-WebSocket-Protocol", "mqtt")
                .build();
        ws = http.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send(ByteString.of(Codec.connect(clientId, willTopic, willPayload, 30)));
            }

            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                handlePacket(bytes.toByteArray());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                teardown();
                if (!closedByUser) listener.onClosed(reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                teardown();
                if (!closedByUser) listener.onFailure(String.valueOf(t));
            }
        });
    }

    public synchronized void subscribe(String topicFilter) {
        synchronized (pendingSubs) {
            if (!open) {
                if (!pendingSubs.contains(topicFilter)) pendingSubs.add(topicFilter);
                return;
            }
        }
        send(Codec.subscribe(packetId.getAndIncrement(), topicFilter));
    }

    public void publish(String topic, byte[] payload, boolean retained) {
        if (!open) return;
        send(Codec.publish(topic, payload, retained, packetId.getAndIncrement()));
    }

    public synchronized void disconnect() {
        closedByUser = true;
        try {
            if (ws != null && open) ws.send(ByteString.of(Codec.DISCONNECT));
        } catch (Throwable ignored) {
        }
        try {
            if (ws != null) ws.close(1000, "bye");
        } catch (Throwable ignored) {
        }
        teardown();
    }

    private void send(byte[] packet) {
        WebSocket w = ws;
        if (w != null) w.send(ByteString.of(packet));
    }

    private void teardown() {
        open = false;
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
    }

    private void handlePacket(byte[] p) {
        if (p.length < 2) return;
        int type = (p[0] >> 4) & 0x0F;
        switch (type) {
            case 2: // CONNACK
                if (p.length >= 4 && p[3] == 0) {
                    open = true;
                    List<String> subs;
                    synchronized (pendingSubs) {
                        subs = new ArrayList<>(pendingSubs);
                        pendingSubs.clear();
                    }
                    for (String f : subs) send(Codec.subscribe(packetId.getAndIncrement(), f));
                    startPinger();
                    listener.onOpen();
                } else {
                    listener.onFailure("connack rc=" + (p.length >= 4 ? p[3] : -1));
                }
                break;
            case 3: { // PUBLISH
                Codec.Publish pub = Codec.decodePublish(p);
                if (pub != null) listener.onMessage(pub.topic, pub.payload, pub.retained);
                break;
            }
            default:
                break; // SUBACK/PUBACK/PINGRESP 不处理
        }
    }

    private void startPinger() {
        if (pinger != null) pinger.shutdownNow();
        pinger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watch-mqtt-ping");
            t.setDaemon(true);
            return t;
        });
        pinger.scheduleAtFixedRate(() -> {
            if (open) send(Codec.PINGREQ);
        }, 20, 20, TimeUnit.SECONDS);
    }

    /** MQTT 报文编解码（纯逻辑）。 */
    static final class Codec {
        static final byte[] PINGREQ = {(byte) 0xC0, 0x00};
        static final byte[] DISCONNECT = {(byte) 0xE0, 0x00};

        static byte[] connect(String clientId, String willTopic, byte[] willPayload, int keepAliveSec) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, "MQTT");
            body.write(4); // level
            int flags = 0x02; // clean session
            if (willTopic != null) flags |= 0x04 | 0x20; // will flag + will retain, QoS0
            body.write(flags);
            body.write((keepAliveSec >> 8) & 0xFF);
            body.write(keepAliveSec & 0xFF);
            writeUtf(body, clientId);
            if (willTopic != null) {
                writeUtf(body, willTopic);
                writeBin(body, willPayload == null ? new byte[0] : willPayload);
            }
            return wrap(0x10, body.toByteArray());
        }

        static byte[] subscribe(int packetId, String filter) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write((packetId >> 8) & 0xFF);
            body.write(packetId & 0xFF);
            writeUtf(body, filter);
            body.write(1); // QoS1
            return wrap(0x82, body.toByteArray());
        }

        static byte[] publish(String topic, byte[] payload, boolean retained, int packetId) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, topic);
            // QoS1: 带 packetId
            body.write((packetId >> 8) & 0xFF);
            body.write(packetId & 0xFF);
            body.write(payload, 0, payload.length);
            return wrap(0x32 | (retained ? 0x01 : 0), body.toByteArray());
        }

        static final class Publish {
            String topic;
            byte[] payload;
            boolean retained;
        }

        static Publish decodePublish(byte[] p) {
            try {
                Publish out = new Publish();
                out.retained = (p[0] & 0x01) != 0;
                int qos = (p[0] >> 1) & 0x03;
                int idx = 1;
                int rem = 0, mult = 1;
                while (true) {
                    int b = p[idx++] & 0xFF;
                    rem += (b & 0x7F) * mult;
                    if ((b & 0x80) == 0) break;
                    mult *= 128;
                }
                int tlen = ((p[idx] & 0xFF) << 8) | (p[idx + 1] & 0xFF);
                idx += 2;
                out.topic = new String(p, idx, tlen, StandardCharsets.UTF_8);
                idx += tlen;
                if (qos > 0) idx += 2; // packetId
                int plen = p.length - idx;
                out.payload = new byte[plen];
                System.arraycopy(p, idx, out.payload, 0, plen);
                return out;
            } catch (Throwable t) {
                return null;
            }
        }

        private static byte[] wrap(int header, byte[] body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(header);
            int len = body.length;
            do {
                int b = len % 128;
                len /= 128;
                if (len > 0) b |= 0x80;
                out.write(b);
            } while (len > 0);
            out.write(body, 0, body.length);
            return out.toByteArray();
        }

        private static void writeUtf(ByteArrayOutputStream out, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write((b.length >> 8) & 0xFF);
            out.write(b.length & 0xFF);
            out.write(b, 0, b.length);
        }

        private static void writeBin(ByteArrayOutputStream out, byte[] b) {
            out.write((b.length >> 8) & 0xFF);
            out.write(b.length & 0xFF);
            out.write(b, 0, b.length);
        }
    }
}
