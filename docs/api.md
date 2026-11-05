# 길동무 서버 API 계약서 (v1, MVP)

차량 앱(Kotlin)과 서버(Go)가 동시에 개발할 수 있도록 먼저 정해 두는 약속입니다.
바꿀 때는 이 문서를 먼저 고치고 양쪽에 알립니다.

- 상태: **초안** (2026-11-03)
- 범위: 1차 제출(11/14) MVP. `2차`라고 적은 항목은 12/3 PT 전까지 추가합니다.
- 아직 정하지 않은 것: 음성→글자(STT) 서비스, 배포 주소. 정해지면 이 문서에 적습니다.

---

## 1. 공통 규칙

### 주소

| 환경 | 기본 주소 |
|---|---|
| 에뮬레이터에서 내 Mac의 서버로 | `http://10.0.2.2:8080/v1` |
| 배포 서버 | `https://<정해지면 기입>/v1` |

### 요청 헤더

| 헤더 | 필수 | 설명 |
|---|---|---|
| `X-Device-Id` | 모든 요청 | 앱을 처음 설치할 때 만든 UUID v4. 로그인 대신 기기를 구분합니다. 앱 데이터를 지우면 새로 만듭니다. |
| `X-App-Version` | 권장 | 예: `0.1.0`. 문제 추적용 |

### 값의 단위와 형식

| 항목 | 형식 |
|---|---|
| 위도·경도 | WGS84 십진수 (예: `37.566500`, `126.978000`) |
| 진행 방향 `heading` | 도 단위 0 이상 360 미만. 북쪽 0, 시계 방향 (동쪽 90) |
| 속도 | `speed_kmh` (시속 km) |
| 거리 | 미터 |
| 시각 | RFC 3339, UTC (예: `2026-11-07T23:50:00Z`) |
| ID | 서버가 만드는 UUID 문자열 |

### 오류 응답

모든 오류는 같은 모양으로 돌려줍니다. `message`는 한국어로 씁니다.

```json
{
  "error": {
    "code": "INVALID_ARGUMENT",
    "message": "heading은 0 이상 360 미만이어야 합니다."
  }
}
```

| HTTP | code | 언제 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 값이 빠졌거나 범위를 벗어남 |
| 401 | `MISSING_DEVICE_ID` | `X-Device-Id`가 없거나 UUID가 아님 |
| 403 | `NOT_OWNER` | 내가 남기지 않은 한마디를 지우려 함 |
| 404 | `NOT_FOUND` | 없는 한마디이거나 이미 지워짐 |
| 413 | `PAYLOAD_TOO_LARGE` | 음성 파일이 1MB를 넘음 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 음성 형식이 `audio/ogg`(Opus)가 아님 |
| 429 | `RATE_LIMITED` | 하루 업로드 한도(기기당 20개, 초기값)를 넘음 |
| 500 | `INTERNAL` | 서버 오류 |

### 공통 값 목록

**`stage`**: 지도와 목록에 보이는 단계

| 값 | 화면 | 뜻 | 누가 보나 |
|---|---|---|---|
| `seed` | 씨앗 | 검수 중 | 남긴 사람만 |
| `sprout` | 새싹 | 검수를 통과해 다른 사람에게 들림 | 모두 |
| `flower` | 꽃 | 고마워요 5개 이상 (초기값) | 모두 |
| `memory` | 꽃밭의 추억 | 유효기간이 끝남. 더는 들리지 않음 | 남긴 사람만 |

**`kind`**: 서버가 검수하면서 나누는 종류. 유효기간이 다릅니다 (초기값).

| 값 | 뜻 | 예 | 유효기간 |
|---|---|---|---|
| `road` | 길 상황 | "이 앞 공사 중이에요" | 3시간 |
| `place` | 장소 이야기 | "이 휴게소 소떡소떡 맛있어요" | 30일 |
| `greeting` | 인사·응원·풍경 | "다들 오늘도 힘내세요" | 90일 |

---

## 2. API 목록

