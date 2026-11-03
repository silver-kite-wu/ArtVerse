package com.artverse.application.workflow;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business capabilities understood by the manga workflow router.
 *
 * <p>A capability may be known but unavailable. Keeping unavailable capabilities
 * in the catalog lets the semantic router reject them without language-specific
 * keyword filters.</p>
 */
public enum MangaWorkflowCapability {
    CONVERSATION(false),
    CREATIVE_GUIDANCE(false),
    STORYBOARD_READ(false),
    STORYBOARD_WRITE(true),
    STORYBOARD_REVIEW(false),
    IMAGE_GENERATION(true),
    SCHEDULED_IMAGE_GENERATION(false);

    private final boolean mutating;

    MangaWorkflowCapability(boolean mutating) {
        this.mutating = mutating;
    }

    public boolean isMutating() {
        return mutating;
    }

    public boolean isAvailable() {
        return MangaWorkflowRoute.routesProviding(this).size() > 0;
    }

    public static Set<MangaWorkflowCapability> unavailable(Set<MangaWorkflowCapability> capabilities) {
        EnumSet<MangaWorkflowCapability> unavailable = EnumSet.noneOf(MangaWorkflowCapability.class);
        capabilities.stream().filter(capability -> !capability.isAvailable()).forEach(unavailable::add);
        return Set.copyOf(unavailable);
    }

    public static String routingCatalog() {
        return java.util.Arrays.stream(values())
                .map(capability -> capability.name() + "="
                        + (capability.isAvailable()
                        ? MangaWorkflowRoute.routesProviding(capability).stream()
                                .map(Enum::name)
                                .sorted()
                                .collect(Collectors.joining(",", "[", "]"))
                        : "UNAVAILABLE"))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }
}
