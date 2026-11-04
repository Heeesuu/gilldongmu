package handler

import "net/http"

// NewRouter는 API 계약서(docs/api.md)의 경로를 연결한다.
// Go 1.22부터 표준 라이브러리가 "메서드 + 경로 변수" 라우팅을 지원해서 프레임워크 없이 쓴다.
func NewRouter(h *MessageHandler, p *ProfileHandler) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", h.health)
	mux.Handle("POST /v1/messages", requireDevice(h.upload))
	mux.Handle("GET /v1/messages/nearby", requireDevice(h.nearby))
	mux.Handle("GET /v1/messages/{id}/audio", requireDevice(h.audio))
	mux.Handle("POST /v1/messages/{id}/thanks", requireDevice(h.thanks))
	mux.Handle("POST /v1/messages/{id}/listen", requireDevice(h.listen))
	mux.Handle("GET /v1/me/messages", requireDevice(h.myMessages))
	mux.Handle("DELETE /v1/messages/{id}", requireDevice(h.delete))
	mux.Handle("GET /v1/me/profile", requireDevice(p.get))
	mux.Handle("PUT /v1/me/profile", requireDevice(p.update))
	return recoverer(logRequests(mux))
}
