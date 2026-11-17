package com.giraffix.concept.entity;

import com.giraffix.concept.port.ConceptImageStorage.StoredImage;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "concepts")
public class Concept {
    @Id private UUID id;
    @Column(nullable = false, length = 255) private String imageBucket;
    @Column(nullable = false, length = 1024) private String imageKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String imageUrl;
    @Column(nullable = false, length = 255) private String imageFilename;
    @Column(nullable = false, length = 100) private String imageMediaType;
    @Column(nullable = false) private long imageSizeBytes;
    @Column(nullable = false) private boolean usable;
    @Column(nullable = false, columnDefinition = "TEXT") private String analysisJson;
    @Column(nullable = false) private int schemaVersion;
    @Column(nullable = false, length = 255) private String createdBy;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected Concept() {}

    public Concept(UUID id, StoredImage image, String filename, boolean usable,
                   String analysisJson, String createdBy) {
        this.id = id;
        this.imageBucket = image.bucket();
        this.imageKey = image.key();
        this.imageUrl = image.url();
        this.imageFilename = filename;
        this.imageMediaType = image.mediaType();
        this.imageSizeBytes = image.sizeBytes();
        this.usable = usable;
        this.analysisJson = analysisJson;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.schemaVersion = 1;
    }

    public UUID getId() { return id; }
    public String getImageUrl() { return imageUrl; }
    public String getAnalysisJson() { return analysisJson; }
    public Instant getCreatedAt() { return createdAt; }
}
