CREATE TABLE usage_operations (
    id UUID PRIMARY KEY,
    kind VARCHAR(24) NOT NULL CHECK(kind IN ('AD','PRODUCT_ANALYSIS','CONCEPT_ANALYSIS')),
    owner_id UUID NOT NULL,
    product_id UUID,
    brand_id UUID,
    reserved_micros BIGINT NOT NULL CHECK(reserved_micros >= 0),
    accounted_micros BIGINT NOT NULL DEFAULT 0 CHECK(accounted_micros >= 0),
    state VARCHAR(24) NOT NULL,
    outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider_started BOOLEAN NOT NULL DEFAULT FALSE,
    model VARCHAR(255),
    provider_id VARCHAR(255),
    input_tokens BIGINT,
    output_tokens BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(input_tokens IS NULL OR input_tokens >= 0),
    CHECK(output_tokens IS NULL OR output_tokens >= 0)
);
CREATE INDEX idx_usage_created ON usage_operations(created_at,owner_id);
CREATE INDEX idx_usage_owner_created ON usage_operations(owner_id,created_at);
CREATE INDEX idx_usage_product_created ON usage_operations(product_id,created_at);
CREATE INDEX idx_usage_brand_created ON usage_operations(brand_id,created_at);

CREATE TABLE usage_ad_calls (
    operation_id UUID NOT NULL,
    stage VARCHAR(24) NOT NULL,
    model VARCHAR(255),
    provider_id VARCHAR(255),
    input_tokens BIGINT,
    output_tokens BIGINT,
    estimated_micros BIGINT NOT NULL DEFAULT 0 CHECK(estimated_micros >= 0),
    PRIMARY KEY(operation_id,stage),
    CHECK(input_tokens IS NULL OR input_tokens >= 0),
    CHECK(output_tokens IS NULL OR output_tokens >= 0)
);

ALTER TABLE selection_attempts ADD COLUMN model_snapshot VARCHAR(255);
-- Only serializes historical adoption across replicas; never gates user requests.
CREATE TABLE usage_tracking_locks(name VARCHAR(50) PRIMARY KEY);
INSERT INTO usage_tracking_locks(name) VALUES('BACKFILL');
