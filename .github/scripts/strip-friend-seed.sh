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
URL_YTMUSIC="https://xingchen-dl.pages.dev/music-sources/ytmusic-lx.js"

grep -q "public static List<MusicSite> builtinSites()" "$MSM" || fail "builtinSites 方法签名对不上"
for u in "$URL_GECIWANG" "$URL_GECIQIANXUN" "$URL_KUWO" "$URL_YTMUSIC"; do
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
# （MusicSourceManager.java 的 BUILTIN_LYRIC_*，不在方法体内），酷我地址另有两份
# （MusicSourceManager.java 的 BUILTIN_KUWO_YUANLI 活常量——isBuiltinKuwo 按它认
# 预置身份；KwChartSource.java 的 FALLBACK_PLUGIN_URL 榜单回落戳），油管地址另有一份
# 常量 BUILTIN_YTMUSIC（isProxyDesignated 按它认）。旧脚本漏清 BUILTIN_KUWO_YUANLI、
# 且完全没管油管：终检 grep 命中酷我即 exit 1、朋友版编不出来；油管就算把酷我修掉
# 也会把地址字面量留在朋友版源码里。本步把 MSM 内四址与 KwChartSource 的酷我址
# 一并清为空串（只改 CI 临时检出，仓库源码不动）：常量名与各认身份／回落返回点还在，
# 编译不断；朋友版里它们不再指向任何预置地址，播种与回落都种不出插件。
KWCS=app/src/main/java/com/fongmi/android/tv/api/music/KwChartSource.java
[ -f "$KWCS" ] || fail "找不到 KwChartSource.java"
grep -qF "\"$URL_KUWO\"" "$KWCS" || fail "酷我榜单回落地址对不上：$URL_KUWO"
python3 - "$MSM" "$KWCS" "$URL_GECIWANG" "$URL_GECIQIANXUN" "$URL_KUWO" "$URL_YTMUSIC" <<'PYEOF'
import sys

for path, urls in ((sys.argv[1], sys.argv[3:7]), (sys.argv[2], sys.argv[5:6])):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for u in urls:
        if ('"' + u + '"') not in text:
            sys.exit("python: 预置地址没找到：" + u + "（文件：" + path + "）")
        text = text.replace('"' + u + '"', '""')
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
PYEOF

# 复查：四个预置地址不许在任何源码里残留（下架 bilibili 与油管旧 FreeJS 形态的存量清理
# 常量是另两个地址，不在此列——它们只删存量、不播种，留着才能清掉装错互装带进来的旧记录；
# 油管只认 music-sources/ytmusic-lx.js 这一具体路径，同域名的歌词端点/ASR/内核地址不是预置插件、不在此列）
if grep -rn "dist/geciwang/index\.js\|dist/geciqianxun/index\.js\|qwerwhr/musicfree-plugins\|music-sources/ytmusic-lx\.js" app/src --include=*.java; then
  fail "删完后仍有预置插件地址残留"
fi
grep -q "public static List<MusicSite> builtinSites()" "$MSM" || fail "删完后 builtinSites 方法不见了"
echo "朋友版：音乐预置播种已删除（builtinSites 返回空表）"

