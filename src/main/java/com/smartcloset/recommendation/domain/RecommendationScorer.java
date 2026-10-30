package com.smartcloset.recommendation.domain;

import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.weather.domain.WeatherType;
import com.smartcloset.weather.domain.WeatherCondition;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 추천 후보의 점수를 계산하고, 동일 점수일 때도 항상 같은 결과가 나오도록 최종 후보를 고른다.
 *
 * <p>점수 배점은 {@code docs/RECOMMENDATION_RULES.md}의 계약과 맞물려 있으므로, 새 항목을
 * 추가하기보다 기존 weather/color/history/preference 축 안에서 보정하는 방식으로 유지한다.</p>
 */
public class RecommendationScorer {

    private static final int MAX_WEATHER_SCORE = 35;
    private static final int MAX_COLOR_SCORE = 25;
    private static final int MAX_WEAR_HISTORY_SCORE = 20;
    private static final int MAX_RECOMMENDATION_HISTORY_SCORE = 10;
    private static final int MAX_PREFERENCE_SCORE = 10;
    private static final Comparator<Long> ID_ASC = Comparator.nullsLast(Long::compareTo);

    /**
     * 한 요청의 후보 순회에만 사용하는 계산 함수를 만든다. 옷별 태그는 최초 사용 시 한 번만 읽는다.
     * 반환 함수는 요청/스레드 사이에 공유하거나 옷을 수정하면서 재사용하지 않는다.
     */
    public Function<OutfitCandidate, RecommendationScore> prepare(
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            List<String> preferredStyleTags,
            RecommendationSituation situation
    ) {
        Objects.requireNonNull(weather, "weather must not be null");
        Objects.requireNonNull(wearHistories, "wearHistories must not be null");
        Objects.requireNonNull(recommendationHistories, "recommendationHistories must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(preferredColors, "preferredColors must not be null");
        Objects.requireNonNull(preferredMaterials, "preferredMaterials must not be null");
        Objects.requireNonNull(preferredStyleTags, "preferredStyleTags must not be null");
        Set<String> preferredTags = styleTagKeys(preferredStyleTags);
        Set<String> situationTags = situationStyleTagKeys(situation);
        Map<ClothingItem, Set<String>> itemTags = new IdentityHashMap<>();
        return candidate -> score(
                candidate, weather, wearHistories, recommendationHistories, requestedAt,
                preferredColors, preferredMaterials,
                preparedStyleTagScore(candidate, preferredTags, situationTags, itemTags)
        );
    }

    private int preparedStyleTagScore(
            OutfitCandidate candidate,
            Set<String> preferredTags,
            Set<String> situationTags,
            Map<ClothingItem, Set<String>> itemTags
    ) {
        boolean preferredMatch = false;
        boolean situationMatch = false;
        for (ClothingItem item : candidate.items()) {
            Set<String> tags = itemTags.computeIfAbsent(item, clothing ->
                    styleTagKeys(readStyleTags(clothing.getStyleTagsJson())));
            preferredMatch |= intersects(tags, preferredTags);
            situationMatch |= intersects(tags, situationTags);
        }
        return (preferredMatch ? 2 : 0) + (situationMatch ? 1 : 0);
    }

    /**
     * 생성된 모든 코디 후보에 동일한 사용자/날씨/이력 컨텍스트를 적용해 점수 모델로 변환한다.
     */
    public List<ScoredOutfitCandidate> scoreAll(
            List<OutfitCandidate> candidates,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            List<String> preferredStyleTags,
            RecommendationSituation situation
    ) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        return candidates.stream()
                .map(candidate -> new ScoredOutfitCandidate(
                        candidate,
                        score(
                                candidate,
                                weather,
                                wearHistories,
                                recommendationHistories,
                                requestedAt,
                                preferredColors,
                                preferredMaterials,
                                preferredStyleTags,
                                situation
                        )
                ))
                .toList();
    }

    public List<ScoredOutfitCandidate> scoreAll(
            List<OutfitCandidate> candidates,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials
    ) {
        return scoreAll(
                candidates,
                weather,
                wearHistories,
                recommendationHistories,
                requestedAt,
                preferredColors,
                preferredMaterials,
                List.of(),
                RecommendationSituation.CASUAL
        );
    }

    public List<ScoredOutfitCandidate> scoreAll(
            List<OutfitCandidate> candidates,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt
    ) {
        return scoreAll(candidates, weather, wearHistories, recommendationHistories, requestedAt, List.of(), List.of());
    }

