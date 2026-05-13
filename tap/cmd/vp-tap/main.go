// vp-tap is the BPF-based traffic capture daemon for the validation platform.
//
// Loads the probe.bpf.o object built by bpf2go, attaches it to the
// sys_enter_write tracepoint, and drains a ring buffer. For each captured
// event we log timestamp, first line, PID/TGID, fd, cgroup_id, and the pod
// (namespace/name) resolved by joining cgroup_id against a K8s-informer
// backed index of pods on this node.
//
// Stop with SIGINT or SIGTERM. main() is pure orchestration: it sets up
// BPF state and the informer, then launches four cooperating goroutines
// (shutdown handler, heartbeat, informer Run, capture loop) and blocks
// until shutdown drains.

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

	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"

	bpf "vp-tap/internal/ebpf"
	"vp-tap/internal/k8s/podinformer"
)

const (
	heartbeatInterval = 30 * time.Second

	// cgroup v2 root inside the container. The DaemonSet must hostPath-mount
	// /sys/fs/cgroup from the host so the informer can stat() pod-cgroup
	// slice directories to learn cgroup IDs.
	cgroupRoot = "/sys/fs/cgroup"
)

// Per-event struct is bpf.ProbeEvent — bpf2go generates it from the BTF
// debug info in probe.bpf.c. One source of truth: edit the C struct and
// the Go mirror updates on the next `go generate`.

func main() {
	setupLogger()
	mustRaiseMemlock()

	nodeName := mustGetNodeName()

	objs := mustLoadBPFObjects()
	defer objs.Close()

	tp := mustAttachTracepoint(objs)
	defer tp.Close()

	rd := mustOpenRingbuf(objs)
	defer rd.Close()

	ctx, cancel := installSignalHandler()
	defer cancel()

	index := mustBuildPodIndex(nodeName)
	var captured uint64

	// Four cooperating goroutines: shutdown handler, heartbeat,
	// informer Run, capture loop. Each exits on ctx cancellation
	// (the shutdown handler propagates by closing the ringbuf).
	var wg sync.WaitGroup
	wg.Add(4)
	go runShutdownHandler(ctx, &wg, rd)
	go runHeartbeat(ctx, &wg, &captured, index)
	go runPodInformer(ctx, &wg, index)
	go runCaptureLoop(&wg, rd, index, &captured)
	wg.Wait()
}

// setupLogger configures the stdlib logger to prepend UTC date+time.
func setupLogger() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.Printf("vp-tap starting (pid=%d)", os.Getpid())
}

// mustRaiseMemlock removes the RLIMIT_MEMLOCK cap so BPF maps can be
// allocated. On kernels < 5.11 the default 64 KiB ceiling rejects our
// 8 MiB ringbuf; on newer kernels the call is a harmless no-op.
func mustRaiseMemlock() {
	if err := rlimit.RemoveMemlock(); err != nil {
		log.Fatalf("removing memlock: %v", err)
	}
}

// mustGetNodeName returns the K8s node name the agent is running on.
// Sourced from the NODE_NAME env var, injected by the DaemonSet via the
// downward API (spec.nodeName). Fatal if missing — without it we can't
// scope the informer to local-node pods.
func mustGetNodeName() string {
	name := os.Getenv("NODE_NAME")
	if name == "" {
		log.Fatalf("NODE_NAME env var is required (set via downward API in the DaemonSet)")
	}
	return name
}

// mustLoadBPFObjects parses the bpf2go-generated ELF blob, makes the
// bpf(BPF_PROG_LOAD) and bpf(BPF_MAP_CREATE) syscalls, returns kernel fds.
func mustLoadBPFObjects() bpf.ProbeObjects {
	objs := bpf.ProbeObjects{}
	if err := bpf.LoadProbeObjects(&objs, nil); err != nil {
		log.Fatalf("loading bpf objects: %v", err)
	}
	return objs
}

