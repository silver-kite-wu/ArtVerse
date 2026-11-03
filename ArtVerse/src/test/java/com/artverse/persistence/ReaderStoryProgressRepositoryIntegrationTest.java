package com.artverse.persistence;

import com.artverse.domain.Role;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against the local PostgreSQL configured by the test profile. */
@DataJpaTest(showSql = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReaderStoryProgressRepositoryIntegrationTest {
    @Autowired private ReaderStoryProgressRepository progress;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void concurrentFirstReadsCreateOneProgressRowAndNeverMoveItBackwards() throws Exception {
        var tx = new TransactionTemplate(transactionManager);
        Long[] ids = tx.execute(status -> {
            var user = new User();
            user.setUsername("reader_it_" + UUID.randomUUID().toString().replace("-", ""));
            user.setEmail(user.getUsername() + "@example.test");
            user.setPasswordHash("integration-test-only");
            user.setRole(Role.USER);
            entityManager.persist(user);
            var story = new Story();
            story.setTitle("reader progress integration fixture");
            story.setUser(user);
            entityManager.persist(story);
            entityManager.flush();
            return new Long[]{user.getId(), story.getId()};
        });
        try {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = callers.submit(() -> upsertAfterStart(ids, 7, ready, start));
                var second = callers.submit(() -> upsertAfterStart(ids, 3, ready, start));
                try {
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    start.countDown();
                }
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            }
            tx.executeWithoutResult(status -> {
                progress.upsertMaxChapter(ids[0], ids[1], 2);
                var saved = progress.findByReaderIdAndStoryId(ids[0], ids[1]).orElseThrow();
                assertThat(saved.getMaxChapterNumber()).isEqualTo(7);
                assertThat(saved.getUpdatedAt()).isNotNull();
                assertThat(entityManager.createQuery(
                        "select count(p) from ReaderStoryProgress p where p.reader.id=:reader and p.story.id=:story", Long.class)
                        .setParameter("reader", ids[0]).setParameter("story", ids[1]).getSingleResult()).isEqualTo(1);
            });
            tx.executeWithoutResult(status -> progress.upsertMaxChapter(ids[0], ids[1], 9));
            tx.executeWithoutResult(status -> assertThat(progress.findByReaderIdAndStoryId(ids[0], ids[1])
                    .orElseThrow().getMaxChapterNumber()).isEqualTo(9));
        } finally {
            tx.executeWithoutResult(status -> {
                entityManager.createQuery("delete from ReaderStoryProgress p where p.story.id=:story")
                        .setParameter("story", ids[1]).executeUpdate();
                entityManager.remove(entityManager.find(Story.class, ids[1]));
                entityManager.remove(entityManager.find(User.class, ids[0]));
            });
        }
    }

    private int upsertAfterStart(Long[] ids, int chapter, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent start timed out");
        return new TransactionTemplate(transactionManager)
                .execute(status -> progress.upsertMaxChapter(ids[0], ids[1], chapter));
    }
}
