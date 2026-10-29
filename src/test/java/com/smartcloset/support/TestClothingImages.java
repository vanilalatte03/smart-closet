package com.smartcloset.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** 실제 디코딩 가능한 작은 이미지로 upload/analysis 경계를 검증한다. */
public final class TestClothingImages {

    private TestClothingImages() {
    }

    public static byte[] jpeg() {
        return jpeg(0xabcdef);
    }

    public static byte[] jpeg(int color) {
        return encoded("jpeg", color);
    }

    public static byte[] png() {
        return encoded("png", 0xabcdef);
    }

    public static byte[] webp(String filename) {
        try (var input = TestClothingImages.class.getResourceAsStream("/clothing-images/" + filename)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing test image: " + filename);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] encoded(String format, int color) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException("No test image writer: " + format);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        } finally {
            image.flush();
        }
    }
}
