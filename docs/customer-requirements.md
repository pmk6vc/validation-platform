# Customer-Facing Requirements

What customers need to provide for the validation platform to capture, replay, and validate their services.

> **Status legend**
> - **Required** — capture/replay will not work without it.
> - **Recommended** — works without it but with reduced coverage or more onboarding friction.
> - **Optional** — unlocks additional features.

---

## Cluster requirements

### Production cluster (where traffic is captured)

| Requirement | Status | Notes |
|---|---|---|
| Kubernetes cluster (any flavor: GKE, EKS, AKS, kOps, k3s, …) | **Required** | The validation agent runs as a standalone pod in your cluster. |
| Outbound HTTPS access from the agent's pod to the platform's Cloud Run URLs | **Required** | The agent pushes captured traffic to the platform; the platform never reaches into your cluster. |
| Kubeshark installed via Helm (the agent connects to `kubeshark-front` over WebSocket) | **Required** | We install it for you in the sandbox via `scripts/sandbox-up.sh`; in your cluster you can install it via the official chart. |
| RBAC: agent's `ServiceAccount` has cluster-wide `list`/`watch` on `services` | **Required** | The agent discovers your Services from the K8s API. See `k8s/agent/overlays/sandbox/rbac.yaml` for the manifests. |

### Staging cluster (where replays happen)

| Requirement | Status | Notes |
|---|---|---|
| Staging environment with real dependencies (DB, queues, caches) wired up | **Required** | The platform pivoted away from PCAP-based dependency mocking; we replay against your staging stack. |
| Deployment mechanism for candidate versions (image tag swap, Helm, kustomize, etc.) | **Required** | Replay engine deploys the candidate before running the candidate replay pass. |
| DB reset hook between replay runs | **Optional** | Without it, the replay engine runs in **read-only mode** (skips `POST/PUT/PATCH/DELETE`). With it, full replay including writes is supported. |

---

## Service requirements (per service you want validated)

### **Required: `app=<service-name>` pod label**

Every pod backing a K8s Service that you want validated **must** carry a label `app: <service-name>`, where `<service-name>` matches the K8s Service's `metadata.name`.

**Example**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api-gateway              # ← service name
  namespace: production
spec:
  selector:
    app: api-gateway             # ← must include `app=<service-name>`
    version: v1                  # additional selector keys are fine
  ports:
    - port: 8080
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: production
spec:
  template:
    metadata:
      labels:
        app: api-gateway         # ← pod label must equal service name
        version: v1
    spec:
      containers:
        - name: api-gateway
          image: ...
```

**Why this label?** The validation agent uses pod labels to filter and attribute captured traffic both server-side (Kubeshark KFL filter) and client-side (mapping HTTP entries → service IDs). Pod names (`<deployment>-<rs-hash>-<pod-hash>`) regenerate on every rollout, so they're not a stable identifier. The K8s Service's own `spec.selector` is — and `app=<name>` is the dominant K8s convention used by Helm charts, `kubectl create deployment`, and the Kubernetes documentation.

**What happens if the label is missing?**

The agent logs a warning per discovery tick and **skips the service** — it is not registered with the platform, no traffic is captured for it, and it does not appear in the platform's Services API. Example log line:

```
WARN  K8sServiceDiscovery — Skipping service production/foo —
      pod selector lacks 'app=foo' label (selector: {tier=backend})
```

To resolve: add `app: <service-name>` to both the Service's `spec.selector` and the Deployment's pod template `metadata.labels`. The agent picks up the change on the next discovery tick (~60s; configurable via the platform's `/api/agent/config` endpoint).

---

## Endpoint classification (Optional)

Mark specific HTTP endpoints as **read-safe** or **write-mutating** to let the replay engine include or exclude them from read-only runs. Without this, the replay engine uses HTTP-method defaults: `GET`/`HEAD` are read-safe; everything else is treated as a write.

This is particularly useful for `POST` endpoints that are conceptually reads (search, query, complex filters) — without classification, they're skipped in read-only mode.

---

## Network architecture

The validation agent only needs **outbound** network access:

```
Customer cluster (your network)         Platform (Cloud Run)
  ┌──────────────────────────┐
  │ kubeshark (eBPF)         │
  │   ↓                      │
  │ validation-agent ────────┼─→ platform.run.app:443  (config, service registration)
  │                          ├─→ collector.run.app:443  (captured traffic, gzip)
  └──────────────────────────┘
```

The platform never initiates a connection into your cluster. There is no inbound port to open, no VPN, no reverse tunnel.

---

## Quick checklist

- [ ] Kubernetes cluster running, `kubectl` access from the agent pod
- [ ] Outbound HTTPS to the platform + collector Cloud Run URLs allowed
- [ ] Kubeshark installed (Helm chart `kubeshark/kubeshark`)
- [ ] Agent `ServiceAccount` granted `list`/`watch` on `services` cluster-wide
- [ ] Each service-to-validate has `app=<service-name>` label on its pods
- [ ] (Replay) staging cluster with real dependencies provisioned
- [ ] (Replay) deployment hook to swap candidate image tag

---

## See also

- `CLAUDE.md` — internal architecture, module boundaries, design decisions
- `k8s/agent/` — agent Kubernetes manifests (base + sandbox overlay)
- `scripts/sandbox-up.sh` — reference end-to-end deployment script
