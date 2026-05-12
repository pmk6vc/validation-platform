// vp-tap is the BPF-based traffic capture daemon for the validation platform.
//
// Loads the probe.bpf.o object built by bpf2go, attaches to a set of syscall
// tracepoints, and drains two ring buffers in parallel:
//
//   - `events`         — HTTP-data writes (per syscall, content-pre-filtered)
//   - `socket_events`  — socket lifecycle (connect / accept4 / close)
//
// For each HTTP event we log timestamp, first line, PID/TGID, fd, cgroup_id,
// and pod UID + container ID resolved from /proc/<tgid>/cgroup (the latter is
// transitional — PR3 replaces it with a K8s informer keyed by cgroup_id).
// For each socket event we log the lifecycle kind (CONNECT / ACCEPT / CLOSE)
// and the peer address/port.
//
// Stop with SIGINT or SIGTERM. main() is pure orchestration: it sets up BPF
// state, then launches four cooperating goroutines (shutdown handler,
// heartbeat, HTTP capture loop, socket-event loop) and blocks until shutdown
// drains.

package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/ringbuf"
	"github.com/cilium/ebpf/rlimit"

	bpf "vp-tap/internal/ebpf"
	"vp-tap/internal/pod"
)

const heartbeatInterval = 30 * time.Second

// Per-event structs are bpf.ProbeEvent and bpf.ProbeSocketEvent — bpf2go
// generates them from the BTF debug info in probe.bpf.c (`-type event` and
// `-type socket_event` flags in gen.go). One source of truth: edit the C
// struct and the Go mirrors update on the next `go generate`.

func main() {
	setupLogger()
	mustRaiseMemlock()

	objs := mustLoadBPFObjects()
	defer objs.Close()

	links := mustAttachTracepoints(objs)
	defer closeLinks(links)

	httpRd := mustOpenRingbuf(objs.Events, "events")
	defer httpRd.Close()

	sockRd := mustOpenRingbuf(objs.SocketEvents, "socket_events")
	defer sockRd.Close()

	ctx, cancel := installSignalHandler()
	defer cancel()

	cache := pod.NewCache()
	var capturedHTTP, capturedSock uint64

	// Four cooperating goroutines: shutdown handler (closes both ringbufs),
	// heartbeat, HTTP capture loop, socket-event loop. Each exits on ctx
	// cancellation propagated through the closed ringbufs.
	var wg sync.WaitGroup
	wg.Add(4)
	go runShutdownHandler(ctx, &wg, httpRd, sockRd)
	go runHeartbeat(ctx, &wg, &capturedHTTP, &capturedSock)
	go runCaptureLoop(&wg, httpRd, cache, &capturedHTTP)
	go runSocketEventLoop(&wg, sockRd, cache, &capturedSock)
	wg.Wait()
}

// setupLogger configures the stdlib logger to prepend UTC date+time and
// emits the initial startup line.
func setupLogger() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.Printf("vp-tap starting (pid=%d)", os.Getpid())
}

// mustRaiseMemlock removes the RLIMIT_MEMLOCK cap so BPF maps can be
// allocated. On kernels < 5.11 the default 64 KiB ceiling rejects our
// ringbufs; on newer kernels the call is a harmless no-op (BPF memory
// has separate accounting). Requires CAP_SYS_RESOURCE, which the
// DaemonSet's privileged: true provides.
func mustRaiseMemlock() {
	if err := rlimit.RemoveMemlock(); err != nil {
		log.Fatalf("removing memlock: %v", err)
	}
}

// mustLoadBPFObjects parses the bpf2go-generated ELF blob, makes the
// bpf(BPF_PROG_LOAD) and bpf(BPF_MAP_CREATE) syscalls to install our
// programs and maps in the kernel, and returns a ProbeObjects struct
// populated with the resulting kernel file descriptors. The caller
// must defer objs.Close() — closing releases the kernel fds.
func mustLoadBPFObjects() bpf.ProbeObjects {
	objs := bpf.ProbeObjects{}
	if err := bpf.LoadProbeObjects(&objs, nil); err != nil {
		log.Fatalf("loading bpf objects: %v", err)
	}
	return objs
}

