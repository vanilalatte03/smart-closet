package com.smartcloset.clothing.infrastructure.file;

import com.smartcloset.common.exception.ErrorCode;
import com.smartcloset.common.exception.SmartClosetException;
import com.smartcloset.common.response.ErrorDetail;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드된 옷 이미지가 MVP에서 허용한 파일 정책을 만족하는지 검사한다.
 *
 * <p>확장자, MIME type, 파일 signature와 실제 디코딩을 확인해, 잘못된 파일이 storage에
 * 들어가기 전에 차단한다.</p>
 */
@Component
public class ClothingImageValidator {

    private static final String FIELD_IMAGE = "image";
    private static final long MAX_PIXELS = 20_000_000L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private static final Map<String, String> EXTENSION_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );

    private final ClothingImageProperties properties;

    public ClothingImageValidator(ClothingImageProperties properties) {
        this.properties = properties;
    }

    /**
     * 파일 정책과 픽셀 수, 디코딩 가능 여부를 검증하고 저장용 metadata를 반환한다.
     */
    public ValidatedClothingImage validate(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw invalidImage("이미지 파일을 선택해주세요.");
        }
        if (image.getSize() > properties.maxSizeBytes()) {
            throw invalidImage("이미지 파일은 5MB 이하여야 합니다.");
        }

        String extension = extensionOf(image.getOriginalFilename());
        String contentType = image.getContentType();
        if (!EXTENSION_CONTENT_TYPES.containsKey(extension)) {
            throw invalidImage("허용되지 않는 이미지 확장자입니다.");
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw invalidImage("허용되지 않는 이미지 MIME type입니다.");
        }
        if (!contentType.equals(EXTENSION_CONTENT_TYPES.get(extension))) {
            throw invalidImage("이미지 확장자와 MIME type이 일치하지 않습니다.");
        }
        if (!hasMatchingSignature(image, contentType)) {
            throw invalidImage("이미지 파일 signature가 올바르지 않습니다.");
        }
        validateDecodableImage(image, contentType);
        return new ValidatedClothingImage(extension, contentType, image.getSize());
    }

    private void validateDecodableImage(MultipartFile image, String contentType) {
        // ImageIO의 전역 disk cache 설정을 바꾸지 않고 분석 이미지의 임시 파일 생성을 피한다.
        try (InputStream source = image.getInputStream();
                ImageInputStream input = new MemoryCacheImageInputStream(source)) {
            if (!hasCompleteContainer(input, contentType, image.getSize())) {
                throw invalidImage("손상되었거나 읽을 수 없는 이미지입니다.");
            }
            input.seek(0);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage("손상되었거나 읽을 수 없는 이미지입니다.");
            }
            ImageReader reader = readers.next();
            try {
                // JPEG reader가 잘린 데이터를 보정해 성공시키는 경우도 거절한다.
                reader.addIIOReadWarningListener((ignored, warning) -> {
                    throw invalidImage("손상되었거나 읽을 수 없는 이미지입니다.");
                });
                reader.setInput(input, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw invalidImage("이미지는 2천만 픽셀 이하여야 합니다.");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalidImage("손상되었거나 읽을 수 없는 이미지입니다.");
                }
                decoded.flush();
            } finally {
                reader.dispose();
            }
        } catch (SmartClosetException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidImage("손상되었거나 읽을 수 없는 이미지입니다.");
        }
    }

    private boolean hasCompleteContainer(ImageInputStream input, String contentType, long size) throws IOException {
        if ("image/jpeg".equals(contentType)) {
            input.seek(size - 2);
            return input.readUnsignedShort() == 0xffd9;
        }
        if ("image/png".equals(contentType)) {
            if (size < 20) {
                return false;
            }
            input.seek(size - 12);
            return input.readInt() == 0 && input.readInt() == 0x49454e44 && input.readInt() == 0xae426082;
        }
        input.seek(4);
        long riffSize = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
        return riffSize + 8 == size;
    }

    private String extensionOf(String originalFilename) {
        String extension = StringUtils.getFilenameExtension(originalFilename);
        return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    }

    /**
     * jpg/png/webp의 magic number를 확인한다. 전체 파일을 읽지 않고 앞 12바이트만 사용한다.
     */
    private boolean hasMatchingSignature(MultipartFile image, String contentType) {
        byte[] header = new byte[12];
        try (InputStream inputStream = image.getInputStream()) {
            int read = inputStream.read(header);
            if ("image/jpeg".equals(contentType)) {
                return read >= 3
                        && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8
                        && (header[2] & 0xff) == 0xff;
            }
            if ("image/png".equals(contentType)) {
                return read >= 8
                        && (header[0] & 0xff) == 0x89
                        && header[1] == 0x50
                        && header[2] == 0x4e
                        && header[3] == 0x47
                        && header[4] == 0x0d
                        && header[5] == 0x0a
                        && header[6] == 0x1a
                        && header[7] == 0x0a;
            }
            if ("image/webp".equals(contentType)) {
                return read >= 12
                        && header[0] == 0x52
                        && header[1] == 0x49
                        && header[2] == 0x46
                        && header[3] == 0x46
                        && header[8] == 0x57
                        && header[9] == 0x45
                        && header[10] == 0x42
                        && header[11] == 0x50;
            }
            return false;
        } catch (IOException exception) {
            throw invalidImage("이미지 파일을 읽을 수 없습니다.");
        }
    }

    private SmartClosetException invalidImage(String message) {
        return new SmartClosetException(
                ErrorCode.INVALID_REQUEST,
                ErrorCode.INVALID_REQUEST.message(),
                List.of(ErrorDetail.of(FIELD_IMAGE, message))
        );
    }
}
