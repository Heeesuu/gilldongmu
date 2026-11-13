package moderation

import (
	"context"
	"encoding/binary"
	"errors"
	"testing"

	"cloud.google.com/go/speech/apiv1/speechpb"
	gax "github.com/googleapis/gax-go/v2"
)

type fakeRecognizer struct {
	got  *speechpb.RecognizeRequest
	resp *speechpb.RecognizeResponse
	err  error
}

func (f *fakeRecognizer) Recognize(_ context.Context, req *speechpb.RecognizeRequest, _ ...gax.CallOption) (*speechpb.RecognizeResponse, error) {
	f.got = req
	return f.resp, f.err
}

// oggWithRate는 첫 쪽에 OpusHead만 담은 Ogg 머리글 흉내를 만든다.
func oggWithRate(rate uint32) []byte {
	head := []byte("OggS" + string(make([]byte, 24)) + "OpusHead")
	head = append(head, 1, 1, 0x38, 0x01) // 버전, 채널 수, 앞 건너뛰기 312
	head = binary.LittleEndian.AppendUint32(head, rate)
	return append(head, 0, 0, 0)
}

func result(texts ...string) *speechpb.SpeechRecognitionResult {
	r := &speechpb.SpeechRecognitionResult{}
	for _, t := range texts {
		r.Alternatives = append(r.Alternatives, &speechpb.SpeechRecognitionAlternative{Transcript: t})
	}
	return r
}

func TestGoogleSTTBuildsRequestAndJoinsResults(t *testing.T) {
	fake := &fakeRecognizer{resp: &speechpb.RecognizeResponse{Results: []*speechpb.SpeechRecognitionResult{
		result("이 앞 사거리는 신호가 길어요.", "이 앞 사거리 신호가 길어요"),
		result(" 천천히 오세요 "),
		result(),
	}}}
	stt := &GoogleSTT{client: fake, language: "ko-KR"}
	audio := oggWithRate(16000)

	got, err := stt.Transcribe(context.Background(), audio, "audio/ogg")
	if err != nil {
		t.Fatal(err)
	}
	if want := "이 앞 사거리는 신호가 길어요. 천천히 오세요"; got != want {
		t.Errorf("글자 = %q, want %q", got, want)
	}
	cfg := fake.got.GetConfig()
	if cfg.GetEncoding() != speechpb.RecognitionConfig_OGG_OPUS || cfg.GetSampleRateHertz() != 16000 || cfg.GetLanguageCode() != "ko-KR" {
		t.Errorf("요청 설정이 다릅니다: %v", cfg)
	}
	if string(fake.got.GetAudio().GetContent()) != string(audio) {
		t.Error("음성을 그대로 보내야 합니다")
	}
}

func TestGoogleSTTRejectsOtherFormatsAndPassesErrors(t *testing.T) {
	fake := &fakeRecognizer{}
	stt := &GoogleSTT{client: fake, language: "ko-KR"}
	if _, err := stt.Transcribe(context.Background(), []byte("RIFF"), "audio/wav"); err == nil || fake.got != nil {
		t.Error("ogg가 아니면 부르지 않고 오류여야 합니다")
	}

	fake.err = errors.New("권한 없음")
	if _, err := stt.Transcribe(context.Background(), oggWithRate(16000), "audio/ogg"); err == nil {
		t.Error("STT 오류는 그대로 돌려 보류되게 해야 합니다")
	}
}

func TestOggOpusSampleRate(t *testing.T) {
	cases := map[string]struct {
		audio []byte
		want  int32
	}{
		"앱 녹음(16kHz)":  {oggWithRate(16000), 16000},
		"48kHz":        {oggWithRate(48000), 48000},
		"허용되지 않는 표본율": {oggWithRate(44100), 48000},
		"머리글 없음":       {[]byte("not an ogg file"), 48000},
		"잘린 머리글":       {oggWithRate(16000)[:40], 48000},
	}
	for name, c := range cases {
		if got := oggOpusSampleRate(c.audio); got != c.want {
			t.Errorf("%s: %d, want %d", name, got, c.want)
		}
	}
}
