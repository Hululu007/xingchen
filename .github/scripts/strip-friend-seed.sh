#!/usr/bin/env bash
# 朋友版专用：构建前把音乐「预置播种」整套从源码里删掉（不是关掉）。
# 只在 CI 的临时检出上执行，仓库源码不动。任何一处对不上就让构建失败，
# 防止哪天重构改了写法、朋友版悄悄带着预置插件发出去。
set -euo pipefail

fail() { echo "::error title=朋友版删预置播种失败::$1"; exit 1; }

MSM=app/src/main/java/com/fongmi/android/tv/api/music/MusicSourceManager.java
[ -f "$MSM" ] || fail "找不到 MusicSourceManager.java"

URL_GECIWANG="https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciwang/index.js"
URL_GECIQIANXUN="https://cdn.jsdelivr.net/gh/maotoumao/MusicFreePlugins@master/dist/geciqianxun/index.js"
URL_KUWO="https://cdn.jsdelivr.net/gh/qwerwhr/musicfree-plugins@master/%E5%85%83%E5%8A%9BKW.js"

grep -q "public static List<MusicSite> builtinSites()" "$MSM" || fail "builtinSites 方法签名对不上"
for u in "$URL_GECIWANG" "$URL_GECIQIANXUN" "$URL_KUWO"; do
  grep -qF "\"$u\"" "$MSM" || fail "预置插件地址对不上：$u"
done

# 把 builtinSites() 整个方法体换成返回空表：三个预置地址字面量随之从源码消失，
# 首启播种（listSites 在存量为空时调 builtinSites）自然种不出任何插件。
python3 - "$MSM" <<'PYEOF'
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as fh:
    lines = fh.read().split("\n")
start = None
for i, ln in enumerate(lines):
    if "public static List<MusicSite> builtinSites()" in ln:
        start = i
        break
if start is None:
    sys.exit("python: builtinSites 方法没找到")
end = None
for j in range(start + 1, len(lines)):
    if lines[j].rstrip("\r") == "    }":
        end = j
        break
if end is None:
    sys.exit("python: builtinSites 方法结束行没找到")
lines[start:end + 1] = [
    "    public static List<MusicSite> builtinSites() {",
    "        return new ArrayList<>();",
    "    }",
]
with open(path, "w", encoding="utf-8") as fh:
    fh.write("\n".join(lines))
PYEOF

# 光换 builtinSites() 方法体不够：歌词网／歌词千寻的地址另有两份常量
# （MusicSourceManager.java 的 BUILTIN_LYRIC_*，不在方法体内），酷我地址另有一份
# 在 KwChartSource.java 的 FALLBACK_PLUGIN_URL（榜单回落戳）。旧脚本只换方法体，
# 终检 grep 仍命中这三处、必失败，朋友版编不出来。本步把这三处字面量一并清为空串
# （只改 CI 临时检出，仓库源码不动）：常量名与 isBuiltinLyricPreset / 回落返回点还在，
# 编译不断；朋友版里它们不再指向任何预置地址，播种与回落都种不出插件。
KWCS=app/src/main/java/com/fongmi/android/tv/api/music/KwChartSource.java
[ -f "$KWCS" ] || fail "找不到 KwChartSource.java"
grep -qF "\"$URL_KUWO\"" "$KWCS" || fail "酷我榜单回落地址对不上：$URL_KUWO"
python3 - "$MSM" "$KWCS" "$URL_GECIWANG" "$URL_GECIQIANXUN" "$URL_KUWO" <<'PYEOF'
import sys

for path, urls in ((sys.argv[1], sys.argv[3:5]), (sys.argv[2], sys.argv[5:6])):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for u in urls:
        if ('"' + u + '"') not in text:
            sys.exit("python: 预置地址没找到：" + u + "（文件：" + path + "）")
        text = text.replace('"' + u + '"', '""')
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
PYEOF

# 复查：三个预置地址不许在任何源码里残留（下架 bilibili 的存量清理常量是另一个地址，不在此列）
if grep -rn "dist/geciwang/index\.js\|dist/geciqianxun/index\.js\|qwerwhr/musicfree-plugins" app/src --include=*.java; then
  fail "删完后仍有预置插件地址残留"
fi
grep -q "public static List<MusicSite> builtinSites()" "$MSM" || fail "删完后 builtinSites 方法不见了"
echo "朋友版：音乐预置播种已删除（builtinSites 返回空表）"
