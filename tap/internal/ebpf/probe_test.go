//go:build linux

// Package ebpf tests verify the bpf2go-generated bindings load against a
// real kernel + BTF, and that the ringbuf map carries the size we expect
// for medium-envelope load (RESEARCH §2a). All tests in this file require
// Linux + BTF (CO-RE relocations); they self-skip when /sys/kernel/btf/vmlinux
// is missing so macOS/Windows dev hosts stay green.

package ebpf

import (
	"os"
	"testing"
)

// TestProbeObjectsLoad asserts every BPF program declared in probe.bpf.c
// loads via the bpf2go-generated LoadProbeObjects() entry point. If any
// SEC() handler is missing or the BPF verifier rejects the program, this
// test fails with a clear pointer to the failing program.
func TestProbeObjectsLoad(t *testing.T) {
	requireBTF(t)

	objs := ProbeObjects{}
	if err := LoadProbeObjects(&objs, nil); err != nil {
		t.Fatalf("LoadProbeObjects: %v", err)
	}
	defer objs.Close()

	progs := map[string]any{
		"TraceSysEnterWrite": objs.TraceSysEnterWrite,
		"TraceSysEnterRead":  objs.TraceSysEnterRead,
		"TraceSysExitRead":   objs.TraceSysExitRead,
		"TraceSysEnterClose": objs.TraceSysEnterClose,
	}
	for name, prog := range progs {
		if prog == nil {
			t.Errorf("BPF program %s missing from ProbeObjects (SEC() directive may have changed)", name)
		}
	}
}

// TestRingbufMapAttributes asserts the ringbuf size matches RESEARCH §2a's
// 32 MiB target. Drift here would break the under-load drain budget.
func TestRingbufMapAttributes(t *testing.T) {
	requireBTF(t)

	spec, err := LoadProbe()
	if err != nil {
		t.Fatalf("LoadProbe: %v", err)
	}

	const wantMaxEntries = 32 * 1024 * 1024 // 32 MiB
	got := spec.Maps["events"]
	if got == nil {
		t.Fatal("events map missing from CollectionSpec")
	}
	if int(got.MaxEntries) != wantMaxEntries {
		t.Errorf("events.MaxEntries = %d; want %d (RESEARCH §2a)", got.MaxEntries, wantMaxEntries)
	}
}

// TestReadBuffersMapPresent asserts the per-syscall stash map exists; the
// sys_exit_read → sys_enter_read pairing depends on it.
func TestReadBuffersMapPresent(t *testing.T) {
	requireBTF(t)

	spec, err := LoadProbe()
	if err != nil {
		t.Fatalf("LoadProbe: %v", err)
	}
	if spec.Maps["read_buffers"] == nil {
		t.Fatal("read_buffers map missing — sys_exit_read can't pair with sys_enter_read")
	}
}

// requireBTF skips the test if /sys/kernel/btf/vmlinux is unavailable.
// Lets the suite run cleanly on macOS / Windows dev hosts where BPF
// programs can't load anyway.
func requireBTF(t *testing.T) {
	t.Helper()
	if _, err := os.Stat("/sys/kernel/btf/vmlinux"); err != nil {
		t.Skip("requires Linux + BTF (/sys/kernel/btf/vmlinux unavailable)")
	}
}
