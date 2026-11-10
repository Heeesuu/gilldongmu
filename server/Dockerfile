# 길동무 서버 이미지. Cloud Run이 이 파일로 빌드한다(docs/deploy.md: gcloud run deploy --source server).

# 1단계: 정적 바이너리를 만든다. SQLite·PostgreSQL 드라이버가 모두 순수 Go라 CGO가 필요 없다.
FROM golang:1.27.3 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/gildongmu-server ./cmd/server

# 2단계: 셸도 없는 작은 이미지에 바이너리만 둔다. HTTPS 인증서(Claude·Google·Neon 연결용)는 들어 있다.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/gildongmu-server /gildongmu-server
USER nonroot:nonroot
# Cloud Run은 PORT를 넣어 준다. 로컬에서 docker run 할 때의 기본값이다.
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/gildongmu-server"]
