package com.smartcloset.recommendation.domain;

import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.clothing;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.feedbackHistory;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.user;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.wearHistory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.weather.domain.WeatherCondition;
import com.smartcloset.weather.domain.WeatherType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationPreparedScoringTest {

    private final RecommendationScorer scorer = new RecommendationScorer();
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Test
    void matchesUnpreparedScoresAndBestAcrossWeatherSituationsAndHistoryBoundaries() {
        for (int seed = 0; seed < 8; seed++) {
            List<ClothingItem> clothes = new ArrayList<>();
            for (ClothingCategory category : ClothingCategory.values()) {
                for (int index = 0; index < 3; index++) {
                    long id = clothes.size() + 1L;
                    clothes.add(clothing(id, user(1), category,
                            ClothingColor.values()[(index + seed) % ClothingColor.values().length],
                            ClothingMaterial.values()[(index + seed) % ClothingMaterial.values().length],
                            -10, 40, index % 2 == 0, List.of(" Office ", "미니멀", "", "WORKOUT")));
                }
            }
            // 빈 배열/blank/null JSON과 null ID도 기존 경로와 같은 의미로 처리한다.
            ReflectionTestUtils.setField(clothes.get(0), "styleTagsJson", seed % 2 == 0 ? "[]" : null);
            ReflectionTestUtils.setField(clothes.get(1), "styleTagsJson", " ");
            ReflectionTestUtils.setField(clothes.get(2), "id", null);
            List<WearHistorySnapshot> wears = new ArrayList<>();
            List<RecommendationHistorySnapshot> histories = new ArrayList<>();
            if (seed % 2 != 0) {
                for (int days : new int[]{0, 1, 3, 7, 14, 15}) {
                    for (int offset : new int[]{-1, 0, 1}) {
                        LocalDateTime at = now.minusDays(days).plusNanos(offset);
                        wears.add(wearHistory(wears.size() + 1L, at, clothes.get(0)));
                        histories.add(feedbackHistory(histories.size() + 1L, at, 18,
                                days % 2 == 0 ? RecommendationFeedbackSentiment.LIKED
                                        : RecommendationFeedbackSentiment.DISLIKED,
                                days % 2 == 0 ? RecommendationThermalFeedback.TOO_COLD
                                        : RecommendationThermalFeedback.TOO_HOT,
                                at, clothes.get(0), clothes.get(3)));
                    }
                }
            }
            for (int temperature : new int[]{12, 13, 18, 19, 25}) {
                WeatherCondition weather = WeatherCondition.of(temperature, WeatherType.CLOUDY, seed % 2 == 0, true);
                var filtered = new WeatherSuitabilityFilter().filter(clothes, weather);
                var candidates = new OutfitCandidateGenerator().generate(filtered, weather);
                for (RecommendationSituation situation : Arrays.asList(null, RecommendationSituation.WORK,
                        RecommendationSituation.CASUAL, RecommendationSituation.WORKOUT,
                        RecommendationSituation.DATE, RecommendationSituation.FORMAL)) {
                    List<String> tags = seed % 2 == 0 ? List.of() : Arrays.asList(" OFFICE ", null, " ", "미니멀");
                    var prepared = scorer.prepare(weather, wears, histories, now,
                            List.of(ClothingColor.NAVY), List.of(ClothingMaterial.COTTON), tags, situation);
                    List<ScoredOutfitCandidate> expected = new ArrayList<>();
                    List<ScoredOutfitCandidate> actual = new ArrayList<>();
                    for (OutfitCandidate candidate : candidates) {
                        RecommendationScore reference = scorer.score(candidate, weather, wears, histories, now,
                                List.of(ClothingColor.NAVY), List.of(ClothingMaterial.COTTON), tags, situation);
                        RecommendationScore optimized = prepared.apply(candidate);
                        assertThat(optimized).isEqualTo(reference);
                        expected.add(new ScoredOutfitCandidate(candidate, reference));
                        actual.add(new ScoredOutfitCandidate(candidate, optimized));
                    }
                    assertThat(scorer.selectBest(actual, weather)).isEqualTo(scorer.selectBest(expected, weather));
                }
            }
        }
    }

    @Test
    void readsEachClothingTagJsonOnlyOncePerPreparedRequest() {
        ClothingItem top = spy(clothing(1, user(1), ClothingCategory.TOP, ClothingColor.WHITE,
                ClothingMaterial.COTTON, 0, 30, true, List.of("OFFICE")));
        ClothingItem bottom = spy(clothing(2, user(1), ClothingCategory.BOTTOM, ClothingColor.BLACK,
                ClothingMaterial.COTTON, 0, 30, true, List.of("미니멀")));
        OutfitCandidate candidate = OutfitCandidate.withoutOuter(top, bottom, 0);
        var prepared = scorer.prepare(WeatherCondition.of(18, WeatherType.CLOUDY, false, false),
                List.of(), List.of(), now, List.of(), List.of(), List.of("office"), RecommendationSituation.WORK);

        for (int repeat = 0; repeat < 10; repeat++) {
            assertThat(prepared.apply(candidate).preferenceScore()).isEqualTo(3);
        }
        verify(top, times(1)).getStyleTagsJson();
        verify(bottom, times(1)).getStyleTagsJson();
    }

    @Test
    void doesNotShareTagsBetweenRequestsOrDifferentClothesWithSameId() {
        ClothingItem top = clothing(1, user(1), ClothingCategory.TOP, ClothingColor.WHITE,
                ClothingMaterial.COTTON, 0, 30, true, List.of("OFFICE"));
        ClothingItem bottom = clothing(1, user(2), ClothingCategory.BOTTOM, ClothingColor.BLACK,
                ClothingMaterial.COTTON, 0, 30, true, List.of("WORKOUT"));
        OutfitCandidate candidate = OutfitCandidate.withoutOuter(top, bottom, 0);
        WeatherCondition weather = WeatherCondition.of(18, WeatherType.CLOUDY, false, false);
        var office = scorer.prepare(weather, List.of(), List.of(), now,
                List.of(), List.of(), List.of("office"), RecommendationSituation.WORKOUT);
        assertThat(office.apply(candidate).preferenceScore()).isEqualTo(3);
        ReflectionTestUtils.setField(top, "styleTagsJson", "[\"casual\"]");
        var nextRequest = scorer.prepare(weather, List.of(), List.of(), now,
                List.of(), List.of(), List.of("office"), RecommendationSituation.WORKOUT);
        assertThat(nextRequest.apply(candidate).preferenceScore()).isEqualTo(1);
    }

    @Test
    void stillRejectsMalformedTagsEvenWhenAnEarlierItemAlreadyMatches() {
        ClothingItem top = clothing(1, user(1), ClothingCategory.TOP, ClothingColor.WHITE,
                ClothingMaterial.COTTON, 0, 30, true, List.of("OFFICE"));
        ClothingItem bottom = clothing(2, user(1), ClothingCategory.BOTTOM, ClothingColor.BLACK,
                ClothingMaterial.COTTON, 0, 30, true, List.of("OFFICE"));
        ReflectionTestUtils.setField(bottom, "styleTagsJson", "invalid-json");
        var prepared = scorer.prepare(WeatherCondition.of(18, WeatherType.CLOUDY, false, false),
                List.of(), List.of(), now, List.of(), List.of(), List.of("office"), RecommendationSituation.WORK);
        assertThatThrownBy(() -> prepared.apply(OutfitCandidate.withoutOuter(top, bottom, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid JSON array");
    }
}
