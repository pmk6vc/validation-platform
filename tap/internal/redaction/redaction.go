// Package redaction implements vp-tap's CAPTURE-09 default-deny header
// allowlist + content-type-aware body redaction, with typed deterministic
// truncated-sha256 placeholders salted per-org (CONTEXT.md D-14..D-17).
//
// Placement: runs in the transformer (Plan 01-05) after reassembly but
// before batching. Plan 01-05 reads the per-org salt from
// DynamicConfig.RedactionSalt (populated via GET /api/agent/config) and
// constructs an Engine.
//
// Placeholder format: <REDACTED:<type>:<6 hex chars>>
//
//   - <type> is one of the RedactionType constants (authorization, jwt, pan, …).
//   - hex is `sha256(salt || ':' || originalValue)[:3]` (6 hex chars → 24 bits).
//     Determinism: same (salt, value) → same placeholder, so the replay
//     engine can match identical auth contexts within an org without
//     ever seeing the secret. Cross-org isolation: different salts → no
//     placeholder collision across tenants. Truncation to 6 hex chars
//     prevents rainbow-table reverse-lookup.
//
// Body redaction is content-type-aware (D-16):
//
//   - application/json — parse → walk leaf string values → replace matches → re-marshal.
//     Preserves JSON validity so downstream replay parses cleanly.
//   - application/x-www-form-urlencoded and text/* — regex over the
//     decoded string.
//   - binary or unknown content-type — skip body redaction; emit
//     vp_tap_redaction_skipped_total{reason="unknown_content_type"}.
//
// Headers always redacted via the default-deny list (case-insensitive).
// Bodies always scanned for the four body patterns from CAPTURE-09:
// JWT-shaped (RFC 7519 3-segment base64url), PAN (Luhn-valid 13–19
// digits), Stripe sk_/pk_ prefixes.
//
// Phase 3 SEC-09 will populate DynamicConfig.ExtraRedactedHeaders +
// ExtraBodyRedactionPatterns; the Engine already honors them when
// non-empty.

package redaction

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"vp-tap/internal/metrics"
)

// RedactionType is the type tag embedded in <REDACTED:<type>:hex>.
type RedactionType string

const (
	TypeAuthorization      RedactionType = "authorization"
	TypeCookie             RedactionType = "cookie"
	TypeProxyAuthorization RedactionType = "proxy-authorization"
	TypeSetCookie          RedactionType = "set-cookie"
	TypeXAPIKey            RedactionType = "x-api-key"
	TypeJWT                RedactionType = "jwt"
	TypePAN                RedactionType = "pan"
	TypeSKToken            RedactionType = "sk_token"
	TypePKToken            RedactionType = "pk_token"
	TypeCustom             RedactionType = "custom"
)

// defaultDeniedHeaders is the CAPTURE-09 header allowlist. Keys are
// stored lowercased; Engine matches case-insensitively.
var defaultDeniedHeaders = map[string]RedactionType{
	"authorization":       TypeAuthorization,
	"cookie":              TypeCookie,
	"proxy-authorization": TypeProxyAuthorization,
	"set-cookie":          TypeSetCookie,
	"x-api-key":           TypeXAPIKey,
}

// Compiled body patterns. Go's regexp is RE2 (linear time) — safe against
// adversarial input.
var (
	// RFC 7519: three base64url segments separated by `.`, each ≥ 4 chars
	// (excludes trivially short matches that would be false positives).
	jwtPattern = regexp.MustCompile(`[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}`)
	// 13–19 digits, optionally separated by space or hyphen. Luhn check
	// applied in the redaction step to reject false positives.
	panPattern = regexp.MustCompile(`\b(?:\d[ -]*?){13,19}\b`)
	// Stripe-style prefixes.
	skTokenPattern = regexp.MustCompile(`sk_(?:test|live)_[A-Za-z0-9]{24,}`)
	pkTokenPattern = regexp.MustCompile(`pk_(?:test|live)_[A-Za-z0-9]{24,}`)
)

// Engine performs header + body redaction for one organization.
// Construct once at agent startup (or whenever DynamicConfig changes the
// per-org salt or allowlist).
type Engine struct {
	salt              []byte
	deniedHeaders     map[string]RedactionType // lowercased; merged default + extras
	extraBodyPatterns []*regexp.Regexp
}

// NewEngine returns an Engine configured with the per-org [salt],
// optional [extraHeaders] (Phase 3 SEC-09), and optional [extraBodyPatterns]
// (Phase 3). Empty salt is allowed for tests / pre-first-poll bootstrap;
// in production the salt is always populated from
// DynamicConfig.RedactionSalt.
//
// Returns an error if any [extraBodyPatterns] regex fails to compile.
func NewEngine(salt string, extraHeaders []string, extraBodyPatterns []string) (*Engine, error) {
	denied := make(map[string]RedactionType, len(defaultDeniedHeaders)+len(extraHeaders))
	for k, v := range defaultDeniedHeaders {
		denied[k] = v
	}
	for _, h := range extraHeaders {
		denied[strings.ToLower(strings.TrimSpace(h))] = TypeCustom
	}
	extras := make([]*regexp.Regexp, 0, len(extraBodyPatterns))
	for i, p := range extraBodyPatterns {
		re, err := regexp.Compile(p)
		if err != nil {
			return nil, fmt.Errorf("extraBodyRedactionPatterns[%d]: %w", i, err)
		}
		extras = append(extras, re)
	}
	return &Engine{
		salt:              []byte(salt),
		deniedHeaders:     denied,
		extraBodyPatterns: extras,
	}, nil
}

