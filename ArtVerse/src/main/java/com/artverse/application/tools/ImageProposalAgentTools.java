package com.artverse.application.tools;

import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.AgentToolAuditService;
import com.artverse.application.imageproposal.*;
import com.artverse.application.imageproposal.ImageProposalViews.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class ImageProposalAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final ImageProposalService images;
    @Tool(name="list_generation_models",description="List saved image model configuration metadata with exact config_version and signed snapshot pagination. No credentials, endpoints or provider discovery calls.",readOnly=true,concurrencySafe=true)
    public ModelPage models(@ToolParam(name="capability",required=false,description="Optional IMAGE_GENERATION capability") Capability capability,
            @ToolParam(name="cursor",required=false,description="Signed next_cursor from the same model directory") String cursor,
            @ToolParam(name="limit",required=false,description="Page size, 1-50") Integer limit,RuntimeContext runtime) {
        return images.models(support.resolveContext(runtime),capability,cursor,limit);
    }
    @Tool(name="preview_manga_generation",description="Read-only preview of exact current-chapter image targets, saved config/version and reference inputs. Returns a short-lived signed preview_ref. No proposal, quota reservation, approval or provider call; unknown costs remain UNKNOWN.",readOnly=true,concurrencySafe=true)
    public Preview preview(@ToolParam(name="selection",description="Exact selected image numbers, FIRST/REGENERATE mode and saved configuration/version") Selection selection,RuntimeContext runtime) {
        return images.preview(support.resolveContext(runtime),selection);
    }
    @Tool(name="propose_manga_generation",description="Persist a reviewable manga generation proposal from an unchanged signed preview_ref. Does not submit generation. The author reviews exact inputs in the proposal card before the existing queue can accept work.",concurrencySafe=false)
    public Map<String,Object> propose(@ToolParam(name="preview_ref",description="Unexpired preview_ref from preview_manga_generation in this exact run") String ref,RuntimeContext runtime) {
        var c=support.resolveContext(runtime);return audit.around("propose_manga_generation",c.userId(),c.chapterId(),runtime,()->images.propose(c,ref,AgentToolAttempt.require(runtime,"propose_manga_generation")));
    }
    @Tool(name="get_generation_request",description="Read current-chapter accepted image request and actual per-image result/status/recovery actions. Does not rebuild state or call the provider. ACCEPTED is not generation success; UNKNOWN remains unknown.",readOnly=true,concurrencySafe=true)
    public Request request(@ToolParam(name="request_id",description="Owned current-chapter image request UUID") String request,
            @ToolParam(name="operation_id",required=false,description="Optional exact image operation UUID in this request") String operation,RuntimeContext runtime) {
        return images.request(support.resolveContext(runtime),UUID.fromString(request),operation==null?null:UUID.fromString(operation));
    }
    @Tool(name="propose_generation_recovery",description="Prepare an author-reviewed recovery proposal for an action currently supported by the user API. Never performs recovery, ADMIN_DECISION, external outcome certification or automatic paid regeneration.",concurrencySafe=false)
    public Map<String,Object> recover(@ToolParam(name="request_id",description="Owned current-chapter request UUID") String request,
            @ToolParam(name="operation_id",description="Exact image operation UUID") String operation,
            @ToolParam(name="action",description="Currently available safe user recovery action") RecoveryAction action,
            @ToolParam(name="expected_recovery_version",description="Exact recovery_version from get_generation_request") Long version,RuntimeContext runtime) {
        var c=support.resolveContext(runtime);return audit.around("propose_generation_recovery",c.userId(),c.chapterId(),runtime,()->images.proposeRecovery(c,UUID.fromString(request),UUID.fromString(operation),action,version,AgentToolAttempt.require(runtime,"propose_generation_recovery")));
    }
}
