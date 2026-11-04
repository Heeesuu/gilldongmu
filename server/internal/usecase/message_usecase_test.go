package usecase_test

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"gildongmu/server/internal/domain"
	ts "gildongmu/server/internal/testsupport"
	"gildongmu/server/internal/usecase"
)

const (
	author   = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	listener = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
)

func nearby(t *testing.T, env *ts.Env, device string, lat, lon, heading float64, filterOn bool) usecase.NearbyResult {
	t.Helper()
	r, err := env.Messages.Nearby(context.Background(), usecase.NearbyInput{
		DeviceID: device, Lat: lat, Lon: lon, Heading: heading, SpeedKmh: 40, FilterOn: filterOn,
	})
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestUploadStartsAsSeedAndIsQueued(t *testing.T) {
	env := ts.NewEnv(t)
	m := env.UploadAt(t, author, ts.MessageLat, ts.MessageLon, ts.Heading)

	if m.StageAt(env.Clock.Now()) != domain.StageSeed {
		t.Errorf("올린 직후는 씨앗이어야 합니다")
	}
	if len(env.Queue.IDs) != 1 || env.Queue.IDs[0] != m.ID {
		t.Errorf("검수 대기열에 들어가야 합니다: %v", env.Queue.IDs)
	}
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("검수 전에는 다른 사람에게 보이면 안 됩니다")
	}
}

// 시연 1번 장면: 같은 방향으로 지나면 들리고, 반대 방향이거나 이미 지나쳤으면 안 들린다.
func TestNearbyDirectionFilter(t *testing.T) {
	env := ts.NewEnv(t)
	m := env.UploadAndModerate(t, author)

	same := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true)
	if same.TotalAhead != 1 || same.Items[0].Message.ID != m.ID {
		t.Fatalf("같은 방향 뒤쪽에서는 한마디가 보여야 합니다: %+v", same)
	}
	if eta := same.Items[0].EtaSec; eta == nil || *eta <= 0 {
		t.Errorf("속도가 있으면 도착 예상 시간이 있어야 합니다")
	}
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, 225, true); r.TotalAhead != 0 {
		t.Errorf("반대 방향으로 가는 차에는 보이면 안 됩니다")
	}
	if r := nearby(t, env, listener, ts.PassedLat, ts.PassedLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("이미 지나친 한마디는 보이면 안 됩니다")
	}
	if r := nearby(t, env, author, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("내가 남긴 한마디는 나에게 들려주지 않습니다")
	}
}

// 방향을 끝내 모른 한마디는 양쪽 방향 차 모두에 들린다. 앞쪽에 있어야 한다는 조건은 그대로다.
func TestNearbyMessageWithoutHeading(t *testing.T) {
	env := ts.NewEnv(t)
	m := env.UploadWithoutHeading(t, author, ts.MessageLat, ts.MessageLon)
	if m.Heading != nil {
		t.Fatalf("방향 없이 올린 한마디는 heading이 비어 있어야 합니다: %v", *m.Heading)
	}
	if err := env.Moderation.Moderate(context.Background(), m.ID); err != nil {
		t.Fatal(err)
	}

	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 1 {
		t.Errorf("같은 방향으로 다가오는 차에 보여야 합니다: %+v", r)
	}
	// 지나친 곳에서 반대로 돌아오면 한마디가 앞쪽에 있다.
	if r := nearby(t, env, listener, ts.PassedLat, ts.PassedLon, 225, true); r.TotalAhead != 1 {
		t.Errorf("반대 방향으로 다가오는 차에도 보여야 합니다: %+v", r)
	}
	if r := nearby(t, env, listener, ts.PassedLat, ts.PassedLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("이미 지나친 한마디는 보이면 안 됩니다")
	}
}

func ptr(v float64) *float64 { return &v }

// 시연 2번 장면: 고마워요 5개면 꽃이 된다. 같은 기기는 한 번만 센다.
func TestThanksTurnsIntoFlower(t *testing.T) {
	env := ts.NewEnv(t)
	m := env.UploadAndModerate(t, author)
	ctx := context.Background()

	var last usecase.ThanksResult
	for i := 1; i <= domain.FlowerThanks; i++ {
		var err error
		last, err = env.Messages.Thanks(ctx, fmt.Sprintf("cccccccc-cccc-4ccc-8ccc-%012d", i), m.ID)
		if err != nil {
			t.Fatal(err)
		}
	}
	if last.ThanksCount != domain.FlowerThanks || last.Stage != domain.StageFlower {
		t.Fatalf("고마워요 5개면 꽃이어야 합니다: %+v", last)
	}

	again, err := env.Messages.Thanks(ctx, "cccccccc-cccc-4ccc-8ccc-000000000001", m.ID)
	if err != nil {
		t.Fatal(err)
	}
	if !again.AlreadyThanked || again.ThanksCount != domain.FlowerThanks {
		t.Errorf("같은 기기의 고마워요는 다시 세지 않아야 합니다: %+v", again)
	}
}

func TestProfanityFilter(t *testing.T) {
	env := ts.NewEnv(t)
	env.STT.Text = "씨발 여기 또 막히네"
	m := env.UploadAndModerate(t, author)

	if m.Review != domain.ReviewProfanity {
		t.Fatalf("사전에 걸리면 비속어로 판정해야 합니다: %s", m.Review)
	}
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("필터가 켜져 있으면 비속어 한마디는 빠져야 합니다")
	}
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, false); r.TotalAhead != 1 {
		t.Errorf("필터를 끄면 비속어 한마디도 들려야 합니다")
	}
}

