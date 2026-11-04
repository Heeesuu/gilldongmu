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
	moderationUC := usecase.NewModerationUsecase(repo, moderation.UnconfiguredSTT{}, judge, moderation.NewDictionary(), cfg.DevAutoApprove)
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

	slog.Info("서버 시작", "addr", cfg.Addr, "db", dbName, "llm", cfg.AnthropicAPIKey != "", "dev_auto_approve", cfg.DevAutoApprove)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("서버 종료", "err", err)
		os.Exit(1)
	}
}
