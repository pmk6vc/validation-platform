# Cluster Agent Architecture Plan

## Overview

Implement cluster connectivity for a SaaS model where customers register their Kubernetes clusters and a lightweight agent pushes data to the platform. The agent is a **transport layer** - it collects raw data and declares which adapter type should normalize it.

## Key Architecture Decisions

### 1. Agent as Transport, Not Adapter

The agent does **not** become a new adapter type. Instead:
- Agent collects raw K8s data (services, namespaces, etc.)
- Agent POSTs to platform with `"adapterType": "kubernetes"`
- Platform uses existing `KubernetesAdapter` normalization logic
- When Pixie support is added, agent POSTs raw Pixie data with `"adapterType": "pixie"`

This keeps the adapter pattern clean - adapters normalize technology-specific data into the unified model, regardless of whether it came from direct API access or agent push.

### 2. Dedicated Endpoints per Data Type

Agent POSTs to dedicated endpoints (`/api/agent/services`, `/api/agent/traffic`, etc.) because:
- Service topology vs traffic samples have different characteristics
- Independent scaling and rate limiting per type
- Simpler, focused schemas

### 3. Agent in This Project

The agent lives in this repository (e.g., `agent/` directory) and is prioritized early to enable end-to-end testing with the existing k3s test infrastructure.

### 4. Single Agent with Pluggable Collectors

One agent binary with pluggable collectors that consumers enable via config:
- **Base agent**: Handles auth, batching, publishing, heartbeat
- **Collectors**: K8sCollector (now), PixieCollector (future), OTelCollector (future)
- **Config**: Consumer specifies `collectors: [kubernetes]` or `collectors: [kubernetes, pixie]`

Benefits:
- Simple deployment for consumers (one agent, one token)
- Modular codebase (add collectors without restructuring)
- Collectors can have different requirements documented
- Consumer enables only what they need

---

## Implementation Plan

### Phase 1: Cluster Infrastructure + Agent Foundation

**Goal**: Register clusters, build agent, test end-to-end with k3s test services.

#### 1.1 Data Models

**Cluster** (`src/main/kotlin/com/platform/models/Cluster.kt`):
```kotlin
data class Cluster(
    val id: String,
    val organizationId: String,
    val name: String,
    val displayName: String?,
    val agentToken: String,           // Hashed in DB
    val status: ClusterStatus,        // PENDING, CONNECTED, DISCONNECTED, ERROR
    val lastHeartbeatAt: Instant?,
    val agentVersion: String?,
    val kubernetesVersion: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val metadata: Map<String, String>?
)

enum class ClusterStatus { PENDING, CONNECTED, DISCONNECTED, ERROR }
```

**Agent request models** (`src/main/kotlin/com/platform/models/agent/`):
```kotlin
// POST /api/agent/services
data class AgentServicesRequest(
    val clusterId: String,
    val agentVersion: String,
    val adapterType: String,          // "kubernetes", "pixie", etc.
    val generation: Long,             // Monotonic counter for ordering
    val timestamp: Instant,
    val checksum: String,
    val services: List<RawK8sService> // Raw K8s data, NOT normalized
)

// Raw K8s service - mirrors K8s API structure
data class RawK8sService(
    val metadata: K8sObjectMeta,
    val spec: K8sServiceSpec
)
```

#### 1.2 Database Migrations

**V0004__create_clusters_table.sql**:
```sql
CREATE TABLE clusters (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    agent_token_hash VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    last_heartbeat_at TIMESTAMP,
    agent_version VARCHAR(50),
    kubernetes_version VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    metadata JSONB,
    CONSTRAINT uq_cluster_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_clusters_organization_id ON clusters(organization_id);
CREATE INDEX idx_clusters_status ON clusters(status);
```

**V0005__create_agent_data_snapshots_table.sql**:
```sql
CREATE TABLE agent_data_snapshots (
    id UUID PRIMARY KEY,
    cluster_id UUID NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
    data_type VARCHAR(50) NOT NULL,       -- 'services', 'namespaces', 'traffic'
    adapter_type VARCHAR(50) NOT NULL,    -- 'kubernetes', 'pixie'
    generation BIGINT NOT NULL,
    received_at TIMESTAMP NOT NULL,
    agent_timestamp TIMESTAMP NOT NULL,
    payload JSONB NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    CONSTRAINT uq_snapshot_generation UNIQUE (cluster_id, data_type, generation)
);

CREATE INDEX idx_snapshots_cluster_type ON agent_data_snapshots(cluster_id, data_type);
```

#### 1.3 Repositories

