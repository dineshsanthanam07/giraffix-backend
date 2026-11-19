CREATE TABLE prompt_templates (
    prompt_key VARCHAR(80) PRIMARY KEY,
    active_version INTEGER
);
CREATE TABLE prompt_template_revisions (
    prompt_key VARCHAR(80) NOT NULL REFERENCES prompt_templates(prompt_key),
    version INTEGER NOT NULL CHECK(version > 0),
    content TEXT NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(prompt_key, version)
);
ALTER TABLE prompt_templates ADD CONSTRAINT fk_active_prompt_version
    FOREIGN KEY(prompt_key, active_version) REFERENCES prompt_template_revisions(prompt_key, version);
CREATE TABLE prompt_publications (
    id UUID PRIMARY KEY,
    prompt_key VARCHAR(80) NOT NULL,
    version INTEGER NOT NULL,
    previous_version INTEGER NOT NULL,
    published_by VARCHAR(255) NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY(prompt_key, version) REFERENCES prompt_template_revisions(prompt_key, version)
);
CREATE INDEX idx_prompt_publication_history ON prompt_publications(prompt_key, published_at);
INSERT INTO prompt_templates(prompt_key) VALUES ('product-analysis'),('concept-analysis'),('concept-picker'),('ad-creative-director');
-- Existing requests/jobs keep the original bundled creative-director prompt.
ALTER TABLE selection_requests ADD COLUMN director_prompt_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE generation_jobs ADD COLUMN director_prompt_version INTEGER NOT NULL DEFAULT 1;