# ---- 第二段：QQ 音乐登录整套剥掉（2026-10-26 他亲定：只进正式版，他留着自测）----
# 剥的只有 QQ 专属件：QQLoginProvider（统一登录的 QQ 提供方）、QqVkeyFetcher
# （带登录态调官方 vkey 取链＋未登录节流弹窗）、它在 LoginManager 里的注册行／
# loginQQ 入口／三个 QQ 静态桥／requestLogin 的 QQ 分发支、LxMusicSource 里 tx
# 脚本全灭后回落 QqVkeyFetcher 的兜底段，以及只测这两件的 QQLoginProviderTest。
# 不许误伤的共用框架：LoginManager 本体（注册表、通用 getCookie/isLoggedIn/logout、
# requestLogin 通用分发与谷歌/快手/115 三支）、LoginProvider 接口、PanLoginDialog
# 统一弹窗、CloudAccountStore 托管存储、网盘四路登录（扫码/账密/Cookie/网页）、
# AccountPlaylistFetcher 的 fetchQq（歌单登录导入的平台取数，Cookie 由导入弹窗
# 自有 WebView 流程给，与本段无关，只 scrub 它注释里提到的 QqVkeyFetcher 类名）。
# 剥后口径：落雪 tx 脚本路照常（脚本取链不依赖 QQ 登录）；脚本全灭时兜底段消失、
# playUrl 如实返回 null 走既有失败路；getCookie("qq")/isLoggedIn("qq") 因未注册
# 由框架空安全回 ""/false，requestLogin("qq") 回 false——不崩、不假装能取。
QQPROV=app/src/main/java/com/fongmi/android/tv/login/QQLoginProvider.java
QQVKEY=app/src/main/java/com/fongmi/android/tv/api/music/QqVkeyFetcher.java
QQTEST=app/src/test/java/com/fongmi/android/tv/login/QQLoginProviderTest.java
LM=app/src/main/java/com/fongmi/android/tv/login/LoginManager.java
LXMS=app/src/main/java/com/fongmi/android/tv/api/music/LxMusicSource.java
APF=app/src/main/java/com/fongmi/android/tv/api/music/AccountPlaylistFetcher.java
for f in "$QQPROV" "$QQVKEY" "$QQTEST" "$LM" "$LXMS" "$APF"; do
  [ -f "$f" ] || fail "QQ 剥离目标文件找不到：$f"
done
grep -qF "register(new QQLoginProvider());" "$LM" || fail "LoginManager 的 QQ 注册行对不上"
grep -qF "public void loginQQ(FragmentActivity activity, LoginCallback callback)" "$LM" || fail "LoginManager 的 loginQQ 入口对不上"
grep -qF "public static boolean loginQQDelivered()" "$LM" || fail "LoginManager 的 loginQQDelivered 对不上"
grep -qF "public static boolean isQQLoggedIn()" "$LM" || fail "LoginManager 的 isQQLoggedIn 对不上"
grep -qF "QqVkeyFetcher.playUrl(" "$LXMS" || fail "LxMusicSource 的 tx 兜底段对不上"
grep -qF "QqVkeyFetcher.maybePromptLogin()" "$LXMS" || fail "LxMusicSource 的弹窗兜底对不上"

python3 - "$LM" "$LXMS" "$APF" <<'PYEOF'
import sys

