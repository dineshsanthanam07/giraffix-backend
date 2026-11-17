ALTER TABLE brands ADD COLUMN logo_url TEXT;
ALTER TABLE brands ADD COLUMN logo_bucket VARCHAR(255);
ALTER TABLE brands ADD COLUMN logo_key VARCHAR(1024);
ALTER TABLE brands ADD COLUMN logo_media_type VARCHAR(100);
ALTER TABLE brands ADD COLUMN logo_size_bytes BIGINT;

ALTER TABLE brands ADD CONSTRAINT ck_brand_logo_metadata CHECK (
    (logo_url IS NULL AND logo_bucket IS NULL AND logo_key IS NULL
        AND logo_media_type IS NULL AND logo_size_bytes IS NULL)
    OR (logo_url IS NOT NULL AND logo_bucket IS NOT NULL AND logo_key IS NOT NULL
        AND logo_media_type IS NOT NULL AND logo_size_bytes IS NOT NULL AND logo_size_bytes > 0)
);
