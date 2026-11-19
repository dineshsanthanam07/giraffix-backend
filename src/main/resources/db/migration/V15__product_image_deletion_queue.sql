-- Independent of products: cleanup must survive deletion of the source row.
CREATE TABLE product_image_deletions (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    bucket VARCHAR(255) NOT NULL,
    object_key TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts >= 0),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    last_error_type VARCHAR(128)
);
CREATE INDEX idx_product_image_deletion_work ON product_image_deletions(next_attempt_at, lease_until);