| API | 하는 일 | MVP |
|---|---|---|
| `GET /v1/health` | 서버가 살아 있는지 | ✓ |
| `POST /v1/messages` | 한마디 올리기 (씨앗 등록) | ✓ |
| `GET /v1/messages/nearby` | 앞쪽 같은 방향의 한마디 찾기 | ✓ |
| `GET /v1/messages/{id}/audio` | 음성 내려받기 | ✓ |
| `POST /v1/messages/{id}/thanks` | 고마워요 | ✓ |
| `POST /v1/messages/{id}/listen` | 끝까지 들었는지 / 넘겼는지 알리기 | ✓ |
| `GET /v1/me/messages` | 내가 남긴 한마디와 반응 수 | ✓ |
| `DELETE /v1/messages/{id}` | 내 한마디 지우기 | ✓ |
| `GET /v1/me/profile` | 내 길동무(닉네임·모습) 읽기 | ✓ |
| `PUT /v1/me/profile` | 내 길동무 정하기·바꾸기 | ✓ |
| `PATCH /v1/messages/{id}` | 내 한마디 위치 옮기기 | 2차 |
| `POST /v1/messages/{id}/reports` | 신고 | 2차 |

---

## 3. API 상세

### 3.1 `GET /v1/health`

배포 후 상태 확인용입니다. `X-Device-Id`가 없어도 됩니다.

```json
{ "status": "ok" }
```

### 3.2 `POST /v1/messages`: 한마디 올리기

`multipart/form-data`로 보냅니다. 위치는 **녹음을 시작한 순간**의 값입니다. 방향도 그때 값이고, 그때 몰랐으면 나중에 정한 값이거나 생략입니다(4장 남기기).

| 파트 | 형식 | 설명 |
|---|---|---|
| `audio` | 파일, `audio/ogg` | Opus, 16kHz 모노, 약 24kbps. 최대 15초, 최대 1MB. 앱은 `MediaRecorder`의 `OutputFormat.OGG` + `AudioEncoder.OPUS`(Android 10 이상, Pleos는 14)로 녹음합니다. Google STT·Whisper 둘 다 이 형식을 받습니다 |
| `meta` | JSON 문자열 | 아래 표 |

`meta` 필드:

| 필드 | 형식 | 필수 | 설명 |
|---|---|---|---|
| `recorded_at` | 시각 | ✓ | 녹음 시작 시각 |
| `lat`, `lon` | 숫자 | ✓ | 녹음 시작 위치 |
| `heading` | 숫자 | | 녹음 시작 때 진행 방향. 정차 중이면 마지막으로 움직일 때의 방향. 끝내 모르면 생략하고, 그러면 양쪽 방향 모두에 들립니다 |
| `speed_kmh` | 숫자 | ✓ | 녹음 시작 때 속도 |
| `duration_ms` | 정수 | ✓ | 녹음 길이. 15,000 이하 |
| `wiper_on` | 불리언 | | 와이퍼 켜짐 여부 (모르면 생략) |
| `headlight_on` | 불리언 | | 전조등 켜짐 여부 (모르면 생략) |

```json
{
  "recorded_at": "2026-11-07T23:50:00Z",
  "lat": 37.566500,
  "lon": 126.978000,
  "heading": 45.0,
  "speed_kmh": 32.5,
  "duration_ms": 6400,
  "wiper_on": false,
  "headlight_on": false
}
```

응답 `202 Accepted`: 검수는 서버에서 따로 진행되므로 바로 씨앗 상태로 돌려줍니다.

```json
{
  "id": "6f1c2b8e-1d2a-4c8e-9f0a-2b7d3e4f5a6b",
  "stage": "seed",
  "created_at": "2026-11-07T23:50:03Z"
}
```

### 3.3 `GET /v1/messages/nearby`: 앞쪽 한마디 찾기

| 쿼리 | 필수 | 설명 |
|---|---|---|
| `lat`, `lon` | ✓ | 현재 위치 |
| `heading` | ✓ | 현재 진행 방향 |
| `speed_kmh` | ✓ | 현재 속도 |
| `filter` | | 비속어 필터. `on`(기본) 또는 `off`. 앱 설정 화면 값 |
| `limit` | | 돌려줄 최대 개수. 기본 3, 최대 10 |
| `wiper_on`, `headlight_on` | | 2차에서 순서 정할 때 씀. MVP에서는 받기만 함 |

