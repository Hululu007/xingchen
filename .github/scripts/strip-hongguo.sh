#!/usr/bin/env bash
# 朋友版专用：构建前把「第三方红果」整套从源码里删掉（不是关掉）。
# 只在 CI 的临时检出上执行，仓库源码不动。任何一处对不上就让构建失败，
# 防止哪天重构改了写法、朋友版悄悄带着红果发出去（与 strip-friend-seed.sh 同款纪律）。
#
# 删的只有本功能（2026-10-27 立项的原生红果，一至五阶段）新增的东西：
#   - 红果本体包 app/src/main/java/com/fongmi/android/tv/hongguo/（api/model/crypto/proxy/ui 全包）
#     与它的离线单测 app/src/test/hongguo/；
#   - 本体资源（main 源集）：activity/item/dialog/view_hongguo_* 布局 18 份、bg_hg_*/ic_hg_* 图形 41 份；
#   - 登录提供方 HongguoLoginProvider 与它在 LoginManager 里的注册行／loginHongguo 入口／
#     三个静态桥／requestLogin 的红果分发支；
#   - Nano 本地服务器的红果代理解密 process（HongguoStream 本体＋Nano 的 import 与注册行）；
#   - 底栏短剧格：HomeActivity 的 import／注册行／点击分发支、isHongguoTabEnabled 改回
#     false（格子不注册）、ic_nav_duanju 双态图标、nav_duanju 字符串；
#   - Manifest（mobile）里红果五个 Activity 的登记。
#
# 不许误伤的共用件（海阔规则年代就有的壳子件，只是名字里带 Hongguo，与本功能无关，留）：
#   - com.fongmi.android.tv.api.HongguoCommentApi / HongguoDanmakuApi / FqCommentSigner
#     （点播播放链的红果评论/弹幕抓取，HgCommentClient 只是复用其中的 FqCommentSigner）；
#   - mobile 的 HongguoCommentDialog / HongguoCommentAdapter 与 adapter_hongguo_* /
#     dialog_hongguo_comment 布局、shape_hongguo_* 图形、hongguo_panel_* 动画、
#     hongguo_comment* 字符串、VideoActivity 的红果评论/弹幕链、Danmaku 的 hongguo 平台名；
#   - 登录框架本体（LoginManager 注册表与通用口、PanLoginDialog、CloudAccountStore）、
#     底栏组件本体（XcBottomBar/BottomTabs 与 ids.xml 的 hongguo 预声明 id——无害占位、
#     与 2e7ddcc3 预声明时同状态）、Nano 其余 process。
# 终检只认本功能专属标识（tv.hongguo 包名与五个 Activity 名、HongguoLoginProvider、
# loginHongguo 系入口名、HongguoStream、ic_nav_duanju/nav_duanju），不认泛 Hongguo 字样，
# 免得把上面的共用件误报成残留。
set -euo pipefail

fail() { echo "::error title=朋友版删红果失败::$1"; exit 1; }

HGPKG=app/src/main/java/com/fongmi/android/tv/hongguo
HGTEST=app/src/test/hongguo
PROVIDER=app/src/main/java/com/fongmi/android/tv/login/HongguoLoginProvider.java
STREAM=app/src/main/java/com/fongmi/android/tv/server/process/HongguoStream.java
LM=app/src/main/java/com/fongmi/android/tv/login/LoginManager.java
NANO=app/src/main/java/com/fongmi/android/tv/server/Nano.java
HOME=app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java
MANIFEST=app/src/mobile/AndroidManifest.xml
MSTRINGS=app/src/mobile/res/values/strings.xml

for f in "$PROVIDER" "$STREAM" "$LM" "$NANO" "$HOME" "$MANIFEST" "$MSTRINGS"; do
  [ -f "$f" ] || fail "红果剥离目标文件找不到：$f"
done
[ -d "$HGPKG" ] || fail "红果本体包找不到：$HGPKG"
[ -d "$HGTEST" ] || fail "红果离线单测目录找不到：$HGTEST"

