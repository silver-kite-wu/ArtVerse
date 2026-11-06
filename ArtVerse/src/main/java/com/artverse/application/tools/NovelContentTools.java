package com.artverse.application.tools;

import com.artverse.application.AgentToolAuditService;
import com.artverse.application.NovelContentService;
import com.artverse.application.ToolIdempotencyService;
import com.artverse.domain.NovelContentRevisionSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** The sole mutation seam for canonical novel text. */
@Component
@RequiredArgsConstructor
public class NovelContentTools {
    private final NovelContentService novelContentService;
    private final AgentToolAuditService auditService;

    public Map<String, Object> saveNovelContent(Long chapterId, Long userId, String content, Long baseVersion,
                                                 NovelContentRevisionSource source) {
        return auditService.around("save_novel_content", userId, chapterId, () -> {
            NovelContentService.SaveResult result = novelContentService.save(chapterId, userId, content, baseVersion, source);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("changed", result.changed());
            response.put("chapter_version", result.chapter().getVersion());
            if (result.revision() != null) {
                response.put("revision_id", result.revision().getId());
                response.put("revision_number", result.revision().getRevisionNumber());
                response.put("content_hash", result.revision().getContentHash());
            }
            return response;
        });
    }

    public Map<String, Object> restoreNovelContent(Long chapterId, Long revisionId, Long userId, Long baseVersion) {
        return auditService.around("restore_novel_content", userId, chapterId, () -> {
            NovelContentService.SaveResult result = novelContentService.restore(chapterId, revisionId, userId, baseVersion);
            return Map.of("changed", result.changed(), "chapter_version", result.chapter().getVersion(),
                    "revision_id", result.revision().getId(), "revision_number", result.revision().getRevisionNumber());
        });
    }
}