서버가 고르는 규칙 (MVP):

1. **후보**: 현재 위치의 300m 칸과 둘레 8칸. 칸은 조회 단위일 뿐이고, 실제 판단은 아래 2~3번으로 합니다.
2. **같은 방향**: 한마디의 `heading`과 현재 `heading` 차이가 45° 이하. 한마디의 `heading`이 없으면 이 조건은 건너뜁니다
3. **앞쪽**: 현재 위치에서 한마디 쪽 방위와 현재 `heading`의 차이가 60° 이하, 거리 600m 이하
4. **빼는 것**:
   - `seed`·`memory` 단계
   - 차단·보류 판정
   - `filter=on`일 때 비속어 판정
   - 이 기기가 이미 끝까지 들었거나 넘긴 한마디
   - 이 기기가 남긴 한마디
5. **순서**: 꽃 먼저 → 고마워요 많은 순 → 최근 순

응답 `200`:

```json
{
  "total_ahead": 2,
  "messages": [
    {
      "id": "6f1c2b8e-1d2a-4c8e-9f0a-2b7d3e4f5a6b",
      "stage": "flower",
      "kind": "greeting",
      "lat": 37.567900,
      "lon": 126.979800,
      "heading": 44.0,
      "distance_m": 210,
      "eta_sec": 23,
      "duration_ms": 6400,
      "thanks_count": 7,
      "created_at": "2026-11-06T22:10:00Z",
      "expires_at": "2027-02-04T22:10:00Z",
      "audio_url": "/v1/messages/6f1c2b8e-1d2a-4c8e-9f0a-2b7d3e4f5a6b/audio"
    }
  ],
  "next_poll_sec": 10
}
```

- `total_ahead`: 안내 문장 "앞쪽 길에 남겨진 한마디가 N개 있어요"의 N
- `recorded_at`: 녹음한 시각. 카드에 "어제 저녁"처럼 보여줍니다
- `wiper_on`·`headlight_on`: 남길 때 와이퍼·전조등이 켜져 있었는지. 모르면 `null`. 카드에 "비 오는 날", "밤에"로 보여줍니다
- `listen_count`: 지금까지 들은 사람 수
- 남긴 사람의 닉네임·모습은 싣지 않습니다(닉네임은 내 기기에서만 보임, 3.9)
- `heading`: 한마디를 남길 때의 방향. 방향을 모른 채 남겨졌으면 `null`
- `eta_sec`: 지금 속도로 도착까지 남은 초. 속도가 시속 5km 미만이면 `null`
- `next_poll_sec`: 다음 조회까지 기다릴 초. 서버가 부하에 따라 늘릴 수 있음

### 3.4 `GET /v1/messages/{id}/audio`: 음성 내려받기

- 응답 `200`, `Content-Type: audio/ogg`, 본문은 음성 파일
- `Cache-Control: public, max-age=86400`. 음성은 바뀌지 않으니 앱이 캐시해도 됩니다.
- 차단·보류된 한마디는 남긴 사람에게만 `200`, 다른 기기에는 `404`

### 3.5 `POST /v1/messages/{id}/thanks`: 고마워요

본문은 없습니다. 같은 기기가 여러 번 눌러도 한 번만 셉니다.

응답 `200`:

```json
{ "thanks_count": 5, "stage": "flower", "already_thanked": false }
```

이 요청으로 5개가 되면 `stage`가 `flower`로 바뀌어 돌아옵니다. 시연 2번 장면이 이 응답으로 꽃을 그립니다.

### 3.6 `POST /v1/messages/{id}/listen`: 들은 결과 알리기

'묻지 않는 신호'입니다. 재생이 끝나거나 넘길 때 한 번 보냅니다.

```json
{ "result": "completed", "played_ms": 6400 }
```

| 필드 | 값 |
|---|---|
| `result` | `completed`(끝까지 들음, 약한 긍정) 또는 `skipped`(중간에 넘김, 약한 부정) |
| `played_ms` | 실제로 재생한 길이 |

응답 `204 No Content`.

`completed`면 서버가 유효기간을 조금 늘립니다 (초기값).
- 길 상황: +30분, 최대 6시간
- 장소: +1일, 최대 60일
- 인사: +1일, 최대 180일

