// Package testsupport는 usecase·handler 테스트가 함께 쓰는 메모리 DB와 가짜 의존성이다.
package testsupport

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"gorm.io/gorm"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/moderation"
	"gildongmu/server/internal/repository"
	"gildongmu/server/internal/usecase"
)

// 테스트 기준 위치: 한마디를 남긴 곳(북동쪽 45°로 가던 중)과 그 앞뒤.
const (
	MessageLat, MessageLon = 37.5672, 126.9790
	BehindLat, BehindLon   = 37.5660, 126.9775 // 한마디 약 190m 뒤(남서쪽)
	PassedLat, PassedLon   = 37.5684, 126.9805 // 한마디를 약 190m 지나친 곳(북동쪽)
	Heading                = 45.0
)

// Env는 테스트 하나에 필요한 것을 묶는다.
type Env struct {
	DB         *gorm.DB
	Repo       *repository.MessageRepository
	Messages   *usecase.MessageUsecase
	Moderation *usecase.ModerationUsecase
	Profiles   *usecase.ProfileUsecase
	Queue      *FakeQueue
	Clock      *FakeClock
	STT        *FakeSTT
	Judge      *FakeJudge
}

// NewEnv는 테스트마다 따로 쓰는 메모리 SQLite와 가짜 STT·판정기로 usecase를 만든다.
func NewEnv(t *testing.T) *Env {
	t.Helper()
	name := strings.NewReplacer("/", "_", " ", "_").Replace(t.Name())
	db, _, err := repository.Open("", fmt.Sprintf("file:%s?mode=memory&cache=shared", name))
	if err != nil {
		t.Fatal(err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		t.Fatal(err)
	}
	sqlDB.SetMaxOpenConns(1)
	t.Cleanup(func() { _ = sqlDB.Close() })
	if err := repository.Migrate(db); err != nil {
		t.Fatal(err)
	}

	repo := repository.NewMessageRepository(db)
	env := &Env{
		DB:    db,
		Repo:  repo,
		Queue: &FakeQueue{},
		Clock: &FakeClock{now: time.Date(2026, 11, 7, 23, 50, 0, 0, time.UTC)},
		STT:   &FakeSTT{Text: "다들 오늘도 힘내세요"},
		Judge: &FakeJudge{Result: usecase.Judgement{Grade: domain.ReviewClean, Kind: domain.KindGreeting, Reason: "응원"}},
	}
	ids := 0
	newID := func() string {
		ids++
		return fmt.Sprintf("00000000-0000-4000-8000-%012d", ids)
	}
	env.Messages = usecase.NewMessageUsecase(repo, repository.NewUnitOfWork(db), env.Queue, env.Clock.Now, newID)
	env.Moderation = usecase.NewModerationUsecase(repo, env.STT, env.Judge, moderation.NewDictionaryFrom("씨발\n"), false)
	env.Profiles = usecase.NewProfileUsecase(repository.NewProfileRepository(db), moderation.NewDictionaryFrom("씨발\n"), env.Clock.Now)
	return env
}

// UploadAt은 녹음 기본값으로 한마디를 올린다.
func (e *Env) UploadAt(t *testing.T, deviceID string, lat, lon, heading float64) *domain.Message {
	t.Helper()
	return e.upload(t, deviceID, lat, lon, &heading)
}

// UploadWithoutHeading은 방향을 모른 채 한마디를 올린다.
func (e *Env) UploadWithoutHeading(t *testing.T, deviceID string, lat, lon float64) *domain.Message {
	t.Helper()
	return e.upload(t, deviceID, lat, lon, nil)
}

func (e *Env) upload(t *testing.T, deviceID string, lat, lon float64, heading *float64) *domain.Message {
	t.Helper()
	m, err := e.Messages.Upload(context.Background(), usecase.UploadInput{
		DeviceID:   deviceID,
		Audio:      []byte("OggS-fake-opus"),
		AudioMime:  domain.AudioMimeOgg,
		RecordedAt: e.Clock.Now(),
		Lat:        lat,
		Lon:        lon,
		Heading:    heading,
		SpeedKmh:   30,
		DurationMs: 6400,
	})
	if err != nil {
		t.Fatal(err)
	}
	return m
}

// UploadAndModerate는 올리고 바로 검수까지 마친다.
func (e *Env) UploadAndModerate(t *testing.T, deviceID string) *domain.Message {
	t.Helper()
	m := e.UploadAt(t, deviceID, MessageLat, MessageLon, Heading)
	if err := e.Moderation.Moderate(context.Background(), m.ID); err != nil {
		t.Fatal(err)
	}
	got, err := e.Repo.Get(context.Background(), m.ID)
	if err != nil {
		t.Fatal(err)
	}
	return got
}

type FakeQueue struct {
	mu  sync.Mutex
	IDs []string
}

func (q *FakeQueue) Enqueue(id string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.IDs = append(q.IDs, id)
}

type FakeClock struct{ now time.Time }

func (c *FakeClock) Now() time.Time          { return c.now }
func (c *FakeClock) Advance(d time.Duration) { c.now = c.now.Add(d) }

type FakeSTT struct {
	Text string
	Err  error
}

func (s *FakeSTT) Transcribe(context.Context, []byte, string) (string, error) { return s.Text, s.Err }

type FakeJudge struct {
	Result usecase.Judgement
	Err    error
}

func (j *FakeJudge) Judge(context.Context, string) (usecase.Judgement, error) { return j.Result, j.Err }

// ErrFake는 실패 경로 테스트용이다.
var ErrFake = errors.New("가짜 실패")
