package handler

import (
	"encoding/json"
	"net/http"
	"time"

	"gildongmu/server/internal/domain"
	"gildongmu/server/internal/usecase"
)

// ProfileHandler는 내 길동무(닉네임과 모습) 입출력만 맡는다. 판단은 usecase에 넘긴다.
type ProfileHandler struct {
	uc *usecase.ProfileUsecase
}

func NewProfileHandler(uc *usecase.ProfileUsecase) *ProfileHandler {
	return &ProfileHandler{uc: uc}
}

type lookBody struct {
	Color int             `json:"color"`
	Item  domain.LookItem `json:"item"`
}

type profileRequest struct {
	Nickname *string   `json:"nickname"`
	Look     *lookBody `json:"look"`
}

// profileResponse는 아직 정하지 않았으면 nickname·look이 null이다.
type profileResponse struct {
	Nickname             *string    `json:"nickname"`
	Look                 *lookBody  `json:"look"`
	NicknameChangedAt    *time.Time `json:"nickname_changed_at"`
	NextNicknameChangeAt *time.Time `json:"next_nickname_change_at"`
}

func toProfileResponse(p *domain.Profile) profileResponse {
	if p == nil {
		return profileResponse{}
	}
	next := p.NextNicknameChangeAt()
	return profileResponse{
		Nickname:             &p.Nickname,
		Look:                 &lookBody{Color: p.LookColor, Item: p.LookItem},
		NicknameChangedAt:    &p.NicknameChangedAt,
		NextNicknameChangeAt: &next,
	}
}

func (h *ProfileHandler) get(w http.ResponseWriter, r *http.Request) {
	p, err := h.uc.Get(r.Context(), deviceIDFrom(r.Context()))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, toProfileResponse(p))
}

func (h *ProfileHandler) update(w http.ResponseWriter, r *http.Request) {
	var req profileRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&req); err != nil {
		writeError(w, domain.Invalid("JSON 본문이 필요합니다."))
		return
	}
	switch {
	case req.Nickname == nil:
		writeError(w, domain.Invalid("nickname 값이 필요합니다."))
		return
	case req.Look == nil:
		writeError(w, domain.Invalid("look 값이 필요합니다."))
		return
	}
	p, err := h.uc.Update(r.Context(), usecase.UpdateProfileInput{
		DeviceID:  deviceIDFrom(r.Context()),
		Nickname:  *req.Nickname,
		LookColor: req.Look.Color,
		LookItem:  req.Look.Item,
	})
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, toProfileResponse(p))
}
