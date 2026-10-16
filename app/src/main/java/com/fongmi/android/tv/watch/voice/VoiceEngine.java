package com.fongmi.android.tv.watch.voice;

import android.content.Context;

import com.google.gson.JsonObject;

/**
 * 实时语音引擎窄接口：实现不在安装包里，装在「用时下载」的语音组件中
 * （WebRTC 太大，进包不划算，他定的打包原则）。组件由 VoiceComponent
 * 下载校验后用 DexClassLoader 加载，实现类名固定 VoiceEngineImpl，
 * 接口在主包（父加载器）保证两边是同一个类。
 */
public interface VoiceEngine {

    /** 信令出口：由房间通道（WatchSession.sendVoice）承接。 */
    interface SignalSink {
        void send(String kind, String to, JsonObject data);
    }

    /** 状态回调：state 取值 connecting/connected/disconnected/failed。 */
    interface Events {
        void onPeerState(String peerId, String state);

        void onError(String message);
    }

    void init(Context context, String nativeLibPath, SignalSink sink, Events events);

    void setLocalId(String id);

    void addPeer(String peerId);

    void removePeer(String peerId);

    void onSignal(String kind, String fromId, JsonObject data);

    void setMuted(boolean muted);

    boolean isMuted();

    void release();
}
