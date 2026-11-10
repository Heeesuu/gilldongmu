package config

import "os"

// Config는 환경 변수로 받는 서버 설정이다. 키 같은 비밀 값은 코드나 저장소에 넣지 않는다.
type Config struct {
	Addr            string
	DatabaseURL     string // 있으면 PostgreSQL, 없으면 SQLite
	SQLitePath      string
	AnthropicAPIKey string
	JudgeModel      string
	STTProvider     string // "google"이면 Google Speech-to-Text, 비어 있으면 STT 없음(한마디가 보류된다)
	DevAutoApprove  bool   // 로컬 개발용: STT·LLM 없이 모두 공개
}

func Load() Config {
	return Config{
		Addr:            ":" + getenv("PORT", "8080"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		SQLitePath:      getenv("SQLITE_PATH", "gildongmu-dev.db"),
		AnthropicAPIKey: os.Getenv("ANTHROPIC_API_KEY"),
		// 한 줄 분류라 가벼운 모델로 비용을 낮춘다(기획서 6번 '1건당 1원 미만').
		JudgeModel:     getenv("JUDGE_MODEL", "claude-haiku-5-5"),
		STTProvider:    os.Getenv("STT_PROVIDER"),
		DevAutoApprove: os.Getenv("DEV_AUTO_APPROVE") == "true",
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
