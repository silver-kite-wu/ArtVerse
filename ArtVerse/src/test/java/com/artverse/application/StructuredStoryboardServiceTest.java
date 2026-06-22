package com.artverse.application;

import com.artverse.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class StructuredStoryboardServiceTest {

    @Test
    void convertsStructuredPagesIntoValidatedSceneText() {
        StructuredStoryboardService service = new StructuredStoryboardService(new NoopSceneService());

        List<String> scenes = service.normalize(Map.of("pages", List.of(page())), 1);

        assertThat(scenes).hasSize(1);
        assertThat(scenes.get(0))
                .contains("第1页：")
                .contains("【第1格（远景）】")
                .contains("【第2格（中景）】")
                .contains("【第3格（近景）】")
                .contains("【第4格（特写）】")
                .contains("对话气泡：「主角：我们继续前进」")
                .contains("旁白框：「风从走廊尽头吹来」")
                .contains("音效字：唰");
    }

    @Test
    void rejectsWrongPageCount() {
        StructuredStoryboardService service = new StructuredStoryboardService(new NoopSceneService());

        assertThatThrownBy(() -> service.normalize(List.of(page()), 2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Storyboard page count must equal image count");
    }

    @Test
    void rejectsPagesWithTooFewPanels() {
        StructuredStoryboardService service = new StructuredStoryboardService(new NoopSceneService());

        assertThatThrownBy(() -> service.normalize(List.of(Map.of("panels", List.of("一", "二", "三"))), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must contain 4-6 panels");
    }

    private Map<String, Object> page() {
        return Map.of("panels", List.of(
                Map.of("shot", "远景", "description", "雨夜街道被路灯切成明暗两层", "narration", "风从走廊尽头吹来"),
                Map.of("shot", "中景", "description", "主角推开门，衣角被风掀起", "dialogue", "主角：我们继续前进"),
                Map.of("shot", "近景", "description", "同伴握紧旧地图，目光有些犹豫", "dialogue", "同伴：这里不太对劲"),
                Map.of("shot", "特写", "description", "门缝里闪过一道白光", "sfx", "唰")
        ));
    }

    private static class NoopSceneService extends SceneService {
        NoopSceneService() {
            super(null, null, null);
        }

        @Override
        public void validateScenes(List<String> scenes) {
        }
    }
}
