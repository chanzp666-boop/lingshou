#!/bin/bash
# 灵手迭代清理（交接标准：迭代完成后清理构建产物/临时文件，避免同步冲突）。
# 只删本仓库构建中间产物；不碰 02-发布产物、git 历史、keystore 磁盘文件。
set -eu
cd "$(dirname "$0")/.."
rm -rf app/build build .gradle app/.cxx .externalNativeBuild captures
find . -name .DS_Store -delete
echo "cleaned: app/build build .gradle app/.cxx .externalNativeBuild captures .DS_Store"
# 可选：清理本机 /tmp 取证临时文件（截图/UI dump），加 --tmp 才执行
if [ "${1:-}" = "--tmp" ]; then
  rm -f /tmp/ls_car_screen.png /tmp/gate0_*.png /tmp/ls_window*.xml
  echo "cleaned: /tmp 取证临时文件"
fi