package usecase

import (
	"context"
	"math"
	"sort"
	"time"

	"gildongmu/server/internal/domain"
)

// 앞쪽 한마디 찾기 규칙(API 계약서 3.3). 초기값.
const (
	sameDirectionMaxDiff = 45.0
	aheadMaxDiff         = 60.0
	lookaheadMeters      = 600.0
	minSpeedForEtaKmh    = 5.0
	defaultNearbyLimit   = 3
	maxNearbyLimit       = 10
	myMessagesLimit      = 50
)

// MessageUsecase는 한마디 올리기·찾기·반응·삭제의 비즈니스 로직이다.
type MessageUsecase struct {
	repo  MessageRepository
	uow   UnitOfWork
	queue ModerationQueue
	now   func() time.Time
	newID func() string
}

func NewMessageUsecase(repo MessageRepository, uow UnitOfWork, queue ModerationQueue, now func() time.Time, newID func() string) *MessageUsecase {
	return &MessageUsecase{repo: repo, uow: uow, queue: queue, now: now, newID: newID}
}

// UploadInput은 녹음을 시작한 순간의 값이다.
type UploadInput struct {
	DeviceID    string
	Audio       []byte
	AudioMime   string
	RecordedAt  time.Time
	Lat         float64
	Lon         float64
	Heading     *float64 // nil이면 방향을 모른다
	SpeedKmh    float64
	DurationMs  int
	WiperOn     *bool
	HeadlightOn *bool
}

func (in UploadInput) validate() error {
	switch {
	case in.AudioMime != domain.AudioMimeOgg:
		return domain.ErrUnsupported
	case len(in.Audio) == 0:
		return domain.Invalid("음성 파일이 비어 있습니다.")
	case len(in.Audio) > domain.MaxAudioBytes:
		return domain.ErrTooLarge
	case in.DurationMs <= 0 || in.DurationMs > domain.MaxDurationMs:
		return domain.Invalid("녹음 길이는 1~15,000ms여야 합니다.")
	case in.RecordedAt.IsZero():
		return domain.Invalid("recorded_at이 필요합니다.")
	}
	if in.Heading != nil {
		if err := validateHeading(*in.Heading); err != nil {
			return err
		}
	}
	return validatePosition(in.Lat, in.Lon, in.SpeedKmh)
}

func validatePosition(lat, lon, speed float64) error {
	switch {
	case lat < -90 || lat > 90 || lon < -180 || lon > 180:
		return domain.Invalid("위도·경도 범위를 벗어났습니다.")
	case speed < 0:
		return domain.Invalid("speed_kmh는 0 이상이어야 합니다.")
	}
	return nil
}

func validateHeading(heading float64) error {
	if heading < 0 || heading >= 360 {
		return domain.Invalid("heading은 0 이상 360 미만이어야 합니다.")
	}
	return nil
}

// Upload는 한마디를 씨앗으로 등록하고 검수 대기열에 넣는다.
func (u *MessageUsecase) Upload(ctx context.Context, in UploadInput) (*domain.Message, error) {
	if err := in.validate(); err != nil {
		return nil, err
	}
	now := u.now()
	count, err := u.repo.CountUploadsSince(ctx, in.DeviceID, now.Add(-24*time.Hour))
	if err != nil {
		return nil, err
	}
	if count >= domain.DailyUploadLimit {
		return nil, domain.ErrRateLimited
	}

	cell := domain.CellOf(in.Lat, in.Lon)
	m := &domain.Message{
		ID:          u.newID(),
		DeviceID:    in.DeviceID,
		Lat:         in.Lat,
		Lon:         in.Lon,
		Heading:     in.Heading,
		SpeedKmh:    in.SpeedKmh,
		CellX:       cell.X,
		CellY:       cell.Y,
		WiperOn:     in.WiperOn,
		HeadlightOn: in.HeadlightOn,
		Audio:       in.Audio,
		AudioMime:   in.AudioMime,
		DurationMs:  in.DurationMs,
		Review:      domain.ReviewPending,
		RecordedAt:  in.RecordedAt,
		CreatedAt:   now,
	}
	if err := u.repo.Create(ctx, m); err != nil {
		return nil, err
	}
	u.queue.Enqueue(m.ID)
	return m, nil
}

// NearbyInput은 현재 차량 상태다.
type NearbyInput struct {
	DeviceID string
	Lat      float64
	Lon      float64
	Heading  float64
	SpeedKmh float64
	FilterOn bool
	Limit    int
}

// NearbyItem은 앞쪽 한마디 하나와 거리·도착 예상 시간이다.
type NearbyItem struct {
	Message   *domain.Message
	DistanceM float64
	EtaSec    *int
}

type NearbyResult struct {
	TotalAhead int
	Items      []NearbyItem
}

