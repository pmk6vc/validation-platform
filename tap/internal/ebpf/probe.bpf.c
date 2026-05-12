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
// the validation criteria. TAP-3 can revisit if needed.
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
// acceptable (the request line is intact). TAP-3 will need larger captures
// (~1 MiB body coverage) which means a per-CPU scratch map for the read,
// multiple ringbuf events per write, AND TCP-stream reassembly in
// userspace — HTTP messages can be split across write() syscalls by
// Nagle, app-level buffering, or chunked transfer encoding.
#define MAX_DATA_SIZE 256

struct event {
    // cgroup_id is the primary attribution key for TAP-3 onward. It identifies
    // the cgroup of the task that performed the write at the instant the
    // syscall fired — race-free across PID reuse, container restart, and
    // userspace eviction lag. See VAL-55 §1 for the full rationale. Placed
    // first (8-byte aligned) to avoid struct padding.
    __u64 cgroup_id;
    __u32 pid;          // kernel pid (thread id) — diagnostic only post-TAP-3
    __u32 tgid;         // userspace getpid() value — diagnostic only post-TAP-3
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

// ============================================================================
// PR2: socket lifecycle tracking
//
// Hooks sys_enter_connect, sys_enter_accept4 + sys_exit_accept4, and
// sys_enter_close to learn which (pid, fd) pairs correspond to live TCP
// sockets and the peer addresses they're connected to. Stores the mapping in
// the `sockets` hash map for PR4 to read at write/read time as the new
// content-agnostic filter ("is this (pid, fd) on our interesting list?").
//
// Lifecycle events are also emitted on a separate ringbuf (`socket_events`)
// so userspace can log them and validate the BPF tracking against expected
// connection patterns. PR4 will read this ringbuf to populate the
// `interesting_cgroups` filter set as new sockets appear.
//
// IPv4 only for the prototype — we capture sa_family for IPv6 but skip
// the 16-byte address parse to keep this PR small. IPv6 follow-up if needed.
// ============================================================================

#define AF_INET 2
#define AF_INET6 10

// User-visible sockaddr_in layout (matches glibc / kernel uapi). We don't
// include the header to keep the build hermetic; the layout is stable ABI.
struct sockaddr_in_ {
    __u16 sin_family;
    __u16 sin_port;     // network byte order — userspace converts to host
    __u32 sin_addr;     // network byte order — store the 4 bytes as-is
};

// conn_info is the value stored in the sockets map. Identifies the peer at
// connection-establishment time so PR4 can join by cgroup_id without
// re-reading sockaddr per write.
struct conn_info {
    __u64 cgroup_id;     // cgroup of the task that opened the socket
    __u64 ts_ns;         // monotonic ns timestamp at connect/accept time
    __u32 family;        // AF_INET or AF_INET6
    __u32 _pad;
    __u16 peer_port;     // network byte order; userspace decodes
    __u8  _pad2[2];
    __u8  peer_addr[16]; // IPv4 in first 4 bytes; full 16 for IPv6 (future)
};

const struct conn_info *unused_conn_info __attribute__((unused));

// socket_event is the per-lifecycle-event userspace payload. Carried on a
// separate ringbuf so the data-flow ringbuf (`events`) isn't polluted by
// connect/close churn that's small and bursty rather than per-syscall.
enum socket_event_kind {
    SOCK_EVT_CONNECT = 1, // outbound connect()
    SOCK_EVT_ACCEPT  = 2, // inbound accept4() returned a new fd
    SOCK_EVT_CLOSE   = 3, // close() of a tracked fd
};

struct socket_event {
    __u64 cgroup_id;
    __u64 ts_ns;
    __u32 pid;
    __u32 tgid;
    __u32 fd;
    __u32 kind;          // socket_event_kind
    __u32 family;
    __u32 _pad;
    __u16 peer_port;     // network byte order
    __u8  _pad2[6];
    __u8  peer_addr[16];
};

const struct socket_event *unused_socket_event __attribute__((unused));

// sockets: keyed by (pid << 32 | fd) so different processes' fd numbers can't
// collide. Populated on connect/accept, removed on close. 64 K capacity is a
// generous upper bound for a single node's live socket count.
struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 65536);
    __type(key, __u64);
    __type(value, struct conn_info);
} sockets SEC(".maps");

