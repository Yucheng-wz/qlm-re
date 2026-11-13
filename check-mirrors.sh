#!/usr/bin/env bash
# 取了吗 GitHub 分发链路自检（Windows Git Bash / macOS / Linux 通用）
# 用法：bash check-mirrors.sh   （按实际仓库改下面三个变量）

OWNER=yanchuan-qlmwz
REPO=qlm-release
TAG=v3.0.9
APK=qlm_re_3.0.9.apk

RAW="https://raw.githubusercontent.com/$OWNER/$REPO/main/version.json"
REL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$APK"

echo "== version.json =="
for p in "https://ghproxy.net/" "https://ghfast.top/" "https://gh-proxy.com/"; do
  code=$(curl -s -o /tmp/qlm_v.json -w '%{http_code}' -m 10 -L "${p}${RAW}")
  printf "%-22s %s  %s\n" "$p" "$code" "$(head -c 60 /tmp/qlm_v.json 2>/dev/null | tr -d '\n')"
done
printf "%-22s %s\n" "jsdelivr" "$(curl -s -o /dev/null -w '%{http_code}' -m 10 -L "https://gcore.jsdelivr.net/gh/$OWNER/$REPO@main/version.json")"
printf "%-22s %s\n" "direct(raw)" "$(curl -s -o /dev/null -w '%{http_code}' -m 8 -L "$RAW")"

echo
echo "== APK（只取前 1KB 验证可达）=="
for p in "https://ghproxy.net/" "https://ghfast.top/" "https://gh-proxy.com/"; do
  out=$(curl -s -o /dev/null -w '%{http_code}/%{size_download}' -m 25 -L -r 0-1023 "${p}${REL}")
  printf "%-22s %s\n" "$p" "$out"
done
printf "%-22s %s\n" "direct(github)" "$(curl -s -o /dev/null -w '%{http_code}' -m 8 -L -r 0-1023 "$REL")"

echo
echo "提示：json 200 且能看到 versionCode；APK 显示 206 或 200 并有字节数，即为可用。"