// Nearby는 같은 방향·앞쪽·아직 듣지 않은 한마디를 순서대로 돌려준다.
func (u *MessageUsecase) Nearby(ctx context.Context, in NearbyInput) (NearbyResult, error) {
	if err := validateHeading(in.Heading); err != nil {
		return NearbyResult{}, err
	}
	if err := validatePosition(in.Lat, in.Lon, in.SpeedKmh); err != nil {
		return NearbyResult{}, err
	}
	limit := in.Limit
	if limit <= 0 {
		limit = defaultNearbyLimit
	}
	if limit > maxNearbyLimit {
		limit = maxNearbyLimit
	}

	now := u.now()
	candidates, err := u.repo.FindPublicInCells(ctx, domain.CellOf(in.Lat, in.Lon).Neighbors(), now, in.DeviceID)
	if err != nil {
		return NearbyResult{}, err
	}
	ids := make([]string, 0, len(candidates))
	for _, m := range candidates {
		ids = append(ids, m.ID)
	}
	listened, err := u.repo.ListenedIDs(ctx, in.DeviceID, ids)
	if err != nil {
		return NearbyResult{}, err
	}

	items := make([]NearbyItem, 0, len(candidates))
	for _, m := range candidates {
		if listened[m.ID] || (in.FilterOn && m.Review == domain.ReviewProfanity) {
			continue
		}
		// 방향을 모르는 한마디는 같은 방향 조건을 건너뛴다. 앞쪽 조건은 듣는 차의 방향으로 확인한다.
		if m.Heading != nil && domain.AngleDiff(*m.Heading, in.Heading) > sameDirectionMaxDiff {
			continue
		}
		distance := domain.DistanceMeters(in.Lat, in.Lon, m.Lat, m.Lon)
		if distance > lookaheadMeters {
			continue
		}
		if domain.AngleDiff(domain.Bearing(in.Lat, in.Lon, m.Lat, m.Lon), in.Heading) > aheadMaxDiff {
			continue
		}
		items = append(items, NearbyItem{Message: m, DistanceM: distance, EtaSec: etaSec(distance, in.SpeedKmh)})
	}

	sort.SliceStable(items, func(i, j int) bool {
		a, b := items[i].Message, items[j].Message
		af, bf := a.StageAt(now) == domain.StageFlower, b.StageAt(now) == domain.StageFlower
		if af != bf {
			return af
		}
		if a.ThanksCount != b.ThanksCount {
			return a.ThanksCount > b.ThanksCount
		}
		return a.CreatedAt.After(b.CreatedAt)
	})

	result := NearbyResult{TotalAhead: len(items), Items: items}
	if len(items) > limit {
		result.Items = items[:limit]
	}
	return result, nil
}

func etaSec(distance, speedKmh float64) *int {
	if speedKmh < minSpeedForEtaKmh {
		return nil
	}
	sec := int(math.Round(distance / (speedKmh / 3.6)))
	return &sec
}

// Audio는 음성을 돌려준다. 공개 전이거나 유효기간이 끝난 한마디는 남긴 사람만 받을 수 있다.
func (u *MessageUsecase) Audio(ctx context.Context, deviceID, id string) (*domain.Message, error) {
	m, err := u.repo.Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if m.DeviceID != deviceID && (!m.IsPublic() || m.IsExpired(u.now())) {
		return nil, domain.ErrNotFound
	}
	return m, nil
}

type ThanksResult struct {
	ThanksCount    int
	Stage          domain.Stage
	AlreadyThanked bool
}

// Thanks는 고마워요를 기기당 한 번 센다.
func (u *MessageUsecase) Thanks(ctx context.Context, deviceID, id string) (ThanksResult, error) {
	var result ThanksResult
	err := u.uow.Do(ctx, func(repo MessageRepository) error {
		now := u.now()
		m, err := repo.Get(ctx, id)
		if err != nil {
			return err
		}
		if !m.IsPublic() || m.IsExpired(now) {
			return domain.ErrNotFound
		}
		added, err := repo.AddThanks(ctx, id, deviceID, now)
		if err != nil {
			return err
		}
		if added {
			if err := repo.IncrementThanks(ctx, id); err != nil {
				return err
			}
			m.ThanksCount++
		}
		result = ThanksResult{ThanksCount: m.ThanksCount, Stage: m.StageAt(now), AlreadyThanked: !added}
		return nil
	})
	return result, err
}

// Listen은 끝까지 들었는지 넘겼는지 기록하고, 끝까지 들었으면 유효기간을 늘린다.
func (u *MessageUsecase) Listen(ctx context.Context, deviceID, id string, result domain.ListenResult, playedMs int) error {
	if result != domain.ListenCompleted && result != domain.ListenSkipped {
		return domain.Invalid("result는 completed 또는 skipped여야 합니다.")
	}
	if playedMs < 0 {
		return domain.Invalid("played_ms는 0 이상이어야 합니다.")
	}
	return u.uow.Do(ctx, func(repo MessageRepository) error {
		m, err := repo.Get(ctx, id)
		if err != nil {
			return err
		}
		listen := &domain.Listen{MessageID: id, DeviceID: deviceID, Result: result, PlayedMs: playedMs, CreatedAt: u.now()}
		if err := repo.AddListen(ctx, listen); err != nil {
			return err
		}
		completed := result == domain.ListenCompleted
		var expiresAt *time.Time
		if completed {
			expiresAt = m.ExtendedExpiry()
		}
		return repo.ApplyListen(ctx, id, completed, expiresAt)
	})
}

// MyMessages는 이 기기가 남긴 한마디를 최근 순으로 돌려준다.
func (u *MessageUsecase) MyMessages(ctx context.Context, deviceID string) ([]*domain.Message, error) {
	return u.repo.ListByDevice(ctx, deviceID, myMessagesLimit)
}

// Delete는 남긴 기기만 지울 수 있다. 음성과 글자, 반응 기록까지 함께 지운다.
func (u *MessageUsecase) Delete(ctx context.Context, deviceID, id string) error {
	return u.uow.Do(ctx, func(repo MessageRepository) error {
		m, err := repo.Get(ctx, id)
		if err != nil {
			return err
		}
		if m.DeviceID != deviceID {
			return domain.ErrNotOwner
		}
		return repo.Delete(ctx, id)
	})
}

// Now는 handler가 단계(stage)를 계산할 때 같은 시계를 쓰도록 연다.
func (u *MessageUsecase) Now() time.Time { return u.now() }
