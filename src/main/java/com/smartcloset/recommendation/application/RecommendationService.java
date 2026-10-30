package com.smartcloset.recommendation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcloset.clothing.application.ClothingStyleTagMapper;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.clothing.repository.ClothingItemRepository;
import com.smartcloset.common.exception.ErrorCode;
import com.smartcloset.common.exception.SmartClosetException;
import com.smartcloset.common.observability.SmartClosetMetrics;
import com.smartcloset.recommendation.domain.OutfitCandidate;
import com.smartcloset.recommendation.domain.OutfitCandidateGenerator;
import com.smartcloset.recommendation.domain.OutfitSlot;
import com.smartcloset.recommendation.domain.RecommendationCandidateBudgeter;
import com.smartcloset.recommendation.domain.RecommendationFailureException;
import com.smartcloset.recommendation.domain.RecommendationFeedbackSentiment;
import com.smartcloset.recommendation.domain.RecommendationHistorySnapshot;
import com.smartcloset.recommendation.domain.RecommendationReasonGenerator;
import com.smartcloset.recommendation.domain.RecommendationResult;
import com.smartcloset.recommendation.domain.RecommendationResultItem;
import com.smartcloset.recommendation.domain.RecommendationScorer;
import com.smartcloset.recommendation.domain.RecommendationSituation;
import com.smartcloset.recommendation.domain.RecommendationThermalFeedback;
import com.smartcloset.recommendation.domain.ScoredOutfitCandidate;
import com.smartcloset.recommendation.domain.WeatherFilteredClothes;
import com.smartcloset.recommendation.domain.WeatherSuitabilityFilter;
import com.smartcloset.recommendation.domain.WearHistory;
import com.smartcloset.recommendation.domain.WearHistorySnapshot;
import com.smartcloset.recommendation.dto.RecommendationFeedbackRequest;
import com.smartcloset.recommendation.dto.RecommendationFeedbackResponse;
import com.smartcloset.recommendation.dto.RecommendationFeedbackStateResponse;
import com.smartcloset.recommendation.dto.RecommendationResponse;
import com.smartcloset.recommendation.dto.RecommendationWornResponse;
import com.smartcloset.recommendation.repository.RecommendationResultItemRepository;
import com.smartcloset.recommendation.repository.RecommendationResultRepository;
import com.smartcloset.recommendation.repository.WearHistoryRepository;
import com.smartcloset.user.domain.PreferenceJsonMapper;
import com.smartcloset.user.domain.User;
import com.smartcloset.user.repository.UserRepository;
import com.smartcloset.weather.application.WeatherProvider;
import com.smartcloset.weather.domain.ForecastPeriod;
import com.smartcloset.weather.domain.WeatherCondition;
import com.smartcloset.weather.domain.WeatherSnapshot;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 추천 생성, 이력 조회, 착용/피드백 기록을 조율하는 application service다.
 *
 * <p>Controller는 HTTP와 DTO만 담당하고, 이 서비스가 사용자 소유 데이터 조회,
 * 날씨 snapshot 확보, 후보 생성/점수화, 결과 저장을 하나의 use case로 묶는다.</p>
 */
@Service
public class RecommendationService {

    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MIN_HISTORY_LIMIT = 1;
    private static final int MAX_HISTORY_LIMIT = 50;
    private static final int SCORING_WEAR_HISTORY_LIMIT = 50;
    private static final int SCORING_RECOMMENDATION_HISTORY_LIMIT = 50;
    private static final int SCORING_FEEDBACK_HISTORY_LIMIT = 50;
    private static final int RECENT_RECOMMENDATION_FALLBACK_LIMIT = 5;
    private static final TypeReference<List<String>> REASONS_TYPE = new TypeReference<>() {
    };

    private final UserRepository userRepository;
    private final ClothingItemRepository clothingItemRepository;
    private final RecommendationResultRepository recommendationResultRepository;
    private final RecommendationResultItemRepository recommendationResultItemRepository;
    private final WearHistoryRepository wearHistoryRepository;
    private final WeatherProvider weatherProvider;
    private final PreferenceJsonMapper preferenceJsonMapper;
    private final ClothingStyleTagMapper clothingStyleTagMapper;
    private final SmartClosetMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WeatherSuitabilityFilter weatherSuitabilityFilter = new WeatherSuitabilityFilter();
    private final RecommendationCandidateBudgeter recommendationCandidateBudgeter = new RecommendationCandidateBudgeter();
    private final OutfitCandidateGenerator outfitCandidateGenerator = new OutfitCandidateGenerator();
    private final RecommendationScorer recommendationScorer = new RecommendationScorer();
    private final RecommendationReasonGenerator recommendationReasonGenerator = new RecommendationReasonGenerator();

