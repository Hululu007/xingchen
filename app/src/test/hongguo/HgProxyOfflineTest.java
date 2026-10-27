import com.fongmi.android.tv.hongguo.api.HgApiClient;
import com.fongmi.android.tv.hongguo.api.HgCallback;
import com.fongmi.android.tv.hongguo.api.HgModelCache;
import com.fongmi.android.tv.hongguo.api.HgPrefetcher;
import com.fongmi.android.tv.hongguo.crypto.HgCencDecryptor;
import com.fongmi.android.tv.hongguo.crypto.HgHex;
import com.fongmi.android.tv.hongguo.proxy.HgCencStream;
import com.fongmi.android.tv.hongguo.proxy.HgFileSource;
import com.fongmi.android.tv.hongguo.proxy.HgRangeInputStream;
import com.fongmi.android.tv.hongguo.proxy.HgStreamRegistry;
import com.fongmi.android.tv.hongguo.proxy.HgStreamSpec;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 红果第二阶段（本地代理解密层）离线验：纯 JVM，语料 ep_enc.mp4 / ep_clear.mp4。
 * 由 run.sh 编译运行；参数 args[0]＝代理整流落盘路径（供 run.sh 后续 ffprobe 对照）。
 */
public class HgProxyOfflineTest {
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail == null ? "" : " | " + detail));
        if (!ok) fail++;
    }

    static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            // HttpServer 是非守护线程，异常后不强退会挂住整个 run.sh
            t.printStackTrace();
            System.exit(2);
        }
    }

    static void run(String[] args) throws Exception {
        String dir = "/home/hatch/workspace/hongguo/";
        String dekHex = "048c86766a0b2fd10a7af719b157ff19";
        byte[] enc = Files.readAllBytes(Paths.get(dir + "ep_enc.mp4"));
        byte[] clear = Files.readAllBytes(Paths.get(dir + "ep_clear.mp4"));
        int total = clear.length;
        check("语料等长", enc.length == total, "enc=" + enc.length + " clear=" + total);

        // 1) 全文件：解密器整解 vs ep_clear 逐字节全等
        byte[] out = HgCencDecryptor.decryptAll(enc.clone(), HgHex.decode(dekHex));
        check("全文件逐字节全等", Arrays.equals(out, clear),
                "sha256 期望=" + sha256(clear) + " 实际=" + sha256(out));

        // 2) 区间：走 HgCencStream 区间路径抽 >=20 区间（含跨样本/盒头/moov 区/文件尾）
        HgCencStream stream = new HgCencStream(new HgFileSource(new File(dir + "ep_enc.mp4")), dekHex, total,
                "d0973b53dc14ffee5bd5380f431fee1f");
        List<long[]> ranges = new ArrayList<>();
        ranges.add(new long[]{0, 4096});                 // 盒头区（ftyp+moov 头）
        ranges.add(new long[]{28, 28 + 297160});         // 整个 moov 区
        ranges.add(new long[]{297100, 297196 + 2000});   // moov/mdat 跨界（跨样本起点）
        ranges.add(new long[]{total - 8192, total});     // 文件尾区
        ranges.add(new long[]{total - 16, total});       // 尾 16 字节
        ranges.add(new long[]{1000000, 1000001});        // 1 字节
        Random rnd = new Random(20261027L);
        for (int i = 0; i < 20; i++) {
            int s = rnd.nextInt(total - 70000);
            int len = 1 + rnd.nextInt(65536);
            ranges.add(new long[]{s, Math.min(total, s + len)});
        }
        boolean allOk = true;
        byte[] buf = new byte[300000];
        for (long[] r : ranges) {
            int len = (int) (r[1] - r[0]);
            int n = stream.readClear(r[0], buf, 0, len);
            if (n != len || !Arrays.equals(Arrays.copyOf(buf, n), Arrays.copyOfRange(clear, (int) r[0], (int) r[1]))) {
                allOk = false;
                System.out.println("  区间不符 [" + r[0] + "," + r[1] + ") n=" + n);
            }
        }
        check("区间解密 " + ranges.size() + " 个区间逐字节全等", allOk, null);
        stream.close();

        // 2b) 尾部小区间证据：新流只读尾 1024 字节，取数与解块数都应远小于整文件
        HgFileSource tailSrc = new HgFileSource(new File(dir + "ep_enc.mp4"));
        HgCencStream tail = new HgCencStream(tailSrc, dekHex, total, null);
        byte[] tailBuf = new byte[1024];
        int tn = tail.readClear(total - 1024, tailBuf, 0, 1024);
        check("尾部小区间内容全等", tn == 1024 && Arrays.equals(tailBuf, Arrays.copyOfRange(clear, total - 1024, total)), null);
        System.out.println("  尾部证据: 上游取数=" + tailSrc.fetchedBytes() + " 字节 / " + tailSrc.fetchCalls()
                + " 次, 解密块=" + tail.decryptedBlocks() + " (整文件约 " + (total / 16) + " 块当量)");
        check("尾部小区间取数 < 整文件 1/4", tailSrc.fetchedBytes() < total / 4,
                "取数=" + tailSrc.fetchedBytes() + " 整文件=" + total);
        check("尾部小区间解密块 < 1000", tail.decryptedBlocks() < 1000, "块=" + tail.decryptedBlocks());
        tail.close();

        // 3) 出流：注册表 + 本地文件源替身 + 真 HTTP 回环（Range 头/中/尾三段 + 整流落盘）
        HgStreamRegistry registry = new HgStreamRegistry(spec -> {
            try {
                return new HgFileSource(new File(dir + "ep_enc.mp4"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String token = registry.register(new HgStreamSpec("file://ep_enc.mp4", dekHex, total,
                "d0973b53dc14ffee5bd5380f431fee1f", "test/360p"));
        check("注册表 register 返回 token", token != null, token);
        check("预热 warm 成功且已解析", registry.warm(token) && registry.open(token).isParsed(), null);

        // 出流本体：HgRangeInputStream 整流（HTTP 回环段在 run.sh 里由
        // HgTestServer 起真服务 + curl 拉段对照，见 run.sh 尾段；本机助手进程的
        // 原始 TCP 被权限层拦，故 JVM 内不自连回环）
        HgCencStream regStream = registry.open(token);
        byte[] fullBytes;
        try (InputStream in = new HgRangeInputStream(regStream, 0, total)) {
            fullBytes = in.readAllBytes();
        }
        check("出流整流逐字节全等", Arrays.equals(fullBytes, clear),
                "sha256=" + sha256(fullBytes));
        if (args.length > 0) Files.write(Paths.get(args[0]), fullBytes);
        registry.closeAll();
        check("注册表 closeAll 后为空", registry.size() == 0, null);

        // 4) HgPrefetcher 在途去重（桩 OkHttp 的 Call 永不回调＝请求在飞）
        HgApiClient client = new HgApiClient();
        HgModelCache cache = new HgModelCache();
        HgPrefetcher prefetcher = new HgPrefetcher(client, cache);
        List<String> ids = Arrays.asList("v1", "v2", "v3", "v4", "v5");
        AtomicInteger firstDone = new AtomicInteger(-1);
        prefetcher.prefetchAround(ids, 0, new HgCallback<Integer>() {
            @Override public void onSuccess(Integer r) { firstDone.set(r); }
            @Override public void onError(int c, String m) { firstDone.set(-9); }
        });
        check("首次预取后在途=3（当前+前瞻2）", prefetcher.inFlightCount() == 3,
                "在途=" + prefetcher.inFlightCount());
        AtomicInteger secondDone = new AtomicInteger(-1);
        prefetcher.prefetchAround(ids, 0, new HgCallback<Integer>() {
            @Override public void onSuccess(Integer r) { secondDone.set(r); }
            @Override public void onError(int c, String m) { secondDone.set(-9); }
        });
        check("在途时重复 prefetchAround 直接回调 0 且在途不变",
                secondDone.get() == 0 && prefetcher.inFlightCount() == 3,
                "second=" + secondDone.get() + " 在途=" + prefetcher.inFlightCount());
        Object stubCalls = null;
        try {
            stubCalls = Class.forName("com.github.catvod.net.OkHttp").getField("STUB_NEW_CALLS").getInt(null);
        } catch (Exception ignored) {
        }
        check("在途期间上游请求只发 1 笔（桩计数）", Integer.valueOf(1).equals(stubCalls), "STUB_NEW_CALLS=" + stubCalls);

        System.out.println(fail == 0 ? "ALL PASS" : ("FAILURES=" + fail));
        System.exit(fail == 0 ? 0 : 1);
    }
}
