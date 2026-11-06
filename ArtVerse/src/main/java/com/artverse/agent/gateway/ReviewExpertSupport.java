package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolInvocationGuard;
import com.artverse.application.AgentBudgetService;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.middleware.*;
import io.agentscope.harness.agent.subagent.task.*;
import io.agentscope.harness.agent.tool.WaitAsyncResultsTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Four native entries, native durable tasks, and a narrow parent-scope adapter. */
@Component
@RequiredArgsConstructor
public class ReviewExpertSupport {
    private static final String SPAWN_CALL = "artverse.review.spawn_call_id";
    private static final Set<String> HELPERS = Set.of("agent_spawn","task_output","task_list","task_cancel","wait_async_results");
    private final ReviewExpertBindings bindings;
    private final AgentBudgetService budget;
    private final PostgresAgentWorkspaceStore workspaceStore;
    private final AgentSessionTurns turns;
    private final AgentTurnStateStores states;
    private final AgentScopeRuntimeContextFactory contexts;

    public Scope open(AgentRunRequest parent,Path workspace,
                      BiFunction<AgentRunRequest,AgentStateStore,HarnessAgent> childBuilder) {
        bindings.requireParent(parent);
        return new Scope(parent,workspace,childBuilder);
    }

    public final class Scope implements AutoCloseable {
        private final AgentRunRequest parent;
        private final String parentSession;
        private final String repositorySession;
        private final WorkspaceManager workspace;
        private final WorkspaceTaskRepository nativeRepository;
        private final ScopedRepository repository;
        private final SubagentsMiddleware middleware;
        private final BiFunction<AgentRunRequest,AgentStateStore,HarnessAgent> childBuilder;
        private final Set<LazyExpert> children=ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed=new AtomicBoolean();
        private final AtomicBoolean reconciled=new AtomicBoolean();
        private volatile RuntimeContext lastContext;

        private Scope(AgentRunRequest parent,Path workspacePath,
                      BiFunction<AgentRunRequest,AgentStateStore,HarnessAgent> childBuilder) {
            this.parent=parent;this.childBuilder=childBuilder;
            parentSession=AgentScopeRuntimeContextFactory.createSessionId(parent);
            repositorySession="review-"+AgentModelSpecFactory.sha256Hex(parent.userId()+":"+parent.storyId()+":"+parent.chapterId()+":"+parent.requestId()+":"+ReviewExpertBindings.step(parent));
            workspace=new WorkspaceManager(workspacePath,new RemoteFilesystem(workspaceStore,AgentWorkspaceService::namespaceFor),null);
            nativeRepository=new WorkspaceTaskRepository(workspace,"artverse-review-experts");
            repository=new ScopedRepository();
            List<SubagentEntry> entries=MangaReviewSubagentDeclarations.all().stream().map(declaration->
                    new SubagentEntry(declaration.getName(),declaration.getDescription(),rc->{
                        requireRuntime(rc);
                        var child=new LazyExpert(declaration.getName(),rc);children.add(child);return child;
                    },declaration)).toList();
            middleware=new SubagentsMiddleware(entries,repository,workspace);
        }

        public void install(Toolkit toolkit) {
            Toolkit generated=new Toolkit();middleware.getTools().forEach(generated::registerTool);
            generated.removeTool("agent_send");generated.removeTool("agent_list");
            if(!generated.getToolNames().equals(Set.of("agent_spawn","task_output","task_list","task_cancel")))
                throw new IllegalStateException("Unexpected native review helper set: "+generated.getToolNames());
            generated.registerTool(new WaitAsyncResultsTool(null,repository));
            toolkit.createToolGroup("review-experts","Fixed read-only review experts",true);
            for(String name:HELPERS) {
                if(toolkit.getToolNames().contains(name))throw new IllegalStateException("Duplicate review helper: "+name);
                toolkit.registration().agentTool(new AgentToolInvocationGuard(new ScopedHelper(generated.getTool(name))))
                        .group("review-experts").apply();
            }
        }

