package com.artverse.agent.mcp;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** Issues run-scoped credentials for the AgentScope MCP client. The token contains no model/API secrets. */
@Service
public class McpAgentExecutionTokenService {
    public static final String TRANSPORT_CONTEXT_KEY = "artverse.agent.execution";
    private static final String HMAC = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final ArtVerseProperties.Agent.Mcp properties;

    public McpAgentExecutionTokenService(ObjectMapper objectMapper, ArtVerseProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties.getAgent().getMcp();
    }

    public String issue(AgentRunRequest request) {
        if (request.requestId() == null || request.taskType() == null || request.userId() == null) {
            throw new IllegalArgumentException("A complete agent execution identity is required for MCP access");
        }
        long now = Instant.now().getEpochSecond();
        ExecutionClaims claims = new ExecutionClaims(
                request.userId(), request.storyId(), request.chapterId(), request.conversationId(),
                request.requestId(), request.taskType(),
                String.valueOf(request.variables().getOrDefault("step_id", request.taskType().sessionSuffix())),
                longValue(request.variables().get("fencing_token")),
                uuidValue(request.variables().get("tenant_id")), now,
                now + Math.max(60, properties.getTokenLifetimeSeconds()));
        try {
            String body = encode(objectMapper.writeValueAsBytes(claims));
            return body + "." + encode(sign(body));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to issue agent MCP execution token", error);
        }
    }

    public ExecutionClaims verify(String token) {
        try {
            if (token == null || token.length() > 8192) {
                throw new IllegalArgumentException("Invalid MCP authorization token");
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), decode(parts[1]))) {
                throw new IllegalArgumentException("Invalid MCP authorization token");
            }
            ExecutionClaims claims = objectMapper.readValue(decode(parts[0]), ExecutionClaims.class);
            long now = Instant.now().getEpochSecond();
            if (claims.expiresAt() <= now || claims.issuedAt() > now + 30
                    || claims.expiresAt() - claims.issuedAt() > 3600) {
                throw new IllegalArgumentException("Expired or invalid MCP authorization token");
            }
            return claims;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid MCP authorization token", error);
        }
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to sign agent MCP execution token", error);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        return Long.valueOf(String.valueOf(value));
    }

    private static UUID uuidValue(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null || String.valueOf(value).isBlank()) return null;
        return UUID.fromString(String.valueOf(value));
    }

    public record ExecutionClaims(String userId, Long storyId, Long chapterId, UUID conversationId,
                                  UUID requestId, AgentTaskType taskType, String stepId,
                                  Long fencingToken, UUID tenantId, long issuedAt, long expiresAt) {
    }
}
