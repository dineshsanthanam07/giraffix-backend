package com.giraffix.shared.image;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Detects image contents and normalizes images to a Claude-supported format. */
public final class ImageProcessor {
    private static final long MAX_PIXELS = 32_000_000;
    private static final Map<String, String> NATIVE_TYPES = Map.of(
            "jpeg", "image/jpeg", "jpg", "image/jpeg", "png", "image/png",
            "gif", "image/gif", "webp", "image/webp");

    private ImageProcessor() {}

    public record PreparedImage(byte[] bytes, String mediaType, String sourceFormat, boolean converted) {}

    public static PreparedImage prepare(byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please upload a nonempty image");
        }
        if (bytes.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds the allowed size");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                // The JDK has no built-in WebP decoder; Claude accepts WebP directly.
                if (isWebP(bytes)) return new PreparedImage(bytes, "image/webp", "webp", false);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unreadable image or unavailable decoder. Use JPEG, PNG, GIF, WebP, BMP or TIFF; "
                                + "HEIC/HEIF, AVIF, SVG and RAW require an additional decoder or conversion first.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8000 || height > 8000
                        || (long) width * height > MAX_PIXELS) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Image dimensions exceed the supported limits (8000 per side, 32 megapixels)");
                }
                String mediaType = NATIVE_TYPES.get(format);
                if (mediaType != null) return new PreparedImage(bytes, mediaType, format, false);

                // ImageIO supports BMP, TIFF, WBMP and any installed reader plugins.
                // For multipage images, extract the first page.
                var decoded = reader.read(0);
                var output = new ByteArrayOutputStream();
                if (decoded == null || !ImageIO.write(decoded, "png", output)) {
                    throw new IOException("Unable to convert image to PNG");
                }
                if (output.size() > maxBytes) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "Converted PNG exceeds the allowed size; upload a smaller image");
                }
                return new PreparedImage(output.toByteArray(), "image/png", format, true);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to decode the uploaded image", ex);
        }
    }

    private static boolean isWebP(byte[] bytes) {
        if (bytes.length < 20) return false;
        String riff = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
        String webp = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
        String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        long size = (bytes[4] & 255L) | ((bytes[5] & 255L) << 8)
                | ((bytes[6] & 255L) << 16) | ((bytes[7] & 255L) << 24);
        return "RIFF".equals(riff) && "WEBP".equals(webp) && size + 8 == bytes.length
                && ("VP8 ".equals(chunk) || "VP8L".equals(chunk) || "VP8X".equals(chunk));
    }
}