        public MiddlewareBase middleware() {
            return new MiddlewareBase() {
                @Override public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext rc,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next) {
                    return Flux.defer(()->{
                        requireRuntime(rc);reconcile(rc);
                        return middleware.onAgent(agent,rc,input,next);
                    }).doFinally(signal->close());
                }
                @Override public Flux<AgentEvent> onReasoning(Agent agent,RuntimeContext rc,ReasoningInput input,Function<ReasoningInput,Flux<AgentEvent>> next) {
                    requireRuntime(rc);return middleware.onReasoning(agent,rc,input,next);
                }
            };
        }

        private void requireRuntime(RuntimeContext rc) {
            if(closed.get()||rc==null||!parent.userId().equals(rc.getUserId())||!parentSession.equals(rc.getSessionId()))throw denied("parent session changed");
            var execution=rc.get(MangaAgentRuntimeContext.class);
            if(execution==null||!parent.requestId().equals(execution.requestId())||!Objects.equals(parent.chapterId(),execution.chapterId())
                    ||!Objects.equals(parent.storyId(),execution.storyId())||!Objects.equals(parent.conversationId(),execution.conversationId())
                    ||execution.taskType()!=AgentTaskType.MANGA_REVIEW||!ReviewExpertBindings.step(parent).equals(execution.stepId()))throw denied("parent execution changed");
            bindings.requireParent(parent);lastContext=rc;
        }

        private void reconcile(RuntimeContext rc) {
            if(reconciled.compareAndSet(false,true)) {
                // A persisted local future cannot be resumed on a new Java invocation.
                // Native cancellation records the missing task; its immutable spawn slot remains occupied.
                for(var task:nativeRepository.listTasks(rc,repositorySession,null))
                    if(!task.getTaskStatus().isTerminal())nativeRepository.cancelTask(rc,repositorySession,task.getTaskId());
            }
        }

        @Override public void close() {
            if(!closed.compareAndSet(false,true))return;
            RuntimeContext rc=lastContext;
            try {
                if(rc!=null)for(var task:nativeRepository.listTasks(rc,repositorySession,null))
                    if(!task.getTaskStatus().isTerminal())nativeRepository.cancelTask(rc,repositorySession,task.getTaskId());
            } finally {
                try {children.forEach(LazyExpert::interrupt);nativeRepository.shutdown();}
                finally {children.forEach(LazyExpert::close);workspace.close();}
            }
        }

