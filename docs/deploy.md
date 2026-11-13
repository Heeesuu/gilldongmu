# 길동무 배포 안내 (Cloud Run + Neon)

1차 제출용 release APK가 붙을 서버를 올리는 순서입니다. 명령은 팀원이 자기 계정으로 실행합니다. 키·비밀번호는 저장소, 채팅, 문서에 적지 않습니다.

```
release APK ──https──▶ Cloud Run (길동무 서버, 서울)
                          ├─▶ Neon (PostgreSQL)              DATABASE_URL (Secret Manager)
                          └─▶ Claude API (검수 판정)           ANTHROPIC_API_KEY (Secret Manager)
```

## 0. 준비물

- Google Cloud 프로젝트 하나와 결제 계정(무료 체험 크레딧이면 충분)
- Neon 계정(무료 플랜)
- Claude API 키(Claude Console, 크레딧 충전)
- Mac에 gcloud CLI: `brew install --cask google-cloud-sdk`

## 1. Neon 데이터베이스

1. Neon에서 프로젝트를 만듭니다. 리전은 서울과 가까운 아시아 리전을 고릅니다.
2. **Connection string**(pooled, `sslmode=require` 포함)을 복사해 둡니다. 3장에서 비밀 값으로 넣습니다.
3. 표는 만들지 않아도 됩니다. **서버가 시작할 때 GORM AutoMigrate로 표를 만듭니다**(`repository.Migrate`). 즉 첫 배포가 곧 첫 마이그레이션입니다.

## 2. Google Cloud 준비

```bash
gcloud auth login
gcloud config set project <프로젝트 ID>
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com compute.googleapis.com
PROJECT=$(gcloud config get-value project)
SA=$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')-compute@developer.gserviceaccount.com
```

`compute.googleapis.com`을 켤 때 Cloud Run과 빌드가 쓰는 기본 서비스 계정(`$SA`)이 만들어집니다. 음성 인식은 앱이 차 안에서(Pleos SpeechToText SDK) 하므로 서버에는 음성 인식 API가 필요 없습니다.

## 3. 비밀 값 넣기 (Secret Manager)

입력한 값이 화면과 셸 기록에 남지 않게 `read -s`로 받습니다. 명령을 실행하고 값을 붙여 넣은 뒤 Enter를 누릅니다.

```bash
read -rs V && printf '%s' "$V" | gcloud secrets create gildongmu-database-url --data-file=- && unset V
read -rs V && printf '%s' "$V" | gcloud secrets create gildongmu-anthropic-key --data-file=- && unset V
for s in gildongmu-database-url gildongmu-anthropic-key; do
  gcloud secrets add-iam-policy-binding "$s" --member="serviceAccount:$SA" --role=roles/secretmanager.secretAccessor
done
```

## 4. 서버 배포

저장소 맨 위 폴더에서 실행합니다. Cloud Build가 `server/Dockerfile`로 이미지를 만들고 Cloud Run에 올립니다(처음에는 5분쯤 걸립니다).

```bash
gcloud run deploy gildongmu \
  --source server \
  --region asia-northeast3 \
  --allow-unauthenticated \
  --set-secrets DATABASE_URL=gildongmu-database-url:latest,ANTHROPIC_API_KEY=gildongmu-anthropic-key:latest \
  --no-cpu-throttling \
  --min-instances 0 --max-instances 1 \
  --memory 512Mi
```

| 옵션 | 이유 |
|---|---|
| `--allow-unauthenticated` | 앱은 로그인 없이 기기 ID 헤더로 부릅니다(api.md 1장) |
| `--no-cpu-throttling` | 업로드에 응답한 뒤에도 검수 작업(서버 안 고루틴)이 돌게 합니다. 없으면 다음 요청이 올 때까지 검수가 멈춥니다 |
| `--max-instances 1` | 검수 작업자를 하나로 두고 요금 상한을 둡니다 |
| `--min-instances 0` | 안 쓰면 꺼집니다. 첫 요청이 몇 초 늦을 수 있고, 남은 검수는 다시 켜질 때 처리합니다 |

`DEV_AUTO_APPROVE`는 **넣지 않습니다**(검수 없이 모두 공개하는 로컬 개발용).

## 5. 확인

```bash
URL=$(gcloud run services describe gildongmu --region asia-northeast3 --format='value(status.url)')
echo "$URL"
curl -s "$URL/v1/health"          # {"status":"ok"}
gcloud run services logs read gildongmu --region asia-northeast3 --limit 20
```

로그에 `서버 시작 ... db=postgres llm=true dev_auto_approve=false`가 보이면 됩니다. `DB 연결 실패`면 3장의 접속 문자열을 확인합니다.

예전에 `STT_PROVIDER=google`로 배포했다면 다시 배포할 때 `--remove-env-vars STT_PROVIDER`를 붙여 지웁니다(지금 서버는 이 값을 읽지 않습니다).

## 6. 제출용 APK 만들기

### 서명 키 (한 번만)

키 파일은 **저장소 밖**에 두고 따로 백업합니다. 잃어버리면 같은 앱으로 업데이트할 수 없습니다.

```bash
mkdir -p ~/gildongmu-keys
keytool -genkeypair -keystore ~/gildongmu-keys/release.jks -alias gildongmu -keyalg RSA -keysize 2048 -validity 10000
```

`~/.gradle/gradle.properties`(사용자 폴더, 저장소 밖)에 적습니다.

```properties
gildongmu.keystore=/Users/<사용자>/gildongmu-keys/release.jks
gildongmu.keystorePassword=<키 저장소 비밀번호>
gildongmu.keyAlias=gildongmu
gildongmu.keyPassword=<키 비밀번호>
```

### 빌드와 확인

```bash
./gradlew :app:assembleRelease -Pgildongmu.serverUrl="$URL/v1"
cp app/build/outputs/apk/release/app-release.apk apk/<팀명>_길동무_release.apk
"$(ls -d ~/Library/Android/sdk/build-tools/* | sort -V | tail -1)/apksigner" verify apk/<팀명>_길동무_release.apk
adb install -r apk/<팀명>_길동무_release.apk
```

- 서버 주소는 `https://…/v1` 형태여야 하며, 아니면 빌드가 멈춥니다.
- 서명 설정이 없으면 `app-release-unsigned.apk`가 나옵니다. 서명하지 않은 APK는 설치되지 않으므로 제출하면 안 됩니다.
- 에뮬레이터에 설치해 앞쪽 조회와 한마디 남기기가 되는지, 남긴 한마디가 몇 초 뒤 공개(새싹)되는지 봅니다.

## 7. 요금과 끄기

- Cloud Run은 켜져 있는 동안만 요금이 나옵니다. Claude 판정은 가벼운 모델이라 1건당 1원 미만입니다(기획서 6번). 정확한 값은 각 가격표를 봅니다.
- 심사가 끝나면 끕니다: `gcloud run services delete gildongmu --region asia-northeast3`
