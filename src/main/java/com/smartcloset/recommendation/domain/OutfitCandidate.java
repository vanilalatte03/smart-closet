package com.smartcloset.recommendation.domain;

import com.smartcloset.clothing.domain.ClothingItem;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 점수 계산을 위해 만든 임시 코디 후보다.
 *
 * <p>DB에 저장되는 엔티티가 아니며, 추천 결과를 저장할 때는 각 옷을
 * {@link RecommendationResultItem}으로 풀어서 기록한다.</p>
 */
public record OutfitCandidate(
        ClothingItem top,
        ClothingItem bottom,
        ClothingItem outer,
        int generationOrder
) {

    public OutfitCandidate {
        Objects.requireNonNull(top, "top must not be null");
        Objects.requireNonNull(bottom, "bottom must not be null");
        if (generationOrder < 0) {
            throw new IllegalArgumentException("generationOrder must not be negative");
        }
    }

    /**
     * 상의와 하의만으로 구성된 후보를 만든다.
     */
    public static OutfitCandidate withoutOuter(ClothingItem top, ClothingItem bottom, int generationOrder) {
        return new OutfitCandidate(top, bottom, null, generationOrder);
    }

    /**
     * 아우터까지 포함된 후보를 만들며 outer는 필수 값으로 검증한다.
     */
    public static OutfitCandidate withOuter(
            ClothingItem top,
            ClothingItem bottom,
            ClothingItem outer,
            int generationOrder
    ) {
        return new OutfitCandidate(top, bottom, Objects.requireNonNull(outer, "outer must not be null"), generationOrder);
    }

    /**
     * 후보가 아우터 슬롯을 포함하는지 확인한다.
     */
    public boolean hasOuter() {
        return outer != null;
    }

    /**
     * 후보를 구성하는 옷을 상의, 하의, 선택적 아우터 순서로 반환한다.
     */
    public List<ClothingItem> items() {
        if (outer == null) {
            return List.of(top, bottom);
        }
        return List.of(top, bottom, outer);
    }

    /**
     * 후보 비교는 순서보다 "같은 옷 묶음인가"가 중요하므로 id set으로 변환한다.
     */
    public Set<Long> itemIds() {
        return items().stream()
                .map(ClothingItem::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 후보의 옷 id 묶음이 순서와 슬롯 이름에 관계없이 같은지 비교한다.
     */
    public boolean hasSameItemSet(Set<Long> otherItemIds) {
        if (otherItemIds == null) {
            return false;
        }
        Long topId = top.getId();
        Long bottomId = bottom.getId();
        Long outerId = outer == null ? null : outer.getId();
        int distinctIds = topId == null ? 0 : 1;
        if (bottomId != null && !bottomId.equals(topId)) {
            distinctIds++;
        }
        if (outerId != null && !outerId.equals(topId) && !outerId.equals(bottomId)) {
            distinctIds++;
        }
        return otherItemIds.size() == distinctIds
                && (topId == null || otherItemIds.contains(topId))
                && (bottomId == null || otherItemIds.contains(bottomId))
                && (outerId == null || otherItemIds.contains(outerId));
    }

    /**
     * 최근 이력과 하나라도 같은 옷을 공유하는지 확인한다.
     */
    public boolean intersects(Set<Long> otherItemIds) {
        Objects.requireNonNull(otherItemIds, "otherItemIds must not be null");
        Long topId = top.getId();
        Long bottomId = bottom.getId();
        Long outerId = outer == null ? null : outer.getId();
        return (topId != null && otherItemIds.contains(topId))
                || (bottomId != null && otherItemIds.contains(bottomId))
                || (outerId != null && otherItemIds.contains(outerId));
    }

    /**
     * 점수 계산과 응답 변환에서 후보 옷들을 순차 처리할 때 사용한다.
     */
    public Stream<ClothingItem> stream() {
        return items().stream();
    }
}
