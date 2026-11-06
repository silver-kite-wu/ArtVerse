package com.artverse.api;

import com.artverse.application.CurrentUserService;
import com.artverse.application.NovelContentService;
import com.artverse.application.tools.NovelContentTools;
import com.artverse.domain.ChapterNovelRevision;
import com.artverse.domain.NovelContentRevisionSource;
import com.artverse.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters/{chapterId}/novel-content")
@RequiredArgsConstructor
public class NovelContentController {
    private final NovelContentService novelContentService;
    private final NovelContentTools novelContentTools;
    private final CurrentUserService currentUserService;

    @GetMapping("/revisions")
    public List<RevisionDto> revisions(@PathVariable Long chapterId) {
        User user = currentUserService.requireCurrentUser();
        return novelContentService.listRevisions(chapterId, user.getId()).stream().map(RevisionDto::from).toList();
    }

    @PutMapping
    public Map<String, Object> save(@PathVariable Long chapterId, @RequestBody SaveRequest request) {
        User user = currentUserService.requireCurrentUser();
        return novelContentTools.saveNovelContent(chapterId, user.getId(), request.content(), request.baseVersion(),
                NovelContentRevisionSource.MANUAL);
    }

    @PostMapping("/revisions/{revisionId}/restore")
    public Map<String, Object> restore(@PathVariable Long chapterId, @PathVariable Long revisionId,
                                       @RequestBody RestoreRequest request) {
        User user = currentUserService.requireCurrentUser();
        return novelContentTools.restoreNovelContent(chapterId, revisionId, user.getId(), request.baseVersion());
    }

    public record SaveRequest(String content, Long baseVersion, NovelContentRevisionSource source) {}
    public record RestoreRequest(Long baseVersion) {}
    public record RevisionDto(Long id, Integer revisionNumber, String content, String contentHash, String source,
                              OffsetDateTime createdAt) {
        static RevisionDto from(ChapterNovelRevision value) {
            return new RevisionDto(value.getId(), value.getRevisionNumber(), value.getContent(), value.getContentHash(),
                    value.getSource().name().toLowerCase(), value.getCreatedAt());
        }
    }
}
