package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.User;
import com.artverse.domain.UserApiKey;
import com.artverse.persistence.UserApiKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    @Test
    void v2EncryptionIsAuthenticatedVersionedAndRandomized() {
        ApiKeyService service = service(mock(UserApiKeyRepository.class));

        String first = service.encryptSecret("sk-secret");
        String second = service.encryptSecret("sk-secret");

        assertThat(first).startsWith("v2:").isNotEqualTo(second);
        assertThat(service.decryptSecret(first)).isEqualTo("sk-secret");
        assertThat(service.decryptSecret(second)).isEqualTo("sk-secret");
    }

    @Test
    void firstSavedProfileIsActivated() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        when(repository.findByUserIdAndSlotOrderByCreatedAtAsc(9L, ApiKeyService.SLOT_LLM)).thenReturn(List.of());
        when(repository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey value = invocation.getArgument(0);
            value.setId(42L);
            return value;
        });

        ApiKeyService.ProviderInfo saved = service.saveProviderConfig(
                user,
                null,
                new UserProviderConfig("llm", "deepseek", "Primary", "sk-first", "https://api.deepseek.com", "deepseek-chat"),
                false
        );

        assertThat(saved.configId()).isEqualTo(42L);
        assertThat(saved.active()).isTrue();
        assertThat(saved.apiKeyMasked()).isNotBlank();
        verify(repository, never()).flush();
    }

    @Test
    void explicitProfileIdResolvesThatProfileSecret() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = new UserApiKey();
        saved.setId(42L);
        saved.setUser(user);
        saved.setSlot(ApiKeyService.SLOT_LLM);
        saved.setProvider("openrouter");
        saved.setLabel("Backup");
        saved.setApiKey(service.encryptSecret("sk-backup"));
        saved.setBaseUrl("https://openrouter.ai/api/v1");
        saved.setModel("openai/gpt-4.1-mini");
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));

        UserProviderConfig resolved = service.resolveProviderConfig(
                user,
                new UserProviderConfig("llm", "", "", "", "", "openai/gpt-4.1"),
                42L
        );

        assertThat(resolved.apiKey()).isEqualTo("sk-backup");
        assertThat(resolved.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(resolved.model()).isEqualTo("openai/gpt-4.1");
    }

    @Test
    void providerSecretCanBeReadOnlyByItsOwner() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, true);
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));

        assertThat(service.getProviderApiKey(user, 42L)).isEqualTo("sk-backup");
        assertThatThrownBy(() -> service.getProviderApiKey(user(10L), 42L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(404));
    }

    @Test
    void deactivatingProfileLeavesSlotWithoutAnActiveSavedProfile() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, true);
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));

        ApiKeyService.ProviderInfo result = service.deactivateProviderConfig(user, 42L);

        assertThat(result.active()).isFalse();
        assertThat(saved.isActive()).isFalse();
        verify(repository).flush();
    }

    @Test
    void inactiveSavedProfileDisablesApplicationDefaultSecret() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(9L, ApiKeyService.SLOT_LLM)).thenReturn(Optional.empty());
        when(repository.findByUserIdAndSlotOrderByCreatedAtAsc(9L, ApiKeyService.SLOT_LLM))
                .thenReturn(List.of(profile(user, service, false)));

        UserProviderConfig resolved = service.resolveProviderConfig(user, ApiKeyService.SLOT_LLM);

        assertThat(resolved.provider()).isEqualTo("deepseek");
        assertThat(resolved.apiKey()).isBlank();
    }

    @Test
    void savingInactiveExistingProfileDoesNotReactivateIt() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, false);
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));
        when(repository.save(saved)).thenReturn(saved);

        ApiKeyService.ProviderInfo result = service.saveProviderConfig(user, 42L,
                new UserProviderConfig("llm", "openrouter", "Backup", "", "https://openrouter.ai/api/v1", "openai/gpt-4.1-mini"),
                false);

        assertThat(result.active()).isFalse();
        verify(repository, never()).flush();
    }

    @Test
    void activatingLlmProfileKeepsOtherLlmProfilesActive() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey current = profile(user, service, true);
        UserApiKey target = profile(user, service, false);
        target.setId(43L);
        target.setProvider("deepseek");
        target.setLabel("Secondary");
        when(repository.findByIdAndUserId(43L, 9L)).thenReturn(Optional.of(target));
        when(repository.findByUserIdAndSlotOrderByCreatedAtAsc(9L, ApiKeyService.SLOT_LLM))
                .thenReturn(List.of(current, target));

        ApiKeyService.ProviderInfo result = service.activateProviderConfig(user, 43L);

        assertThat(result.active()).isTrue();
        assertThat(current.isActive()).isTrue();
        assertThat(target.isActive()).isTrue();
        verify(repository, never()).flush();
    }

    @Test
    void savingSecondActiveImageProfileKeepsExistingProfileActive() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey current = profile(user, service, true);
        current.setSlot(ApiKeyService.SLOT_IMAGE);
        current.setProvider("image2");
        when(repository.findByUserIdAndSlotOrderByCreatedAtAsc(9L, ApiKeyService.SLOT_IMAGE))
                .thenReturn(List.of(current));
        when(repository.save(any(UserApiKey.class))).thenAnswer(invocation -> {
            UserApiKey value = invocation.getArgument(0);
            value.setId(43L);
            return value;
        });

        ApiKeyService.ProviderInfo saved = service.saveProviderConfig(
                user,
                null,
                new UserProviderConfig("image", "openai-images", "Secondary", "sk-image", "https://api.openai.com/v1", "gpt-image-1"),
                true
        );

        assertThat(saved.active()).isTrue();
        assertThat(current.isActive()).isTrue();
        verify(repository, never()).flush();
    }

    @Test
    void activatingWorkflowProfileStillDeactivatesOtherWorkflowProfiles() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey current = profile(user, service, true);
        current.setSlot(ApiKeyService.SLOT_WORKFLOW);
        UserApiKey target = profile(user, service, false);
        target.setId(43L);
        target.setSlot(ApiKeyService.SLOT_WORKFLOW);
        when(repository.findByIdAndUserId(43L, 9L)).thenReturn(Optional.of(target));
        when(repository.findByUserIdAndSlotOrderByCreatedAtAsc(9L, ApiKeyService.SLOT_WORKFLOW))
                .thenReturn(List.of(current, target));

        ApiKeyService.ProviderInfo result = service.activateProviderConfig(user, 43L);

        assertThat(result.active()).isTrue();
        assertThat(current.isActive()).isFalse();
        assertThat(target.isActive()).isTrue();
        verify(repository).flush();
    }

    @Test
    void runtimeSelectionRejectsInactiveProfile() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, false);
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.requireProviderConfig(
                user,
                new UserProviderConfig("llm", "", "", "", "", "openai/gpt-4.1"),
                42L,
                "missing"
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled")
                .satisfies(error -> assertThat(((BusinessException) error).getStatus()).isEqualTo(409));
    }

    @Test
    void runtimeSelectionUsesSavedEndpointAndSecretWithRequestedModel() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, true);
        when(repository.findByIdAndUserId(42L, 9L)).thenReturn(Optional.of(saved));

        UserProviderConfig resolved = service.requireProviderConfig(
                user,
                new UserProviderConfig("llm", "forged", "Forged", "sk-forged", "https://forged.example/v1", "openai/gpt-4.1"),
                42L,
                "missing"
        );

        assertThat(resolved.provider()).isEqualTo("openrouter");
        assertThat(resolved.label()).isEqualTo("Backup");
        assertThat(resolved.apiKey()).isEqualTo("sk-backup");
        assertThat(resolved.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(resolved.model()).isEqualTo("openai/gpt-4.1");
    }

    @Test
    void byokResolutionNeverFallsBackToOperatorSecret() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository, new ArtVerseProperties());
        User user = user(9L);
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                9L, ApiKeyService.SLOT_LLM)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireByokProviderConfig(
                user,
                new UserProviderConfig("llm", "", "", "", "", ""),
                null,
                "BYOK required"
        )).isInstanceOf(BusinessException.class)
                .hasMessageContaining("BYOK required");
    }

    @Test
    void byokResolutionUsesSavedIdentityAndAllowsOnlyModelOverride() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        UserApiKey saved = profile(user, service, true);
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                9L, ApiKeyService.SLOT_LLM)).thenReturn(Optional.of(saved));

        UserProviderConfig resolved = service.requireByokProviderConfig(
                user,
                new UserProviderConfig("llm", "forged", "forged", "",
                        "https://forged.invalid/v1", "openai/gpt-4.1"),
                null,
                "missing"
        );

        assertThat(resolved.configId()).isEqualTo(42L);
        assertThat(resolved.provider()).isEqualTo("openrouter");
        assertThat(resolved.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(resolved.apiKey()).isEqualTo("sk-backup");
        assertThat(resolved.model()).isEqualTo("openai/gpt-4.1");
    }

    @Test
    void activeUserProviderConfigOrBlankNeverFallsBackToOperatorEndpoints() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository, new ArtVerseProperties());
        User user = user(9L);
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                9L, ApiKeyService.SLOT_WORKFLOW)).thenReturn(Optional.empty());
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                9L, ApiKeyService.SLOT_LLM)).thenReturn(Optional.empty());

        UserProviderConfig workflow = service.activeUserProviderConfigOrBlank(user, ApiKeyService.SLOT_WORKFLOW);
        UserProviderConfig llm = service.activeUserProviderConfigOrBlank(user, ApiKeyService.SLOT_LLM);

        assertThat(workflow.apiKey()).isBlank();
        assertThat(workflow.baseUrl()).isBlank();
        assertThat(llm.apiKey()).isBlank();
        assertThat(llm.baseUrl()).isBlank();
    }

    @Test
    void requireActiveUserProviderConfigRejectsMissingUserConfiguration() {
        UserApiKeyRepository repository = mock(UserApiKeyRepository.class);
        ApiKeyService service = service(repository);
        User user = user(9L);
        when(repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                9L, ApiKeyService.SLOT_WORKFLOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireActiveUserProviderConfig(
                user, ApiKeyService.SLOT_WORKFLOW, "Please configure workflow API key in Settings."))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Please configure workflow API key in Settings.");
    }

    private UserApiKey profile(User user, ApiKeyService service, boolean active) {
        UserApiKey saved = new UserApiKey();
        saved.setId(42L);
        saved.setUser(user);
        saved.setSlot(ApiKeyService.SLOT_LLM);
        saved.setProvider("openrouter");
        saved.setLabel("Backup");
        saved.setApiKey(service.encryptSecret("sk-backup"));
        saved.setBaseUrl("https://openrouter.ai/api/v1");
        saved.setModel("openai/gpt-4.1-mini");
        saved.setActive(active);
        return saved;
    }

    private ApiKeyService service(UserApiKeyRepository repository) {
        return service(repository, new ArtVerseProperties());
    }

    private ApiKeyService service(UserApiKeyRepository repository, ArtVerseProperties properties) {
        return new ApiKeyService(repository, properties, mock(WebClient.Builder.class), new ObjectMapper());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
