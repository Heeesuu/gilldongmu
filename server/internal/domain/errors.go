package domain

import "time"

// Error는 API 계약서의 오류 code와 한국어 message를 함께 담는 도메인 오류다.
// handler가 Code를 보고 HTTP 상태를 정한다.
type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

var (
	ErrNotFound    = &Error{Code: "NOT_FOUND", Message: "한마디를 찾을 수 없습니다."}
	ErrNotOwner    = &Error{Code: "NOT_OWNER", Message: "내가 남긴 한마디만 지울 수 있습니다."}
	ErrRateLimited = &Error{Code: "RATE_LIMITED", Message: "오늘 남길 수 있는 한마디 수를 넘었습니다."}
	ErrTooLarge    = &Error{Code: "PAYLOAD_TOO_LARGE", Message: "음성 파일은 1MB를 넘을 수 없습니다."}
	ErrUnsupported = &Error{Code: "UNSUPPORTED_MEDIA_TYPE", Message: "음성은 audio/ogg(Opus) 형식이어야 합니다."}
	ErrNoDeviceID  = &Error{Code: "MISSING_DEVICE_ID", Message: "X-Device-Id 헤더에 UUID를 넣어야 합니다."}
)

// ErrNicknameNotAllowed는 비속어나 사칭할 수 있는 낱말이 든 닉네임이다.
var ErrNicknameNotAllowed = &Error{Code: "NICKNAME_NOT_ALLOWED", Message: "쓸 수 없는 닉네임입니다. 다른 이름을 골라 주세요."}

// NicknameTooSoon은 30일이 지나기 전에 닉네임을 바꾸려 할 때의 오류다. 다시 바꿀 수 있는 날을 알려 준다.
func NicknameTooSoon(next time.Time) *Error {
	return &Error{Code: "NICKNAME_CHANGE_TOO_SOON", Message: "닉네임은 한 달에 한 번 바꿀 수 있어요. " + next.In(seoul).Format("1월 2일") + "부터 바꿀 수 있습니다."}
}

// 날짜는 한국 시간으로 알려 준다.
var seoul = time.FixedZone("KST", 9*60*60)

// Invalid는 입력값이 잘못됐을 때 쓰는 오류를 만든다.
func Invalid(message string) *Error {
	return &Error{Code: "INVALID_ARGUMENT", Message: message}
}
