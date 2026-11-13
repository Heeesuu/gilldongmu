package domain

import "time"

// 내 길동무 규칙(API 계약서 3.9). 닉네임은 내 기기에서만 보이고 다른 기기에는 내려주지 않는다.
const (
	NicknameMinRunes       = 2
	NicknameMaxRunes       = 10
	NicknameChangeInterval = 30 * 24 * time.Hour
	LookColorCount         = 6
)

// LookItem은 내 길동무가 걸친 소품이다.
type LookItem string

const (
	LookNone     LookItem = "none"
	LookHat      LookItem = "hat"
	LookScarf    LookItem = "scarf"
	LookBackpack LookItem = "backpack"
)

// Valid는 정해진 소품인지 본다.
func (i LookItem) Valid() bool {
	switch i {
	case LookNone, LookHat, LookScarf, LookBackpack:
		return true
	}
	return false
}

// Profile은 기기 하나의 내 길동무(닉네임과 모습)다. 로그인 대신 X-Device-Id로 구분한다.
type Profile struct {
	DeviceID          string   `gorm:"primaryKey;type:varchar(36)"`
	Nickname          string   `gorm:"type:varchar(40);not null"`
	LookColor         int      `gorm:"not null;default:0"`
	LookItem          LookItem `gorm:"type:varchar(16);not null;default:none"`
	NicknameChangedAt time.Time
	UpdatedAt         time.Time
}

// NextNicknameChangeAt은 닉네임을 다시 바꿀 수 있는 가장 이른 시각이다.
func (p *Profile) NextNicknameChangeAt() time.Time {
	return p.NicknameChangedAt.Add(NicknameChangeInterval)
}
