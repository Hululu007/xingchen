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

# 复查：三个预置地址不许在任何源码里残留（下架 bilibili 的存量清理常量是另一个地址，不在此列）
if grep -rn "dist/geciwang/index\.js\|dist/geciqianxun/index\.js\|qwerwhr/musicfree-plugins" app/src --include=*.java; then
  fail "删完后仍有预置插件地址残留"
fi
grep -q "public static List<MusicSite> builtinSites()" "$MSM" || fail "删完后 builtinSites 方法不见了"
echo "朋友版：音乐预置播种已删除（builtinSites 返回空表）"
