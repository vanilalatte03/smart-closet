package com.smartcloset.recommendation.domain;

import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.clothing;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.feedbackHistory;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.user;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.wearHistory;

import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.user.domain.User;
import com.smartcloset.weather.domain.WeatherCondition;
import com.smartcloset.weather.domain.WeatherType;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * DB/외부 API와 JaCoCo agent 없이 실행하는 수동 비교용 벤치마크다. CI 시간 임계값으로 쓰지 않는다.
 */
public final class RecommendationScoringBenchmark {

    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASUREMENT_ROUNDS = 7;
    private static volatile ScoredOutfitCandidate resultSink;

    private RecommendationScoringBenchmark() {
    }

    public static void main(String[] args) {
        String mode = args.length == 0 ? "prepared" : args[0];
        if (!mode.equals("baseline") && !mode.equals("prepared")) {
            throw new IllegalArgumentException("mode must be baseline or prepared");
        }
        System.out.printf("JVM=%s %s; heap=512m; warmup=%d; measurements=%d%n",
                System.getProperty("java.vendor"), System.getProperty("java.version"),
                WARMUP_ROUNDS, MEASUREMENT_ROUNDS);
        System.out.println("mode,items_per_category,histories,candidates,median_ms,median_allocated_bytes,best_signature");
        for (int size : new int[]{8, 16, 32}) {
            for (int historyCount : new int[]{0, 50}) {
                Fixture fixture = new Fixture(size, historyCount);
                for (int round = 0; round < WARMUP_ROUNDS; round++) {
                    evaluate(fixture, mode);
                }
                double[] millis = new double[MEASUREMENT_ROUNDS];
                long[] allocations = new long[MEASUREMENT_ROUNDS];
                ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
                if (!threadBean.isThreadAllocatedMemorySupported()) {
                    throw new IllegalStateException("Thread allocation measurement is required");
                }
                threadBean.setThreadAllocatedMemoryEnabled(true);
                for (int round = 0; round < MEASUREMENT_ROUNDS; round++) {
                    long beforeBytes = threadBean.getCurrentThreadAllocatedBytes();
                    long start = System.nanoTime();
                    evaluate(fixture, mode);
                    millis[round] = (System.nanoTime() - start) / 1_000_000.0;
                    allocations[round] = threadBean.getCurrentThreadAllocatedBytes() - beforeBytes;
                }
                Arrays.sort(millis);
                Arrays.sort(allocations);
                RecommendationScore score = resultSink.score();
                OutfitCandidate best = resultSink.candidate();
                String signature = String.format(Locale.ROOT, "%s:%s:%s:%d:%d:%d:%d:%d:%d",
                        best.top().getId(), best.bottom().getId(), best.hasOuter() ? best.outer().getId() : "none",
                        score.totalScore(), score.weatherScore(), score.colorScore(), score.wearHistoryScore(),
                        score.recommendationHistoryScore(), score.preferenceScore());
                System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%.3f,%d,%s%n",
                        mode, size, historyCount, size * size * (size + 1),
                        millis[MEASUREMENT_ROUNDS / 2], allocations[MEASUREMENT_ROUNDS / 2], signature);
            }
        }
    }

    private static void evaluate(Fixture fixture, String mode) {
        Function<OutfitCandidate, RecommendationScore> scoreCandidate = mode.equals("prepared")
                ? fixture.scorer.prepare(
                        fixture.weather, fixture.wearHistories, fixture.histories, fixture.requestedAt,
                        List.of(ClothingColor.NAVY), List.of(ClothingMaterial.COTTON), List.of(" OFFICE ", "미니멀"),
                        RecommendationSituation.WORK)
                : candidate -> fixture.scorer.score(
                candidate, fixture.weather, fixture.wearHistories, fixture.histories, fixture.requestedAt,
                List.of(ClothingColor.NAVY), List.of(ClothingMaterial.COTTON), List.of(" OFFICE ", "미니멀"),
                RecommendationSituation.WORK
        );
        ScoredOutfitCandidate[] best = new ScoredOutfitCandidate[1];
        fixture.generator.forEach(fixture.clothes, fixture.weather, candidate -> {
            ScoredOutfitCandidate scored = new ScoredOutfitCandidate(candidate, scoreCandidate.apply(candidate));
            best[0] = best[0] == null ? scored : fixture.scorer.betterOf(scored, best[0], fixture.weather);
        });
        resultSink = best[0];
    }

    private static final class Fixture {
        private final RecommendationScorer scorer = new RecommendationScorer();
        private final OutfitCandidateGenerator generator = new OutfitCandidateGenerator();
        private final WeatherCondition weather = WeatherCondition.of(18, WeatherType.CLOUDY, false, false);
        private final LocalDateTime requestedAt = LocalDateTime.of(2026, 10, 30, 12, 0);
        private final WeatherFilteredClothes clothes;
        private final List<WearHistorySnapshot> wearHistories = new ArrayList<>();
        private final List<RecommendationHistorySnapshot> histories = new ArrayList<>();

        private Fixture(int size, int historyCount) {
            User owner = user(1);
            List<ClothingItem> items = new ArrayList<>();
            long id = 1;
            for (ClothingCategory category : List.of(ClothingCategory.TOP, ClothingCategory.BOTTOM, ClothingCategory.OUTER)) {
                for (int index = 0; index < size; index++) {
                    items.add(clothing(id++, owner, category,
                            ClothingColor.values()[index % ClothingColor.values().length],
                            ClothingMaterial.values()[index % ClothingMaterial.values().length],
                            -10, 40, index % 2 == 0, List.of("Office", " 미니멀 ", "tag-" + index)));
                }
            }
            clothes = new WeatherSuitabilityFilter().filter(items, weather);
            for (int index = 0; index < historyCount; index++) {
                ClothingItem top = items.get(index % size);
                ClothingItem bottom = items.get(size + index % size);
                LocalDateTime at = requestedAt.minusHours(index * 5L);
                wearHistories.add(wearHistory(index + 1L, at, top, bottom));
                histories.add(feedbackHistory(index + 1L, at, 18,
                        index % 2 == 0 ? RecommendationFeedbackSentiment.LIKED : RecommendationFeedbackSentiment.DISLIKED,
                        RecommendationThermalFeedback.TOO_COLD, at, top, bottom));
            }
        }
    }
}
