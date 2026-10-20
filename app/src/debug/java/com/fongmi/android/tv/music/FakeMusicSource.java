package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * 假源（仅 debug 包）：内存假数据，让音乐 UI 全链路先跑通。
 * TODO:一期真源落地后删掉本文件＋MusicRepository.ensureDebugFake()。
 * 真机验证用的音频是公开测试音频（SoundHelix 示例 MP3）。
 * ============================================================
 */
public class FakeMusicSource implements MusicSource {

    private static final String P = "fake";

    private final List<MusicSite> sites = new ArrayList<>();
    private final List<MusicTrack> all = new ArrayList<>();

    public FakeMusicSource() {
        MusicSite a = new MusicSite(P, "假源·华语", "0.1", "debug://fake/a");
        a.connected = true;
        MusicSite b = new MusicSite(P, "假源·欧美", "0.1", "debug://fake/b");
        b.connected = true;
        sites.add(a);
        sites.add(b);
        // 华语
        add("f01", "晴天", "周杰伦", "叶惠美", 299000, "SQ", 1);
        add("f02", "雾港灯塔", "沈雾", "雾港", 252000, "SQ", 2);
        add("f03", "潮汐里的信", "陆遥", "潮汐", 228000, "HQ", 3);
        add("f04", "傍晚的站台", "沈雾", "站台", 301000, "SQ", 4);
        add("f05", "青苔路", "白榆", "青苔", 213000, "HQ", 5);
        add("f06", "纸飞机邮局", "陆遥", "邮局", 266000, "SQ", 6);
        // 欧美
        add("f07", "夜行慢车", "沈雾", "迷雾电台", 244000, "SQ", 7);
        add("f08", "Neon Harbor", "Mira Sol", "Neon", 231000, "HQ", 8);
        add("f09", "Paper Moon", "June Lake", "Lake", 258000, "SQ", 1);
        add("f10", "Slow Parade", "Mira Sol", "Parade", 279000, "HQ", 2);
        a.trackCount = 6;
        b.trackCount = 4;
    }

    private void add(String id, String title, String artist, String album, long duration, String tag, int helix) {
        MusicTrack t = new MusicTrack(id, title, artist, album, duration, "https://picsum.photos/seed/xcmusic" + id + "/300");
        t.platform = P;
        t.qualityTag = tag;
        all.add(t);
    }

    @Override
    public String platform() {
        return P;
    }

    @Override
    public List<MusicSite> sites() {
        return new ArrayList<>(sites);
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        List<MusicTrack> r = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        for (MusicTrack t : all) {
            if (kw.isEmpty()) continue;
            if ((t.title != null && t.title.toLowerCase().contains(kw))
                    || (t.artist != null && t.artist.toLowerCase().contains(kw))
                    || (t.album != null && t.album.toLowerCase().contains(kw))) {
                r.add(t);
            }
        }
        return r;
    }

    /** 按单个子源搜歌：先按 tracks(site) 的分段收窄范围，再按关键词过滤，不串另一个子源的歌 */
    @Override
    public List<MusicTrack> searchBySite(MusicSite site, String keyword) {
        List<MusicTrack> r = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) return r;
        for (MusicTrack t : tracks(site)) {
            if ((t.title != null && t.title.toLowerCase().contains(kw))
                    || (t.artist != null && t.artist.toLowerCase().contains(kw))
                    || (t.album != null && t.album.toLowerCase().contains(kw))) {
                r.add(t);
            }
        }
        return r;
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        List<MusicTrack> r = new ArrayList<>();
        boolean first = sites.indexOf(site) == 0;
        for (int i = 0; i < all.size(); i++) {
            if (first == (i < 6)) r.add(all.get(i));
        }
        return r;
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        List<MusicChart> r = new ArrayList<>();
        r.add(new MusicChart(P, "hot", "热歌榜"));
        r.add(new MusicChart(P, "new", "新歌榜"));
        return r;
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        List<MusicTrack> r = new ArrayList<>(all);
        if ("new".equals(chart.id)) {
            // 新歌榜：倒序
            List<MusicTrack> rev = new ArrayList<>();
            for (int i = r.size() - 1; i >= 0; i--) rev.add(r.get(i));
            return rev;
        }
        return r;
    }

    @Override
    public String playUrl(MusicTrack track, int quality) {
        // 四档音质假源都给同一条测试音频，真源按 quality 拼不同码率地址
        int n = 1 + Math.abs(track.id.hashCode()) % 8;
        return "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-" + n + ".mp3";
    }

    @Override
    public String lyric(MusicTrack track) {
        // 生成一段带时间戳的假歌词，让三行歌词滚动跑通
        StringBuilder sb = new StringBuilder();
        String[] lines = {"假歌词·调试行", "跟着节拍轻轻晃", "风把夜色吹得很长", "这句只是占位", "等真源接上换真词", "霓虹在远处闪烁", "心跳和鼓点重合", "下一句还没写好"};
        long t = 3000;
        for (int i = 0; i < lines.length; i++) {
            sb.append('[').append(fmt(t)).append(']').append(lines[i]).append('\n');
            t += 8000;
        }
        return sb.toString();
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d.%02d", s / 60, s % 60, (ms % 1000) / 10);
    }
}
