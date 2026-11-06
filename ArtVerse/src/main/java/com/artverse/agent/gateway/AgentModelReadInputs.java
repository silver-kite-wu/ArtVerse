package com.artverse.agent.gateway;

import com.artverse.application.StoryboardArtifactService.ModelReadInput;
import com.artverse.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;

import java.util.ArrayList;
import java.util.List;

/** Reads only native result blocks from the detached model input, never text claiming a tool result. */
final class AgentModelReadInputs {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    static List<ModelReadInput> collect(List<Msg> messages) {
        return collect(messages, "get_chapter_context");
    }

    static List<ModelReadInput> collect(List<Msg> messages, String toolName) {
        var inputs = new ArrayList<ModelReadInput>();
        for (Msg message : messages) {
            if (message.getRole() != MsgRole.TOOL) continue;
            for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                if (!toolName.equals(result.getName()) || result.getState() != ToolResultState.SUCCESS) continue;
                var text = new StringBuilder();
                for (var block : result.getOutput()) {
                    if (!(block instanceof TextBlock part) || part.getText() == null || part.getText().length() > 256_000 - text.length())
                        throw invalid();
                    text.append(part.getText());
                }
                try {
                    var tree = JSON.readTree(text.toString());
                    if (tree == null || !tree.isObject()) throw invalid();
                    inputs.add(new ModelReadInput(result.getId(), JSON.convertValue(tree, new TypeReference<>() {})));
                } catch (java.io.IOException error) { throw invalid(); }
            }
        }
        return List.copyOf(inputs);
    }

    private static BusinessException invalid() {
        return new BusinessException(409, "READ_RECEIPT_UNAVAILABLE: frozen native result is incomplete or ambiguous");
    }
}