- `ClusterRepository.kt` - CRUD for clusters, find by token hash
- `AgentDataRepository.kt` - Save/retrieve snapshots with generation ordering

---

### Phase 2: Platform API Endpoints

#### 2.1 Cluster Management (`/api/clusters`)

| Endpoint | Description |
|----------|-------------|
| `POST /api/clusters` | Register cluster, returns agent token (one-time visible) |
| `GET /api/clusters?organizationId=xxx` | List clusters for org |
| `GET /api/clusters/{id}` | Get cluster details |
| `POST /api/clusters/{id}/regenerate-token` | Regenerate agent token |

**POST /api/clusters response**:
```json
{
  "cluster": { "id": "xxx", "name": "prod-us-west", "status": "PENDING" },
  "agentToken": "vp_agent_abc123...",
  "installCommand": "kubectl apply -f https://platform/agent/xxx.yaml"
}
```

#### 2.2 Agent Data Ingestion (`/api/agent`)

| Endpoint | Description |
|----------|-------------|
| `POST /api/agent/services` | Agent pushes K8s services (raw data) |
| `POST /api/agent/namespaces` | Agent pushes namespace list |
| `POST /api/agent/heartbeat` | Lightweight status check |

**Data processing flow**:
1. Validate Bearer token → find cluster
2. Verify checksum
3. Store raw snapshot (only if generation > existing)
4. Route to adapter based on `adapterType`:
   - `"kubernetes"` → use KubernetesAdapter normalization logic
   - `"pixie"` → use PixieAdapter normalization logic (future)
5. Upsert normalized services to Services table
6. Update cluster heartbeat

**POST /api/agent/services request**:
```json
{
  "clusterId": "uuid",
  "agentVersion": "1.0.0",
  "adapterType": "kubernetes",
  "generation": 42,
  "timestamp": "2026-01-30T10:00:00Z",
  "checksum": "sha256...",
  "services": [
    {
      "metadata": {
        "name": "api-gateway",
        "namespace": "production",
        "uid": "k8s-uid",
        "labels": {"app": "api-gateway", "team": "platform"},
        "annotations": {"description": "Main API gateway"}
      },
      "spec": {
        "type": "ClusterIP",
        "clusterIP": "10.0.0.1",
        "ports": [{"name": "http", "port": 8080, "protocol": "TCP"}],
        "selector": {"app": "api-gateway"}
      }
    }
  ]
}
```

---

### Phase 3: Agent Implementation

**Location**: `agent/` directory in this repository

**Language**: Kotlin (reuse platform models, familiar to team)

**Architecture**: Single agent with pluggable collectors

**Structure**:
```
agent/
├── build.gradle.kts
├── src/main/kotlin/com/platform/agent/
│   ├── Agent.kt                    # Main entry point, orchestrates collectors
│   ├── config/
│   │   └── AgentConfig.kt          # Platform URL, token, enabled collectors
│   ├── core/
│   │   ├── Collector.kt            # Collector interface
│   │   └── Publisher.kt            # POSTs data to platform
│   └── collectors/
│       └── kubernetes/             # K8s collector (enabled by default)
│           ├── K8sCollector.kt     # Implements Collector interface
│           ├── ServiceDiscovery.kt # Discovers K8s services
│           └── NamespaceDiscovery.kt
└── src/main/resources/
    └── application.yaml            # Default config
```

**Collector interface**:
```kotlin
interface Collector {
    val name: String                          // "kubernetes", "pixie", etc.
    val adapterType: String                   // Maps to platform adapter
    suspend fun collect(): List<CollectorResult>
}

sealed class CollectorResult {
    data class Services(val services: List<RawK8sService>) : CollectorResult()
    data class Namespaces(val namespaces: List<RawNamespace>) : CollectorResult()
    // Future: Traffic, Metrics, etc.
}
```

**Agent behavior**:
1. Read config (platform URL, cluster ID, agent token, enabled collectors)
2. Initialize enabled collectors (default: `[kubernetes]`)
3. Run collection loop:
   - Each collector runs on its own schedule (services: 30s, heartbeat: 10s)
   - Results batched and POSTed to appropriate `/api/agent/*` endpoint
   - Each request includes `adapterType` from collector
4. Graceful shutdown on SIGTERM

**Configuration**:
```yaml
platform:
  url: https://platform.example.com
  clusterId: ${CLUSTER_ID}
  token: ${AGENT_TOKEN}

collectors:
  enabled: [kubernetes]  # Future: [kubernetes, pixie]
  kubernetes:
    namespaces: []       # Empty = all non-system namespaces
    interval: 30s
```

