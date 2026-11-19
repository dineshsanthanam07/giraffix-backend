-- Reuse a completed paid review if publication is interrupted.
ALTER TABLE generation_jobs ADD COLUMN review_json TEXT;
ALTER TABLE generation_jobs ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_generation_work ON generation_jobs(status, next_attempt_at, lease_until, created_at);
CREATE INDEX idx_selection_attempt_reuse ON selection_attempts(request_id, stage, input_hash, status, created_at);
