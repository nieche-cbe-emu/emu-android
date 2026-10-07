#!/bin/bash

set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/.." && pwd)
DST=$HERE/app/src/main/python

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
