package usecase

import (
	"context"
	"time"

	"gildongmu/server/internal/domain"
)

// MessageRepository는 usecase가 필요로 하는 저장소 기능이다. 구현은 repository 패키지에 있다.
type MessageRepository interface {
	Create(ctx context.Context, m *domain.Message) error
	Get(ctx context.Context, id string) (*domain.Message, error)
	Delete(ctx context.Context, id string) error

	// FindPublicInCells는 칸 안에서 공개 판정이고 유효기간 안인 한마디를 찾는다. excludeDeviceID가 남긴 것은 뺀다.
	FindPublicInCells(ctx context.Context, cells []domain.Cell, now time.Time, excludeDeviceID string) ([]*domain.Message, error)
	// ListenedIDs는 ids 가운데 deviceID가 이미 들었거나 넘긴 것을 돌려준다.
	ListenedIDs(ctx context.Context, deviceID string, ids []string) (map[string]bool, error)
	CountUploadsSince(ctx context.Context, deviceID string, since time.Time) (int64, error)
	ListByDevice(ctx context.Context, deviceID string, limit int) ([]*domain.Message, error)

	FindPendingIDs(ctx context.Context, limit int) ([]string, error)
	SaveReview(ctx context.Context, id string, r ReviewOutcome) error

	// AddThanks는 처음 누른 경우에만 true를 돌려준다.
	AddThanks(ctx context.Context, messageID, deviceID string, at time.Time) (bool, error)
	IncrementThanks(ctx context.Context, id string) error
	AddListen(ctx context.Context, l *domain.Listen) error
	ApplyListen(ctx context.Context, id string, completed bool, expiresAt *time.Time) error
}

// UnitOfWork는 여러 저장 작업을 한 트랜잭션으로 묶는다.
type UnitOfWork interface {
	Do(ctx context.Context, fn func(repo MessageRepository) error) error
}

// ReviewOutcome은 검수 파이프라인이 저장할 결과다.
type ReviewOutcome struct {
	Transcript string
	Review     domain.Review
	Kind       domain.Kind
	Reason     string
	ExpiresAt  *time.Time
}

// SpeechToText는 음성을 글자로 바꾼다. 어떤 서비스를 쓸지는 아직 정하지 않았다.
type SpeechToText interface {
	Transcribe(ctx context.Context, audio []byte, mime string) (string, error)
}

// Judgement는 LLM 판정 결과다.
type Judgement struct {
	Grade  domain.Review // clean, profanity, hold, blocked 중 하나
	Kind   domain.Kind
	Promo  bool
	Reason string
}

// Judge는 글자로 바꾼 한마디를 판정한다(Claude API).
type Judge interface {
	Judge(ctx context.Context, transcript string) (Judgement, error)
}

// ProfanityDictionary는 비속어 사전이다.
type ProfanityDictionary interface {
	Contains(text string) bool
}

// ModerationQueue는 업로드된 한마디를 검수 대기열에 넣는다.
type ModerationQueue interface {
	Enqueue(id string)
}
