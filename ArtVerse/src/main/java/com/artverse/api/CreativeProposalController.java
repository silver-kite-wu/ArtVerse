package com.artverse.api;

import com.artverse.application.*;
import com.artverse.application.imageproposal.ImageProposalService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/creative-proposals") @RequiredArgsConstructor
public class CreativeProposalController {
    private final CurrentUserService users;
    private final CreativeProposalArtifacts proposals;
    private final SettingsProposalService settings;
    private final ImageProposalService images;
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Decision(String decision,String commandHash) {}
    @GetMapping
    public Map<String,Object> list(@RequestParam(required=false) Long chapterId,@RequestParam(required=false) UUID conversationId,
            @RequestParam(required=false) UUID before,@RequestParam(defaultValue="20") int size) {
        return proposals.list(users.requireCurrentUser().getId(),chapterId,conversationId,before,size);
    }
    @PostMapping("/{id}/decision")
    public Map<String,Object> decide(@PathVariable UUID id,@RequestBody Decision input) {
        long actor=users.requireCurrentUser().getId();var row=proposals.owned(actor,id,false);
        return Set.of("SETTINGS_CHANGE","CHARACTER_CHANGE").contains(row.get("artifact_type"))
                ?settings.decide(actor,id,input.decision(),input.commandHash()):images.decide(actor,id,input.decision(),input.commandHash());
    }
}