    /**
     * 단일 후보의 총점과 세부 점수를 계산한다.
     *
     * <p>총점은 100점 만점이며, 각 세부 점수의 최대값은 이 클래스의 상수로 고정되어 있다.</p>
     */
    public RecommendationScore score(
            OutfitCandidate candidate,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            List<String> preferredStyleTags,
            RecommendationSituation situation
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(weather, "weather must not be null");
        Objects.requireNonNull(wearHistories, "wearHistories must not be null");
        Objects.requireNonNull(recommendationHistories, "recommendationHistories must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(preferredColors, "preferredColors must not be null");
        Objects.requireNonNull(preferredMaterials, "preferredMaterials must not be null");
        Objects.requireNonNull(preferredStyleTags, "preferredStyleTags must not be null");

        return score(candidate, weather, wearHistories, recommendationHistories, requestedAt,
                preferredColors, preferredMaterials, calculateStyleTagScore(candidate, preferredStyleTags, situation));
    }

    private RecommendationScore score(
            OutfitCandidate candidate,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            int styleTagScore
    ) {
        int weatherScore = calculateWeatherScore(candidate, weather);
        int colorScore = calculateColorScore(candidate);
        int wearHistoryScore = calculateWearHistoryScore(candidate, wearHistories, requestedAt);
        int recommendationHistoryScore = calculateRecommendationHistoryScore(
                candidate,
                recommendationHistories,
                requestedAt
        );
        int preferenceScore = calculatePreferenceScore(
                candidate,
                preferredColors,
                preferredMaterials,
                styleTagScore,
                recommendationHistories,
                requestedAt,
                weather
        );
        int totalScore = weatherScore + colorScore + wearHistoryScore + recommendationHistoryScore + preferenceScore;

        return RecommendationScore.of(
                totalScore,
                weatherScore,
                colorScore,
                wearHistoryScore,
                recommendationHistoryScore,
                preferenceScore
        );
    }

    public RecommendationScore score(
            OutfitCandidate candidate,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials
    ) {
        return score(
                candidate,
                weather,
                wearHistories,
                recommendationHistories,
                requestedAt,
                preferredColors,
                preferredMaterials,
                List.of(),
                RecommendationSituation.CASUAL
        );
    }

    public RecommendationScore score(
            OutfitCandidate candidate,
            WeatherCondition weather,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt
    ) {
        return score(candidate, weather, wearHistories, recommendationHistories, requestedAt, List.of(), List.of());
    }

    public ScoredOutfitCandidate selectBest(List<ScoredOutfitCandidate> candidates) {
        return selectBest(candidates, null);
    }

    /**
     * 가장 높은 점수의 후보를 선택한다. 동점이면 {@link #compareBest}의 tie-break 순서를 따른다.
     */
    public ScoredOutfitCandidate selectBest(List<ScoredOutfitCandidate> candidates, WeatherCondition weather) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        return candidates.stream()
                .min((left, right) -> compareBest(left, right, weather))
                .orElseThrow(() -> new RecommendationFailureException(RecommendationFailureCode.INSUFFICIENT_CLOSET_ITEMS));
    }

    /**
     * 두 후보 중 기존 tie-break 기준상 더 나은 후보를 반환한다.
     */
    public ScoredOutfitCandidate betterOf(
            ScoredOutfitCandidate left,
            ScoredOutfitCandidate right,
            WeatherCondition weather
    ) {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
        return compareBest(left, right, weather) <= 0 ? left : right;
    }

    /**
     * 기온 범위 통과를 기본점으로 두고, 아우터/비/소재 적합성으로 날씨 점수를 보정한다.
     */
    int calculateWeatherScore(OutfitCandidate candidate, WeatherCondition weather) {
        int temperatureRangeScore = 15;
        int outerScore = calculateOuterScore(candidate, weather);
        int rainScore = calculateRainScore(candidate, weather);
        int materialWeatherScore = calculateMaterialWeatherScore(candidate, weather);
        return clamp(
                temperatureRangeScore + outerScore + rainScore + materialWeatherScore,
                0,
                MAX_WEATHER_SCORE
        );
    }

