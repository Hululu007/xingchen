#!/usr/bin/env bash
# 朋友版专用：构建前把「检测更新」整套从源码里删掉（不是关掉）。
# 只在 CI 的临时检出上执行，仓库源码不动。任何一处对不上就让构建失败，
# 防止哪天重构改了写法、朋友版悄悄带着更新功能发出去。
set -euo pipefail

fail() { echo "::error title=朋友版删更新失败::$1"; exit 1; }

UPDATER=app/src/main/java/com/fongmi/android/tv/Updater.java
[ -f "$UPDATER" ] || fail "找不到 Updater.java"
rm "$UPDATER"

BASE=app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java
grep -q "import com.fongmi.android.tv.Updater;" "$BASE" || fail "BaseActivity 的 Updater import 对不上"
grep -q "Updater.create().resume(this);" "$BASE" || fail "BaseActivity 的 resume 调用对不上"
sed -i "/import com\.fongmi\.android\.tv\.Updater;/d" "$BASE"
sed -i "/Updater\.create()\.resume(this);/d" "$BASE"

SETA=app/src/mobile/java/com/fongmi/android/tv/ui/activity/XingChenSettingsActivity.java
grep -q "import com.fongmi.android.tv.Updater;" "$SETA" || fail "设置页的 Updater import 对不上"
grep -q "Updater.create().force().start(this)" "$SETA" || fail "设置页的检查更新调用对不上"
sed -i "/import com\.fongmi\.android\.tv\.Updater;/d" "$SETA"
sed -i "s/AboutDialog\.show(this, () -> Updater\.create()\.force()\.start(this))/AboutDialog.show(this, null)/" "$SETA"

APP=app/src/main/java/com/fongmi/android/tv/App.java
grep -q "Updater.create().notifyModules();" "$APP" || fail "App 的模块更新提示调用对不上"
sed -i "/Updater\\.create()\\.notifyModules();/d" "$APP"

# 复查（mobile 构建范围）：mobile 源集里不许再有任何地方引用 Updater 类。
# 口径定稿（X-20 默认口径，不再留待定）：leanback 终检维持“只 warning、不失败”。
# 定成这样的理由：① 工作流只编 assembleMobileArm64_v8aRelease，leanback 源集
# 不在本次构建内，warning 不影响本次产物；② fail-closed 仍在：日后若编 leanback
# 朋友版，Updater.java 已删、leanback 两处引用（BaseActivity:161、
# SettingActivity:244）还在，编译会直接失败而不是悄悄带更新功能发出去，
# 不是泄漏；③ 现在就把 leanback 纳入剥离等于改 TV 端代码路径，而本工作流
# 根本不编 leanback、改完无法验证，风险大于收益。编 leanback 朋友版之前，
# 须先把 leanback 两处纳入与 mobile 同款的剥离（删 import、删 resume 调用、
# AboutDialog 回调改 null）并把本段 warning 改回 fail，缺一不可。
if grep -rn "com\.fongmi\.android\.tv\.Updater\|Updater\.create()" app/src/main/java app/src/mobile/java --include=*.java; then
  fail "删完后仍有 Updater 引用残留"
fi
if grep -rn "com\.fongmi\.android\.tv\.Updater\|Updater\.create()" app/src/leanback/java --include=*.java; then
  echo "::warning title=朋友版 leanback 仍有 Updater 引用::leanback 源集不在 mobile 构建内，本次不失败；编 leanback 朋友版前须先剥离"
fi
echo "朋友版：检测更新已删除（Updater 类与两个入口）"
