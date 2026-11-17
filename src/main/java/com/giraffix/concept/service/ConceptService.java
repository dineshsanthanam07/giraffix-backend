package com.giraffix.concept.service;

import com.giraffix.concept.dto.ConceptResponse;
import com.giraffix.concept.entity.Concept;
import com.giraffix.concept.model.ConceptAnalysis;
import com.giraffix.concept.port.ConceptAnalyzer;
import com.giraffix.concept.port.ConceptImageStorage;
import com.giraffix.concept.repository.ConceptRepository;
import com.giraffix.shared.image.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;

@Service
public class ConceptService {
    private static final Logger log = LoggerFactory.getLogger(ConceptService.class);
    private final ConceptAnalyzer analyzer;
    private final ConceptImageStorage storage;
    private final ConceptRepository concepts;
    private final ObjectMapper mapper;
    private final ConceptAnalysisValidator validator;
    private final TransactionTemplate transactions;
    private final long maxImageBytes;
    private final ConceptLibrary library;

    public ConceptService(ConceptAnalyzer analyzer, ConceptImageStorage storage, ConceptRepository concepts,
                          ObjectMapper mapper, ConceptAnalysisValidator validator, PlatformTransactionManager manager,
                          @Value("${giraffix.concept.max-image-size-bytes:5242880}") long maxImageBytes,
                          ConceptLibrary library) {
        this.analyzer = analyzer;
        this.storage = storage;
        this.concepts = concepts;
        this.mapper = mapper;
        this.validator = validator;
        this.transactions = new TransactionTemplate(manager);
        this.maxImageBytes = maxImageBytes;
        this.library = library;
    }

    // External calls happen before the short database transaction. execute() includes
    // commit, so even a commit-time failure triggers compensation of the S3 upload.
    public ConceptResponse create(String creatorSubject, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload one nonempty concept image");
        }
        if (file.getSize() > maxImageBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Concept image exceeds the allowed size");
        }
        ImageProcessor.PreparedImage prepared;
        try {
            prepared = ImageProcessor.prepare(file.getBytes(), maxImageBytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read concept image");
        }
        UUID id = UUID.randomUUID();
        log.info("stage=concept_creation_started conceptId={}", id);
        ConceptAnalysis analysis = analyzer.analyze(prepared.bytes(), prepared.mediaType());
        validator.validate(mapper.valueToTree(analysis));
        String json = mapper.writeValueAsString(analysis);
        var stored = storage.upload(id, prepared.bytes(), prepared.mediaType());
        try {
            var entity = new Concept(id, stored, filename(file), analysis.usable(), json, creatorSubject);
            var response = transactions.execute(status -> {
                var saved = concepts.saveAndFlush(entity);
                library.register(saved);
                return response(saved);
            });
            log.info("stage=concept_saved conceptId={} usable={}", id, analysis.usable());
            return response;
        } catch (RuntimeException ex) {
            try { storage.delete(stored); } catch (RuntimeException cleanup) {
                log.error("stage=concept_image_cleanup_failed conceptId={} bucket={} key={}", id, stored.bucket(), stored.key());
            }
            log.error("stage=concept_save_failed conceptId={} errorType={}", id, ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Concept could not be saved");
        }
    }

    @Transactional(readOnly = true)
    public ConceptResponse get(UUID id) {
        return response(concepts.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept not found")));
    }

    @Transactional(readOnly = true)
    public Page<ConceptResponse> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use page >= 0 and size between 1 and 100");
        }
        return concepts.findAll(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
                .map(this::response);
    }

    private ConceptResponse response(Concept concept) {
        var analysis = mapper.readValue(concept.getAnalysisJson(), ConceptAnalysis.class);
        return new ConceptResponse(concept.getId(), concept.getImageUrl(), concept.getCreatedAt(),
                analysis.usable(), analysis.unusableReason(), analysis.concept());
    }

    private String filename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "image";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "_");
        if (name.isBlank()) return "image";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
