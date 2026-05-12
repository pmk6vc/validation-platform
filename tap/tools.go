//go:build tools

// Package tools tracks build-time tool dependencies so `go mod tidy` keeps
// them in go.mod. The `tools` build tag means none of this is compiled into
// the production binary.
package tools

import (
	_ "github.com/cilium/ebpf/cmd/bpf2go"
)