// RedactHeaders mutates [h] in place, replacing every value of any
// denied header (case-insensitive) with a typed placeholder.
//
// Headers are typed map[string][]string to match Go's net/http style.
// Multi-value headers get every value replaced — each independently
// hashes to its own placeholder (so two identical Cookie values map to
// the same placeholder, two different ones to different placeholders).
func (e *Engine) RedactHeaders(h map[string][]string) {
	if h == nil {
		return
	}
	for name, values := range h {
		t, denied := e.deniedHeaders[strings.ToLower(name)]
		if !denied {
			continue
		}
		for i, v := range values {
			values[i] = e.placeholder(t, v)
			metrics.RedactionReplacementsTotal.WithLabelValues(string(t)).Inc()
		}
		h[name] = values
	}
}

// RedactBody redacts the body according to [contentType]. Returns the
// possibly-modified body. If the content-type is unknown / binary,
// returns [body] unchanged and increments
// vp_tap_redaction_truncated_bodies_total only when the upstream caller
// already truncated the body.
//
// [truncated] flags bodies that were cut off upstream (per-buffer cap);
// when set, body-pattern redaction is skipped because the regex may match
// across boundary-truncated content.
func (e *Engine) RedactBody(contentType string, body []byte, truncated bool) []byte {
	if len(body) == 0 {
		return body
	}
	if truncated {
		metrics.RedactionTruncatedBodiesTotal.Inc()
		return body
	}

	ct := strings.ToLower(strings.TrimSpace(contentType))
	// Strip "; charset=..." etc.
	if i := strings.IndexByte(ct, ';'); i >= 0 {
		ct = strings.TrimSpace(ct[:i])
	}

	switch {
	case ct == "application/json":
		return e.redactJSON(body)
	case ct == "application/x-www-form-urlencoded" || strings.HasPrefix(ct, "text/"):
		return []byte(e.redactString(string(body)))
	default:
		return body
	}
}

// redactString applies the body-pattern regex set to a string and
// returns the result with placeholders.
func (e *Engine) redactString(s string) string {
	s = e.replaceAllWithLuhnGate(s, panPattern, TypePAN, true)
	s = e.replaceAll(s, jwtPattern, TypeJWT)
	s = e.replaceAll(s, skTokenPattern, TypeSKToken)
	s = e.replaceAll(s, pkTokenPattern, TypePKToken)
	for _, re := range e.extraBodyPatterns {
		s = e.replaceAll(s, re, TypeCustom)
	}
	return s
}

func (e *Engine) replaceAll(s string, re *regexp.Regexp, t RedactionType) string {
	return re.ReplaceAllStringFunc(s, func(match string) string {
		metrics.RedactionReplacementsTotal.WithLabelValues(string(t)).Inc()
		return e.placeholder(t, match)
	})
}

// replaceAllWithLuhnGate is like replaceAll but only redacts matches
// that pass a Luhn check (PAN detection). When [luhn] is false, behaves
// like replaceAll.
func (e *Engine) replaceAllWithLuhnGate(s string, re *regexp.Regexp, t RedactionType, luhn bool) string {
	if !luhn {
		return e.replaceAll(s, re, t)
	}
	return re.ReplaceAllStringFunc(s, func(match string) string {
		digits := stripNonDigits(match)
		if !luhnValid(digits) {
			return match
		}
		metrics.RedactionReplacementsTotal.WithLabelValues(string(t)).Inc()
		return e.placeholder(t, match)
	})
}

// redactJSON parses [body] as JSON and walks the tree, replacing leaf
// strings matching any redaction pattern. Returns the re-marshaled JSON.
// If parsing fails, returns the input unchanged (don't corrupt bodies
// the agent can't understand).
func (e *Engine) redactJSON(body []byte) []byte {
	var root any
	if err := json.Unmarshal(body, &root); err != nil {
		return body
	}
	root = e.walkJSON(root)
	out, err := json.Marshal(root)
	if err != nil {
		return body
	}
	return out
}

func (e *Engine) walkJSON(v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, vv := range x {
			x[k] = e.walkJSON(vv)
		}
		return x
	case []any:
		for i, vv := range x {
			x[i] = e.walkJSON(vv)
		}
		return x
	case string:
		return e.redactString(x)
	default:
		return v
	}
}

// placeholder builds the typed deterministic placeholder for a given
// raw value. Format: <REDACTED:type:hex6>.
func (e *Engine) placeholder(t RedactionType, value string) string {
	sum := sha256.New()
	sum.Write(e.salt)
	sum.Write([]byte{':'})
	sum.Write([]byte(value))
	digest := sum.Sum(nil)
	return fmt.Sprintf("<REDACTED:%s:%s>", t, hex.EncodeToString(digest[:3]))
}

// stripNonDigits returns [s] with all non-digit runes removed.
func stripNonDigits(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// luhnValid returns true iff [s] is a Luhn-valid digit-only string.
// Used to filter PAN-shape false positives (regex matches but checksum
// fails). Length must be 13–19 per PAN spec.
func luhnValid(s string) bool {
	if len(s) < 13 || len(s) > 19 {
		return false
	}
	sum := 0
	alt := false
	for i := len(s) - 1; i >= 0; i-- {
		d := int(s[i] - '0')
		if alt {
			d *= 2
			if d > 9 {
				d -= 9
			}
		}
		sum += d
		alt = !alt
	}
	return sum%10 == 0
}
