#!/bin/bash
# 1차 시연 영상 장면을 에뮬레이터 화면 녹화(webm, 소리 포함)로 찍는다. Mac 전용.
# 앱은 debug 빌드(로컬 서버 http://10.0.2.2:8080/v1, 가짜 내비 안내 도구 포함)를 쓰고,
# 서버는 로컬에서 검수 없이 바로 공개하는 모드로 장면마다 새 DB로 켠다. 배포 서버의 심사용 한마디는 건드리지 않는다.
#
# 사용: tools/demo/scenes.sh <1|2|3|4> <결과 폴더>
#   1 같은 방향 필터: 남쪽으로 달리며 광장의 북쪽 한마디 3개는 지나치고, 남쪽 한마디 1개만 묻고 듣는다
#   2 고마워요 → 꽃: 북쪽으로 달려 광장 3개를 묻고, 첫 한마디(고마워요 4개)를 들으며 고마워요를 눌러 꽃을 피운다
#   3 내비 끼어들기: 듣는 중에 내비 안내 음성이 나오면 멈췄다가, 안내가 끝나면 이어서 끝까지 재생한다
#   4 화면 밖: 내비 앱을 앞에 띄운 채 소리로 묻고, 핸들 재생 버튼으로 대답해 듣는다
#
# 준비:
#   - 에뮬레이터를 켜고 debug 앱을 설치한다(release 앱이 있으면 서명이 달라 먼저 지운다)
#   - adb forward tcp:8088 tcp:8088 뒤 브라우저로 http://127.0.0.1:8088 (Virtual Console)을 열어 둔다. 기어를 바꿀 때 쓴다
#   - 8080 포트를 비워 둔다(로컬 서버)
set -euo pipefail

scene=${1:?장면 번호(1~4)}
OUT=${2:?결과 폴더}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
APP=com.gildongmu.app
U=10
NAVI_APP=ai.umos.maps.android.navigation.app/.MainActivity
LEFT_X=1545 LEFT_Y=697   # 안내 카드 왼쪽 버튼(묻는 중: 들어 볼게요, 듣는 중: 고마워요). 2560x1440 화면 기준
START_X=1972 START_Y=546 # 첫 실행 "이 이름으로 시작할게요"
LOCAL=http://127.0.0.1:8080/v1
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
SERVER_BIN=$OUT/gildongmu-server
VIDEO=$OUT/scene$scene.webm
RECORDING=
DRIVE_PID=

say_step() { echo "[$(date +%H:%M:%S)] 장면 $scene: $*"; }

# 앱 로그(태그 Gildongmu)에 패턴이 n번 나올 때까지 기다린다.
wait_log() {
  local pat=$1 limit=${2:-60} n=${3:-1} i
  for ((i = 0; i < limit; i++)); do
    if [ "$("$ADB" logcat -d -s Gildongmu | grep -c -- "$pat" || true)" -ge "$n" ]; then return 0; fi
    sleep 1
  done
  echo "기다리던 앱 로그가 ${limit}초 안에 나오지 않았습니다: $pat" >&2
  return 1
}

start_server() {
  stop_server
  [ -x "$SERVER_BIN" ] || (cd "$ROOT/server" && go build -o "$SERVER_BIN" ./cmd/server)
  rm -f "$OUT/scene$scene.db"
  DEV_AUTO_APPROVE=true SQLITE_PATH="$OUT/scene$scene.db" PORT=8080 "$SERVER_BIN" >"$OUT/scene$scene.server.log" 2>&1 &
  echo $! >"$OUT/server.pid"
  for _ in $(seq 1 30); do
    curl -s -m 2 "$LOCAL/health" >/dev/null && return 0
    sleep 1
  done
  echo "로컬 서버가 켜지지 않았습니다($OUT/scene$scene.server.log)" >&2
  return 1
}

stop_server() {
  if [ -f "$OUT/server.pid" ]; then
    kill "$(cat "$OUT/server.pid")" 2>/dev/null || true
    rm -f "$OUT/server.pid"
  fi
}

seed() {
  rm -f "$OUT/scene$scene.seed.json"
  GILDONGMU_SERVER=$LOCAL GILDONGMU_SEED_STATE="$OUT/scene$scene.seed.json" \
    python3 -I "$ROOT/tools/demo/seed.py" >"$OUT/scene$scene.seed.log"
}

