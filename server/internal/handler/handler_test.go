package handler_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"testing"

	"gildongmu/server/internal/handler"
	ts "gildongmu/server/internal/testsupport"
)

const (
	author   = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	listener = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
)

type apiClient struct {
	t   *testing.T
	srv *httptest.Server
}

func newAPI(t *testing.T) (*apiClient, *ts.Env) {
	env := ts.NewEnv(t)
	srv := httptest.NewServer(handler.NewRouter(handler.NewMessageHandler(env.Messages)))
	t.Cleanup(srv.Close)
	return &apiClient{t: t, srv: srv}, env
}

func (c *apiClient) do(method, path, device string, body io.Reader, contentType string) (*http.Response, []byte) {
	c.t.Helper()
	req, err := http.NewRequest(method, c.srv.URL+path, body)
	if err != nil {
		c.t.Fatal(err)
	}
	if device != "" {
		req.Header.Set("X-Device-Id", device)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func uploadBody(t *testing.T, audioType, meta string) (*bytes.Buffer, string) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := make(textproto.MIMEHeader)
	h.Set("Content-Disposition", `form-data; name="audio"; filename="m.ogg"`)
	h.Set("Content-Type", audioType)
	part, _ := w.CreatePart(h)
	_, _ = part.Write([]byte("OggS-fake-opus"))
	_ = w.WriteField("meta", meta)
	_ = w.Close()
	return &buf, w.FormDataContentType()
}

const validMeta = `{"recorded_at":"2026-11-07T23:50:00Z","lat":37.5672,"lon":126.979,"heading":45,"speed_kmh":32.5,"duration_ms":6400}`

func errorCode(t *testing.T, data []byte) string {
	t.Helper()
	var body struct {
		Error struct{ Code, Message string } `json:"error"`
	}
	if err := json.Unmarshal(data, &body); err != nil {
		t.Fatalf("오류 응답이 JSON이 아닙니다: %s", data)
	}
	if body.Error.Message == "" {
		t.Errorf("오류 메시지가 비어 있습니다")
	}
	return body.Error.Code
}

func TestHealthAndDeviceID(t *testing.T) {
	api, _ := newAPI(t)
	if resp, _ := api.do("GET", "/v1/health", "", nil, ""); resp.StatusCode != http.StatusOK {
		t.Errorf("health는 기기 ID 없이 200이어야 합니다: %d", resp.StatusCode)
	}
	resp, data := api.do("GET", "/v1/me/messages", "", nil, "")
	if resp.StatusCode != http.StatusUnauthorized || errorCode(t, data) != "MISSING_DEVICE_ID" {
		t.Errorf("기기 ID가 없으면 401 MISSING_DEVICE_ID여야 합니다: %d %s", resp.StatusCode, data)
	}
}

func TestUploadRejectsWrongInput(t *testing.T) {
	api, _ := newAPI(t)

	body, ct := uploadBody(t, "audio/mp4", validMeta)
	if resp, data := api.do("POST", "/v1/messages", author, body, ct); resp.StatusCode != http.StatusUnsupportedMediaType || errorCode(t, data) != "UNSUPPORTED_MEDIA_TYPE" {
		t.Errorf("ogg가 아니면 415여야 합니다: %d %s", resp.StatusCode, data)
	}
	body, ct = uploadBody(t, "audio/ogg", `{"lat":37.5}`)
	if resp, data := api.do("POST", "/v1/messages", author, body, ct); resp.StatusCode != http.StatusBadRequest || errorCode(t, data) != "INVALID_ARGUMENT" {
		t.Errorf("필수 값이 빠지면 400이어야 합니다: %d %s", resp.StatusCode, data)
	}
	if resp, _ := api.do("GET", "/v1/messages/nearby?lat=37.5&lon=127", listener, nil, ""); resp.StatusCode != http.StatusBadRequest {
		t.Errorf("heading이 없으면 400이어야 합니다: %d", resp.StatusCode)
	}
}

// 올릴 때 heading은 빠져도 되고, 찾기 응답에는 null로 나간다.
func TestUploadWithoutHeading(t *testing.T) {
	api, env := newAPI(t)

	body, ct := uploadBody(t, "audio/ogg", `{"recorded_at":"2026-11-07T23:50:00Z","lat":37.5672,"lon":126.979,"speed_kmh":0,"duration_ms":6400}`)
	resp, data := api.do("POST", "/v1/messages", author, body, ct)
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("heading 없이도 202여야 합니다: %d %s", resp.StatusCode, data)
	}
	var uploaded struct{ ID string }
	_ = json.Unmarshal(data, &uploaded)
	if err := env.Moderation.Moderate(context.Background(), uploaded.ID); err != nil {
		t.Fatal(err)
	}

	near := fmt.Sprintf("/v1/messages/nearby?lat=%v&lon=%v&heading=225&speed_kmh=40", ts.PassedLat, ts.PassedLon)
	resp, data = api.do("GET", near, listener, nil, "")
	var nearby struct {
		Messages []struct {
			Heading *float64 `json:"heading"`
		} `json:"messages"`
	}
	_ = json.Unmarshal(data, &nearby)
	if resp.StatusCode != http.StatusOK || len(nearby.Messages) != 1 || nearby.Messages[0].Heading != nil {
		t.Errorf("반대 방향에서도 보이고 heading은 null이어야 합니다: %d %s", resp.StatusCode, data)
	}
}