        private final class ScopedHelper extends ToolBase {
            private final AgentTool nativeTool;
            ScopedHelper(AgentTool delegate) {super(metadata(delegate));nativeTool=delegate;}
            @Override public Boolean getStrict(){return nativeTool.getStrict();}
            @Override public Map<String,Object> getOutputSchema(){return nativeTool.getOutputSchema();}
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.defer(()->{
                    requireRuntime(param.getRuntimeContext());
                    var input=param.getInput();
                    if("agent_spawn".equals(getName())) {
                        String expert=Objects.toString(input.get("agent_id"),"");
                        if(MangaReviewSubagentDeclarations.all().stream().noneMatch(d->d.getName().equals(expert))
                                ||!(input.get("task") instanceof String task)||task.isBlank()||task.length()>20000
                                ||!(input.get("timeout_seconds") instanceof Number timeout)||timeout.intValue()!=0
                                ||Boolean.TRUE.equals(input.get("expose_to_user")))throw denied("use one fixed expert, a task and timeout_seconds=0");
                        var previous=bindings.find(parent,expert);
                        if(previous!=null) {
                            var original=repository.getTask(param.getRuntimeContext(),parentSession,previous.taskId());
                            String status=original==null?"interrupted: native task record unavailable":original.getStatus();
                            return Mono.just(ToolResultBlock.text("task_id: "+previous.taskId()+"\nstatus: "+status+"\nExisting expert task retained; do not spawn it again."));
                        }
                        budget.requireMinimumRemaining(parent,3,2);
                        RuntimeContext isolated=RuntimeContext.builder(param.getRuntimeContext()).build();
                        isolated.put(SPAWN_CALL,param.getToolUseBlock().getId());
                        return nativeTool.callAsync(ToolCallParam.builder(param).runtimeContext(isolated).build());
                    }
                    if("wait_async_results".equals(getName())
                            &&!Boolean.TRUE.equals(input.get("wait_all"))&&Objects.toString(input.get("task_ids"),"").isBlank())
                        throw denied("review waits require explicit task_ids or wait_all");
                    if(Set.of("task_output", "task_cancel").contains(getName())) {
                        String task = Objects.toString(input.get("task_id"), "");
                        if(task.isBlank()) throw denied("task_id is required");
                        bindings.requireTask(parent, task);
                    }
                    return nativeTool.callAsync(param);
                });
            }
        }

        private final class ScopedRepository implements TaskRepository {
            private void scope(RuntimeContext rc,String session){requireRuntime(rc);if(!parentSession.equals(session))throw denied("task session changed");}
            @Override public BackgroundTask getTask(RuntimeContext rc,String session,String task) {scope(rc,session);bindings.requireTask(parent,task);return nativeRepository.getTask(rc,repositorySession,task);}
            @Override public BackgroundTask putTask(RuntimeContext rc,String task,String expert,String session,TaskRunSpec spec) {
                scope(rc,session);
                if(!(spec instanceof TaskRunSpec.LocalTaskRunSpec local))throw denied("only native local expert tasks are supported");
                var identity=new AgentReviewExpert(expert,task,parent.requestId(),ReviewExpertBindings.step(parent));
                if(!bindings.register(parent,identity,rc.get(SPAWN_CALL,String.class))||!budget.reserveReviewExpert(parent,identity))
                    throw denied("expert task was already reserved; retrieve the original result");
                rc.put(AgentReviewExpert.class,identity);
                return nativeRepository.putTask(rc,task,expert,repositorySession,new TaskRunSpec.LocalTaskRunSpec(()->{
                    bindings.requireTask(parent,task);
                    String output=local.execution().get();
                    if(output!=null&&output.startsWith("Error: "))throw new IllegalStateException(output);
                    return output;
                }));
            }
            @Override public Collection<BackgroundTask> listTasks(RuntimeContext rc,String session,TaskStatus filter) {scope(rc,session);return nativeRepository.listTasks(rc,repositorySession,filter);}
            @Override public boolean cancelTask(RuntimeContext rc,String session,String task) {
                scope(rc,session);bindings.requireTask(parent,task);
                boolean cancelled=nativeRepository.cancelTask(rc,repositorySession,task);
                if(cancelled)children.stream().filter(child->child.matches(task)).forEach(LazyExpert::interrupt);
                return cancelled;
            }
            @Override public List<TaskDelivery> findPendingDeliveries(RuntimeContext rc,String session) {scope(rc,session);return nativeRepository.findPendingDeliveries(rc,repositorySession);}
            @Override public void markDelivered(RuntimeContext rc,String session,String task) {scope(rc,session);bindings.requireTask(parent,task);nativeRepository.markDelivered(rc,repositorySession,task);}
            @Override public boolean isDelivered(RuntimeContext rc,String session,String task) {scope(rc,session);bindings.requireTask(parent,task);return nativeRepository.isDelivered(rc,repositorySession,task);}
            @Override public void shutdown(){Scope.this.close();}
        }

        private final class LazyExpert implements Agent,AutoCloseable {
            private final String expert;
            private final RuntimeContext parentContext;
            private volatile HarnessAgent active;
            private final AtomicBoolean stopped=new AtomicBoolean();
            private final Sinks.Empty<Void> cancellation = Sinks.empty();
            LazyExpert(String expert,RuntimeContext parentContext){this.expert=expert;this.parentContext=parentContext;}
            private boolean matches(String task) {
                var identity=parentContext.get(AgentReviewExpert.class);
                return identity!=null&&identity.taskId().equals(task);
            }
            @Override public String getAgentId(){return expert;}
            @Override public String getName(){return expert;}
            @Override public Mono<Msg> call(List<Msg> messages) {
                return Mono.using(()->prepare(),running->running.agent().call(messages,running.runtime())
                        .takeUntilOther(cancellation.asMono()),Running::close);
            }
            private Running prepare() {
                if(stopped.get()||closed.get())throw denied("review task stopped");
                var identity=parentContext.get(AgentReviewExpert.class);
                if(identity==null||!expert.equals(identity.expertId()))throw denied("native task identity missing");
                bindings.requireTask(parent,identity.taskId());
                String session="expert-"+AgentModelSpecFactory.sha256Hex(repositorySession+":"+identity.expertId()+":"+identity.taskId());
                var variables=new LinkedHashMap<>(parent.variables());variables.put("review_expert",identity);variables.put("review_expert_session",session);
                var childRequest=new AgentRunRequest(parent.userId(),parent.storyId(),parent.chapterId(),AgentTaskType.MANGA_REVIEW,
                        parent.messages(),variables,parent.modelSpec(),parent.llmApiKey(),parent.requestId(),parent.conversationId(),BusinessSkillSelection.empty());
                AgentSessionTurns.Lease lease;
                try {lease=turns.acquire(parent.userId()+":"+session);}
                catch(io.agentscope.harness.agent.gateway.TurnBusyException error) {
                    throw new BusinessException(409,"审查专家正在执行，请查询原任务结果");
                }
                StrictAgentStateStore state=null;HarnessAgent agent=null;
                try {
                    state=states.forTurn(parent.userId(),session,lease);
                    agent=childBuilder.apply(childRequest,state);active=agent;
                    var runtime=RuntimeContext.builder(contexts.create(childRequest)).sessionId(session).put(AgentSessionTurns.Lease.class,lease)
                            .put(AgentReviewExpert.class,identity).build();
                    HarnessAgent child=agent;lease.onLost(child::interrupt);
                    if(stopped.get()||closed.get()){agent.interrupt();throw denied("review task stopped");}
                    return new Running(agent,runtime,state,lease);
                } catch(RuntimeException error){if(agent!=null)agent.close();if(state!=null)state.close();lease.close();throw error;}
            }
            @Override public Mono<Msg> call(List<Msg> messages,Class<?> output){return Mono.error(denied("expert output schema is fixed"));}
            @Override public Mono<Msg> call(List<Msg> messages,JsonNode output){return Mono.error(denied("expert output schema is fixed"));}
            @Override public Flux<Event> stream(List<Msg> messages,StreamOptions options){return Flux.using(this::prepare,r->r.agent().stream(messages,options,r.runtime())
                    .takeUntilOther(cancellation.asMono()),Running::close);}
            @Override public Flux<Event> stream(List<Msg> messages,StreamOptions options,Class<?> output){return Flux.error(denied("expert output schema is fixed"));}
            @Override public Flux<Event> stream(List<Msg> messages,StreamOptions options,JsonNode output){return Flux.error(denied("expert output schema is fixed"));}
            @Override public Mono<Void> observe(Msg message){return Mono.error(denied("expert session cannot receive follow-up messages"));}
            @Override public Mono<Void> observe(List<Msg> messages){return Mono.error(denied("expert session cannot receive follow-up messages"));}
            @Override public void interrupt(){stopped.set(true);cancellation.tryEmitEmpty();var agent=active;if(agent!=null)agent.interrupt();}
            @Override public void interrupt(Msg message){interrupt();}
            @Override public void close(){interrupt();}
        }
        private record Running(HarnessAgent agent,RuntimeContext runtime,StrictAgentStateStore state,AgentSessionTurns.Lease lease) implements AutoCloseable {
            @Override public void close(){try{agent.close();}finally{try{state.close();}finally{lease.close();}}}
        }
    }

    private static ToolBase.Builder metadata(AgentTool tool) {
        var builder=ToolBase.builder().name(tool.getName()).description(tool.getDescription()).inputSchema(tool.getParameters())
                .readOnly(tool.isReadOnly()).returnDirect(tool.isReturnDirect());
        if(tool instanceof ToolBase nativeTool)builder.stateInjected(nativeTool.isStateInjected()).externalTool(nativeTool.isExternalTool()).concurrencySafe(nativeTool.isConcurrencySafe());
        return builder;
    }
    private static BusinessException denied(String message){return new BusinessException(403,"REVIEW_EXPERT_DENIED: "+message);}
}
