package com.artverse.application;

import com.artverse.common.BusinessException;
import io.agentscope.core.model.ModelHttpException;

import java.util.Map;

/** Public error metadata deliberately excludes provider bodies, prompts and credentials. */
public record StoryChatFailure(String code, String stage, String message, boolean retryable, Integer upstreamStatus) {
    public static StoryChatFailure from(Throwable error, String phase) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof BusinessException business) {
                return new StoryChatFailure("STORY_CHAT_REJECTED", phase, business.getMessage(), false, null);
            }
            if (cause instanceof ModelHttpException http && http.getStatusCode() != null) {
                int status = http.getStatusCode();
                String hint = status == 400 || status == 422 ? "请检查所选模型的工具调用支持与请求参数。"
                        : status == 401 || status == 403 ? "请检查模型服务的密钥与访问权限。"
                        : status == 402 ? "请检查模型服务账户的余额或配额，恢复后再重试。"
                        : status == 429 ? "模型服务请求过于频繁，请稍后重试。" : "请稍后重试。";
                return new StoryChatFailure("MODEL_HTTP_ERROR", "MODEL",
                        "模型服务拒绝或未完成请求（HTTP " + status + "）。" + hint,
                        status == 429 || status >= 500, status);
            }
        }
        String message = "PREPARING_CONTEXT".equals(phase) ? "章节内容准备失败，请重试。"
                : "BUILDING_REQUEST".equals(phase) ? "创作上下文整理失败，请重试。"
                : "TOOL".equals(phase) ? "工具执行失败，请查看执行记录后重试。" : "AI 回复失败，请稍后重试。";
        return new StoryChatFailure("STORY_CHAT_EXECUTION_FAILED", phase, message, true, null);
    }

    public Map<String, Object> metadata() {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("code", code);
        data.put("stage", stage == null ? "UNKNOWN" : stage);
        data.put("retryable", retryable);
        if (upstreamStatus != null) data.put("upstreamStatus", upstreamStatus);
        return Map.copyOf(data);
    }
}
