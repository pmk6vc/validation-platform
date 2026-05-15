//go:build ignore

// SPDX-License-Identifier: BSD-2-Clause
//
// vp-tap Phase 1 eBPF program — extended from the TAP-1 prototype.
//
// Hooks four syscall tracepoints (all stable kernel ABI):
//
//   sys_enter_write   — emits KIND_WRITE with up to MAX_SEGMENT outbound bytes
//   sys_enter_read    — stashes the read buffer pointer + fd keyed by task id
//   sys_exit_read     — looks up the stashed pointer, reads up to args->ret
//                       inbound bytes, emits KIND_READ
//   sys_enter_close   — emits KIND_CLOSE so userspace invalidates its
//                       per-(pid, fd) reassembly buffer
//
// Why three event kinds emitted from four tracepoints: sys_exit_read carries
// the bytes-returned value but NOT the fd or buffer pointer (those live in
// the syscall's *entry* context). The standard pattern — used by Pixie,
// Beyla, Coroot — is to stash (buf, fd) on enter and consume on exit using
// pid_tgid as the key (a task is single-threaded through one syscall, so
// pid_tgid is unambiguous for the lifetime of one read()).
//
// Why tracepoints (not kprobes): stable ABI across kernels; no CO-RE
// dependency beyond BTF; TAP-1 already established this choice and the
// kernel-compat matrix in RESEARCH §2b assumes it.
//
// Attribution model (LOCKED, see CONTEXT.md D-05): cgroup_id is the
// canonical pod-attribution key (VAL-55). (pid, fd) is a transient
// connection-correlation key carried on each event; userspace stitches
// segments per-(pid, fd) into HTTP messages.

#include <linux/bpf.h>
#include <bpf/bpf_helpers.h>

// MAX_SEGMENT — per-event payload cap. 4096 (page size) matches the L7
// capture convention (Pixie, Beyla, Coroot). HTTP bodies larger than this
// span multiple ringbuf events keyed by the same (pid, fd); userspace
// reassembly stitches them.
#define MAX_SEGMENT 4096

// Event kinds reported to userspace (single byte; struct has padding).
#define KIND_WRITE 1
#define KIND_READ  2
#define KIND_CLOSE 3

// Wire-format event emitted to the ringbuf. Layout must match the Go
// binary.Read decoder in cmd/vp-tap/main.go. bpf2go generates the Go
// mirror from the BTF debug info; field order matters.
struct event {
    __u64 cgroup_id;             // canonical attribution key (VAL-55)
    __u64 ts_ns;                 // bpf_ktime_get_ns() — monotonic
    __u32 pid;                   // kernel pid (thread id)
    __u32 tgid;                  // userspace getpid()
    __u32 fd;                    // fd from the syscall — connection correlation
    __u32 len;                   // bytes copied into data[] (0 for KIND_CLOSE)
    __u8  kind;                  // KIND_WRITE / KIND_READ / KIND_CLOSE
    __u8  _pad[7];               // align data[] to 8 bytes
    __u8  data[MAX_SEGMENT];
};

// Force `struct event` into the .BTF section so bpf2go's `-type event`
// can find it. Without a reference the struct is stripped pre-emission.
const struct event *unused_event __attribute__((unused));

// Ring buffer for userspace consumption. 32 MiB per RESEARCH.md §2a:
// medium-envelope load (50 services × ~1k RPS = ~50k events/sec/node) at
// ~4 KiB/event = ~200 MiB/sec; 32 MiB absorbs a ~150 ms userspace stall.
struct {
    __uint(type, BPF_MAP_TYPE_RINGBUF);
    __uint(max_entries, 32 * 1024 * 1024);
} events SEC(".maps");

// sys_enter_read stash: (buf, fd) keyed by pid_tgid (a task can only be in
// one read() syscall at a time). sys_exit_read consumes + deletes.
struct read_state {
    __u64 buf;
    __u32 fd;
    __u32 _pad;
};

struct {
    __uint(type, BPF_MAP_TYPE_HASH);
    __uint(max_entries, 65536);
    __type(key, __u64);          // pid_tgid
    __type(value, struct read_state);
} read_buffers SEC(".maps");

// Syscall tracepoint context structs. Layout matches
// /sys/kernel/debug/tracing/events/syscalls/*/format — stable kernel ABI.

