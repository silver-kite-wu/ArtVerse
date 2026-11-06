package com.artverse.agent.gateway;

import com.artverse.agent.*;
import com.artverse.agent.gateway.tools.AgentToolConfigurationRegistry;
import com.artverse.agent.gateway.tools.AgentToolInvocationGuard;
import com.artverse.application.*;
import com.artverse.application.automation.AutomationAgentTools;
import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Factory, Harness, native spawn/task/wait and read-only child Harnesses; no provider calls. */
class NativeReviewExpertsTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> HELPERS=Set.of("agent_spawn","task_output","task_list","task_cancel","wait_async_results");

    @Test void fourExplicitExpertsRunThroughNativeTasksAndReuseTheirOriginalTask() throws Exception {
        var f=new Fixture();
        AtomicInteger parentRounds=new AtomicInteger(),childCalls=new AtomicInteger();
        var model=model((messages,tools)->{
            if(tools.stream().noneMatch(tool->"agent_spawn".equals(tool.getName()))) {
                childCalls.incrementAndGet();
                assertThat(tools).extracting(ToolSchema::getName).containsExactly("get_chapter_context");
                if(resultText(messages,"get_chapter_context").isEmpty())return response(call("read-"+UUID.randomUUID(),"get_chapter_context",Map.of()));
                return response(TextBlock.builder().text("只读专家审查结果").build());
            }
            assertThat(tools).extracting(ToolSchema::getName).containsExactlyInAnyOrderElementsOf(union(HELPERS,"get_chapter_context"));
            int round=parentRounds.getAndIncrement();
            return switch(round) {
                case 0 -> response(MangaReviewSubagentDeclarations.all().stream().map(expert->
                        (ContentBlock)call("spawn-"+expert.getName(),"agent_spawn",spawn(expert.getName()))).toList());
                case 1 -> response(call("wait","wait_async_results",Map.of("task_ids",String.join(",",f.tasks.values().stream().map(AgentReviewExpert::taskId).toList()),"timeout_seconds",5)));
                case 2 -> {
                    assertThat(resultText(messages,"wait_async_results")).contains("只读专家审查结果");
                    yield response(call("repeat-camera","agent_spawn",spawn("camera-reviewer")));
                }
                case 3 -> {
                    assertThat(resultText(messages,"agent_spawn")).contains("Existing expert task retained");
                    yield response(call("camera-output","task_output",Map.of("task_id",f.tasks.get("camera-reviewer").taskId(),"block",false)));
                }
                case 4 -> {
                    assertThat(resultText(messages,"task_output")).contains("只读专家审查结果");
                    yield response(TextBlock.builder().text("四专家审查完成").build());
                }
                default -> throw new AssertionError("Unexpected parent model call");
            };
        });
        HarnessAgent parent=f.factory.buildAgent(f.request,workspace,BusinessSkillSelection.empty(),model);
        try {
            assertThat(parent.getToolkit().getToolNames()).containsExactlyInAnyOrderElementsOf(union(HELPERS,"get_chapter_context"));
            Msg result=parent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("审查当前章").build()),f.context()).block(Duration.ofSeconds(20));
            assertThat(result.getTextContent()).isEqualTo("四专家审查完成");
            assertThat(f.tasks).hasSize(4);
            assertThat(childCalls).hasValue(8);
            verify(f.budget,times(4)).reserveReviewExpert(eq(f.request),any());
            verify(f.turns,times(4)).acquire(anyString());
            verify(f.budget,times(4)).reserveToolCalls(argThat(request->request.variables().get("review_expert") instanceof AgentReviewExpert),anyList());
            verify(f.budget,atLeastOnce()).reserveModelCall(argThat(request->
                    request.variables().get("review_expert") instanceof AgentReviewExpert expert
                            && request.requestId().equals(f.request.requestId()) && expert.parentRequestId().equals(f.request.requestId())
                            && "manga-review".equals(request.variables().get("step_id")) && request.businessSkillSelection().isEmpty()),any(),anyString(),any());
        } finally {f.factory.closeAgent(parent);}
        f.childLeases.forEach(lease->verify(lease).close());
    }

    @Test void helpersRejectUnscopedWaitsUnknownExpertsForeignTasksAndForeignRuntime() {
        var f=new Fixture();
        try(var scope=f.support.open(f.request,workspace,(request,state)->{throw new AssertionError("No child is authorized");})) {
            Toolkit toolkit=new Toolkit();scope.install(toolkit);
            assertThat(toolkit.getToolNames()).isEqualTo(HELPERS);
            assertThatThrownBy(()->invoke(toolkit,"agent_spawn",spawn("general-purpose"),f.context()))
                    .hasMessageContaining("REVIEW_EXPERT_DENIED");
            var sync=new LinkedHashMap<String,Object>(spawn("camera-reviewer"));sync.put("timeout_seconds",1);
            assertThatThrownBy(()->invoke(toolkit,"agent_spawn",sync,f.context())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->invoke(toolkit,"wait_async_results",Map.of("timeout_seconds",1),f.context())).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->invoke(toolkit,"task_output",Map.of("task_id","foreign-task","block",false),f.context())).isInstanceOf(RuntimeException.class);
            var foreign=RuntimeContext.builder(f.context()).userId("another-author").build();
            assertThatThrownBy(()->invoke(toolkit,"task_list",Map.of(),foreign)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->invoke(toolkit,"task_cancel",Map.of("task_id","foreign-task"),f.context())).isInstanceOf(RuntimeException.class);
            verify(f.budget,never()).reserveReviewExpert(any(),any());
        }
    }

    @Test void taskCancellationInterruptsTheActualChildAndClosesItsLease() throws Exception {
        var f=new Fixture();
        var childStarted=new CountDownLatch(1);var childStopped=new CountDownLatch(1);
        AtomicInteger rounds=new AtomicInteger();
        Model model=new Model() {
            @Override public String getModelName(){return "native-review-cancellation";}
            @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                if(tools.stream().noneMatch(tool->"agent_spawn".equals(tool.getName()))) {
                    return Flux.<ChatResponse>never().doOnSubscribe(ignored -> childStarted.countDown()).doOnCancel(childStopped::countDown);
                }
                return Flux.defer(()->{
                    switch(rounds.getAndIncrement()) {
                        case 0:return response(call("spawn-camera","agent_spawn",spawn("camera-reviewer")));
                        case 1:
                            await(childStarted);
                            return response(call("cancel-camera","task_cancel",Map.of("task_id",f.tasks.get("camera-reviewer").taskId())));
                        default:
                            await(childStopped);
                            return response(TextBlock.builder().text("专家已取消，审查缺项").build());
                    }
                });
            }
        };
        HarnessAgent parent=f.factory.buildAgent(f.request,workspace,BusinessSkillSelection.empty(),model);
        try {
            Msg result=parent.call(List.of(Msg.builder().role(MsgRole.USER).textContent("审查后取消专家").build()),f.context()).block(Duration.ofSeconds(15));
            assertThat(result.getTextContent()).contains("缺项");
            assertThat(childStopped.getCount()).isZero();
        } finally {f.factory.closeAgent(parent);}
        assertThat(f.childLeases).hasSize(1);
        verify(f.childLeases.getFirst()).close();
    }

    @Test void rebuildingTheScopeRetainsAllFourNativeTaskIdentitiesAndNeverRespawns() {
        var f=new Fixture();
        var model=model((messages,tools)->response(TextBlock.builder().text("已恢复原专家任务").build()));
        for(var expert:MangaReviewSubagentDeclarations.all())f.tasks.put(expert.getName(),new AgentReviewExpert(expert.getName(),"old-"+expert.getName(),f.request.requestId(),"manga-review"));
        HarnessAgent parent=f.factory.buildAgent(f.request,workspace,BusinessSkillSelection.empty(),model);
        try {
            for(var expert:MangaReviewSubagentDeclarations.all()) {
                String output=text(invoke(parent.getToolkit(),"agent_spawn",spawn(expert.getName()),f.context()));
                assertThat(output).contains("old-"+expert.getName(),"interrupted: native task record unavailable");
            }
            verify(f.budget,never()).reserveReviewExpert(any(),any());
            verifyNoInteractions(f.turns);
        } finally {f.factory.closeAgent(parent);}
    }

    private final class Fixture {
        final AgentBudgetService budget=mock(AgentBudgetService.class);
        final MangaAgentRunService runs=mock(MangaAgentRunService.class);
        final AgentSessionTurns turns=mock(AgentSessionTurns.class);
        final AgentTurnStateStores states=mock(AgentTurnStateStores.class);
        final ReviewExpertBindings bindings=mock(ReviewExpertBindings.class);
        final PostgresAgentWorkspaceStore store=memoryWorkspace();
        final Map<String,AgentReviewExpert> tasks=new ConcurrentHashMap<>();
        final List<AgentSessionTurns.Lease> childLeases=new CopyOnWriteArrayList<>();
        final AgentRunRequest request=new AgentRunRequest("1",2L,3L,AgentTaskType.MANGA_REVIEW,
                List.of(new AgentMessage("user","审查当前章")),Map.of("step_id","manga-review","execution_owner","fixture-worker","fencing_token",1L),
                null,"",UUID.randomUUID(),UUID.randomUUID(),BusinessSkillSelection.empty());
        final ReviewExpertSupport support;
        final AgentScopeAgentFactory factory;
        Fixture() {
            ToolCapabilityFixtures.mockScopes(runs);
            when(budget.reserveModelCall(any(),any(),anyString(),any())).thenReturn(true);
            when(budget.measureOutput(anyString())).thenReturn(new AgentBudgetService.OutputUsage(1,1));
            when(budget.modelTimeAllowance(any(),any())).thenReturn(Duration.ofMinutes(1));
            when(budget.reserveToolCalls(any(),anyList())).thenReturn(true);
            when(budget.reserveReviewExpert(any(),any())).thenReturn(true);
            when(bindings.find(any(),anyString())).thenAnswer(invocation->tasks.get(invocation.getArgument(1,String.class)));
            when(bindings.register(any(),any(),anyString())).thenAnswer(invocation->{var identity=invocation.getArgument(1,AgentReviewExpert.class);return tasks.putIfAbsent(identity.expertId(),identity)==null;});
            doAnswer(invocation->{
                if(tasks.values().stream().noneMatch(identity->identity.taskId().equals(invocation.getArgument(1))))throw new BusinessException(403,"foreign expert task");
                return null;
            }).when(bindings).requireTask(any(),anyString());
            try {when(turns.acquire(anyString())).thenAnswer(invocation->{var lease=mock(AgentSessionTurns.Lease.class);childLeases.add(lease);return lease;});}
            catch(io.agentscope.harness.agent.gateway.TurnBusyException impossible){throw new AssertionError(impossible);}
            when(states.forTurn(anyString(),anyString(),any())).thenAnswer(invocation->new StrictAgentStateStore(new InMemoryAgentStateStore()));
            var registry=mock(AgentToolConfigurationRegistry.class);
            doAnswer(invocation->{Toolkit toolkit=invocation.getArgument(0);toolkit.createToolGroup("story-context","fixture chapter",true);
                toolkit.registration().agentTool(new AgentToolInvocationGuard(new ChapterRead())).group("story-context").apply();return null;
            }).when(registry).configure(any(),any());
            support=new ReviewExpertSupport(bindings,budget,store,turns,states,new AgentScopeRuntimeContextFactory());
            var boundary = new AgentToolBoundaryMiddleware(runs,mock(AutomationAgentTools.class),budget);
            boundary.setStoryboardArtifacts(mock(StoryboardArtifactService.class));
            factory=new AgentScopeAgentFactory(CompactionConfig.builder().build(),new ArtVerseProperties(),mock(AgentWorkspaceService.class),
                    new MangaAgentPromptProvider(),new StrictAgentStateStore(new InMemoryAgentStateStore()),states,registry,
                    mock(ArtVerseSkillRegistry.class),mock(ArtVerseSkillRepository.class),runs,store,
                    boundary);
            factory.setReviewExpertSupport(support);
        }
        RuntimeContext context(){return new AgentScopeRuntimeContextFactory().create(request);}
    }

    private static final class ChapterRead extends ToolBase {
        ChapterRead(){super(ToolBase.builder().name("get_chapter_context").description("Read fixture source and storyboard").readOnly(true)
                .inputSchema(Map.of("type","object","properties",Map.of(),"additionalProperties",false)));}
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param){return Mono.just(ToolResultBlock.text("{\"source\":\"当前章来源与分镜\"}"));}
    }
    private static PostgresAgentWorkspaceStore memoryWorkspace() {
        var store=mock(PostgresAgentWorkspaceStore.class);
        Map<List<String>,Map<String,StoreItem>> namespaces=new ConcurrentHashMap<>();
        when(store.get(anyList(),anyString())).thenAnswer(invocation->namespaces.getOrDefault(invocation.getArgument(0),Map.of()).get(invocation.getArgument(1)));
        doAnswer(invocation->{List<String> namespace=invocation.getArgument(0);String key=invocation.getArgument(1);Map<String,Object> value=invocation.getArgument(2);
            var items=namespaces.computeIfAbsent(List.copyOf(namespace),ignored->new ConcurrentHashMap<>());
            synchronized(items){var previous=items.get(key);items.put(key,new StoreItem(key,new LinkedHashMap<>(value),previous==null?1:previous.version()+1));}return null;
        }).when(store).put(anyList(),anyString(),anyMap());
        when(store.putIfVersion(anyList(),anyString(),anyMap(),anyLong())).thenAnswer(invocation->{
            List<String> namespace=invocation.getArgument(0);String key=invocation.getArgument(1);Map<String,Object> value=invocation.getArgument(2);long expected=invocation.getArgument(3);
            var items=namespaces.computeIfAbsent(List.copyOf(namespace),ignored->new ConcurrentHashMap<>());
            synchronized(items){var previous=items.get(key);if((previous==null?0:previous.version())!=expected)return false;
                items.put(key,new StoreItem(key,new LinkedHashMap<>(value),expected+1));return true;}
        });
        when(store.search(anyList(),anyInt(),anyInt())).thenAnswer(invocation->{
            int limit=invocation.getArgument(1),offset=invocation.getArgument(2);
            return namespaces.getOrDefault(invocation.getArgument(0),Map.of()).values().stream().sorted(Comparator.comparing(StoreItem::key)).skip(offset).limit(limit).toList();
        });
        return store;
    }
    private interface ModelStep {Flux<ChatResponse> apply(List<Msg> messages,List<ToolSchema> tools);}
    private static Model model(ModelStep step){return new Model(){
        @Override public String getModelName(){return "native-review-fixture";}
        @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options){return Flux.defer(()->step.apply(messages,tools));}
    };}
    private static Map<String,Object> spawn(String expert){return Map.of("agent_id",expert,"task","只读审查当前章","timeout_seconds",0);}
    private static Set<String> union(Set<String> names,String extra){var result=new HashSet<>(names);result.add(extra);return result;}
    private static Flux<ChatResponse> response(ContentBlock content){return response(List.of(content));}
    private static Flux<ChatResponse> response(List<ContentBlock> content){return Flux.just(ChatResponse.builder().content(content).finishReason(content.getFirst() instanceof ToolUseBlock?"tool_calls":"stop").build());}
    private static ToolUseBlock call(String id,String name,Map<String,Object> input){try{return new ToolUseBlock(id,name,input,JSON.writeValueAsString(input),Map.of());}catch(Exception failure){throw new AssertionError(failure);}}
    private static ToolResultBlock invoke(Toolkit toolkit,String name,Map<String,Object> input,RuntimeContext runtime){
        return toolkit.getTool(name).callAsync(ToolCallParam.builder().toolUseBlock(call(UUID.randomUUID().toString(),name,input)).input(input).runtimeContext(runtime).build()).block(Duration.ofSeconds(10));
    }
    private static String text(ToolResultBlock result){return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).reduce("",String::concat);}
    private static String resultText(List<Msg> messages,String tool){return messages.stream().flatMap(message->message.getContentBlocks(ToolResultBlock.class).stream()).filter(result->tool.equals(result.getName())).map(NativeReviewExpertsTest::text).reduce("",String::concat);}
    private static void await(CountDownLatch latch){try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Native child barrier timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
}