// mustAttachTracepoints wires every BPF program in `objs` to its kernel
// hook. Returns a slice of link.Link the caller must close on shutdown
// (closing a link detaches its program from the tracepoint).
func mustAttachTracepoints(objs bpf.ProbeObjects) []link.Link {
	type entry struct {
		group, name string
		prog        *ebpf.Program
	}
	specs := []entry{
		{"syscalls", "sys_enter_write", objs.TraceSysEnterWrite},
		{"syscalls", "sys_enter_connect", objs.TraceSysEnterConnect},
		{"syscalls", "sys_enter_accept4", objs.TraceSysEnterAccept4},
		{"syscalls", "sys_exit_accept4", objs.TraceSysExitAccept4},
		{"syscalls", "sys_enter_close", objs.TraceSysEnterClose},
	}
	links := make([]link.Link, 0, len(specs))
	for _, s := range specs {
		tp, err := link.Tracepoint(s.group, s.name, s.prog, nil)
		if err != nil {
			log.Fatalf("attaching tracepoint %s/%s: %v", s.group, s.name, err)
		}
		links = append(links, tp)
	}
	log.Printf("attached %d syscall tracepoints", len(links))
	return links
}

// closeLinks closes every attached BPF link in reverse order.
func closeLinks(links []link.Link) {
	for i := len(links) - 1; i >= 0; i-- {
		_ = links[i].Close()
	}
}

// mustOpenRingbuf opens the userspace end of a BPF ringbuf map. The
// returned *ringbuf.Reader mmaps the ringbuf data region into our
// address space (zero-copy reads) and sets up epoll so rd.Read() can
// block efficiently when the buffer is empty.
func mustOpenRingbuf(m *ebpf.Map, name string) *ringbuf.Reader {
	rd, err := ringbuf.NewReader(m)
	if err != nil {
		log.Fatalf("opening ringbuf reader for %s: %v", name, err)
	}
	return rd
}

// installSignalHandler returns a context that is cancelled on SIGINT or
// SIGTERM. SIGTERM is what `kubectl delete pod` sends (SIGKILL follows
// 30s later if we haven't exited).
func installSignalHandler() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
}

// runShutdownHandler waits for SIGTERM/SIGINT and closes both ringbufs so
// the capture loops unblock with a ringbuf.ErrClosed sentinel. Idempotent
// with main's deferred closes.
func runShutdownHandler(ctx context.Context, wg *sync.WaitGroup, rds ...*ringbuf.Reader) {
	defer wg.Done()
	<-ctx.Done()
	log.Printf("shutdown signal received, closing ringbufs")
	for _, rd := range rds {
		_ = rd.Close()
	}
}

// runHeartbeat logs HTTP + socket capture counts every heartbeatInterval.
func runHeartbeat(ctx context.Context, wg *sync.WaitGroup, http *uint64, sock *uint64) {
	defer wg.Done()
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			log.Printf("heartbeat http=%d sock=%d",
				atomic.LoadUint64(http), atomic.LoadUint64(sock))
		}
	}
}

// runCaptureLoop drains the HTTP-data ringbuf. Same shape as TAP-1.
func runCaptureLoop(wg *sync.WaitGroup, rd *ringbuf.Reader, cache *pod.Cache, captured *uint64) {
	defer wg.Done()
	log.Printf("events ringbuf open; waiting for HTTP traffic on this node...")

	var e bpf.ProbeEvent
	for {
		record, err := rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				log.Printf("events ringbuf closed, exiting (total http=%d)", atomic.LoadUint64(captured))
				return
			}
			log.Printf("events ringbuf read error: %v", err)
			continue
		}

		if err := binary.Read(bytes.NewReader(record.RawSample), binary.LittleEndian, &e); err != nil {
			log.Printf("events decode error: %v", err)
			continue
		}

		n := int(e.Len)
		if n > len(e.Data) {
			n = len(e.Data)
		}
		line := firstHTTPLine(e.Data[:n])
		if line == "" {
			continue
		}

		info := cache.Lookup(e.Tgid)
		atomic.AddUint64(captured, 1)

		log.Printf("[http cgroup=%d tgid=%d pid=%d fd=%d pod=%s container=%s] %s",
			e.CgroupId, e.Tgid, e.Pid, e.Fd, orQ(info.PodUID), orQ(info.ContainerID), line)
	}
}

