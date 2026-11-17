ALTER TABLE generation_jobs ADD COLUMN prompt_text TEXT;
ALTER TABLE generation_jobs ADD COLUMN prompt_model VARCHAR(255);
ALTER TABLE generation_jobs ADD COLUMN prompt_version VARCHAR(80);
ALTER TABLE generation_jobs ADD COLUMN prompt_provider_id VARCHAR(255);
ALTER TABLE generation_jobs ADD COLUMN prompt_cost_micros BIGINT NOT NULL DEFAULT 0;

ALTER TABLE creations ADD COLUMN aspect_ratio VARCHAR(16);
ALTER TABLE creations ADD COLUMN image_prompt TEXT;
ALTER TABLE creations ADD COLUMN prompt_version VARCHAR(80);

ALTER TABLE generation_jobs ADD CONSTRAINT ck_prompt_cost CHECK(prompt_cost_micros >= 0);
ALTER TABLE creations ADD CONSTRAINT ck_creation_aspect_ratio
    CHECK(aspect_ratio IS NULL OR aspect_ratio IN ('1:1','4:5','3:4','16:9','9:16'));
