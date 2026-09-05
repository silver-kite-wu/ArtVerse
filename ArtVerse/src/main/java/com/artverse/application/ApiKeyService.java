package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.artverse.domain.User;
import com.artverse.domain.UserApiKey;
import com.artverse.persistence.UserApiKeyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import com.artverse.security.ProviderEndpointPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ApiKeyService {

    public static final String SLOT_LLM = "llm";
    public static final String SLOT_IMAGE = "image";
    public static final String SLOT_WORKFLOW = "workflow";

    private static final String LEGACY_ALGORITHM = "AES";
    private static final byte[] LEGACY_ENCRYPTION_KEY =
            "ArtVerse!ApiKey1".getBytes(StandardCharsets.UTF_8);
    private static final String ACTIVE_CIPHER = "AES/GCM/NoPadding";
    private static final String ACTIVE_PREFIX = "v2";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_NONCE_BYTES = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserApiKeyRepository repository;
    private final ArtVerseProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ProviderEndpointPolicy endpointPolicy;

    @Autowired
    public ApiKeyService(UserApiKeyRepository repository,
                         ArtVerseProperties properties,
                         WebClient.Builder webClientBuilder,
                         ObjectMapper objectMapper,
                         ProviderEndpointPolicy endpointPolicy) {
        this.repository = repository;
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
    }

    public ApiKeyService(UserApiKeyRepository repository,
                         ArtVerseProperties properties,
                         WebClient.Builder webClientBuilder,
                         ObjectMapper objectMapper) {
        this(repository, properties, webClientBuilder, objectMapper, null);
    }

    public record KeyInfo(String provider, String apiKeyMasked) {}

    public record ProviderInfo(
            Long configId,
            String slot,
            String provider,
            String label,
            String apiKeyMasked,
            String baseUrl,
            String model,
            boolean active
    ) {}

    @Transactional
    public void saveKey(User user, String provider, String apiKey) {
        UserProviderConfig current = resolveProviderConfig(user, slotFromLegacyProvider(provider));
        Long configId = repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(user.getId(), current.slot())
                .map(UserApiKey::getId)
                .orElse(null);
        saveProviderConfig(user, configId, new UserProviderConfig(
                current.slot(),
                legacyProviderOrDefault(provider, current.provider()),
                current.label(),
                apiKey,
                current.baseUrl(),
                current.model()
        ), true);
    }

    @Transactional
    public ProviderInfo saveProviderConfig(User user, Long configId, UserProviderConfig config, boolean activate) {
        String slot = requireSupportedSlot(config.slot());
        UserApiKey entity = configId == null
                ? new UserApiKey()
                : repository.findByIdAndUserId(configId, user.getId())
                    .filter(item -> slot.equals(item.getSlot()))
                    .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
        if (entity.getId() == null) {
                    UserApiKey newKey = new UserApiKey();
                    newKey.setUser(user);
                    newKey.setSlot(slot);
                    entity = newKey;
        }
        UserProviderConfig merged = mergeWithDefaults(slot, new UserProviderConfig(
                config.slot(),
                config.provider(),
                config.label(),
                config.apiKey().isBlank() && entity.getId() != null ? decryptSecret(entity.getApiKey()) : config.apiKey(),
                config.baseUrl(),
                config.model()
        ));
        validateProviderUrl(merged.baseUrl());
        String encrypted = encryptSecret(merged.apiKey());
        boolean isNew = entity.getId() == null;
        boolean isFirstProfile = isNew && repository.findByUserIdAndSlotOrderByCreatedAtAsc(user.getId(), slot).isEmpty();
        boolean shouldActivate = activate || entity.isActive() || isFirstProfile;
        if (shouldActivate && !supportsMultipleActiveProfiles(slot)) {
            repository.findByUserIdAndSlotOrderByCreatedAtAsc(user.getId(), slot)
                    .forEach(item -> item.setActive(false));
            repository.flush();
        }
        entity.setProvider(merged.provider());
        entity.setLabel(merged.label());
        entity.setApiKey(encrypted);
        entity.setBaseUrl(merged.baseUrl());
        entity.setModel(merged.model());
        entity.setActive(shouldActivate);
        return toProviderInfo(repository.save(entity));
    }

    public List<KeyInfo> getKeys(User user) {
        return repository.findByUserId(user.getId()).stream()
                .map(k -> new KeyInfo(k.getProvider(), maskKey(decryptSecret(k.getApiKey()))))
                .toList();
    }

    public List<ProviderInfo> getProviderConfigs(User user) {
        return repository.findByUserId(user.getId()).stream()
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .map(this::toProviderInfo)
                .toList();
    }

    public String getProviderApiKey(User user, Long configId) {
        return repository.findByIdAndUserId(configId, user.getId())
                .map(UserApiKey::getApiKey)
                .map(this::decryptSecret)
                .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
    }

    public String getDecryptedKey(User user, String provider) {
        String slot = isSupportedSlot(provider) ? provider : slotFromLegacyProvider(provider);
        return resolveProviderConfig(user, slot).apiKey();
    }

    public UserProviderConfig resolveProviderConfig(User user, String slot) {
        String normalizedSlot = requireSupportedSlot(slot);
        UserProviderConfig defaults = defaultConfigForSlot(normalizedSlot);
        return repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(user.getId(), normalizedSlot)
                .map(entity -> toProviderConfig(entity, normalizedSlot))
                .orElseGet(() -> repository.findByUserIdAndSlotOrderByCreatedAtAsc(user.getId(), normalizedSlot).isEmpty()
                        ? defaults
                        : new UserProviderConfig(
                                defaults.slot(), defaults.provider(), defaults.label(), "", defaults.baseUrl(), defaults.model()
                        ));
    }

    public UserProviderConfig requireProviderConfig(User user, String slot, String message) {
        UserProviderConfig config = resolveProviderConfig(user, slot);
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, message);
        }
        validateProviderUrl(config.baseUrl());
        return config;
    }

    /**
     * Resolves an active, user-owned provider configuration without falling
     * back to an operator key. Background agent work must remain BYOK.
     */
    public UserProviderConfig requireActiveUserProviderConfig(User user, String slot, String message) {
        String normalizedSlot = requireSupportedSlot(slot);
        UserProviderConfig config = repository
                .findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(user.getId(), normalizedSlot)
                .map(entity -> toProviderConfig(entity, normalizedSlot))
                .orElseThrow(() -> new BusinessException(400, message));
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, message);
        }
        validateProviderUrl(config.baseUrl());
        return config;
    }

    /**
     * Resolves the user's active provider configuration without throwing when
     * none is configured. Returns blank fields so callers can decide whether
     * the capability is optional. Never falls back to operator credentials.
     */
    public UserProviderConfig activeUserProviderConfigOrBlank(User user, String slot) {
        String normalizedSlot = requireSupportedSlot(slot);
        return repository.findFirstByUserIdAndSlotAndActiveTrueOrderByCreatedAtAscIdAsc(
                        user.getId(), normalizedSlot)
                .map(entity -> toProviderConfig(entity, normalizedSlot))
                .orElseGet(() -> new UserProviderConfig(normalizedSlot, "", "", "", "", ""));
    }

    /**
     * BYOK request resolution. New clients select a saved config and may
     * override only the model; legacy clients may still supply their own raw
     * key/base URL for one release cycle.
     */
    public UserProviderConfig requireByokProviderConfig(User user, UserProviderConfig override,
                                                        Long configId, String message) {
        UserProviderConfig safeOverride = override == null
                ? new UserProviderConfig(SLOT_LLM, "", "", "", "", "")
                : override;
        if (configId != null || !safeOverride.apiKey().isBlank()) {
            return requireProviderConfig(user, safeOverride, configId, message);
        }
        UserProviderConfig saved = requireActiveUserProviderConfig(user, safeOverride.slot(), message);
        return new UserProviderConfig(
                saved.slot(), saved.provider(), saved.label(), saved.apiKey(),
                saved.baseUrl(), blankToDefault(safeOverride.model(), saved.model()), saved.configId());
    }

    public UserProviderConfig requireProviderConfigForModel(User user, String slot, Long configId,
                                                            String model, String message) {
        String normalizedSlot = requireSupportedSlot(slot);
        String selectedModel = safe(model);
        if (selectedModel.isBlank()) {
            return requireProviderConfig(user, normalizedSlot, message);
        }

        UserApiKey entity;
        if (configId != null) {
            entity = repository.findByIdAndUserId(configId, user.getId())
                    .filter(item -> normalizedSlot.equals(item.getSlot()))
                    .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
            if (!entity.isActive()) {
                throw new BusinessException(409, "Provider configuration is disabled. Enable it in Settings before use.");
            }
            if (!hasConfiguredModel(entity.getModel(), selectedModel)) {
                throw new BusinessException(400, "Selected image model is not available in this provider configuration.");
            }
        } else {
            entity = repository.findByUserIdAndSlotOrderByCreatedAtAsc(user.getId(), normalizedSlot).stream()
                    .filter(UserApiKey::isActive)
                    .filter(item -> hasConfiguredModel(item.getModel(), selectedModel))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(400,
                            "Selected image model is not available in your active image provider settings."));
        }

        UserProviderConfig config = toProviderConfig(entity, normalizedSlot);
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, message);
        }
        validateProviderUrl(config.baseUrl());
        return new UserProviderConfig(
                config.slot(), config.provider(), config.label(), config.apiKey(),
                config.baseUrl(), selectedModel, config.configId());
    }

    public UserProviderConfig requireProviderConfig(User user, UserProviderConfig override, String message) {
        return requireProviderConfig(user, override, null, message);
    }

    public UserProviderConfig requireProviderConfig(User user, UserProviderConfig override, Long configId, String message) {
        UserProviderConfig config;
        if (configId != null) {
            String normalizedSlot = requireSupportedSlot(override.slot());
            UserApiKey selected = repository.findByIdAndUserId(configId, user.getId())
                    .filter(entity -> normalizedSlot.equals(entity.getSlot()))
                    .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
            if (!selected.isActive()) {
                throw new BusinessException(409, "Provider configuration is disabled. Enable it in Settings before use.");
            }
            UserProviderConfig saved = toProviderConfig(selected, normalizedSlot);
            config = new UserProviderConfig(
                    normalizedSlot,
                    saved.provider(),
                    saved.label(),
                    saved.apiKey(),
                    saved.baseUrl(),
                    blankToDefault(override.model(), saved.model()),
                    selected.getId()
            );
        } else {
            config = resolveProviderConfig(user, override, null);
        }
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, message);
        }
        validateProviderUrl(config.baseUrl());
        return config;
    }

    public UserProviderConfig resolveProviderConfig(User user, UserProviderConfig override) {
        return resolveProviderConfig(user, override, null);
    }

    public UserProviderConfig resolveProviderConfig(User user, UserProviderConfig override, Long configId) {
        String normalizedSlot = requireSupportedSlot(override.slot());
        UserProviderConfig base = configId == null
                ? resolveProviderConfig(user, normalizedSlot)
                : repository.findByIdAndUserId(configId, user.getId())
                    .filter(entity -> normalizedSlot.equals(entity.getSlot()))
                    .map(entity -> toProviderConfig(entity, normalizedSlot))
                    .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
        return new UserProviderConfig(
                normalizedSlot,
                blankToDefault(override.provider(), base.provider()),
                blankToDefault(override.label(), base.label()),
                blankToDefault(override.apiKey(), base.apiKey()),
                blankToDefault(override.baseUrl(), base.baseUrl()),
                blankToDefault(override.model(), base.model()),
                base.configId()
        );
    }

    @Transactional
    public void deleteKey(User user, String provider) {
        repository.deleteByUserIdAndSlot(user.getId(), slotFromLegacyProvider(provider));
    }

    @Transactional
    public void deleteProviderConfig(User user, Long configId) {
        UserApiKey entity = repository.findByIdAndUserId(configId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
        repository.delete(entity);
    }

    @Transactional
    public ProviderInfo activateProviderConfig(User user, Long configId) {
        UserApiKey entity = repository.findByIdAndUserId(configId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
        if (!supportsMultipleActiveProfiles(entity.getSlot())) {
            repository.findByUserIdAndSlotOrderByCreatedAtAsc(user.getId(), entity.getSlot())
                    .forEach(item -> item.setActive(false));
            repository.flush();
        }
        entity.setActive(true);
        return toProviderInfo(entity);
    }

    @Transactional
    public ProviderInfo deactivateProviderConfig(User user, Long configId) {
        UserApiKey entity = repository.findByIdAndUserId(configId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "Provider configuration not found."));
        entity.setActive(false);
        repository.flush();
        return toProviderInfo(entity);
    }

    public List<String> discoverModels(User user, String slot, String provider, String apiKey, String baseUrl, Long configId) {
        String normalizedSlot = requireSupportedSlot(slot);
        UserProviderConfig override = new UserProviderConfig(
                slot,
                provider,
                "",
                apiKey,
                baseUrl,
                ""
        );
        UserProviderConfig config = configId == null
                ? mergeWithDefaults(normalizedSlot, override)
                : resolveProviderConfig(user, override, configId);
        if (config.apiKey().isBlank()) {
            throw new BusinessException(400, "请先填写 API Key，再获取模型。", config.displayName());
        }
        if (SLOT_WORKFLOW.equals(config.slot())) {
            return List.of("workflow");
        }
        validateProviderUrl(config.baseUrl());
        try {
            String response = webClientBuilder
                    .baseUrl(config.baseUrl())
                    .build()
                    .get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            List<?> items = extractModelItems(response, config);
            List<String> models = items.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(item -> Objects.toString(item.get("id"), "").trim())
                    .filter(id -> !id.isBlank())
                    .distinct()
                    .toList();
            if (models.isEmpty()) {
                throw new BusinessException(502, config.displayName() + " returned no available models.", config.displayName());
            }
            return models;
        } catch (WebClientResponseException e) {
            throw mapHttpError(e, config);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, config.displayName() + " model discovery failed: " + e.getMessage(), config.displayName());
        }
    }

    private List<?> extractModelItems(String response, UserProviderConfig config) {
        try {
            JsonNode root = objectMapper.readTree(response == null ? "" : response);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new BusinessException(502, invalidJsonMessage(config), config.displayName());
            }
            return objectMapper.convertValue(data, List.class);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, describeNonJsonResponse(config, response, "models"), config.displayName());
        }
    }

    private BusinessException mapHttpError(WebClientResponseException ex, UserProviderConfig config) {
        if (ex.getStatusCode().value() == 401) {
            return new BusinessException(401, config.displayName() + " API key is invalid or expired.", config.displayName());
        }
        String body = ex.getResponseBodyAsString();
        if (looksLikeHtml(body)) {
            return new BusinessException(ex.getStatusCode().value(),
                    describeNonJsonResponse(config, body, "models"),
                    config.displayName());
        }
        return new BusinessException(ex.getStatusCode().value(),
                config.displayName() + " model discovery failed (" + ex.getStatusCode() + "): " + compactMessage(body, ex.getMessage()),
                config.displayName());
    }

    private String invalidJsonMessage(UserProviderConfig config) {
        return config.displayName() + " returned JSON, but not an OpenAI-compatible `{\"data\":[{\"id\":\"...\"}]}` model list.";
    }

    private String describeNonJsonResponse(UserProviderConfig config, String body, String endpointName) {
        if (looksLikeHtml(body)) {
            return config.displayName() + " returned HTML instead of JSON for `" + endpointName + "`. Check that Base URL points to the API root such as `https://host/v1`, not a website page or dashboard route.";
        }
        return config.displayName() + " returned a non-JSON response for `" + endpointName + "`. " +
                "Check whether the gateway is OpenAI-compatible and whether Base URL points to the API root. Response starts with: " +
                compactMessage(body, "(empty response)");
    }

    private boolean looksLikeHtml(String body) {
        String trimmed = safe(body);
        return trimmed.startsWith("<!DOCTYPE html")
                || trimmed.startsWith("<html")
                || trimmed.startsWith("<HTML")
                || trimmed.startsWith("<");
    }

    private String compactMessage(String body, String fallback) {
        String trimmed = safe(body).replaceAll("\\s+", " ");
        if (trimmed.isBlank()) {
            return fallback;
        }
        return trimmed.length() > 180 ? trimmed.substring(0, 180) + "..." : trimmed;
    }

    private UserProviderConfig toProviderConfig(UserApiKey entity, String slot) {
        return mergeWithDefaults(slot, new UserProviderConfig(
                slot,
                entity.getProvider(),
                entity.getLabel(),
                decryptSecret(entity.getApiKey()),
                entity.getBaseUrl(),
                firstConfiguredModel(entity.getModel()),
                entity.getId()
        ));
    }

    private void validateProviderUrl(String baseUrl) {
        if (endpointPolicy != null) {
            endpointPolicy.requireSafeBaseUrl(baseUrl);
        }
    }

    private ProviderInfo toProviderInfo(UserApiKey entity) {
        return new ProviderInfo(
                entity.getId(),
                entity.getSlot(),
                entity.getProvider(),
                entity.getLabel(),
                maskKey(decryptSecret(entity.getApiKey())),
                entity.getBaseUrl(),
                entity.getModel(),
                entity.isActive()
        );
    }

    private UserProviderConfig mergeWithDefaults(String slot, UserProviderConfig config) {
        UserProviderConfig defaults = defaultConfigForSlot(slot);
        return new UserProviderConfig(
                slot,
                blankToDefault(config.provider(), defaults.provider()),
                blankToDefault(config.label(), defaults.label()),
                config.apiKey(),
                blankToDefault(config.baseUrl(), defaults.baseUrl()),
                blankToDefault(config.model(), defaults.model()),
                config.configId()
        );
    }

    /**
     * Display-only metadata defaults (provider/label/model). Credentials and
     * endpoints are never taken from operator configuration: every request
     * must use the user's own saved provider configuration.
     */
    private UserProviderConfig defaultConfigForSlot(String slot) {
        return switch (slot) {
            case SLOT_LLM -> new UserProviderConfig(
                    SLOT_LLM,
                    "deepseek",
                    "DeepSeek Official",
                    "",
                    "",
                    safe(properties.getDeepseek().getModel())
            );
            case SLOT_IMAGE -> new UserProviderConfig(
                    SLOT_IMAGE,
                    "image2",
                    "Image2 Official",
                    "",
                    "",
                    safe(properties.getImage().getModel())
            );
            case SLOT_WORKFLOW -> new UserProviderConfig(
                    SLOT_WORKFLOW,
                    "coze",
                    "Coze Official",
                    "",
                    "",
                    safe(properties.getCoze().getWorkflowId())
            );
            default -> throw new BusinessException(400, "Unsupported provider slot: " + slot);
        };
    }

    private String slotFromLegacyProvider(String provider) {
        return switch (safe(provider)) {
            case "deepseek", SLOT_LLM -> SLOT_LLM;
            case "image2", SLOT_IMAGE -> SLOT_IMAGE;
            case "coze", SLOT_WORKFLOW -> SLOT_WORKFLOW;
            default -> throw new BusinessException(400, "Unsupported provider: " + provider);
        };
    }

    private String legacyProviderOrDefault(String provider, String fallback) {
        return switch (safe(provider)) {
            case "deepseek", "image2", "coze" -> provider.trim();
            default -> fallback;
        };
    }

    private String requireSupportedSlot(String slot) {
        if (!isSupportedSlot(slot)) {
            throw new BusinessException(400, "Unsupported provider slot: " + slot);
        }
        return slot.trim();
    }

    private boolean isSupportedSlot(String slot) {
        return Map.of(SLOT_LLM, true, SLOT_IMAGE, true, SLOT_WORKFLOW, true).containsKey(safe(slot));
    }

    private boolean supportsMultipleActiveProfiles(String slot) {
        return SLOT_LLM.equals(slot) || SLOT_IMAGE.equals(slot);
    }

    private boolean hasConfiguredModel(String models, String selectedModel) {
        String normalized = safe(selectedModel);
        return !normalized.isBlank() && safe(models).lines()
                .map(String::trim)
                .anyMatch(normalized::equals);
    }

    private String firstConfiguredModel(String models) {
        return safe(models).lines()
                .map(String::trim)
                .filter(model -> !model.isBlank())
                .findFirst()
                .orElse("");
    }

    public String encryptSecret(String plainText) {
        try {
            if (properties.getSecrets().getActiveKeyVersion() != 2) {
                throw new IllegalStateException("Unsupported active secret key version");
            }
            byte[] nonce = new byte[GCM_NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(ACTIVE_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, activeKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(safe(plainText).getBytes(StandardCharsets.UTF_8));
            return ACTIVE_PREFIX + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }

    public String decryptSecret(String encrypted) {
        if (encrypted != null && encrypted.startsWith(ACTIVE_PREFIX + ":")) {
            return decryptV2(encrypted);
        }
        return decryptLegacy(encrypted);
    }

    private String decryptV2(String encrypted) {
        try {
            String[] parts = encrypted.split(":", 3);
            if (parts.length != 3) throw new IllegalArgumentException("Malformed v2 secret");
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            if (nonce.length != GCM_NONCE_BYTES) throw new IllegalArgumentException("Invalid v2 nonce");
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(ACTIVE_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, activeKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    private String decryptLegacy(String encrypted) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(LEGACY_ENCRYPTION_KEY, LEGACY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(LEGACY_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt API key", e);
        }
    }

    private SecretKeySpec activeKey() {
        String configured = properties.getSecrets().getEncryptionKey();
        if (configured == null || configured.length() < 16) {
            throw new IllegalStateException("artverse.secrets.encryption-key must contain at least 16 characters");
        }
        try {
            return new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256").digest(configured.getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to derive secret encryption key", error);
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 7) + "****" + key.substring(key.length() - 4);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