    /**
     * 상의-하의 조합을 기본으로 평가하고, 아우터가 있으면 세 쌍의 평균으로 색상 균형을 잡는다.
     */
    int calculateColorScore(OutfitCandidate candidate) {
        if (!candidate.hasOuter()) {
            return calculatePairColorScore(candidate.top().getColor(), candidate.bottom().getColor());
        }

        int topBottom = calculatePairColorScore(candidate.top().getColor(), candidate.bottom().getColor());
        int topOuter = calculatePairColorScore(candidate.top().getColor(), candidate.outer().getColor());
        int bottomOuter = calculatePairColorScore(candidate.bottom().getColor(), candidate.outer().getColor());
        return clamp(Math.round((topBottom + topOuter + bottomOuter) / 3.0f), 0, MAX_COLOR_SCORE);
    }

    /**
     * 최근 7일 착용 이력을 페널티로 반영한다. 점수가 높을수록 최근 반복 착용 부담이 낮다.
     */
    int calculateWearHistoryScore(
            OutfitCandidate candidate,
            List<WearHistorySnapshot> wearHistories,
            LocalDateTime requestedAt
    ) {
        return wearHistories.stream()
                .filter(history -> candidate.intersects(history.clothingItemIds()))
                .map(WearHistorySnapshot::wornAt)
                .max(LocalDateTime::compareTo)
                .map(wornAt -> scoreRecentWornAt(wornAt, requestedAt))
                .orElse(MAX_WEAR_HISTORY_SCORE);
    }

    /**
     * 최근 추천과 같은 조합 또는 일부 겹치는 조합이 반복 노출되지 않도록 점수를 낮춘다.
     */
    int calculateRecommendationHistoryScore(
            OutfitCandidate candidate,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt
    ) {
        int score = MAX_RECOMMENDATION_HISTORY_SCORE;
        for (RecommendationHistorySnapshot history : recommendationHistories) {
            LocalDateTime createdAt = history.createdAt();
            if (createdAt == null || createdAt.isBefore(requestedAt.minusDays(7))) {
                continue;
            }

            Set<Long> historyItemIds = history.clothingItemIds();
            if (candidate.hasSameItemSet(historyItemIds)) {
                if (!createdAt.isBefore(requestedAt.minusDays(3))) {
                    score = Math.min(score, 2);
                } else {
                    score = Math.min(score, 5);
                }
            } else if (candidate.intersects(historyItemIds)) {
                if (!createdAt.isBefore(requestedAt.minusDays(3))) {
                    score = Math.min(score, 7);
                } else {
                    score = Math.min(score, 8);
                }
            }
        }
        return score;
    }

    /**
     * 사용자 선호 색상/소재/태그와 최근 피드백을 개인화 점수로 합산한다.
     */
    int calculatePreferenceScore(
            OutfitCandidate candidate,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials
    ) {
        return calculatePreferenceScore(
                candidate,
                preferredColors,
                preferredMaterials,
                List.of(),
                RecommendationSituation.CASUAL,
                List.of(),
                LocalDateTime.now(),
                WeatherCondition.of(12, WeatherType.CLOUDY, false, false)
        );
    }

