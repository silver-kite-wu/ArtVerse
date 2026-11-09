package com.artverse.application.automation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.*;

/** Explicit projections: never serialize a provider configuration, raw run or arbitrary persisted payload. */
public final class AutomationReadViews {
    private AutomationReadViews() {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelDisplay(Long configId,String label,String provider,String model,boolean available) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Targets(long storyId,List<Long> chapterIds,String inputPolicy) {
        public Targets{chapterIds=List.copyOf(chapterIds);}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Definition(String name,String taskType,String executor,String instruction,
                             AutomationDefinition.Schedule schedule,AutomationDefinition.Limits limits,Targets targets,
                             String misfirePolicy,String overlapPolicy,Integer maxLatenessSeconds,String permissionMode) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Plan(UUID automationId,String status,long version,Definition definition,ModelDisplay model,
                       Instant nextRunAt,Instant createdAt,Instant updatedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Preview(String previewRef,String definitionHash,String inputSnapshotHash,Definition definition,
                          ModelDisplay model,List<Map<String,String>> occurrences,Instant observedAt,Instant expiresAt,
                          boolean authorizationGranted,boolean quotaReserved) {
        public Preview{occurrences=List.copyOf(occurrences);}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Run(UUID runId,UUID automationId,long automationVersion,String state,String triggerKind,int attemptCount,
                      String errorCode,String waitingReason,Instant scheduledFor,Instant createdAt,Instant startedAt,Instant completedAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunPage(List<Run> items,String nextCursor,boolean hasMore,String snapshot) {
        public RunPage{items=List.copyOf(items);}
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Artifact(UUID artifactId,String type,String status,long chapterId,String contentHash) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PendingInput(UUID agentRequestId,String toolCallId,Long pendingVersion,String purpose) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Image(long chapterId,int imageNumber,String state,Long resultImageId,Long resultVersion,String errorCode) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunDetail(Run run,Definition frozenDefinition,ModelDisplay model,List<Artifact> artifacts,
                            List<PendingInput> pendingInputs,List<Image> images,boolean hasMoreArtifacts,boolean hasMoreImages,
                            boolean hasMorePendingInputs) {
        public RunDetail{artifacts=List.copyOf(artifacts);pendingInputs=List.copyOf(pendingInputs);images=List.copyOf(images);}
    }
}
