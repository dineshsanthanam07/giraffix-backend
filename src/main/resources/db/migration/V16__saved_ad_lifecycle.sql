-- Preserve attribution even after a product is deleted. No live-product/brand FK.
ALTER TABLE creations ADD COLUMN brand_id UUID;
UPDATE creations SET brand_id=(SELECT p.brand_id FROM products p WHERE p.id=creations.product_id);
CREATE INDEX idx_creation_owner_time ON creations(owner_id,created_at,id);
CREATE INDEX idx_creation_owner_brand ON creations(owner_id,brand_id,created_at,id);

-- Explicit ad deletion has its own durable cleanup intent, independent of product photos.
CREATE TABLE generated_ad_deletions (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    job_id UUID NOT NULL UNIQUE,
    asset_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_error_type VARCHAR(128)
);
CREATE INDEX idx_generated_ad_deletion_work ON generated_ad_deletions(next_attempt_at,lease_until);
