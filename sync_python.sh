#!/bin/bash

set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
DST=$HERE/app/src/main/python

rm -rf "$DST/emu" "$DST/cbelib" "$DST/unicorn" "$DST/capstone"
cp -R "$ROOT/emu" "$DST/emu"
cp -R "$ROOT/cbelib" "$DST/cbelib"
find "$DST" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

PYSITE=$(python3 -c 'import unicorn, os; print(os.path.dirname(os.path.dirname(unicorn.__file__)))')
cp -R "$PYSITE/unicorn" "$DST/unicorn"
cp -R "$PYSITE/capstone" "$DST/capstone"
rm -rf "$DST/unicorn/lib" "$DST/unicorn/include" "$DST/capstone/lib" "$DST/capstone/include"
find "$DST" -name '*.dylib' -o -name '*.a' -o -name '*.dll' | xargs rm -f 2>/dev/null || true
find "$DST" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

python3 - "$DST" <<'PATCH'
import sys, pathlib
f = pathlib.Path(sys.argv[1]) / "unicorn" / "unicorn_py3" / "unicorn.py"
s = f.read_text()
old = "    canonicals = resources.files('unicorn') / 'lib'"
new = ("    # 安卓(Chaquopy)上这里返回 AssetPath，pathlib.Path() 吃不下，\n"
       "    # 会在 import 期就抛 TypeError。转成字符串即可；找不到库不要紧，\n"
       "    # 后面还有 LIBUNICORN_PATH 和裸名 libunicorn.so 两条路。\n"
       "    canonicals = str(resources.files('unicorn') / 'lib')")
if old in s:
    f.write_text(s.replace(old, new, 1))
    print("  已给 unicorn 打上 AssetPath 补丁")
elif new.splitlines()[-1] in s:
    print("  unicorn 补丁已在")
else:
    raise SystemExit("!! unicorn 的加载器改版了，补丁没打上——请核对")
PATCH

echo "已同步到 $DST"
du -sh "$DST"
