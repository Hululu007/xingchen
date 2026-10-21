package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放队列（纯 Java，不依赖安卓）：曲目列表、当前序号、播放模式。
 * 学 Auxio 的思路：队列逻辑与播放器解耦，服务只按序号取歌。
 */
public class MusicQueue {

    /** 顺序播放 */
    public static final int MODE_SEQUENTIAL = 0;
    /** 单曲循环 */
    public static final int MODE_REPEAT_ONE = 1;
    /** 全部循环 */
    public static final int MODE_REPEAT_ALL = 2;
    /** 随机播放 */
    public static final int MODE_SHUFFLE = 3;

    private final List<MusicTrack> tracks = new ArrayList<>();
    private final Random random = new Random();
    private int index = -1;
    private int mode = MODE_SEQUENTIAL;

    /**
     * 队列级来源（B7，入队时随队列写入）：展示用来源名，如榜单名、歌单标题、随心听。
     * 空＝未知来源（老断点恢复的队列无此信息，面板回落旧反查口径）。
     */
    private String source;
    /**
     * 队列级来源 route（B7，入队时随队列写入）：可跳转的来源页标识，如 home（首页）、
     * list（列表页，参数见 routeParams）。空＝无跳转落点，来源行只展示、不可点（与 GZ 无 route 形态一致）。
     */
    private String route;
    /** route 参数（B7，纯 Java Map，不依赖安卓 Bundle）：list 路由带 mode／keyword／sitePlatform／playlistId／title。 */
    private java.util.Map<String, String> routeParams;

    public synchronized void setTracks(List<MusicTrack> list, int startIndex) {
        setTracks(list, startIndex, null, null, null);
    }

    /** 入队时写来源／route：来源行读这里，不再开面板时反查（B7）。 */
    public synchronized void setTracks(List<MusicTrack> list, int startIndex,
                                       String source, String route, java.util.Map<String, String> routeParams) {
        tracks.clear();
        if (list != null) tracks.addAll(list);
        index = tracks.isEmpty() ? -1 : Math.max(0, Math.min(startIndex, tracks.size() - 1));
        this.source = (source == null || source.isEmpty()) ? null : source;
        this.route = (route == null || route.isEmpty()) ? null : route;
        this.routeParams = routeParams == null ? null : new java.util.HashMap<>(routeParams);
    }

    public synchronized String getSource() {
        return source;
    }

    public synchronized String getRoute() {
        return route;
    }

    public synchronized java.util.Map<String, String> getRouteParams() {
        return routeParams == null ? null : new java.util.HashMap<>(routeParams);
    }

    public synchronized List<MusicTrack> getTracks() {
        return new ArrayList<>(tracks);
    }

    public synchronized int size() {
        return tracks.size();
    }

    public synchronized boolean isEmpty() {
        return tracks.isEmpty();
    }

    public synchronized int getIndex() {
        return index;
    }

    public synchronized MusicTrack current() {
        return (index >= 0 && index < tracks.size()) ? tracks.get(index) : null;
    }

    public synchronized MusicTrack get(int i) {
        return (i >= 0 && i < tracks.size()) ? tracks.get(i) : null;
    }

    public synchronized int getMode() {
        return mode;
    }

    public synchronized void setMode(int mode) {
        this.mode = mode;
    }

    /** 是否还有下一首（顺序模式到尾则无） */
    public synchronized boolean hasNext() {
        if (tracks.isEmpty()) return false;
        if (mode == MODE_REPEAT_ONE || mode == MODE_REPEAT_ALL || mode == MODE_SHUFFLE) return true;
        return index + 1 < tracks.size();
    }

    /** 按模式算下一首序号（顺序到尾返回 -1） */
    public synchronized int nextIndex() {
        if (tracks.isEmpty() || index < 0) return -1;
        switch (mode) {
            case MODE_REPEAT_ONE:
                return index;
            case MODE_SHUFFLE:
                if (tracks.size() == 1) return index;
                int r;
                do {
                    r = random.nextInt(tracks.size());
                } while (r == index);
                return r;
            default:
                int n = index + 1;
                if (n < tracks.size()) return n;
                return mode == MODE_REPEAT_ALL ? 0 : -1;
        }
    }

    public synchronized int prevIndex() {
        if (tracks.isEmpty() || index < 0) return -1;
        if (mode == MODE_SHUFFLE && tracks.size() > 1) {
            int r;
            do {
                r = random.nextInt(tracks.size());
            } while (r == index);
            return r;
        }
        int p = index - 1;
        if (p >= 0) return p;
        return mode == MODE_REPEAT_ALL ? tracks.size() - 1 : index;
    }

    public synchronized boolean moveTo(int i) {
        if (i < 0 || i >= tracks.size()) return false;
        index = i;
        return true;
    }

    /** 删序号：删的是当前播，后面顶上；删的是前面，序号前移 */
    public synchronized void removeAt(int i) {
        if (i < 0 || i >= tracks.size()) return;
        tracks.remove(i);
        if (tracks.isEmpty()) {
            index = -1;
        } else if (i < index) {
            index--;
        } else if (i == index) {
            if (index >= tracks.size()) index = tracks.size() - 1;
        }
    }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size() || from == to) return;
        MusicTrack cur = current();
        MusicTrack t = tracks.remove(from);
        tracks.add(to, t);
        if (cur != null) index = tracks.indexOf(cur);
    }

    /** 插到下一首播 */
    public synchronized void insertNext(MusicTrack track) {
        if (track == null) return;
        int at = index + 1;
        if (at < 0) at = 0;
        if (at > tracks.size()) at = tracks.size();
        tracks.add(at, track);
        if (index < 0) index = 0;
    }

    /** 加到队尾 */
    public synchronized void append(MusicTrack track) {
        if (track == null) return;
        tracks.add(track);
        if (index < 0) index = 0;
    }

    public synchronized void clear() {
        tracks.clear();
        index = -1;
        source = null;
        route = null;
        routeParams = null;
    }

    public static String modeName(int mode) {
        switch (mode) {
            case MODE_REPEAT_ONE:
                return "单曲循环";
            case MODE_REPEAT_ALL:
                return "全部循环";
            case MODE_SHUFFLE:
                return "随机播放";
            default:
                return "顺序播放";
        }
    }
}
