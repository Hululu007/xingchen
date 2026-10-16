package com.fongmi.android.tv.watch;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.watch.voice.VoiceComponent;
import com.fongmi.android.tv.watch.voice.VoiceEngine;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * 实时合唱胶水层：把房间信令通道接到下发的语音引擎上。
 * 成员变化时对齐连接（新成员自动建连），离开房间整体释放。
 */
public final class VoiceRoomLink implements VoiceEngine.Events {

    public interface Status {
        void onStatus(String text);
    }

    private final WatchSession session;
    private final Status status;
    private final Set<String> knownPeers = new HashSet<>();
    private VoiceEngine engine;
    private boolean enabled;
    private boolean preparing;

    public VoiceRoomLink(WatchSession session, Status status) {
        this.session = session;
        this.status = status;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void enable() {
        if (enabled || preparing) return;
        preparing = true;
        status.onStatus("实时合唱准备中…");
        VoiceComponent.ensureReady(new VoiceComponent.Callback() {
            @Override
            public void onReady(VoiceEngine e, String soPath) {
                synchronized (VoiceRoomLink.this) {
                    preparing = false;
                    engine = e;
                    enabled = true;
                    engine.init(App.get(), soPath,
                            (kind, to, data) -> session.sendVoice(kind, to, data),
                            VoiceRoomLink.this);
                    engine.setLocalId(session.getClientId());
                    syncPeersLocked();
                }
                status.onStatus("实时合唱已开，可以边看边唱了");
            }

            @Override
            public void onStatus(String text) {
                status.onStatus(text);
            }

            @Override
            public void onError(String message) {
                synchronized (VoiceRoomLink.this) {
                    preparing = false;
                }
                status.onStatus(message);
            }
        });
    }

    public synchronized void disable() {
        if (engine != null && enabled) {
            try {
                session.sendVoice("bye", "", null);
                engine.release();
            } catch (Throwable ignored) {
            }
        }
        enabled = false;
        knownPeers.clear();
    }

    public synchronized void syncPeers() {
        if (engine == null || !enabled) return;
        syncPeersLocked();
    }

    private void syncPeersLocked() {
        String self = session.getClientId();
        Set<String> now = new HashSet<>(session.getMemberIds());
        now.remove(self);
        for (String id : now) {
            if (knownPeers.add(id)) engine.addPeer(id);
        }
        for (String id : new HashSet<>(knownPeers)) {
            if (!now.contains(id)) {
                knownPeers.remove(id);
                engine.removePeer(id);
            }
        }
    }

    public synchronized void onSignal(String kind, String fromId, JsonObject data) {
        if (engine != null && enabled) engine.onSignal(kind, fromId, data);
    }

    @Override
    public void onPeerState(String peerId, String state) {
        if ("failed".equals(state)) status.onStatus("有个伙伴的语音没连上（网络穿不透）");
        else if ("connected".equals(state)) status.onStatus("语音接通了");
    }

    @Override
    public void onError(String message) {
        status.onStatus(message);
    }
}