struct sys_enter_write_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
    __u64 buf;
    __u64 count;
};

struct sys_enter_read_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
    __u64 buf;
    __u64 count;
};

struct sys_exit_read_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __s64 ret; // bytes read, or negative errno
};

struct sys_enter_close_args {
    __u64 unused;
    __s32 syscall_nr;
    __u32 _pad;
    __u64 fd;
};

// In-kernel HTTP/1.1 pre-filter — same shape as the TAP-1 prototype, kept
// on both write and read sides. False positives are harmless (userspace
// parser re-checks). False negatives drop non-HTTP/1.1 plaintext (gRPC /
// TLS — Phase 2 widens this via HTTP/2 dissection in userspace).
static __always_inline int looks_like_http(const __u8 *p) {
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

// Fill the common header fields on a reserved event.
static __always_inline void fill_header(struct event *e, __u8 kind, __u32 fd) {
    e->cgroup_id = bpf_get_current_cgroup_id();
    e->ts_ns     = bpf_ktime_get_ns();
    __u64 id = bpf_get_current_pid_tgid();
    e->pid  = (__u32)id;
    e->tgid = (__u32)(id >> 32);
    e->fd   = fd;
    e->kind = kind;
}

SEC("tracepoint/syscalls/sys_enter_write")
int trace_sys_enter_write(struct sys_enter_write_args *ctx) {
    __u64 count = ctx->count;
    if (count < 16) return 0; // shorter than the smallest HTTP request line

    __u8 prefix[4] = {};
    if (bpf_probe_read_user(&prefix, sizeof(prefix), (void *)ctx->buf) != 0)
        return 0;
    if (!looks_like_http(prefix)) return 0;

    struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (!e) return 0;

    fill_header(e, KIND_WRITE, (__u32)ctx->fd);
    e->len = count > MAX_SEGMENT ? MAX_SEGMENT : (__u32)count;

    __u32 to_read = e->len & (MAX_SEGMENT - 1);
    if (to_read == 0) to_read = MAX_SEGMENT - 1;
    bpf_probe_read_user(&e->data, to_read, (void *)ctx->buf);

    bpf_ringbuf_submit(e, 0);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_read")
int trace_sys_enter_read(struct sys_enter_read_args *ctx) {
    __u64 id = bpf_get_current_pid_tgid();
    struct read_state st = { .buf = ctx->buf, .fd = (__u32)ctx->fd };
    bpf_map_update_elem(&read_buffers, &id, &st, BPF_ANY);
    return 0;
}

SEC("tracepoint/syscalls/sys_exit_read")
int trace_sys_exit_read(struct sys_exit_read_args *ctx) {
    __u64 id = bpf_get_current_pid_tgid();
    struct read_state *st = bpf_map_lookup_elem(&read_buffers, &id);
    if (!st) return 0;

    __u64 buf = st->buf;
    __u32 fd = st->fd;
    bpf_map_delete_elem(&read_buffers, &id);

    __s64 ret = ctx->ret;
    if (ret < 16) return 0; // partial / errno / too small to be HTTP

    __u8 prefix[4] = {};
    if (bpf_probe_read_user(&prefix, sizeof(prefix), (void *)buf) != 0)
        return 0;
    if (!looks_like_http(prefix)) return 0;

    struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (!e) return 0;

    fill_header(e, KIND_READ, fd);
    e->len = (__u64)ret > MAX_SEGMENT ? MAX_SEGMENT : (__u32)ret;

    __u32 to_read = e->len & (MAX_SEGMENT - 1);
    if (to_read == 0) to_read = MAX_SEGMENT - 1;
    bpf_probe_read_user(&e->data, to_read, (void *)buf);

    bpf_ringbuf_submit(e, 0);
    return 0;
}

SEC("tracepoint/syscalls/sys_enter_close")
int trace_sys_enter_close(struct sys_enter_close_args *ctx) {
    struct event *e = bpf_ringbuf_reserve(&events, sizeof(*e), 0);
    if (!e) return 0;

    fill_header(e, KIND_CLOSE, (__u32)ctx->fd);
    e->len = 0;
    // data[] left uninitialized; userspace ignores it when kind == KIND_CLOSE

    bpf_ringbuf_submit(e, 0);
    return 0;
}

char LICENSE[] SEC("license") = "Dual BSD/GPL";
