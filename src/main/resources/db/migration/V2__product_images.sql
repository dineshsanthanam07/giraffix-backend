CREATE TABLE product_images (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(100) NOT NULL,
    position INTEGER NOT NULL,
    size_bytes BIGINT NOT NULL,
    image_data BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_product_image_position UNIQUE (product_id, position)
);

-- The product_id prefix of the unique index supports loading a product's images.
-- Existing products.image_url values are preserved as external legacy URLs.
