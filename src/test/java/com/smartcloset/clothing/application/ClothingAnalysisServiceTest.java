package com.smartcloset.clothing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.clothing.dto.ClothingAnalysisResponse;
import com.smartcloset.clothing.infrastructure.analysis.ClothingAnalysisField;
import com.smartcloset.clothing.infrastructure.analysis.ClothingAnalysisImage;
import com.smartcloset.clothing.infrastructure.analysis.ClothingAnalysisProperties;
import com.smartcloset.clothing.infrastructure.analysis.ClothingAnalysisResult;
import com.smartcloset.clothing.infrastructure.analysis.ClothingAnalysisSuggestion;
import com.smartcloset.clothing.infrastructure.analysis.ClothingImageAnalysisUnavailableException;
import com.smartcloset.clothing.infrastructure.analysis.ClothingImageAnalyzer;
import com.smartcloset.clothing.infrastructure.analysis.DisabledClothingImageAnalyzer;
import com.smartcloset.clothing.infrastructure.file.ClothingImageProperties;
import com.smartcloset.clothing.infrastructure.file.ClothingImageValidator;
import com.smartcloset.common.exception.ErrorCode;
import com.smartcloset.common.exception.SmartClosetException;
import com.smartcloset.common.observability.SmartClosetMetrics;
import com.smartcloset.support.TestClothingImages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ClothingAnalysisServiceTest {

    private ClothingAnalysisProperties analysisProperties;
    private SimpleMeterRegistry meterRegistry;
    private FakeAnalyzer analyzer;
    private ClothingAnalysisService service;

    @BeforeEach
    void setUp() {
        ClothingImageProperties imageProperties = new ClothingImageProperties();
        imageProperties.setMaxSizeBytes(1024);
        analysisProperties = new ClothingAnalysisProperties();
        meterRegistry = new SimpleMeterRegistry();
        analyzer = new FakeAnalyzer();
        service = new ClothingAnalysisService(
                new ClothingImageValidator(imageProperties),
                analyzer,
                new ClothingAnalysisDailyLimiter(analysisProperties),
                new SmartClosetMetrics(meterRegistry)
        );
    }

    @Test
    void mapsAnalyzerResultToResponseWithoutPersistingImage() {
        analyzer.result = ClothingAnalysisResult.analyzable(
                new ClothingAnalysisSuggestion(
                        "흰색 셔츠",
                        ClothingCategory.TOP,
                        ClothingColor.WHITE,
                        ClothingMaterial.COTTON,
                        18,
                        28,
                        false,
                        List.of("미니멀", "단정")
                ),
                Map.of(
                        ClothingAnalysisField.CATEGORY, 0.94,
                        ClothingAnalysisField.MATERIAL, 0.58
                ),
                List.of(ClothingAnalysisField.NAME),
                0.75
        );

        ClothingAnalysisResponse response = service.analyze(1L, jpegFile());

        assertThat(response.analyzable()).isTrue();
        assertThat(response.suggestion().name()).isEqualTo("흰색 셔츠");
        assertThat(response.suggestion().category()).isEqualTo(ClothingCategory.TOP);
        assertThat(response.suggestion().styleTags()).containsExactly("미니멀", "단정");
        assertThat(response.fieldConfidence())
                .containsEntry("category", 0.94)
                .containsEntry("material", 0.58);
        assertThat(response.reviewRequiredFields()).contains("name", "material");
        assertThat(response.lowConfidenceThreshold()).isEqualTo(0.75);
        assertThat(analyzer.lastImage.contentType()).isEqualTo("image/jpeg");
        assertThat(analyzer.lastImage.bytes()).containsExactly(jpegBytes());
        assertThat(analysisRequestCount("success")).isEqualTo(1.0);
    }

    @Test
    void returnsNotAnalyzableResponseAsSuccessDto() {
        analyzer.result = ClothingAnalysisResult.notAnalyzable(0.75);

        ClothingAnalysisResponse response = service.analyze(1L, jpegFile());

        assertThat(response.analyzable()).isFalse();
        assertThat(response.suggestion()).isNull();
        assertThat(response.fieldConfidence()).isEmpty();
        assertThat(response.reviewRequiredFields()).isEmpty();
        assertThat(analysisRequestCount("not_analyzable")).isEqualTo(1.0);
    }

    @Test
    void mapsProviderUnavailableToStableErrorCode() {
        analyzer.unavailable = true;

        assertThatThrownBy(() -> service.analyze(1L, jpegFile()))
                .isInstanceOfSatisfying(SmartClosetException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLOTHING_ANALYSIS_UNAVAILABLE));
        assertThat(analysisRequestCount("unavailable")).isEqualTo(1.0);
    }

    @Test
    void enforcesUserDailyLimitInMemory() {
        analysisProperties.setDailyLimit(1);
        analyzer.result = ClothingAnalysisResult.notAnalyzable(0.75);

        service.analyze(1L, jpegFile());

        assertThatThrownBy(() -> service.analyze(1L, jpegFile()))
                .isInstanceOfSatisfying(SmartClosetException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLOTHING_ANALYSIS_LIMIT_EXCEEDED));
        assertThat(analysisRequestCount("limit_exceeded")).isEqualTo(1.0);

        service.analyze(2L, jpegFile());
    }

    @Test
    void disabledAnalyzerDoesNotConsumeDailyLimit() {
        analysisProperties.setDailyLimit(1);
        ClothingImageProperties imageProperties = new ClothingImageProperties();
        imageProperties.setMaxSizeBytes(1024);
        ClothingAnalysisService disabledService = new ClothingAnalysisService(
                new ClothingImageValidator(imageProperties),
                new DisabledClothingImageAnalyzer(),
                new ClothingAnalysisDailyLimiter(analysisProperties),
                new SmartClosetMetrics(meterRegistry)
        );

        assertThatThrownBy(() -> disabledService.analyze(1L, jpegFile()))
                .isInstanceOfSatisfying(SmartClosetException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLOTHING_ANALYSIS_DISABLED));
        assertThatThrownBy(() -> disabledService.analyze(1L, jpegFile()))
                .isInstanceOfSatisfying(SmartClosetException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.CLOTHING_ANALYSIS_DISABLED));
        assertThat(analysisRequestCount("disabled")).isEqualTo(2.0);
    }

    @Test
    void corruptImageDoesNotCallProviderOrConsumeDailyLimit() {
        analysisProperties.setDailyLimit(1);
        MockMultipartFile corrupt = new MockMultipartFile("image", "broken.jpg", "image/jpeg",
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01});

        assertThatThrownBy(() -> service.analyze(1L, corrupt))
                .isInstanceOfSatisfying(SmartClosetException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(exception.details().getFirst().field()).isEqualTo("image");
                });
        assertThat(analyzer.lastImage).isNull();
        assertThat(analysisRequestCount("invalid_request")).isEqualTo(1.0);

        assertThat(service.analyze(1L, jpegFile()).analyzable()).isFalse();
        assertThat(analyzer.lastImage).isNotNull();
    }

    @Test
    void invalidImageFailsBeforeAnalyzer() {
        MockMultipartFile invalid = new MockMultipartFile("image", "image.gif", "image/gif", new byte[] {'G'});

        assertThatThrownBy(() -> service.analyze(1L, invalid))
                .isInstanceOfSatisfying(SmartClosetException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(analyzer.lastImage).isNull();
        assertThat(analysisRequestCount("invalid_request")).isEqualTo(1.0);
    }

    private double analysisRequestCount(String outcome) {
        return meterRegistry.get("smartcloset.clothing.analysis.requests")
                .tag("provider", "openai")
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("image", "shirt.jpg", "image/jpeg", jpegBytes());
    }

    private byte[] jpegBytes() {
        return TestClothingImages.jpeg();
    }

    private static class FakeAnalyzer implements ClothingImageAnalyzer {

        private ClothingAnalysisResult result = ClothingAnalysisResult.notAnalyzable(0.75);
        private ClothingAnalysisImage lastImage;
        private boolean unavailable;

        @Override
        public ClothingAnalysisResult analyze(ClothingAnalysisImage image) {
            lastImage = image;
            if (unavailable) {
                throw new ClothingImageAnalysisUnavailableException("provider unavailable");
            }
            return result;
        }
    }
}
