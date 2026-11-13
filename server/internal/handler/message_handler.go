package handler

import (
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"strconv"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// multipart의 meta 파트와 경계 문자열 몫으로 음성 한도보다 조금 더 받는다.
const maxUploadBody = domain.MaxAudioBytes + 64<<10

// MessageHandler는 HTTP 입출력만 맡는다. 판단은 usecase에 넘긴다.
type MessageHandler struct {
	uc *usecase.MessageUsecase
}

func NewMessageHandler(uc *usecase.MessageUsecase) *MessageHandler {
	return &MessageHandler{uc: uc}
}

func (h *MessageHandler) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (h *MessageHandler) upload(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBody)
	if err := r.ParseMultipartForm(maxUploadBody); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			writeError(w, domain.ErrTooLarge)
			return
		}
		writeError(w, domain.Invalid("multipart/form-data 형식이어야 합니다."))
		return
	}

	file, header, err := r.FormFile("audio")
	if err != nil {
		writeError(w, domain.Invalid("audio 파트가 필요합니다."))
		return
	}
	defer file.Close()
	mediaType, _, _ := mime.ParseMediaType(header.Header.Get("Content-Type"))
	audio, err := io.ReadAll(io.LimitReader(file, domain.MaxAudioBytes+1))
	if err != nil {
		writeError(w, domain.Invalid("음성 파일을 읽지 못했습니다."))
		return
	}

	var meta uploadMeta
	if err := json.Unmarshal([]byte(r.FormValue("meta")), &meta); err != nil {
		writeError(w, domain.Invalid("meta는 JSON 문자열이어야 합니다."))
		return
	}
	if field := meta.missing(); field != "" {
		writeError(w, domain.Invalid(field+" 값이 필요합니다."))
		return
	}

	m, err := h.uc.Upload(r.Context(), usecase.UploadInput{
		DeviceID:    deviceIDFrom(r.Context()),
		Audio:       audio,
		AudioMime:   mediaType,
		RecordedAt:  *meta.RecordedAt,
		Lat:         *meta.Lat,
		Lon:         *meta.Lon,
		Heading:     meta.Heading,
		SpeedKmh:    *meta.SpeedKmh,
		DurationMs:  *meta.DurationMs,
		WiperOn:     meta.WiperOn,
		HeadlightOn: meta.HeadlightOn,
		Transcript:  meta.Transcript,
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, uploadResponse{ID: m.ID, Stage: m.StageAt(h.uc.Now()), CreatedAt: m.CreatedAt})
}

func (h *MessageHandler) nearby(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	in := usecase.NearbyInput{DeviceID: deviceIDFrom(r.Context()), FilterOn: true}
	var err error
	if in.Lat, err = requiredFloat(q, "lat"); err != nil {
		writeError(w, err)
		return
	}
	if in.Lon, err = requiredFloat(q, "lon"); err != nil {
		writeError(w, err)
		return
	}
	if in.Heading, err = requiredFloat(q, "heading"); err != nil {
		writeError(w, err)
		return
	}
	if in.SpeedKmh, err = requiredFloat(q, "speed_kmh"); err != nil {
		writeError(w, err)
		return
	}
	switch q.Get("filter") {
	case "", "on":
	case "off":
		in.FilterOn = false
	default:
		writeError(w, domain.Invalid("filter는 on 또는 off여야 합니다."))
		return
	}
	if v := q.Get("limit"); v != "" {
		if in.Limit, err = strconv.Atoi(v); err != nil {
			writeError(w, domain.Invalid("limit은 정수여야 합니다."))
			return
		}
	}

	result, err := h.uc.Nearby(r.Context(), in)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, toNearbyResponse(result, h.uc.Now()))
}

func (h *MessageHandler) audio(w http.ResponseWriter, r *http.Request) {
	m, err := h.uc.Audio(r.Context(), deviceIDFrom(r.Context()), r.PathValue("id"))
	if err != nil {
		writeError(w, err)
		return
	}
	w.Header().Set("Content-Type", m.AudioMime)
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Header().Set("Content-Length", strconv.Itoa(len(m.Audio)))
	_, _ = w.Write(m.Audio)
}

func (h *MessageHandler) thanks(w http.ResponseWriter, r *http.Request) {
	result, err := h.uc.Thanks(r.Context(), deviceIDFrom(r.Context()), r.PathValue("id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, thanksResponse{ThanksCount: result.ThanksCount, Stage: result.Stage, AlreadyThanked: result.AlreadyThanked})
}

func (h *MessageHandler) listen(w http.ResponseWriter, r *http.Request) {
	var req listenRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
		writeError(w, domain.Invalid("본문은 {\"result\", \"played_ms\"} JSON이어야 합니다."))
		return
	}
	if err := h.uc.Listen(r.Context(), deviceIDFrom(r.Context()), r.PathValue("id"), req.Result, req.PlayedMs); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *MessageHandler) myMessages(w http.ResponseWriter, r *http.Request) {
	messages, err := h.uc.MyMessages(r.Context(), deviceIDFrom(r.Context()))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, toMyMessages(messages, h.uc.Now()))
}

func (h *MessageHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.uc.Delete(r.Context(), deviceIDFrom(r.Context()), r.PathValue("id")); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func requiredFloat(q url.Values, name string) (float64, error) {
	v := q.Get(name)
	if v == "" {
		return 0, domain.Invalid(name + " 값이 필요합니다.")
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return 0, domain.Invalid(name + "은(는) 숫자여야 합니다.")
	}
	return f, nil
}
