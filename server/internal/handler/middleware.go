package handler

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"

	"gildongmu/server/internal/domain"
)

type deviceIDKey struct{}

// requireDevice는 X-Device-Id(UUID)를 확인해 요청 문맥에 넣는다. 로그인 대신 기기를 구분한다.
func requireDevice(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("X-Device-Id")
		parsed, err := uuid.Parse(id)
		if err != nil {
			writeError(w, domain.ErrNoDeviceID)
			return
		}
		ctx := context.WithValue(r.Context(), deviceIDKey{}, parsed.String())
		next(w, r.WithContext(ctx))
	})
}

func deviceIDFrom(ctx context.Context) string {
	id, _ := ctx.Value(deviceIDKey{}).(string)
	return id
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

// logRequests는 요청마다 방법·경로·상태·걸린 시간을 남긴다.
func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		slog.Info("요청", "method", r.Method, "path", r.URL.Path, "status", rec.status, "took", time.Since(start).String())
	})
}

// recoverer는 처리 중 패닉이 나도 서버가 죽지 않고 500을 돌려주게 한다.
func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				slog.Error("패닉 복구", "panic", v, "path", r.URL.Path)
				writeJSON(w, http.StatusInternalServerError, errorBody{Error: errorDetail{Code: "INTERNAL", Message: "서버 오류가 발생했습니다."}})
			}
		}()
		next.ServeHTTP(w, r)
	})
}
