// vp-tap is the TAP-1 prototype: prove eBPF L7 capture on GKE COS.
//
// Loads the probe.bpf.o object built by bpf2go, attaches it to the
// sys_enter_write tracepoint, and drains a ring buffer. Every event is
// expected to begin with an HTTP/1.1 request or response prefix (filtered
// in-kernel by the eBPF program). For each event we log:
//   - timestamp
//   - first line of the buffer (request line or status line)
//   - PID and TGID
//   - pod UID + container ID resolved from /proc/<tgid>/cgroup
//
// Stop with SIGINT or SIGTERM. main() is pure orchestration: it sets up
// BPF state, then launches three cooperating goroutines (shutdown
// handler, heartbeat, capture loop) and blocks until shutdown drains.

package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"log"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"github.com/cilium/ebpf/rlimit"

	bpf "vp-tap/internal/ebpf"
	"vp-tap/internal/pod"
)

const (
	maxDataSize       = 256
	heartbeatInterval = 30 * time.Second
)

// event mirrors `struct event` in probe.bpf.c. Field order and types must
// match exactly. The verifier uses native (little-endian on amd64) layout.
type event struct {
	PID  uint32
	TGID uint32
	Len  uint32
	FD   uint32
	Data [maxDataSize]byte
}

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.Printf("vp-tap TAP-1 prototype starting (pid=%d)", os.Getpid())

	if err := rlimit.RemoveMemlock(); err != nil {
		log.Fatalf("removing memlock: %v", err)
	}

	objs := bpf.ProbeObjects{}
	if err := bpf.LoadProbeObjects(&objs, nil); err != nil {
		log.Fatalf("loading bpf objects: %v", err)
	}
	defer objs.Close()

	tp, err := link.Tracepoint("syscalls", "sys_enter_write", objs.TraceSysEnterWrite, nil)
	if err != nil {
		log.Fatalf("attaching tracepoint sys_enter_write: %v", err)
	}
	defer tp.Close()
	log.Printf("attached tracepoint syscalls/sys_enter_write")

	rd, err := ringbuf.NewReader(objs.Events)
	if err != nil {
		log.Fatalf("opening ringbuf reader: %v", err)
	}
	defer rd.Close()

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	cache := pod.NewCache()
	var captured uint64

	// Launch the three cooperating goroutines and block on their
	// completion. They each exit on ctx cancellation; the shutdown
	// handler is the one that actually closes the ringbuf so the
	// capture loop can unwind. main does no work itself — wg.Wait()
	// is the join point.
	var wg sync.WaitGroup
	wg.Add(3)
	go runShutdownHandler(ctx, &wg, rd)
	go runHeartbeat(ctx, &wg, &captured)
	go runCaptureLoop(&wg, rd, cache, &captured)
	wg.Wait()
}

// runShutdownHandler waits for SIGTERM/SIGINT (delivered through ctx) and
// closes the ringbuf reader. Closing the reader unblocks runCaptureLoop
// with a ringbuf.ErrClosed sentinel, which is the only graceful way out
// of the otherwise-blocking rd.Read() call. The goroutine exits as soon
// as the close happens, regardless of whether the capture loop is still
// processing events — the deferred rd.Close() in main is idempotent.
func runShutdownHandler(ctx context.Context, wg *sync.WaitGroup, rd *ringbuf.Reader) {
	defer wg.Done()
	<-ctx.Done()
	log.Printf("shutdown signal received, closing ringbuf")
	_ = rd.Close()
}

// runHeartbeat logs a "still alive" line with the running capture count
// every heartbeatInterval. Useful when traffic is sparse — a silent log
// file makes it hard to tell "no traffic" from "tap is wedged." Reads
// the counter atomically because the capture loop writes it without
// holding any lock.
func runHeartbeat(ctx context.Context, wg *sync.WaitGroup, captured *uint64) {
	defer wg.Done()
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			log.Printf("heartbeat captured=%d", atomic.LoadUint64(captured))
		}
	}
}

// runCaptureLoop is the hot path. It blocks on rd.Read() for each ringbuf
// event, decodes the raw bytes into a Go `event` struct (same byte layout
// as the C struct in probe.bpf.c), pulls the first HTTP line out of the
// payload, resolves the source pod from /proc/<tgid>/cgroup via the
// cache, and emits one log line per captured event.
//
// Returns when rd.Read() yields ringbuf.ErrClosed, which the shutdown
// handler arranges by closing the reader. Other read errors are logged
// and the loop continues — a transient verifier or memory error on one
// event shouldn't kill the whole tap.
func runCaptureLoop(wg *sync.WaitGroup, rd *ringbuf.Reader, cache *pod.Cache, captured *uint64) {
	defer wg.Done()
	log.Printf("ringbuf reader open; waiting for HTTP traffic on this node...")

	var e event
	for {
		record, err := rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				log.Printf("ringbuf closed, exiting (total captured=%d)", atomic.LoadUint64(captured))
				return
			}
			log.Printf("ringbuf read error: %v", err)
			continue
		}

		if err := binary.Read(bytes.NewReader(record.RawSample), binary.LittleEndian, &e); err != nil {
			log.Printf("decode error: %v", err)
			continue
		}

		n := int(e.Len)
		if n > maxDataSize {
			n = maxDataSize
		}
		line := firstHTTPLine(e.Data[:n])
		if line == "" {
			// In-kernel filter said it looked like HTTP, but the first line
			// didn't survive the check (e.g. truncated). Skip but count it.
			continue
		}

		info := cache.Lookup(e.TGID)
		atomic.AddUint64(captured, 1)

		log.Printf("[tgid=%d pid=%d fd=%d pod=%s container=%s] %s",
			e.TGID, e.PID, e.FD, orQ(info.PodUID), orQ(info.ContainerID), line)
	}
}

// firstHTTPLine extracts the request line or status line from a write
// buffer that the in-kernel filter already pre-screened as HTTP-shaped.
// Returns "" if userspace can't confirm the prefix — the in-kernel sniff
// is a 4-byte check on raw bytes, this re-validates after we have the
// full line.
func firstHTTPLine(b []byte) string {
	idx := bytes.IndexAny(b, "\r\n")
	if idx < 0 {
		idx = len(b)
	}
	s := string(b[:idx])
	switch {
	case strings.HasPrefix(s, "HTTP/"):
		return s
	case strings.HasPrefix(s, "GET "),
		strings.HasPrefix(s, "POST "),
		strings.HasPrefix(s, "PUT "),
		strings.HasPrefix(s, "DELETE "),
		strings.HasPrefix(s, "HEAD "),
		strings.HasPrefix(s, "PATCH "),
		strings.HasPrefix(s, "OPTIONS "),
		strings.HasPrefix(s, "CONNECT "),
		strings.HasPrefix(s, "TRACE "):
		return s
	}
	return ""
}

// orQ substitutes "?" for empty strings so log lines render cleanly when
// pod attribution falls through (host processes outside kubepods).
func orQ(s string) string {
	if s == "" {
		return "?"
	}
	return s
}
