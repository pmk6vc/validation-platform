package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestLoadStatic_RequiredEnvMissing(t *testing.T) {
	resetStaticEnv(t)
	if _, err := LoadStatic(); err == nil {
		t.Fatal("expected error when PLATFORM_URL missing; got nil")
	}
}

func TestLoadStatic_CollectorFallsBackToPlatform(t *testing.T) {
	resetStaticEnv(t)
	t.Setenv("PLATFORM_URL", "https://platform.example.com")
	t.Setenv("API_KEY", "test-jwt")
	t.Setenv("NODE_NAME", "test-node")
	// COLLECTOR_URL deliberately unset

	got, err := LoadStatic()
	if err != nil {
		t.Fatalf("LoadStatic: %v", err)
	}
	if got.CollectorURL != got.PlatformURL {
		t.Errorf("CollectorURL = %q; expected fallback to PlatformURL %q", got.CollectorURL, got.PlatformURL)
	}
}

func TestLoadStatic_AllSet(t *testing.T) {
	resetStaticEnv(t)
	t.Setenv("PLATFORM_URL", "https://platform.example.com")
	t.Setenv("COLLECTOR_URL", "https://collector.example.com")
	t.Setenv("API_KEY", "test-jwt")
	t.Setenv("NODE_NAME", "test-node")

	got, err := LoadStatic()
	if err != nil {
		t.Fatalf("LoadStatic: %v", err)
	}
	if got.PlatformURL != "https://platform.example.com" {
		t.Errorf("PlatformURL = %q", got.PlatformURL)
	}
	if got.CollectorURL != "https://collector.example.com" {
		t.Errorf("CollectorURL = %q", got.CollectorURL)
	}
	if got.APIKey != "test-jwt" {
		t.Errorf("APIKey = %q", got.APIKey)
	}
	if got.NodeName != "test-node" {
		t.Errorf("NodeName = %q", got.NodeName)
	}
}

func TestLoadBehavioral_MissingFile(t *testing.T) {
	bd, err := LoadBehavioral(filepath.Join(t.TempDir(), "nonexistent.yaml"))
	if err != nil {
		t.Fatalf("LoadBehavioral missing-file: %v", err)
	}
	if bd.MaxBodyBytes != 1024*1024 {
		t.Errorf("MaxBodyBytes default = %d; expected 1 MiB", bd.MaxBodyBytes)
	}
	if bd.ReassemblyIdleTTL != 30*time.Second {
		t.Errorf("ReassemblyIdleTTL default = %v; expected 30s", bd.ReassemblyIdleTTL)
	}
	if bd.MetricsPort != 9090 {
		t.Errorf("MetricsPort default = %d; expected 9090", bd.MetricsPort)
	}
}

func TestLoadBehavioral_YAMLOverrides(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte(`
maxBodyBytes: 2097152
maxHeaderBytes: 32768
reassemblyIdleTTL: 60s
attributionQuarantineSeconds: 10
maxConcurrentConnections: 5000
metricsPort: 9091
`), 0o644); err != nil {
		t.Fatal(err)
	}

	bd, err := LoadBehavioral(path)
	if err != nil {
		t.Fatalf("LoadBehavioral: %v", err)
	}
	if bd.MaxBodyBytes != 2*1024*1024 {
		t.Errorf("MaxBodyBytes = %d; expected 2 MiB", bd.MaxBodyBytes)
	}
	if bd.ReassemblyIdleTTL != 60*time.Second {
		t.Errorf("ReassemblyIdleTTL = %v; expected 60s", bd.ReassemblyIdleTTL)
	}
	if bd.MaxConcurrentConnections != 5000 {
		t.Errorf("MaxConcurrentConnections = %d", bd.MaxConcurrentConnections)
	}
	if bd.MetricsPort != 9091 {
		t.Errorf("MetricsPort = %d", bd.MetricsPort)
	}
}

func TestLoadBehavioral_DefendsAgainstNegativeYAML(t *testing.T) {
	// User passes -1 for everything — fallback to defaults rather than
	// allow nonsensical state.
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte(`
maxBodyBytes: -1
maxHeaderBytes: -1
reassemblyIdleTTL: -1s
attributionQuarantineSeconds: -1
maxConcurrentConnections: -1
metricsPort: -1
`), 0o644); err != nil {
		t.Fatal(err)
	}

	bd, err := LoadBehavioral(path)
	if err != nil {
		t.Fatalf("LoadBehavioral: %v", err)
	}
	if bd.MaxBodyBytes <= 0 {
		t.Errorf("MaxBodyBytes negative override leaked: %d", bd.MaxBodyBytes)
	}
	if bd.MetricsPort != 9090 {
		t.Errorf("MetricsPort negative override leaked: %d", bd.MetricsPort)
	}
}

func TestDefaultDynamicConfig(t *testing.T) {
	dc := DefaultDynamicConfig()
	if dc == nil {
		t.Fatal("DefaultDynamicConfig returned nil")
	}
	if dc.SamplingRate != 1.0 {
		t.Errorf("default SamplingRate = %v; expected 1.0", dc.SamplingRate)
	}
	if dc.TargetServices == nil {
		t.Error("default TargetServices nil — should be empty map")
	}
}

// resetStaticEnv clears every env var LoadStatic reads so per-test
// t.Setenv calls produce a known starting state.
func resetStaticEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{"PLATFORM_URL", "COLLECTOR_URL", "API_KEY", "NODE_NAME"} {
		t.Setenv(k, "")
		os.Unsetenv(k)
	}
}
