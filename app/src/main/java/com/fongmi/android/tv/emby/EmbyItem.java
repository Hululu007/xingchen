package com.fongmi.android.tv.emby;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Emby 条目模型：电影 / 剧集 / 季 / 单集 / 人员。字段覆盖浏览、详情、播放所需。
 */
public class EmbyItem {

    public static final String TYPE_MOVIE = "Movie";
    public static final String TYPE_SERIES = "Series";
    public static final String TYPE_SEASON = "Season";
    public static final String TYPE_EPISODE = "Episode";
    public static final String TYPE_FOLDER = "CollectionFolder";
    public static final String TYPE_PERSON = "Person";

    public String id = "";
    public String name = "";
    public String type = "";
    public String overview = "";
    public String imageTag = "";
    public String backdropTag = "";
    public long runTimeTicks = 0;
    public long positionTicks = 0;
    public boolean played = false;
    public boolean favorite = false;
    public int indexNumber = 0;
    public int productionYear = 0;
    public double communityRating = 0;
    public double criticRating = 0;
    public String parentId = "";
    public String seriesId = "";
    public String seasonId = "";
    public final List<String> genres = new ArrayList<>();
    public final List<Person> people = new ArrayList<>();

    /** 演职员。 */
    public static class Person {
        public String name = "";
        public String role = "";
        public String imageTag = "";

        public static Person fromJson(JSONObject o) {
            Person p = new Person();
            if (o == null) return p;
            p.name = o.optString("Name", "");
            p.role = o.optString("Role", "");
            String tag = o.optString("PrimaryImageTag", "");
            if (tag.isEmpty()) {
                JSONObject tags = o.optJSONObject("ImageTags");
                if (tags != null) tag = tags.optString("Primary", "");
            }
            p.imageTag = tag;
            return p;
        }
    }

    public boolean isPlayable() {
        return TYPE_MOVIE.equals(type) || TYPE_EPISODE.equals(type);
    }

    public boolean isSeries() {
        return TYPE_SERIES.equals(type);
    }

    public static EmbyItem fromJson(JSONObject o) {
        EmbyItem it = new EmbyItem();
        if (o == null) return it;
        it.id = o.optString("Id", "");
        it.name = o.optString("Name", "");
        it.type = o.optString("Type", "");
        it.overview = o.optString("Overview", "");
        it.runTimeTicks = o.optLong("RunTimeTicks", 0);
        it.indexNumber = o.optInt("IndexNumber", 0);
        it.productionYear = o.optInt("ProductionYear", 0);
        it.communityRating = o.optDouble("CommunityRating", 0);
        it.criticRating = o.optDouble("CriticRating", 0);
        it.parentId = o.optString("ParentId", "");
        it.seriesId = o.optString("SeriesId", "");
        it.seasonId = o.optString("SeasonId", "");
        JSONObject tags = o.optJSONObject("ImageTags");
        if (tags != null) it.imageTag = tags.optString("Primary", "");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        if (backdrops != null && backdrops.length() > 0) it.backdropTag = backdrops.optString(0, "");
        JSONArray gs = o.optJSONArray("Genres");
        if (gs != null) for (int i = 0; i < gs.length(); i++) {
            String g = gs.optString(i, "");
            if (!g.isEmpty()) it.genres.add(g);
        }
        JSONArray ps = o.optJSONArray("People");
        if (ps != null) for (int i = 0; i < ps.length(); i++) {
            Person p = Person.fromJson(ps.optJSONObject(i));
            if (!p.name.isEmpty()) it.people.add(p);
        }
        JSONObject ud = o.optJSONObject("UserData");
        if (ud != null) {
            it.positionTicks = ud.optLong("PlaybackPositionTicks", 0);
            it.played = ud.optBoolean("Played", false);
            it.favorite = ud.optBoolean("IsFavorite", false);
        }
        return it;
    }

    public static List<EmbyItem> listFrom(JSONObject root) {
        List<EmbyItem> list = new ArrayList<>();
        if (root == null) return list;
        JSONArray arr = root.optJSONArray("Items");
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            EmbyItem it = fromJson(arr.optJSONObject(i));
            if (!it.id.isEmpty()) list.add(it);
        }
        return list;
    }

    /** 详情显示用：分钟数 */
    public String durationText() {
        if (runTimeTicks <= 0) return "";
        long min = runTimeTicks / 600_000_000L;
        return min + " 分钟";
    }

    /** 续播点：毫秒 */
    public long resumeMs() {
        return positionTicks / 10_000L;
    }

    /** 评分显示：9.2 分 */
    public String ratingText() {
        if (communityRating > 0) return String.format("%.1f 分", communityRating);
        if (criticRating > 0) return String.format("%.0f 分", criticRating);
        return "";
    }

    /** 演员名串（详情页用） */
    public String actorText() {
        StringBuilder sb = new StringBuilder();
        for (Person p : people) {
            if (p.role.contains("导") || p.role.contains("Direct")) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(p.name);
            if (sb.length() > 120) break;
        }
        return sb.toString();
    }

    /** 导演名串（详情页用） */
    public String directorText() {
        StringBuilder sb = new StringBuilder();
        for (Person p : people) {
            if (!(p.role.contains("导") || p.role.contains("Direct"))) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(p.name);
        }
        return sb.toString();
    }
}
