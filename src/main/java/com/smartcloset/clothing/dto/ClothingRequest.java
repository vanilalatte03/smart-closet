package com.smartcloset.clothing.dto;

import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingMaterial;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ClothingRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 50, message = "name must be 50 characters or less")
        String name,

        @NotNull(message = "category is required")
        ClothingCategory category,

        @NotNull(message = "color is required")
        ClothingColor color,

        @NotNull(message = "material is required")
        ClothingMaterial material,

        @NotNull(message = "minTemperature is required")
        Integer minTemperature,

        @NotNull(message = "maxTemperature is required")
        Integer maxTemperature,

        @NotNull(message = "rainSuitable is required")
        Boolean rainSuitable,

        List<@NotNull(message = "style tag must not be null")
                @Size(max = 30, message = "style tag must be 30 characters or less") String> styleTags
) {

    public ClothingRequest {
        // null 요소는 생성자가 아닌 Bean Validation에서 필드 오류로 처리한다.
        styleTags = styleTags == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(styleTags));
    }

    @AssertTrue(message = "minTemperature must be less than or equal to maxTemperature")
    public boolean isTemperatureRangeValid() {
        if (minTemperature == null || maxTemperature == null) {
            return true;
        }
        return minTemperature <= maxTemperature;
    }
}
