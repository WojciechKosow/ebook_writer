package com.ebookwriter.SaaS.service.library;

import com.ebookwriter.SaaS.entity.LibraryAsset;
import com.ebookwriter.SaaS.repository.LibraryAssetRepository;
import com.ebookwriter.SaaS.service.payment.SubscriptionService;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The library keeps one copy of each file per user, keeps it with PRO, and
 * purges it after the retention window without PRO.
 */
@ExtendWith(MockitoExtension.class)
class AssetLibraryServiceTest {

    @Mock LibraryAssetRepository repository;
    @Mock R2StorageService storage;
    @Mock SubscriptionService subscriptionService;

    @InjectMocks AssetLibraryService service;

    private final UUID userId = UUID.randomUUID();
    private final byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "retentionHours", 24);
    }

    @Test
    void newUploadWithoutProIsStoredWithAnExpiry() {
        when(subscriptionService.isPro(userId)).thenReturn(false);
        when(repository.findFirstByUserIdAndSha256(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(LibraryAsset.class))).thenAnswer(inv -> inv.getArgument(0));

        LibraryAsset saved = service.remember(userId, bytes, "image/png", "logo.png", 10, 10);

        verify(storage).upload(anyString(), any(), anyString());
        assertTrue(saved.getStorageKey().startsWith("library/" + userId + "/"));
        assertTrue(saved.getStorageKey().endsWith(".png"));
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusHours(23)));
    }

    @Test
    void newUploadWithProIsKept() {
        when(subscriptionService.isPro(userId)).thenReturn(true);
        when(repository.findFirstByUserIdAndSha256(any(), anyString())).thenReturn(Optional.empty());
        when(repository.save(any(LibraryAsset.class))).thenAnswer(inv -> inv.getArgument(0));

        assertNull(service.remember(userId, bytes, "image/png", "logo.png", 10, 10).getExpiresAt());
    }

    @Test
    void theSameFileIsNotStoredTwice() {
        LibraryAsset existing = LibraryAsset.builder().id(UUID.randomUUID()).userId(userId)
                .storageKey("library/x.png").sha256(AssetLibraryService.sha256(bytes))
                .contentType("image/png").expiresAt(LocalDateTime.now().plusHours(1)).build();
        when(subscriptionService.isPro(userId)).thenReturn(true);
        when(repository.findFirstByUserIdAndSha256(userId, AssetLibraryService.sha256(bytes)))
                .thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        LibraryAsset result = service.remember(userId, bytes, "image/png", "copy.png", 10, 10);

        assertSame(existing, result);
        assertNull(result.getExpiresAt(), "re-uploading with PRO keeps the asset");
        verify(storage, never()).upload(anyString(), any(), anyString());
    }

    @Test
    void purgeDeletesExpiredAssetsButKeepsThoseOfUsersWhoUpgraded() {
        UUID proUser = UUID.randomUUID();
        LibraryAsset free = LibraryAsset.builder().id(UUID.randomUUID()).userId(userId)
                .storageKey("library/free.png").expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        LibraryAsset upgraded = LibraryAsset.builder().id(UUID.randomUUID()).userId(proUser)
                .storageKey("library/pro.png").expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        when(repository.findByExpiresAtBefore(any())).thenReturn(List.of(free, upgraded));
        when(subscriptionService.isPro(userId)).thenReturn(false);
        when(subscriptionService.isPro(proUser)).thenReturn(true);

        service.purgeExpired();

        verify(repository).delete(free);
        verify(storage).delete("library/free.png");
        ArgumentCaptor<LibraryAsset> kept = ArgumentCaptor.forClass(LibraryAsset.class);
        verify(repository).save(kept.capture());
        assertSame(upgraded, kept.getValue());
        assertNull(upgraded.getExpiresAt());
        verify(storage, never()).delete("library/pro.png");
    }

    @Test
    void listingWithProKeepsPendingAssets() {
        LibraryAsset pending = LibraryAsset.builder().id(UUID.randomUUID()).userId(userId)
                .storageKey("library/a.png").contentType("image/png")
                .expiresAt(LocalDateTime.now().plusHours(3)).build();
        when(subscriptionService.isPro(userId)).thenReturn(true);
        when(repository.findByUserIdAndExpiresAtIsNotNull(userId)).thenReturn(List.of(pending));
        when(repository.findByUserIdOrderByLastUsedAtDesc(userId)).thenReturn(List.of(pending));

        var response = service.list(userId);

        assertTrue(response.kept());
        assertEquals(24, response.retentionHours());
        assertNull(response.assets().get(0).expiresAt());
    }
}
