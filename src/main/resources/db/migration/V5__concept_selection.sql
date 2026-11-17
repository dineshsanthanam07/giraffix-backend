-- Existing extracted concepts remain drafts. Usability is not administrative approval.
CREATE TABLE concept_revisions (
    id UUID PRIMARY KEY,
    concept_id UUID NOT NULL REFERENCES concepts(id),
    revision INTEGER NOT NULL,
    analysis_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','APPROVED','REVOKED')),
    card_version INTEGER NOT NULL DEFAULT 1,
    reviewed_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(concept_id, revision)
);
INSERT INTO concept_revisions(id, concept_id, revision, analysis_json, status, created_at)
SELECT id, id, 1, analysis_json, 'DRAFT', created_at FROM concepts;
CREATE INDEX idx_revision_status ON concept_revisions(status, concept_id);

CREATE TABLE selection_requests (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    product_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    body_hash VARCHAR(64) NOT NULL,
    context_json TEXT NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    policy_json TEXT NOT NULL,
    manual_concept_id UUID,
    status VARCHAR(40) NOT NULL,
    rounds INTEGER NOT NULL DEFAULT 0,
    verification_calls INTEGER NOT NULL DEFAULT 0,
    retries INTEGER NOT NULL DEFAULT 0,
    spent_micros BIGINT NOT NULL DEFAULT 0,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP WITH TIME ZONE,
    deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    selected_evaluation_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(owner_id, idempotency_key)
);
CREATE INDEX idx_selection_work ON selection_requests(status, lease_until);
CREATE TABLE selection_batches (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES selection_requests(id),
    round INTEGER NOT NULL,
    candidates_json TEXT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(request_id, round)
);
CREATE TABLE selection_seen (
    request_id UUID NOT NULL REFERENCES selection_requests(id),
    concept_id UUID NOT NULL REFERENCES concepts(id),
    PRIMARY KEY(request_id, concept_id)
);
CREATE TABLE selection_attempts (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES selection_requests(id),
    stage VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reserved_micros BIGINT NOT NULL,
    actual_micros BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    provider_id VARCHAR(255),
    input_hash VARCHAR(64),
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE concept_evaluations (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES selection_requests(id),
    batch_id UUID REFERENCES selection_batches(id),
    revision_id UUID NOT NULL REFERENCES concept_revisions(id),
    stage VARCHAR(16) NOT NULL,
    result_json TEXT NOT NULL,
    numerator INTEGER,
    passes BOOLEAN NOT NULL,
    verified_evaluation_id UUID REFERENCES concept_evaluations(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_evaluation_request ON concept_evaluations(request_id, stage);
CREATE TABLE concept_match_cache (
    owner_id UUID NOT NULL,
    context_hash VARCHAR(64) NOT NULL,
    revision_id UUID NOT NULL REFERENCES concept_revisions(id),
    policy_hash VARCHAR(64) NOT NULL,
    evaluation_id UUID NOT NULL REFERENCES concept_evaluations(id),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(owner_id, context_hash, revision_id, policy_hash)
);
CREATE TABLE concept_allocations (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE REFERENCES selection_requests(id),
    owner_id UUID NOT NULL,
    product_id UUID NOT NULL,
    concept_id UUID NOT NULL REFERENCES concepts(id),
    evaluation_id UUID NOT NULL REFERENCES concept_evaluations(id),
    state VARCHAR(16) NOT NULL CHECK(state IN ('RESERVED','CONSUMED','RELEASED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- A separate occupancy table is equivalent to a partial unique index and also
-- works in H2. Released allocations remain in history, but relinquish occupancy.
CREATE TABLE concept_allocation_claims (
    owner_id UUID NOT NULL,
    product_id UUID NOT NULL,
    concept_id UUID NOT NULL REFERENCES concepts(id),
    allocation_id UUID NOT NULL UNIQUE REFERENCES concept_allocations(id),
    PRIMARY KEY(owner_id, product_id, concept_id)
);
CREATE TABLE generation_jobs (
    id UUID PRIMARY KEY,
    allocation_id UUID NOT NULL UNIQUE REFERENCES concept_allocations(id),
    brief_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP WITH TIME ZONE,
    provider_id VARCHAR(255),
    output_json TEXT,
    spent_micros BIGINT NOT NULL DEFAULT 0,
    review_started BOOLEAN NOT NULL DEFAULT FALSE,
    deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE REFERENCES generation_jobs(id),
    delivered BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE creations (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE REFERENCES generation_jobs(id),
    owner_id UUID NOT NULL,
    product_id UUID NOT NULL,
    concept_id UUID NOT NULL REFERENCES concepts(id),
    evaluation_id UUID NOT NULL REFERENCES concept_evaluations(id),
    asset_json TEXT NOT NULL,
    review_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_creation_product ON creations(owner_id, product_id, created_at);
