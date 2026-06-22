package com.artverse.application;

import com.artverse.guard.GuardNonTerminalException;

public class AgentUserInputRequiredException extends RuntimeException implements GuardNonTerminalException {

    private final AgentUserInputRequest request;

    public AgentUserInputRequiredException(AgentUserInputRequest request) {
        super("Agent requires user input");
        this.request = request;
    }

    public AgentUserInputRequest request() {
        return request;
    }
}
