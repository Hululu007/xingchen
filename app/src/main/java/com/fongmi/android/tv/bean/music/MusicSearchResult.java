package com.fongmi.android.tv.bean.music;

import java.util.ArrayList;
import java.util.List;

/** 音乐搜索结果（分页由调用侧处理，照 FreeJS 协议）。 */
public class MusicSearchResult {

    private List<MusicTrack> tracks = new ArrayList<>();
    private boolean isEnd = true;
    private int page = 1;
    /** 哪类搜索：music / album / artist / sheet / lyric */
    private String type;

    public List<MusicTrack> getTracks() {
        return tracks;
    }

    public void setTracks(List<MusicTrack> tracks) {
        this.tracks = tracks == null ? new ArrayList<>() : tracks;
    }

    public boolean isEnd() {
        return isEnd;
    }

    public void setEnd(boolean end) {
        isEnd = end;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isEmpty() {
        return tracks == null || tracks.isEmpty();
    }
}