gear() {
  local r
  r=$(curl -s -m 10 -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=$1" || true)
  if ! grep -q "\"value\":\"$1\"" <<<"$r"; then
    echo "기어를 $1(으)로 바꾸지 못했습니다. Virtual Console 화면(http://127.0.0.1:8088)을 열어 두세요: $r" >&2
    return 1
  fi
}

place() { "$ADB" emu geo fix 126.9772 "$1" 30 8 0 >/dev/null; }

# 앱 데이터를 지워 새 기기로 시작한다(안내 5분 제한과 들은 기록도 처음부터). 권한은 미리 주고 닉네임은 기본값으로 정한다.
fresh_app() {
  "$ADB" shell am force-stop --user $U $APP || true
  "$ADB" shell pm clear --user $U $APP >/dev/null
  for p in android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION \
    android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS android.car.permission.CAR_SPEED; do
    "$ADB" shell pm grant --user $U $APP $p 2>/dev/null || true
  done
  "$ADB" logcat -c
  "$ADB" shell am start --user $U -n $APP/.MainActivity >/dev/null
  sleep 5
  "$ADB" shell input tap $START_X $START_Y
  wait_log "저장했어요" 15
}

push_navi() {
  [ -f "$OUT/navi.ogg" ] || "$ROOT/tools/demo/make_voice.sh" "Reed (한국어(한국))" "삼백 미터 앞에서 오른쪽 방향입니다." "$OUT/navi.ogg" >/dev/null
  "$ADB" shell run-as $APP --user $U mkdir -p files/demo
  "$ADB" exec-in run-as $APP --user $U sh -c 'cat > files/demo/navi.ogg' <"$OUT/navi.ogg"
}

rec_start() {
  rm -f "$VIDEO"
  "$ADB" emu screenrecord start --time-limit 170 "$VIDEO" >/dev/null
  RECORDING=1
  sleep 2
}

rec_stop() {
  if [ -n "$RECORDING" ]; then
    "$ADB" emu screenrecord stop >/dev/null || true
    RECORDING=
  fi
}

drive_bg() {
  "$ROOT/tools/demo/drive.sh" "$@" >/dev/null &
  DRIVE_PID=$!
}

cleanup() {
  rec_stop
  [ -n "$DRIVE_PID" ] && kill "$DRIVE_PID" 2>/dev/null || true
  gear Park 2>/dev/null || true
  stop_server
}
trap cleanup EXIT

say_step "로컬 서버를 새 DB로 켜고 시연용 한마디를 심어요"
start_server
seed

case $scene in
  1)
    gear Park
    place 37.5760
    fresh_app
    gear Drive
    say_step "녹화 시작: 남쪽으로 달려요(광장의 북쪽 한마디 3개는 지나쳐야 해요)"
    rec_start
    drive_bg south 37.5760 37.5660
    wait_log "볼까요" 90
    sleep 1
    "$ADB" shell input tap $LEFT_X $LEFT_Y
    wait_log "끝까지 들음" 30
    sleep 3
    ;;
  2)
    gear Park
    place 37.5680
    fresh_app
    gear Drive
    say_step "녹화 시작: 북쪽으로 달려 광장에서 묻게 해요"
    rec_start
    drive_bg north 37.5680 37.5760
    wait_log "볼까요" 90
    sleep 1
    "$ADB" shell input tap $LEFT_X $LEFT_Y
    wait_log "\[안내\] 듣기" 15
    sleep 3
    "$ADB" shell input tap $LEFT_X $LEFT_Y
    wait_log "고마워요 5개" 15
    sleep 5
    ;;
  3)
    gear Park
    place 37.5680
    fresh_app
    push_navi
    gear Drive
    say_step "녹화 시작: 듣는 중에 내비 안내를 끼워 넣어요"
    rec_start
    drive_bg north 37.5680 37.5760
    wait_log "볼까요" 90
    sleep 1
    "$ADB" shell input tap $LEFT_X $LEFT_Y
    wait_log "\[안내\] 듣기" 15
    sleep 2
    "$ADB" shell am broadcast --user $U -n $APP/.check.DemoNaviReceiver >/dev/null
    wait_log "끝까지 들음" 40
    sleep 3
    ;;
  4)
    gear Park
    place 37.5680
    fresh_app
    "$ADB" shell am start --user $U -n $NAVI_APP >/dev/null
    sleep 3
    gear Drive
    say_step "녹화 시작: 내비가 앞에 떠 있는 채로 소리로 묻고 핸들 버튼으로 대답해요"
    rec_start
    drive_bg north 37.5680 37.5760
    wait_log "볼까요" 90
    sleep 4
    "$ADB" shell input keyevent KEYCODE_MEDIA_PLAY
    wait_log "끝까지 들음" 30
    sleep 3
    ;;
  *)
    echo "장면 번호는 1~4입니다" >&2
    exit 1
    ;;
esac

rec_stop
"$ADB" logcat -d -s Gildongmu >"$OUT/scene$scene.app.log"
say_step "끝: $VIDEO"
