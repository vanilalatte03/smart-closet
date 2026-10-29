package com.smartcloset.clothing.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartcloset.common.exception.ErrorCode;
import com.smartcloset.common.exception.SmartClosetException;
import com.smartcloset.support.TestClothingImages;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ClothingImageValidatorTest {

    private ClothingImageValidator validator;

    @BeforeEach
    void setUp() {
        ClothingImageProperties properties = new ClothingImageProperties();
        validator = new ClothingImageValidator(properties);
    }

    @Test
    void validatesJpegPngAndWebpImages() {
        assertThat(validator.validate(file("image.jpg", "image/jpeg", TestClothingImages.jpeg())).extension())
                .isEqualTo("jpg");
        assertThat(validator.validate(file("image.png", "image/png", TestClothingImages.png())).contentType())
                .isEqualTo("image/png");
        for (String filename : new String[] {"lossless.webp", "lossy.webp"}) {
            byte[] bytes = TestClothingImages.webp(filename);
            assertThat(validator.validate(file(filename, "image/webp", bytes)).sizeBytes())
                    .isEqualTo(bytes.length);
        }
    }

    @Test
    void rejectsSignatureOnlyFiles() {
        assertInvalid(file("broken.jpg", "image/jpeg", jpegBytes()));
        assertInvalid(file("broken.png", "image/png", pngBytes()));
        assertInvalid(file("broken.webp", "image/webp", webpBytes()));
    }

    @Test
    void rejectsTruncatedRealImages() {
        assertInvalid(file("broken.jpg", "image/jpeg", truncated(TestClothingImages.jpeg())));
        assertInvalid(file("broken.png", "image/png", truncated(TestClothingImages.png())));
        assertInvalid(file("broken.webp", "image/webp", truncated(TestClothingImages.webp("lossless.webp"))));
    }

    @Test
    void rejectsCorruptPixelDataEvenWhenContainerIsComplete() {
        byte[] jpeg = TestClothingImages.jpeg();
        byte[] brokenJpeg = Arrays.copyOf(jpeg, 22);
        brokenJpeg[20] = (byte) 0xff;
        brokenJpeg[21] = (byte) 0xd9;
        assertInvalid(file("broken.jpg", "image/jpeg", brokenJpeg));

        byte[] png = TestClothingImages.png();
        // IDAT의 zlib stream을 손상시키되 IEND는 유지한다.
        png[41] = 0;
        assertInvalid(file("broken.png", "image/png", png));

        byte[] webp = TestClothingImages.webp("lossless.webp");
        webp[20] = 0;
        assertInvalid(file("broken.webp", "image/webp", webp));
    }

    @Test
    void rejectsTruncatedJpegEvenWhenEndMarkerIsRestored() {
        byte[] jpeg = TestClothingImages.jpeg();
        byte[] truncated = Arrays.copyOf(jpeg, jpeg.length - 8);
        truncated[truncated.length - 2] = (byte) 0xff;
        truncated[truncated.length - 1] = (byte) 0xd9;
        assertInvalid(file("broken.jpg", "image/jpeg", truncated));
    }

    @Test
    void rejectsExcessivePixelCountBeforeDecoding() {
        byte[] png = TestClothingImages.png();
        ByteBuffer.wrap(png).putInt(16, 100_000).putInt(20, 100_000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());

        assertThatThrownBy(() -> validator.validate(file("huge.png", "image/png", png)))
                .isInstanceOfSatisfying(SmartClosetException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(exception.details().getFirst().field()).isEqualTo("image");
                    assertThat(exception.details().getFirst().message()).contains("2천만 픽셀");
                });
    }

    @Test
    void rejectsEmptyFile() {
        assertInvalid(file("empty.jpg", "image/jpeg", new byte[0]));
    }

    @Test
    void rejectsOversizedFile() {
        assertInvalid(file("large.jpg", "image/jpeg",
                new byte[(int) ClothingImageProperties.DEFAULT_MAX_SIZE_BYTES + 1]));
    }

    @Test
    void rejectsUnsupportedExtension() {
        assertInvalid(file("image.gif", "image/gif", new byte[] {'G', 'I', 'F'}));
    }

    @Test
    void rejectsUnsupportedMimeType() {
        assertInvalid(file("image.jpg", "application/octet-stream", jpegBytes()));
    }

    @Test
    void rejectsExtensionAndMimeTypeMismatch() {
        assertInvalid(file("image.jpg", "image/png", pngBytes()));
    }

    @Test
    void rejectsSignatureMismatch() {
        assertInvalid(file("image.jpg", "image/jpeg", new byte[] {'n', 'o', 't', 'j', 'p', 'g'}));
    }

    private void assertInvalid(MockMultipartFile file) {
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(SmartClosetException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(exception.details()).hasSize(1);
                    assertThat(exception.details().getFirst().field()).isEqualTo("image");
                });
    }

    private MockMultipartFile file(String filename, String contentType, byte[] content) {
        return new MockMultipartFile("image", filename, contentType, content);
    }

    private byte[] truncated(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length - 2);
    }

    private byte[] jpegBytes() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00};
    }

    private byte[] pngBytes() {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
    }

    private byte[] webpBytes() {
        return new byte[] {
                0x52, 0x49, 0x46, 0x46, 0x01, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
        };
    }
}
