package handler

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"gildongmu/server/internal/domain"
)

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

var statusByCode = map[string]int{
	"INVALID_ARGUMENT":       http.StatusBadRequest,
	"MISSING_DEVICE_ID":      http.StatusUnauthorized,
	"NOT_OWNER":              http.StatusForbidden,
	"NOT_FOUND":              http.StatusNotFound,
	"PAYLOAD_TOO_LARGE":      http.StatusRequestEntityTooLarge,
	"UNSUPPORTED_MEDIA_TYPE": http.StatusUnsupportedMediaType,
	"RATE_LIMITED":           http.StatusTooManyRequests,
	"NICKNAME_NOT_ALLOWED":   http.StatusBadRequest,
	// 30일이 지나야 바꿀 수 있다. 같은 요청을 지금 다시 보내도 결과가 같으므로 409로 둔다.
	"NICKNAME_CHANGE_TOO_SOON": http.StatusConflict,
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("응답 쓰기 실패", "err", err)
	}
}

// writeError는 도메인 오류를 계약서의 오류 모양으로 바꾼다. 그 밖의 오류는 내용을 숨기고 500으로 돌려준다.
func writeError(w http.ResponseWriter, err error) {
	var de *domain.Error
	if errors.As(err, &de) {
		status, ok := statusByCode[de.Code]
		if !ok {
			status = http.StatusBadRequest
		}
		writeJSON(w, status, errorBody{Error: errorDetail{Code: de.Code, Message: de.Message}})
		return
	}
	slog.Error("처리 중 오류", "err", err)
	writeJSON(w, http.StatusInternalServerError, errorBody{Error: errorDetail{Code: "INTERNAL", Message: "서버 오류가 발생했습니다."}})
}
