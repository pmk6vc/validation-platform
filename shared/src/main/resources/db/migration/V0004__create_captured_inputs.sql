CREATE TABLE captured_inputs (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES services(id),
    input_type VARCHAR(50) NOT NULL,
    classification VARCHAR(50) NOT NULL,
    method VARCHAR(20),
    url TEXT,
    request_headers JSONB,
    request_body TEXT,
    response_status INTEGER,
    response_headers JSONB,
    response_body TEXT,
    latency_ms BIGINT,
    source_ip VARCHAR(45),
    destination_ip VARCHAR(45),
    captured_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_captured_inputs_service_id ON captured_inputs(service_id);
CREATE INDEX idx_captured_inputs_input_type ON captured_inputs(input_type);
CREATE INDEX idx_captured_inputs_classification ON captured_inputs(classification);
CREATE INDEX idx_captured_inputs_captured_at ON captured_inputs(captured_at);
