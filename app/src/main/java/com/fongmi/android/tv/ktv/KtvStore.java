package com.fongmi.android.tv.ktv;

import com.github.catvod.utils.Prefers;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/** 点歌台的本地存档：队列、唱过的、收藏的、服务器地址、歌源模式。都走 Prefers，全是用户自己的数据。 */
public class KtvStore {

    private static final String K_QUEUE = "ktv_queue";
    private static final String K_HISTORY = "ktv_history";
    private static final String K_FAVORITE = "ktv_favorites";
    private static final String K_BASE_URL = "ktv_base_url";
    private static final String K_MODE = "ktv_source_mode"; // vod | server
    private static final Gson GSON = new Gson();

    private static List<KtvSong> read(String key) {
        try {
            String raw = Prefers.getString(key, "");
            if (raw == null || raw.isEmpty()) return new ArrayList<>();
            List<KtvSong> list = GSON.fromJson(raw, new TypeToken<List<KtvSong>>() {}.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static void write(String key, List<KtvSong> list) {
        try {
            Prefers.put(key, GSON.toJson(list));
        } catch (Throwable ignored) {
        }
    }

    // ---------- 队列 ----------
    public static List<KtvSong> queue() {
        return read(K_QUEUE);
    }

    public static void addToQueue(KtvSong song) {
        List<KtvSong> q = queue();
        q.add(song);
        write(K_QUEUE, q);
    }

    public static void insertNext(KtvSong song) {
        List<KtvSong> q = queue();
        q.add(q.isEmpty() ? 0 : 1, song);
        write(K_QUEUE, q);
    }

    public static void removeFromQueue(int index) {
        List<KtvSong> q = queue();
        if (index >= 0 && index < q.size()) {
            q.remove(index);
            write(K_QUEUE, q);
        }
    }

    public static void moveToTop(int index) {
        List<KtvSong> q = queue();
        if (index > 0 && index < q.size()) {
            KtvSong s = q.remove(index);
            q.add(0, s);
            write(K_QUEUE, q);
        }
    }

    public static KtvSong pollQueue() {
        List<KtvSong> q = queue();
        if (q.isEmpty()) return null;
        KtvSong s = q.remove(0);
        write(K_QUEUE, q);
        return s;
    }

    public static void clearQueue() {
        write(K_QUEUE, new ArrayList<>());
    }

    // ---------- 唱过的（常唱） ----------
    public static List<KtvSong> history() {
        return read(K_HISTORY);
    }

    public static void addHistory(KtvSong song) {
        List<KtvSong> h = history();
        h.removeIf(s -> s.identity().equals(song.identity()));
        h.add(0, song);
        while (h.size() > 100) h.remove(h.size() - 1);
        write(K_HISTORY, h);
    }

    // ---------- 收藏 ----------
    public static List<KtvSong> favorites() {
        return read(K_FAVORITE);
    }

    public static boolean isFavorite(KtvSong song) {
        for (KtvSong s : favorites()) if (s.identity().equals(song.identity())) return true;
        return false;
    }

    public static void toggleFavorite(KtvSong song) {
        List<KtvSong> f = favorites();
        if (isFavorite(song)) f.removeIf(s -> s.identity().equals(song.identity()));
        else f.add(0, song);
        write(K_FAVORITE, f);
    }

    // ---------- 设置 ----------
    public static String baseUrl() {
        String u = Prefers.getString(K_BASE_URL, "");
        return u == null ? "" : u.trim();
    }

    public static void setBaseUrl(String url) {
        Prefers.put(K_BASE_URL, url == null ? "" : url.trim());
    }

    public static boolean useServer() {
        return "server".equals(Prefers.getString(K_MODE, "vod"));
    }

    public static void setUseServer(boolean server) {
        Prefers.put(K_MODE, server ? "server" : "vod");
    }
}