    public RecommendationService(
            UserRepository userRepository,
            ClothingItemRepository clothingItemRepository,
            RecommendationResultRepository recommendationResultRepository,
            RecommendationResultItemRepository recommendationResultItemRepository,
            WearHistoryRepository wearHistoryRepository,
            WeatherProvider weatherProvider,
            PreferenceJsonMapper preferenceJsonMapper,
            ClothingStyleTagMapper clothingStyleTagMapper,
            SmartClosetMetrics metrics,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = userRepository;
        this.clothingItemRepository = clothingItemRepository;
        this.recommendationResultRepository = recommendationResultRepository;
        this.recommendationResultItemRepository = recommendationResultItemRepository;
        this.wearHistoryRepository = wearHistoryRepository;
        this.weatherProvider = weatherProvider;
        this.preferenceJsonMapper = preferenceJsonMapper;
        this.clothingStyleTagMapper = clothingStyleTagMapper;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 추천 요청 body가 없는 기존 경로에서 기본 상황과 현재 예보 시간대를 사용한다.
     */
    public RecommendationResponse createRecommendation(Long userId) {
        return createRecommendation(userId, RecommendationSituation.CASUAL, ForecastPeriod.CURRENT);
    }

    /**
     * 예보 시간대가 없는 추천 요청에서 현재 날씨 입력을 사용한다.
     */
    public RecommendationResponse createRecommendation(Long userId, RecommendationSituation situation) {
        return createRecommendation(userId, situation, ForecastPeriod.CURRENT);
    }

    /**
     * 날씨 조회는 외부 API 호출 가능성이 있어 transaction 밖에서 먼저 수행한다.
     * 이후 저장이 필요한 추천 생성 단계만 명시적인 TransactionTemplate 안으로 넣는다.
     */
    public RecommendationResponse createRecommendation(
            Long userId,
            RecommendationSituation situation,
            ForecastPeriod forecastPeriod
    ) {
        Timer.Sample sample = metrics.startTimer();
        ForecastPeriod resolvedForecastPeriod = forecastPeriod == null ? ForecastPeriod.CURRENT : forecastPeriod;
        RecommendationSituation resolvedSituation = situation == null ? RecommendationSituation.CASUAL : situation;
        try {
            WeatherSnapshot weather = weatherProvider.getWeather(userId, resolvedForecastPeriod);
            LocalDateTime requestedAt = LocalDateTime.now();
            RecommendationResponse response = Objects.requireNonNull(transactionTemplate.execute(status ->
                    createRecommendationInTransaction(userId, resolvedSituation, resolvedForecastPeriod, weather, requestedAt)
            ));
            metrics.recordRecommendation(sample, resolvedSituation, resolvedForecastPeriod, "success");
            return response;
        } catch (RuntimeException exception) {
            metrics.recordRecommendation(sample, resolvedSituation, resolvedForecastPeriod, "failure");
            throw exception;
        }
    }

    private RecommendationResponse createRecommendationInTransaction(
            Long userId,
            RecommendationSituation situation,
            ForecastPeriod forecastPeriod,
            WeatherSnapshot weather,
            LocalDateTime requestedAt
    ) {
        User user = findUser(userId);
        WeatherCondition condition = weather.condition();
        List<ClothingItem> activeClothes = clothingItemRepository.findByUserIdAndArchivedFalseOrderByIdAsc(userId);
        List<WearHistorySnapshot> wearHistories = findWearHistorySnapshots(userId, requestedAt);
        List<RecommendationHistorySnapshot> recommendationHistories = findRecommendationHistories(userId, requestedAt);
        List<ClothingColor> preferredColors = preferenceJsonMapper.readColors(user.getPreferredColorsJson());
        List<ClothingMaterial> preferredMaterials = preferenceJsonMapper.readMaterials(user.getPreferredMaterialsJson());
        List<String> preferredStyleTags = preferenceJsonMapper.readStyleTags(user.getStyleTagsJson());

        try {
            // 추천 파이프라인: 날씨 필터 -> 후보 예산 적용 -> 스트리밍 점수화 -> 결정적 tie-break -> snapshot 저장.
            WeatherFilteredClothes filteredClothes = weatherSuitabilityFilter.filter(activeClothes, condition);
            WeatherFilteredClothes budgetedClothes = recommendationCandidateBudgeter.apply(
                    filteredClothes,
                    condition,
                    wearHistories,
                    recommendationHistories,
                    requestedAt,
                    preferredColors,
                    preferredMaterials,
                    preferredStyleTags,
                    situation
            );
            ScoredOutfitCandidate best = selectBestCandidate(
                    budgetedClothes,
                    condition,
                    wearHistories,
                    recommendationHistories,
                    requestedAt,
                    preferredColors,
                    preferredMaterials,
                    preferredStyleTags,
                    situation
            );
            List<String> reasons = recommendationReasonGenerator.generate(
                    best.candidate(),
                    best.score(),
                    condition,
                    wearHistories,
                    recommendationHistories,
                    requestedAt,
                    preferredStyleTags,
                    situation
            );
            RecommendationResult recommendationResult = saveRecommendation(user, situation, forecastPeriod, weather, best, reasons);
            return RecommendationResponse.from(recommendationResult, best.candidate(), reasons, clothingStyleTagMapper);
        } catch (RecommendationFailureException exception) {
            throw toSmartClosetException(exception);
        }
    }

    private ScoredOutfitCandidate selectBestCandidate(
            WeatherFilteredClothes filteredClothes,
            WeatherCondition condition,
            List<WearHistorySnapshot> wearHistories,
            List<RecommendationHistorySnapshot> recommendationHistories,
            LocalDateTime requestedAt,
            List<ClothingColor> preferredColors,
            List<ClothingMaterial> preferredMaterials,
            List<String> preferredStyleTags,
            RecommendationSituation situation
    ) {
        ScoredOutfitCandidate[] best = new ScoredOutfitCandidate[1];
        var scoreCandidate = recommendationScorer.prepare(
                condition, wearHistories, recommendationHistories, requestedAt,
                preferredColors, preferredMaterials, preferredStyleTags, situation
        );
        outfitCandidateGenerator.forEach(filteredClothes, condition, candidate -> {
            ScoredOutfitCandidate scored = new ScoredOutfitCandidate(
                    candidate,
                    scoreCandidate.apply(candidate)
            );
            best[0] = best[0] == null ? scored : recommendationScorer.betterOf(scored, best[0], condition);
        });
        return Objects.requireNonNull(best[0], "best candidate must not be null");
    }

    /**
     * 최신순 recommendation id를 먼저 고른 뒤, 필요한 item/worn 정보를 batch 조회해 N+1을 피한다.
     */
    @Transactional(readOnly = true)
    public List<RecommendationResponse> getRecommendationHistory(Long userId, Integer limit) {
        int resolvedLimit = validateHistoryLimit(limit);
        List<Long> orderedResultIds = recommendationResultRepository.findIdsByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(0, resolvedLimit)
        );
        if (orderedResultIds.isEmpty()) {
            return List.of();
        }

        Map<Long, List<RecommendationResultItem>> itemsByResultId = findItemsByRecommendationResultIds(orderedResultIds);
        Map<Long, LocalDateTime> wornAtByResultId = findWornAtByRecommendationResultIds(orderedResultIds);
        return findResultsInOrderedIds(orderedResultIds).stream()
                .map(result -> RecommendationResponse.from(
                        result,
                        itemsByResultId.getOrDefault(result.getId(), List.of()),
                        readReasonsJson(result.getReasonsJson()),
                        wornAtByResultId.get(result.getId()),
                        clothingStyleTagMapper
                ))
                .toList();
    }

