ALTER TABLE creations ADD COLUMN quality VARCHAR(16);
ALTER TABLE creations ADD CONSTRAINT ck_creation_quality CHECK(quality IS NULL OR quality IN ('low','medium','high'));

ALTER TABLE generation_jobs ADD COLUMN failure_code VARCHAR(80);
ALTER TABLE generation_jobs ADD COLUMN failure_stage VARCHAR(32);
ALTER TABLE generation_jobs ADD COLUMN failure_message VARCHAR(500);
ALTER TABLE generation_jobs ADD COLUMN failure_provider_id VARCHAR(255);
ALTER TABLE generation_jobs ADD COLUMN failure_stop_reason VARCHAR(80);
