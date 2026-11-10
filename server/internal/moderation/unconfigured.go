package moderation

import (
	"context"
	"errors"

	"gildongmu/server/internal/usecase"
)

// UnconfiguredSTT는 STT_PROVIDER가 없을 때(로컬 개발) 쓰는 자리 표시자다. 항상 실패해서 한마디가 보류되게 한다.
type UnconfiguredSTT struct{}

var _ usecase.SpeechToText = UnconfiguredSTT{}

func (UnconfiguredSTT) Transcribe(context.Context, []byte, string) (string, error) {
	return "", errors.New("STT 서비스가 설정되지 않았습니다(STT_PROVIDER)")
}

// UnconfiguredJudge는 ANTHROPIC_API_KEY가 없을 때 쓴다. 항상 실패해서 한마디가 보류되게 한다.
type UnconfiguredJudge struct{}

var _ usecase.Judge = UnconfiguredJudge{}

func (UnconfiguredJudge) Judge(context.Context, string) (usecase.Judgement, error) {
	return usecase.Judgement{}, errors.New("ANTHROPIC_API_KEY가 설정되지 않았습니다")
}
