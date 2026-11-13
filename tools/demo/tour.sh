#!/bin/bash
# 1차 시연 영상용 "전체 기능 둘러보기"를 장면별로 녹화한다(Mac 전용). 사람 목소리는 나중에 입힌다.
# - 소리: 앱이 내는 안내 TTS("앞에 길동무 3명이 있어요. 들어 볼까요?")와 알림음, 내비 끼어들기 장면의 내비 안내만 담긴다.
# - 한마디(남의 것·내 것)는 무음이다: 남의 한마디는 같은 길이의 무음으로 심고(seed.py 무음 모드), 내 한마디는 마이크를 끊고 녹음한다.
# - 대답("응", "고마워")은 화면 버튼·핸들 키로 누른다. 사람이 그 자리에 목소리를 입힌다.
# - 한 운전자(처음 고른 캐릭터)로 끝까지 찍는다. 장면마다 앱을 껐다 켜서 "5분에 한 번 안내" 제한만 푼다.
#
# 사용: tools/demo/tour.sh <setup|1|2|3|4|5|6|7> <결과 폴더>
#   setup 로컬 서버(새 DB) 켜기, 무음 한마디 심기, 에뮬레이터 마이크 끊기
#   1 첫 실행: 권한 허용 → 닉네임·캐릭터 꾸미기 → 시작
#   2 주행 → 앞에 길동무 3명 → 들어 볼게요 → 고마워요(꽃) → 다음 길동무 → 그만 들을게요
#   3 반대 방향: 북쪽으로 남겨진 한마디 위를 남쪽으로 지나도 묻지 않음
#   4 내비 끼어들기: 듣는 중 내비 안내가 나오면 멈췄다가 이어서 재생
#   5 화면 밖: 내비를 앞에 띄운 채 소리로 묻고 핸들 재생 버튼으로 대답
#   6 나도 한마디 남기기: 달리며 녹음 → 씨앗 → 새싹
#   7 정차 → 내 발자취(남긴 한마디·들은 사람·고마워요) → 설정
# 준비: 에뮬레이터 + debug 앱, adb forward tcp:8088 tcp:8088 뒤 Virtual Console(http://127.0.0.1:8088) 화면을 열어 둔다.
set -euo pipefail

step=${1:?setup 또는 장면 번호(1~7)}
OUT=${2:?결과 폴더}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
APP=com.gildongmu.app
U=10
NAVI_APP=ai.umos.maps.android.navigation.app/.MainActivity
LOCAL=http://127.0.0.1:8080/v1
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
DB=$OUT/tour.db
VIDEO=$OUT/tour$step.webm
RECORDING= DRIVE_PID=

# 화면 좌표(2560x1440)
ALLOW_X=1706 ALLOW_Y=644            # 권한 창 첫 번째 버튼(앱 사용 중에만 허용 / 허용)
COLOR_BLUE_X=1184 COLOR_Y=482       # 내 길동무 색(파랑)
HAT_X=1090 SCARF_X=1194 BAG_X=1300 ITEM_Y=568
REROLL_X=2424 REROLL_Y=356          # 닉네임 다시 뽑기
START_X=1972 START_Y=546            # 이 이름으로 시작할게요
LEFT_X=1545 RIGHT_X=2267 CARD_Y=697 # 안내 카드 버튼: 왼쪽(들어 볼게요/고마워요), 오른쪽(그만 들을게요)
RECORD_X=2076 RECORD_Y=1218         # 나도 한마디 남기기
TAB_ROAD_X=940 TAB_MINE_X=1098 TAB_SETTINGS_X=1268 TAB_Y=210

say_step() { echo "[$(date +%H:%M:%S)] $*"; }
now() { python3 -I -c 'import time; print(f"{time.time():.3f}")'; }
tap() { "$ADB" shell input tap "$1" "$2"; }

wait_log() { # 패턴 제한초 [횟수]
  local pat=$1 limit=${2:-60} n=${3:-1} i
  for ((i = 0; i < limit; i++)); do
    if [ "$("$ADB" logcat -d -s Gildongmu | grep -c -- "$pat" || true)" -ge "$n" ]; then return 0; fi
    sleep 1
  done
  echo "기다리던 앱 로그가 ${limit}초 안에 나오지 않았습니다: $pat" >&2
  return 1
}

gear() { # Virtual Console 웹 화면이 잠깐 비활성(emulator_inactive)일 수 있어 몇 번 다시 시도한다
  local r i
  for i in 1 2 3 4 5 6; do
    r=$(curl -s -m 10 -X PUT "http://127.0.0.1:8088/vehicle/gear?gear=$1" || true)
    grep -q "\"value\":\"$1\"" <<<"$r" && return 0
    sleep 2
  done
  echo "기어를 $1(으)로 못 바꿨습니다. Virtual Console 화면을 열어 두세요: $r" >&2
  return 1
}
place() { "$ADB" emu geo fix 126.9772 "$1" 30 8 0 >/dev/null; }
drive_bg() { "$ROOT/tools/demo/drive.sh" "$@" >/dev/null & DRIVE_PID=$!; }