// runSocketEventLoop drains the socket lifecycle ringbuf. Logs each
// CONNECT / ACCEPT / CLOSE event with the peer addr:port and the cgroup
// of the originating task. PR4 will consume this same stream to populate
// the per-socket filter set.
func runSocketEventLoop(wg *sync.WaitGroup, rd *ringbuf.Reader, cache *pod.Cache, captured *uint64) {
	defer wg.Done()
	log.Printf("socket_events ringbuf open; waiting for connect/accept/close events...")

	var s bpf.ProbeSocketEvent
	for {
		record, err := rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				log.Printf("socket_events ringbuf closed, exiting (total sock=%d)", atomic.LoadUint64(captured))
				return
			}
			log.Printf("socket_events ringbuf read error: %v", err)
			continue
		}

		if err := binary.Read(bytes.NewReader(record.RawSample), binary.LittleEndian, &s); err != nil {
			log.Printf("socket_events decode error: %v", err)
			continue
		}

		atomic.AddUint64(captured, 1)
		info := cache.Lookup(s.Tgid)

		log.Printf("[sock %s cgroup=%d tgid=%d pid=%d fd=%d pod=%s peer=%s]",
			socketEventKindName(s.Kind), s.CgroupId, s.Tgid, s.Pid, s.Fd,
			orQ(info.PodUID), formatPeer(s.Family, s.PeerAddr, s.PeerPort))
	}
}

// socketEventKindName maps the BPF socket_event_kind enum to a human
// label. Must stay in sync with probe.bpf.c's enum.
func socketEventKindName(k uint32) string {
	switch k {
	case 1:
		return "CONNECT"
	case 2:
		return "ACCEPT"
	case 3:
		return "CLOSE"
	default:
		return fmt.Sprintf("kind=%d", k)
	}
}

// formatPeer renders the (family, addr, port) tuple into a human-readable
// "ip:port" string. The port comes from BPF in network byte order (we
// copied sin_port verbatim); addr is 4 bytes for AF_INET in network
// order. AF_INET6 surfaces as "[v6]:port" — full IPv6 decode deferred.
func formatPeer(family uint32, addr [16]byte, portNet uint16) string {
	// portNet is the raw 16-bit network-byte-order value as stored in
	// memory. binary.BigEndian.Uint16 over its 2 bytes yields host order.
	pb := []byte{byte(portNet), byte(portNet >> 8)}
	port := binary.BigEndian.Uint16(pb)
	switch family {
	case 2: // AF_INET
		ip := net.IPv4(addr[0], addr[1], addr[2], addr[3])
		return fmt.Sprintf("%s:%d", ip.String(), port)
	case 10: // AF_INET6
		return fmt.Sprintf("[v6]:%d", port)
	case 0:
		return "-"
	default:
		return fmt.Sprintf("family=%d", family)
	}
}

// httpLinePrefixes is the set of accepted leading tokens for an HTTP/1.1
// request line or status line.
var httpLinePrefixes = []string{
	"HTTP/", // response status line
	"GET ", "POST ", "PUT ", "DELETE ",
	"HEAD ", "PATCH ", "OPTIONS ", "CONNECT ", "TRACE ",
}

// firstHTTPLine extracts the request line or status line from a write
// buffer that the in-kernel filter already pre-screened as HTTP-shaped.
func firstHTTPLine(b []byte) string {
	idx := bytes.IndexAny(b, "\r\n")
	if idx < 0 {
		idx = len(b)
	}
	s := string(b[:idx])
	for _, p := range httpLinePrefixes {
		if strings.HasPrefix(s, p) {
			return s
		}
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
