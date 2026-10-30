package com.smartcloset.recommendation.domain;

import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.clothing;
import static com.smartcloset.recommendation.domain.RecommendationDomainTestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutfitCandidateItemSetTest {

    @Test
    void matchesMaterializedSetSemanticsForNullDuplicateAndDistinctIds() {
        for (Long topId : Arrays.asList(null, 1L, 2L)) {
            for (Long bottomId : Arrays.asList(null, 1L, 2L)) {
                for (Long outerId : Arrays.asList(null, 1L, 2L)) {
                    ClothingItem top = item(topId, ClothingCategory.TOP);
                    ClothingItem bottom = item(bottomId, ClothingCategory.BOTTOM);
                    verifySets(OutfitCandidate.withoutOuter(top, bottom, 0));
                    verifySets(OutfitCandidate.withOuter(top, bottom, item(outerId, ClothingCategory.OUTER), 0));
                }
            }
        }
    }

    private void verifySets(OutfitCandidate candidate) {
        assertThat(candidate.hasSameItemSet(null)).isFalse();
        for (int mask = 0; mask < 16; mask++) {
            Set<Long> history = new HashSet<>();
            Long[] ids = {null, 1L, 2L, 3L};
            for (int bit = 0; bit < ids.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    history.add(ids[bit]);
                }
            }
            assertThat(candidate.hasSameItemSet(history)).isEqualTo(candidate.itemIds().equals(history));
            assertThat(candidate.intersects(history))
                    .isEqualTo(candidate.itemIds().stream().anyMatch(history::contains));
        }
    }

    private ClothingItem item(Long id, ClothingCategory category) {
        ClothingItem item = clothing(1, user(1), category, ClothingColor.BLACK,
                ClothingMaterial.COTTON, 0, 30, true);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }
}