// mustAttachTracepoint wires the BPF program to syscalls/sys_enter_write.
func mustAttachTracepoint(objs bpf.ProbeObjects) link.Link {
	tp, err := link.Tracepoint("syscalls", "sys_enter_write", objs.TraceSysEnterWrite, nil)
	if err != nil {
		log.Fatalf("attaching tracepoint sys_enter_write: %v", err)
	}
	log.Printf("attached tracepoint syscalls/sys_enter_write")
	return tp
}

// mustOpenRingbuf opens the userspace end of the BPF ringbuf map.
func mustOpenRingbuf(objs bpf.ProbeObjects) *ringbuf.Reader {
	rd, err := ringbuf.NewReader(objs.Events)
	if err != nil {
		log.Fatalf("opening ringbuf reader: %v", err)
	}
	return rd
}

// installSignalHandler returns a context cancelled on SIGINT/SIGTERM.
func installSignalHandler() (context.Context, context.CancelFunc) {
	return signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
}

// mustBuildPodIndex constructs the cgroup_id → *v1.Pod index against the
// in-cluster K8s API. Returns the index without starting it — runPodInformer
// does that in its own goroutine.
func mustBuildPodIndex(nodeName string) *podinformer.Index {
	cfg, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("loading in-cluster K8s config: %v", err)
	}
	client, err := kubernetes.NewForConfig(cfg)
	if err != nil {
		log.Fatalf("constructing K8s client: %v", err)
	}
	resolver := podinformer.NewFSCgroupResolver(cgroupRoot)
	return podinformer.NewIndex(client, nodeName, resolver)
}

// runShutdownHandler waits for SIGTERM/SIGINT and closes the ringbuf so
// the capture loop unblocks with ringbuf.ErrClosed.
func runShutdownHandler(ctx context.Context, wg *sync.WaitGroup, rd *ringbuf.Reader) {
	defer wg.Done()
	<-ctx.Done()
	log.Printf("shutdown signal received, closing ringbuf")
	_ = rd.Close()
}

// runHeartbeat logs a "still alive" line with capture count + index size
// every heartbeatInterval. Useful when traffic is sparse.
func runHeartbeat(ctx context.Context, wg *sync.WaitGroup, captured *uint64, idx *podinformer.Index) {
	defer wg.Done()
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			log.Printf("heartbeat captured=%d indexed_cgroups=%d",
				atomic.LoadUint64(captured), idx.Size())
		}
	}
}

// runPodInformer runs the K8s informer goroutine. Blocks until ctx done.
func runPodInformer(ctx context.Context, wg *sync.WaitGroup, idx *podinformer.Index) {
	defer wg.Done()
	idx.Run(ctx)
}

// runCaptureLoop drains the HTTP-data ringbuf and emits one log line per
// captured event, attributing via the informer's cgroup_id → pod index.
func runCaptureLoop(wg *sync.WaitGroup, rd *ringbuf.Reader, idx *podinformer.Index, captured *uint64) {
	defer wg.Done()
	log.Printf("events ringbuf open; waiting for HTTP traffic on this node...")

	var e bpf.ProbeEvent
	for {
		record, err := rd.Read()
		if err != nil {
			if errors.Is(err, ringbuf.ErrClosed) {
				log.Printf("events ringbuf closed, exiting (total captured=%d)", atomic.LoadUint64(captured))
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

		atomic.AddUint64(captured, 1)

		// cgroup_id → pod via informer. Misses (host processes outside
		// kubepods, or pods the informer hasn't seen yet) log as "pod=?"
		// — we still emit the event for diagnostic purposes.
		podLabel := "?"
		if p, ok := idx.Lookup(e.CgroupId); ok {
			podLabel = p.Namespace + "/" + p.Name
		}

		log.Printf("[cgroup=%d tgid=%d pid=%d fd=%d pod=%s] %s",
			e.CgroupId, e.Tgid, e.Pid, e.Fd, podLabel, line)
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
