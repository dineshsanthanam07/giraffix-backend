ALTER TABLE selection_requests ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE concept_evaluations ADD COLUMN compatibility_score SMALLINT CHECK(compatibility_score BETWEEN 0 AND 100);
ALTER TABLE concept_evaluations ADD COLUMN classification VARCHAR(16)
    CHECK(classification IN ('STRONG','CONDITIONAL','NOT_SUITABLE'));
ALTER TABLE concept_evaluations ADD CONSTRAINT ck_picker_evaluation
    CHECK((compatibility_score IS NULL AND classification IS NULL)
       OR (compatibility_score IS NOT NULL AND classification IS NOT NULL));

CREATE TABLE product_concept_exclusions (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    concept_id UUID NOT NULL REFERENCES concepts(id),
    revision_id UUID NOT NULL REFERENCES concept_revisions(id),
    compatibility_score SMALLINT NOT NULL CHECK(compatibility_score BETWEEN 0 AND 100),
    classification VARCHAR(16) NOT NULL CHECK(classification IN ('STRONG','CONDITIONAL','NOT_SUITABLE')),
    minimum_score SMALLINT NOT NULL CHECK(minimum_score BETWEEN 0 AND 100),
    request_id UUID NOT NULL REFERENCES selection_requests(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(product_id, concept_id)
);
