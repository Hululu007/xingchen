package com.fongmi.android.tv.watch;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 一起看房间会话：房号/口令、房主控播、进度对表、房内弹幕，全部走公共消息通道。
 * 通道顺序：HiveMQ（真机实测最稳）→ Mosquitto 备选。
 * 协议（topic xc/room/<房号>/msg，JSON）：
 * play/pause/resume/seek/sync/chat/join/bye/needstate；
 * 在线状态走 retained 的 xc/room/<房号>/p/<成员id>，房主播放状态走 retained 的 .../state。
 */
public final class WatchSession {

    public interface Listener {
        void onConnected(boolean host, String roomCode);

        void onDisconnected(String reason);

        void onPlay(String url, String title, long posMs, java.util.Map<String, String> headers);

        void onPause();

        void onResume(long posMs);

        void onSeek(long posMs);

        void onSync(long posMs, boolean playing);

        void onChat(String name, String text);

        void onMembers(int count);

        void onPeerJoin(String name);

        void onHostGone();

        void onSingStart(String name);

        void onSingStop(String name);

        void onScore(String name, int score, String grade);

        void onVoiceSignal(String kind, String fromId, String name, com.google.gson.JsonObject data);
    }

    private static final String[] BROKERS = {
            "wss://broker.hivemq.com:8884/mqtt",
            "wss://test.mosquitto.org:8081/mqtt"
    };

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String clientId = "xc" + Long.toHexString(new Random().nextLong() & 0xFFFFFFFFFFFFL);
    private final Map<String, Boolean> members = new HashMap<>();
    private final Map<String, String> memberNames = new HashMap<>();

    private MqttMiniClient client;
    private int brokerIdx;
    private String roomCode;
    private String myName = "";
    private boolean host;
    private boolean active;
    private String password = "";

    public WatchSession(Listener listener) {
        this.listener = listener;
    }

    public boolean isHost() {
        return host;
    }

    public boolean isActive() {
        return active;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void createRoom(String name, String password) {
        this.host = true;
        this.myName = name == null ? "" : name;
        this.password = password == null ? "" : password;
        this.roomCode = String.valueOf(100000 + new Random().nextInt(900000));
        connect();
    }

    public void joinRoom(String code, String name, String password) {
        this.host = false;
        this.myName = name == null ? "" : name;
        this.password = password == null ? "" : password;
        this.roomCode = code;
        connect();
    }

    public void leave() {
        if (!active) return;
        send("bye", null);
        publishPresence(false);
        active = false;
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    // ---- 房主发出 ----

    public void hostPlay(String url, String title, long posMs, java.util.Map<String, String> headers) {
        JsonObject o = new JsonObject();
        o.addProperty("url", url);
        o.addProperty("title", title);
        o.addProperty("pos", posMs);
        if (headers != null && !headers.isEmpty()) {
            JsonObject h = new JsonObject();
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) h.addProperty(e.getKey(), e.getValue());
            }
            o.add("headers", h);
        }
        send("play", o);
        publishState(url, title, posMs, true, headers);
    }

    public void hostPause(long posMs) {
        JsonObject o = new JsonObject();
        o.addProperty("pos", posMs);
        send("pause", o);
    }

    public void hostResume(long posMs) {
        JsonObject o = new JsonObject();
        o.addProperty("pos", posMs);
        send("resume", o);
    }

    public void hostSeek(long posMs) {
        JsonObject o = new JsonObject();
        o.addProperty("pos", posMs);
        send("seek", o);
    }

    public void hostSync(long posMs, boolean playing) {
        JsonObject o = new JsonObject();
        o.addProperty("pos", posMs);
        o.addProperty("playing", playing);
        o.addProperty("ts", System.currentTimeMillis());
        send("sync", o);
    }

    public String getClientId() {
        return clientId;
    }

    public String getMyName() {
        return myName;
    }

