package com.fongmi.android.tv.api.music;

import com.fongmi.android.tv.bean.music.MusicSite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 预置补播种的纯决策（不碰存储：MusicSourceManager.listSites 喂数据、负责落盘）。
 * 病灶：预置只在存量为空（首装）时随 builtinSites() 整表播种，老用户存量非空，
 * 后加的预置（油管音乐）永远进不了他的列表。这里决定往存量尾部补哪几件：
 * 一、只补带「后加」标记（MusicSite.lateBuiltin）的预置——老预置在首播种年代就整表
 *     种下过，存量里缺了只可能是用户亲手删的，永不补；
 * 二、地址已在「已播种集合」里的不补——补播种过的预置被用户删掉后不许复活；
 * 三、存量里已有的不重复补——用户自己加的同地址源原样保留，一概不碰。
 * 本轮见过的所有预置地址都由 planAppends 记入集合（调用方在本轮后落盘），
 * 免得哪件老预置将来被误判成新预置。朋友版 builtinSites() 返回空表，
 * 本类输入恒空、输出恒空，不会种出任何预置。
 */
public final class BuiltinReseed {

    private BuiltinReseed() {
    }

    /** 已播种集合首次建立时先记入的地址：存量里已存在的预置（它们当年已被播种过）。 */
    public static Set<String> initialSeeded(List<MusicSite> stock, List<MusicSite> builtin) {
        Set<String> seeded = new HashSet<>();
        Set<String> builtinUrls = urlsOf(builtin);
        if (stock != null) {
            for (MusicSite s : stock) {
                if (s != null && s.srcUrl != null && builtinUrls.contains(s.srcUrl)) {
                    seeded.add(s.srcUrl);
                }
            }
        }
        return seeded;
    }

    /**
     * 本轮要追加到存量尾部的预置（保持内置表内的先后顺序）；同时把本轮见过的
     * 预置地址补进 seeded（方法会改动传入的集合，调用方在本轮后决定是否落盘）。
     */
    public static List<MusicSite> planAppends(List<MusicSite> stock, List<MusicSite> builtin, Set<String> seeded) {
        List<MusicSite> appends = new ArrayList<>();
        if (builtin == null || builtin.isEmpty()) return appends;
        Set<String> present = urlsOf(stock);
        for (MusicSite b : builtin) {
            if (b == null || b.srcUrl == null || b.srcUrl.isEmpty()) continue;
            boolean known = seeded.contains(b.srcUrl);
            seeded.add(b.srcUrl);
            if (!b.lateBuiltin || known) continue;
            if (present.add(b.srcUrl)) appends.add(b);
        }
        return appends;
    }

    /** 内置表全部地址（首装整表播种后记入集合用）。 */
    public static Set<String> allUrls(List<MusicSite> builtin) {
        return urlsOf(builtin);
    }

    private static Set<String> urlsOf(List<MusicSite> sites) {
        Set<String> urls = new HashSet<>();
        if (sites != null) {
            for (MusicSite s : sites) {
                if (s != null && s.srcUrl != null) urls.add(s.srcUrl);
            }
        }
        return urls;
    }
}