func TestModerationFailuresAreHeld(t *testing.T) {
	cases := map[string]func(env *ts.Env){
		"음성 변환 실패": func(env *ts.Env) { env.STT.Err = ts.ErrFake },
		"빈 음성":     func(env *ts.Env) { env.STT.Text = "  " },
		"LLM 실패":   func(env *ts.Env) { env.Judge.Err = ts.ErrFake },
		"홍보":       func(env *ts.Env) { env.Judge.Result.Promo = true },
	}
	for name, setup := range cases {
		t.Run(name, func(t *testing.T) {
			env := ts.NewEnv(t)
			setup(env)
			m := env.UploadAndModerate(t, author)
			if m.Review != domain.ReviewHold || m.ExpiresAt != nil {
				t.Errorf("보류여야 하고 유효기간이 없어야 합니다: review=%s expires=%v", m.Review, m.ExpiresAt)
			}
		})
	}
}

func TestListenCompletedExtendsExpiryAndHidesForListener(t *testing.T) {
	env := ts.NewEnv(t)
	env.Judge.Result.Kind = domain.KindRoad
	m := env.UploadAndModerate(t, author)
	ctx := context.Background()

	if err := env.Messages.Listen(ctx, listener, m.ID, domain.ListenCompleted, 6400); err != nil {
		t.Fatal(err)
	}
	got, _ := env.Repo.Get(ctx, m.ID)
	if got.ListenCount != 1 || !got.ExpiresAt.Equal(m.ExpiresAt.Add(30*time.Minute)) {
		t.Errorf("끝까지 들으면 횟수와 유효기간(+30분)이 늘어야 합니다: count=%d expires=%v", got.ListenCount, got.ExpiresAt)
	}
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("이미 들은 한마디는 다시 안내하지 않아야 합니다")
	}
	if err := env.Messages.Listen(ctx, listener, m.ID, "liked", 0); err == nil {
		t.Errorf("result는 completed/skipped만 받아야 합니다")
	}
}

func TestExpiredMessageIsNotAnnounced(t *testing.T) {
	env := ts.NewEnv(t)
	env.Judge.Result.Kind = domain.KindRoad
	env.UploadAndModerate(t, author)

	env.Clock.Advance(3*time.Hour + time.Minute)
	if r := nearby(t, env, listener, ts.BehindLat, ts.BehindLon, ts.Heading, true); r.TotalAhead != 0 {
		t.Errorf("유효기간(길 상황 3시간)이 끝나면 안내하지 않아야 합니다")
	}
}

func TestDeleteOnlyByOwner(t *testing.T) {
	env := ts.NewEnv(t)
	m := env.UploadAndModerate(t, author)
	ctx := context.Background()

	if err := env.Messages.Delete(ctx, listener, m.ID); !errors.Is(err, domain.ErrNotOwner) {
		t.Fatalf("남이 지우면 NOT_OWNER여야 합니다: %v", err)
	}
	if err := env.Messages.Delete(ctx, author, m.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := env.Repo.Get(ctx, m.ID); !errors.Is(err, domain.ErrNotFound) {
		t.Errorf("지운 뒤에는 찾을 수 없어야 합니다: %v", err)
	}
}

func TestDailyUploadLimit(t *testing.T) {
	env := ts.NewEnv(t)
	for i := 0; i < domain.DailyUploadLimit; i++ {
		env.UploadAt(t, author, ts.MessageLat, ts.MessageLon, ts.Heading)
	}
	_, err := env.Messages.Upload(context.Background(), usecase.UploadInput{
		DeviceID: author, Audio: []byte("x"), AudioMime: domain.AudioMimeOgg, RecordedAt: env.Clock.Now(),
		Lat: ts.MessageLat, Lon: ts.MessageLon, Heading: ptr(ts.Heading), DurationMs: 1000,
	})
	if !errors.Is(err, domain.ErrRateLimited) {
		t.Errorf("하루 한도를 넘으면 RATE_LIMITED여야 합니다: %v", err)
	}
}

func TestUploadValidation(t *testing.T) {
	env := ts.NewEnv(t)
	base := usecase.UploadInput{
		DeviceID: author, Audio: []byte("x"), AudioMime: domain.AudioMimeOgg, RecordedAt: env.Clock.Now(),
		Lat: ts.MessageLat, Lon: ts.MessageLon, Heading: ptr(ts.Heading), DurationMs: 1000,
	}
	cases := map[string]func(in *usecase.UploadInput){
		"형식":     func(in *usecase.UploadInput) { in.AudioMime = "audio/mp4" },
		"15초 초과": func(in *usecase.UploadInput) { in.DurationMs = 15_001 },
		"방향 범위":  func(in *usecase.UploadInput) { in.Heading = ptr(360.0) },
		"위도 범위":  func(in *usecase.UploadInput) { in.Lat = 91 },
	}
	for name, mutate := range cases {
		in := base
		mutate(&in)
		var de *domain.Error
		if _, err := env.Messages.Upload(context.Background(), in); !errors.As(err, &de) {
			t.Errorf("%s: 도메인 오류여야 합니다: %v", name, err)
		}
	}
}
