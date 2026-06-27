package com.artverse.application.workflow;

public record MangaIntentResult(
        MangaWorkflowRoute route,
        String intent,
        double confidence,
        String reason,
        boolean requiresConfirmation
) {
    private static final double CONFIRMATION_THRESHOLD = 0.55;

    public MangaIntentResult {
        route = route == null ? MangaWorkflowRoute.CHAT : route;
        intent = blankToDefault(intent, route.name().toLowerCase());
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = blankToDefault(reason, "根据用户输入识别任务意图");
        requiresConfirmation = requiresConfirmation || confidence < CONFIRMATION_THRESHOLD;
    }

    public static MangaIntentResult resolved(MangaWorkflowRoute route, String intent, double confidence, String reason) {
        return new MangaIntentResult(route, intent, confidence, reason, false);
    }

    public static MangaIntentResult needsConfirmation(String intent, String reason) {
        return new MangaIntentResult(MangaWorkflowRoute.CHAT, intent, 0.35, reason, true);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
