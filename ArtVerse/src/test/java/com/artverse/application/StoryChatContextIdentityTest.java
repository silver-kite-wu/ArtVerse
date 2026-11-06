package com.artverse.application;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.AgentTaskType;
import com.artverse.application.tools.MangaToolSupport;
import com.artverse.application.tools.StoryChatContextTools;
import com.artverse.domain.Chapter;
import com.artverse.domain.Story;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryChatContextIdentityTest {
    @Test
    void initialContextAndReadToolDistinguishResourceIdFromChapterNumber() {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        when(knowledge.recallForGeneration(any(), any(), anyInt(), anyString(), any())).thenReturn(Optional.empty());
        var snapshot = new StoryChatContextSnapshot(2L, 4L, 6L, UUID.randomUUID(), UUID.randomUUID(), 1, 3L, "正文", List.of());
        String context = new StoryChatRuntimeContextAssembler(knowledge).assemble(snapshot, "审核本章", StoryChatRoute.REVIEW).getFirst().content();
        assertThat(context).contains("chapter_id: 6", "chapter_number: 1", "chapter_display_name: 第1章");
        ChapterAccessService access = mock(ChapterAccessService.class);
        Story story = new Story(); story.setId(4L);
        Chapter chapter = new Chapter(); chapter.setId(6L); chapter.setChapterNumber(1); chapter.setStory(story); chapter.setVersion(3L);
        when(access.requireVisible(6L, 2L)).thenReturn(chapter);
        var status = mock(AgentRunToolStatus.class);
        var tools = new StoryChatContextTools(new MangaToolSupport(status), new AgentToolAuditService(status), access);
        var ctx = new MangaAgentRuntimeContext(2L, 4L, 6L, snapshot.conversationId(), snapshot.requestId(), null, AgentTaskType.STORY_CHAT_READ, "test", 1L);
        var reads = mock(NovelModelReadService.class);
        when(reads.readCurrent(ctx, null, null, null)).thenReturn(Map.of("chapter_id", 6L, "chapter_number", 1,
                "chapter_display_name", "第1章", "chapter_version", 3L));
        tools.setNovelReads(reads);
        var result = tools.getNovelContext(RuntimeContext.builder().put(MangaAgentRuntimeContext.class, ctx).build());
        assertThat(result).containsEntry("chapter_id", 6L).containsEntry("chapter_number", 1).containsEntry("chapter_display_name", "第1章");
        verify(reads).readCurrent(ctx, null, null, null);
    }
}
