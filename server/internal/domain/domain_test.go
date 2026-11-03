package domain

import (
	"math"
	"testing"
	"time"
)

func TestAngleDiff(t *testing.T) {
	cases := []struct {
		a, b, want float64
	}{
		{0, 0, 0},
		{359, 1, 2},
		{1, 359, 2},
		{90, 270, 180},
		{45, 225, 180},
		{10, 50, 40},
	}
	for _, c := range cases {
		if got := AngleDiff(c.a, c.b); math.Abs(got-c.want) > 1e-9 {
			t.Errorf("AngleDiff(%v, %v) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}

func TestBearingAndDistance(t *testing.T) {
	// 북동쪽으로 약 190m
	lat1, lon1, lat2, lon2 := 37.5660, 126.9775, 37.5672, 126.9790
	if b := Bearing(lat1, lon1, lat2, lon2); math.Abs(b-45) > 3 {
		t.Errorf("방위가 북동쪽(약 45°)이어야 합니다: %v", b)
	}
	if d := DistanceMeters(lat1, lon1, lat2, lon2); d < 170 || d > 210 {
		t.Errorf("거리가 약 190m여야 합니다: %v", d)
	}
	if b := Bearing(lat2, lon2, lat1, lon1); math.Abs(b-225) > 3 {
		t.Errorf("반대 방위는 약 225°여야 합니다: %v", b)
	}
}

func TestCellNeighbors(t *testing.T) {
	c := CellOf(37.5665, 126.9780)
	near := CellOf(37.5665+250.0/metersPerDegree, 126.9780)
	if near.Y-c.Y > 1 || near.X != c.X {
		t.Errorf("250m 북쪽은 같은 칸이거나 바로 옆 칸이어야 합니다: %v → %v", c, near)
	}
	if len(c.Neighbors()) != 9 {
		t.Errorf("둘레 칸은 자기 칸 포함 9개여야 합니다")
	}
}

func TestStageAt(t *testing.T) {
	now := time.Date(2026, 11, 8, 0, 0, 0, 0, time.UTC)
	future := now.Add(time.Hour)
	past := now.Add(-time.Hour)

	cases := []struct {
		name string
		m    Message
		want Stage
	}{
		{"검수 중", Message{Review: ReviewPending}, StageSeed},
		{"보류는 씨앗으로 남음", Message{Review: ReviewHold, ExpiresAt: &future}, StageSeed},
		{"공개", Message{Review: ReviewClean, ExpiresAt: &future}, StageSprout},
		{"비속어도 공개", Message{Review: ReviewProfanity, ExpiresAt: &future}, StageSprout},
		{"고마워요 5개", Message{Review: ReviewClean, ExpiresAt: &future, ThanksCount: FlowerThanks}, StageFlower},
		{"유효기간 끝", Message{Review: ReviewClean, ExpiresAt: &past, ThanksCount: 9}, StageMemory},
	}
	for _, c := range cases {
		if got := c.m.StageAt(now); got != c.want {
			t.Errorf("%s: StageAt = %s, want %s", c.name, got, c.want)
		}
	}
}

func TestExtendedExpiryRespectsCeiling(t *testing.T) {
	created := time.Date(2026, 11, 8, 0, 0, 0, 0, time.UTC)
	expires := created.Add(KindRoad.Lifetime()) // 3시간
	m := Message{Kind: KindRoad, CreatedAt: created, ExpiresAt: &expires}

	if got := m.ExtendedExpiry(); !got.Equal(expires.Add(30 * time.Minute)) {
		t.Errorf("길 상황은 30분 늘어나야 합니다: %v", got)
	}
	almost := created.Add(5*time.Hour + 50*time.Minute)
	m.ExpiresAt = &almost
	if got := m.ExtendedExpiry(); !got.Equal(created.Add(6 * time.Hour)) {
		t.Errorf("길 상황은 만든 뒤 6시간을 넘지 않아야 합니다: %v", got)
	}
}
