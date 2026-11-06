package com.artverse.application.workflow;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static com.artverse.application.workflow.MangaWorkflowCapability.CREATIVE_GUIDANCE;
import static com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_READ;
import static com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_REVIEW;
import static com.artverse.application.workflow.MangaWorkflowCapability.STORYBOARD_WRITE;
import static com.artverse.application.workflow.MangaWorkflowCapability.SCHEDULED_IMAGE_GENERATION;

public enum MangaWorkflowRoute {
    CONVERSATION(MangaWorkflowCapability.CONVERSATION, STORYBOARD_READ, SCHEDULED_IMAGE_GENERATION,
            MangaWorkflowCapability.CREATIVE_PROPOSALS,MangaWorkflowCapability.IMAGE_PROPOSALS),
    CREATIVE(CREATIVE_GUIDANCE, STORYBOARD_READ),
    STORYBOARD(STORYBOARD_READ, STORYBOARD_WRITE),
    REVIEW(STORYBOARD_READ, STORYBOARD_REVIEW),
    DIRECTOR();

    private final Set<MangaWorkflowCapability> capabilities;

    MangaWorkflowRoute(MangaWorkflowCapability... capabilities) {
        this.capabilities = capabilities.length == 0
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(Arrays.asList(capabilities)));
    }

    public Set<MangaWorkflowCapability> capabilities() {
        return capabilities;
    }

    public boolean isMutating() {
        return capabilities.stream().anyMatch(MangaWorkflowCapability::isMutating);
    }

    public static Set<MangaWorkflowRoute> routesProviding(MangaWorkflowCapability capability) {
        EnumSet<MangaWorkflowRoute> routes = EnumSet.noneOf(MangaWorkflowRoute.class);
        Arrays.stream(values())
                .filter(route -> route.capabilities.contains(capability))
                .forEach(routes::add);
        return Set.copyOf(routes);
    }
}
