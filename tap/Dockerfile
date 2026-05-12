# Multi-stage build for the vp-tap TAP-1 prototype.
#
# Stage 1 compiles the eBPF C program to a relocatable ELF (.o) via clang,
# generates Go bindings via cilium/ebpf/cmd/bpf2go, then builds a static
# Go binary. Stage 2 is a minimal runtime — `distroless/static` works
# because Go's net dependencies are pure Go and we set CGO_ENABLED=0.

FROM golang:1.23-bookworm AS builder

# clang + libbpf-dev provide everything bpf2go needs: clang for the BPF
# target compilation and libbpf headers (bpf_helpers.h). libelf-dev is for
# the host-side cilium/ebpf library at build time. linux-libc-dev gives
# us <linux/bpf.h> and friends.
RUN apt-get update && apt-get install -y --no-install-recommends \
        clang \
        llvm \
        libbpf-dev \
        libelf-dev \
        linux-libc-dev \
    && rm -rf /var/lib/apt/lists/*

ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64

WORKDIR /src

# Copy everything in one shot, let go mod tidy generate go.sum, then build.
# bpf2go runs as a `go run` from the cilium/ebpf module, so the module cache
# must be populated before `go generate` executes.
COPY . .
RUN go mod tidy
RUN go generate ./internal/ebpf/...
# Tests are gated by .github/workflows/tap-pr.yml — the image build path
# is for production artifact production only, mirroring how push_main.yml
# builds the Kotlin images without re-running tests.
RUN go build -trimpath -ldflags="-s -w" -o /vp-tap ./cmd/vp-tap

# Pluck ca-certificates from the builder so the runtime image can do TLS
# (TAP-5 will POST to the Cloud Run collector). The static Go binary
# bundles everything else.
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# FROM scratch + ca-certs per TAP-2 spec. Smallest possible runtime — the
# Go binary is self-contained. /proc is mounted by the kubelet inside the
# pod regardless of image contents.
FROM scratch
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /vp-tap /vp-tap
ENTRYPOINT ["/vp-tap"]
