package com.giraffix.brand.service;

import com.giraffix.brand.dto.BrandRequest;
import com.giraffix.brand.dto.BrandResponse;
import com.giraffix.brand.entity.Brand;
import com.giraffix.brand.repository.BrandRepository;
import com.giraffix.shared.image.ImageProcessor;
import com.giraffix.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class BrandLogoService {
    private static final long MAX_LOGO_BYTES = 5 * 1024 * 1024;
    private final BrandService brands;
    private final BrandRepository repository;
    private final UserService users;
    private final S3BrandLogoStorage storage;
    private final TransactionTemplate transactions;

    public BrandLogoService(BrandService brands, BrandRepository repository, UserService users,
                           S3BrandLogoStorage storage, PlatformTransactionManager manager) {
        this.brands = brands;
        this.repository = repository;
        this.users = users;
        this.storage = storage;
        transactions = new TransactionTemplate(manager);
        // This boundary owns commit, so compensation also covers commit-time failures.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BrandResponse create(String subject, BrandRequest request, MultipartFile logo) {
        users.getUserByGoogleId(subject);
        return save(prepare(logo), () -> {
            var created = brands.createBrand(subject, request);
            return repository.findById(created.id()).orElseThrow();
        });
    }

    public BrandResponse update(String subject, UUID brandId, BrandRequest request, MultipartFile logo) {
        // Reject ownership violations before any upload, and recheck under the update lock.
        var owner = users.getUserByGoogleId(subject);
        if (!repository.existsByIdAndCreatedBy_Id(brandId, owner.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brand not found");
        }
        return save(prepare(logo), () -> {
            brands.updateBrand(subject, brandId, request);
            return repository.findById(brandId).orElseThrow();
        });
    }

    private ImageProcessor.PreparedImage prepare(MultipartFile logo) {
        if (logo == null || logo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload one nonempty brand logo");
        }
        if (logo.getSize() > MAX_LOGO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Brand logo must be at most 5 MB");
        }
        try {
            return ImageProcessor.prepare(logo.getBytes(), MAX_LOGO_BYTES);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read brand logo");
        }
    }

    private BrandResponse save(ImageProcessor.PreparedImage image, Supplier<Brand> mutation) {
        var uploaded = storage.upload(image);
        Saved saved;
        try {
            saved = transactions.execute(status -> {
                Brand brand = mutation.get();
                var previous = brand.getLogoKey() == null ? null : new S3BrandLogoStorage.Logo(
                        brand.getLogoUrl(), brand.getLogoBucket(), brand.getLogoKey(),
                        brand.getLogoMediaType(), brand.getLogoSizeBytes());
                brand.setLogo(uploaded.url(), uploaded.bucket(), uploaded.key(), uploaded.mediaType(), uploaded.sizeBytes());
                repository.saveAndFlush(brand);
                return new Saved(BrandResponse.from(brand), previous);
            });
        } catch (RuntimeException ex) {
            storage.cleanup(uploaded);
            throw ex;
        }
        // Only delete the previous object after the replacement has committed.
        storage.cleanup(saved.previous());
        return saved.response();
    }

    private record Saved(BrandResponse response, S3BrandLogoStorage.Logo previous) {}
}
