package handler

import (
	"time"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// uploadMeta는 POST /v1/messages의 meta 파트다. 0도 올바른 값이라 필수 숫자는 포인터로 받아 빠졌는지 확인한다.
type uploadMeta struct {
	RecordedAt  *time.Time `json:"recorded_at"`
	Lat         *float64   `json:"lat"`
	Lon         *float64   `json:"lon"`
	Heading     *float64   `json:"heading"`
	SpeedKmh    *float64   `json:"speed_kmh"`
	DurationMs  *int       `json:"duration_ms"`
	WiperOn     *bool      `json:"wiper_on"`
	HeadlightOn *bool      `json:"headlight_on"`
}

func (m uploadMeta) missing() string {
	switch {
	case m.RecordedAt == nil:
		return "recorded_at"
	case m.Lat == nil:
		return "lat"
	case m.Lon == nil:
		return "lon"
	case m.Heading == nil:
		return "heading"
	case m.SpeedKmh == nil:
		return "speed_kmh"
	case m.DurationMs == nil:
		return "duration_ms"
	}
	return ""
}

type uploadResponse struct {
	ID        string       `json:"id"`
	Stage     domain.Stage `json:"stage"`
	CreatedAt time.Time    `json:"created_at"`
}

type nearbyMessage struct {
	ID          string       `json:"id"`
	Stage       domain.Stage `json:"stage"`
	Kind        domain.Kind  `json:"kind"`
	Lat         float64      `json:"lat"`
	Lon         float64      `json:"lon"`
	Heading     float64      `json:"heading"`
	DistanceM   int          `json:"distance_m"`
	EtaSec      *int         `json:"eta_sec"`
	DurationMs  int          `json:"duration_ms"`
	ThanksCount int          `json:"thanks_count"`
	CreatedAt   time.Time    `json:"created_at"`
	ExpiresAt   *time.Time   `json:"expires_at"`
	AudioURL    string       `json:"audio_url"`
}

type nearbyResponse struct {
	TotalAhead  int             `json:"total_ahead"`
	Messages    []nearbyMessage `json:"messages"`
	NextPollSec int             `json:"next_poll_sec"`
}

type myMessage struct {
	ID          string        `json:"id"`
	Stage       domain.Stage  `json:"stage"`
	Kind        domain.Kind   `json:"kind,omitempty"`
	Review      domain.Review `json:"review"`
	Lat         float64       `json:"lat"`
	Lon         float64       `json:"lon"`
	DurationMs  int           `json:"duration_ms"`
	ListenCount int           `json:"listen_count"`
	ThanksCount int           `json:"thanks_count"`
	CreatedAt   time.Time     `json:"created_at"`
	ExpiresAt   *time.Time    `json:"expires_at"`
	AudioURL    string        `json:"audio_url"`
}

type myMessagesResponse struct {
	Messages []myMessage `json:"messages"`
}

type thanksResponse struct {
	ThanksCount    int          `json:"thanks_count"`
	Stage          domain.Stage `json:"stage"`
	AlreadyThanked bool         `json:"already_thanked"`
}

type listenRequest struct {
	Result   domain.ListenResult `json:"result"`
	PlayedMs int                 `json:"played_ms"`
}

func audioURL(id string) string { return "/v1/messages/" + id + "/audio" }

func toNearbyResponse(r usecase.NearbyResult, now time.Time) nearbyResponse {
	out := nearbyResponse{TotalAhead: r.TotalAhead, Messages: make([]nearbyMessage, 0, len(r.Items)), NextPollSec: 10}
	for _, item := range r.Items {
		m := item.Message
		out.Messages = append(out.Messages, nearbyMessage{
			ID:          m.ID,
			Stage:       m.StageAt(now),
			Kind:        m.Kind,
			Lat:         m.Lat,
			Lon:         m.Lon,
			Heading:     m.Heading,
			DistanceM:   int(item.DistanceM),
			EtaSec:      item.EtaSec,
			DurationMs:  m.DurationMs,
			ThanksCount: m.ThanksCount,
			CreatedAt:   m.CreatedAt,
			ExpiresAt:   m.ExpiresAt,
			AudioURL:    audioURL(m.ID),
		})
	}
	return out
}

func toMyMessages(messages []*domain.Message, now time.Time) myMessagesResponse {
	out := myMessagesResponse{Messages: make([]myMessage, 0, len(messages))}
	for _, m := range messages {
		out.Messages = append(out.Messages, myMessage{
			ID:          m.ID,
			Stage:       m.StageAt(now),
			Kind:        m.Kind,
			Review:      m.Review,
			Lat:         m.Lat,
			Lon:         m.Lon,
			DurationMs:  m.DurationMs,
			ListenCount: m.ListenCount,
			ThanksCount: m.ThanksCount,
			CreatedAt:   m.CreatedAt,
			ExpiresAt:   m.ExpiresAt,
			AudioURL:    audioURL(m.ID),
		})
	}
	return out
}
