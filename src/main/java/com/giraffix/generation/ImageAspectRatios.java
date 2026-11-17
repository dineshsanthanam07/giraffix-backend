package com.giraffix.generation;

import com.giraffix.shared.image.ImageProcessor;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Verify the actual output canvas; never stretch or crop an ad to disguise a wrong ratio. */
public final class ImageAspectRatios {
    private ImageAspectRatios() {}
    public static String size(String ratio) {
        if (ratio == null) throw new IllegalArgumentException("Aspect ratio is required");
        return switch(ratio) {
            case "1:1" -> "1024x1024";
            case "4:5" -> "1024x1280";
            case "3:4" -> "960x1280";
            case "16:9" -> "1536x864";
            case "9:16" -> "864x1536";
            default -> throw new IllegalArgumentException("Unsupported aspect ratio");
        };
    }
    public static boolean matches(byte[] bytes, String ratio) {
        ImageProcessor.prepare(bytes,5242880);
        if (ratio == null || !ratio.matches("1:1|4:5|3:4|16:9|9:16")) return false;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return false;
            String[] parts = ratio.split(":");
            return (long) image.getWidth() * Integer.parseInt(parts[1])
                    == (long) image.getHeight() * Integer.parseInt(parts[0]);
        } catch (IOException ex) {
            return false;
        }
    }
}