# ---- 锚点预检：改一处写法就先在这里炸，不许带着对不上硬删 ----
grep -qF "register(new HongguoLoginProvider());" "$LM" || fail "LoginManager 的红果注册行对不上"
grep -qF "public void loginHongguo(FragmentActivity activity, LoginCallback callback)" "$LM" || fail "LoginManager 的 loginHongguo 入口对不上"
grep -qF "public static boolean loginHongguoDelivered()" "$LM" || fail "LoginManager 的 loginHongguoDelivered 对不上"
grep -qF "public static boolean isHongguoLoggedIn()" "$LM" || fail "LoginManager 的 isHongguoLoggedIn 对不上"
grep -qF "import com.fongmi.android.tv.server.process.HongguoStream;" "$NANO" || fail "Nano 的 HongguoStream import 对不上"
grep -qF "process.add(new HongguoStream());" "$NANO" || fail "Nano 的 HongguoStream 注册行对不上"
grep -qF "import com.fongmi.android.tv.hongguo.ui.HongguoFeedActivity;" "$HOME" || fail "HomeActivity 的红果 import 对不上"
grep -qF "tabs.add(new BottomTabs.Spec(R.id.hongguo, R.drawable.ic_nav_duanju_outline, R.drawable.ic_nav_duanju_filled, getString(R.string.nav_duanju), true));" "$HOME" || fail "HomeActivity 的短剧格注册行对不上"
grep -qF "if (itemId == R.id.hongguo) {" "$HOME" || fail "HomeActivity 的短剧格点击分发对不上"
grep -qF "红果「启动直达短剧」" "$HOME" || fail "HomeActivity 的启动直达短剧块对不上"
grep -qF "<string name=\"nav_duanju\">短剧</string>" "$MSTRINGS" || fail "短剧格字符串 nav_duanju 对不上"
for a in HongguoFeedActivity HongguoSearchActivity HongguoAuthorActivity; do
  grep -qF "android:name=\"com.fongmi.android.tv.hongguo.ui.$a\"" "$MANIFEST" || fail "Manifest 的红果登记对不上：$a"
done

# ---- 本体资源清单（main 源集，逐份核在再删） ----
RES_FILES="
app/src/main/res/layout/activity_hongguo_author.xml
app/src/main/res/layout/activity_hongguo_feed.xml
app/src/main/res/layout/activity_hongguo_search.xml
app/src/main/res/layout/dialog_hongguo_comments.xml
app/src/main/res/layout/dialog_hongguo_danmaku.xml
app/src/main/res/layout/dialog_hongguo_episodes.xml
app/src/main/res/layout/dialog_hongguo_more_panel.xml
app/src/main/res/layout/dialog_hongguo_timer.xml
app/src/main/res/layout/item_hongguo_comment.xml
app/src/main/res/layout/item_hongguo_episode.xml
app/src/main/res/layout/item_hongguo_feed.xml
app/src/main/res/layout/item_hongguo_search.xml
app/src/main/res/layout/item_hongguo_search_text.xml
app/src/main/res/layout/item_hongguo_series_row.xml
app/src/main/res/layout/item_hongguo_theater.xml
app/src/main/res/layout/view_hongguo_mine.xml
app/src/main/res/layout/view_hongguo_drawer.xml
app/src/main/res/layout/view_hongguo_series_sheet.xml
app/src/main/res/drawable/bg_hg_actor_chip.xml
app/src/main/res/drawable/bg_hg_avatar_circle.xml
app/src/main/res/drawable/bg_hg_badge.xml
app/src/main/res/drawable/bg_hg_progress_dot.xml
app/src/main/res/drawable/bg_hg_refresh_pill.xml
app/src/main/res/drawable/bg_hg_bottom_scrim.xml
app/src/main/res/drawable/bg_hg_chip.xml
app/src/main/res/drawable/bg_hg_dialog_dark.xml
app/src/main/res/drawable/bg_hg_episode_chip.xml
app/src/main/res/drawable/bg_hg_ep_cell.xml
app/src/main/res/drawable/bg_hg_ep_dot.xml
app/src/main/res/drawable/bg_hg_ep_fav.xml
app/src/main/res/drawable/bg_hg_ep_fav_on.xml
app/src/main/res/drawable/bg_hg_ep_grab.xml
app/src/main/res/drawable/bg_hg_ep_sheet.xml
app/src/main/res/drawable/bg_hg_follow_badge.xml
app/src/main/res/drawable/bg_hg_follow_btn.xml
app/src/main/res/drawable/bg_hg_followed_btn.xml
app/src/main/res/drawable/bg_hg_grab.xml
app/src/main/res/drawable/bg_hg_login_btn.xml
app/src/main/res/drawable/bg_hg_panel_chip_on.xml
app/src/main/res/drawable/bg_hg_panel_track.xml
app/src/main/res/drawable/bg_hg_series_bar.xml
app/src/main/res/drawable/bg_hg_sheet.xml
app/src/main/res/drawable/ic_hg_back.xml
app/src/main/res/drawable/ic_hg_home.xml
app/src/main/res/drawable/ic_hg_mine.xml
app/src/main/res/drawable/ic_hg_close.xml
app/src/main/res/drawable/ic_hg_detail_arrow.xml
app/src/main/res/drawable/ic_hg_comment.xml
app/src/main/res/drawable/ic_hg_fullscreen.xml
app/src/main/res/drawable/ic_hg_heart.xml
app/src/main/res/drawable/ic_hg_heart_fill.xml
app/src/main/res/drawable/ic_hg_menu.xml
app/src/main/res/drawable/ic_hg_pause.xml
app/src/main/res/drawable/ic_hg_play.xml
app/src/main/res/drawable/ic_hg_playing_orange.xml
app/src/main/res/drawable/ic_hg_search.xml
app/src/main/res/drawable/ic_hg_share.xml
app/src/main/res/drawable/ic_hg_star.xml
app/src/main/res/drawable/ic_hg_star_fill.xml
app/src/mobile/res/drawable/ic_nav_duanju_outline.xml
app/src/mobile/res/drawable/ic_nav_duanju_filled.xml
"
for f in $RES_FILES; do
  [ -f "$f" ] || fail "红果本体资源找不到：$f"
