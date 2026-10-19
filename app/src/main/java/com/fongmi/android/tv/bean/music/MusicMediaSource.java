package com.fongmi.android.tv.bean.music;

import java.util.HashMap;
import java.util.Map;

/** 可播地址：FreeJS getMediaSource 返回。 */
public class MusicMediaSource {

    public String url;
    /** 播放该地址需要的请求头（如 referer/ua），可空 */
    public Map<String, String> headers = new HashMap<>();

    public MusicMediaSource() {
    }

    public MusicMediaSource(String url) {
        this.url = url;
    }
}