### 3.7 `GET /v1/me/messages`: 내가 남긴 한마디

정차 중 "새싹이 돋았어요" 화면과 2차의 '내 꽃밭'에서 씁니다. 최근 순으로 최대 50개를 돌려줍니다.

```json
{
  "messages": [
    {
      "id": "6f1c2b8e-1d2a-4c8e-9f0a-2b7d3e4f5a6b",
      "stage": "sprout",
      "kind": "greeting",
      "review": "clean",
      "lat": 37.566500,
      "lon": 126.978000,
      "duration_ms": 6400,
      "listen_count": 32,
      "thanks_count": 4,
      "created_at": "2026-11-07T23:50:03Z",
      "expires_at": "2027-02-05T23:50:03Z",
      "audio_url": "/v1/messages/6f1c2b8e-1d2a-4c8e-9f0a-2b7d3e4f5a6b/audio"
    }
  ]
}
```

- `review`: 남긴 사람에게만 보이는 검수 결과
  - `pending`: 검수 중
  - `clean`: 공개
  - `profanity`: 필터를 끈 사람에게만 들림
  - `hold`: 확인할 때까지 비공개
  - `blocked`: 공개 안 함
- `listen_count`: 끝까지 들은 횟수

### 3.8 `DELETE /v1/messages/{id}`: 내 한마디 지우기

남긴 기기만 지울 수 있습니다. 음성과 변환된 글자를 바로 지웁니다. 응답 `204 No Content`.

### 3.9 `GET /v1/me/profile`, `PUT /v1/me/profile`: 내 길동무

내 기기에서만 보이는 닉네임과 길동무 모습입니다. 다른 기기의 응답(찾기 등)에는 실리지 않습니다.

`PUT` 본문:

```json
{ "nickname": "느긋한 고라니", "look": { "color": 3, "item": "scarf" } }
```

| 필드 | 규칙 |
|---|---|
| `nickname` | 2~10자, 한글·영문·숫자와 낱말 사이 빈칸 하나. 앞뒤·겹친 빈칸은 서버가 정리합니다 |
| `look.color` | 몸 색 0~5 (앱의 6색) |
| `look.item` | 소품 `none`·`hat`·`scarf`·`backpack` |

- 비속어(서버 사전, 띄어쓰기 무시)나 "운영자·관리자·공식·길동무팀·admin·official·gildongmu"가 들어가면 `400 NICKNAME_NOT_ALLOWED`
- 닉네임은 **30일에 한 번** 바꿀 수 있습니다. 그 전에 다른 닉네임을 보내면 `409 NICKNAME_CHANGE_TOO_SOON`(메시지에 바꿀 수 있는 날짜). 모습(`look`)만 바꾸는 건 언제든 됩니다
- 앱 데이터를 지우면 새 기기 ID가 되므로 새로 정하는 것으로 봅니다

응답 `200` (`GET`에서 아직 정하지 않았으면 모두 `null`):

```json
{
  "nickname": "느긋한 고라니",
  "look": { "color": 3, "item": "scarf" },
  "nickname_changed_at": "2026-11-04T09:00:00Z",
  "next_nickname_change_at": "2026-12-04T09:00:00Z"
}
```

---

## 4. 앱이 지킬 규칙 (기획서 3·5번 요약)

