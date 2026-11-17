CREATE TABLE concepts (
    id UUID PRIMARY KEY,
    image_bucket VARCHAR(255) NOT NULL,
    image_key VARCHAR(1024) NOT NULL,
    image_url TEXT NOT NULL,
    image_filename VARCHAR(255) NOT NULL,
    image_media_type VARCHAR(100) NOT NULL,
    image_size_bytes BIGINT NOT NULL,
    usable BOOLEAN NOT NULL,
    analysis_json TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_concepts_created_at ON concepts(created_at);
CREATE INDEX idx_concepts_usable ON concepts(usable);
-- This module owns its table. created_by is an identity subject, with no
-- cross-module foreign key or entity relationship, to support later extraction.
