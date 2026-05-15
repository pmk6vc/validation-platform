// Package metrics declares vp-tap's Prometheus metrics registry and every
// named metric per RESEARCH.md §7. The /metrics endpoint served on
// :MetricsPort (default 9090) returns the standard Prometheus exposition
// format; Cloud Monitoring scrapes via prometheus.io/scrape annotation
// (Phase 12 wires the scrape integration).
//
// Metric naming follows Prometheus conventions: vp_tap_<subsystem>_<unit>{...labels}.
// Counters end in _total. Histograms expose _bucket / _count / _sum suffixes.
//
// Every metric used by other packages is exported as a package-level
// variable; callers do `metrics.RingbufDropsTotal.WithLabelValues("bpf_ringbuf_full").Inc()`.

package metrics

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// registry is package-private; expose only Handler() and Registry() so
// callers don't accidentally register metrics on the default global
// registry (which would conflict with the auto-registered Go runtime
// metrics from client_golang).
var registry = prometheus.NewRegistry()

// Registry returns the underlying prometheus.Registerer for tests and
// any caller that needs to register its own metrics.
func Registry() *prometheus.Registry {
	return registry
}

// Handler returns the /metrics HTTP handler.
func Handler() http.Handler {
	return promhttp.HandlerFor(registry, promhttp.HandlerOpts{
		EnableOpenMetrics: false, // stick to text exposition format
	})
}

// --- Ringbuf -----------------------------------------------------------------

// RingbufFillRatio — current used/capacity ratio of the BPF ringbuf,
// sampled by a background goroutine. 0.0 ≤ value ≤ 1.0.
var RingbufFillRatio = prometheus.NewGauge(prometheus.GaugeOpts{
	Name: "vp_tap_ringbuf_fill_ratio",
	Help: "Current used/capacity ratio of the BPF ringbuf (0.0–1.0).",
})

// RingbufDropsTotal — events lost between kernel and userspace.
// Reasons: bpf_ringbuf_full (kernel-side drop), decode_error (userspace
// decode of a malformed event).
var RingbufDropsTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_ringbuf_drops_total",
		Help: "Events lost between BPF ringbuf and userspace, by reason.",
	},
	[]string{"reason"},
)

// --- Reassembly + capture ---------------------------------------------------

// HTTPPairsCapturedTotal — successfully paired (request, response).
var HTTPPairsCapturedTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_http_pairs_captured_total",
		Help: "Successfully paired HTTP request/response pairs, by service and method.",
	},
	[]string{"service", "method"},
)

// HTTPPairsDroppedTotal — reassembly-side drops.
// Reasons: pipelined (HTTP/1.1 pipelining detected), aged_out (idle
// buffer evicted), truncated (body exceeded MaxBodyBytes).
var HTTPPairsDroppedTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_http_pairs_dropped_total",
		Help: "Reassembly-side drops, by reason.",
	},
	[]string{"reason"},
)

// --- Collector POST ---------------------------------------------------------

// CollectorBatchesTotal — outcome of each batch POST.
// Statuses: ok, retry, permanent_fail.
var CollectorBatchesTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_collector_batches_total",
		Help: "Outcome of batch POSTs to the collector, by status.",
	},
	[]string{"status"},
)

// CollectorBatchPostDurationSeconds — wall-clock latency per POST.
var CollectorBatchPostDurationSeconds = prometheus.NewHistogramVec(
	prometheus.HistogramOpts{
		Name:    "vp_tap_collector_batch_post_duration_seconds",
		Help:    "Wall-clock duration of batch POSTs, by HTTP status code.",
		Buckets: prometheus.DefBuckets,
	},
	[]string{"status_code"},
)

// CollectorBatchErrorsTotal — HTTP errors from the collector.
var CollectorBatchErrorsTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_collector_batch_errors_total",
		Help: "HTTP error responses from the collector, by status code.",
	},
	[]string{"status_code"},
)

// --- Service registration ---------------------------------------------------

// ServiceRegistrationOutcomesTotal — RegistrationOutcome variants per
// CONTEXT.md D-04 / RESEARCH §1. Outcomes: success, permanent_rejection,
// transient_failure.
var ServiceRegistrationOutcomesTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_service_registration_outcomes_total",
		Help: "Outcome of POST /api/services attempts, by outcome class.",
	},
	[]string{"outcome"},
)

// --- Informer freshness -----------------------------------------------------

// InformerFreshnessSeconds — seconds since last informer event by
// informer ({pod, service}). High values mean attribution / target lists
// are stale.
var InformerFreshnessSeconds = prometheus.NewGaugeVec(
	prometheus.GaugeOpts{
		Name: "vp_tap_informer_freshness_seconds",
		Help: "Seconds since the last event from each K8s informer.",
	},
	[]string{"informer"},
)

// --- Redaction -------------------------------------------------------------

// RedactionReplacementsTotal — count of redactions applied, by typed
// placeholder kind (authorization, cookie, set-cookie, x-api-key, jwt,
// pan, sk_token, pk_token, custom).
var RedactionReplacementsTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_redaction_replacements_total",
		Help: "Redactions applied at the agent, by typed placeholder kind.",
	},
	[]string{"type"},
)

// RedactionTruncatedBodiesTotal — bodies skipped for body-redaction
// because they were truncated upstream (reassembly cap exceeded).
var RedactionTruncatedBodiesTotal = prometheus.NewCounter(
	prometheus.CounterOpts{
		Name: "vp_tap_redaction_truncated_bodies_total",
		Help: "Bodies skipped for body-redaction because they were truncated upstream.",
	},
)

// --- Attribution -----------------------------------------------------------

// AttributionUnknownTotal — events emitted with pod=? by reason.
// Reasons: pre_sync, quarantined, host_process.
var AttributionUnknownTotal = prometheus.NewCounterVec(
	prometheus.CounterOpts{
		Name: "vp_tap_attribution_unknown_total",
		Help: "Captured events that could not be attributed to a pod, by reason.",
	},
	[]string{"reason"},
)

// --- Pre-flight ------------------------------------------------------------

// PreflightStatus — 1=pass, 0=fail for each pre-flight check.
// Checks: btf, loopback, grpc (Phase 2). The dashboard onboarding surface
// reads these values per RESEARCH §8.
var PreflightStatus = prometheus.NewGaugeVec(
	prometheus.GaugeOpts{
		Name: "vp_tap_preflight_status",
		Help: "Pre-flight check status (1=pass, 0=fail), by check name.",
	},
	[]string{"check"},
)

// init registers every declared metric on the package registry. Done at
// package import time so callers don't have to remember to call Register().
func init() {
	registry.MustRegister(
		RingbufFillRatio,
		RingbufDropsTotal,
		HTTPPairsCapturedTotal,
		HTTPPairsDroppedTotal,
		CollectorBatchesTotal,
		CollectorBatchPostDurationSeconds,
		CollectorBatchErrorsTotal,
		ServiceRegistrationOutcomesTotal,
		InformerFreshnessSeconds,
		RedactionReplacementsTotal,
		RedactionTruncatedBodiesTotal,
		AttributionUnknownTotal,
		PreflightStatus,
	)
}
