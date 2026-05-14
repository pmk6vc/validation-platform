//go:build ignore

// SPDX-License-Identifier: BSD-2-Clause
//
// Build constraint above tells Go's toolchain to skip this file entirely.
// Without it, `go test ./...` with cgo enabled (the default outside our
// Dockerfile) sees a .c file in a Go package and errors with "C source
// files not allowed when not using cgo or SWIG". The actual BPF
// compilation goes through bpf2go → clang -target bpf and ignores this
// constraint, so the file still gets compiled correctly into BPF
// bytecode by `go generate`.
//
// vp-tap TAP-1 prototype eBPF program.
//
// Hooks the sys_enter_write tracepoint, filters in-kernel to writes whose
// first 4 bytes match an HTTP/1.1 method or response prefix ("HTTP"), and
// emits the first MAX_DATA_SIZE bytes of the buffer to userspace via a
// BPF ring buffer.
//
// We intentionally use tracepoints rather than kprobes (the TAP-1 spec said
// kprobes on sys_read/sys_write). Tracepoints have a stable ABI across kernel
// versions, do not depend on the kernel's symbol naming convention
// (`__x64_sys_write` vs `sys_write`), and work without vmlinux.h / CO-RE for
// this minimal context-struct shape. Behavioural coverage is identical for
// the validation criteria.
//
// We only hook sys_enter_write: clients write requests, servers write
// responses — both surface as a write() with HTTP-shaped bytes. That's
// enough for the foundation-gate check ("can we see HTTP at all").

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>

// Per-event payload size, in bytes. Three constraints drive the choice:
//
//   1. An HTTP/1.1 request line and a few headers fit comfortably in 256 B
//      (typical request line is < 100 B). That's all the TAP-1 foundation
//      gate needs to prove.
//   2. Older kernels cap the BPF program stack at 512 B; keeping per-event
//      buffers small leaves room for locals and helper-call frames.
//   3. Power-of-two sizes let the verifier prove `count & (SIZE-1)` is in
//      range without dataflow analysis — see the mask trick below.
//
// Behaviour at the limit: silent truncation. e->len is clamped to
// MAX_DATA_SIZE and the tail of the buffer is discarded. For TAP-1 that's
// acceptable (the request line is intact). Larger captures (~1 MiB body
// coverage) will need a per-CPU scratch map for the read, multiple ringbuf
// events per write, AND TCP-stream reassembly in userspace — HTTP messages
// can be split across write() syscalls by Nagle, app-level buffering, or
// chunked transfer encoding.
#define MAX_DATA_SIZE 256

struct event {
    // cgroup_id is the sole attribution key. It identifies the cgroup of the
    // task that performed the write at the instant the syscall fired — race-
    // free across PID reuse, container restart, and userspace eviction lag.
    // Userspace joins this against a K8s-informer-backed cgroup_id → pod map
    // (see internal/k8s/podinformer). Placed first (8-byte aligned) to avoid
    // struct padding. See VAL-55 §1 for the full rationale.
    __u64 cgroup_id;
    __u32 pid;          // kernel pid (thread id) — diagnostic only
    __u32 tgid;         // userspace getpid() value — diagnostic only
    __u32 len;          // bytes actually copied into data[]
    __u32 fd;           // fd from the write() call
    __u8  data[MAX_DATA_SIZE];
};

// Force `struct event` into the .BTF section so bpf2go's `-type event` flag
// can find it. Without a reference like this the struct gets stripped before
// BTF emission and bpf2go errors with "type name event: not found".
const struct event *unused_event __attribute__((unused));

struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 8 * 1024 * 1024); // 8 MiB
} events SEC(".maps");

// Context layout for tracepoint/syscalls/sys_enter_write. Matches the format
// at /sys/kernel/debug/tracing/events/syscalls/sys_enter_write/format. The
// common-fields preamble (first 8 bytes) and the syscall_nr / args[] layout
// are stable kernel ABI for syscall tracepoints.
struct sys_enter_write_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
    __u64 buf;
    __u64 count;
};

