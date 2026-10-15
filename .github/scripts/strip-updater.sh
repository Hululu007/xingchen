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

# 复查：主源码里不许再有任何地方引用 Updater 类
if grep -rn "com\.fongmi\.android\.tv\.Updater\|Updater\.create()" app/src/main/java app/src/mobile/java --include=*.java; then
  fail "删完后仍有 Updater 引用残留"
fi
echo "朋友版：检测更新已删除（Updater 类与两个入口）"
