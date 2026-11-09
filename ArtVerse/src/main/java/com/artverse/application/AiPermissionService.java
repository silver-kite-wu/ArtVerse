package com.artverse.application;

import com.artverse.agent.AgentRunRequest;
import com.artverse.agent.AgentTaskType;
import com.artverse.agent.AiPermissionMode;
import com.artverse.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * AI 运行时的权限来源：定时任务读取自身定义，交互式助手读取用户设置。
 * 未配置的历史数据保持原有能力（完全访问）。
 */
@Service
@RequiredArgsConstructor
public class AiPermissionService {
    private static final Set<AgentTaskType> INTERACTIVE_TASKS = Set.of(
            AgentTaskType.STORY_CHAT_READ,
            AgentTaskType.STORY_CHAT_WRITE,
            AgentTaskType.MANGA_CONVERSATION,
            AgentTaskType.MANGA_DIRECTOR);

    private final JdbcTemplate jdbc;

    public AiPermissionMode resolve(AgentRunRequest request) {
        // 仅约束交互式助手及其定时任务；分镜提交等工作流步骤保持既有能力。
        if (request.taskType() == null || !INTERACTIVE_TASKS.contains(request.taskType())) {
            return AiPermissionMode.FULL;
        }
        AiPermissionMode automation = automationMode(request);
        if (automation != null) return automation;
        AiPermissionMode saved = AiPermissionMode.parse(get(userId(request.userId())));
        return saved == null ? AiPermissionMode.AUTO : saved;
    }

    public String get(long userId) {
        String mode = jdbc.query("SELECT ai_permission_mode FROM users WHERE id=?",
                (org.springframework.jdbc.core.ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null, userId);
        return mode == null || mode.isBlank() ? AiPermissionMode.AUTO.name() : mode;
    }

    public String save(long userId, String mode) {
        AiPermissionMode parsed = AiPermissionMode.parse(mode);
        if (parsed == null) throw new BusinessException(400, "未知的 AI 权限模式");
        int updated = jdbc.update("UPDATE users SET ai_permission_mode=? WHERE id=?", parsed.name(), userId);
        if (updated == 0) throw new BusinessException(404, "用户不存在");
        return parsed.name();
    }

    /** 定时任务子运行使用定义快照中的模式；没有模式的旧任务保持完全访问。 */
    private AiPermissionMode automationMode(AgentRunRequest request) {
        Object value = request.variables().get("automation_run_id");
        if (!(value instanceof String runId) || runId.isBlank()) return null;
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(403, "定时任务运行标识无效");
        }
        String mode = jdbc.query("SELECT definition_snapshot->>'permissionMode' FROM automation_run WHERE id=? AND user_id=?",
                (org.springframework.jdbc.core.ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null, id, userId(request.userId()));
        return AiPermissionMode.parse(mode) == null ? AiPermissionMode.FULL : AiPermissionMode.parse(mode);
    }

    private static Long userId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(400, "用户标识无效");
        }
    }
}