# 앱을 껐다 켠다(캐릭터·기기는 그대로, "5분에 한 번 안내" 제한만 풀림)
restart_app() {
  "$ADB" shell am force-stop --user $U $APP || true
  "$ADB" logcat -c
  "$ADB" shell am start --user $U -n $APP/.MainActivity >/dev/null
  wait_log "길동무 시작" 15
  sleep 3
}

rec_start() {
  rm -f "$VIDEO"
  "$ADB" emu screenrecord start --time-limit 175 "$VIDEO" >/dev/null
  echo "screen $(now)" >"$OUT/tour$step.times"
  RECORDING=1
  sleep 1
}
rec_stop() { if [ -n "$RECORDING" ]; then "$ADB" emu screenrecord stop >/dev/null || true; RECORDING=; fi; }
mark() { echo "$1 $(now)" >>"$OUT/tour$step.times"; } # 편집용: 화면을 바꾼 시각
finish() {
  rec_stop
  "$ADB" logcat -d -v epoch -s Gildongmu >"$OUT/tour$step.app.log"
  say_step "장면 $step 끝: $VIDEO"
}
cleanup() {
  rec_stop
  [ -n "$DRIVE_PID" ] && kill "$DRIVE_PID" 2>/dev/null || true
}

# 대답을 기다리기 시작한 뒤(안내 TTS가 끝난 뒤) 조금 있다가 누른다. 사람이 이 사이에 "응"을 입힌다.
answer_after_prompt() {
  wait_log "볼까요" 90
  wait_log "대답을 듣고 있어요 (8초)" 15
  sleep 1.5
}

if [ "$step" = setup ]; then
  if [ -f "$OUT/server.pid" ]; then kill "$(cat "$OUT/server.pid")" 2>/dev/null || true; sleep 1; fi
  [ -x "$OUT/gildongmu-server" ] || (cd "$ROOT/server" && go build -o "$OUT/gildongmu-server" ./cmd/server)
  rm -f "$DB" "$OUT/seed.json"
  DEV_AUTO_APPROVE=true SQLITE_PATH="$DB" PORT=8080 nohup "$OUT/gildongmu-server" >"$OUT/server.log" 2>&1 &
  echo $! >"$OUT/server.pid"
  for _ in $(seq 1 30); do curl -s -m 2 "$LOCAL/health" >/dev/null && break; sleep 1; done
  "$ADB" emu avd hostmicoff >/dev/null
  GILDONGMU_SERVER=$LOCAL GILDONGMU_SEED_STATE="$OUT/seed.json" GILDONGMU_SEED_SET=tour GILDONGMU_SEED_SILENT=1 \
    python3 -I "$ROOT/tools/demo/seed.py" >"$OUT/seed.log"
  say_step "준비 끝: 로컬 서버(새 DB), 무음 한마디 5개, 에뮬레이터 마이크 끊음"
  exit 0
fi

