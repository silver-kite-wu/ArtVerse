package com.artverse.application;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Distributed hard budget for model and tool calls in one manga run. */
@Slf4j
@Service
public class AgentBudgetService {

    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCRBY', KEYS[1], ARGV[1])
            if current == tonumber(ARGV[1]) then
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ArtVerseProperties properties;

    public AgentBudgetService(StringRedisTemplate redisTemplate,
                              JdbcTemplate jdbcTemplate,
                              ArtVerseProperties properties) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public long consumeModelCall(AgentRunRequest request) {
        if (!isMangaTask(request.taskType())) {
            return 0;
        }
        UUID requestId = requireRequestId(request.requestId());
        long userId = parseUserId(request.userId());
        long limit = modelLimit(request.taskType());
        if(jdbcTemplate!=null&&request.conversationId()!=null) {
            var automation=jdbcTemplate.queryForList("SELECT id,state,(definition_snapshot->'limits'->>'maxModelCallsPerRun')::integer AS model_limit FROM automation_run WHERE id=? AND user_id=?",request.conversationId(),userId);
            if(!automation.isEmpty()) {
                if(!"RUNNING".equals(automation.getFirst().get("state")))throw new BusinessException(409,"定时任务已停止或不再处于执行状态");
                requestId=(UUID)automation.getFirst().get("id");
                limit=Math.min(limit,((Number)automation.getFirst().get("model_limit")).longValue());
            }
        }
        return consume(userId, requestId, request.taskType(), "MODEL_CALL", limit);
    }

    public long consumeToolCall(MangaAgentRuntimeContext context, String toolName) {
        if (context == null || !isMangaTask(context.taskType())) {
            return 0;
        }
        long limit = toolLimit(context.taskType());
        if(jdbcTemplate!=null&&context.conversationId()!=null) {
            var roots=jdbcTemplate.queryForList("SELECT state FROM automation_run WHERE id=? AND user_id=?",context.conversationId(),context.userId());
            if(!roots.isEmpty()&&!"RUNNING".equals(roots.getFirst().get("state")))throw new BusinessException(409,"定时任务已停止");
        }
        return consume(context.userId(), requireRequestId(context.requestId()), context.taskType(),
                "TOOL_CALL", limit, Map.of("tool", toolName == null ? "unknown" : toolName));
    }

    public void validateAndRecordInput(AgentRunRequest request) {
        validateAndRecordInput(request, request.messages());
    }

    public void validateAndRecordInput(AgentRunRequest request,
                                       List<com.artverse.agent.AgentMessage> effectiveMessages) {
        if (!isMangaTask(request.taskType())) return;
        long estimatedTokens = effectiveMessages == null ? 0 : effectiveMessages.stream()
                .mapToLong(message -> estimateTokens(message.content()))
                .sum();
        long limit = properties.getAgent().getMaxInputTokens();
        recordMeasuredUsage(request, "INPUT_TOKEN", estimatedTokens, limit);
        if (estimatedTokens > limit) {
            throw new AgentBudgetExceededException("INPUT_TOKEN", limit, estimatedTokens);
        }
    }

    public OutputUsage measureOutput(String text) {
        String safe = text == null ? "" : text;
        return new OutputUsage(
                estimateTokens(safe),
                safe.getBytes(StandardCharsets.UTF_8).length
        );
    }

    public void requireOutputWithinLimit(OutputUsage usage) {
        long tokenLimit = properties.getAgent().getMaxOutputTokens();
        long byteLimit = properties.getAgent().getMaxOutputBytes();
        if (usage.estimatedTokens() > tokenLimit) {
            throw new AgentBudgetExceededException("OUTPUT_TOKEN", tokenLimit, usage.estimatedTokens());
        }
        if (usage.bytes() > byteLimit) {
            throw new AgentBudgetExceededException("OUTPUT_BYTE", byteLimit, usage.bytes());
        }
    }

    public void recordOutput(AgentRunRequest request, OutputUsage usage) {
        if (!isMangaTask(request.taskType())) return;
        recordMeasuredUsage(request, "OUTPUT_TOKEN", usage.estimatedTokens(),
                properties.getAgent().getMaxOutputTokens());
        recordMeasuredUsage(request, "OUTPUT_BYTE", usage.bytes(),
                properties.getAgent().getMaxOutputBytes());
    }

    private long consume(long userId, UUID requestId, AgentTaskType taskType,
                         String kind, long limit) {
        return consume(userId, requestId, taskType, kind, limit, Map.of());
    }

