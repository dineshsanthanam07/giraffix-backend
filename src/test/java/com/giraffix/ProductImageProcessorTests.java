package com.giraffix;

import com.giraffix.product.service.ProductImageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ProductImageProcessorTests {
    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png", "gif"})
    void preservesProviderNativeFormats(String format) throws Exception {
        byte[] bytes = fixture(format);
        var result = ProductImageProcessor.prepare(bytes, 5242880);
        assertThat(result.mediaType()).isEqualTo(Map.of("jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif").get(format));
        assertThat(result.bytes()).isEqualTo(bytes);
        assertThat(result.converted()).isFalse();
    }

    @Test
    void acceptsWebPWithoutAJdkWebPDecoder() {
        byte[] bytes = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        var result = ProductImageProcessor.prepare(bytes, 5242880);
        assertThat(result.mediaType()).isEqualTo("image/webp");
        assertThat(result.bytes()).isEqualTo(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bmp", "tiff", "wbmp"})
    void convertsAdditionalFormatsToReadablePng(String format) throws Exception {
        var result = ProductImageProcessor.prepare(fixture(format), 5242880);
        assertThat(result.mediaType()).isEqualTo("image/png");
        assertThat(result.converted()).isTrue();
        var decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertThat(decoded.getWidth()).isEqualTo(2);
        assertThat(decoded.getHeight()).isEqualTo(2);
    }

    @Test
    void rejectsNonImagesAndTruncatedImageHeaders() {
        for (byte[] bytes : new byte[][] {"not an image".getBytes(), {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}}) {
            assertThatThrownBy(() -> ProductImageProcessor.prepare(bytes, 5242880))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void retainsUploadSizeLimit() throws Exception {
        byte[] bytes = fixture("png");
        assertThatThrownBy(() -> ProductImageProcessor.prepare(bytes, bytes.length - 1))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }

    @Test
    void checksDimensionsBeforeAllocatingDecodedPixels() throws Exception {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(9000, 1, BufferedImage.TYPE_INT_RGB), "bmp", output)).isTrue();
        byte[] bytes = output.toByteArray();
        assertThatThrownBy(() -> ProductImageProcessor.prepare(bytes, 5242880))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).contains("dimensions");
                });
    }

    static byte[] fixture(String format) throws Exception {
        var image = new BufferedImage(2, 2, "wbmp".equals(format)
                ? BufferedImage.TYPE_BYTE_BINARY : BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFFFFFF);
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