trap cleanup EXIT
case $step in
  1)
    gear Park
    place 37.5690
    "$ADB" shell am force-stop --user $U $APP || true
    "$ADB" shell pm clear --user $U $APP >/dev/null
    "$ADB" logcat -c
    say_step "녹화 시작: 첫 실행"
    rec_start
    "$ADB" shell am start --user $U -n $APP/.MainActivity >/dev/null
    sleep 4
    for _ in 1 2 3; do tap $ALLOW_X $ALLOW_Y; sleep 2.5; done # 위치, 마이크, 알림
    sleep 1.5
    tap $REROLL_X $REROLL_Y; sleep 2
    tap $COLOR_BLUE_X $COLOR_Y; sleep 1.5
    tap $SCARF_X $ITEM_Y; sleep 1.5
    tap $BAG_X $ITEM_Y; sleep 1.5
    tap $HAT_X $ITEM_Y; sleep 2
    tap $START_X $START_Y
    wait_log "저장했어요" 15
    sleep 4
    finish
    # 내비 끼어들기 장면에서 쓸 가짜 내비 안내 음성을 앱 저장소에 넣어 둔다
    [ -f "$OUT/navi.ogg" ] || "$ROOT/tools/demo/make_voice.sh" "Yuna" "삼백 미터 앞에서 오른쪽 방향입니다." "$OUT/navi.ogg" >/dev/null
    "$ADB" shell run-as $APP --user $U mkdir -p files/demo
    "$ADB" exec-in run-as $APP --user $U sh -c 'cat > files/demo/navi.ogg' <"$OUT/navi.ogg"
    ;;
  2)
    gear Park
    place 37.5690
    restart_app
    say_step "녹화 시작: 주행 → 앞에 길동무 3명 → 듣기 → 고마워요 → 꽃 → 다음 → 그만"
    rec_start
    sleep 2
    gear Drive
    drive_bg north 37.5690 37.5760
    answer_after_prompt
    tap $LEFT_X $CARD_Y # 들어 볼게요
    wait_log "\[안내\] 듣기" 15
    wait_log "끝까지 들음" 20
    sleep 1.2
    tap $LEFT_X $CARD_Y # 다 듣고 나서 고마워요(사람이 "고마워"를 입힌다)
    wait_log "고마워요 5개" 10
    wait_log "끝까지 들음" 25 2 # 두 번째 길동무까지 듣고
    sleep 1
    tap $RIGHT_X $CARD_Y # 그만 들을게요
    sleep 4
    finish
    ;;
  3)
    gear Park
    place 37.5830
    restart_app
    say_step "녹화 시작: 반대 방향(남쪽)으로 지나기"
    rec_start
    gear Drive
    drive_bg south 37.5830 37.5785
    wait "$DRIVE_PID" || true
    DRIVE_PID=
    sleep 2
    finish
    if grep -q "볼까요" "$OUT/tour3.app.log"; then echo "주의: 반대 방향인데 안내가 나왔습니다" >&2; fi
    ;;
  4)
    gear Park
    place 37.5735
    restart_app
    say_step "녹화 시작: 듣는 중 내비 안내 끼어들기"
    rec_start
    gear Drive
    drive_bg north 37.5735 37.5785
    answer_after_prompt
    tap $LEFT_X $CARD_Y
    wait_log "\[안내\] 듣기" 15
    sleep 2
    "$ADB" shell am broadcast --user $U -n $APP/.check.DemoNaviReceiver >/dev/null
    wait_log "끝까지 들음" 40
    sleep 4
    finish
    ;;
  5)
    gear Park
    place 37.5770
    restart_app
    "$ADB" shell am start --user $U -n $NAVI_APP >/dev/null
    sleep 3
    say_step "녹화 시작: 내비를 앞에 띄운 채 소리로 묻고 핸들 버튼으로 대답"
    rec_start
    gear Drive
    drive_bg north 37.5770 37.5815
    answer_after_prompt
    "$ADB" shell input keyevent KEYCODE_MEDIA_PLAY
    wait_log "\[안내\] 듣기" 15
    wait_log "끝까지 들음" 30
    sleep 3
    finish
    ;;
  6)
    gear Park
    place 37.5815
    restart_app
    say_step "녹화 시작: 달리며 나도 한마디 남기기"
    rec_start
    gear Drive
    drive_bg north 37.5815 37.5870
    wait_log "진행 방향을 잡았어요" 20
    sleep 4
    tap $RECORD_X $RECORD_Y
    wait_log "\[녹음\] 시작" 10
    sleep 7 # 사람이 이 사이에 한마디를 입힌다
    tap $RECORD_X $RECORD_Y
    wait_log "한마디를 남겼어요" 20
    wait_log "길 위에 섰어요" 20
    sleep 5
    finish
    ;;
  7)
    say_step "녹화 시작: 정차 → (다른 운전자들이 듣고 고마워함) → 꽃 소식 → 내 발자취 → 설정"
    "$ADB" logcat -c
    rec_start
    sleep 2
    gear Park
    sleep 2
    # 내가 남긴 한마디(가장 최근)를 다른 운전자 12명이 끝까지 듣고 5명이 고마워한 것으로 만든다(꽃).
    # 앱은 1분마다 내 한마디를 다시 읽으므로, 꽃 소식이 화면에 뜰 때까지 기다린다.
    id=$(sqlite3 -readonly "$DB" "select id from messages order by created_at desc limit 1;")
    for i in $(seq 1 12); do
      dev=$(uuidgen | tr 'A-Z' 'a-z')
      curl -s -m 10 -o /dev/null -X POST -H "X-Device-Id: $dev" -H "Content-Type: application/json" \
        -d '{"result":"completed","played_ms":7000}' "$LOCAL/messages/$id/listen"
      [ "$i" -le 5 ] && curl -s -m 10 -o /dev/null -X POST -H "X-Device-Id: $dev" "$LOCAL/messages/$id/thanks"
    done
    wait_log "꽃을 들었어요" 75
    sleep 5
    mark mine
    tap $TAB_MINE_X $TAB_Y
    sleep 7
    mark settings
    tap $TAB_SETTINGS_X $TAB_Y
    sleep 6
    mark road
    tap $TAB_ROAD_X $TAB_Y
    sleep 3
    finish
    ;;
  *)
    echo "setup 또는 1~7" >&2
    exit 1
    ;;
esac
