import com.fongmi.android.tv.hongguo.proxy.HgCencStream;
import com.fongmi.android.tv.hongguo.proxy.HgFileSource;
import com.fongmi.android.tv.hongguo.proxy.HgRangeInputStream;
import com.fongmi.android.tv.hongguo.proxy.HgStreamRegistry;
import com.fongmi.android.tv.hongguo.proxy.HgStreamSpec;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * 离线验专用回环服务器：与生产 HongguoStream 同 handler 逻辑（Range 解析＋
 * HgRangeInputStream 出流），供 run.sh 用 curl 拉头/中/尾三段与整流对照。
 * 只在 run.sh 临时目录里编译运行，不进 App。启动后向 stdout 打印 PORT=<端口>。
 */
public class HgTestServer {
    public static void main(String[] args) throws Exception {
        String dir = "/home/hatch/workspace/hongguo/";
        long total = new File(dir + "ep_enc.mp4").length();
        HgStreamRegistry registry = new HgStreamRegistry(spec -> {
            try {
                return new HgFileSource(new File(dir + "ep_enc.mp4"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String token = registry.register(new HgStreamSpec("file://ep_enc.mp4",
                "048c86766a0b2fd10a7af719b157ff19", total, "d0973b53dc14ffee5bd5380f431fee1f", "test/360p"));
        registry.warm(token);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hongguo/stream", ex -> {
            try {
                HgCencStream s = registry.open(token);
                long tot = s.length();
                long start = 0, end = tot - 1;
                String range = ex.getRequestHeaders().getFirst("Range");
                if (range != null && range.startsWith("bytes=")) {
                    String[] p = range.substring(6).split("-", 2);
                    if (!p[0].isEmpty()) start = Long.parseLong(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
                    if (end >= tot) end = tot - 1;
                }
                long len = end - start + 1;
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                if (len < tot) ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + tot);
                ex.sendResponseHeaders(len < tot ? 206 : 200, len);
                try (InputStream in = new HgRangeInputStream(s, start, end + 1);
                     OutputStream os = ex.getResponseBody()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) os.write(b, 0, n);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                try {
                    ex.sendResponseHeaders(500, -1);
                } catch (Exception ignored) {
                }
            } finally {
                ex.close();
            }
        });
        server.start();
        System.out.println("PORT=" + server.getAddress().getPort());
        System.out.flush();
        Thread.sleep(120_000);
    }
}
