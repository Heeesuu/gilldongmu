"""배포 서버의 시험 경로에 시연·심사용 한마디를 심는다(Mac 전용: 합성 목소리를 씀).

시험 경로: 서울시청 앞(37.5650) → 광화문(37.5790), 경도 126.9772, 북쪽으로 약 1.6km. tools/demo/drive.sh가 이 길을 달린다.
- 광화문 광장 앞(북쪽 방향) 3개를 50m 안에 모아 둔다 → "앞에 길동무 3명이 있어요. 들어 볼까요?"
  첫 한마디는 고마워요 4개를 미리 받아 둬서, 듣고 "고마워"라고 하면 꽃이 핀다(기획서 1차 시연 2번).
- 남쪽 방향 1개 → 북쪽으로 갈 때는 안 들리고, 남쪽으로 갈 때만 들린다(시연 1번, 방향 필터).

한마디마다 다른 기기 ID로 올리고, 검수(Google STT → 비속어 사전 → Claude)를 통과해 공개될 때까지 기다린다.
길 상황(road)으로 분류되면 3시간 뒤 사라지므로, 인사·장소로 분류됐는지도 확인한다.
심은 기기 ID는 tools/demo/seeded.local.json에 남긴다(저장소에 올리지 않음). 지울 때는 unseed.py가 쓴다.
다시 실행하면 이미 공개된 한마디는 그대로 두고, 공개되지 않았거나 아래 목록에서 빠진 것만 지운 뒤 모자란 것을 심는다.
합성 목소리 중 일부(Sandy)는 음성 인식이 잘 못 알아들어 보류됐다. 보류되면 목소리나 문장을 바꿔 다시 실행한다.

사용: GILDONGMU_SERVER=https://…/v1 python3 -I tools/demo/seed.py
시연 영상용 로컬 서버(http://127.0.0.1:8080/v1)에도 쓸 수 있다. 이때는 GILDONGMU_SEED_STATE로 기록 파일을 따로 정해
배포 서버의 기록(seeded.local.json)을 덮지 않게 한다(tools/demo/scenes.sh가 그렇게 부른다).
"""
import json
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.environ.get("GILDONGMU_SEED_STATE") or os.path.join(HERE, "seeded.local.json")
LOCAL_PREFIXES = ("http://127.0.0.1", "http://localhost")
OUT = os.path.join(HERE, "out")
LON = 126.9772

# (이름, 목소리, 문장, 위도, 방향, 미리 받을 고마워요 수)
MESSAGES = [
    ("광장-은행나무", "Yuna", "광화문 광장 은행나무가 노랗게 물들었어요. 신호 기다릴 때 한번 보세요.", 37.5728, 0.0, 4),
    ("광장-응원", "Eddy (한국어(한국))", "다들 오늘도 안전운전하세요. 좋은 하루 보내세요!", 37.5730, 0.0, 0),
    ("광장-출근길", "Grandma (한국어(한국))", "광화문 앞을 지날 때면 늘 마음이 든든해져요. 오늘도 좋은 길 되세요.", 37.5732, 0.0, 0),
    ("남쪽-퇴근길", "Grandpa (한국어(한국))", "퇴근길 다들 고생 많으셨어요. 조심히 들어가세요.", 37.5690, 180.0, 0),
]
LONG_LIVED = {"greeting", "place"}


def request(base, method, path, device, body=None, content_type=None):
    req = urllib.request.Request(base + path, data=body, method=method)
    req.add_header("X-Device-Id", device)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def ogg_duration_ms(path):
    """Ogg Opus의 마지막 위치 값과 OpusHead의 pre-skip으로 길이를 잰다."""
    d = open(path, "rb").read()
    pre_skip = struct.unpack("<H", d[d.index(b"OpusHead") + 10:][:2])[0]
    last = d.rindex(b"OggS")
    granule = struct.unpack("<q", d[last + 6:last + 14])[0]
    return int((granule - pre_skip) / 48)


def multipart(meta, audio):
    b = uuid.uuid4().hex
    body = (
        f"--{b}\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n{json.dumps(meta)}\r\n".encode()
        + f"--{b}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"voice.ogg\"\r\nContent-Type: audio/ogg\r\n\r\n".encode()
        + audio + f"\r\n--{b}--\r\n".encode()
    )
    return body, f"multipart/form-data; boundary={b}"


