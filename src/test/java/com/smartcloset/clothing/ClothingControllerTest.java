package com.smartcloset.clothing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.clothing.infrastructure.file.ClothingImageProperties;
import com.smartcloset.clothing.repository.ClothingItemRepository;
import com.smartcloset.security.CurrentUserPrincipal;
import com.smartcloset.security.JwtTokenProvider;
import com.smartcloset.user.domain.User;
import com.smartcloset.user.domain.UserRole;
import com.smartcloset.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ClothingControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClothingItemRepository clothingItemRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ClothingImageProperties clothingImageProperties;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void createsClothingWithArchivedFalse() throws Exception {
        User user = userRepository.save(User.createSeedUser("create-clothing-user"));
        Map<String, Object> request = validRequest();

        mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.name").value("그레이 후드"))
                .andExpect(jsonPath("$.data.category").value("TOP"))
                .andExpect(jsonPath("$.data.color").value("GRAY"))
                .andExpect(jsonPath("$.data.material").value("COTTON"))
                .andExpect(jsonPath("$.data.minTemperature").value(5))
                .andExpect(jsonPath("$.data.maxTemperature").value(18))
                .andExpect(jsonPath("$.data.rainSuitable").value(false))
                .andExpect(jsonPath("$.data.styleTags").isArray())
                .andExpect(jsonPath("$.data.styleTags").isEmpty())
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    void normalizesAndPersistsClothingStyleTags() throws Exception {
        User user = userRepository.save(User.createSeedUser("style-tag-user"));
        Map<String, Object> request = validRequest();
        request.put("styleTags", List.of(" MINIMAL ", "minimal", "OFFICE", " ", "오피스", "오피스"));

        MvcResult result = mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.styleTags[0]").value("MINIMAL"))
                .andExpect(jsonPath("$.data.styleTags[1]").value("OFFICE"))
                .andExpect(jsonPath("$.data.styleTags[2]").value("오피스"))
                .andExpect(jsonPath("$.data.styleTags[3]").doesNotExist())
                .andReturn();

        long clothingId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();

        mockMvc.perform(get("/api/clothes/{clothingId}", clothingId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.styleTags[0]").value("MINIMAL"))
                .andExpect(jsonPath("$.data.styleTags[1]").value("OFFICE"))
                .andExpect(jsonPath("$.data.styleTags[2]").value("오피스"))
                .andExpect(jsonPath("$.data.styleTags[3]").doesNotExist());
    }

    @Test
    void rejectsNullStyleTagOnCreateWithoutPersistingClothing() throws Exception {
        User user = userRepository.save(User.createSeedUser("null-style-create-user"));
        Map<String, Object> request = validRequest();
        request.put("styleTags", java.util.Arrays.asList("MINIMAL", null));
        long countBefore = clothingItemRepository.count();

        MvcResult result = mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        assertThat(clothingItemRepository.count()).isEqualTo(countBefore);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("METHOD_ARGUMENT_NOT_VALID");
        assertThat(error.path("details").findValuesAsText("field")).contains("styleTags[1]");
        assertThat(error.path("details").findValuesAsText("message")).contains("style tag must not be null");
    }

    @Test
    void rejectsNullStyleTagOnUpdateWithoutChangingClothing() throws Exception {
        User user = userRepository.save(User.createSeedUser("null-style-update-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();
        MvcResult before = mockMvc.perform(get("/api/clothes/{clothingId}", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Object> request = validRequest();
        request.put("styleTags", java.util.Arrays.asList("MINIMAL", null));

        MvcResult result = mockMvc.perform(put("/api/clothes/{clothingId}", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        clothingItemRepository.flush();
        MvcResult after = mockMvc.perform(get("/api/clothes/{clothingId}", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(after.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(before.getResponse().getContentAsString()));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.path("code").asText()).isEqualTo("METHOD_ARGUMENT_NOT_VALID");
        assertThat(error.path("details").findValuesAsText("field")).contains("styleTags[1]");
        assertThat(error.path("details").findValuesAsText("message")).contains("style tag must not be null");
    }

    @Test
    void treatsNullStyleTagsAsEmptyOnCreateAndUpdate() throws Exception {
        User user = userRepository.save(User.createSeedUser("null-style-list-user"));
        Map<String, Object> request = validRequest();
        request.put("styleTags", null);

        MvcResult result = mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.styleTags").isArray())
                .andExpect(jsonPath("$.data.styleTags").isEmpty())
                .andReturn();
        long clothingId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        Map<String, Object> taggedRequest = validRequest();
        taggedRequest.put("styleTags", List.of("MINIMAL"));
        mockMvc.perform(put("/api/clothes/{clothingId}", clothingId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taggedRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.styleTags[0]").value("MINIMAL"));

        mockMvc.perform(put("/api/clothes/{clothingId}", clothingId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.styleTags").isArray())
                .andExpect(jsonPath("$.data.styleTags").isEmpty());
    }

    @Test
    void clothingApisRequireBearerToken() throws Exception {
        Map<String, Object> request = validRequest();

        mockMvc.perform(post("/api/clothes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/clothes"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/clothes/archived"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/clothes/{clothingId}", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/clothes/{clothingId}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/clothes/{clothingId}/archive", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/clothes/{clothingId}/unarchive", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", 1L)
                        .file(jpegFile("image.jpg"))
                        .with(servletRequest -> {
                            servletRequest.setMethod("PUT");
                            return servletRequest;
                        }))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/clothes/{clothingId}/image", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/clothes/{clothingId}/image", 1L))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/clothes/analyze-image")
                        .file(jpegFile("shirt.jpg")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsOnlyActiveClothesForCurrentUserOrderedById() throws Exception {
        User targetUser = userRepository.save(User.createSeedUser("target-user"));
        User otherUser = userRepository.save(User.createSeedUser("other-user"));

        ClothingItem first = clothingItemRepository.save(ClothingItem.create(
                targetUser,
                "화이트 셔츠",
                ClothingCategory.TOP,
                ClothingColor.WHITE,
                ClothingMaterial.COTTON,
                5,
                25,
                false
        ));
        ClothingItem archived = ClothingItem.create(
                targetUser,
                "보관 니트",
                ClothingCategory.TOP,
                ClothingColor.GRAY,
                ClothingMaterial.KNIT,
                0,
                16,
                false
        );
        archived.archive();
        clothingItemRepository.save(archived);
        ClothingItem second = clothingItemRepository.save(ClothingItem.create(
                targetUser,
                "블랙 팬츠",
                ClothingCategory.BOTTOM,
                ClothingColor.BLACK,
                ClothingMaterial.DENIM,
                0,
                22,
                false
        ));
        clothingItemRepository.save(ClothingItem.create(
                otherUser,
                "다른 사용자 코트",
                ClothingCategory.OUTER,
                ClothingColor.NAVY,
                ClothingMaterial.WOOL,
                -10,
                12,
                false
        ));
        clothingItemRepository.flush();

        MvcResult result = mockMvc.perform(get("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(first.getId());
        assertThat(data.get(1).get("id").asLong()).isEqualTo(second.getId());
        for (JsonNode item : data) {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.get("archived").asBoolean()).isFalse();
        }
    }

    @Test
    void returnsOnlyArchivedClothesForCurrentUserOrderedById() throws Exception {
        User targetUser = userRepository.save(User.createSeedUser("target-user"));
        User otherUser = userRepository.save(User.createSeedUser("other-user"));
        clothingItemRepository.save(createTop(targetUser, "화이트 셔츠"));
        ClothingItem firstArchived = createTop(targetUser, "보관 니트");
        firstArchived.archive();
        clothingItemRepository.save(firstArchived);
        ClothingItem secondArchived = ClothingItem.create(
                targetUser,
                "보관 팬츠",
                ClothingCategory.BOTTOM,
                ClothingColor.BLACK,
                ClothingMaterial.DENIM,
                0,
                22,
                false
        );
        secondArchived.archive();
        clothingItemRepository.save(secondArchived);
        ClothingItem otherArchived = createTop(otherUser, "다른 사용자 셔츠");
        otherArchived.archive();
        clothingItemRepository.save(otherArchived);
        clothingItemRepository.flush();

        MvcResult result = mockMvc.perform(get("/api/clothes/archived")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(firstArchived.getId());
        assertThat(data.get(1).get("id").asLong()).isEqualTo(secondArchived.getId());
        for (JsonNode item : data) {
            assertThat(item.has("userId")).isFalse();
            assertThat(item.get("archived").asBoolean()).isTrue();
        }
    }

    @Test
    void returnsInvalidRequestWhenRequestValidationFails() throws Exception {
        User user = userRepository.save(User.createSeedUser("invalid-request-user"));
        Map<String, Object> request = validRequest();
        request.put("name", " ");
        request.put("minTemperature", 20);
        request.put("maxTemperature", 10);

        mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METHOD_ARGUMENT_NOT_VALID"))
                .andExpect(jsonPath("$.message").value("요청 본문 검증에 실패했습니다."))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void returnsInvalidRequestWhenStyleTagExceedsMaxLength() throws Exception {
        User user = userRepository.save(User.createSeedUser("long-style-tag-user"));
        Map<String, Object> request = validRequest();
        request.put("styleTags", List.of("1234567890123456789012345678901"));

        mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METHOD_ARGUMENT_NOT_VALID"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void returnsInvalidRequestWhenRequiredBooleanIsMissing() throws Exception {
        User user = userRepository.save(User.createSeedUser("missing-boolean-user"));
        Map<String, Object> request = validRequest();
        request.remove("rainSuitable");

        mockMvc.perform(post("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METHOD_ARGUMENT_NOT_VALID"))
                .andExpect(jsonPath("$.details[0].field").value("rainSuitable"));
    }

    @Test
    void returnsUserNotFoundWhenTokenSubjectDoesNotExist() throws Exception {
        String missingUserToken = jwtTokenProvider.createAccessToken(
                new CurrentUserPrincipal(99999L, "missing-user@example.com", UserRole.USER));

        mockMvc.perform(get("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + missingUserToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void getsClothingOnlyWhenOwnedByCurrentUser() throws Exception {
        User targetUser = userRepository.save(User.createSeedUser("target-user"));
        User otherUser = userRepository.save(User.createSeedUser("other-user"));
        ClothingItem targetClothing = clothingItemRepository.save(createTop(targetUser, "화이트 셔츠"));
        ClothingItem otherClothing = clothingItemRepository.save(createTop(otherUser, "다른 사용자 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(get("/api/clothes/{clothingId}", targetClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(targetClothing.getId()))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.name").value("화이트 셔츠"));

        mockMvc.perform(get("/api/clothes/{clothingId}", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));
    }

    @Test
    void updatesClothingDetailsWithoutChangingArchived() throws Exception {
        User user = userRepository.save(User.createSeedUser("target-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();

        Map<String, Object> request = validRequest();
        request.put("name", "웜 그레이 니트");
        request.put("material", "KNIT");
        request.put("minTemperature", 3);
        request.put("maxTemperature", 16);
        request.put("styleTags", List.of(" OFFICE ", "MINIMAL"));

        mockMvc.perform(put("/api/clothes/{clothingId}", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.name").value("웜 그레이 니트"))
                .andExpect(jsonPath("$.data.material").value("KNIT"))
                .andExpect(jsonPath("$.data.minTemperature").value(3))
                .andExpect(jsonPath("$.data.maxTemperature").value(16))
                .andExpect(jsonPath("$.data.styleTags[0]").value("OFFICE"))
                .andExpect(jsonPath("$.data.styleTags[1]").value("MINIMAL"))
                .andExpect(jsonPath("$.data.archived").value(false));
    }

    @Test
    void archivesClothingIdempotentlyAndExcludesItFromActiveList() throws Exception {
        User user = userRepository.save(User.createSeedUser("target-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(patch("/api/clothes/{clothingId}/archive", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.archived").value(true))
                .andExpect(jsonPath("$.data.updatedAt").exists());

        mockMvc.perform(patch("/api/clothes/{clothingId}/archive", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.archived").value(true));

        mockMvc.perform(get("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void unarchivesClothingIdempotentlyAndReturnsItToActiveList() throws Exception {
        User user = userRepository.save(User.createSeedUser("target-user"));
        ClothingItem clothing = createTop(user, "화이트 셔츠");
        clothing.archive();
        clothingItemRepository.save(clothing);
        clothingItemRepository.flush();

        mockMvc.perform(patch("/api/clothes/{clothingId}/unarchive", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.archived").value(false))
                .andExpect(jsonPath("$.data.updatedAt").exists());

        mockMvc.perform(patch("/api/clothes/{clothingId}/unarchive", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.archived").value(false));

        mockMvc.perform(get("/api/clothes/archived")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/clothes")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(clothing.getId()))
                .andExpect(jsonPath("$.data[0].archived").value(false));
    }

    @Test
    void returnsClothingNotFoundWhenUpdatingOrArchivingOtherUsersClothing() throws Exception {
        User targetUser = userRepository.save(User.createSeedUser("target-user"));
        User otherUser = userRepository.save(User.createSeedUser("other-user"));
        ClothingItem otherClothing = clothingItemRepository.save(createTop(otherUser, "다른 사용자 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(put("/api/clothes/{clothingId}", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));

        mockMvc.perform(patch("/api/clothes/{clothingId}/archive", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));

        mockMvc.perform(patch("/api/clothes/{clothingId}/unarchive", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));
    }

    @Test
    void uploadsAndReturnsClothingImageMetadata() throws Exception {
        User user = userRepository.save(User.createSeedUser("image-upload-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", clothing.getId())
                        .file(jpegFile("shirt.jpg"))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.image.url").value("/api/clothes/" + clothing.getId() + "/image"))
                .andExpect(jsonPath("$.data.image.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.image.sizeBytes").value(jpegBytes().length))
                .andExpect(jsonPath("$.data.image.uploadedAt").exists());

        mockMvc.perform(get("/api/clothes/{clothingId}", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image.url").value("/api/clothes/" + clothing.getId() + "/image"))
                .andExpect(jsonPath("$.data.image.contentType").value("image/jpeg"));
    }

    @Test
    void replacesClothingImageForSameClothingItem() throws Exception {
        User user = userRepository.save(User.createSeedUser("image-replace-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();
        byte[] firstImageBytes = jpegBytes();
        byte[] replacementImageBytes = replacementJpegBytes();

        uploadImage(user, clothing.getId(), jpegFile("shirt-first.jpg", firstImageBytes));
        String firstStoredFilename = clothingItemRepository.findById(clothing.getId())
                .orElseThrow()
                .getImageStoredFilename();
        assertThat(firstStoredFilename).isNotBlank();
        assertThat(Files.exists(testStoragePath(firstStoredFilename))).isTrue();

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", clothing.getId())
                        .file(jpegFile("shirt-replacement.jpg", replacementImageBytes))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.image.url").value("/api/clothes/" + clothing.getId() + "/image"))
                .andExpect(jsonPath("$.data.image.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.image.sizeBytes").value(replacementImageBytes.length));

        String replacementStoredFilename = clothingItemRepository.findById(clothing.getId())
                .orElseThrow()
                .getImageStoredFilename();
        assertThat(replacementStoredFilename).isNotEqualTo(firstStoredFilename);

        mockMvc.perform(get("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/jpeg"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .containsExactly(replacementImageBytes));
    }

    @Test
    void readsClothingImageBytesOnlyForOwner() throws Exception {
        User owner = userRepository.save(User.createSeedUser("image-owner"));
        User otherUser = userRepository.save(User.createSeedUser("image-other"));
        ClothingItem clothing = clothingItemRepository.save(createTop(owner, "화이트 셔츠"));
        ClothingItem otherClothing = clothingItemRepository.save(createTop(otherUser, "다른 셔츠"));
        otherClothing.updateImageMetadata("missing.jpg", "image/jpeg", 3L, java.time.LocalDateTime.now());
        clothingItemRepository.flush();
        uploadImage(owner, clothing.getId(), jpegFile("shirt.jpg"));

        mockMvc.perform(get("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("image/jpeg"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .containsExactly(jpegBytes()));

        mockMvc.perform(get("/api/clothes/{clothingId}/image", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));
    }

    @Test
    void returnsClothingImageNotFoundWhenOwnedClothingHasNoImage() throws Exception {
        User user = userRepository.save(User.createSeedUser("no-image-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(get("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_IMAGE_NOT_FOUND"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void deletesClothingImageIdempotently() throws Exception {
        User user = userRepository.save(User.createSeedUser("image-delete-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();
        uploadImage(user, clothing.getId(), jpegFile("shirt.jpg"));

        mockMvc.perform(delete("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.image").doesNotExist());

        mockMvc.perform(delete("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(clothing.getId()))
                .andExpect(jsonPath("$.data.image").doesNotExist());

        mockMvc.perform(get("/api/clothes/{clothingId}/image", clothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_IMAGE_NOT_FOUND"));
    }

    @Test
    void returnsInvalidRequestWhenImageValidationFails() throws Exception {
        User user = userRepository.save(User.createSeedUser("invalid-image-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();
        MockMultipartFile invalidImage = new MockMultipartFile(
                "image",
                "shirt.gif",
                "image/gif",
                new byte[] {'G', 'I', 'F'}
        );

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", clothing.getId())
                        .file(invalidImage)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("image"));
    }

    @Test
    void returnsInvalidRequestWhenImagePartIsMissing() throws Exception {
        User user = userRepository.save(User.createSeedUser("missing-image-part-user"));
        ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", clothing.getId())
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_SERVLET_REQUEST_PART"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void analyzesClothingImageRequiresValidImageAndMapsDisabledAnalyzer() throws Exception {
        User user = userRepository.save(User.createSeedUser("analysis-disabled-user"));

        mockMvc.perform(multipart("/api/clothes/analyze-image")
                        .file(jpegFile("shirt.jpg"))
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CLOTHING_ANALYSIS_DISABLED"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void returnsInvalidRequestWhenAnalysisImageValidationFails() throws Exception {
        User user = userRepository.save(User.createSeedUser("analysis-invalid-image-user"));
        MockMultipartFile invalidImage = new MockMultipartFile(
                "image",
                "shirt.gif",
                "image/gif",
                new byte[] {'G', 'I', 'F'}
        );

        mockMvc.perform(multipart("/api/clothes/analyze-image")
                        .file(invalidImage)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details[0].field").value("image"));
    }

    @Test
    void returnsClothingNotFoundForOtherUsersImageApi() throws Exception {
        User targetUser = userRepository.save(User.createSeedUser("image-target-user"));
        User otherUser = userRepository.save(User.createSeedUser("image-other-user"));
        ClothingItem otherClothing = clothingItemRepository.save(createTop(otherUser, "다른 사용자 셔츠"));
        clothingItemRepository.flush();

        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", otherClothing.getId())
                        .file(jpegFile("shirt.jpg"))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));

        mockMvc.perform(delete("/api/clothes/{clothingId}/image", otherClothing.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(targetUser)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLOTHING_NOT_FOUND"));
    }

    private String bearerToken(User user) {
        return "Bearer " + jwtTokenProvider.createAccessToken(new CurrentUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getRole()
        ));
    }

    private Map<String, Object> validRequest() {
        return new java.util.LinkedHashMap<>(Map.of(
                "name", "그레이 후드",
                "category", "TOP",
                "color", "GRAY",
                "material", "COTTON",
                "minTemperature", 5,
                "maxTemperature", 18,
                "rainSuitable", false
        ));
    }

    private ClothingItem createTop(User user, String name) {
        return ClothingItem.create(
                user,
                name,
                ClothingCategory.TOP,
                ClothingColor.WHITE,
                ClothingMaterial.COTTON,
                0,
                25,
                false
        );
    }

    private void uploadImage(User user, Long clothingId, MockMultipartFile image) throws Exception {
        mockMvc.perform(multipart("/api/clothes/{clothingId}/image", clothingId)
                        .file(image)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk());
    }

    private MockMultipartFile jpegFile(String filename) {
        return new MockMultipartFile("image", filename, "image/jpeg", jpegBytes());
    }

    private MockMultipartFile jpegFile(String filename, byte[] bytes) {
        return new MockMultipartFile("image", filename, "image/jpeg", bytes);
    }

    private byte[] jpegBytes() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
    }

    private byte[] replacementJpegBytes() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x09, 0x08, 0x07};
    }

    private Path testStoragePath(String storedFilename) {
        return Path.of(clothingImageProperties.storageDir(), storedFilename);
    }
}