    int calculatePreferenceScore(
            OutfitCandidate candidate,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            List<String> preferredStyleTags,
            RecommendationSituation situation,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            WeatherCondition weather
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(preferredColors, "preferredColors must not be null");
        Objects.requireNonNull(preferredMaterials, "preferredMaterials must not be null");
        Objects.requireNonNull(preferredStyleTags, "preferredStyleTags must not be null");
        Objects.requireNonNull(recommendationHistories, "recommendationHistories must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(weather, "weather must not be null");

        return calculatePreferenceScore(candidate, preferredColors, preferredMaterials,
                calculateStyleTagScore(candidate, preferredStyleTags, situation),
                recommendationHistories, requestedAt, weather);
    }

    private int calculatePreferenceScore(
            OutfitCandidate candidate,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            int styleTagScore,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            WeatherCondition weather
    ) {
        int score = 0;
        if (!preferredColors.isEmpty()
                && candidate.stream().map(ClothingItem::getColor).anyMatch(preferredColors::contains)) {
            score += 2;
        }
        if (!preferredMaterials.isEmpty()
                && candidate.stream().map(ClothingItem::getMaterial).anyMatch(preferredMaterials::contains)) {
            score += 2;
        }
        score += styleTagScore;
        score += calculateFeedbackAdjustment(candidate, recommendationHistories, requestedAt, weather);
        return clamp(score, 0, MAX_PREFERENCE_SCORE);
    }

    /**
     * 사용자 태그와 상황 태그를 같은 정규화 규칙으로 비교해 다국어/대소문자 차이를 줄인다.
     */
    int calculateStyleTagScore(
            OutfitCandidate candidate,
            List<String> preferredStyleTags,
            RecommendationSituation situation
    ) {
        Set<String> candidateTags = candidateStyleTagKeys(candidate);
        int score = 0;
        if (!candidateTags.isEmpty() && intersects(candidateTags, styleTagKeys(preferredStyleTags))) {
            score += 2;
        }
        if (!candidateTags.isEmpty() && intersects(candidateTags, situationStyleTagKeys(situation))) {
            score += 1;
        }
        return score;
    }

    /**
     * 최근 14일 피드백을 후보와의 겹침 정도에 따라 보정한다.
     *
     * <p>부정 피드백은 안전하게 우선 적용한다. 같은 후보를 싫어했거나 비슷한 기온에서
     * 춥다/덥다고 했으면 긍정 보정보다 먼저 감점한다.</p>
     */
    int calculateFeedbackAdjustment(
            OutfitCandidate candidate,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            WeatherCondition weather
    ) {
        int positiveAdjustment = 0;
        int negativeAdjustment = 0;
        LocalDateTime feedbackWindowStart = requestedAt.minusDays(14);

        for (RecommendationHistorySnapshot history : recommendationHistories) {
            if (!history.hasFeedback() || history.feedbackUpdatedAt().isBefore(feedbackWindowStart)) {
                continue;
            }

            Overlap overlap = overlap(candidate, history.clothingItemIds());
            if (overlap == Overlap.NONE) {
                continue;
            }

            if (history.sentimentFeedback() == RecommendationFeedbackSentiment.DISLIKED) {
                negativeAdjustment = Math.min(negativeAdjustment, overlap == Overlap.SAME ? -3 : -1);
            } else if (history.sentimentFeedback() == RecommendationFeedbackSentiment.LIKED) {
                positiveAdjustment = Math.max(positiveAdjustment, overlap == Overlap.SAME ? 3 : 1);
            }

            // 온도 피드백은 당시 추천 온도와 현재 온도가 충분히 가까울 때만 재사용한다.
            if (history.thermalFeedback() == RecommendationThermalFeedback.TOO_COLD
                    && weather.temperature() <= history.weatherTemperature() + 3) {
                negativeAdjustment = Math.min(negativeAdjustment, overlap == Overlap.SAME ? -2 : -1);
            }
            if (history.thermalFeedback() == RecommendationThermalFeedback.TOO_HOT
                    && weather.temperature() >= history.weatherTemperature() - 3) {
                negativeAdjustment = Math.min(negativeAdjustment, overlap == Overlap.SAME ? -2 : -1);
            }
        }

        if (negativeAdjustment < 0) {
            return clamp(negativeAdjustment, -3, 3);
        }
        return clamp(positiveAdjustment, -3, 3);
    }

    private int calculateOuterScore(OutfitCandidate candidate, WeatherCondition weather) {
        int score;
        if (weather.temperature() <= 12) {
            score = candidate.hasOuter() ? 8 : 0;
        } else if (weather.temperature() <= 18) {
            score = candidate.hasOuter() ? 7 : 5;
        } else {
            score = candidate.hasOuter() ? 5 : 7;
        }

        if ((weather.rainy() || weather.windy()) && candidate.hasOuter()) {
            score += 1;
        }
        return clamp(score, 0, 8);
    }

    private int calculateRainScore(OutfitCandidate candidate, WeatherCondition weather) {
        if (!weather.rainy()) {
            return 6;
        }

        long rainSuitableCount = candidate.stream().filter(ClothingItem::isRainSuitable).count();
        if (rainSuitableCount == candidate.items().size()) {
            return 6;
        }
        if (rainSuitableCount > 0) {
            return 3;
        }
        return 1;
    }

    private int calculateMaterialWeatherScore(OutfitCandidate candidate, WeatherCondition weather) {
        int score = 3;

        if (weather.temperature() <= 12) {
            long warmMaterialCount = candidate.stream()
                    .filter(item -> item.getMaterial() == ClothingMaterial.KNIT
                            || item.getMaterial() == ClothingMaterial.WOOL)
                    .count();
            score += (int) Math.min(warmMaterialCount, 3);
        }

        if (weather.temperature() >= 25) {
            long hotPenaltyCount = candidate.stream()
                    .filter(item -> item.getMaterial() == ClothingMaterial.KNIT
                            || item.getMaterial() == ClothingMaterial.WOOL)
                    .count();
            score -= (int) hotPenaltyCount * 2;
        }

        if (weather.rainy()) {
            long nylonCount = candidate.stream()
                    .filter(item -> item.getMaterial() == ClothingMaterial.NYLON)
                    .count();
            long woolCount = candidate.stream()
                    .filter(item -> item.getMaterial() == ClothingMaterial.WOOL)
                    .count();
            score += Math.min(nylonCount * 2, 2);
            score -= woolCount * 2;
        }

        return clamp(score, 0, 6);
    }

    private int calculatePairColorScore(ClothingColor left, ClothingColor right) {
        if (left == ClothingColor.UNKNOWN || right == ClothingColor.UNKNOWN) {
            return 15;
        }
        if (isStrongClash(left, right)) {
            return 10;
        }
        if (isComplementary(left, right)) {
            return 17;
        }

        ColorGroup leftGroup = ColorGroup.from(left);
        ColorGroup rightGroup = ColorGroup.from(right);
        if (leftGroup == ColorGroup.NEUTRAL && rightGroup == ColorGroup.NEUTRAL) {
            return 24;
        }
        if (leftGroup == ColorGroup.NEUTRAL || rightGroup == ColorGroup.NEUTRAL) {
            return 25;
        }
        if (isBlueEarthPair(leftGroup, rightGroup)) {
            return 22;
        }
        if (leftGroup == rightGroup) {
            return 20;
        }
        return 15;
    }

    private int scoreRecentWornAt(LocalDateTime wornAt, LocalDateTime requestedAt) {
        if (!wornAt.isBefore(requestedAt.minusDays(1))) {
            return 5;
        }
        if (!wornAt.isBefore(requestedAt.minusDays(3))) {
            return 10;
        }
        if (!wornAt.isBefore(requestedAt.minusDays(7))) {
            return 15;
        }
        return MAX_WEAR_HISTORY_SCORE;
    }

    private boolean isBlueEarthPair(ColorGroup left, ColorGroup right) {
        return (left == ColorGroup.BLUE && right == ColorGroup.EARTH)
                || (left == ColorGroup.EARTH && right == ColorGroup.BLUE);
    }

    private boolean isComplementary(ClothingColor left, ClothingColor right) {
        return pairMatches(left, right, ClothingColor.RED, ClothingColor.GREEN)
                || pairMatches(left, right, ClothingColor.BLUE, ClothingColor.YELLOW)
                || pairMatches(left, right, ClothingColor.NAVY, ClothingColor.YELLOW);
    }

    private boolean isStrongClash(ClothingColor left, ClothingColor right) {
        return pairMatches(left, right, ClothingColor.RED, ClothingColor.YELLOW)
                || pairMatches(left, right, ClothingColor.GREEN, ClothingColor.YELLOW);
    }

    private boolean pairMatches(ClothingColor left, ClothingColor right, ClothingColor first, ClothingColor second) {
        return (left == first && right == second) || (left == second && right == first);
    }

    /**
     * Comparator에서 점수가 높은 후보가 먼저 오도록 음수를 반환한다.
     *
     * <p>점수 동률이면 계약 문서의 tie-break 순서대로 세부 점수, 옷 id, 아우터 유무,
     * 생성 순서를 사용해 결과를 결정적으로 만든다.</p>
     */
    private int compareBest(ScoredOutfitCandidate left, ScoredOutfitCandidate right, WeatherCondition weather) {
        int result = compareHigher(left.score().totalScore(), right.score().totalScore());
        if (result != 0) {
            return result;
        }
        result = compareHigher(left.score().weatherScore(), right.score().weatherScore());
        if (result != 0) {
            return result;
        }
        result = compareHigher(left.score().preferenceScore(), right.score().preferenceScore());
        if (result != 0) {
            return result;
        }
        result = compareHigher(left.score().colorScore(), right.score().colorScore());
        if (result != 0) {
            return result;
        }
        result = compareHigher(left.score().wearHistoryScore(), right.score().wearHistoryScore());
        if (result != 0) {
            return result;
        }
        result = compareHigher(
                left.score().recommendationHistoryScore(),
                right.score().recommendationHistoryScore()
        );
        if (result != 0) {
            return result;
        }
        result = ID_ASC.compare(left.candidate().top().getId(), right.candidate().top().getId());
        if (result != 0) {
            return result;
        }
        result = ID_ASC.compare(left.candidate().bottom().getId(), right.candidate().bottom().getId());
        if (result != 0) {
            return result;
        }
        result = compareOuter(left.candidate(), right.candidate(), weather);
        if (result != 0) {
            return result;
        }
        return Integer.compare(left.candidate().generationOrder(), right.candidate().generationOrder());
    }

    private int compareHigher(int left, int right) {
        return Integer.compare(right, left);
    }

    private int compareOuter(OutfitCandidate left, OutfitCandidate right, WeatherCondition weather) {
        if (left.hasOuter() && right.hasOuter()) {
            return ID_ASC.compare(left.outer().getId(), right.outer().getId());
        }
        if (left.hasOuter() == right.hasOuter()) {
            return 0;
        }
        boolean preferOuter = weather != null && weather.temperature() <= 18;
        if (left.hasOuter() == preferOuter) {
            return -1;
        }
        if (right.hasOuter() == preferOuter) {
            return 1;
        }
        return Boolean.compare(left.hasOuter(), right.hasOuter());
    }

    private int clamp(int score, int min, int max) {
        return Math.max(min, Math.min(score, max));
    }

    private Overlap overlap(OutfitCandidate candidate, Set<Long> historyItemIds) {
        if (candidate.hasSameItemSet(historyItemIds)) {
            return Overlap.SAME;
        }
        if (candidate.intersects(historyItemIds)) {
            return Overlap.PARTIAL;
        }
        return Overlap.NONE;
    }

    private Set<String> candidateStyleTagKeys(OutfitCandidate candidate) {
        return candidate.stream()
                .flatMap(item -> readStyleTags(item.getStyleTagsJson()).stream())
                .map(this::styleTagKey)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> styleTagKeys(List<String> styleTags) {
        return styleTags.stream()
                .map(this::styleTagKey)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> situationStyleTagKeys(RecommendationSituation situation) {
        RecommendationSituation resolvedSituation = situation == null ? RecommendationSituation.CASUAL : situation;
        return switch (resolvedSituation) {
            case WORK -> styleTagKeys(List.of("WORK", "OFFICE", "MINIMAL", "SMART", "출근", "오피스", "미니멀", "단정"));
            case CASUAL -> styleTagKeys(List.of("CASUAL", "DAILY", "COMFORT", "MINIMAL", "캐주얼", "데일리", "편안함", "미니멀"));
            case WORKOUT -> styleTagKeys(List.of("WORKOUT", "SPORTY", "ACTIVE", "COMFORT", "운동", "스포티", "활동적", "편안함"));
            case DATE -> styleTagKeys(List.of("DATE", "NEAT", "POINT", "MINIMAL", "데이트", "깔끔", "포인트", "미니멀"));
            case FORMAL -> styleTagKeys(List.of("FORMAL", "OFFICIAL", "SMART", "MINIMAL", "격식", "포멀", "단정", "미니멀"));
        };
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    private String styleTagKey(String styleTag) {
        if (styleTag == null) {
            return "";
        }
        // 한글 태그는 그대로 두고 ASCII 태그만 case-insensitive 비교되도록 Locale.ROOT를 사용한다.
        return styleTag.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> readStyleTags(String styleTagsJson) {
        return RecommendationStyleTags.fromJson(styleTagsJson);
    }

    private enum Overlap {
        NONE,
        PARTIAL,
        SAME
    }

    /**
     * 색상 점수에서 쓰는 작은 팔레트 분류다. UNKNOWN은 중립 점수로 처리하므로 별도 그룹에 넣지 않는다.
     */
    private enum ColorGroup {
        NEUTRAL(EnumSet.of(ClothingColor.BLACK, ClothingColor.WHITE, ClothingColor.GRAY)),
        BLUE(EnumSet.of(ClothingColor.NAVY, ClothingColor.BLUE)),
        EARTH(EnumSet.of(ClothingColor.BROWN, ClothingColor.BEIGE)),
        ACCENT(EnumSet.of(ClothingColor.RED, ClothingColor.GREEN, ClothingColor.YELLOW));

        private final Set<ClothingColor> colors;

        ColorGroup(Set<ClothingColor> colors) {
            this.colors = colors;
        }

        private static ColorGroup from(ClothingColor color) {
            for (ColorGroup group : values()) {
                if (group.colors.contains(color)) {
                    return group;
                }
            }
            throw new IllegalArgumentException("unsupported color: " + color);
        }
    }
}