    /**
     * 같은 추천을 여러 번 착용 완료해도 기존 WearHistory를 재사용해 API를 멱등하게 유지한다.
     */
    @Transactional
    public RecommendationWornResponse markWorn(Long userId, Long recommendationId) {
        RecommendationResult recommendationResult = recommendationResultRepository
                .findByIdAndUserIdForWorn(recommendationId, userId)
                .orElseThrow(() -> new SmartClosetException(ErrorCode.RECOMMENDATION_NOT_FOUND));

        Optional<WearHistory> existingWearHistory = wearHistoryRepository.findByRecommendationResultId(
                recommendationResult.getId()
        );
        if (existingWearHistory.isPresent()) {
            recommendationResult.markWorn();
            return RecommendationWornResponse.of(recommendationResult.getId(), existingWearHistory.get().getWornAt());
        }

        recommendationResult.markWorn();
        LocalDateTime wornAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        WearHistory wearHistory = wearHistoryRepository.save(WearHistory.record(
                recommendationResult.getUser(),
                recommendationResult,
                wornAt
        ));
        return RecommendationWornResponse.of(recommendationResult.getId(), wearHistory.getWornAt());
    }

    /**
     * 추천 피드백 PUT 계약에 맞춰 누락된 body나 필드는 null로 해석해 기존 값을 전체 교체한다.
     */
    @Transactional
    public RecommendationFeedbackResponse replaceFeedback(
            Long userId,
            Long recommendationId,
            RecommendationFeedbackRequest request
    ) {
        RecommendationResult recommendationResult = recommendationResultRepository
                .findByIdAndUserId(recommendationId, userId)
                .orElseThrow(() -> new SmartClosetException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        RecommendationFeedbackRequest resolvedRequest = request == null
                ? new RecommendationFeedbackRequest(null, null)
                : request;
        RecommendationFeedbackSentiment sentiment = resolvedRequest.sentiment();
        RecommendationThermalFeedback thermal = resolvedRequest.thermal();

        recommendationResult.replaceFeedback(sentiment, thermal, LocalDateTime.now());
        return new RecommendationFeedbackResponse(
                recommendationResult.getId(),
                RecommendationFeedbackStateResponse.from(recommendationResult)
        );
    }

    private RecommendationResult saveRecommendation(
            User user,
            RecommendationSituation situation,
            ForecastPeriod forecastPeriod,
            WeatherSnapshot weather,
            ScoredOutfitCandidate best,
            List<String> reasons
    ) {
        RecommendationResult recommendationResult = RecommendationResult.create(
                user,
                situation,
                forecastPeriod,
                weather,
                best.score(),
                writeReasonsJson(reasons)
        );
        RecommendationResult savedRecommendationResult = recommendationResultRepository.save(recommendationResult);
        recommendationResultItemRepository.saveAll(createRecommendationResultItems(
                savedRecommendationResult,
                best.candidate()
        ));
        return savedRecommendationResult;
    }

    private List<RecommendationResultItem> createRecommendationResultItems(
            RecommendationResult recommendationResult,
            OutfitCandidate candidate
    ) {
        List<RecommendationResultItem> resultItems = new ArrayList<>();
        resultItems.add(RecommendationResultItem.of(recommendationResult, candidate.top(), OutfitSlot.TOP));
        resultItems.add(RecommendationResultItem.of(recommendationResult, candidate.bottom(), OutfitSlot.BOTTOM));
        if (candidate.hasOuter()) {
            resultItems.add(RecommendationResultItem.of(recommendationResult, candidate.outer(), OutfitSlot.OUTER));
        }
        return resultItems;
    }

    /**
     * 점수 계산에는 최근 착용 여부만 필요하므로 entity 대신 계산 전용 snapshot으로 축약한다.
     */
    private List<WearHistorySnapshot> findWearHistorySnapshots(Long userId, LocalDateTime requestedAt) {
        List<WearHistory> wearHistories = wearHistoryRepository.findByUserIdAndWornAtGreaterThanEqualOrderByWornAtDesc(
                userId,
                requestedAt.minusDays(7),
                PageRequest.of(0, SCORING_WEAR_HISTORY_LIMIT)
        );
        List<Long> orderedResultIds = wearHistories.stream()
                .map(history -> history.getRecommendationResult().getId())
                .toList();
        Map<Long, Set<Long>> itemIdsByResultId = findItemIdsByRecommendationResultIds(orderedResultIds);

        return wearHistories.stream()
                .map(history -> {
                    Long recommendationResultId = history.getRecommendationResult().getId();
                    return new WearHistorySnapshot(
                            recommendationResultId,
                            history.getWornAt(),
                            itemIdsByResultId.getOrDefault(recommendationResultId, Set.of())
                    );
                })
                .toList();
    }

    /**
     * 추천 이력 페널티와 피드백 보정은 서로 다른 기간을 보므로 id를 합친 뒤 순서를 보존해 조회한다.
     */
    private List<RecommendationHistorySnapshot> findRecommendationHistories(Long userId, LocalDateTime requestedAt) {
        List<Long> lastSevenDaysIds = recommendationResultRepository
                .findIdsByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        userId,
                        requestedAt.minusDays(7),
                        PageRequest.of(0, SCORING_RECOMMENDATION_HISTORY_LIMIT)
                );
        List<Long> recentFiveIds = recommendationResultRepository.findIdsByUserIdOrderByCreatedAtDesc(
                userId,
                PageRequest.of(0, RECENT_RECOMMENDATION_FALLBACK_LIMIT)
        );
        List<Long> feedbackIds = recommendationResultRepository
                .findIdsByUserIdAndFeedbackUpdatedAtGreaterThanEqualOrderByFeedbackUpdatedAtDesc(
                        userId,
                        requestedAt.minusDays(14),
                        PageRequest.of(0, SCORING_FEEDBACK_HISTORY_LIMIT)
                );

        // Preserve previous recommendation-history priority, then include the MVP6 feedback window.
        LinkedHashSet<Long> orderedHistoryIds = new LinkedHashSet<>();
        orderedHistoryIds.addAll(lastSevenDaysIds);
        orderedHistoryIds.addAll(recentFiveIds);
        orderedHistoryIds.addAll(feedbackIds);
        return findRecommendationHistorySnapshots(List.copyOf(orderedHistoryIds));
    }

