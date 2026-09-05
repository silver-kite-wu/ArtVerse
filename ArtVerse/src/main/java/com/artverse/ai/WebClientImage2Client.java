package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.PrematureCloseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebClientImage2Client implements Image2Client {

    private final ArtVerseProperties properties;
    private final ObjectMapper objectMapper;

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(600);
    private static final int MAX_IN_MEMORY_SIZE = 128 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final int TRANSIENT_RETRY_ATTEMPTS = 1;

    private WebClient webClient;
    private ConnectionProvider connectionProvider;

    @PostConstruct
    public void init() {
        this.connectionProvider = ConnectionProvider.builder("image2-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .protocol(HttpProtocol.HTTP11)
                .responseTimeout(READ_TIMEOUT)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) CONNECT_TIMEOUT.toMillis());
        this.webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                        .build())
                .build();
        log.info("WebClientImage2Client initialized");
    }

    @PreDestroy
    public void destroy() {
        if (connectionProvider != null) {
            connectionProvider.dispose();
            log.info("WebClientImage2Client connection pool disposed");
        }
    }

    @Override
    public Mono<GeneratedImage> generate(ImageGenerationRequest request, UserProviderConfig providerConfig) {
        UserProviderConfig config = resolveConfig(providerConfig);
        boolean hasReferences = request.referenceImages() != null && !request.referenceImages().isEmpty();
        return hasReferences ? generateWithReferences(request, config) : generateWithoutReferences(request, config);
    }

    private Mono<GeneratedImage> generateWithoutReferences(ImageGenerationRequest request, UserProviderConfig config) {
        String body = buildGenerationsRequest(request);
        return clientFor(config).post()
                .uri("/images/generations")
                .header("Authorization", "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.createException())
                .bodyToMono(String.class)
                .timeout(READ_TIMEOUT)
                .flatMap(response -> parseImageResponse(response, config))
                .retryWhen(reactor.util.retry.Retry.backoff(TRANSIENT_RETRY_ATTEMPTS, Duration.ofSeconds(1))
                        .filter(this::isPrematureClose)
                        .doBeforeRetry(signal -> log.warn("Image provider closed connection before a response; retrying once")))
                .onErrorMap(this::isPrematureClose, error -> new BusinessException(502,
                        config.displayName() + " closed the connection before returning an image. Please try again.",
                        config.displayName()))
                .onErrorMap(WebClientResponseException.class, ex -> mapHttpError(ex, config));
    }

    private Mono<GeneratedImage> generateWithReferences(ImageGenerationRequest request, UserProviderConfig config) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("prompt", request.prompt());
        builder.part("model", request.model());
        builder.part("size", request.size());
        builder.part("response_format", "b64_json");

        List<Path> refs = request.referenceImages();
        if (refs.size() == 1) {
            builder.part("image", new FileSystemResource(refs.get(0)));
        } else {
            for (Path ref : refs) {
                builder.part("image[]", new FileSystemResource(ref));
            }
        }

        return clientFor(config).post()
                .uri("/images/edits")
                .header("Authorization", "Bearer " + config.apiKey())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.createException())
                .bodyToMono(String.class)
                .timeout(READ_TIMEOUT)
                .flatMap(response -> parseImageResponse(response, config))
                .retryWhen(reactor.util.retry.Retry.backoff(TRANSIENT_RETRY_ATTEMPTS, Duration.ofSeconds(1))
                        .filter(this::isPrematureClose)
                        .doBeforeRetry(signal -> log.warn("Image provider closed connection before a response; retrying once")))
                .onErrorMap(this::isPrematureClose, error -> new BusinessException(502,
                        config.displayName() + " closed the connection before returning an image. Please try again.",
                        config.displayName()))
                .onErrorMap(WebClientResponseException.class, ex -> mapHttpError(ex, config));
    }

    private Mono<GeneratedImage> parseImageResponse(String response, UserProviderConfig config) {
        return Mono.fromCallable(() -> {
            try {
                JsonNode node = objectMapper.readTree(response);
                if (node.has("error")) {
                    throw new BusinessException(502, config.displayName() + " returned error: " + node.get("error"));
                }
                JsonNode data = node.path("data").path(0);
                if (data.isMissingNode()) {
                    throw new BusinessException(502, config.displayName() + " returned no data item");
                }

                byte[] imageBytes;
                if (data.has("b64_json")) {
                    imageBytes = Base64.getDecoder().decode(data.get("b64_json").asText());
                } else if (data.has("url")) {
                    imageBytes = clientFor(config).get().uri(data.get("url").asText())
                            .retrieve()
                            .bodyToMono(byte[].class)
                            .timeout(READ_TIMEOUT)
                            .block();
                } else {
                    throw new BusinessException(502, config.displayName() + " returned no image data");
                }

                if (imageBytes == null || imageBytes.length == 0) {
                    throw new BusinessException(502, config.displayName() + " returned empty image bytes");
                }

                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                if (image == null) {
                    throw new BusinessException(502, "Invalid image format from " + config.displayName());
                }

                Path tempDir = Files.createTempDirectory("artverse_img_");
                String filename = "panel_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
                Path tempFile = tempDir.resolve(filename);
                ImageIO.write(image, "png", tempFile.toFile());
                return new GeneratedImage(tempFile, "image/png", Files.size(tempFile));
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("Image response processing failed. Response first 500 chars: {}",
                        response.length() > 500 ? response.substring(0, 500) : response, e);
                throw new BusinessException(502, describeImageResponseError(response, config, e));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildGenerationsRequest(ImageGenerationRequest request) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("model", request.model());
            node.put("prompt", request.prompt());
            node.put("size", request.size());
            node.put("response_format", "b64_json");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request", e);
        }
    }

    private UserProviderConfig resolveConfig(UserProviderConfig providerConfig) {
        if (providerConfig == null) {
            throw new BusinessException(400, "Image provider config is missing");
        }
        if (providerConfig.apiKey().isBlank()) {
            throw new BusinessException(400, "Image API key is missing. Please set it in Settings.", providerConfig.displayName());
        }
        if (providerConfig.baseUrl().isBlank()) {
            throw new BusinessException(400, "Image provider Base URL is missing. Please configure it in Settings.", providerConfig.displayName());
        }
        return new UserProviderConfig(
                providerConfig.slot(),
                providerConfig.provider(),
                providerConfig.label(),
                providerConfig.apiKey(),
                providerConfig.baseUrl(),
                providerConfig.primaryModel().isBlank() ? properties.getImage().getModel() : providerConfig.primaryModel()
        );
    }

    private WebClient clientFor(UserProviderConfig config) {
        return webClient.mutate().baseUrl(config.baseUrl()).build();
    }

    private BusinessException mapHttpError(WebClientResponseException ex, UserProviderConfig config) {
        if (ex.getStatusCode().value() == 401) {
            return new BusinessException(401, config.displayName() + " API key is invalid or expired.", config.displayName());
        }
        String body = ex.getResponseBodyAsString();
        if (looksLikeHtml(body)) {
            return new BusinessException(ex.getStatusCode().value(),
                    config.displayName() + " returned HTML instead of JSON for the image API. Check that Base URL points to the API root such as `https://host/v1`, not a website page or panel route.",
                    config.displayName());
        }
        return new BusinessException(ex.getStatusCode().value(),
                config.displayName() + " API error (" + ex.getStatusCode() + "): " + ex.getMessage(), config.displayName());
    }

    private String describeImageResponseError(String response, UserProviderConfig config, Exception e) {
        if (looksLikeHtml(response)) {
            return config.displayName() + " returned HTML instead of JSON for the image API. Check that Base URL points to the API root such as `https://host/v1`, not a website page or panel route.";
        }
        return "Failed to process image response from " + config.displayName() + ": " + compactMessage(response, e.getMessage());
    }

    private boolean looksLikeHtml(String response) {
        String trimmed = response == null ? "" : response.trim();
        return trimmed.startsWith("<!DOCTYPE html")
                || trimmed.startsWith("<html")
                || trimmed.startsWith("<HTML")
                || trimmed.startsWith("<");
    }

    private String compactMessage(String response, String fallback) {
        String trimmed = response == null ? "" : response.trim().replaceAll("\\s+", " ");
        if (trimmed.isBlank()) {
            return fallback == null ? "unknown error" : fallback;
        }
        return trimmed.length() > 180 ? trimmed.substring(0, 180) + "..." : trimmed;
    }

    private boolean isPrematureClose(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof PrematureCloseException) return true;
            current = current.getCause();
        }
        return false;
    }
}
