package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.User;
import com.artverse.persistence.UserRepository;
import com.artverse.security.PasswordHashService;
import com.artverse.storage.ObjectStorageService;
import com.artverse.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {
    @Mock UserRepository users;
    @Mock PasswordHashService passwords;
    @Mock ObjectStorageService storage;
    @Mock UserSessionService sessions;
    @InjectMocks UserProfileService service;
    User user;

    @BeforeEach void setup() {
        user = new User(); user.setId(7L); user.setUsername("original"); user.setPasswordHash("old-hash");
    }
    void existingUser() { when(users.findByIdForProfileUpdate(7L)).thenReturn(Optional.of(user)); }
    void savedUser() { when(users.saveAndFlush(user)).thenReturn(user); }

    @Test void updatesTrimmedUniqueUsername() {
        existingUser(); savedUser();
        assertThat(service.updateName(7, "  new-name  ").getUsername()).isEqualTo("new-name");
        verify(users).existsByUsername("new-name");
    }
    @Test void duplicateNameDoesNotMutateUser() {
        existingUser(); when(users.existsByUsername("taken")).thenReturn(true);
        assertThatThrownBy(() -> service.updateName(7, "taken")).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));
        assertThat(user.getUsername()).isEqualTo("original"); verify(users, never()).saveAndFlush(any());
    }
    @Test void concurrentUniqueConstraintReturnsConflict() {
        existingUser(); when(users.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> service.updateName(7, "new-name")).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getStatus()).isEqualTo(409));
    }
    @Test void rejectsInvalidNamesBeforePersistence() {
        for (String name : new String[]{" ", "a", "x".repeat(51)}) assertThatThrownBy(() -> service.updateName(7, name)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users);
    }
    @Test void wrongPasswordDoesNotSaveOrInvalidateSessions() {
        existingUser(); when(passwords.matches("incorrect", "old-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword(7, "incorrect", "new-password", "new-password")).hasMessageContaining("当前密码不正确");
        assertThat(user.getPasswordHash()).isEqualTo("old-hash"); verifyNoInteractions(sessions); verify(users, never()).saveAndFlush(any());
    }
    @Test void passwordRulesRejectWithoutReadingAccount() {
        assertThatThrownBy(() -> service.changePassword(7, "old", "short", "short")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.changePassword(7, "old", "new-password", "different")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.changePassword(7, "same-password", "same-password", "same-password")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.changePassword(7, "old", "x".repeat(17), "x".repeat(17))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users, passwords, sessions);
    }
    @Test void hashesNewPasswordAndRevokesAllSessions() {
        existingUser(); when(passwords.matches("old-password", "old-hash")).thenReturn(true);
        when(passwords.encode("new-password")).thenReturn("new-hash");
        service.changePassword(7, "old-password", "new-password", "new-password");
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        var ordered = inOrder(users, sessions); ordered.verify(users).saveAndFlush(user); ordered.verify(sessions).invalidateAll(7L);
    }
    @Test void revocationFailurePropagatesForTransactionRollback() {
        existingUser(); when(passwords.matches("old-password", "old-hash")).thenReturn(true);
        when(passwords.encode("new-password")).thenReturn("new-hash");
        doThrow(new IllegalStateException("redis unavailable")).when(sessions).invalidateAll(7L);
        assertThatThrownBy(() -> service.changePassword(7, "old-password", "new-password", "new-password")).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsSpoofedOversizedAndHugeDimensionImages() throws Exception {
        assertThatThrownBy(() -> service.saveAvatar(7, new MockMultipartFile("image", "fake.png", "image/png", "not an image".getBytes()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveAvatar(7, new MockMultipartFile("image", new byte[20 * 1024 + 1]))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveAvatar(7, image(513))).hasMessageContaining("尺寸过大");
        verifyNoInteractions(users, storage);
    }
    @Test void successfulAvatarUsesVerifiedTypeAndDeletesOldObjectOnlyAfterCommit() throws Exception {
        existingUser(); savedUser(); user.setAvatarBucket("private"); user.setAvatarKey("old-key");
        when(storage.put(anyString(), any(), anyLong(), eq("image/png"))).thenReturn(new StoredObject("private", "new-key", "image/png", 100));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.saveAvatar(7, image(32));
            assertThat(user.getAvatarKey()).isEqualTo("new-key"); assertThat(user.getAvatarContentType()).isEqualTo("image/png");
            verify(storage, never()).deleteBestEffort(any(), any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(storage).deleteBestEffort("private", "old-key"); verify(storage, never()).deleteBestEffort("private", "new-key");
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void rollbackDeletesNewUploadAndPreservesOldObject() throws Exception {
        existingUser(); user.setAvatarBucket("private"); user.setAvatarKey("old-key");
        when(storage.put(anyString(), any(), anyLong(), anyString())).thenReturn(new StoredObject("private", "new-key", "image/png", 100));
        when(users.saveAndFlush(user)).thenThrow(new IllegalStateException("database unavailable"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.saveAvatar(7, image(32))).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(storage).deleteBestEffort("private", "new-key"); verify(storage, never()).deleteBestEffort("private", "old-key");
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void deleteAvatarClearsMetadataAndDefersBlobDeletion() {
        existingUser(); savedUser(); user.setAvatarBucket("private"); user.setAvatarKey("old-key"); user.setAvatarContentType("image/png");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.deleteAvatar(7); assertThat(user.getAvatarKey()).isNull(); assertThat(user.getAvatarBucket()).isNull(); assertThat(user.getAvatarContentType()).isNull();
            verifyNoInteractions(storage);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(storage).deleteBestEffort("private", "old-key");
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    private MockMultipartFile image(int side) throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("image", "misleading.jpg", "image/jpeg", bytes.toByteArray());
    }
}
