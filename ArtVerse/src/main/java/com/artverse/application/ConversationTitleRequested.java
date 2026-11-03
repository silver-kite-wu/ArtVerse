package com.artverse.application;

/** Only immutable IDs and bounded input cross the transaction/worker boundary. */
public record ConversationTitleRequested(Long conversationId, Long userId, String input, String fallback) {}
