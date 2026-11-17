package com.giraffix.concept.service;

import com.giraffix.concept.entity.Concept;
import com.giraffix.selection.JsonSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class ConceptLibrary {
    private final JdbcTemplate db;
    private final JsonSupport json;
    private final ConceptAnalysisValidator validator;
    private final List<String> admins;
    public ConceptLibrary(JdbcTemplate db, JsonSupport json, ConceptAnalysisValidator validator,
                          @Value("${giraffix.selection.admin-subjects:}") String admins) {
        this.db = db; this.json = json; this.validator = validator;
        this.admins = Arrays.stream(admins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    public void register(Concept concept) {
        db.update("INSERT INTO concept_revisions(id,concept_id,revision,analysis_json,status,created_at) VALUES(?,?,1,?,'DRAFT',?)",
                concept.getId(), concept.getId(), concept.getAnalysisJson(), Timestamp.from(concept.getCreatedAt()));
    }
    private void admin(String subject) {
        if (!admins.contains(subject)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Concept review requires a configured administrator");
    }
    @Transactional
    public Revision review(String subject, UUID concept, UUID revision, boolean approve) {
        admin(subject);
        // Lock stable identity for both revocation and selection reservation.
        var rows = db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE", concept);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept not found");
        var revisions = db.query("SELECT id,concept_id,revision,analysis_json,status FROM concept_revisions WHERE id=? AND concept_id=?",
                (r,n) -> new Revision(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getString(4),r.getString(5)), revision, concept);
        if (revisions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Revision not found");
        var current = revisions.get(0);
        var analysis = json.mapper.readTree(current.analysisJson());
        validator.validate(analysis);
        if (approve && !analysis.path("usable").asBoolean()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Unusable references cannot be approved");
        if (approve) db.update("UPDATE concept_revisions SET status='REVOKED' WHERE concept_id=? AND status='APPROVED'", concept);
        String status = approve ? "APPROVED" : "REVOKED";
        db.update("UPDATE concept_revisions SET status=?,reviewed_by=? WHERE id=?", status, subject, revision);
        return new Revision(current.id(), concept, current.revision(), current.analysisJson(), status);
    }
    @Transactional
    public Revision revise(String subject, UUID concept, tools.jackson.databind.JsonNode analysis) {
        admin(subject);
        validator.validate(analysis);
        if (db.queryForList("SELECT id FROM concepts WHERE id=? FOR UPDATE",concept).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept not found");
        int next = db.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM concept_revisions WHERE concept_id=?",Integer.class,concept);
        var id=UUID.randomUUID(); String content=json.write(analysis);
        db.update("INSERT INTO concept_revisions(id,concept_id,revision,analysis_json,status,created_at) VALUES(?,?,?,?,'DRAFT',?)",
                id,concept,next,content,Timestamp.from(Instant.now()));
        return new Revision(id,concept,next,content,"DRAFT");
    }
    @Transactional(readOnly=true)
    public List<Revision> revisions(String subject, UUID concept) {
        admin(subject);
        return db.query("SELECT id,concept_id,revision,analysis_json,status FROM concept_revisions WHERE concept_id=? ORDER BY revision DESC",
                (r,n)->new Revision(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getString(4),r.getString(5)),concept);
    }
    public record Revision(UUID id, UUID conceptId, int revision, String analysisJson, String status) {}
}
