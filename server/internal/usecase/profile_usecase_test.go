package usecase_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"gildongmu/server/internal/domain"
	ts "gildongmu/server/internal/testsupport"
	"gildongmu/server/internal/usecase"
)

func profileInput(nickname string) usecase.UpdateProfileInput {
	return usecase.UpdateProfileInput{DeviceID: author, Nickname: nickname, LookColor: 2, LookItem: domain.LookHat}
}

func TestProfileFirstSetAndRead(t *testing.T) {
	env := ts.NewEnv(t)
	ctx := context.Background()
	if p, err := env.Profiles.Get(ctx, author); err != nil || p != nil {
		t.Fatalf("처음에는 프로필이 없어야 합니다: %v %v", p, err)
	}
	if _, err := env.Profiles.Update(ctx, profileInput("  느긋한   고라니 ")); err != nil {
		t.Fatal(err)
	}
	p, err := env.Profiles.Get(ctx, author)
	if err != nil || p == nil || p.Nickname != "느긋한 고라니" || p.LookColor != 2 || p.LookItem != domain.LookHat {
		t.Fatalf("빈칸을 정리해 저장해야 합니다: %+v %v", p, err)
	}
}

// 닉네임은 30일에 한 번. 모습만 바꾸는 건 언제든 된다.
func TestNicknameChangesOncePerMonth(t *testing.T) {
	env := ts.NewEnv(t)
	ctx := context.Background()
	if _, err := env.Profiles.Update(ctx, profileInput("느긋한 고라니")); err != nil {
		t.Fatal(err)
	}

	env.Clock.Advance(10 * 24 * time.Hour)
	var de *domain.Error
	if _, err := env.Profiles.Update(ctx, profileInput("씩씩한 수달")); !errors.As(err, &de) || de.Code != "NICKNAME_CHANGE_TOO_SOON" {
		t.Fatalf("30일 전에는 닉네임을 바꿀 수 없어야 합니다: %v", err)
	}
	sameName := profileInput("느긋한 고라니")
	sameName.LookColor, sameName.LookItem = 4, domain.LookScarf
	if _, err := env.Profiles.Update(ctx, sameName); err != nil {
		t.Fatalf("모습은 언제든 바꿀 수 있어야 합니다: %v", err)
	}

	env.Clock.Advance(20 * 24 * time.Hour)
	p, err := env.Profiles.Update(ctx, profileInput("씩씩한 수달"))
	if err != nil || p.Nickname != "씩씩한 수달" || !p.NicknameChangedAt.Equal(env.Clock.Now()) {
		t.Fatalf("30일이 지나면 바꿀 수 있어야 합니다: %+v %v", p, err)
	}
}

func TestNicknameValidation(t *testing.T) {
	env := ts.NewEnv(t)
	cases := map[string]string{
		"가": "INVALID_ARGUMENT",
		"열한글자를넘는닉네임이에요": "INVALID_ARGUMENT",
		"고라니!":    "INVALID_ARGUMENT",
		"씨 발 고라니": "NICKNAME_NOT_ALLOWED",
		"길동무 운영자": "NICKNAME_NOT_ALLOWED",
		"Admin 1": "NICKNAME_NOT_ALLOWED",
	}
	for nickname, code := range cases {
		var de *domain.Error
		if _, err := env.Profiles.Update(context.Background(), profileInput(nickname)); !errors.As(err, &de) || de.Code != code {
			t.Errorf("%q: %s여야 합니다: %v", nickname, code, err)
		}
	}
	bad := profileInput("느긋한 고라니")
	bad.LookItem = "crown"
	var de *domain.Error
	if _, err := env.Profiles.Update(context.Background(), bad); !errors.As(err, &de) || de.Code != "INVALID_ARGUMENT" {
		t.Errorf("정해진 소품만 쓸 수 있어야 합니다: %v", err)
	}
}