- **조회 시점**: 100m 이상 움직였거나 `next_poll_sec`가 지나면 `nearby`를 부릅니다.
- **조회 개수**: 앱은 `limit=10`으로 부릅니다. 모여 있는 한마디를 한 번에 묻기 위해서입니다.
- **안내 시점**: 가장 먼저 닿는(가장 가까운) 한마디에 10초 안에 닿으면 "들을까요?"를 한 번 묻습니다. 서버 순서(꽃 먼저)와 상관없이 닿는 순서를 따릅니다.
  - 그 한마디에서 50m 안에 모인 한마디는 묶어서 한 번에 묻고("앞쪽에 한마디가 6건 있어요. 들을까요?"), 들으면 닿는 순서대로 이어서 틉니다. 50m는 GPS 오차(10m 안팎)보다 넉넉하고 시속 40km로 5초 안쪽입니다.
  - 묻는 문장은 Pleos TextToSpeech SDK(`ai.pleos.playground:TextToSpeech`, 차 안 엔진 `ON_DEVICE` 모드)로 읽습니다. 미디어 용도로 내고, 다 읽은 뒤부터 8초 대답 창을 엽니다.
    - `ON_DEVICE`는 앱 등록(Pleos Playground My Project의 Client ID·Secret)이 필요 없습니다. 서버 음성(`HYBRID`·`SERVER`)은 자격 증명을 받은 뒤에 바꿀 수 있습니다.
    - Pleos가 아닌 기기에서는 안드로이드 TTS로 읽고, 둘 다 안 되면 알림음만 냅니다.
    - 읽는 중에 내비 안내가 끼어들면(포커스를 잠시 잃으면) 읽기를 멈추고, 안내가 끝나면 문장을 처음부터 다시 읽습니다.
  - 내비 안내·통화·음성 비서가 말하는 중이면(재생 중인 소리의 용도가 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`·`USAGE_VOICE_COMMUNICATION`·`USAGE_ASSISTANT`이거나 통화 모드) 묻지 않고, 끝난 뒤 다음 위치(1초 뒤)에 다시 봅니다. 차의 오디오 규칙상 미디어 소리가 내비 안내와 겹쳐 나올 수 있어서입니다.
- **화면 밖 안내**: 내비를 전체 화면으로 띄워 길동무가 뒤로 숨어도 안내가 이어집니다.
  - 앱을 열면 포그라운드 서비스(`GuideService`)가 알림 "길동무가 앞쪽 길을 살피고 있어요"를 띄우고, 위치·안내·재생·녹음·Gleo 명령을 맡는 `Core`를 살려 둡니다. 화면은 `Core`를 읽어 그리기만 합니다.
  - 서비스 종류는 권한에 맞춥니다: 위치(위치 권한), 마이크(마이크 권한), 미디어 재생(항상). 화면이 보일 때 시작해야 화면 밖에서도 위치를 받습니다.
  - 화면을 닫아도(뒤로 가기, 최근 앱에서 지우기) 계속 돌고, 알림의 "끄기"로 멈춥니다.
  - 안내 사이는 최소 5분(초기값)이에요.
  - 다음 회전까지 300m 이내면 미룹니다. (2차)
- **대답**: 화면 버튼, 핸들 버튼, 말로 받아요. 문장을 다 읽은 뒤 8초 안에 대답이 없으면 넘어가고 `listen`은 보내지 않습니다.
  - 핸들: 묻는 중에는 재생 = 듣기, 다음 곡 = 넘기기. 듣는 중에는 다음 곡 = 다음 한마디, 재생 = 그만 듣기.
  - 말: Pleos SpeechToText SDK(`ai.pleos.playground:SpeechToText`, 차 안 엔진 `ON_DEVICE` 모드, 자격 증명 불필요)로 듣습니다. Pleos가 아닌 기기에서는 안드로이드 `SpeechRecognizer`로 듣습니다. "응·네·그래·들어" = 듣기, "아니·넘겨·다음·괜찮아" = 넘기기, "그만" = 그만 듣기, "고마워·고마워요·감사" = 고마워요. "안 들을래"처럼 거절 낱말이 섞이면 넘기기로 봅니다.
  - 마이크는 "들을까요?" 뒤 8초와, 한마디 하나를 다 듣고 난 뒤 4초 동안만 엽니다. 한마디가 나오는 동안에는 그 소리를 대답으로 잘못 들을 수 있어서 듣지 않습니다.
  - 설정에서 "말로 대답하기"를 끌 수 있습니다(기본 켜짐). 마이크 권한이 없으면 버튼으로만 받습니다.
  - 부분 결과마다 낱말을 맞춰 보므로 말이 끝나기 전에도 대답할 수 있습니다. 엔진은 "인식 끝"을 최종 결과보다 먼저 알리므로, 끝을 받아도 남은 결과는 마저 봅니다.
  - 인식 엔진이 3초 넘게 아무 응답(준비됨·소리 크기·결과·오류)도 주지 않으면 그 실행 동안은 버튼으로만 받고, 화면도 버튼 안내로 바꿉니다.
  - **에뮬레이터 확인 결과(2026-11-04)**: 안드로이드 `SpeechRecognizer`의 기본 인식 서비스(Gleo AI, `ai.umos.ambientai`)는 마이크만 열고 응답을 주지 않습니다.
  - **말로 대답하기 확인 결과(2026-11-05)**: Pleos SpeechToText SDK로는 알아듣습니다.
    - 녹음 파일을 엔진에 바로 넣어(개발용 확인 화면 ⑦) "응·네·들어 볼게"는 듣기, "넘겨·아니 안 들을래"는 넘기기, "고마워"는 고마워요, "그만"은 그만으로 7개 모두 맞았습니다.
    - 본 화면의 8초 대답 창에서 엔진이 마이크를 열고 소리 크기를 주는 것까지 확인했습니다. 실제 목소리로 마이크를 거쳐 대답하는 것은 실제 차(또는 Mac 소리를 켠 에뮬레이터)에서 확인해야 합니다.
  - **문장 읽기 확인 결과(2026-11-05)**: 안드로이드 TTS로 차 엔진(`ai.pleos.playground.caas` 4.2.10)을 부르면 초기화에 실패하지만(-1), Pleos TTS SDK로는 읽힙니다. "이 자리에 길동무 2명이 한마디씩 남겼어요. 들어 볼까요?"가 약 4.5초 걸렸고, 끝나고 8초 뒤에 넘어갔습니다. 읽는 중에 내비 안내가 끼어들면 멈췄다가 안내가 끝난 뒤 처음부터 다시 읽었습니다.
- **Gleo 음성 명령(Gleo Interface, Pleos 공식)**: 운전자가 Gleo를 불러 말하면 Gleo가 앱 함수를 부릅니다. 함수는 `app/src/main/assets/schema.json`에 셋까지 둘 수 있습니다.

  | 함수 | 말 예시 | 앱이 하는 일 | 결과 소리(`mode`) |
  |---|---|---|---|
  | `CONTROL_ROAD_MESSAGES` (`command`: `listen`·`skip`·`stop`) | "길동무 들려 줘", "다음", "그만" | 묻는 중이면 듣기. 조용할 때는 가장 가까운 묶음을 묻지 않고 바로 틉니다(운전자가 먼저 청했으므로 5분 간격은 보지 않음). 넘기기·그만 | 듣기·넘기기는 `silent`(앱이 바로 소리 냄), 그만은 `beep` |
  | `THANK_ROAD_MESSAGE` | "고마워요 남겨 줘" | 듣고 있거나 방금 들은 한마디에 고마워요 | 조용할 때 `voice`, 듣는 중 `beep` |
  | `RECORD_ROAD_MESSAGE` | "한마디 남길래" | 녹음 시작(삑 소리 1.2초 뒤), 녹음 중이면 끝내고 올리기 | `beep` |

  - 셋째 칸을 녹음에 쓴 이유: 주행 중 손으로 하기 가장 어려운 일이 남기기이기 때문입니다.
  - Gleo는 스키마의 `component`가 `service`라서 브로드캐스트로 보냅니다(`GleoActionReceiver`). 인자는 `ACTION_PARAM`(JSON 문자열)에 옵니다.
  - 앱은 7초 안에 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`(대상 `ai.umos.ambientai`, extra `packageName`·`response`)로 결과를 돌려줍니다. 결과 문장(`message`)은 Pleos 규칙대로 영어로 씁니다.
  - 앱 화면이 떠 있지 않으면 처리하지 않고 `fail`을 돌려줍니다.
  - 스키마는 앱을 설치할 때 Gleo가 읽고, Pleos 검토·승인 뒤에 실제 음성 명령으로 쓸 수 있습니다.
  - **에뮬레이터 확인 결과(2026-11-04)**: Gleo가 스키마를 읽었습니다(`converted | package=com.gildongmu.app functionCount=3`). adb 브로드캐스트로 액션을 흉내 내 여섯 경우(앞쪽 없음, 묶음 듣기, 고마워요, 다음, 그만, 녹음 시작·끝)를 확인했습니다. 실제 말 → Gleo → 앱 경로는 확인하지 못했습니다. 에뮬레이터의 Gleo는 스키마를 서버로 올리다 실패하고(오프라인), 승인도 받지 않았기 때문입니다.
- **길 띠**: 50m 안에 모인 한마디(또는 화면에서 겹치는 한마디)는 핀 하나에 개수 배지로 모아 그립니다("100m · 6건").
- **화면**: 디자인 B안 "길 띠". 왼쪽 띠에 앞쪽 600m 길과 한마디를 남긴 길동무(거리)를 세우고, 오른쪽에 안내 카드와 남기기 버튼을 둡니다.
  - 캐릭터 "길동무": 한마디를 남긴 사람이 그 자리에 서 있는 모습입니다. 단계는 모습으로 나타냅니다. 씨앗(확인 중) = 점선 윤곽, 새싹(공개) = 서 있음, 꽃 = 꽃을 듦, 추억 = 벤치에 앉음. 몸 색은 한마디 id로 정해서(6색) 여러 길동무를 구분하고, 누가 남겼는지는 드러나지 않습니다.
  - 말투: "길동무가 인사 한마디를 남겼어요. 들어 볼까요?", "이 자리에 길동무 6명이 한마디씩 남겼어요." 버튼은 "들어 볼게요·지나갈게요·다음 길동무·그만 들을게요·나도 한마디 남기기", 탭은 "길·내 발자취·설정"입니다.
  - CarUxRestrictions가 주행 중(`isRequiresDistractionOptimization`)이면 길 띠·안내 한 줄·그때 필요한 버튼만 보여주고, 정차 중에만 탭(내 꽃밭·설정)을 엽니다.
  - 그림은 움직이지 않습니다. 길 띠는 거리가 10m 바뀔 때만 다시 그립니다.
  - 낮·밤 색은 차의 낮·밤 모드를 따르고(`values-night`), 글자와 바탕 대비는 4.5:1 이상입니다. 글꼴은 따로 넣지 않고 Pleos 기본 글꼴(Asta Sans)을 씁니다.
  - 창이 세로로 길면(화면 나누기) 길 띠를 위에 가로로 눕힙니다. 화면 나누기·낮밤 전환 때 Activity를 다시 만들지 않아 듣던 한마디가 끊기지 않습니다.
  - 아이콘은 Material Symbols(Apache License 2.0)이고, 라이선스 전문은 `app/src/main/assets/licenses/`에 있습니다.
- **비속어 필터**: 기본 켜짐. 켜기는 바로, 끄기는 정차 중에 확인 창을 한 번 더 거칩니다. 바꾸면 다음 위치에서 바로 다시 조회합니다.
- **재생**: 1개만 틀고 끝나면 자동 종료합니다. 오디오 설정은 에뮬레이터에서 확인한 값을 씁니다.
  - 올린 길이(`duration_ms`, 최대 15초)보다 1초 넘게 소리가 나면 끝까지 들은 것으로 보고 멈춥니다. OGG에 `ANDROID_LOOP=true` 태그가 있으면 MediaPlayer가 끝없이 되풀이하기 때문입니다(에뮬레이터 벨소리로 확인). 내비 안내로 멈춘 시간은 세지 않습니다.
  - 미디어 용도(`USAGE_MEDIA`)
  - 잠깐 포커스(`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`)
  - 내비 안내가 오면 직접 멈춤(`setWillPauseWhenDucked(true)`)
- **남기기(녹음)**: 화면의 큰 버튼 한 번으로 시작하고, 한 번 더 누르거나 15초가 되면 끝나며 바로 올립니다.
  - GPS 위치가 아직 없으면 남기지 않습니다. 한마디를 남길 자리가 없기 때문입니다.
  - 진행 방향을 아직 모르면(막힌 길에서 앱을 막 켠 경우 등) 녹음은 그대로 하고, 위치는 녹음 시작 자리로 둔 채 방향만 기다립니다.
    - 녹음한 자리에서 300m 안에서 방향이 잡히면 그 방향을 붙여 올립니다.
    - 10분이 지나거나 방향 없이 300m 넘게 벗어나면 `heading` 없이 올립니다. 서버는 양쪽 방향 모두에 들려줍니다.
    - 기다리는 중에 앱이 닫히면 `heading` 없이 바로 올려 봅니다. 닫히는 중이라 못 보낼 수 있습니다.
  - 1초보다 짧은 녹음은 버립니다. 듣는 중(안내·재생)에는 녹음하지 않고, 녹음 중에는 "들을까요?"를 묻지 않습니다.
  - 녹음하는 동안 잠깐 포커스(`AUDIOFOCUS_GAIN_TRANSIENT`)로 음악을 멈춥니다. 내비 안내는 막지 않습니다.
  - `wiper_on`·`headlight_on`은 Pleos Vehicle State에서 읽고, 값을 모르면 생략합니다.
- **진행 방향**: 표준 GPS의 bearing을 정확도 20° 이내일 때 쓰고, 아니면 직전 기준 위치에서 8m 이상 움직였을 때 두 위치 사이의 방위로 계산합니다. 시속 5km 미만이면 마지막 방향을 유지합니다.
  - 에뮬레이터의 `geo fix`는 bearing 0°, 정확도 30°를 고정으로 보내므로 위치로 계산하는 쪽을 탑니다.

---

## 5. 서버 내부 처리 (앱은 몰라도 됨)

### 검수 순서

업로드가 끝나면 서버가 따로 처리합니다.

1. 음성 → 글자 (STT 서비스는 정해지면 기입)
2. 비속어 사전 검사
3. Claude API(가벼운 모델)에 글자를 보내 아래 JSON을 받습니다.

   ```json
   { "grade": "clean", "kind": "place", "promo": false, "reason": "휴게소 음식 추천" }
   ```

   | 필드 | 값 |
   |---|---|
   | `grade` | `clean` / `profanity` / `hold` / `blocked` |
   | `kind` | `road` / `place` / `greeting` |
   | `promo` | 일반 사용자인 척 남긴 홍보면 `true` → `hold`로 처리 |
   | `reason` | 판정 이유를 짧게 (운영자 확인용, 앱에는 보내지 않음) |

4. 합치는 규칙
   - 사전에 걸렸는데 LLM이 `clean`이라고 하면 `profanity`로 처리해요.
   - `clean`·`profanity`이면 `stage`를 `sprout`로 바꾸고 `kind`에 맞춰 `expires_at`을 정해요.
   - 판정이 실패하면(STT 실패, LLM 오류) `hold`로 둡니다.

### 300m 칸 계산

- `cell_y = floor(lat × 111,320 / 300)`
- `cell_x = floor(lon × 111,320 × cos(37.5°) / 300)`

경도 폭을 한국 중간 위도(37.5°)로 고정해서 칸 크기를 단순하게 맞춥니다.

### 각도 차이

`diff(a, b) = | ((a − b + 540) mod 360) − 180 |`

결과는 0~180°예요. 359°와 1°의 차이가 2°로 나옵니다.

### 저장 (GORM 모델 초안)

| 테이블 | 주요 컬럼 |
|---|---|
| `messages` | id, device_id, lat, lon, heading, cell_x, cell_y, speed_kmh, wiper_on, headlight_on, audio(bytea), audio_mime, duration_ms, transcript, review, kind, stage, thanks_count, listen_count, skip_count, recorded_at, created_at, expires_at, deleted_at |
| `thanks` | message_id, device_id, created_at (두 값으로 유일) |
| `listens` | message_id, device_id, result, played_ms, created_at |

---

## 6. 빠른 확인 (curl)

```bash
curl -s http://localhost:8080/v1/health
```

```bash
curl -s -H "X-Device-Id: 11111111-1111-4111-8111-111111111111" \
  "http://localhost:8080/v1/messages/nearby?lat=37.5665&lon=126.9780&heading=45&speed_kmh=40"
```

```bash
curl -s -X POST -H "X-Device-Id: 11111111-1111-4111-8111-111111111111" \
  -F "audio=@sample.ogg;type=audio/ogg" \
  -F 'meta={"recorded_at":"2026-11-07T23:50:00Z","lat":37.5665,"lon":126.978,"heading":45,"speed_kmh":32.5,"duration_ms":6400}' \
  http://localhost:8080/v1/messages
```
