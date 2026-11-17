package com.giraffix.product.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/** Documents the multipart form; the controller handles the legacy alias separately. */
@Schema(requiredProperties = {"images"})
public record ProductAnalysisUpload(
        @ArraySchema(arraySchema = @Schema(description = "Photos of the same product; 5 MB each, 20 MB total"),
                schema = @Schema(type = "string", format = "binary"), minItems = 1, maxItems = 5)
        List<MultipartFile> images
) {}
