#!/bin/bash
# Mac 합성 목소리로 한국어 문장을 Ogg Opus 파일로 만든다(시연·시험용, Mac 전용).
# 사용: tools/demo/make_voice.sh "<목소리>" "<문장>" 결과.ogg
#   목소리 예: Yuna, "Eddy (한국어(한국))", "Sandy (한국어(한국))", "Grandpa (한국어(한국))"
#   목록: say -v '?' | grep ko_KR
set -euo pipefail

if [ $# -ne 3 ]; then
  echo "사용: $0 \"<목소리>\" \"<문장>\" 결과.ogg" >&2
  exit 1
fi
voice=$1
text=$2
out=$3
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -f "$tmp"/v.aiff "$tmp"/v.wav "$tmp"/v.caf; rmdir "$tmp"' EXIT

say -v "$voice" -o "$tmp/v.aiff" "$text"
afconvert -f WAVE -d LEI16@48000 -c 1 "$tmp/v.aiff" "$tmp/v.wav"
afconvert -f caff -d opus "$tmp/v.wav" "$tmp/v.caf"
python3 -I "$here/caf_to_ogg_opus.py" "$tmp/v.caf" "$out"