// A 4-byte content sniff that filters in-kernel so userspace only sees
// writes that *look* like HTTP/1.1. It is deliberately the cheapest
// possible filter to prove the capture pipeline end-to-end.
//
// Known weaknesses:
//
//   - False positives: any write whose first 4 bytes happen to match an
//     HTTP method or "HTTP". Harmless; userspace re-checks the request
//     line and drops what doesn't parse.
//   - False negatives: anything that isn't HTTP/1.1 in plaintext gets
//     dropped. gRPC (binary HTTP/2 frames), TLS (encrypted), Postgres /
//     Redis / Kafka wire protocols — all invisible.
//   - Adversarial input is *not* a concern at this layer: the tap is a
//     passive observer that never gates anything, so a crafted "GET "
//     prefix just produces a noisy log line. Trust of captured data is a
//     downstream replay-engine concern.
//
// Note: this is content-based pre-filtering, NOT attribution. Attribution
// is done by cgroup_id (see `bpf_get_current_cgroup_id()` below) and joined
// in userspace against a K8s informer that maintains a cgroup_id → pod map.
// We deliberately rejected a (pid,fd) socket-tracking design (eBPF hooks on
// accept4/connect populating an "interesting sockets" BPF map): cgroup_id is
// captured atomically with the syscall, race-free, and doesn't require
// keeping a side-channel map in sync across separate kernel hooks.
//
// Future work: target-service filtering (drop captures for pods not in the
// platform's /api/agent/config target list) lives in userspace, after the
// cgroup_id → pod join — being wrong there cannot crash the kernel. Larger
// payloads and protocols beyond HTTP/1.1 are likewise deferred to userspace
// with real parsers (e.g. golang.org/x/net/http2 with HPACK).
static __always_inline int looks_like_http(const __u8 *p) {
    // First 4 bytes of an HTTP/1.1 request line are:
    //   "GET ", "POST", "PUT ", "HEAD", "DELE", "PATC", "OPTI", "CONN", "TRAC"
    // Responses start with "HTTP".
    if (p[0] == 'G' && p[1] == 'E' && p[2] == 'T' && p[3] == ' ') return 1;
    if (p[0] == 'P' && p[1] == 'O' && p[2] == 'S' && p[3] == 'T') return 1;
    if (p[0] == 'P' && p[1] == 'U' && p[2] == 'T' && p[3] == ' ') return 1;
    if (p[0] == 'H' && p[1] == 'E' && p[2] == 'A' && p[3] == 'D') return 1;
    if (p[0] == 'D' && p[1] == 'E' && p[2] == 'L' && p[3] == 'E') return 1;
    if (p[0] == 'P' && p[1] == 'A' && p[2] == 'T' && p[3] == 'C') return 1;
    if (p[0] == 'O' && p[1] == 'P' && p[2] == 'T' && p[3] == 'I') return 1;
    if (p[0] == 'C' && p[1] == 'O' && p[2] == 'N' && p[3] == 'N') return 1;
    if (p[0] == 'T' && p[1] == 'R' && p[2] == 'A' && p[3] == 'C') return 1;
    if (p[0] == 'H' && p[1] == 'T' && p[2] == 'T' && p[3] == 'P') return 1; // response
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_write")
int trace_sys_enter_write(struct sys_enter_write_args *ctx) {
    __u64 count = ctx->count;
    if (count < 16) return 0;

    __u8 prefix[4] = {};
    if (bpf_probe_read_user(&prefix, sizeof(prefix), (void *)ctx->buf) != 0)
        return 0;
    if (!looks_like_http(prefix)) return 0;

    struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (!e) return 0;

    // bpf_get_current_cgroup_id() returns the cgroup ID of the task that
    // triggered this tracepoint, captured atomically with the syscall. This
    // is the foundation of cgroup-ID-based attribution: userspace joins this
    // value against a cgroup_id → pod map maintained by a K8s informer in
    // internal/k8s/podinformer.
    e->cgroup_id = bpf_get_current_cgroup_id();

    __u64 id = bpf_get_current_pid_tgid();
    e->pid  = (__u32)id;
    e->tgid = (__u32)(id >> 32);
    e->fd   = (__u32)ctx->fd;
    e->len  = count > MAX_DATA_SIZE ? MAX_DATA_SIZE : (__u32)count;

    // Verifier insists on a bounded constant for the read length; bound
    // again with a mask so the verifier can prove the access stays in range.
    __u32 to_read = e->len & (MAX_DATA_SIZE - 1);
    if (to_read == 0) to_read = MAX_DATA_SIZE - 1;
    bpf_probe_read_user(&e->data, to_read, (void *)ctx->buf);

    bpf_ringbuf_submit(e, 0);
    return 0;
}

char LICENSE[] SEC("license") = "Dual BSD/GPL";
