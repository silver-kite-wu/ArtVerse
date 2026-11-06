package com.artverse.application.imageproposal;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.core.tool.ToolParam;
import java.time.Instant;
import java.util.*;

public final class ImageProposalViews {
    private ImageProposalViews(){}
    public enum Mode { FIRST, REGENERATE }
    public enum RecoveryAction { RECHECK, REQUEST_REVIEW, RESUME_SAFE, RELEASE_CONFIRMED }
    public enum Capability { IMAGE_GENERATION }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Selection(
            @ToolParam(name="image_numbers",description="Distinct current-chapter image numbers, 1-60") List<Integer> imageNumbers,
            @ToolParam(name="config_id",description="Saved image configuration ID returned by list_generation_models") Long configId,
            @ToolParam(name="config_version",description="Exact saved configuration version from list_generation_models") String configVersion,
            @ToolParam(name="model",description="Configured image model returned by list_generation_models") String model,
            @ToolParam(name="mode",description="FIRST requires empty targets; REGENERATE requires existing target images") Mode mode,
            @ToolParam(name="asset_group_id",required=false,description="Optional current-story reference asset group ID") Long assetGroupId,
            @ToolParam(name="size",required=false,description="Image dimensions such as 1024x1024, or auto") String size){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Model(Long configId,String configVersion,String label,String provider,List<String> models,
                        List<Capability> capabilities,boolean available){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModelPage(List<Model> items,String nextCursor,boolean hasMore){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Target(int imageNumber,String expectedImageId,String expectedImageVersion,String contextHash,
                         int referenceCount){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Preview(String previewRef,Selection selection,String inputSnapshotHash,List<Target> targets,
                          String executionLane,int modelCalls,boolean admissionEnabled,String quotaAvailability,
                          String estimatedCost,Instant observedAt,Instant expiresAt,boolean authorizationGranted,
                          boolean quotaReserved){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(UUID requestId,UUID operationId,Integer imageNumber,String status,String statusSource,
                         String imageId,String imageVersion,long recoveryVersion,List<RecoveryAction> recoveryActions,
                         boolean providerOutcomeUnknown,boolean resultDeleted){}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(UUID requestId,long chapterId,String submissionStatus,List<Result> images,
                          int totalImages,boolean complete){}
}
