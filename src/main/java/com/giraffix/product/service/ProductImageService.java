package com.giraffix.product.service;

import com.giraffix.config.ClaudeProperties;
import com.giraffix.product.dto.ProductRequest;
import com.giraffix.product.dto.ProductResponse;
import com.giraffix.product.entity.Product;
import com.giraffix.product.repository.ProductRepository;
import com.giraffix.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProductImageService {
    private static final Logger log = LoggerFactory.getLogger(ProductImageService.class);
    private final ProductRepository products;
    private final ProductService productService;
    private final UserService users;
    private final ClaudeProperties ai;
    private final int maxImages;
    private final long maxTotalBytes;
    private final S3ImageStorage storage;

    public ProductImageService(ProductRepository products, ProductService productService, UserService users,
                               ClaudeProperties ai, @Value("${giraffix.ai.max-images:5}") int maxImages,
                               @Value("${giraffix.ai.max-total-image-size-bytes:20971520}") long maxTotalBytes,
                               S3ImageStorage storage) {
        this.products = products;
        this.productService = productService;
        this.users = users;
        this.ai = ai;
        this.maxImages = maxImages;
        this.maxTotalBytes = maxTotalBytes;
        this.storage = storage;
    }

    @Transactional
    public ProductResponse createWithImages(String googleId, UUID brandId, ProductRequest request,
                                            List<MultipartFile> images) {
        log.info("stage=product_confirmation_received brandId={} imageCount={}", brandId, images == null ? 0 : images.size());
        // Confirmation supplies the user's edited details and the original selected files.
        List<Upload> prepared = prepare(images, 0, 0);
        Product product=productService.newOwnedProduct(googleId,brandId,request);
        products.save(product); // UUID is assigned without an initial flush and re-read.
        return save(product, prepared);
    }

    @Transactional
    public ProductResponse addImages(String googleId, UUID productId, List<MultipartFile> images) {
        Product product = ownedProduct(googleId, productId);
        long storedBytes = product.getImages().stream().mapToLong(image -> image.getSizeBytes()).sum();
        return save(product, prepare(images, product.getImages().size(), storedBytes));
    }

    private Product ownedProduct(String googleId, UUID productId) {
        return products.findOwnedForUpdate(productId,googleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private record Upload(String filename, ProductImageProcessor.PreparedImage image) {}

    private List<Upload> prepare(List<MultipartFile> files, int storedCount, long storedBytes) {
        if (files == null || files.isEmpty() || storedCount + files.size() > maxImages) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A product supports 1 to " + maxImages + " images");
        }
        long total = storedBytes;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every uploaded image must be nonempty");
            }
            if (file.getSize() > ai.maxImageSizeBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the allowed size");
            }
            total += file.getSize();
        }
        if (total > maxTotalBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Product images exceed the allowed total size");
        }
        List<Upload> uploads = new ArrayList<>();
        total = storedBytes;
        for (MultipartFile file : files) {
            try {
                var prepared = ProductImageProcessor.prepare(file.getBytes(), ai.maxImageSizeBytes());
                total += prepared.bytes().length;
                if (total > maxTotalBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Converted images exceed the allowed total size");
                }
                String filename = file.getOriginalFilename();
                filename = filename == null ? "image" : filename.replace('\\', '/');
                filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "_");
                if (filename.isBlank()) filename = "image";
                if (filename.length() > 255) filename = filename.substring(filename.length() - 255);
                uploads.add(new Upload(filename, prepared));
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read uploaded image", ex);
            }
        }
        return uploads;
    }

    private ProductResponse save(Product product, List<Upload> uploads) {
        List<String> uploaded=new ArrayList<>();
        // One compensation callback covers partial upload and commit-time rollback.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization(){
                    @Override public void afterCompletion(int status){
                        if(status==STATUS_ROLLED_BACK && !uploaded.isEmpty())storage.cleanupProductImages(product.getId(),uploaded);
                    }
                });
        for (Upload upload : uploads) {
            String url = storage.upload(product.getId(), upload.image().mediaType(), upload.image().bytes());
            uploaded.add(url);
            product.addImage(upload.filename(), upload.image().mediaType(), upload.image().bytes().length,
                    url);
        }
        products.flush();
        log.info("stage=product_metadata_flushed productId={} addedCount={} totalCount={} storage=s3",
                product.getId(), uploads.size(), product.getImages().size());
        return ProductResponse.from(product);
    }

}
