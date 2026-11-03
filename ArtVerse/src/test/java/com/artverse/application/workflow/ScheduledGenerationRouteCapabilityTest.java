package com.artverse.application.workflow;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScheduledGenerationRouteCapabilityTest {
    @Test
    void scheduledImageCreationIsAvailableOnConversationRoute() {
        assertThat(MangaWorkflowCapability.SCHEDULED_IMAGE_GENERATION.isAvailable()).isTrue();
        assertThat(MangaWorkflowCapability.routingCatalog()).contains("SCHEDULED_IMAGE_GENERATION=[CONVERSATION]");
        assertThat(MangaWorkflowRoute.CONVERSATION.capabilities()).contains(MangaWorkflowCapability.SCHEDULED_IMAGE_GENERATION);
    }
}
