# vp-tap TAP-1 prototype build commands.
#
# Targets:
#   make generate    Run bpf2go to compile the BPF C and emit Go bindings.
#                    Requires clang + libbpf-dev installed locally.
#                    On macOS this only works inside `make docker-build`.
#   make build       Build the Go binary for linux/amd64 from current sources.
#                    Requires `make generate` to have run, OR run inside docker.
#   make docker-build  Build the multi-stage Docker image end-to-end.
#                      This is the only target that works on macOS host.
#   make push        Push the image to the sandbox Artifact Registry.
#   make deploy      Apply the DaemonSet to the current kubectl context.
#   make logs        Tail logs from the DaemonSet's pods.
#   make undeploy    Remove the DaemonSet.

REGISTRY ?= us-central1-docker.pkg.dev/zugzwang-381922/validation
IMAGE    ?= $(REGISTRY)/vp-tap
TAG      ?= prototype

.PHONY: generate build docker-build push deploy logs undeploy

generate:
	cd internal/bpf && go generate

build:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o vp-tap ./cmd/vp-tap

docker-build:
	docker buildx build --platform=linux/amd64 --load -t $(IMAGE):$(TAG) .

push:
	docker push $(IMAGE):$(TAG)

deploy:
	kubectl apply -f ../k8s/tap/daemonset.yaml

logs:
	kubectl logs -n validation -l app=vp-tap -f --max-log-requests=10

undeploy:
	kubectl delete -f ../k8s/tap/daemonset.yaml --ignore-not-found
