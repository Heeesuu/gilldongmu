package usecase

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"gildongmu/server/internal/domain"
)

// ModerationUsecase는 검수 파이프라인이다: 음성 → 글자 → 비속어 사전 → LLM 판정 → 저장.
// 어느 단계든 실패하면 공개하지 않도록 보류(hold)로 둔다.
type ModerationUsecase struct {
	repo           MessageRepository
	stt            SpeechToText
	judge          Judge
	dictionary     ProfanityDictionary
	devAutoApprove bool
}

// devAutoApprove는 로컬 개발용이다. STT·LLM 없이 모든 한마디를 인사(greeting)로 공개한다.
func NewModerationUsecase(repo MessageRepository, stt SpeechToText, judge Judge, dictionary ProfanityDictionary, devAutoApprove bool) *ModerationUsecase {
	return &ModerationUsecase{repo: repo, stt: stt, judge: judge, dictionary: dictionary, devAutoApprove: devAutoApprove}
}

// Moderate는 검수 중인 한마디 하나를 판정해 저장한다.
func (u *ModerationUsecase) Moderate(ctx context.Context, id string) error {
	m, err := u.repo.Get(ctx, id)
	if err != nil {
		return err
	}
	if m.Review != domain.ReviewPending {
		return nil
	}
	outcome := u.decide(ctx, m)
	if outcome.Review == domain.ReviewClean || outcome.Review == domain.ReviewProfanity {
		expiresAt := m.CreatedAt.Add(outcome.Kind.Lifetime())
		outcome.ExpiresAt = &expiresAt
	}
	slog.Info("검수 완료", "id", id, "review", outcome.Review, "kind", outcome.Kind)
	return u.repo.SaveReview(ctx, id, outcome)
}

func (u *ModerationUsecase) decide(ctx context.Context, m *domain.Message) ReviewOutcome {
	if u.devAutoApprove {
		return ReviewOutcome{Review: domain.ReviewClean, Kind: domain.KindGreeting, Reason: "개발용 자동 공개"}
	}

	transcript, err := u.stt.Transcribe(ctx, m.Audio, m.AudioMime)
	if err != nil {
		slog.Warn("음성 변환 실패", "id", m.ID, "err", err)
		return hold("", "음성을 글자로 바꾸지 못했습니다.")
	}
	if strings.TrimSpace(transcript) == "" {
		return hold(transcript, "말소리가 없습니다.")
	}

	j, err := u.judge.Judge(ctx, transcript)
	if err != nil {
		slog.Warn("LLM 판정 실패", "id", m.ID, "err", err)
		return hold(transcript, "판정하지 못했습니다.")
	}

	review := j.Grade
	switch {
	case j.Promo:
		review = domain.ReviewHold
	case review == domain.ReviewClean && u.dictionary.Contains(transcript):
		review = domain.ReviewProfanity
	}
	return ReviewOutcome{Transcript: transcript, Review: review, Kind: j.Kind, Reason: j.Reason}
}

func hold(transcript, reason string) ReviewOutcome {
	return ReviewOutcome{Transcript: transcript, Review: domain.ReviewHold, Reason: reason}
}

// ModerationWorker는 대기열의 한마디를 하나씩 검수한다. MVP는 서버 안 고루틴 하나로 충분하다.
type ModerationWorker struct {
	usecase *ModerationUsecase
	repo    MessageRepository
	ids     chan string
}

func NewModerationWorker(usecase *ModerationUsecase, repo MessageRepository) *ModerationWorker {
	return &ModerationWorker{usecase: usecase, repo: repo, ids: make(chan string, 256)}
}

// Enqueue는 막히지 않는다. 대기열이 가득 차면 다음 시작 때 Run이 다시 집어 간다.
func (w *ModerationWorker) Enqueue(id string) {
	select {
	case w.ids <- id:
	default:
		slog.Warn("검수 대기열이 가득 참", "id", id)
	}
}

// Run은 서버가 꺼졌던 사이 남은 검수 중 한마디를 먼저 넣고, ctx가 끝날 때까지 처리한다.
func (w *ModerationWorker) Run(ctx context.Context) {
	if pending, err := w.repo.FindPendingIDs(ctx, 100); err != nil {
		slog.Error("검수 대기 목록 조회 실패", "err", err)
	} else {
		for _, id := range pending {
			w.Enqueue(id)
		}
	}
	for {
		select {
		case <-ctx.Done():
			return
		case id := <-w.ids:
			jobCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
			if err := w.usecase.Moderate(jobCtx, id); err != nil {
				slog.Error("검수 실패", "id", id, "err", err)
			}
			cancel()
		}
	}
}