    private long consume(long userId, UUID requestId, AgentTaskType taskType,
                         String kind, long limit, Map<String, Object> metadata) {
        if (limit <= 0) {
            throw new AgentBudgetExceededException(kind, limit, 1);
        }
        String key = "agent:budget:" + userId + ":" + requestId + ":" + kind.toLowerCase();
        Long usage;
        try {
            usage = redisTemplate.execute(CONSUME_SCRIPT, List.of(key), "1",
                    String.valueOf(properties.getAgent().getBudgetTtlSeconds()));
        } catch (RuntimeException error) {
            log.error("Agent budget store unavailable for requestId={}", requestId, error);
            throw new BusinessException(503, "Agent budget service is unavailable");
        }
        if (usage == null) {
            throw new BusinessException(503, "Agent budget service returned no result");
        }

        persistUsage(userId, requestId, taskType, kind, usage, limit, metadata);
        if (usage > limit) {
            throw new AgentBudgetExceededException(kind, limit, usage);
        }
        return usage;
    }

    private void persistUsage(long userId, UUID requestId, AgentTaskType taskType, String kind,
                              long usage, long limit, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                INSERT INTO agent_usage_ledger
                    (user_id, request_id, usage_kind, amount, limit_value, task_type, metadata)
                VALUES (?, ?, ?, 1, ?, ?, CAST(? AS jsonb))
                """, userId, requestId, kind, limit, taskType.name(), toJson(metadata));
        jdbcTemplate.update("""
                UPDATE manga_agent_runs
                SET budget_usage_json = jsonb_set(
                    coalesce(budget_usage_json, '{}'::jsonb), ARRAY[?]::text[], to_jsonb(?::bigint), true)
                WHERE user_id = ? AND request_id = ?
                """, kind.toLowerCase(), usage, userId, requestId);
    }

    private void recordMeasuredUsage(AgentRunRequest request, String kind, long amount, long limit) {
        long userId = parseUserId(request.userId());
        UUID requestId = requireRequestId(request.requestId());
        String stepId = String.valueOf(request.variables().getOrDefault("step_id", ""));
        jdbcTemplate.update("""
                INSERT INTO agent_usage_ledger
                    (user_id, request_id, step_id, usage_kind, amount, limit_value, task_type, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                """, userId, requestId, stepId, kind, amount, limit, request.taskType().name());
        jdbcTemplate.update("""
                UPDATE manga_agent_runs
                SET budget_usage_json = jsonb_set(
                    coalesce(budget_usage_json, '{}'::jsonb),
                    ARRAY[?]::text[],
                    to_jsonb(coalesce((budget_usage_json ->> ?)::bigint, 0) + ?::bigint),
                    true)
                WHERE user_id = ? AND request_id = ?
                """, kind.toLowerCase(), kind.toLowerCase(), amount, userId, requestId);
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata.isEmpty()) {
            return "{}";
        }
        String tool = String.valueOf(metadata.getOrDefault("tool", "unknown"))
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return "{\"tool\":\"" + tool + "\"}";
    }

    private long modelLimit(AgentTaskType taskType) {
        ArtVerseProperties.Agent agent = properties.getAgent();
        return switch (taskType) {
            case MANGA_ROUTER -> agent.getRouterMaxModelCalls();
            case MANGA_CONVERSATION, MANGA_CREATIVE -> agent.getConversationMaxModelCalls();
            case READER_CHARACTER_CHAT -> agent.getConversationMaxModelCalls();
            case MANGA_STORYBOARD -> agent.getStoryboardMaxModelCalls();
            case MANGA_REVIEW -> agent.getReviewMaxModelCalls();
            case MANGA_DIRECTOR -> agent.getDirectorMaxModelCalls();
            default -> Long.MAX_VALUE;
        };
    }

    private long toolLimit(AgentTaskType taskType) {
        ArtVerseProperties.Agent agent = properties.getAgent();
        return switch (taskType) {
            case MANGA_CONVERSATION, MANGA_CREATIVE -> agent.getConversationMaxToolCalls();
            case MANGA_STORYBOARD -> agent.getStoryboardMaxToolCalls();
            case MANGA_REVIEW -> agent.getReviewMaxToolCalls();
            case MANGA_ROUTER, MANGA_DIRECTOR -> 0;
            default -> Long.MAX_VALUE;
        };
    }

    private boolean isMangaTask(AgentTaskType taskType) {
        return taskType == AgentTaskType.MANGA_ROUTER
                || taskType == AgentTaskType.READER_CHARACTER_CHAT
                || (taskType != null && taskType.isMangaExecutionTask());
    }

    private long parseUserId(String userId) {
        try {
            return Long.parseLong(userId);
        } catch (Exception error) {
            throw new BusinessException(400, "Invalid agent user id");
        }
    }

    private UUID requireRequestId(UUID requestId) {
        if (requestId == null) {
            throw new BusinessException(400, "requestId is required for budgeted agent execution");
        }
        return requestId;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        // A conservative provider-neutral estimate: one Unicode code point is
        // counted as one token. It overestimates most Latin text and safely
        // bounds Chinese content without coupling the control plane to a model tokenizer.
        return text.codePointCount(0, text.length());
    }

    public record OutputUsage(long estimatedTokens, long bytes) {
        public OutputUsage plus(OutputUsage other) {
            return new OutputUsage(estimatedTokens + other.estimatedTokens, bytes + other.bytes);
        }
    }
}
