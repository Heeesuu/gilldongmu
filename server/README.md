# 길동무 서버 (Go)

API 약속은 [../docs/api.md](../docs/api.md)를 따릅니다.

## 실행

```bash
cd server
DEV_AUTO_APPROVE=true go run ./cmd/server
```

- `DEV_AUTO_APPROVE=true`: STT·LLM 없이 올린 한마디를 바로 '인사'로 공개합니다. **로컬 개발 전용**입니다.
- `DATABASE_URL`이 없으면 `gildongmu-dev.db`(SQLite)를 만들어 씁니다.
- 에뮬레이터 앱에서는 `http://10.0.2.2:8080/v1`로 접속합니다.

## 환경 변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `PORT` | `8080` | |
| `DATABASE_URL` | (없음) | PostgreSQL 접속 문자열. 없으면 SQLite |
| `SQLITE_PATH` | `gildongmu-dev.db` | |
| `ANTHROPIC_API_KEY` | (없음) | 없으면 LLM 판정이 실패해 한마디가 보류(hold)됩니다 |
| `JUDGE_MODEL` | `claude-haiku-5-5` | 검수에 쓸 Claude 모델 |
| `STT_PROVIDER` | (없음) | `google`이면 Google Speech-to-Text로 음성을 글자로 바꿉니다(인증은 ADC, Cloud Run에서는 서비스 계정). 없으면 STT가 실패해 한마디가 보류됩니다 |
| `DEV_AUTO_APPROVE` | `false` | 위 설명 참고 |

키는 셸이나 배포 환경 변수로만 넣고, 파일로 저장소에 올리지 않습니다. 배포는 [../docs/deploy.md](../docs/deploy.md)를 따릅니다(`Dockerfile`, Cloud Run + Neon).

## 구조

```
cmd/server        진입점: 설정 읽기, 의존성 연결, 서버 시작
internal/handler  입출력(HTTP): 요청 해석, 계약서 모양의 응답·오류
internal/usecase  비즈니스 로직: 앞쪽 한마디 고르기, 고마워요, 검수 파이프라인
internal/repository  DB(GORM): 저장·조회, 트랜잭션(UnitOfWork)
internal/domain   한마디·단계·종류·유효기간 규칙, 칸·각도 계산
internal/moderation  외부 연동: Claude 판정, Google STT, 비속어 사전, 미설정용 자리 표시자
internal/testsupport 테스트용 메모리 DB와 가짜 STT·판정기
```

- 층은 handler → usecase → repository 순서로만 부릅니다.
- usecase는 저장소·STT·판정기를 인터페이스(`usecase/ports.go`)로만 알고, 구현은 바깥 패키지에 있습니다. 테스트에서 가짜로 바꿔 끼우기 위함입니다.
- DB 접근은 GORM만 씁니다(raw SQL 없음).

## 확인

```bash
go build ./... && go vet ./... && go test ./...
```

## 아직 남은 것

- 실제 배포(팀이 docs/deploy.md대로 실행)와 배포 서버에서의 STT·판정 확인
- 2차: 위치 옮기기, 신고, 와이퍼·전조등으로 순서 바꾸기