def load(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()

def save(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)

def cut_exact(text, block, what):
    if text.count(block) != 1:
        sys.exit("python: QQ 剥离锚点对不上（%s，命中 %d 处）" % (what, text.count(block)))
    return text.replace(block, "")

def swap_exact(text, old, new, what):
    if text.count(old) != 1:
        sys.exit("python: QQ 剥离锚点对不上（%s，命中 %d 处）" % (what, text.count(old)))
    return text.replace(old, new)

def find_line(lines, needle, what):
    hits = [i for i, ln in enumerate(lines) if needle in ln]
    if len(hits) != 1:
        sys.exit("python: QQ 剥离定位对不上（%s，命中 %d 处）" % (what, len(hits)))
    return hits[0]

lm_path, lx_path, apf_path = sys.argv[1], sys.argv[2], sys.argv[3]

# ---- LoginManager：注册行 + requestLogin QQ 支（整块删） ----
lm = load(lm_path)
lm = cut_exact(lm, "        register(new QQLoginProvider());\n", "QQ 注册行")
lm = cut_exact(lm, """        if (QQLoginProvider.ID.equals(providerId)) {
            // 按投递结果落节流戳的调用方（QqVkeyFetcher）据此不落戳
            return loginQQDelivered();
        }
""", "requestLogin 的 QQ 分发支")
lines = lm.split("\n")

# ---- LoginManager：loginQQ 实例方法（含 javadoc）整段删 ----
i = find_line(lines, "弹 QQ 音乐网页登录框：走壳子统一弹窗", "loginQQ javadoc")
j = find_line(lines, "    private void openMethod", "openMethod")
if not (i < j and lines[i - 1].strip() == "/**"):
    sys.exit("python: loginQQ 段落形状对不上")
lines = lines[:i - 1] + lines[j:]

# ---- LoginManager：loginQQ/loginQQDelivered 静态桥整段删（单行 javadoc，起始行即注释行本身） ----
i = find_line(lines, "弹壳子统一的 QQ 音乐网页登录框（异步）", "loginQQ 静态桥 javadoc")
j = find_line(lines, "    // 扫描 O13：", "O13 注释")
if not (i < j and lines[i].strip().startswith("/**")):
    sys.exit("python: loginQQ 静态桥段落形状对不上")
lines = lines[:i] + lines[j:]

# ---- LoginManager：isQQLoggedIn/getQQCookie/logoutQQ 三个静态口整段删（同为单行 javadoc 起头） ----
# 结束锚点是 requestLogin 的 javadoc 正文行，它上一行就是该 javadoc 的 /** 起始行——
# 删到起始行之前为止，不许把人家的注释头吃掉。
i = find_line(lines, "QQ 是否已登录（托管 Cookie 含账号标识＋音乐钥匙）", "isQQLoggedIn 注释")
j = find_line(lines, "     * 通用：按提供方 id 请壳子弹统一登录框", "requestLogin javadoc")
if not (i < j - 1 and lines[i].strip().startswith("/**") and lines[j - 1].strip() == "/**"):
    sys.exit("python: QQ 静态桥段落形状对不上")
lines = lines[:i] + lines[j - 1:]
lm = "\n".join(lines)
save(lm_path, lm)

# ---- LxMusicSource：删 tx 官方兜底整段 + 两处注释里的类名 ----
lx = load(lx_path)
lx = cut_exact(lx, """        if (LxPlatformSearch.QQ.equals(p.platform)) {
            // tx 官方兜底（通用层一处，搜索/榜单/歌单导入的 tx 歌都经此）：脚本全灭、
            // 回空、回幻影链、乃至一个脚本都没装时，已存 QQ 登录态就改走官方 vkey
            // 带态取链；未登录按 10 分钟节流弹统一登录（探链不弹），本次照常失败返回。
            String fallback = QqVkeyFetcher.playUrl(
                    optStr(musicInfo, "songmid"), optStr(musicInfo, "strMediaMid"), want);
            if (!TextUtils.isEmpty(fallback)) return fallback;
            if (!probe) QqVkeyFetcher.maybePromptLogin();
        }
""", "LxMusicSource tx 兜底段")
lx = swap_exact(lx, "兜底段（官方 vkey＋登录弹窗）永远轮", "后面的脚本永远轮", "probeUrlDead 注释一")
lx = swap_exact(lx, "（与 QqVkeyFetcher 同出口，吃代理/DoH/节点定向）", "（吃代理/DoH/节点定向）", "probeUrlDead 注释二")
save(lx_path, lx)

# ---- AccountPlaylistFetcher：注释里的类名 scrub（本体是平台通用件，不动） ----
apf = load(apf_path)
apf = swap_exact(apf, "取号三级回落与 QqVkeyFetcher.uinOf 同口径（扫描 M17）", "取号三级回落与取链侧同口径（扫描 M17）", "歌单导入注释")
save(apf_path, apf)
PYEOF

rm "$QQPROV" "$QQVKEY" "$QQTEST"

# 复查一：QQ 音乐登录标识全树零残留（QQ 作为音乐平台的搜索/榜单/歌单导入不在此列，
# 只认登录专属标识：两个类名＋LoginManager 的六个 QQ 入口名）
if grep -rn "QQLoginProvider\|QqVkeyFetcher\|loginQQ\|getQQCookie\|isQQLoggedIn\|logoutQQ" app/src --include=*.java; then
  fail "删完后仍有 QQ 音乐登录残留"
fi
# 复查二：共用框架必须完好——三家注册行、通用口、requestLogin 通用分发都还在
grep -qF "register(new Qr115Provider());" "$LM" || fail "剥完后 115 提供方注册不见了"
grep -qF "register(new KuaishouLoginProvider());" "$LM" || fail "剥完后快手提供方注册不见了"
grep -qF "register(new GoogleLoginProvider());" "$LM" || fail "剥完后谷歌提供方注册不见了"
grep -qF "public static boolean requestLogin(String providerId)" "$LM" || fail "剥完后 requestLogin 通用分发不见了"
grep -qF "public static String getCookie(String providerId)" "$LM" || fail "剥完后 getCookie 通用口不见了"
grep -qF "LxPlatformSearch.QQ" "$LXMS" || fail "剥完后 LxMusicSource 的 QQ 平台逻辑被误伤"
echo "朋友版：QQ 音乐登录已删除（提供方＋vkey 取链＋统一登录入口，框架本体保留）"
