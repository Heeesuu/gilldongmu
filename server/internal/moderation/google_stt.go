package moderation

import (
	"context"
	"encoding/binary"
	"fmt"
	"strings"
	"time"

	speech "cloud.google.com/go/speech/apiv1"
	"cloud.google.com/go/speech/apiv1/speechpb"
	gax "github.com/googleapis/gax-go/v2"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// GoogleSTT는 Google Cloud Speech-to-Text(v1)로 한마디를 글자로 바꾼다.
// 인증은 기본 사용자 인증 정보(ADC)를 쓴다. Cloud Run에서는 서비스 계정으로 붙으므로 키 파일이 필요 없다.
// 한마디는 15초 이하라 동기 인식(Recognize, 1분까지)으로 충분하다.
type GoogleSTT struct {
	client   recognizer
	closer   func() error
	language string
}

var _ usecase.SpeechToText = (*GoogleSTT)(nil)

// recognizer는 테스트에서 가짜로 바꿔 끼우기 위한 좁은 인터페이스다(speech.Client가 만족한다).
type recognizer interface {
	Recognize(ctx context.Context, req *speechpb.RecognizeRequest, opts ...gax.CallOption) (*speechpb.RecognizeResponse, error)
}

const sttTimeout = 20 * time.Second

func NewGoogleSTT(ctx context.Context, language string) (*GoogleSTT, error) {
	client, err := speech.NewClient(ctx)
	if err != nil {
		return nil, fmt.Errorf("Google STT 연결 실패: %w", err)
	}
	return &GoogleSTT{client: client, closer: client.Close, language: language}, nil
}

func (g *GoogleSTT) Close() error {
	if g.closer == nil {
		return nil
	}
	return g.closer()
}

func (g *GoogleSTT) Transcribe(ctx context.Context, audio []byte, mime string) (string, error) {
	if mime != domain.AudioMimeOgg {
		return "", fmt.Errorf("지원하지 않는 음성 형식: %q", mime)
	}
	ctx, cancel := context.WithTimeout(ctx, sttTimeout)
	defer cancel()
	resp, err := g.client.Recognize(ctx, &speechpb.RecognizeRequest{
		Config: &speechpb.RecognitionConfig{
			Encoding:                   speechpb.RecognitionConfig_OGG_OPUS,
			SampleRateHertz:            oggOpusSampleRate(audio),
			LanguageCode:               g.language,
			EnableAutomaticPunctuation: true,
		},
		Audio: &speechpb.RecognitionAudio{AudioSource: &speechpb.RecognitionAudio_Content{Content: audio}},
	})
	if err != nil {
		return "", fmt.Errorf("Google STT 호출 실패: %w", err)
	}
	return joinTranscripts(resp), nil
}

// joinTranscripts는 구간마다 가장 그럴듯한 후보를 이어 붙인다. 말소리가 없으면 빈 글자다(검수에서 보류된다).
func joinTranscripts(resp *speechpb.RecognizeResponse) string {
	var parts []string
	for _, r := range resp.GetResults() {
		if alts := r.GetAlternatives(); len(alts) > 0 {
			if t := strings.TrimSpace(alts[0].GetTranscript()); t != "" {
				parts = append(parts, t)
			}
		}
	}
	return strings.Join(parts, " ")
}

// oggOpusSampleRate는 Ogg Opus 머리글(OpusHead)의 원래 표본율을 읽는다.
// Google은 OGG_OPUS에 8000·12000·16000·24000·48000 중 하나를 요구하므로, 그 밖이거나 읽지 못하면 Opus 기본값 48000을 쓴다.
// 앱(MediaRecorder)은 16000으로 녹음한다.
func oggOpusSampleRate(audio []byte) int32 {
	const fallback = 48000
	head := audio
	if len(head) > 512 {
		head = head[:512]
	}
	i := strings.Index(string(head), "OpusHead")
	// OpusHead(8) + 버전(1) + 채널 수(1) + 앞 건너뛰기(2) 다음 4바이트가 표본율이다.
	if i < 0 || i+16 > len(audio) {
		return fallback
	}
	switch rate := int32(binary.LittleEndian.Uint32(audio[i+12 : i+16])); rate {
	case 8000, 12000, 16000, 24000, 48000:
		return rate
	default:
		return fallback
	}
}
