#!/bin/sh
# 红果离线单测：纯 JVM（JDK21），不依赖 Android 运行时。
# 语料只用存档文件（~/workspace/hongguo/ 下两份真回包 + fresh_360p_url.txt
# + ep_enc.mp4/ep_clear.mp4 密/明文对）。
# 客户端与代理的 OkHttp 依赖用 /tmp 里按真库签名生成的桩编译（桩签名对
# catvod OkHttp 源码核对），只验语法与逻辑，不发网络。
# 桩 Call.enqueue 永不回调＝请求永远在飞，HgPrefetcher 在途去重靠它验。
set -e
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
JAVAC=${JAVAC:-$HOME/workspace/.jdk21/bin/javac}
JAVA=${JAVA:-$HOME/workspace/.jdk21/bin/java}
GSON=${GSON:-$HOME/gradle-9.5.1/lib/gson-2.13.1.jar}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/stub/okhttp3" "$WORK/stub/com/github/catvod/net"
cat > "$WORK/stub/okhttp3/MediaType.java" << 'EOF'
package okhttp3;
public final class MediaType { public static MediaType parse(String s){ return new MediaType(); } }
EOF
cat > "$WORK/stub/okhttp3/RequestBody.java" << 'EOF'
package okhttp3;
public class RequestBody { public static RequestBody create(MediaType t, String c){ return new RequestBody(); } }
EOF
cat > "$WORK/stub/okhttp3/ResponseBody.java" << 'EOF'
package okhttp3;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
public class ResponseBody {
  public String string() throws IOException { return ""; }
  public byte[] bytes() throws IOException { return new byte[0]; }
  public InputStream byteStream() { return new ByteArrayInputStream(new byte[0]); }
  public long contentLength() { return 0; }
}
EOF
cat > "$WORK/stub/okhttp3/Response.java" << 'EOF'
package okhttp3;
import java.io.Closeable;
public class Response implements Closeable {
  public ResponseBody body(){ return null; } public boolean isSuccessful(){ return true; }
  public int code(){ return 200; } public String header(String n){ return null; } public void close(){} }
EOF
cat > "$WORK/stub/okhttp3/Callback.java" << 'EOF'
package okhttp3;
import java.io.IOException;
public interface Callback { void onFailure(Call c, IOException e); void onResponse(Call c, Response r); }
EOF
cat > "$WORK/stub/okhttp3/Call.java" << 'EOF'
package okhttp3;
import java.io.IOException;
public interface Call { void enqueue(Callback cb); Response execute() throws IOException; }
EOF
cat > "$WORK/stub/okhttp3/Request.java" << 'EOF'
package okhttp3;
public final class Request {
  public static final class Builder {
    public Builder url(String u){ return this; }
    public Builder header(String n, String v){ return this; }
    public Request build(){ return new Request(); }
  }
}
EOF
cat > "$WORK/stub/okhttp3/OkHttpClient.java" << 'EOF'
package okhttp3;
public class OkHttpClient { public Call newCall(Request r){ return null; } }
EOF
mkdir -p "$WORK/stub/com/fongmi/android/tv/login"
cat > "$WORK/stub/com/fongmi/android/tv/login/LoginManager.java" << 'EOF'
package com.fongmi.android.tv.login;
public class LoginManager {
  public static String getCookie(String providerId) { return ""; }
  public static boolean isLoggedIn(String providerId) { return false; }
}
EOF
mkdir -p "$WORK/stub/com/github/catvod/utils"
cat > "$WORK/stub/com/github/catvod/utils/Prefers.java" << 'EOF'
package com.github.catvod.utils;
import java.util.HashMap;
import java.util.Map;
public class Prefers {
  private static final Map<String, Object> MAP = new HashMap<>();
  public static String getString(String key) { Object v = MAP.get(key); return v == null ? "" : String.valueOf(v); }
  public static String getString(String key, String def) { Object v = MAP.get(key); return v == null ? def : String.valueOf(v); }
  public static boolean getBoolean(String key, boolean def) { Object v = MAP.get(key); return v == null ? def : (Boolean) v; }
  public static void put(String key, String value) { MAP.put(key, value); }
  public static void put(String key, boolean value) { MAP.put(key, value); }
}
EOF
cat > "$WORK/stub/com/github/catvod/net/OkHttp.java" << 'EOF'
package com.github.catvod.net;
import java.util.Map;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
public class OkHttp {
  public static int STUB_NEW_CALLS = 0;
  private static final Call HELD = new Call() {
    public void enqueue(Callback cb) { /* 永不回调：请求保持在飞 */ }
    public Response execute() { return null; }
  };
  public static Call newCall(String url, Map<String,String> headers) { STUB_NEW_CALLS++; return HELD; }
  public static Call newCall(String url, Map<String,String> headers, RequestBody body) { STUB_NEW_CALLS++; return HELD; }
  public static OkHttpClient client() { return new OkHttpClient(); }
}
EOF

