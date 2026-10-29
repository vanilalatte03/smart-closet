package com.smartcloset.clothing;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartcloset.clothing.application.ClothingService;
import com.smartcloset.clothing.domain.ClothingCategory;
import com.smartcloset.clothing.domain.ClothingColor;
import com.smartcloset.clothing.domain.ClothingItem;
import com.smartcloset.clothing.domain.ClothingMaterial;
import com.smartcloset.clothing.infrastructure.file.ClothingImageStorage;
import com.smartcloset.clothing.infrastructure.file.StoredClothingImage;
import com.smartcloset.clothing.repository.ClothingItemRepository;
import com.smartcloset.support.TestClothingImages;
import com.smartcloset.user.application.AccountDeletionService;
import com.smartcloset.user.domain.User;
import com.smartcloset.user.dto.AccountDeletionRequest;
import com.smartcloset.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest
class ClothingImageTransactionCleanupTest {

    private static final String PASSWORD = "password123!";

    @Autowired
    private ClothingService clothingService;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClothingItemRepository clothingItemRepository;

    @Autowired
    private ClothingImageStorage clothingImageStorage;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private final List<String> storedFilenames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanupStoredFiles() {
        storedFilenames.forEach(clothingImageStorage::delete);
    }

    @Test
    void imageReplacementKeepsPreviousFileOnRollbackAndDeletesItAfterCommit() {
        TestClothing testClothing = createCommittedClothing("replace");
        String originalStoredFilename = transactionTemplate.execute(status -> {
            clothingService.uploadClothingImage(
                    testClothing.userId(),
                    testClothing.clothingId(),
                    jpegFile("original.jpg", jpegBytes(1))
            );
            return currentStoredFilename(testClothing.clothingId());
        });
        storedFilenames.add(originalStoredFilename);
        assertThat(clothingImageStorage.read(originalStoredFilename)).isPresent();

        String[] rollbackReplacement = new String[1];
        transactionTemplate.executeWithoutResult(status -> {
            clothingService.uploadClothingImage(
                    testClothing.userId(),
                    testClothing.clothingId(),
                    jpegFile("rollback-replacement.jpg", jpegBytes(2))
            );
            rollbackReplacement[0] = currentStoredFilename(testClothing.clothingId());
            storedFilenames.add(rollbackReplacement[0]);
            assertThat(clothingImageStorage.read(originalStoredFilename)).isPresent();
            assertThat(clothingImageStorage.read(rollbackReplacement[0])).isPresent();
            status.setRollbackOnly();
        });

        assertThat(currentStoredFilename(testClothing.clothingId())).isEqualTo(originalStoredFilename);
        assertThat(clothingImageStorage.read(originalStoredFilename)).isPresent();
        assertThat(clothingImageStorage.read(rollbackReplacement[0])).isEmpty();

        String committedReplacement = transactionTemplate.execute(status -> {
            clothingService.uploadClothingImage(
                    testClothing.userId(),
                    testClothing.clothingId(),
                    jpegFile("committed-replacement.jpg", jpegBytes(3))
            );
            return currentStoredFilename(testClothing.clothingId());
        });
        storedFilenames.add(committedReplacement);

        assertThat(committedReplacement).isNotEqualTo(originalStoredFilename);
        assertThat(clothingImageStorage.read(originalStoredFilename)).isEmpty();
        assertThat(clothingImageStorage.read(committedReplacement)).isPresent();
        assertThat(currentStoredFilename(testClothing.clothingId())).isEqualTo(committedReplacement);
    }

