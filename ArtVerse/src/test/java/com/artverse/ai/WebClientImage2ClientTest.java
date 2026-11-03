package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebClientImage2ClientTest {

    @Test
    void preservesPngBytesWithoutReencoding() throws Exception {
        byte[] original = imageBytes("png");
        WebClientImage2Client client = clientReturningBase64(original);

        GeneratedImage result = generate(client);

        assertThat(result.bytes()).isEqualTo(original);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.extension()).isEqualTo(".png");
        assertThat(result.sizeBytes()).isEqualTo(original.length);
        try (var input = result.openStream()) {
            assertThat(input.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void preservesJpegAndDeterminesContentTypeFromImageInsteadOfUrlOrHeaders() throws Exception {
        byte[] original = imageBytes("jpeg");
        AtomicInteger downloads = new AtomicInteger();
        WebClientImage2Client client = new WebClientImage2Client(new ArtVerseProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            if (request.url().getPath().equals("/output.png")) {
                downloads.incrementAndGet();
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "image/png")
                        .body(reactor.core.publisher.Flux.just(new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(original)))
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"data\":[{\"url\":\"https://images.example.test/output.png\"}]}").build());
        }).build());

        GeneratedImage result = generate(client);

        assertThat(downloads).hasValue(1);
        assertThat(result.bytes()).isEqualTo(original);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.extension()).isEqualTo(".jpg");
    }

    @Test
    void rejectsInvalidOrEmptyImages() throws Exception {
        assertThatThrownBy(() -> generate(clientReturningBase64(new byte[] {1, 2, 3})))
                .isInstanceOf(BusinessException.class).hasMessageContaining("image format");
        assertThatThrownBy(() -> generate(clientReturningBase64(new byte[0])))
                .isInstanceOf(BusinessException.class).hasMessageContaining("empty image");
    }

    @Test
    void rejectsHugeDimensionsBeforeDecodingPixels() throws Exception {
        byte[] image = imageBytes("png");
        ByteBuffer.wrap(image).putInt(16, 100_000).putInt(20, 100_000);

        assertThatThrownBy(() -> generate(clientReturningBase64(image)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("dimensions exceed limit");
    }

    @Test
    void scheduledParsingFailureRemainsUncertainAndDoesNotRetryProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WebClientImage2Client client = new WebClientImage2Client(new ArtVerseProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"data\":[{\"b64_json\":\"invalid-base64\"}]}").build());
        }).build());

        assertThatThrownBy(() -> client.generateScheduled(request(), config()).block(Duration.ofSeconds(5)))
                .isInstanceOf(ImageResultUncertainException.class);
        assertThat(calls).hasValue(1);
    }

    private static GeneratedImage generate(WebClientImage2Client client) {
        return client.generate(request(), config()).block(Duration.ofSeconds(5));
    }

    private static ImageGenerationRequest request() {
        return new ImageGenerationRequest("test", "test-model", "1024x1024", null, null);
    }

    private static UserProviderConfig config() {
        return new UserProviderConfig("image", "image2", "Image2", "test-key", "https://api.example.test/v1", "test-model");
    }

    private static WebClientImage2Client clientReturningBase64(byte[] bytes) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("data", java.util.List.of(
                Map.of("b64_json", Base64.getEncoder().encodeToString(bytes)))));
        WebClientImage2Client client = new WebClientImage2Client(new ArtVerseProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build())).build());
        return client;
    }

    private static byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x112233);
        image.setRGB(1, 0, 0xABCDEF);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    @Test
    void mapsUnauthorizedToClearApiKeyMessage() {
        WebClientImage2Client client = new WebClientImage2Client(new ArtVerseProperties(), new ObjectMapper());

        WebClientResponseException ex = WebClientResponseException.create(
                401,
                "Unauthorized",
                null,
                "{\"error\":{\"message\":\"Invalid token\"}}".getBytes(),
                null
        );

        UserProviderConfig config = new UserProviderConfig("image", "image2", "Image2", "sk-test", "https://api.example.com/v1", "test-model");
        BusinessException mapped = invokeMapHttpError(client, ex, config);

        assertThat(mapped.getStatus()).isEqualTo(401);
        assertThat(mapped.getProvider()).isEqualTo("Image2");
        assertThat(mapped.getMessage()).contains("API key is invalid");
    }

    private BusinessException invokeMapHttpError(WebClientImage2Client client, WebClientResponseException ex, UserProviderConfig config) {
        try {
            var method = WebClientImage2Client.class.getDeclaredMethod("mapHttpError", WebClientResponseException.class, UserProviderConfig.class);
            method.setAccessible(true);
            return (BusinessException) method.invoke(client, ex, config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