**Deployment**:
- Builds as Docker image via Jib (like test-services)
- K8s manifest in `k8s/agent/` with ServiceAccount + RBAC
- For testing: deploy to k3s test cluster alongside test workloads

---

### Phase 4: Refactor KubernetesAdapter for Reuse

Extract normalization logic from `KubernetesAdapter` so it can be used both for:
1. Direct K8s API queries (existing flow)
2. Agent-pushed raw data (new flow)

```kotlin
// src/main/kotlin/com/platform/adapters/KubernetesAdapter.kt

class KubernetesAdapter(...) : ServiceAdapter {
    // Existing: queries K8s API directly
    override suspend fun discoverServices(organizationId: String): List<Service> {
        val k8sServices = client.services().inAnyNamespace().list().items
        return k8sServices.mapNotNull { normalizeService(it, organizationId, clusterName) }
    }

    // NEW: Normalize raw K8s data from agent push
    fun normalizeFromAgent(
        rawServices: List<RawK8sService>,
        organizationId: String,
        clusterName: String
    ): List<Service> {
        return rawServices.mapNotNull { normalizeRawService(it, organizationId, clusterName) }
    }
}
```

---

### Phase 5: End-to-End Integration Test

**Test flow**:
1. Start k3s cluster with test workloads (existing `KubernetesWorkloadTestBase`)
2. Create organization and register cluster via API
3. Deploy agent to k3s cluster with generated token
4. Wait for agent to push data
5. Verify services appear in `GET /api/services` with `provider: AGENT`
6. Verify cluster status changes to `CONNECTED`

**Test file**: `src/test/kotlin/com/platform/agent/AgentIntegrationTest.kt`

---

## File Structure

```
validation-platform/
├── agent/                              # NEW: Agent subproject
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/platform/agent/
│       ├── Agent.kt                    # Orchestrates collectors
│       ├── core/
│       │   ├── Collector.kt            # Collector interface
│       │   └── Publisher.kt            # POSTs to platform
│       └── collectors/
│           └── kubernetes/             # K8s collector plugin
│               └── K8sCollector.kt
├── k8s/
│   ├── agent/                          # NEW: Agent deployment manifests
│   │   ├── namespace.yaml
│   │   ├── serviceaccount.yaml
│   │   ├── clusterrole.yaml
│   │   └── deployment.yaml
│   └── test-services/                  # Existing
├── src/main/kotlin/com/platform/
│   ├── adapters/
│   │   └── KubernetesAdapter.kt        # MODIFY: Extract normalization
│   ├── api/
│   │   ├── Routes.kt                   # MODIFY: Wire new routes
│   │   ├── ClusterRoutes.kt            # NEW
│   │   └── AgentRoutes.kt              # NEW
│   ├── database/
│   │   ├── Tables.kt                   # MODIFY: Add Clusters, AgentDataSnapshots
│   │   ├── ClusterRepository.kt        # NEW
│   │   └── AgentDataRepository.kt      # NEW
│   ├── features/
│   │   └── AgentDataProcessor.kt       # NEW: Routes to adapter, upserts
│   ├── models/
│   │   ├── Cluster.kt                  # NEW
│   │   ├── ClusterStatus.kt            # NEW
│   │   ├── Provider.kt                 # MODIFY: Add AGENT
│   │   └── agent/                      # NEW
│   │       ├── AgentServicesRequest.kt
│   │       └── RawK8sService.kt
│   └── security/
│       └── TokenUtils.kt               # NEW
└── src/main/resources/db/migration/
    ├── V0004__create_clusters_table.sql     # NEW
    └── V0005__create_agent_data_snapshots.sql # NEW
```

---

## Verification

1. **Unit tests**:
   - ClusterRepository, AgentDataRepository
   - TokenUtils
   - KubernetesAdapter.normalizeFromAgent()

2. **Integration tests**:
   - Register cluster → POST agent data → services appear
   - Test generation ordering (stale data rejected)
   - Test token authentication

3. **End-to-end test**:
   - Full loop with k3s: agent deployed → discovers test services → platform receives → queryable via API

---

## Implementation Order

1. **Cluster infrastructure**: Models, migrations, ClusterRepository
2. **Cluster API**: POST/GET /api/clusters endpoints
3. **Agent ingestion API**: POST /api/agent/services, /api/agent/heartbeat
4. **Agent**: Build agent subproject, collectors, publisher
5. **Integration**: Refactor KubernetesAdapter, wire AgentDataProcessor
6. **Testing**: Deploy agent to k3s, verify end-to-end

---

## Out of Scope (Future)

- Traffic capture (Pixie adapter)
- WebSocket tunnel for real-time queries
- Cluster health monitoring/alerting
- Agent auto-update mechanism