package main

import "testing"

func TestFirstHTTPLineRequest(t *testing.T) {
	cases := map[string]string{
		"GET /api/orders HTTP/1.1\r\nHost: ...":            "GET /api/orders HTTP/1.1",
		"POST /api/orders HTTP/1.1\r\nContent-Length: 12":  "POST /api/orders HTTP/1.1",
		"DELETE /api/orders/42 HTTP/1.1":                   "DELETE /api/orders/42 HTTP/1.1",
		"OPTIONS * HTTP/1.1\r\nHost: example.com":          "OPTIONS * HTTP/1.1",
		"HEAD / HTTP/1.1":                                  "HEAD / HTTP/1.1",
		"PATCH /resource HTTP/1.1":                         "PATCH /resource HTTP/1.1",
		"PUT /thing HTTP/1.1":                              "PUT /thing HTTP/1.1",
		"CONNECT host:443 HTTP/1.1":                        "CONNECT host:443 HTTP/1.1",
		"TRACE / HTTP/1.1":                                 "TRACE / HTTP/1.1",
	}
	for input, want := range cases {
		got := firstHTTPLine([]byte(input))
		if got != want {
			t.Errorf("firstHTTPLine(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestFirstHTTPLineResponse(t *testing.T) {
	cases := map[string]string{
		"HTTP/1.1 200 OK\r\nContent-Type: text/plain": "HTTP/1.1 200 OK",
		"HTTP/1.1 404 Not Found\r\n":                  "HTTP/1.1 404 Not Found",
		"HTTP/1.0 500 Internal Server Error":          "HTTP/1.0 500 Internal Server Error",
	}
	for input, want := range cases {
		got := firstHTTPLine([]byte(input))
		if got != want {
			t.Errorf("firstHTTPLine(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestFirstHTTPLineRejectsNonHTTP(t *testing.T) {
	// The in-kernel filter pre-screens on 4 bytes, but garbage that *starts*
	// with "POST" or "HEAD" could slip through (e.g., "POSTGRES" wire-protocol
	// prefix, "HEADER:" log lines). The userspace check requires the trailing
	// space to reject those.
	inputs := []string{
		"POSTFIX configuration", // starts with "POST" but not "POST "
		"HEADER: x-thing\r\n",   // starts with "HEAD" but not "HEAD "
		"GETSTATE protocol",     // starts with "GETS" not "GET "
		"random garbage bytes",  // no HTTP prefix at all
		"",                      // empty buffer
		"GET",                   // valid prefix but no trailing space
	}
	for _, in := range inputs {
		if got := firstHTTPLine([]byte(in)); got != "" {
			t.Errorf("firstHTTPLine(%q) = %q, want empty string", in, got)
		}
	}
}

func TestFirstHTTPLineNoLineTerminator(t *testing.T) {
	// If the buffer is truncated mid-line (no \r or \n), the whole buffer
	// should be returned as the "first line" — the in-kernel filter caps
	// our read at 256 bytes, so seeing a long line without a terminator is
	// possible.
	in := "GET /very/long/path/that/fills/the/buffer HTTP/1.1"
	got := firstHTTPLine([]byte(in))
	if got != in {
		t.Errorf("firstHTTPLine(%q) = %q, want %q", in, got, in)
	}
}

