// Package ebpf hosts the compiled eBPF program and its generated Go bindings.
// bpf2go is invoked via `go generate` and emits probe_x86_bpfel.go +
// probe_x86_bpfel.o (and an arm64 pair we don't use — restrict the target
// list to amd64 since GKE COS in the sandbox is amd64).
package ebpf

//go:generate go run github.com/cilium/ebpf/cmd/bpf2go -target amd64 -type event -type socket_event -cflags "-O2 -g -Wall -I/usr/include/x86_64-linux-gnu" Probe probe.bpf.c
