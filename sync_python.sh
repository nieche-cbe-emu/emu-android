#!/bin/bash
# 把 Rust 核心的 Python 绑定同步进 Chaquopy 的 python 源目录。
#
# 只有一个文件：nieche.py（ctypes 接 libnieche.so）。
# 以前这里要搬整个 emu/ 和 cbelib/，还要连 unicorn 和 capstone 的 Python 绑定
# 一起搬、再给 unicorn 打一个 AssetPath 补丁——那些是 Python 核心才需要的。
# 现在外壳只跑 Rust 核心，全都不需要了。
#
# 为什么是拷贝而不是引用：Chaquopy 只认自己 sourceSet 里的目录，
# 而绑定的真身在 emu-core-rs 里。所以构建前同步一次，
# **单向**（核心 -> 安卓），不要反着改安卓这份。
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
DST=$HERE/app/src/main/python

# 绑定在单仓库布局下是 rust/python/nieche.py；独立发布时和本仓库同级的
# emu-core-rs/python/nieche.py。谁在用谁。
SRC=""
for c in "$ROOT/rust/python/nieche.py" "$ROOT/../emu-core-rs/python/nieche.py"; do
  [ -f "$c" ] && SRC="$c" && break
done
[ -n "$SRC" ] || { echo "找不到 nieche.py —— emu-core-rs 放在同级目录了吗？"; exit 1; }

rm -rf "$DST/emu" "$DST/cbelib" "$DST/unicorn" "$DST/capstone"
mkdir -p "$DST"
cp "$SRC" "$DST/nieche.py"
find "$DST" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

echo "已同步 $SRC -> $DST/nieche.py"
du -sh "$DST"
