package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/joho/godotenv"

	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/admin"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/analytics"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/api"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/clock"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/forecast"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/llm"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/prompts"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/repo"
	"github.com/KeeGooRoomiE/sber500-companion/backend/internal/scheduler"
)

func main() {
	_ = godotenv.Load()

	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
	slog.Info("config", "tz", clock.Location().String(), "llm_model", os.Getenv("LLM_MODEL"))

	if os.Getenv("DATABASE_URL") == "" {
		slog.Error("DATABASE_URL is not set")
		os.Exit(1)
	}

	db, err := pgxpool.New(context.Background(), os.Getenv("DATABASE_URL"))
	if err != nil {
		slog.Error("db connect failed", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	devUserIDs := splitCSV(os.Getenv("DEV_USER_IDS"))
	callLog := analytics.NewLogger(db, devUserIDs)
	morningRepo := repo.NewMorningRepo(db)
	llmClient := llm.NewClient()
	promptStore := prompts.NewStore(db, map[string]string{prompts.MorningSystem: llm.DefaultMorningSystem})
	generator := forecast.NewGenerator(db, repo.NewDailyRepo(db), repo.NewCheckInRepo(db), morningRepo, llmClient, callLog, promptStore)

	r := chi.NewRouter()
	r.Use(middleware.RealIP)
	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)

	// 200 only when the database answers — uptime checks should see a broken DB as down.
	r.Get("/health", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := db.Ping(ctx); err != nil {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	api.Mount(r, api.Deps{DB: db, CallLog: callLog, Generator: generator, DevUserIDs: devUserIDs})

	// Background morning-message scheduler.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sched := scheduler.New(morningRepo, generator)
	sched.Start(ctx)
	go heartbeat(ctx, repo.NewActivityRepo(db))
	admin.New(db, promptStore, llmClient, callLog).Start(ctx)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// Production sets LISTEN_ADDR=127.0.0.1:8080 so the API is reachable only through Caddy.
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":" + port
	}

	srv := &http.Server{
		Addr:         addr,
		Handler:      r,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	go func() {
		slog.Info("server started", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			slog.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	cancel()
	shutCtx, shutCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutCancel()
	_ = srv.Shutdown(shutCtx)
	slog.Info("server stopped")
}

// heartbeat records one row per minute; /api/v1/metrics turns it into uptime_24h.
func heartbeat(ctx context.Context, activity *repo.ActivityRepo) {
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		if err := activity.Heartbeat(ctx, time.Now()); err != nil {
			slog.Warn("heartbeat failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

func splitCSV(s string) []string {
	if s == "" {
		return nil
	}
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
