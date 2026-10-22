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
     * 随机历史栈（R6）：按对象引用存已播过的歌，上一首按栈回退、不是再随机。
     * 换队／清队／切模式时清掉，旧队历史不许串进新队（R4 队列隔离）。
     * 存引用不用序号：删／移后序号会变，引用按 == 找回当前序号，找不到（已删）就继续往栈下翻。
     */
    private final java.util.ArrayDeque<MusicTrack> shuffleHistory = new java.util.ArrayDeque<>();
    /**
     * D12 固定随机顺序：切随机时按当前歌重排（当前歌在首位），按此顺序播完一轮才重排。
     * 存原队列序号，不存曲目引用：删／插后序号按下面 adjust 规则跟进，失效时 ensure 重建。
     * shufflePos 不单独维护：next／prev 每次按当前序号在 order 里现找，避免 moveTo 后游标与位置脱节。
     */
    private final List<Integer> shuffleOrder = new ArrayList<>();

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
    /**
     * 队列移除契约（另一工并行调用口径，方法名定死）：按 key 删，不按序号、不按 id。
     * key＝platform|id；id 为空时退化为 platform|title|artist（与自定义封面键同一口径）。
     * 删的是当前歌时沿用 removeAt 的落点口径（非末首顶上下一首、末首播新的队尾见 removeAt）。
     * 返回删掉的序号，找不到返回 -1。
     */
    public static String trackKey(MusicTrack t) {
        if (t == null) return "|";
        String platform = t.platform == null ? "" : t.platform;
        if (t.id != null && !t.id.isEmpty()) return platform + "|" + t.id;
        String title = t.title == null ? "" : t.title;
        String artist = t.artist == null ? "" : t.artist;
        return platform + "|" + title + "|" + artist;
    }

    public synchronized int removeByKey(String key) {
        if (key == null) return -1;
        for (int i = 0; i < tracks.size(); i++) {
            if (key.equals(trackKey(tracks.get(i)))) {
                removeAt(i);
                return i;
            }
        }
        return -1;
    }

    /** 随机历史入栈（服务在起播成功时调：离场的那首才是“上一首”；同首重播不入栈） */
    public synchronized void pushShuffleHistory(MusicTrack track) {
        if (track == null) return;
        if (shuffleHistory.size() >= 100) shuffleHistory.removeFirst();
        shuffleHistory.addLast(track);
    }

    /**
     * 随机上一首：弹栈找回当前序号。栈顶已不在队里（被删）时继续往下翻，
     * 翻空返回 -1（调用方回落旧随机口径，不许把删掉的歌播回来）。
     */
    public synchronized int popShuffleHistoryIndex() {
        while (!shuffleHistory.isEmpty()) {
            MusicTrack t = shuffleHistory.removeLast();
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i) == t) return i;
            }
        }
        return -1;
    }

    public synchronized void setTracks(List<MusicTrack> list, int startIndex,
                                       String source, String route, java.util.Map<String, String> routeParams) {
        shuffleHistory.clear();
        shuffleOrder.clear();
        tracks.clear();
        if (list != null) tracks.addAll(list);
        index = tracks.isEmpty() ? -1 : Math.max(0, Math.min(startIndex, tracks.size() - 1));
        this.source = (source == null || source.isEmpty()) ? null : source;
        this.route = (route == null || route.isEmpty()) ? null : route;
        this.routeParams = routeParams == null ? null : new java.util.HashMap<>(routeParams);
        // 换队即新一轮：随机模式下直接按新队当前歌重排（setMode 同值时不重建，靠这里补），
        // 非随机模式下顺序保持清空（ensure 只在随机模式生效）。
        if (mode == MODE_SHUFFLE) rebuildShuffleOrder();
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
        boolean changed = this.mode != mode;
        if (changed) shuffleHistory.clear();
        this.mode = mode;
        if (mode == MODE_SHUFFLE) {
            if (changed) rebuildShuffleOrder();
        } else {
            shuffleOrder.clear();
        }
    }

    /** D12：切随机时的重排——全部序号洗牌后把当前歌移到首位（本轮从当前歌起播，不立即重播它）。 */
    private void rebuildShuffleOrder() {
        shuffleOrder.clear();
        for (int i = 0; i < tracks.size(); i++) shuffleOrder.add(i);
        Collections.shuffle(shuffleOrder, random);
        if (index >= 0 && index < tracks.size()) {
            shuffleOrder.remove(Integer.valueOf(index));
            shuffleOrder.add(0, index);
        }
    }

    /** 随机顺序有效性：大小与队长一致、序号全在队内且不重复、当前歌在内；失效才重建（中途重建会丢本轮进度）。 */
    private void ensureShuffleOrder() {
        if (mode != MODE_SHUFFLE) return;
        boolean ok = shuffleOrder.size() == tracks.size() && index >= 0 && index < tracks.size();
        if (ok) {
            boolean[] seen = new boolean[tracks.size()];
            for (int v : shuffleOrder) {
                if (v < 0 || v >= tracks.size() || seen[v]) {
                    ok = false;
                    break;
                }
                seen[v] = true;
            }
            if (ok && !shuffleOrder.contains(index)) ok = false;
        }
        if (!ok) rebuildShuffleOrder();
    }

    /** 随机顺序快照（序号列表，供调试／后波面板按随机顺序展示时用；返回副本）。 */
    public synchronized List<Integer> getShuffleOrder() {
        return new ArrayList<>(shuffleOrder);
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
                ensureShuffleOrder();
                int pos = shuffleOrder.indexOf(index);
                if (pos >= 0 && pos + 1 < shuffleOrder.size()) return shuffleOrder.get(pos + 1);
                // 播完一轮：按当前歌重排，新一轮的下一首是重排后的第 2 位（首位是刚播完的本首，不立即重播）。
                rebuildShuffleOrder();
                return shuffleOrder.size() > 1 ? shuffleOrder.get(1) : index;
            default:
                int n = index + 1;
                if (n < tracks.size()) return n;
                return mode == MODE_REPEAT_ALL ? 0 : -1;
        }
    }

    public synchronized int prevIndex() {
        if (tracks.isEmpty() || index < 0) return -1;
        // Q2：单曲循环下上一首与下一首同口径＝停本首，不许用上一首丢掉循环的那首
        if (mode == MODE_REPEAT_ONE) return index;
        if (mode == MODE_SHUFFLE && tracks.size() > 1) {
            // D12：上一首先走服务侧历史栈（见服务 prev()）；栈空回落到这里时按固定顺序回退，
            // 不再随机。已在本轮首位时无上一首，返回本首（不跳到上一轮尾，避免历史栈外的乱跳）。
            ensureShuffleOrder();
            int p = shuffleOrder.indexOf(index);
            return p > 0 ? shuffleOrder.get(p - 1) : index;
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

    /**
     * 删序号（D13，照瓜子 removeFromQueue 口径）：删的是前面，序号前移；
     * 删的是当前播，非末首由后面顶上，队尾删当前歌播新的队尾（不再置 -1 停播），
     * 队删空才置 -1（服务侧见 current()==null 即停播＋清断点）；
     * 随机模式删当前歌播删前预先算好的洗牌下一首（按对象同一性定位，不按 id，
     * 队里同首歌出现两次时不许错位到另一首）。
     */
    public synchronized void removeAt(int i) {
        if (i < 0 || i >= tracks.size()) return;
        boolean wasCurrent = (i == index);
        MusicTrack shuffleNext = null;
        if (wasCurrent && mode == MODE_SHUFFLE && tracks.size() > 1) {
            int ni = nextIndex();
            if (ni >= 0 && ni < tracks.size()) shuffleNext = tracks.get(ni);
        }
        // D12：随机顺序跟进删位——删掉该序号、其后序号前移；顺序本身失效时 ensure 会重建。
        if (mode == MODE_SHUFFLE && shuffleOrder.size() == tracks.size()) {
            shuffleOrder.remove(Integer.valueOf(i));
            for (int k = 0; k < shuffleOrder.size(); k++) {
                if (shuffleOrder.get(k) > i) shuffleOrder.set(k, shuffleOrder.get(k) - 1);
            }
        }
        tracks.remove(i);
        if (tracks.isEmpty()) {
            index = -1;
        } else if (i < index) {
            index--;
        } else if (wasCurrent) {
            if (shuffleNext != null) {
                int p = -1;
                for (int k = 0; k < tracks.size(); k++) {
                    if (tracks.get(k) == shuffleNext) {
                        p = k;
                        break;
                    }
                }
                index = p >= 0 ? p : Math.min(i, tracks.size() - 1);
            } else if (index >= tracks.size()) {
                index = tracks.size() - 1;
            }
        }
    }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= tracks.size() || to < 0 || to >= tracks.size() || from == to) return;
        if (mode == MODE_SHUFFLE) {
            // D12：随机模式拖动只动 shuffleOrder，不动原队列。from／to 仍是面板拖动口径
            // （原队列序号，队列面板按原队列展示）：把被拖曲目的随机位置移到目标曲目的随机位置，
            // 原队列顺序与当前序号一律不动；顺序失效时先重建再移。
            ensureShuffleOrder();
            int fromPos = shuffleOrder.indexOf(from);
            int toPos = shuffleOrder.indexOf(to);
            if (fromPos < 0 || toPos < 0) return;
            Integer v = shuffleOrder.remove(fromPos);
            shuffleOrder.add(toPos, v);
            return;
        }
        MusicTrack cur = current();
        MusicTrack t = tracks.remove(from);
        tracks.add(to, t);
        // Q8：不许用 indexOf（MusicTrack.equals 只比 id，同 ID 重复歌会跳到第一个）；
        // 当前歌按对象引用 == 找回，找不到才钳住序号。
        if (cur != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i) == cur) {
                    index = i;
                    return;
                }
            }
            if (index >= tracks.size()) index = tracks.size() - 1;
        }
    }

    /** 插到下一首播 */
    public synchronized void insertNext(MusicTrack track) {
        if (track == null) return;
        int at = index + 1;
        if (at < 0) at = 0;
        if (at > tracks.size()) at = tracks.size();
        // D12：随机顺序跟进插位——其后序号后移，新歌挂到随机顺序末尾（不插到本轮中间打断已定顺序）。
        boolean shuffleValid = mode == MODE_SHUFFLE && shuffleOrder.size() == tracks.size();
        tracks.add(at, track);
        if (shuffleValid) {
            for (int k = 0; k < shuffleOrder.size(); k++) {
                if (shuffleOrder.get(k) >= at) shuffleOrder.set(k, shuffleOrder.get(k) + 1);
            }
            shuffleOrder.add(at);
        }
        if (index < 0) index = 0;
    }

    /** 加到队尾 */
    public synchronized void append(MusicTrack track) {
        if (track == null) return;
        boolean shuffleValid = mode == MODE_SHUFFLE && shuffleOrder.size() == tracks.size();
        tracks.add(track);
        if (shuffleValid) shuffleOrder.add(tracks.size() - 1);
        if (index < 0) index = 0;
    }

    public synchronized void clear() {
        shuffleHistory.clear();
        shuffleOrder.clear();
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
