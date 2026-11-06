package com.artverse.application.tools;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.application.AgentRunToolStatus;
import com.artverse.application.AgentUserInputRequest;
import com.artverse.common.BusinessException;
import com.artverse.domain.Chapter;
import io.agentscope.core.agent.RuntimeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MangaToolSupport {

    private final AgentRunToolStatus agentRunToolStatus;

    public MangaAgentRuntimeContext resolveContext(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            throw new BusinessException(500, "RuntimeContext is required for tool execution");
        }
        MangaAgentRuntimeContext context = runtimeContext.get(MangaAgentRuntimeContext.class);
        if (context == null) {
            throw new BusinessException(500, "MangaAgentRuntimeContext is missing from RuntimeContext");
        }
        return context;
    }

    public void requestUserInput(MangaAgentRuntimeContext context,
                                 AgentUserInputRequest request) {
        if (context != null && context.requestId() != null) {
            agentRunToolStatus.requestUserInput(
                    context.userId(), context.chapterId(), context.requestId(), request);
        }
    }

    public String chapterDisplayName(Chapter chapter) {
        if (chapter.getDisplayTitle() != null && !chapter.getDisplayTitle().isBlank()) {
            return chapter.getDisplayTitle();
        }
        return "Chapter " + chapter.getChapterNumber();
    }

    public String excerpt(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars) + "...";
    }

    public String optionalText(Object value) {
        return value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
    }

    public List<AgentUserInputRequest.Option> normalizeOptions(Object rawOptions) {
        if (!(rawOptions instanceof List<?> list)) {
            return List.of();
        }
        List<AgentUserInputRequest.Option> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String id = String.valueOf((char) ('a' + Math.min(i, 25)));
            if (item instanceof Map<?, ?> map) {
                String label = optionalText(map.get("label"));
                if (label.isBlank()) {
                    label = optionalText(map.get("title"));
                }
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(
                            optionalText(map.get("id")).isBlank() ? id : optionalText(map.get("id")),
                            label,
                            optionalText(map.get("description")),
                            Boolean.TRUE.equals(map.get("recommended"))
                    ));
                }
            } else {
                String label = optionalText(item);
                if (!label.isBlank()) {
                    result.add(new AgentUserInputRequest.Option(id, label, "", i == 0));
                }
            }
        }
        return result;
    }
}
