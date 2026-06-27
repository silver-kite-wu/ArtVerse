package com.artverse.ai;

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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

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

    private WebClient webClient;
    private ConnectionProvider connectionProvider;

    @PostConstruct
    public void init() {
        this.connectionProvider = buildConnectionProvider();
        this.webClient = buildWebClient(buildHttpClient(connectionProvider));
        log.info("WebClientImage2Client initialized with base URL: {}", properties.getImage().getBaseUrl());
    }

    @PreDestroy
    public void destroy() {
        if (connectionProvider != null) {
            connectionProvider.dispose();
            log.info("WebClientImage2Client connection pool disposed");
        }
    }

    private ConnectionProvider buildConnectionProvider() {
        return ConnectionProvider.builder("image2-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Builds a Netty HttpClient configured to:
     * <ul>
     *   <li>Use JVM's built-in DNS resolver (avoids Netty async DNS failures on Windows)</li>
     *   <li>Use JDK SSL provider — requires JVM flag {@code -Dio.netty.handler.ssl.noOpenSsl=true}
     *       to force JDK SSL over OpenSSL (avoids TLS renegotiation issues with some proxies)</li>
     *   <li>Force HTTP/1.1 protocol (avoids HTTP/2 compatibility issues with some proxies)</li>
     *   <li>Use a shared connection pool for better performance</li>
     * </ul>
     */
    private HttpClient buildHttpClient(ConnectionProvider connectionProvider) {
        return HttpClient.create(connectionProvider)
                .resolver(DefaultAddressResolverGroup.INSTANCE)     // use JVM DNS
                .protocol(HttpProtocol.HTTP11)   // force HTTP/1.1
                .responseTimeout(READ_TIMEOUT)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) CONNECT_TIMEOUT.toMillis());
    }

    private WebClient buildWebClient(HttpClient httpClient) {
        return WebClient.builder()
                .baseUrl(properties.getImage().getBaseUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                        .build())
                .build();
    }

    @Override
    public Mono<GeneratedImage> generate(ImageGenerationRequest request, String apiKey) {
        String key = resolveApiKey(apiKey);
        boolean hasReferences = request.referenceImages() != null && !request.referenceImages().isEmpty();

        return hasReferences ? generateWithReferences(request, key) : generateWithoutReferences(request, key);
    }

    private Mono<GeneratedImage> generateWithoutReferences(ImageGenerationRequest request, String apiKey) {
        String body = buildGenerationsRequest(request);

        return webClient.post()
                .uri("/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(READ_TIMEOUT)
                .flatMap(response -> parseImageResponse(response, request.prompt()));
    }

    private Mono<GeneratedImage> generateWithReferences(ImageGenerationRequest request, String apiKey) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("prompt", request.prompt());
        builder.part("model", properties.getImage().getModel());
        builder.part("size", properties.getImage().getSize());
        builder.part("response_format", "b64_json");

        List<Path> refs = request.referenceImages();
        if (refs.size() == 1) {
            builder.part("image", new FileSystemResource(refs.get(0)));
        } else {
            for (Path ref : refs) {
                builder.part("image[]", new FileSystemResource(ref));
            }
        }

        return webClient.post()
                .uri("/images/edits")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(READ_TIMEOUT)
                .flatMap(response -> parseImageResponse(response, request.prompt()));
    }

    private Mono<GeneratedImage> parseImageResponse(String response, String prompt) {
        return Mono.fromCallable(() -> {
            try {
                JsonNode node = objectMapper.readTree(response);
                if (node.has("error")) {
                    throw new BusinessException(502, "Image2 returned error: " + node.get("error").toString());
                }
                JsonNode data = node.path("data").path(0);
                if (data.isMissingNode()) {
                    throw new BusinessException(502, "Image2 returned no data item");
                }

                byte[] imageBytes;
                if (data.has("b64_json")) {
                    imageBytes = Base64.getDecoder().decode(data.get("b64_json").asText());
                } else if (data.has("url")) {
                    imageBytes = webClient.get().uri(data.get("url").asText())
                            .retrieve()
                            .bodyToMono(byte[].class)
                            .timeout(READ_TIMEOUT)
                            .block();
                } else {
                    throw new BusinessException(502, "Image2 returned no image data");
                }

                if (imageBytes == null || imageBytes.length == 0) {
                    throw new BusinessException(502, "Image2 returned empty image bytes");
                }

                BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                if (image == null) {
                    throw new BusinessException(502, "Invalid image format from Image2");
                }

                Path tempDir = Files.createTempDirectory("artverse_img_");
                String filename = "panel_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
                Path tempFile = tempDir.resolve(filename);
                ImageIO.write(image, "png", tempFile.toFile());

                return new GeneratedImage(tempFile, "image/png", Files.size(tempFile));
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                log.error("Image2 response processing failed. Response first 500 chars: {}",
                        response.length() > 500 ? response.substring(0, 500) : response, e);
                throw new BusinessException(502, "Failed to process Image2 response: " + e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String buildGenerationsRequest(ImageGenerationRequest request) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("model", properties.getImage().getModel());
            node.put("prompt", request.prompt());
            node.put("size", properties.getImage().getSize());
            node.put("response_format", "b64_json");
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request", e);
        }
    }

    private String resolveApiKey(String requestApiKey) {
        if (requestApiKey != null && !requestApiKey.isBlank()) {
            return requestApiKey;
        }
        String configKey = properties.getImage().getApiKey();
        if (configKey != null && !configKey.isBlank()) {
            return configKey;
        }
        throw new BusinessException(400, "Image API Key is missing. Please set it in the frontend settings.", "Image");
    }
}