done

# ---- 接线文件手术（LoginManager / HomeActivity / Manifest） ----
python3 - "$LM" "$HOME" "$MANIFEST" <<'PYEOF'
import re, sys

def load(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()

def save(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)

def cut_exact(text, block, what):
    if text.count(block) != 1:
        sys.exit("python: 红果剥离锚点对不上（%s，命中 %d 处）" % (what, text.count(block)))
    return text.replace(block, "")

def swap_exact(text, old, new, what):
    if text.count(old) != 1:
        sys.exit("python: 红果剥离锚点对不上（%s，命中 %d 处）" % (what, text.count(old)))
    return text.replace(old, new)

def find_line(lines, needle, what):
    hits = [i for i, ln in enumerate(lines) if needle in ln]
    if len(hits) != 1:
        sys.exit("python: 红果剥离定位对不上（%s，命中 %d 处）" % (what, len(hits)))
    return hits[0]

lm_path, home_path, manifest_path = sys.argv[1], sys.argv[2], sys.argv[3]

# ---- LoginManager：注册行 + requestLogin 红果分发支（整块删） ----
lm = load(lm_path)
lm = cut_exact(lm, "        register(new HongguoLoginProvider());\n", "红果注册行")
lm = cut_exact(lm, """        if (HongguoLoginProvider.ID.equals(providerId)) {
            return loginHongguoDelivered();
        }
""", "requestLogin 的红果分发支")
lines = lm.split("\n")

# ---- LoginManager：loginHongguo 实例方法（含 javadoc）整段删 ----
i = find_line(lines, "弹红果网页登录框（{@link PanLoginDialog}", "loginHongguo javadoc")
j = find_line(lines, "    private void openMethod", "openMethod")
if not (i < j and lines[i - 1].strip() == "/**"):
    sys.exit("python: loginHongguo 段落形状对不上")
lines = lines[:i - 1] + lines[j:]

# ---- LoginManager：红果静态桥整段删（loginHongguo 起、QQ 静态桥 javadoc 止） ----
i = find_line(lines, "弹红果网页登录框（异步）", "loginHongguo 静态桥 javadoc")
j = find_line(lines, "QQ 是否已登录（托管 Cookie 含账号标识＋音乐钥匙）", "QQ 静态桥 javadoc")
if not (i < j and lines[i].strip().startswith("/**") and lines[j].strip().startswith("/**")):
    sys.exit("python: 红果静态桥段落形状对不上")
lines = lines[:i] + lines[j:]
lm = "\n".join(lines)
save(lm_path, lm)

# ---- HomeActivity：import／短剧格注册行／点击分发支整删，条件位改回 false ----
# 注释里带本功能标识（图标名/类名）的两处一并收口成中性文案，免得终检 grep 命中注释残留。
home = load(home_path)
home = swap_exact(home, """        // 短剧格（红果）：只在正式版条件成立时才注册（未注册＝底栏根本没这一格，
        // 不留空位不摆占位）；在设置格之前插入，标签用他亲定的「短剧」
        // （nav_duanju），图标 ic_nav_duanju 双态（规格见 hongguo/duanju-tab-spec.md）。
""", """        // 短剧格（条件格）：条件成立时才在设置格之前注册，不留空位不摆占位。
""", "短剧格注册注释")
home = swap_exact(home, """     * 短剧格的正式版条件位：红果本体（HongguoFeedActivity 与前四阶段全套）
     * 已落地，正式版恒 true 开格；朋友版由 strip-hongguo.sh 在构建前把整套
     * 连同本格注册删净（此方法在朋友版临时检出里被改回 false），不在此留开关。
""", """     * 短剧格的条件位：朋友版整套剥离、此条件恒不成立，格子不注册；
     * 正式版源码里本体在册、恒 true 开格（剥离脚本在本检出里改回 false）。
""", "条件位 javadoc")
home = cut_exact(home, "import com.fongmi.android.tv.hongguo.ui.HongguoFeedActivity;\n", "HomeActivity 红果 import")
home = cut_exact(home, "            tabs.add(new BottomTabs.Spec(R.id.hongguo, R.drawable.ic_nav_duanju_outline, R.drawable.ic_nav_duanju_filled, getString(R.string.nav_duanju), true));\n", "短剧格注册行")
home = cut_exact(home, """        if (itemId == R.id.hongguo) {
            HongguoFeedActivity.start(this);
            overridePendingTransition(R.anim.xc_tab_fade_in, R.anim.xc_tab_fade_out);
            return true;
        }
""", "短剧格点击分发支")
home = cut_exact(home, """        // 红果「启动直达短剧」（抽屉开关，他亲定名）：冷启从桌面进来且开关开着，
        // 直接落红果刷剧页；首页不关、留在任务栈底下，刷剧页按返回就落回首页。
        if (savedInstanceState == null && isHongguoTabEnabled()
                && Intent.ACTION_MAIN.equals(getIntent() == null ? null : getIntent().getAction())
                && com.fongmi.android.tv.hongguo.ui.HgStore.isLaunchDirect()) {
            HongguoFeedActivity.start(this);
            overridePendingTransition(R.anim.xc_tab_fade_in, R.anim.xc_tab_fade_out);
        }
""", "启动直达短剧块")
hlines = home.split("\n")
k = find_line(hlines, "    private boolean isHongguoTabEnabled() {", "isHongguoTabEnabled")
if hlines[k + 1].strip() != "return true;":
    sys.exit("python: isHongguoTabEnabled 方法体形状对不上")
hlines[k + 1] = "        return false;"
save(home_path, "\n".join(hlines))

# ---- Manifest：红果五个 Activity 登记逐个删（每个必须恰好一处） ----
# 登记上方的说明注释同属本功能，整块一并删（留着会成无主注释、且带类名残留）。
manifest = load(manifest_path)
manifest = cut_exact(manifest, """        <!-- 红果刷剧页（三阶段）：点进就是竖屏刷剧流，独立一套界面与控制，
             不借 VideoActivity。底栏接线在五阶段，本阶段只静态入口。 -->
        <!-- 窗口不转（四阶段订正，照官方主界面口径）：screenOrientation="behind"
             ＋configChanges 全套自管，横屏观看在本窗内转 90°（HongguoFeedActivity
             的 landMode），不许再开真转窗口的横屏页（旧 HongguoLandActivity 已删）。 -->
""", "刷剧页登记注释")
for name in ("HongguoFeedActivity", "HongguoSearchActivity", "HongguoAuthorActivity"):
    pattern = r"<activity\s+android:name=\"com\.fongmi\.android\.tv\.hongguo\.ui\." + name + r"\"[^>]*/>"
    manifest, n = re.subn(pattern, "", manifest)
    if n != 1:
        sys.exit("python: Manifest 红果登记对不上（%s，命中 %d 处）" % (name, n))
save(manifest_path, manifest)
PYEOF

# ---- Nano：红果 process 的 import 与注册行删 ----
sed -i "/import com\.fongmi\.android\.tv\.server\.process\.HongguoStream;/d" "$NANO"
sed -i "/process\.add(new HongguoStream());/d" "$NANO"

# ---- 短剧格字符串删 ----
sed -i "/<string name=\"nav_duanju\">/d" "$MSTRINGS"

# ---- 本体文件整删 ----
rm -rf "$HGPKG" "$HGTEST"
rm "$PROVIDER" "$STREAM"
for f in $RES_FILES; do
  rm "$f"
done

# ---- 复查一：本功能专属标识全树零残留（共用件的泛 Hongguo 字样不在此列，见文件头白名单） ----
if grep -rn -E "com\.fongmi\.android\.tv\.hongguo|HongguoFeedActivity|HongguoSearchActivity|HongguoAuthorActivity|HgLibraryActivity|HgListActivity|HgTheaterActivity|HongguoLoginProvider|HongguoSmsLoginDialog|loginHongguo|isHongguoLoggedIn|getHongguoCookie|logoutHongguo|HongguoStream|ic_nav_duanju|nav_duanju" app/src/main app/src/mobile app/src/leanback app/src/test --include=*.java --include=*.xml; then
  fail "删完后仍有红果本功能残留"
fi

# ---- 复查二：共用件与框架必须完好 ----
for f in app/src/main/java/com/fongmi/android/tv/api/FqCommentSigner.java \
         app/src/main/java/com/fongmi/android/tv/api/HongguoCommentApi.java \
         app/src/main/java/com/fongmi/android/tv/api/HongguoDanmakuApi.java \
         app/src/mobile/java/com/fongmi/android/tv/ui/dialog/HongguoCommentDialog.java \
         app/src/mobile/java/com/fongmi/android/tv/ui/adapter/HongguoCommentAdapter.java; do
  [ -f "$f" ] || fail "剥完后共用件不见了：$f"
done
grep -qF "register(new Qr115Provider());" "$LM" || fail "剥完后 115 提供方注册不见了"
grep -qF "register(new KuaishouLoginProvider());" "$LM" || fail "剥完后快手提供方注册不见了"
grep -qF "register(new GoogleLoginProvider());" "$LM" || fail "剥完后谷歌提供方注册不见了"
grep -qF "register(new QQLoginProvider());" "$LM" || fail "剥完后 QQ 提供方注册不见了"
grep -qF "public static boolean requestLogin(String providerId)" "$LM" || fail "剥完后 requestLogin 通用分发不见了"
grep -qF "public static String getCookie(String providerId)" "$LM" || fail "剥完后 getCookie 通用口不见了"
grep -qF "process.add(new Proxy());" "$NANO" || fail "剥完后 Nano 的 Proxy process 不见了"
grep -qF "process.add(new Local());" "$NANO" || fail "剥完后 Nano 的 Local process 不见了"
grep -qF "BottomTabs.Spec(R.id.vod" "$HOME" || fail "剥完后底栏点播格注册不见了"
grep -qF "BottomTabs.Spec(R.id.setting" "$HOME" || fail "剥完后底栏设置格注册不见了"
grep -qF ".ui.activity.VideoActivity" "$MANIFEST" || fail "剥完后 Manifest 的 VideoActivity 登记不见了"
echo "朋友版：第三方红果已删除（本体包＋登录提供方＋代理解密 process＋底栏短剧格＋Manifest 登记，规则年代共用件保留）"