// 올리기 → 검수 → 찾기 → 음성 → 고마워요 → 들은 결과 → 내 목록 → 삭제를 계약서 모양대로 확인한다.
func TestMessageLifecycle(t *testing.T) {
	api, env := newAPI(t)

	body, ct := uploadBody(t, "audio/ogg", validMeta)
	resp, data := api.do("POST", "/v1/messages", author, body, ct)
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("올리기는 202여야 합니다: %d %s", resp.StatusCode, data)
	}
	var uploaded struct{ ID, Stage string }
	_ = json.Unmarshal(data, &uploaded)
	if uploaded.Stage != "seed" {
		t.Errorf("올린 직후 stage는 seed여야 합니다: %s", data)
	}
	if err := env.Moderation.Moderate(context.Background(), uploaded.ID); err != nil {
		t.Fatal(err)
	}

	near := fmt.Sprintf("/v1/messages/nearby?lat=%v&lon=%v&heading=45&speed_kmh=40", ts.BehindLat, ts.BehindLon)
	resp, data = api.do("GET", near, listener, nil, "")
	var nearby struct {
		TotalAhead int `json:"total_ahead"`
		Messages   []struct {
			ID       string `json:"id"`
			Stage    string `json:"stage"`
			EtaSec   *int   `json:"eta_sec"`
			AudioURL string `json:"audio_url"`
		} `json:"messages"`
		NextPollSec int `json:"next_poll_sec"`
	}
	_ = json.Unmarshal(data, &nearby)
	if resp.StatusCode != http.StatusOK || nearby.TotalAhead != 1 || nearby.Messages[0].Stage != "sprout" || nearby.NextPollSec == 0 {
		t.Fatalf("검수 뒤 같은 방향에서는 새싹으로 보여야 합니다: %d %s", resp.StatusCode, data)
	}

	resp, data = api.do("GET", nearby.Messages[0].AudioURL, listener, nil, "")
	if resp.StatusCode != http.StatusOK || resp.Header.Get("Content-Type") != "audio/ogg" || string(data) != "OggS-fake-opus" {
		t.Errorf("음성을 그대로 내려줘야 합니다: %d %q", resp.StatusCode, resp.Header.Get("Content-Type"))
	}

	resp, data = api.do("POST", "/v1/messages/"+uploaded.ID+"/thanks", listener, nil, "")
	if resp.StatusCode != http.StatusOK || !bytes.Contains(data, []byte(`"thanks_count":1`)) {
		t.Errorf("고마워요는 200이고 1개여야 합니다: %d %s", resp.StatusCode, data)
	}

	resp, data = api.do("POST", "/v1/messages/"+uploaded.ID+"/listen", listener, bytes.NewBufferString(`{"result":"completed","played_ms":6400}`), "application/json")
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("들은 결과는 204여야 합니다: %d %s", resp.StatusCode, data)
	}

	resp, data = api.do("GET", "/v1/me/messages", author, nil, "")
	if resp.StatusCode != http.StatusOK || !bytes.Contains(data, []byte(`"listen_count":1`)) || !bytes.Contains(data, []byte(`"review":"clean"`)) {
		t.Errorf("내 목록에 반응 수와 검수 결과가 보여야 합니다: %s", data)
	}

	if resp, data = api.do("DELETE", "/v1/messages/"+uploaded.ID, listener, nil, ""); resp.StatusCode != http.StatusForbidden || errorCode(t, data) != "NOT_OWNER" {
		t.Errorf("남이 지우면 403이어야 합니다: %d %s", resp.StatusCode, data)
	}
	if resp, _ = api.do("DELETE", "/v1/messages/"+uploaded.ID, author, nil, ""); resp.StatusCode != http.StatusNoContent {
		t.Errorf("남긴 사람은 지울 수 있어야 합니다: %d", resp.StatusCode)
	}
	if resp, _ = api.do("GET", "/v1/messages/"+uploaded.ID+"/audio", author, nil, ""); resp.StatusCode != http.StatusNotFound {
		t.Errorf("지운 뒤 음성은 404여야 합니다: %d", resp.StatusCode)
	}
}
