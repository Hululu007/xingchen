package com.fongmi.android.tv.music;

import com.fongmi.android.tv.bean.music.MusicChart;
import com.fongmi.android.tv.bean.music.MusicSite;
import com.fongmi.android.tv.bean.music.MusicTrack;
import com.fongmi.android.tv.cloud.CloudDrive;
import com.fongmi.android.tv.cloud.CloudFile;
import com.fongmi.android.tv.cloud.drive115.Drive115;
import com.fongmi.android.tv.cloud.drivequark.DriveQuark;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网盘音乐源：只负责把网盘搜索页递来的曲目解析成播放直链。
 * 搜索本身由 MusicCloudActivity 直接调 Drive 列表筛音频做，不走这里的 search()。
 * 115 走本机代理（Drive115.getPlayUrl 自带 Proxy115），播放器不直连 115；
 * 夸克取下载直链直接播（与 CloudDriveActivity 视频路子一致）。
 * 歌词不在网盘侧出（lyric 返回空，走播放器现有取词回落）。
 */
public class CloudMusicSource implements MusicSource {

    public static final String PLATFORM = "pan";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    /** 幂等注册：网盘页打开时调一次即可；服务与页同进程，注册后 playUrl 才能按 platform 找到本源。 */
    public static void ensureRegistered() {
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                MusicRepository.register(new CloudMusicSource());
            } catch (Throwable ignored) {
                REGISTERED.set(false);
            }
        }
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public List<MusicSite> sites() {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> search(String keyword) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> tracks(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicChart> charts(MusicSite site) {
        return Collections.emptyList();
    }

    @Override
    public List<MusicTrack> chartTracks(MusicChart chart) {
        return Collections.emptyList();
    }

    @Override
    public String lyric(MusicTrack track) {
        return null;
    }

    @Override
    public String playUrl(MusicTrack track, int quality) throws Exception {
        if (track == null || track.rawJson == null) throw new Exception("网盘曲目信息缺失");
        JSONObject o = new JSONObject(track.rawJson);
        String driveId = o.optString("drive");
        CloudFile f = new CloudFile();
        f.setId(o.optString("fid"));
        f.setName(o.optString("name"));
        f.setPickCode(o.optString("pickCode"));
        CloudDrive drive;
        if (Drive115.ID.equals(driveId)) {
            drive = new Drive115();
        } else if (DriveQuark.ID.equals(driveId)) {
            drive = new DriveQuark();
        } else {
            throw new Exception("未知网盘来源");
        }
        String url = drive.getPlayUrl(f);
        if (url == null || url.isEmpty()) throw new Exception("取播放地址失败");
        return url;
    }
}