    /** 成员快照（房间页头像条用）：每项含 id/name/host。 */
    public java.util.List<java.util.Map<String, Object>> getMembers() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        synchronized (members) {
            for (java.util.Map.Entry<String, Boolean> e : members.entrySet()) {
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("id", e.getKey());
                String n = memberNames.get(e.getKey());
                m.put("name", n == null || n.isEmpty() ? "伙伴" : n);
                m.put("host", e.getValue());
                out.add(m);
            }
        }
        out.sort((a, b) -> Boolean.compare(Boolean.TRUE.equals(b.get("host")), Boolean.TRUE.equals(a.get("host"))));
        return out;
    }

    public java.util.List<String> getMemberIds() {
        synchronized (members) {
            return new java.util.ArrayList<>(members.keySet());
        }
    }

    /** 实时语音信令：kind=offer/answer/ice/bye，to 为目标成员（空=广播） */
    public void sendVoice(String kind, String to, com.google.gson.JsonObject data) {
        JsonObject o = data == null ? new JsonObject() : data;
        o.addProperty("kind", kind);
        if (to != null && !to.isEmpty()) o.addProperty("to", to);
        send("voice", o);
    }

    public void sendSingStart() {
        send("sing_start", new JsonObject());
    }

    public void sendSingStop() {
        send("sing_stop", new JsonObject());
    }

    public void sendScore(int score, String grade) {
        JsonObject o = new JsonObject();
        o.addProperty("score", score);
        o.addProperty("grade", grade == null ? "" : grade);
        send("score", o);
    }

    public void sendChat(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("name", myName);
        o.addProperty("text", text);
        send("chat", o);
    }

    // ---- 内部 ----

    private String topicMsg() {
        return "xc/room/" + roomCode + "/msg";
    }

    private String topicPresence() {
        return "xc/room/" + roomCode + "/p/" + clientId;
    }

    private String topicState() {
        return "xc/room/" + roomCode + "/state";
    }

    private void connect() {
        active = true;
        brokerIdx = 0;
        openBroker();
    }

    private void openBroker() {
        JsonObject will = new JsonObject();
        will.addProperty("name", myName);
        will.addProperty("host", host);
        will.addProperty("on", false);
        client = new MqttMiniClient(BROKERS[brokerIdx], clientId, topicPresence(),
                will.toString().getBytes(StandardCharsets.UTF_8), new MqttMiniClient.Listener() {
            @Override
            public void onOpen() {
                client.subscribe(topicMsg());
                client.subscribe("xc/room/" + roomCode + "/p/+");
                client.subscribe(topicState());
                publishPresence(true);
                send("join", null);
                if (!host) send("needstate", null);
                main.post(() -> listener.onConnected(host, roomCode));
            }

            @Override
            public void onMessage(String topic, byte[] payload, boolean retained) {
                handle(topic, payload);
            }

            @Override
            public void onClosed(String reason) {
                onBrokerDown(reason);
            }

            @Override
            public void onFailure(String error) {
                onBrokerDown(error);
            }
        });
        client.connect();
    }

    private void onBrokerDown(String reason) {
        if (!active) return;
        if (brokerIdx + 1 < BROKERS.length) {
            brokerIdx++;
            openBroker();
        } else {
            active = false;
            main.post(() -> listener.onDisconnected(reason));
        }
    }

    private void publishPresence(boolean on) {
        if (client == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("name", myName);
        o.addProperty("host", host);
        o.addProperty("on", on);
        client.publish(topicPresence(), o.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private void publishState(String url, String title, long posMs, boolean playing, java.util.Map<String, String> headers) {
        if (client == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("url", url);
        o.addProperty("title", title);
        o.addProperty("pos", posMs);
        o.addProperty("playing", playing);
        o.addProperty("pwd", password);
        if (headers != null && !headers.isEmpty()) {
            JsonObject h = new JsonObject();
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) h.addProperty(e.getKey(), e.getValue());
            }
            o.add("headers", h);
        }
        client.publish(topicState(), o.toString().getBytes(StandardCharsets.UTF_8), true);
    }

    private static java.util.Map<String, String> headersOf(JsonObject o) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (o.has("headers") && o.get("headers").isJsonObject()) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.getAsJsonObject("headers").entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return out;
    }

    private void send(String type, JsonObject extra) {
        if (client == null) return;
        JsonObject o = extra == null ? new JsonObject() : extra;
        o.addProperty("t", type);
        o.addProperty("from", clientId);
        o.addProperty("name", myName);
        client.publish(topicMsg(), o.toString().getBytes(StandardCharsets.UTF_8), false);
    }

    private void handle(String topic, byte[] payload) {
        try {
            String s = new String(payload, StandardCharsets.UTF_8);
            if (topic.contains("/p/")) {
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                String cid = topic.substring(topic.lastIndexOf('/') + 1);
                boolean on = o.has("on") && o.get("on").getAsBoolean();
                synchronized (members) {
                    if (on) {
                        members.put(cid, o.has("host") && o.get("host").getAsBoolean());
                        if (o.has("name")) memberNames.put(cid, o.get("name").getAsString());
                    } else {
                        members.remove(cid);
                        memberNames.remove(cid);
                    }
                }
                int count;
                boolean hostPresent;
                synchronized (members) {
                    count = members.size();
                    hostPresent = members.containsValue(Boolean.TRUE);
                }
                main.post(() -> listener.onMembers(count));
                if (!host && !hostPresent && count > 0) main.post(listener::onHostGone);
                return;
            }
            if (topic.endsWith("/state")) {
                if (host) return;
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();
                String pwd = o.has("pwd") ? o.get("pwd").getAsString() : "";
                if (!pwd.isEmpty() && !pwd.equals(password)) return;
                String url = o.has("url") ? o.get("url").getAsString() : "";
                String title = o.has("title") ? o.get("title").getAsString() : "";
                long pos = o.has("pos") ? o.get("pos").getAsLong() : 0;
                if (!url.isEmpty()) main.post(() -> listener.onPlay(url, title, pos, headersOf(o)));
                return;
            }
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            String type = o.has("t") ? o.get("t").getAsString() : "";
            String from = o.has("from") ? o.get("from").getAsString() : "";
            if (from.equals(clientId)) return; // 自己发的回声
            long pos = o.has("pos") ? o.get("pos").getAsLong() : 0;
            switch (type) {
                case "play": {
                    String url = o.has("url") ? o.get("url").getAsString() : "";
                    String title = o.has("title") ? o.get("title").getAsString() : "";
                    main.post(() -> listener.onPlay(url, title, pos, headersOf(o)));
                    break;
                }
                case "pause":
                    main.post(listener::onPause);
                    break;
                case "resume":
                    main.post(() -> listener.onResume(pos));
                    break;
                case "seek":
                    main.post(() -> listener.onSeek(pos));
                    break;
                case "sync": {
                    boolean playing = o.has("playing") && o.get("playing").getAsBoolean();
                    main.post(() -> listener.onSync(pos, playing));
                    break;
                }
                case "chat": {
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    String text = o.has("text") ? o.get("text").getAsString() : "";
                    main.post(() -> listener.onChat(name, text));
                    break;
                }
                case "join": {
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    main.post(() -> listener.onPeerJoin(name));
                    break;
                }
                case "sing_start": {
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    main.post(() -> listener.onSingStart(name));
                    break;
                }
                case "sing_stop": {
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    main.post(() -> listener.onSingStop(name));
                    break;
                }
                case "score": {
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    int score = o.has("score") ? o.get("score").getAsInt() : 0;
                    String grade = o.has("grade") ? o.get("grade").getAsString() : "";
                    main.post(() -> listener.onScore(name, score, grade));
                    break;
                }
                case "voice": {
                    String to = o.has("to") ? o.get("to").getAsString() : "";
                    if (!to.isEmpty() && !to.equals(clientId)) break;
                    String kind = o.has("kind") ? o.get("kind").getAsString() : "";
                    String name = o.has("name") ? o.get("name").getAsString() : "";
                    main.post(() -> listener.onVoiceSignal(kind, from, name, o));
                    break;
                }
                case "needstate":
                    // 客人要状态：房主下一拍 sync 自然带到，这里不用回
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }
}
