package metrics

import (
	"io"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHandler_ExposesAllMetrics(t *testing.T) {
	// Increment every counter / gauge once so the exposition format
	// surfaces the names.
	RingbufFillRatio.Set(0.42)
	RingbufDropsTotal.WithLabelValues("bpf_ringbuf_full").Inc()
	HTTPPairsCapturedTotal.WithLabelValues("svc", "GET").Inc()
	HTTPPairsDroppedTotal.WithLabelValues("pipelined").Inc()
	CollectorBatchesTotal.WithLabelValues("ok").Inc()
	CollectorBatchPostDurationSeconds.WithLabelValues("200").Observe(0.05)
	CollectorBatchErrorsTotal.WithLabelValues("503").Inc()
	ServiceRegistrationOutcomesTotal.WithLabelValues("success").Inc()
	InformerFreshnessSeconds.WithLabelValues("pod").Set(1.0)
	RedactionReplacementsTotal.WithLabelValues("authorization").Inc()
	RedactionTruncatedBodiesTotal.Inc()
	AttributionUnknownTotal.WithLabelValues("pre_sync").Inc()
	PreflightStatus.WithLabelValues("btf").Set(1)

	srv := httptest.NewServer(Handler())
	defer srv.Close()

	resp, err := srv.Client().Get(srv.URL)
	if err != nil {
		t.Fatalf("GET /metrics: %v", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	out := string(body)

	wantNames := []string{
		"vp_tap_ringbuf_fill_ratio",
		"vp_tap_ringbuf_drops_total",
		"vp_tap_http_pairs_captured_total",
		"vp_tap_http_pairs_dropped_total",
		"vp_tap_collector_batches_total",
		"vp_tap_collector_batch_post_duration_seconds",
		"vp_tap_collector_batch_errors_total",
		"vp_tap_service_registration_outcomes_total",
		"vp_tap_informer_freshness_seconds",
		"vp_tap_redaction_replacements_total",
		"vp_tap_redaction_truncated_bodies_total",
		"vp_tap_attribution_unknown_total",
		"vp_tap_preflight_status",
	}
	for _, name := range wantNames {
		if !strings.Contains(out, name) {
			t.Errorf("metric %q missing from /metrics output", name)
		}
	}
}

func TestRegistry_NotNil(t *testing.T) {
	if Registry() == nil {
		t.Fatal("Registry() returned nil")
	}
}
