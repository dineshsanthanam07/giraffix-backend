ALTER TABLE concept_evaluations ADD COLUMN concept_fidelity SMALLINT CHECK(concept_fidelity BETWEEN 0 AND 4);
ALTER TABLE concept_evaluations ADD COLUMN product_fidelity SMALLINT CHECK(product_fidelity BETWEEN 0 AND 4);
ALTER TABLE concept_evaluations ADD COLUMN intent_fit SMALLINT CHECK(intent_fit BETWEEN 0 AND 4);
ALTER TABLE concept_evaluations ADD COLUMN visual_clarity SMALLINT CHECK(visual_clarity BETWEEN 0 AND 4);
ALTER TABLE concept_evaluations ADD COLUMN renderability SMALLINT CHECK(renderability BETWEEN 0 AND 4);
ALTER TABLE concept_evaluations ADD COLUMN verification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
    CHECK(verification_status IN ('PENDING','DONE','INELIGIBLE'));
ALTER TABLE concept_evaluations ADD CONSTRAINT ck_selection_numerator CHECK(numerator BETWEEN 0 AND 400);
ALTER TABLE concept_evaluations ADD CONSTRAINT ck_selection_stage CHECK(stage IN ('SCREEN','VERIFY'));
ALTER TABLE selection_requests ADD CONSTRAINT fk_selection_winner FOREIGN KEY(selected_evaluation_id) REFERENCES concept_evaluations(id);
ALTER TABLE selection_requests ADD CONSTRAINT ck_selection_counters CHECK(rounds >= 0 AND verification_calls >= 0 AND retries >= 0 AND spent_micros >= 0);