// socket_events: lifecycle ringbuf. Small (1 MiB) because socket open/close
// volume is orders of magnitude lower than write/read syscall volume.
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 1 * 1024 * 1024);
} socket_events SEC(".maps");

// accept_args: per-thread temporary that bridges sys_enter_accept4 (where we
// see the sockaddr * out-pointer the kernel will write to) and
// sys_exit_accept4 (where the return value gives us the new fd and the
// sockaddr * has been populated). Keyed by pid_tgid because accept4 is a
// blocking syscall and we need to associate the enter/exit pair on the
// same thread.
struct accept_args {
    __u64 sockaddr_ptr;
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 1024);
    __type(key, __u64);
    __type(value, struct accept_args);
} accept_pending SEC(".maps");

// Helpers ---------------------------------------------------------------------

// Read an AF_INET sockaddr from user memory into ci. Returns 0 on success,
// -1 if the family isn't AF_INET (caller decides whether to drop or keep
// with empty addr).
static __always_inline int read_sockaddr_in(struct conn_info *ci, void *uaddr) {
    struct sockaddr_in_ sa = {};
    if (bpf_probe_read_user(&sa, sizeof(sa), uaddr) != 0) return -1;
    ci->family = sa.sin_family;
    if (sa.sin_family != AF_INET) {
        // Mark family but leave addr/port zeroed. IPv6 path lands here today.
        return 0;
    }
    ci->peer_port = sa.sin_port;
    __builtin_memcpy(ci->peer_addr, &sa.sin_addr, 4);
    return 0;
}

// Emit one socket_event from a conn_info. Drops silently on ringbuf overflow
// (lifecycle events are best-effort; userspace can rebuild from periodic
// dumps if we ever need stricter delivery).
static __always_inline void emit_socket_event(__u32 kind, __u32 fd,
                                              struct conn_info *ci) {
    struct socket_event *e = bpf_ringbuf_reserve(&socket_events, sizeof(*e), 0);
    if (!e) return;
    __u64 id = bpf_get_current_pid_tgid();
    e->cgroup_id = ci->cgroup_id;
    e->ts_ns     = ci->ts_ns;
    e->pid       = (__u32)id;
    e->tgid      = (__u32)(id >> 32);
    e->fd        = fd;
    e->kind      = kind;
    e->family    = ci->family;
    e->peer_port = ci->peer_port;
    __builtin_memcpy(e->peer_addr, ci->peer_addr, 16);
    bpf_ringbuf_submit(e, 0);
}

// Compose the sockets-map key from (pid, fd). The Go side mirrors this.
static __always_inline __u64 sockets_key(__u32 pid, __u32 fd) {
    return ((__u64)pid << 32) | (__u64)fd;
}

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

// TAP-1 only — a 4-byte content sniff that filters in-kernel so userspace
// only sees writes that *look* like HTTP/1.1. It is deliberately the
// cheapest possible filter to prove the capture pipeline end-to-end.
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
// TAP-3 replaces this with socket-based attribution that is independent
// of payload contents:
//
//   1. eBPF hooks on sys_enter_accept4 / sys_enter_connect record live
//      (pid, fd) → (peer addr, sock type) pairs in a BPF hash map.
//   2. Userspace populates an "interesting sockets" map by resolving
//      pid → pod → service-name and checking against the registered
//      target services from the platform's /api/agent/config response.
//   3. On sys_enter_write the kernel program does a single map lookup
//      on (pid, fd). Match → capture; miss → drop. No content sniff.
//
// Protocol detection (HTTP/1.1 vs HTTP/2 vs gRPC vs raw) then moves to
// userspace where being wrong cannot crash the kernel, and a real parser
// (e.g. golang.org/x/net/http2 with HPACK) can be used. The function
// below disappears when TAP-3 lands.
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
    // is the foundation of cgroup-ID-based attribution: subsequent TAP-3 PRs
    // will join this against a userspace cgroup_id → pod map populated by a
    // K8s informer, replacing the current PID-keyed /proc-lookup cache.
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

// ============================================================================
// PR2: socket lifecycle tracepoints
// ============================================================================

