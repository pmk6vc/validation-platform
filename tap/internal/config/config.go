// Package config holds vp-tap's static + dynamic configuration types and
// the loader for each:
//
//   - StaticConfig — read once at startup from env vars (PLATFORM_URL,
//     COLLECTOR_URL, API_KEY, NODE_NAME). Required for the agent to start.
//   - BehavioralDefaults — read once at startup from a ConfigMap-mounted
//     YAML file (default: /etc/vp-tap/config.yaml). Optional; safe
//     defaults apply when the file is missing.
//   - DynamicConfig — polled from platform's GET /api/agent/config every
//     configPollIntervalMs; held in main as an atomic.Pointer so all
//     goroutines see a consistent snapshot without locks.
//
// Per CONTEXT.md D-02: ConfigMap YAML for behavioral defaults, Secret for
// JWT (via env API_KEY), env vars for URLs. RESEARCH §6 documents the
// keys here.

package config

import (
	"fmt"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

// StaticConfig holds the env-var configuration loaded once at startup.
type StaticConfig struct {
	PlatformURL  string
	CollectorURL string
	APIKey       string
	NodeName     string
}

// LoadStatic reads required env vars and returns a *StaticConfig.
// PLATFORM_URL, API_KEY, and NODE_NAME are required; missing values
// return an error. COLLECTOR_URL falls back to PLATFORM_URL when unset.
func LoadStatic() (*StaticConfig, error) {
	platform := os.Getenv("PLATFORM_URL")
	if platform == "" {
		return nil, fmt.Errorf("PLATFORM_URL env var is required")
	}
	apiKey := os.Getenv("API_KEY")
	if apiKey == "" {
		return nil, fmt.Errorf("API_KEY env var is required (mount via secretKeyRef: platform-api-key/jwt-token)")
	}
	nodeName := os.Getenv("NODE_NAME")
	if nodeName == "" {
		return nil, fmt.Errorf("NODE_NAME env var is required (set via downward API: spec.nodeName)")
	}
	collector := os.Getenv("COLLECTOR_URL")
	if collector == "" {
		collector = platform
	}
	return &StaticConfig{
		PlatformURL:  platform,
		CollectorURL: collector,
		APIKey:       apiKey,
		NodeName:     nodeName,
	}, nil
}

// BehavioralDefaults holds tunable behavior loaded from the ConfigMap YAML.
// All fields have safe defaults; the file is optional.
type BehavioralDefaults struct {
	MaxBodyBytes                 int           `yaml:"maxBodyBytes"`
	MaxHeaderBytes               int           `yaml:"maxHeaderBytes"`
	ReassemblyIdleTTL            time.Duration `yaml:"reassemblyIdleTTL"`
	AttributionQuarantineSeconds int           `yaml:"attributionQuarantineSeconds"`
	MaxConcurrentConnections     int           `yaml:"maxConcurrentConnections"`
	MetricsPort                  int           `yaml:"metricsPort"`
}

// defaultBehavioral returns the documented defaults from RESEARCH §3.
func defaultBehavioral() BehavioralDefaults {
	return BehavioralDefaults{
		MaxBodyBytes:                 1024 * 1024, // 1 MiB
		MaxHeaderBytes:               64 * 1024,   // 64 KiB
		ReassemblyIdleTTL:            30 * time.Second,
		AttributionQuarantineSeconds: 5,
		MaxConcurrentConnections:     10_000,
		MetricsPort:                  9090,
	}
}

// LoadBehavioral reads ConfigMap-mounted YAML at the given path and
// returns BehavioralDefaults. Missing file → defaults (with no error).
// Malformed YAML → error.
func LoadBehavioral(path string) (*BehavioralDefaults, error) {
	defaults := defaultBehavioral()
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return &defaults, nil
		}
		return nil, fmt.Errorf("reading %s: %w", path, err)
	}
	parsed := defaults // start from defaults so missing keys keep them
	if err := yaml.Unmarshal(data, &parsed); err != nil {
		return nil, fmt.Errorf("parsing %s: %w", path, err)
	}
	// Apply defensive minimums — if YAML sets nonsense, fall back.
	if parsed.MaxBodyBytes <= 0 {
		parsed.MaxBodyBytes = defaults.MaxBodyBytes
	}
	if parsed.MaxHeaderBytes <= 0 {
		parsed.MaxHeaderBytes = defaults.MaxHeaderBytes
	}
	if parsed.ReassemblyIdleTTL <= 0 {
		parsed.ReassemblyIdleTTL = defaults.ReassemblyIdleTTL
	}
	if parsed.AttributionQuarantineSeconds <= 0 {
		parsed.AttributionQuarantineSeconds = defaults.AttributionQuarantineSeconds
	}
	if parsed.MaxConcurrentConnections <= 0 {
		parsed.MaxConcurrentConnections = defaults.MaxConcurrentConnections
	}
	if parsed.MetricsPort <= 0 {
		parsed.MetricsPort = defaults.MetricsPort
	}
	return &parsed, nil
}

// DynamicConfig mirrors platform's AgentConfigResponse wire shape (which
// itself mirrors the Kotlin agent's existing DTO byte-for-byte plus the
// new redactionSalt + extraRedactedHeaders + extraBodyRedactionPatterns
// fields landed in Plan 01-03). Held as atomic.Pointer in main; all
// goroutines read by Load() to get a consistent snapshot.
type DynamicConfig struct {
	TargetServices             map[string]string
	SamplingRate               float64
	BatchSize                  int
	CaptureIntervalMs          int64
	ConfigPollIntervalMs       int64
	DiscoveryIntervalMs        int64
	NamespaceFilters           []string
	RedactionSalt              string
	ExtraRedactedHeaders       []string
	ExtraBodyRedactionPatterns []string
}

// DefaultDynamicConfig returns a zeroed-but-sane DynamicConfig — used
// before the first /api/agent/config poll completes so the pipeline has
// something to read.
func DefaultDynamicConfig() *DynamicConfig {
	return &DynamicConfig{
		TargetServices:             map[string]string{},
		SamplingRate:               1.0,
		BatchSize:                  100,
		CaptureIntervalMs:          5000,
		ConfigPollIntervalMs:       30000,
		DiscoveryIntervalMs:        60000,
		NamespaceFilters:           []string{},
		RedactionSalt:              "",
		ExtraRedactedHeaders:       []string{},
		ExtraBodyRedactionPatterns: []string{},
	}
}
