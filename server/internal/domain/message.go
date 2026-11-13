package domain

import "time"

// Stage는 지도와 목록에 보이는 단계다. DB에 저장하지 않고 검수 결과·고마워요·유효기간으로 계산한다.
type Stage string

const (
	StageSeed   Stage = "seed"   // 검수 중(또는 비공개). 남긴 사람만 본다.
	StageSprout Stage = "sprout" // 공개
	StageFlower Stage = "flower" // 고마워요 FlowerThanks개 이상
	StageMemory Stage = "memory" // 유효기간 끝. 남긴 사람만 본다.
)

// Kind는 검수하면서 나누는 종류다. 종류마다 유효기간이 다르다.
type Kind string

const (
	KindRoad     Kind = "road"     // 길 상황
	KindPlace    Kind = "place"    // 장소 이야기
	KindGreeting Kind = "greeting" // 인사·응원·풍경
)

// Review는 검수 결과다.
type Review string

const (
	ReviewPending   Review = "pending"
	ReviewClean     Review = "clean"
	ReviewProfanity Review = "profanity" // 필터를 끈 사람에게만 들린다.
	ReviewHold      Review = "hold"      // 확인할 때까지 비공개
	ReviewBlocked   Review = "blocked"   // 공개하지 않는다.
)

// ListenResult는 '묻지 않는 신호'다.
type ListenResult string

const (
	ListenCompleted ListenResult = "completed"
	ListenSkipped   ListenResult = "skipped"
)

// 초기값. 운영하면서 조정한다.
const (
	FlowerThanks     = 5
	MaxDurationMs    = 15_000
	MaxAudioBytes    = 1 << 20
	DailyUploadLimit = 20
	AudioMimeOgg     = "audio/ogg"
	// MaxTranscriptRunes는 앱이 보내는 글자의 최대 길이다. 15초 말은 많아야 100자 남짓이다.
	MaxTranscriptRunes = 500
)

// Message는 길 위에 남긴 한마디다. 작성자가 지우면 음성과 글자까지 바로 지운다(하드 삭제).
type Message struct {
	ID       string  `gorm:"primaryKey;type:varchar(36)"`
	DeviceID string  `gorm:"type:varchar(36);not null;index"`
	Lat      float64 `gorm:"not null"`
	Lon      float64 `gorm:"not null"`
	// Heading이 nil이면 방향을 모른 채 올라온 한마디다. 같은 방향 조건 없이 양쪽 방향 모두에 들린다.
	Heading      *float64
	SpeedKmh     float64
	CellX        int `gorm:"not null;index:idx_messages_cell"`
	CellY        int `gorm:"not null;index:idx_messages_cell"`
	WiperOn      *bool
	HeadlightOn  *bool
	Audio        []byte `gorm:"not null"`
	AudioMime    string `gorm:"type:varchar(32);not null"`
	DurationMs   int    `gorm:"not null"`
	Transcript   string
	Review       Review `gorm:"type:varchar(16);not null;index"`
	ReviewReason string
	Kind         Kind `gorm:"type:varchar(16)"`
	ThanksCount  int  `gorm:"not null;default:0"`
	ListenCount  int  `gorm:"not null;default:0"`
	SkipCount    int  `gorm:"not null;default:0"`
	RecordedAt   time.Time
	CreatedAt    time.Time `gorm:"index"`
	ExpiresAt    *time.Time
}

// IsPublic은 다른 사람에게 들려줄 수 있는 검수 결과인지 알려준다.
func (m *Message) IsPublic() bool {
	return m.Review == ReviewClean || m.Review == ReviewProfanity
}

// IsExpired는 now 기준으로 유효기간이 끝났는지 알려준다.
func (m *Message) IsExpired(now time.Time) bool {
	return m.ExpiresAt != nil && !now.Before(*m.ExpiresAt)
}

// StageAt은 now 기준 단계를 계산한다.
func (m *Message) StageAt(now time.Time) Stage {
	switch {
	case !m.IsPublic():
		return StageSeed
	case m.IsExpired(now):
		return StageMemory
	case m.ThanksCount >= FlowerThanks:
		return StageFlower
	default:
		return StageSprout
	}
}

// Lifetime은 종류별 첫 유효기간이다.
func (k Kind) Lifetime() time.Duration {
	switch k {
	case KindRoad:
		return 3 * time.Hour
	case KindPlace:
		return 30 * 24 * time.Hour
	default:
		return 90 * 24 * time.Hour
	}
}

// ExtendedExpiry는 끝까지 들었을 때 늘어난 유효기간을 돌려준다. 만든 시각 기준 상한을 넘지 않는다.
func (m *Message) ExtendedExpiry() *time.Time {
	if m.ExpiresAt == nil {
		return nil
	}
	step, ceiling := 24*time.Hour, 180*24*time.Hour
	switch m.Kind {
	case KindRoad:
		step, ceiling = 30*time.Minute, 6*time.Hour
	case KindPlace:
		step, ceiling = 24*time.Hour, 60*24*time.Hour
	}
	next := m.ExpiresAt.Add(step)
	if limit := m.CreatedAt.Add(ceiling); next.After(limit) {
		next = limit
	}
	return &next
}

// Thanks는 기기당 한 번만 세는 고마워요다.
type Thanks struct {
	MessageID string `gorm:"primaryKey;type:varchar(36)"`
	DeviceID  string `gorm:"primaryKey;type:varchar(36)"`
	CreatedAt time.Time
}

// Listen은 끝까지 들었는지 넘겼는지 기록이다.
type Listen struct {
	ID        uint         `gorm:"primaryKey"`
	MessageID string       `gorm:"type:varchar(36);not null;index"`
	DeviceID  string       `gorm:"type:varchar(36);not null;index"`
	Result    ListenResult `gorm:"type:varchar(16);not null"`
	PlayedMs  int
	CreatedAt time.Time
}
