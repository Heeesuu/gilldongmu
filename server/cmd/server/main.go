package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/google/uuid"

	"gildongmu/server/internal/config"
	"gildongmu/server/internal/handler"
	"gildongmu/server/internal/moderation"
	"gildongmu/server/internal/repository"
	"gildongmu/server/internal/usecase"
)

func main() {
	cfg := config.Load()

	db, dbName, err := repository.Open(cfg.DatabaseURL, cfg.SQLitePath)
	if err != nil {
		slog.Error("DB 연결 실패", "err", err)
		os.Exit(1)
	}
	if err := repository.Migrate(db); err != nil {
		slog.Error("마이그레이션 실패", "err", err)
		os.Exit(1)
	}

	repo := repository.NewMessageRepository(db)
	uow := repository.NewUnitOfWork(db)

	var judge usecase.Judge = moderation.UnconfiguredJudge{}
	if cfg.AnthropicAPIKey != "" {
		judge = moderation.NewClaudeJudge(cfg.AnthropicAPIKey, cfg.JudgeModel)
	}
	var stt usecase.SpeechToText = moderation.UnconfiguredSTT{}
	switch cfg.STTProvider {
	case "":
	case "google":
		googleSTT, err := moderation.NewGoogleSTT(context.Background(), "ko-KR")
		if err != nil {
			// 설정했는데 붙지 않으면 바로 멈춘다. 조용히 모든 한마디가 보류되는 것보다 배포 단계에서 아는 게 낫다.
			slog.Error("STT 연결 실패", "provider", cfg.STTProvider, "err", err)
			os.Exit(1)
		}
		defer googleSTT.Close()
		stt = googleSTT
	default:
		slog.Error("알 수 없는 STT_PROVIDER", "provider", cfg.STTProvider)
		os.Exit(1)
	}
	moderationUC := usecase.NewModerationUsecase(repo, stt, judge, moderation.NewDictionary(), cfg.DevAutoApprove)
	worker := usecase.NewModerationWorker(moderationUC, repo)

	now := func() time.Time { return time.Now().UTC() }
	messageUC := usecase.NewMessageUsecase(repo, uow, worker, now, uuid.NewString)
	profileUC := usecase.NewProfileUsecase(repository.NewProfileRepository(db), moderation.NewDictionary(), now)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go worker.Run(ctx)

	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler.NewRouter(handler.NewMessageHandler(messageUC), handler.NewProfileHandler(profileUC)),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	slog.Info("서버 시작", "addr", cfg.Addr, "db", dbName, "stt", cfg.STTProvider, "llm", cfg.AnthropicAPIKey != "", "dev_auto_approve", cfg.DevAutoApprove)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("서버 종료", "err", err)
		os.Exit(1)
	}
}
