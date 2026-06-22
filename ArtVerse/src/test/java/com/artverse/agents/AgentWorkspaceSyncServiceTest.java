package com.artverse.agents;

import com.artverse.domain.Chapter;
import com.artverse.domain.ColorMode;
import com.artverse.domain.ContentSource;
import com.artverse.domain.MangaImage;
import com.artverse.domain.Story;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkspaceSyncServiceTest {

    @Test
    void buildsKnowledgeSnapshotForMangaDirector() {
        Story story = new Story();
        story.setId(10L);
        story.setTitle("星河旅人");
        story.setDescription("少年穿越星河寻找失落城市。");
        story.setMangaStyle("japanese_manga");

        Chapter chapter = new Chapter();
        chapter.setId(20L);
        chapter.setStory(story);
        chapter.setChapterNumber(3);
        chapter.setImageCount(8);
        chapter.setColorMode(ColorMode.COLOR);
        chapter.setContentSource(ContentSource.IMPORT);
        chapter.setNovelContent("主角抵达港口，发现城市悬浮在星河之上。");
        chapter.setScenesText("[\"第1页分镜\",\"第2页分镜\"]");

        MangaImage image = new MangaImage();
        image.setImageNumber(1);
        image.setImagePath("manga_outputs/chapter_20/panel_01.png");
        image.setPrompt("港口与星河");

        AgentWorkspaceSyncService service = new AgentWorkspaceSyncService(null, null, null, null);

        String knowledge = service.buildKnowledge(
                chapter,
                story,
                List.of(image),
                Map.of("content", "角色名：林澈\n描述：银发少年", "source", "asset_group")
        );

        assertThat(knowledge).contains("# Story Knowledge");
        assertThat(knowledge).contains("Title: 星河旅人");
        assertThat(knowledge).contains("Display Name: 第3话");
        assertThat(knowledge).contains("Image Count: 8");
        assertThat(knowledge).contains("主角抵达港口");
        assertThat(knowledge).contains("Scenes Count: 2");
        assertThat(knowledge).contains("panel_01.png");
        assertThat(knowledge).contains("角色名：林澈");
    }
}
