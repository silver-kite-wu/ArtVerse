package com.artverse.application;

import com.artverse.ai.ConversationTitleClient;
import com.artverse.domain.AiConversationTitleSource;
import com.artverse.domain.AiConversationTitleState;
import com.artverse.domain.User;
import com.artverse.persistence.MangaAgentConversationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class ConversationTitleGenerator {
    private final Executor executor;
    private final ApiKeyService apiKeys;
    private final ConversationTitleClient client;
    private final MangaAgentConversationRepository conversations;
    private final TransactionTemplate writes;

    public ConversationTitleGenerator(@Qualifier("conversationTitleExecutor") Executor executor, ApiKeyService apiKeys,
                                      ConversationTitleClient client, MangaAgentConversationRepository conversations,
                                      PlatformTransactionManager transactions) {
        this.executor = executor;
        this.apiKeys = apiKeys;
        this.client = client;
        this.conversations = conversations;
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void requested(ConversationTitleRequested event) {
        try {
            executor.execute(() -> generate(event));
        } catch (RejectedExecutionException ignored) {
            finish(event, event.fallback(), AiConversationTitleSource.FALLBACK);
        }
    }

    void generate(ConversationTitleRequested event) {
        String title = event.fallback();
        AiConversationTitleSource source = AiConversationTitleSource.FALLBACK;
        try {
            User user = new User(); user.setId(event.userId());
            UserProviderConfig config = apiKeys.requireActiveUserProviderConfig(user, ApiKeyService.SLOT_LLM,
                    "A saved LLM profile is required for conversation titles");
            title = client.generate(config, event.input());
            source = AiConversationTitleSource.AI;
        } catch (Exception ignored) {
            // Provider failures never fail the conversation. Do not log secrets or user text.
        }
        finish(event, title, source);
    }

    private void finish(ConversationTitleRequested event, String title, AiConversationTitleSource source) {
        writes.executeWithoutResult(status -> conversations.finalizeGeneratedTitle(event.conversationId(), title,
                source, AiConversationTitleState.FINALIZED));
    }

    /** A restart or an interrupted task still leaves a useful fallback title. */
    @Scheduled(fixedDelay = 60000)
    public void recoverExpired() {
        writes.executeWithoutResult(status -> conversations.finalizeExpiredTitles(OffsetDateTime.now().minusMinutes(2)));
    }
}