def wait_review(base, device, message_id, limit_sec=90):
    for _ in range(limit_sec // 3):
        time.sleep(3)
        status, data = request(base, "GET", "/me/messages", device)
        if status != 200:
            continue
        for m in data["messages"]:
            if m["id"] == message_id and m["review"] != "pending":
                return m
    return None


def main():
    base = os.environ.get("GILDONGMU_SERVER", "").rstrip("/")
    if not (base.startswith("https://") or base.startswith(LOCAL_PREFIXES)) or not base.endswith("/v1"):
        sys.exit("GILDONGMU_SERVER=https://…/v1 를 넣어 주세요(로컬 서버는 http://127.0.0.1:8080/v1)")
    if base.startswith(LOCAL_PREFIXES) and "GILDONGMU_SEED_STATE" not in os.environ:
        sys.exit("로컬 서버에 심을 때는 GILDONGMU_SEED_STATE로 기록 파일을 따로 정해 주세요")
    os.makedirs(OUT, exist_ok=True)

    seeded = {"server": base, "created_at": datetime.now(timezone.utc).isoformat(), "messages": []}
    if os.path.exists(STATE):
        seeded = json.load(open(STATE, encoding="utf-8"))
        if seeded["server"] != base:
            sys.exit(f"기록의 서버가 다릅니다: {seeded['server']}")
        wanted = {m[0] for m in MESSAGES}
        keep = []
        for m in seeded["messages"]:
            if m.get("review") == "clean" and m["name"] in wanted:
                keep.append(m)
                continue
            status, _ = request(base, "DELETE", f"/messages/{m['id']}", m["device"])
            print(f"{m['name']}: 다시 심으려고 지움({status})")
        seeded["messages"] = keep
    done = {m["name"] for m in seeded["messages"]}
    try:
        for name, voice, text, lat, heading, thanks in MESSAGES:
            if name in done:
                print(f"{name}: 이미 공개됨, 그대로 둠")
                continue
            audio_path = os.path.join(OUT, f"{name}.ogg")
            subprocess.run([os.path.join(HERE, "make_voice.sh"), voice, text, audio_path], check=True)
            device = str(uuid.uuid4())
            meta = {
                "recorded_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "lat": lat, "lon": LON, "heading": heading, "speed_kmh": 35.0,
                "duration_ms": ogg_duration_ms(audio_path),
            }
            body, ctype = multipart(meta, open(audio_path, "rb").read())
            status, data = request(base, "POST", "/messages", device, body, ctype)
            if status != 202:
                raise SystemExit(f"{name}: 올리기 실패 {status} {data}")
            entry = {"name": name, "id": data["id"], "device": device, "lat": lat, "heading": heading, "thanks_devices": []}
            seeded["messages"].append(entry)
            m = wait_review(base, device, data["id"])
            if m is None:
                raise SystemExit(f"{name}: 검수 결과가 90초 안에 오지 않았습니다")
            entry.update(review=m["review"], kind=m.get("kind"), stage=m["stage"], expires_at=m.get("expires_at"))
            if m["review"] != "clean":
                raise SystemExit(f"{name}: 공개되지 않았습니다({m['review']}). 문장을 바꿔 다시 심으세요")
            print(f"{name}: {m['review']} · {m['kind']} · {m['stage']} · 만료 {m['expires_at'][:10]}")
            if m["kind"] not in LONG_LIVED:
                print(f"  주의: {m['kind']}로 분류돼 금방 사라집니다. 문장을 바꿔 다시 심으세요")
            for _ in range(thanks):
                other = str(uuid.uuid4())
                status, t = request(base, "POST", f"/messages/{data['id']}/thanks", other)
                if status != 200:
                    raise SystemExit(f"{name}: 고마워요 실패 {status} {t}")
                entry["thanks_devices"].append(other)
                entry["thanks_count"], entry["stage"] = t["thanks_count"], t["stage"]
            if thanks:
                print(f"  고마워요 {entry['thanks_count']}개 · {entry['stage']}")
    finally:
        with open(STATE, "w", encoding="utf-8") as f:
            json.dump(seeded, f, ensure_ascii=False, indent=2)
        print(f"기록: {STATE}")


if __name__ == "__main__":
    main()