    @Test
    void imageDeletePreservesFileOnRollbackAndDeletesItAfterCommit() {
        TestClothing testClothing = createCommittedClothing("delete");
        String storedFilename = transactionTemplate.execute(status -> {
            clothingService.uploadClothingImage(
                    testClothing.userId(),
                    testClothing.clothingId(),
                    jpegFile("delete-target.jpg", jpegBytes(4))
            );
            return currentStoredFilename(testClothing.clothingId());
        });
        storedFilenames.add(storedFilename);
        assertThat(clothingImageStorage.read(storedFilename)).isPresent();

        transactionTemplate.executeWithoutResult(status -> {
            clothingService.deleteClothingImage(testClothing.userId(), testClothing.clothingId());
            assertThat(currentStoredFilename(testClothing.clothingId())).isNull();
            assertThat(clothingImageStorage.read(storedFilename)).isPresent();
            status.setRollbackOnly();
        });

        assertThat(currentStoredFilename(testClothing.clothingId())).isEqualTo(storedFilename);
        assertThat(clothingImageStorage.read(storedFilename)).isPresent();

        transactionTemplate.executeWithoutResult(status ->
                clothingService.deleteClothingImage(testClothing.userId(), testClothing.clothingId()));

        assertThat(currentStoredFilename(testClothing.clothingId())).isNull();
        assertThat(clothingImageStorage.read(storedFilename)).isEmpty();
    }

    @Test
    void accountDeletionPreservesImagesOnRollbackAndDeletesThemAfterCommit() {
        TestClothing testClothing = transactionTemplate.execute(status -> {
            User user = userRepository.save(User.create(
                    uniqueEmail("delete-account"),
                    passwordEncoder.encode(PASSWORD),
                    "Delete Account"
            ));
            ClothingItem clothing = clothingItemRepository.save(createTop(user, "계정 삭제 셔츠"));
            StoredClothingImage storedImage = clothingImageStorage.store(new byte[] {1, 2, 3}, "jpg");
            storedFilenames.add(storedImage.storedFilename());
            clothing.updateImageMetadata(storedImage.storedFilename(), "image/jpeg", 3L, LocalDateTime.now());
            return new TestClothing(user.getId(), clothing.getId(), storedImage.storedFilename());
        });
        assertThat(clothingImageStorage.read(testClothing.storedFilename())).isPresent();

        transactionTemplate.executeWithoutResult(status -> {
            accountDeletionService.deleteAccount(testClothing.userId(), deletionRequest());
            assertThat(userRepository.findById(testClothing.userId())).isEmpty();
            assertThat(clothingImageStorage.read(testClothing.storedFilename())).isPresent();
            status.setRollbackOnly();
        });

        assertThat(userRepository.findById(testClothing.userId())).isPresent();
        assertThat(clothingItemRepository.findById(testClothing.clothingId())).isPresent();
        assertThat(clothingImageStorage.read(testClothing.storedFilename())).isPresent();

        transactionTemplate.executeWithoutResult(status ->
                accountDeletionService.deleteAccount(testClothing.userId(), deletionRequest()));

        assertThat(userRepository.findById(testClothing.userId())).isEmpty();
        assertThat(clothingImageStorage.read(testClothing.storedFilename())).isEmpty();
    }

    private TestClothing createCommittedClothing(String suffix) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.save(User.create(uniqueEmail(suffix), passwordEncoder.encode(PASSWORD),
                    "Image Cleanup"));
            ClothingItem clothing = clothingItemRepository.save(createTop(user, "화이트 셔츠"));
            return new TestClothing(user.getId(), clothing.getId(), null);
        });
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

    private MockMultipartFile jpegFile(String filename, byte[] bytes) {
        return new MockMultipartFile("image", filename, "image/jpeg", bytes);
    }

    private byte[] jpegBytes(int marker) {
        return TestClothingImages.jpeg(marker);
    }

    private String currentStoredFilename(Long clothingId) {
        return clothingItemRepository.findById(clothingId)
                .orElseThrow()
                .getImageStoredFilename();
    }

    private AccountDeletionRequest deletionRequest() {
        return new AccountDeletionRequest("DELETE", PASSWORD);
    }

    private String uniqueEmail(String suffix) {
        return suffix + "-" + System.nanoTime() + "@example.com";
    }

    private record TestClothing(Long userId, Long clothingId, String storedFilename) {
    }
}
