package usecase

import (
	"context"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"gildongmu/server/internal/domain"
)

// 닉네임에 쓸 수 있는 글자: 한글·영문·숫자, 낱말 사이 빈칸 하나.
var nicknamePattern = regexp.MustCompile(`^[가-힣a-zA-Z0-9]+( [가-힣a-zA-Z0-9]+)*$`)

// 운영진이나 공식 계정처럼 보일 수 있는 낱말. 띄어쓰기·대소문자는 무시하고 본다.
var reservedNicknameWords = []string{"운영자", "관리자", "공식", "길동무팀", "admin", "official", "gildongmu"}

// ProfileUsecase는 내 길동무(닉네임과 모습)를 정하고 읽는다. 닉네임은 내 기기에서만 보인다.
type ProfileUsecase struct {
	repo       ProfileRepository
	dictionary ProfanityDictionary
	now        func() time.Time
}

func NewProfileUsecase(repo ProfileRepository, dictionary ProfanityDictionary, now func() time.Time) *ProfileUsecase {
	return &ProfileUsecase{repo: repo, dictionary: dictionary, now: now}
}

// UpdateProfileInput은 앱이 보낸 닉네임과 모습이다.
type UpdateProfileInput struct {
	DeviceID  string
	Nickname  string
	LookColor int
	LookItem  domain.LookItem
}

// Get은 내 프로필을 돌려준다. 아직 정하지 않았으면 nil이다.
func (u *ProfileUsecase) Get(ctx context.Context, deviceID string) (*domain.Profile, error) {
	return u.repo.Get(ctx, deviceID)
}

// Update는 닉네임과 모습을 저장한다. 모습은 언제든 바꿀 수 있고, 닉네임은 30일에 한 번만 바꿀 수 있다.
func (u *ProfileUsecase) Update(ctx context.Context, in UpdateProfileInput) (*domain.Profile, error) {
	nickname := strings.Join(strings.Fields(in.Nickname), " ")
	if err := u.validate(nickname, in.LookColor, in.LookItem); err != nil {
		return nil, err
	}
	now := u.now()
	current, err := u.repo.Get(ctx, in.DeviceID)
	if err != nil {
		return nil, err
	}

	p := &domain.Profile{DeviceID: in.DeviceID, Nickname: nickname, LookColor: in.LookColor, LookItem: in.LookItem, NicknameChangedAt: now, UpdatedAt: now}
	if current != nil {
		p.NicknameChangedAt = current.NicknameChangedAt
		if current.Nickname != nickname {
			if now.Before(current.NextNicknameChangeAt()) {
				return nil, domain.NicknameTooSoon(current.NextNicknameChangeAt())
			}
			p.NicknameChangedAt = now
		}
	}
	if err := u.repo.Save(ctx, p); err != nil {
		return nil, err
	}
	return p, nil
}

func (u *ProfileUsecase) validate(nickname string, color int, item domain.LookItem) error {
	n := utf8.RuneCountInString(nickname)
	switch {
	case n < domain.NicknameMinRunes || n > domain.NicknameMaxRunes:
		return domain.Invalid("닉네임은 2~10자여야 합니다.")
	case !nicknamePattern.MatchString(nickname):
		return domain.Invalid("닉네임은 한글·영문·숫자로만 쓸 수 있습니다.")
	case u.dictionary.Contains(nickname) || hasReservedWord(nickname):
		return domain.ErrNicknameNotAllowed
	case color < 0 || color >= domain.LookColorCount:
		return domain.Invalid("look.color는 0~5여야 합니다.")
	case !item.Valid():
		return domain.Invalid("look.item은 none, hat, scarf, backpack 중 하나여야 합니다.")
	}
	return nil
}

func hasReservedWord(nickname string) bool {
	compact := strings.ToLower(strings.ReplaceAll(nickname, " ", ""))
	for _, w := range reservedNicknameWords {
		if strings.Contains(compact, w) {
			return true
		}
	}
	return false
}
