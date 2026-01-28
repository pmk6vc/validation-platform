CREATE TABLE services (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    cluster VARCHAR(255) NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    provider VARCHAR(50),
    discovered_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    metadata JSONB,
    CONSTRAINT uq_service_identity UNIQUE (organization_id, cluster, namespace, name)
);

CREATE INDEX idx_services_organization_id ON services(organization_id);
CREATE INDEX idx_services_cluster ON services(cluster);
CREATE INDEX idx_services_namespace ON services(namespace);
