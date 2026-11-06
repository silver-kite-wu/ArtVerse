package com.artverse.application.tools;

import com.artverse.agent.AgentToolAttempt;
import com.artverse.application.*;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class SettingsDraftAgentTools {
    private final MangaToolSupport support;
    private final AgentToolAuditService audit;
    private final SettingsProposalService settings;
    public enum CharacterMode {CREATE,UPDATE}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StoryPatch(@ToolParam(name="title",required=false,description="Optional title, 1-255 characters") String title,
            @ToolParam(name="description",required=false,description="Optional description, at most 12000 characters") String description,
            @ToolParam(name="manga_style",required=false,description="Supported style from current story settings") String mangaStyle) {}
    public record CharacterPatch(@ToolParam(name="name",required=false,description="Name, required for CREATE, 1-255 characters") String name,
            @ToolParam(name="description",required=false,description="Description, at most 12000 characters; no automatic chapter facts") String description) {}
    @Tool(name="draft_story_settings",description="Prepare an exact title/description/manga_style difference for author review. Requires base_version from get_story_context. Never publishes or changes ownership.",concurrencySafe=false)
    public Map<String,Object> story(@ToolParam(name="base_version",description="Exact story base_version from get_story_context") Long base,
            @ToolParam(name="patch",description="Only the changed supported setting fields") StoryPatch patch,RuntimeContext runtime) {
        var c=support.resolveContext(runtime);var p=new LinkedHashMap<String,String>();
        if(patch.title()!=null)p.put("title",patch.title());if(patch.description()!=null)p.put("description",patch.description());if(patch.mangaStyle()!=null)p.put("manga_style",patch.mangaStyle());
        return audit.around("draft_story_settings",c.userId(),c.chapterId(),runtime,()->settings.story(c,base,p,AgentToolAttempt.require(runtime,"draft_story_settings")));
    }
    @Tool(name="draft_character_profile",description="Prepare explicit CREATE or UPDATE of a character profile, limited to name/description. UPDATE requires a stable ID and base_version from get_story_context. Author review creates a separate knowledge candidate, never confirms chapter facts.",concurrencySafe=false)
    public Map<String,Object> character(@ToolParam(name="mode",description="CREATE new profile or UPDATE existing stable ID") CharacterMode mode,
            @ToolParam(name="character_id",required=false,description="Required only for UPDATE") Long id,
            @ToolParam(name="base_version",required=false,description="Required only for UPDATE") Long base,
            @ToolParam(name="patch",description="Only changed name/description fields") CharacterPatch patch,RuntimeContext runtime) {
        var c=support.resolveContext(runtime);var p=new LinkedHashMap<String,String>();if(patch.name()!=null)p.put("name",patch.name());if(patch.description()!=null)p.put("description",patch.description());
        return audit.around("draft_character_profile",c.userId(),c.chapterId(),runtime,()->settings.character(c,mode.name(),id,base,p,AgentToolAttempt.require(runtime,"draft_character_profile")));
    }
}
