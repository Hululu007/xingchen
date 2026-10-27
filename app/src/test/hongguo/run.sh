#!/bin/sh
# 红果数据层离线单测：纯 JVM（JDK21），不依赖 Android 运行时。
# 语料只用存档文件（~/workspace/hongguo/ 下两份真回包 + fresh_360p_url.txt）。
# 客户端的 OkHttp 依赖用 /tmp 里按真库签名生成的桩编译（桩签名对
# catvod OkHttp 源码 newCall 201/213 行核对），只验语法与请求构造，不发网络。
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
import java.io.IOException;
public class ResponseBody { public String string() throws IOException { return ""; } }
EOF
cat > "$WORK/stub/okhttp3/Response.java" << 'EOF'
package okhttp3;
import java.io.Closeable;
public class Response implements Closeable {
  public ResponseBody body(){ return null; } public boolean isSuccessful(){ return true; }
  public int code(){ return 200; } public void close(){} }
EOF
cat > "$WORK/stub/okhttp3/Callback.java" << 'EOF'
package okhttp3;
import java.io.IOException;
public interface Callback { void onFailure(Call c, IOException e); void onResponse(Call c, Response r); }
EOF
cat > "$WORK/stub/okhttp3/Call.java" << 'EOF'
package okhttp3;
public interface Call { void enqueue(Callback cb); }
EOF
cat > "$WORK/stub/com/github/catvod/net/OkHttp.java" << 'EOF'
package com.github.catvod.net;
import java.util.Map;
import okhttp3.Call;
import okhttp3.RequestBody;
public class OkHttp {
  public static Call newCall(String url, Map<String,String> headers) { return null; }
  public static Call newCall(String url, Map<String,String> headers, RequestBody body) { return null; }
}
EOF

find "$WORK/stub" -name '*.java' > "$WORK/srcs.txt"
find "$ROOT/app/src/main/java/com/fongmi/android/tv/hongguo" -name '*.java' >> "$WORK/srcs.txt"
echo "$ROOT/app/src/test/hongguo/HgOfflineTest.java" >> "$WORK/srcs.txt"
"$JAVAC" -encoding UTF-8 -cp "$GSON" -d "$WORK/out" @"$WORK/srcs.txt"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out:$GSON" HgOfflineTest
