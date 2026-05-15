// Package logging configures vp-tap's structured JSON logger built on
// stdlib log/slog. Every package below cmd/vp-tap should slog.{Info,Warn,Error}
// rather than the legacy log.* — Cloud Logging recognizes JSON output
// natively and the structured fields land in queryable columns.
//
// CONTEXT.md D-03 + RESEARCH.md don't pick a specific logger; stdlib
// log/slog with the JSON handler is the obvious Go-side choice — zero
// extra deps and matches the existing logback+logstash pattern in
// platform/collector for consistent log shape across the bilingual stack.

package logging

import (
	"log/slog"
	"os"
	"strings"
)

// Setup installs a JSON-handler default logger at the given level.
// Call once from main; idempotent (replaces any previous default).
func Setup(level slog.Level) {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level:     level,
		AddSource: false,
	})
	slog.SetDefault(slog.New(handler))
}

// MustLevel maps a string log level (case-insensitive) to slog.Level.
// Unknown values fall back to LevelInfo — vp-tap should not refuse to
// start because of a typo in LOG_LEVEL.
func MustLevel(s string) slog.Level {
	switch strings.ToUpper(strings.TrimSpace(s)) {
	case "DEBUG":
		return slog.LevelDebug
	case "WARN", "WARNING":
		return slog.LevelWarn
	case "ERROR":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}
