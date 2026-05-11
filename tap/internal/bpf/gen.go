// Package bpf hosts the compiled eBPF program and its generated Go bindings.
// bpf2go is invoked via `go generate` and emits probe_bpfel_x86.go +
// probe_bpfel_x86.o (and an arm64 pair we don't use — restrict the target
// list to amd64 since GKE COS in the sandbox is amd64).
package bpf

//go:generate go run github.com/cilium/ebpf/cmd/bpf2go -target amd64 -type event -cflags "-O2 -g -Wall -I/usr/include/x86_64-linux-gnu" Probe probe.bpf.c
