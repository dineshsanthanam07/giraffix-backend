ALTER TABLE product_images ADD COLUMN storage_url TEXT;
ALTER TABLE product_images ALTER COLUMN image_data DROP NOT NULL;

-- Preserve existing database-backed images. New uploads store S3 references with image_data NULL.
CREATE UNIQUE INDEX uq_product_images_storage_url ON product_images(storage_url);