    private List<RecommendationHistorySnapshot> findRecommendationHistorySnapshots(List<Long> orderedResultIds) {
        if (orderedResultIds.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<Long>> itemIdsByResultId = findItemIdsByRecommendationResultIds(orderedResultIds);
        return findResultsInOrderedIds(orderedResultIds).stream()
                .map(result -> new RecommendationHistorySnapshot(
                        result.getId(),
                        result.getCreatedAt(),
                        itemIdsByResultId.getOrDefault(result.getId(), Set.of()),
                        result.getWeatherTemperature(),
                        result.getSentimentFeedback(),
                        result.getThermalFeedback(),
                        result.getFeedbackUpdatedAt()
                ))
                .toList();
    }

    private List<RecommendationResult> findResultsInOrderedIds(List<Long> orderedResultIds) {
        Map<Long, RecommendationResult> resultById = recommendationResultRepository.findByIdIn(orderedResultIds)
                .stream()
                .collect(Collectors.toMap(
                        RecommendationResult::getId,
                        Function.identity()
                ));
        // IN queries do not guarantee row order; the preselected id list is the source of truth.
        return orderedResultIds.stream()
                .map(resultId -> requireResult(resultById, resultId))
                .toList();
    }

    /**
     * 결과 id별 빈 리스트를 먼저 만들어, 옷이 없는 예외 상황에서도 응답 순서와 key를 보존한다.
     */
    private Map<Long, List<RecommendationResultItem>> findItemsByRecommendationResultIds(List<Long> orderedResultIds) {
        if (orderedResultIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<RecommendationResultItem>> itemsByResultId = new LinkedHashMap<>();
        for (Long resultId : orderedResultIds) {
            itemsByResultId.put(resultId, new ArrayList<>());
        }

        recommendationResultItemRepository.findByRecommendationResultIdInWithClothingItem(orderedResultIds)
                .forEach(item -> {
                    List<RecommendationResultItem> resultItems = itemsByResultId.get(item.getRecommendationResult().getId());
                    if (resultItems != null) {
                        resultItems.add(item);
                    }
                });
        return itemsByResultId;
    }

    private Map<Long, Set<Long>> findItemIdsByRecommendationResultIds(List<Long> orderedResultIds) {
        return findItemsByRecommendationResultIds(orderedResultIds).entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(RecommendationResultItem::getClothingItem)
                                .map(ClothingItem::getId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toUnmodifiableSet()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<Long, LocalDateTime> findWornAtByRecommendationResultIds(List<Long> orderedResultIds) {
        if (orderedResultIds.isEmpty()) {
            return Map.of();
        }
        return wearHistoryRepository.findByRecommendationResultIdIn(orderedResultIds)
                .stream()
                .collect(Collectors.toMap(
                        history -> history.getRecommendationResult().getId(),
                        WearHistory::getWornAt
                ));
    }

    private RecommendationResult requireResult(Map<Long, RecommendationResult> resultById, Long resultId) {
        RecommendationResult result = resultById.get(resultId);
        if (result == null) {
            throw new SmartClosetException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return result;
    }

    private String writeReasonsJson(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException exception) {
            throw new SmartClosetException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private List<String> readReasonsJson(String reasonsJson) {
        try {
            return objectMapper.readValue(reasonsJson, REASONS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SmartClosetException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private int validateHistoryLimit(Integer limit) {
        int resolvedLimit = limit == null ? DEFAULT_HISTORY_LIMIT : limit;
        if (resolvedLimit < MIN_HISTORY_LIMIT || resolvedLimit > MAX_HISTORY_LIMIT) {
            throw new SmartClosetException(ErrorCode.INVALID_PAGINATION);
        }
        return resolvedLimit;
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new SmartClosetException(ErrorCode.USER_NOT_FOUND));
    }

    private SmartClosetException toSmartClosetException(RecommendationFailureException exception) {
        return new SmartClosetException(ErrorCode.valueOf(exception.failureCode().name()));
    }
}
