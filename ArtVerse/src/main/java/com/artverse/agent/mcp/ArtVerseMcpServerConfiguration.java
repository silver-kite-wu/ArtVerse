package com.artverse.agent.mcp;

import com.artverse.agent.MangaAgentRuntimeContext;
import com.artverse.agent.gateway.AgentScopeRuntimeContextFactory;
import com.artverse.application.UserProviderConfig;
import com.artverse.application.tools.MangaContextTools;
import com.artverse.application.tools.StoryboardAgentTools;
import com.artverse.application.tools.StoryChatContextTools;
import com.artverse.application.tools.StoryChatNovelContentTools;
import com.artverse.application.ScheduledMangaGenerationService;
import com.artverse.config.ArtVerseProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** Business-owned MCP boundary. Tool implementations remain in the business application. */
@Configuration
public class ArtVerseMcpServerConfiguration {
    private static final String ENDPOINT = "/internal/mcp";

    @Bean
    McpJsonMapper artVerseMcpJsonMapper(ObjectMapper mapper) {
        return new JacksonMcpJsonMapper(mapper);
    }

    @Bean
    HttpServletStreamableServerTransportProvider artVerseMcpTransport(
            McpJsonMapper mapper, McpAgentExecutionTokenService tokenService) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper)
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(request -> {
                    String authorization = request.getHeader("Authorization");
                    if (authorization == null || !authorization.startsWith("Bearer ")) {
                        throw new IllegalArgumentException("MCP bearer authorization is required");
                    }
                    var claims = tokenService.verify(authorization.substring("Bearer ".length()).trim());
                    return McpTransportContext.create(Map.of(
                            McpAgentExecutionTokenService.TRANSPORT_CONTEXT_KEY, claims));
                })
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> artVerseMcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, ENDPOINT, ENDPOINT + "/*");
        registration.setName("artVerseMcpServlet");
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpSyncServer artVerseBusinessMcpServer(
            HttpServletStreamableServerTransportProvider transport,
            McpJsonMapper mapper,
            ObjectMapper objectMapper,
            ArtVerseProperties properties,
            MangaContextTools mangaContextTools,
            StoryboardAgentTools storyboardTools,
            StoryChatContextTools storyContextTools,
            StoryChatNovelContentTools novelTools,
            ScheduledMangaGenerationService scheduledManga,
            com.artverse.application.automation.AutomationAgentTools automations) {
        if (!properties.getAgent().getMcp().isEnabled()) {
            return McpServer.sync(transport)
                    .serverInfo("artverse-business-tools", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                    .build();
        }
        return McpServer.sync(transport)
                .serverInfo("artverse-business-tools", "1.0.0")
                .instructions("ArtVerse domain tools. Authorization and resource ownership are enforced by the business application.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(
                        tool(mapper,"create_automation",
                                "Only on an explicit user scheduling request: create a persisted ONCE, DAILY or WEEKLY existing-Agent or manga-image task in the current story. definition follows the task center DTO: name, taskType AGENT/IMAGE, executorKey MANGA_AGENT/STORY_CHAT/MANGA_IMAGE, instruction, schedule {kind,localDateTime or localTime,timezone,weekdays 1-7}, payload {chapterId or chapterIds,storyId,modelConfigId,model,inputPolicy}, optional limits and misfirePolicy. Use saved model configuration IDs; ask for missing scope/time. Return the task URL. Background automations cannot use this tool.",
                                "{\"type\":\"object\",\"properties\":{\"definition\":{\"type\":\"object\"}},\"required\":[\"definition\"]}",
                                (exchange,args)->automations.create((MangaAgentRuntimeContext)runtimeContext(exchange).get(MangaAgentRuntimeContext.class),args)),
                        tool(mapper,"list_automations","List the authenticated user's task plans in this story, including model config IDs and versions needed for updates.","{}",
                                (exchange,args)->automations.list((MangaAgentRuntimeContext)runtimeContext(exchange).get(MangaAgentRuntimeContext.class))),
                        tool(mapper,"update_automation","Only on explicit user instruction: edit, pause, resume or archive an owned task in this story. Edit requires full definition with expectedVersion; lifecycle actions require expectedVersion. Existing run snapshots remain immutable.",
                                "{\"type\":\"object\",\"properties\":{\"automationId\":{\"type\":\"string\"},\"action\":{\"type\":\"string\",\"enum\":[\"edit\",\"pause\",\"resume\",\"archive\"]},\"expectedVersion\":{\"type\":\"integer\"},\"definition\":{\"type\":\"object\"}},\"required\":[\"automationId\",\"action\"]}",
                                (exchange,args)->automations.update((MangaAgentRuntimeContext)runtimeContext(exchange).get(MangaAgentRuntimeContext.class),args)),
                        tool(mapper, "get_chapter_context",
                                "Read the current chapter, story settings, storyboard, and generated-image status.",
                                "{}", (exchange, args) -> mangaContextTools.getChapterContext(runtimeContext(exchange))),
                        tool(mapper, "schedule_manga_generation",
                                "Create a one-time manga image generation task for the current chapter, or the explicitly selected chapter numbers in the current story. Call only for an explicit scheduling request with a clear execution time; use the product timezone default when the user gives no zone. Returns persisted task IDs and validated scope.",
                                "{\"type\":\"object\",\"properties\":{\"localTime\":{\"type\":\"string\",\"description\":\"Local date and time without offset, e.g. 2026-10-22T00:00:00\"},\"timezone\":{\"type\":\"string\",\"description\":\"Optional IANA zone; if omitted, the configured product default is used\"},\"chapterNumbers\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"maxItems\":3,\"description\":\"Optional explicit chapter numbers from the current story\"},\"model\":{\"type\":\"string\"},\"configId\":{\"type\":\"integer\"}},\"required\":[\"localTime\"]}",
                                (exchange, args) -> {
                                    MangaAgentRuntimeContext ctx = (MangaAgentRuntimeContext) runtimeContext(exchange).get(MangaAgentRuntimeContext.class);
                                    automations.requireInteractive(ctx);
                                    if(ctx.requestId()==null)throw new IllegalStateException("Authenticated request id is required for task idempotency");
                                    String key = "agent:" + ctx.requestId() + ":" + ctx.chapterId();
                                    java.time.LocalDateTime localTime=java.time.LocalDateTime.parse(String.valueOf(args.get("localTime")));
                                    String timezone=args.get("timezone")==null?null:String.valueOf(args.get("timezone"));
                                    String model=args.get("model")==null?null:String.valueOf(args.get("model"));
                                    Long configId=args.get("configId")==null?null:((Number)args.get("configId")).longValue();
                                    if(args.get("chapterNumbers") instanceof List<?> values){
                                        List<Integer> chapterNumbers=values.stream().map(value->((Number)value).intValue()).toList();
                                        return scheduledManga.createBatch(ctx.userId(),ctx.storyId(),ctx.chapterId(),chapterNumbers,localTime,timezone,model,configId,key);
                                    }
                                    return scheduledManga.create(ctx.userId(),ctx.chapterId(),localTime,timezone,model,configId,key);
                                }),
                        tool(mapper, "draft_structured_storyboard",
                                "Create and validate a storyboard draft without changing the chapter.",
                                "{\"type\":\"object\",\"properties\":{\"pages\":{}},\"required\":[\"pages\"]}",
                                (exchange, args) -> storyboardTools.draftStructuredStoryboard(args.get("pages"), runtimeContext(exchange))),
                        tool(mapper, "commit_storyboard",
                                "Commit one validated storyboard artifact. This is the only chapter write operation in the new storyboard workflow.",
                                "{\"type\":\"object\",\"properties\":{\"artifact_id\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"artifact_id\"]}",
                                (exchange, args) -> storyboardTools.commitStoryboard(
                                        String.valueOf(args.get("artifact_id")), runtimeContext(exchange))),
                        tool(mapper, "get_novel_context",
                                "Read the current story, chapter, canonical novel text, and version.",
                                "{}", (exchange, args) -> storyContextTools.getNovelContext(runtimeContext(exchange))),
                        tool(mapper, "draft_novel_content",
                                "Save a complete novel-text draft as an artifact without changing the chapter.",
                                "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\"}},\"required\":[\"content\"]}",
                                (exchange, args) -> novelTools.draftNovelContent(String.valueOf(args.get("content")), runtimeContext(exchange))),
                        tool(mapper, "commit_novel_content",
                                "Commit the exact user-confirmed novel-text artifact as a new chapter revision.",
                                "{\"type\":\"object\",\"properties\":{\"artifactId\":{\"type\":\"string\",\"format\":\"uuid\"}},\"required\":[\"artifactId\"]}",
                                (exchange, args) -> novelTools.commitNovelContent(
                                        objectMapper.convertValue(args.get("artifactId"), java.util.UUID.class), runtimeContext(exchange)))
                )
                .build();
    }

    private io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool(
            McpJsonMapper mapper, String name, String description, String schema,
            java.util.function.BiFunction<McpSyncServerExchange, Map<String, Object>, Object> action) {
        return new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder().name(name).description(description).inputSchema(mapper, schema).build(),
                (exchange, arguments) -> {
                    try {
                        Object result = action.apply(exchange, arguments);
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(new McpSchema.TextContent(writeJson(mapper, result))))
                                .build();
                    } catch (RuntimeException error) {
                        return McpSchema.CallToolResult.builder()
                                .isError(true)
                                .content(List.of(new McpSchema.TextContent(error.getMessage() == null
                                        ? "Business tool failed" : error.getMessage())))
                                .build();
                    }
                });
    }

    private io.agentscope.core.agent.RuntimeContext runtimeContext(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext().get(McpAgentExecutionTokenService.TRANSPORT_CONTEXT_KEY);
        if (!(value instanceof McpAgentExecutionTokenService.ExecutionClaims claims)) {
            throw new IllegalArgumentException("Authenticated ArtVerse execution context is required");
        }
        MangaAgentRuntimeContext context = new MangaAgentRuntimeContext(
                AgentScopeRuntimeContextFactory.parseUserIdForTool(claims.userId()),
                claims.storyId(), claims.chapterId(), claims.conversationId(), claims.requestId(),
                new UserProviderConfig("workflow", "", "", "", "", ""),
                claims.taskType(), claims.stepId(), claims.fencingToken(), claims.tenantId());
        return io.agentscope.core.agent.RuntimeContext.builder()
                .userId(claims.userId())
                .sessionId(AgentScopeRuntimeContextFactory.createSessionId(claims.userId(), claims.storyId(),
                        claims.chapterId(), claims.conversationId(), claims.taskType()))
                .put(MangaAgentRuntimeContext.class, context)
                .build();
    }

    private static String writeJson(McpJsonMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize business tool result", error);
        }
    }
}
