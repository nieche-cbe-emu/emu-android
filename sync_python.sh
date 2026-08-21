#!/bin/bash
# 把模拟核心和两个 ctypes 绑定同步进 Chaquopy 的 python 源目录。
#
# 为什么是拷贝而不是引用：Chaquopy 只认自己 sourceSet 里的目录，
# 而 emu/ 和 cbelib/ 的真身在仓库根上，两边都要能改。所以构建前同步一次，
# **单向**（根 -> 安卓），不要反着改安卓这份。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
DST=$HERE/app/src/main/python

rm -rf "$DST/emu" "$DST/cbelib" "$DST/unicorn" "$DST/capstone"
cp -R "$ROOT/emu" "$DST/emu"
cp -R "$ROOT/cbelib" "$DST/cbelib"
find "$DST" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

# 两个绑定都是纯 Python + ctypes，直接搬包；原生部分由 jniLibs 提供，
# 所以把随包附带的桌面版 lib/ 删掉，省得 apk 里塞两份用不上的库。
PYSITE=$(python3 -c 'import unicorn, os; print(os.path.dirname(os.path.dirname(unicorn.__file__)))')
cp -R "$PYSITE/unicorn" "$DST/unicorn"
cp -R "$PYSITE/capstone" "$DST/capstone"
rm -rf "$DST/unicorn/lib" "$DST/unicorn/include" "$DST/capstone/lib" "$DST/capstone/include"
find "$DST" -name '*.dylib' -o -name '*.a' -o -name '*.dll' | xargs rm -f 2>/dev/null || true
find "$DST" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

# --- 安卓专属补丁 ---
# unicorn 的绑定里有一句 `canonicals = resources.files('unicorn') / 'lib'`，
# 结果直接塞进 pathlib.Path()。在 Chaquopy 上 resources.files() 返回的是
# AssetPath（apk 里的资源不是真实文件），Path() 吃不下，于是 **import 阶段就
# TypeError**——连 LIBUNICORN_PATH 都还没轮到。套一层 str() 就行；
# capstone 的同一处本来就写了 str()，没这个问题。
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
