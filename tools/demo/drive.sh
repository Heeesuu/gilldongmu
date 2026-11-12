#!/bin/bash
# 에뮬레이터 GPS로 시험 경로를 시속 약 40km로 달린다(1초에 약 11m, 2분 남짓).
# 경로: 서울시청 앞(37.5650) ↔ 광화문(37.5790), 경도 126.9772
# 사용: tools/demo/drive.sh [north|south]   (기본 north)
#   먼저 Pleos 에뮬레이터 Virtual Console에서 기어를 D로 바꿔 두세요.
#   adb가 PATH에 없으면 ADB=~/Library/Android/sdk/platform-tools/adb 처럼 지정합니다.
set -euo pipefail

way=${1:-north}
ADB=${ADB:-adb}
LON=126.9772
KNOTS=21.6 # 시속 40km

case $way in
  north) points=$(seq -f %.4f 37.5650 0.0001 37.5790) ;;
  south) points=$(seq -f %.4f 37.5790 -0.0001 37.5650) ;;
  *) echo "사용: $0 [north|south]" >&2; exit 1 ;;
esac

echo "$way 방향으로 달립니다. 멈추려면 Ctrl+C"
for lat in $points; do
  "$ADB" emu geo fix "$LON" "$lat" 30 8 "$KNOTS" >/dev/null
  sleep 1
done
# 도착하면 멈춘 위치를 한 번 더 보낸다(속도 0)
"$ADB" emu geo fix "$LON" "$lat" 30 8 0 >/dev/null
echo "도착: $lat"