// sys_enter_connect args layout: (int fd, struct sockaddr __user *uservaddr,
// int addrlen). connect() takes an existing socket fd and a peer sockaddr.
struct sys_enter_connect_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
    __u64 uservaddr;
    __u64 addrlen;
};

SEC("tracepoint/syscalls/sys_enter_connect")
int trace_sys_enter_connect(struct sys_enter_connect_args *ctx) {
    struct conn_info ci = {};
    ci.cgroup_id = bpf_get_current_cgroup_id();
    ci.ts_ns     = bpf_ktime_get_ns();
    if (read_sockaddr_in(&ci, (void *)ctx->uservaddr) != 0) return 0;

    __u64 id = bpf_get_current_pid_tgid();
    __u32 tgid = (__u32)(id >> 32);
    __u64 k = sockets_key(tgid, (__u32)ctx->fd);
    bpf_map_update_elem(&sockets, &k, &ci, BPF_ANY);
    emit_socket_event(SOCK_EVT_CONNECT, (__u32)ctx->fd, &ci);
    return 0;
}

// sys_enter_accept4 args layout: (int fd, struct sockaddr __user *upeer_sockaddr,
// int __user *upeer_addrlen, int flags). The sockaddr is an *output* pointer
// the kernel populates with the peer's address; we have to wait until exit
// to read it. Stash the pointer between enter and exit.
struct sys_enter_accept4_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
    __u64 upeer_sockaddr;
    __u64 upeer_addrlen;
    __u64 flags;
};

SEC("tracepoint/syscalls/sys_enter_accept4")
int trace_sys_enter_accept4(struct sys_enter_accept4_args *ctx) {
    __u64 id = bpf_get_current_pid_tgid();
    struct accept_args a = { .sockaddr_ptr = ctx->upeer_sockaddr };
    bpf_map_update_elem(&accept_pending, &id, &a, BPF_ANY);
    return 0;
}

// sys_exit_accept4: return value is the new fd (or -errno). Look up the
// stashed sockaddr pointer, read the now-populated peer address.
struct sys_exit_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __s64 ret;
};

SEC("tracepoint/syscalls/sys_exit_accept4")
int trace_sys_exit_accept4(struct sys_exit_args *ctx) {
    __u64 id = bpf_get_current_pid_tgid();
    struct accept_args *a = bpf_map_lookup_elem(&accept_pending, &id);
    if (!a) return 0;
    __u64 sockaddr_ptr = a->sockaddr_ptr;
    bpf_map_delete_elem(&accept_pending, &id);

    if (ctx->ret < 0) return 0; // accept failed
    __u32 new_fd = (__u32)ctx->ret;

    struct conn_info ci = {};
    ci.cgroup_id = bpf_get_current_cgroup_id();
    ci.ts_ns     = bpf_ktime_get_ns();
    if (sockaddr_ptr) {
        if (read_sockaddr_in(&ci, (void *)sockaddr_ptr) != 0) return 0;
    }

    __u32 tgid = (__u32)(id >> 32);
    __u64 k = sockets_key(tgid, new_fd);
    bpf_map_update_elem(&sockets, &k, &ci, BPF_ANY);
    emit_socket_event(SOCK_EVT_ACCEPT, new_fd, &ci);
    return 0;
}

// sys_enter_close args layout: (unsigned int fd). Evict the (pid, fd) entry
// from the sockets map and emit a CLOSE event so userspace can mirror.
// Stale entries are harmless for filtering but cap the map's size.
struct sys_enter_close_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
};

SEC("tracepoint/syscalls/sys_enter_close")
int trace_sys_enter_close(struct sys_enter_close_args *ctx) {
    __u64 id = bpf_get_current_pid_tgid();
    __u32 tgid = (__u32)(id >> 32);
    __u64 k = sockets_key(tgid, (__u32)ctx->fd);
    struct conn_info *ci = bpf_map_lookup_elem(&sockets, &k);
    if (!ci) return 0; // not a tracked socket fd
    emit_socket_event(SOCK_EVT_CLOSE, (__u32)ctx->fd, ci);
    bpf_map_delete_elem(&sockets, &k);
    return 0;
}

char LICENSE[] SEC("license") = "Dual BSD/GPL";
