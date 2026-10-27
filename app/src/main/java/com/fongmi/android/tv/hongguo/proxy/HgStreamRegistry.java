package com.fongmi.android.tv.hongguo.proxy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 红果解密流注册表：三阶段与本地服务器之间的唯一接口面。
 *
 * <p>调用时序（三阶段照此）：</p>
 * <ol>
 *   <li>拿到一集的播放信息后 {@link #register(HgStreamSpec)} → 得 token；</li>
 *   <li>本地播放 URL＝App 本地服务器地址＋{@link #playbackPath(String)}
 *       （服务器地址由 server 层 Server.getAddress(true) 提供，本类不依赖它）；</li>
 *   <li>播放页滑到该集之前先 {@link #warmAsync(String)} 预热（盒解析＋首段取数
 *       在后台线程做完，起播时不现等）；</li>
 *   <li>切剧／退出时 {@link #close(String)} 回收；漏关的流随 {@link #closeAll()}
 *       在模块退出时兜底。</li>
 * </ol>
 *
 * <p>服务器侧（server.process.HongguoStream）按 token 用 {@link #open(String)}
 * 取流出 Range 响应，不直接碰规格与密钥。</p>
 */
public final class HgStreamRegistry {

    /** 上游源工厂：默认走 HTTP Range 源；离线验注入文件源替身。 */
    public interface SourceFactory {
        HgRandomSource create(HgStreamSpec spec);
    }

    private static final HgStreamRegistry INSTANCE = new HgStreamRegistry(spec -> new HgHttpRangeSource(spec.upstreamUrl));

    public static HgStreamRegistry get() {
        return INSTANCE;
    }

    private final SourceFactory factory;
    private final Map<String, HgCencStream> streams = new ConcurrentHashMap<>();
    private final Map<String, HgStreamSpec> specs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService warmer = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "hg-stream-warm");
        t.setDaemon(true);
        return t;
    });

    public HgStreamRegistry(SourceFactory factory) {
        this.factory = factory;
    }

    /** 注册一条流（不取数），返回 token。dekHex 可为 null（未保护条目直喂）。 */
    public String register(HgStreamSpec spec) {
        if (spec == null || spec.upstreamUrl == null) return null;
        String token = "hg" + seq.incrementAndGet() + "_" + System.currentTimeMillis();
        specs.put(token, spec);
        return token;
    }

    /** 本地播放路径（拼在本地服务器地址后）。 */
    public String playbackPath(String token) {
        return "/hongguo/stream?t=" + token;
    }

    /** 取流（首次取时才建源，不解析）；token 无效返回 null。 */
    public HgCencStream open(String token) {
        if (token == null) return null;
        HgCencStream s = streams.get(token);
        if (s != null) return s;
        HgStreamSpec spec = specs.get(token);
        if (spec == null) return null;
        synchronized (this) {
            s = streams.get(token);
            if (s == null) {
                s = new HgCencStream(factory.create(spec), spec.dekHex, spec.size, spec.fileHash);
                streams.put(token, s);
            }
            return s;
        }
    }

    /** 预热口：后台线程建流＋盒解析＋首段取数；失败不抛（起播时出流会再试并报错）。 */
    public void warmAsync(String token) {
        warmer.execute(() -> {
            try {
                HgCencStream s = open(token);
                if (s != null) s.warm();
            } catch (Exception ignored) {
            }
        });
    }

    /** 同步预热（离线验／已确定在 IO 线程时用）。 */
    public boolean warm(String token) {
        try {
            HgCencStream s = open(token);
            if (s == null) return false;
            s.warm();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 关闭并回收一条流。 */
    public void close(String token) {
        if (token == null) return;
        specs.remove(token);
        HgCencStream s = streams.remove(token);
        if (s != null) s.close();
    }

    public void closeAll() {
        for (String token : specs.keySet().toArray(new String[0])) close(token);
    }

    public int size() {
        return specs.size();
    }
}
