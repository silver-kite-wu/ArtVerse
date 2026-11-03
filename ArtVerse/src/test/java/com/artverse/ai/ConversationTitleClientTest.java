package com.artverse.ai;

import com.artverse.application.UserProviderConfig;
import com.artverse.security.ProviderEndpointPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationTitleClientTest {
    private final ProviderEndpointPolicy policy = mock(ProviderEndpointPolicy.class);
    private final UserProviderConfig config = new UserProviderConfig("llm", "fixture", "fixture", "test-key",
            "https://example.test/v1/", "fixture-model");

    @Test
    void usesSavedEndpointAndExtractsOneLineTitle() {
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("https://example.test/v1/chat/completions");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer test-key");
            return response("{\"choices\":[{\"message\":{\"content\":\"构思雨夜悬疑故事\"}}]}");
        });
        assertThat(new ConversationTitleClient(builder, policy).generate(config, "帮我写故事")).isEqualTo("构思雨夜悬疑故事");
        verify(policy).requireSafeBaseUrl(config.baseUrl());
    }

    @Test
    void emptyOrMalformedOutputFailsForCallerToUseFallback() {
        for (String body : new String[]{"{}", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", "not-json"}) {
            var client = new ConversationTitleClient(WebClient.builder().exchangeFunction(request -> response(body)), policy);
            assertThatThrownBy(() -> client.generate(config, "内容")).isInstanceOf(Exception.class);
        }
    }

    @Test
    void unsafeEndpointNeverMakesRequest() {
        when(policy.requireSafeBaseUrl(config.baseUrl())).thenThrow(new IllegalArgumentException("unsafe"));
        var client = new ConversationTitleClient(WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("Must validate before request");
        }), policy);
        assertThatThrownBy(() -> client.generate(config, "内容")).isInstanceOf(IllegalArgumentException.class);
    }

    private Mono<ClientResponse> response(String json) {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(json).build());
    }
}
