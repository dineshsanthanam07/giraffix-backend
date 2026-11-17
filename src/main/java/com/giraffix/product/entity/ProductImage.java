package com.giraffix.product.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_images")
public class ProductImage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private String filename;
    @Column(nullable = false, length = 100)
    private String mediaType;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private long sizeBytes;

    @Column(columnDefinition = "TEXT")
    private String storageUrl;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductImage() {}

    public ProductImage(Product product, String filename, String mediaType, int position, long sizeBytes,
                        String storageUrl) {
        this.product = product;
        this.filename = filename;
        this.mediaType = mediaType;
        this.position = position;
        this.sizeBytes = sizeBytes;
        this.storageUrl = storageUrl;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public UUID getId() { return id; }
    public Product getProduct() { return product; }
    public String getFilename() { return filename; }
    public String getMediaType() { return mediaType; }
    public int getPosition() { return position; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageUrl() { return storageUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
