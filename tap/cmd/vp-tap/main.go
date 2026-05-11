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
// A heartbeat line is emitted every 30s with the running capture count so
// the operator can tell the program is alive even when no HTTP flows.
//
// Stop with SIGINT or SIGTERM. The 10-minute smoke test in TAP-1's
// validation criteria is just `kubectl logs ... -f` against this binary
// running as a DaemonSet on the sandbox.

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
	"sync/atomic"
	"syscall"
	"time"

	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"github.com/cilium/ebpf/rlimit"

	bpf "vp-tap/internal/ebpf"
	"vp-tap/internal/pod"
)

const maxDataSize = 256

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

	go func() {
		<-ctx.Done()
		log.Printf("shutdown signal received, closing ringbuf")
		_ = rd.Close()
	}()

	cache := pod.NewCache()
	var captured uint64

	// Heartbeat goroutine — proves the loop is alive when traffic is sparse.
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				log.Printf("heartbeat captured=%d", atomic.LoadUint64(&captured))
			}
		}
	}()

	log.Printf("ringbuf reader open; waiting for HTTP traffic on this node...")

	var e event
	for {
		record, err := rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				log.Printf("ringbuf closed, exiting (total captured=%d)", atomic.LoadUint64(&captured))
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
		atomic.AddUint64(&captured, 1)

		log.Printf("[tgid=%d pid=%d fd=%d pod=%s container=%s] %s",
			e.TGID, e.PID, e.FD, orQ(info.PodUID), orQ(info.ContainerID), line)
	}
}

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

func orQ(s string) string {
	if s == "" {
		return "?"
	}
	return s
}
