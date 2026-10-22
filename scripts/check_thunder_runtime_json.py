#!/usr/bin/env python3
# 校验 thunder-runtime 各 flavor 的 assets/runtime.json 与
# thunder/src/main/jniLibs 的 .so 实测值一致，并与 RuntimeModule /
# PrepareRuntimeTask 的校验口径对齐：
#  - files 按 path 排序，恰好是 THUNDER_LIBRARIES 两项；
#  - library / sha256 / size 指向主库 libxl_thunder_sdk.so；
#  - module / packageName / compatibilityId / entryClass / javaApi /
#    versionCode / versionName 与 thunder-runtime/build.gradle 一致；
#  - metadata 总长不超过 64KB（RuntimeModule.readMetadata 上限）。
# .so 换了必须重算重写这两个 json 再编包，否则宿主验包摘要对不上。
# 用法：python3 scripts/check_thunder_runtime_json.py
import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ERRORS = []


def fail(msg):
    ERRORS.append(msg)
    print("FAIL:", msg)


def main():
    for abi, flavor in [("arm64-v8a", "arm64_v8a"), ("armeabi-v7a", "armeabi_v7a")]:
        meta_path = ROOT / f"thunder-runtime/src/{flavor}/assets/runtime.json"
        if not meta_path.is_file():
            fail(f"{abi}: 缺 {meta_path}")
            continue
        meta = json.loads(meta_path.read_text())
        if len(meta_path.read_bytes()) > 64 * 1024:
            fail(f"{abi}: runtime.json 超 64KB")
        files = []
        for name in ["libxl_stat.so", "libxl_thunder_sdk.so"]:
            blob = ROOT / f"thunder/src/main/jniLibs/{abi}/{name}"
            if not blob.is_file():
                fail(f"{abi}: 缺 .so {blob}")
                continue
            data = blob.read_bytes()
            files.append({"path": f"lib/{abi}/{name}", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        files.sort(key=lambda f: f["path"])
        primary = next((f for f in files if f["path"].endswith("libxl_thunder_sdk.so")), None)
        expected = {
            "module": "thunder",
            "packageName": "com.fongmi.runtime.thunder",
            "versionCode": 6,
            "versionName": "1.1",
            "compatibilityId": "catvod-thunder-plugin-v1",
            "abi": abi,
            "entryClass": "com.xunlei.downloadlib.RuntimeEntry",
            "javaApi": 1,
            "library": f"lib/{abi}/libxl_thunder_sdk.so",
            "files": files,
        }
        if primary is not None:
            expected["sha256"] = primary["sha256"]
            expected["size"] = primary["size"]
        for key, value in expected.items():
            if meta.get(key) != value:
                fail(f"{abi}: {key} 不一致（json={meta.get(key)!r} 实测={value!r}）")
        extra = set(meta) - set(expected)
        if extra:
            fail(f"{abi}: json 多余字段 {sorted(extra)}")
    if ERRORS:
        print(f"check_thunder_runtime_json: {len(ERRORS)} 项不一致")
        return 1
    print("check_thunder_runtime_json: 两 flavor runtime.json 与 .so 实测一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
