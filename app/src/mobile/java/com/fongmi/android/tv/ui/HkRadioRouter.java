package com.fongmi.android.tv.ui;

import android.app.Activity;

import com.fongmi.android.tv.legado.LegadoAudio;
import com.fongmi.android.tv.ui.activity.AudioBookActivity;

/**
 * 海阔电台路由：跟音乐路由一样是「调用」别的播放器——电台归听书播放器。
 * 识别口径：规则名含 电台/广播 且不含 音乐/听歌/歌曲（唱片电台那类歌单
 * 仍归音乐播放器）。命中后把选集整表建成听书专辑（台名＝集名），
 * 章节地址在听书页内走海阔解析（AudioBookActivity 的 hkRule 分支）。
 * 此前电台规则被音乐关键词（PyMusicSource 的识别表含「电台」）吞进
 * 音乐播放器、或落到视频播放器，听书从没被调用（他 2026-10-28 点名）。
 */
public class HkRadioRouter {

    private HkRadioRouter() {
    }

    public static boolean looksRadio(String ruleTitle) {
        if (ruleTitle == null) return false;
        String n = ruleTitle.toLowerCase();
        if (n.contains("音乐") || n.contains("听歌") || n.contains("歌曲")) return false;
        return n.contains("电台") || n.contains("广播") || n.contains("radio");
    }

    /**
     * 海阔直接播放点电台条目：整表进听书专辑、从被点那台起播，返回 true；
     * 不是电台规则返回 false，调用方继续走音乐/视频链。
     * episodesJson 两种形态与 MusicVodRouter.hkQueueFromJson 同口径
     * （扁平 [{name,url,pic}] / {flags:[{name,episodes:[...]}]}）。
     */
    public static boolean routeHk(Activity activity, String ruleTitle, String line,
                                  String episodeUrl, String episodeName,
                                  String name, String pic,
                                  String episodesJson, int selIdx) {
        try {
            if (activity == null || !looksRadio(ruleTitle)) return false;
            if (episodeUrl == null || episodeUrl.isEmpty()) return false;
            LegadoAudio.AudioAlbum album = new LegadoAudio.AudioAlbum();
            album.legadoKey = "hk:" + ruleTitle;
            album.hkRule = ruleTitle;
            album.hkLine = line == null ? "" : line;
            album.sourceName = ruleTitle;
            album.title = name == null || name.isEmpty() ? ruleTitle : name;
            album.coverUrl = pic == null ? "" : pic;
            fillChapters(album, line, episodesJson);
            if (album.chapters.isEmpty()) {
                album.chapters.add(new LegadoAudio.Chapter(
                        episodeName == null || episodeName.isEmpty()
                                ? album.title : episodeName, episodeUrl));
            }
            int start = 0;
            boolean found = false;
            for (int i = 0; i < album.chapters.size(); i++) {
                if (episodeUrl.equals(album.chapters.get(i).url)) {
                    start = i;
                    found = true;
                    break;
                }
            }
            if (!found) start = Math.max(0, Math.min(selIdx, album.chapters.size() - 1));
            album.index = start;
            AudioBookActivity.start(activity, LegadoAudio.put(album));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 海阔详情整表：收藏/历史走 startHk 打开视频详情页时，电台规则在详情
     * 数据到手后改道听书（与 MusicVodRouter.routeHkDetail 同位、优先于它）。
     * 取第一条非空线路的选集建专辑，从第一台起播。
     */
    public static boolean routeHkDetail(Activity activity, String ruleTitle,
                                        com.fongmi.android.tv.bean.Vod vod) {
        try {
            if (activity == null || !looksRadio(ruleTitle)) return false;
            if (vod == null || vod.getFlags() == null) return false;
            LegadoAudio.AudioAlbum album = new LegadoAudio.AudioAlbum();
            album.legadoKey = "hk:" + ruleTitle;
            album.hkRule = ruleTitle;
            album.sourceName = ruleTitle;
            album.title = vod.getName() == null || vod.getName().isEmpty()
                    ? ruleTitle : vod.getName();
            album.coverUrl = vod.getPic() == null ? "" : vod.getPic();
            for (com.fongmi.android.tv.bean.Flag flag : vod.getFlags()) {
                if (flag == null || flag.getEpisodes() == null
                        || flag.getEpisodes().isEmpty()) continue;
                album.hkLine = flag.getFlag() == null ? "" : flag.getFlag();
                for (com.fongmi.android.tv.bean.Episode ep : flag.getEpisodes()) {
                    if (ep == null || ep.getUrl() == null || ep.getUrl().isEmpty()) continue;
                    album.chapters.add(new LegadoAudio.Chapter(
                            ep.getName() == null ? "" : ep.getName(), ep.getUrl()));
                }
                if (!album.chapters.isEmpty()) break;
            }
            if (album.chapters.isEmpty()) return false;
            album.index = 0;
            AudioBookActivity.start(activity, LegadoAudio.put(album));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fillChapters(LegadoAudio.AudioAlbum album, String line,
                                     String episodesJson) {        if (episodesJson == null || episodesJson.isEmpty()) return;
        try {
            String json = episodesJson.trim();
            if (json.startsWith("{")) {
                org.json.JSONObject root = new org.json.JSONObject(json);
                org.json.JSONArray flagArr = root.optJSONArray("flags");
                if (flagArr == null) return;
                org.json.JSONObject chosen = null;
                org.json.JSONObject first = null;
                for (int fi = 0; fi < flagArr.length(); fi++) {
                    org.json.JSONObject fo = flagArr.optJSONObject(fi);
                    if (fo == null) continue;
                    org.json.JSONArray eps = fo.optJSONArray("episodes");
                    if (eps == null || eps.length() == 0) continue;
                    if (first == null) first = fo;
                    if (line != null && line.equals(fo.optString("name", ""))) {
                        chosen = fo;
                        break;
                    }
                }
                if (chosen == null) chosen = first;
                if (chosen == null) return;
                if (album.hkLine.isEmpty()) {
                    album.hkLine = chosen.optString("name", "");
                }
                org.json.JSONArray eps = chosen.optJSONArray("episodes");
                for (int i = 0; i < eps.length(); i++) {
                    org.json.JSONObject o = eps.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    if (url.isEmpty()) continue;
                    album.chapters.add(new LegadoAudio.Chapter(
                            o.optString("name", ""), url));
                }
                return;
            }
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty()) continue;
                album.chapters.add(new LegadoAudio.Chapter(o.optString("name", ""), url));
            }
        } catch (Throwable ignored) {
        }
    }
}