find "$WORK/stub" -name '*.java' > "$WORK/srcs.txt"
find "$ROOT/app/src/main/java/com/fongmi/android/tv/hongguo" -path '*/hongguo/ui' -prune -o -name '*.java' -print >> "$WORK/srcs.txt"
echo "$ROOT/app/src/main/java/com/fongmi/android/tv/api/FqCommentSigner.java" >> "$WORK/srcs.txt"
echo "$ROOT/app/src/test/hongguo/HgOfflineTest.java" >> "$WORK/srcs.txt"
echo "$ROOT/app/src/test/hongguo/HgProxyOfflineTest.java" >> "$WORK/srcs.txt"
echo "$ROOT/app/src/test/hongguo/HgInteractionTest.java" >> "$WORK/srcs.txt"
echo "$ROOT/app/src/test/hongguo/HgTestServer.java" >> "$WORK/srcs.txt"
"$JAVAC" -encoding UTF-8 -cp "$GSON" -d "$WORK/out" @"$WORK/srcs.txt"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out:$GSON" HgOfflineTest
"$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out:$GSON" HgProxyOfflineTest "$WORK/proxy_full.mp4"
(cd "$ROOT" && "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out:$GSON" HgInteractionTest)

# HTTP 回环出流：HgTestServer 起真服务，curl 按 Range 拉头/中/尾三段与整流，
# 与 ep_clear 对应切片逐字节比（cmp）。助手 JVM 进程的原始 TCP 被本机权限层
# 拦，故客户端用 curl（已实证 curl 对回环服务正常）。
HG_DIR_CURL=$HOME/workspace/hongguo
"$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out:$GSON" HgTestServer > "$WORK/server.log" 2>&1 &
SRVPID=$!
PORT=""
for i in $(seq 1 50); do
  PORT=$(sed -n 's/^PORT=//p' "$WORK/server.log" | head -1)
  [ -n "$PORT" ] && break
  sleep 0.2
done
if [ -z "$PORT" ]; then echo "FAIL HTTP 回环服务未启动"; cat "$WORK/server.log"; kill $SRVPID 2>/dev/null; exit 1; fi
BASE="http://127.0.0.1:$PORT/hongguo/stream?t=test"
TOTAL=$(stat -c %s "$HG_DIR_CURL/ep_clear.mp4")
http_ok=1
for seg in "0-65535" "2000000-2065535" "$(($TOTAL-65536))-$(($TOTAL-1))"; do
  s=${seg%-*}; e=${seg#*-}
  curl -s -r "$seg" "$BASE" -o "$WORK/seg.bin"
  python3 -c "
import sys
clear=open('$HG_DIR_CURL/ep_clear.mp4','rb').read()
seg=open('$WORK/seg.bin','rb').read()
sys.exit(0 if seg==clear[$s:$e+1] else 1)
" || { echo "FAIL HTTP Range 段 $seg"; http_ok=0; }
done
curl -s "$BASE" -o "$WORK/curl_full.mp4"
cmp -s "$WORK/curl_full.mp4" "$HG_DIR_CURL/ep_clear.mp4" || { echo "FAIL HTTP 整流不一致"; http_ok=0; }
kill $SRVPID 2>/dev/null
[ "$http_ok" = 1 ] && echo "PASS HTTP 回环 Range 头/中/尾三段 + 整流逐字节全等"

# ffprobe 认流：代理整流与 ep_clear 的识别结果（编码/尺寸/时长）必须一致
HG_DIR=$HOME/workspace/hongguo
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height -show_entries format=duration -of default=noprint_wrappers=1 "$HG_DIR/ep_clear.mp4" > "$WORK/probe_clear.txt"
ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height -show_entries format=duration -of default=noprint_wrappers=1 "$WORK/proxy_full.mp4" > "$WORK/probe_proxy.txt"
ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,channels -of default=noprint_wrappers=1 "$HG_DIR/ep_clear.mp4" >> "$WORK/probe_clear.txt"
ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,channels -of default=noprint_wrappers=1 "$WORK/proxy_full.mp4" >> "$WORK/probe_proxy.txt"
diff "$WORK/probe_clear.txt" "$WORK/probe_proxy.txt" && echo "PASS ffprobe 认流一致" && cat "$WORK/probe_proxy.txt"
