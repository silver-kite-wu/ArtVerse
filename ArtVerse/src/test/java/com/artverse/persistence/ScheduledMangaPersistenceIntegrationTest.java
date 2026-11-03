package com.artverse.persistence;

import com.artverse.domain.Chapter;
import com.artverse.domain.Role;
import com.artverse.domain.ScheduledMangaGeneration;
import com.artverse.domain.Story;
import com.artverse.domain.User;
import com.artverse.application.ScheduledMangaWorkerSlotService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/manga_novel?stringtype=unspecified",
        "artverse.scheduled-manga.poll-interval-ms=3600000"
})
@EnabledIf(value = "com.artverse.persistence.ScheduledMangaPersistenceIntegrationTest#localPostgresAvailable",
        disabledReason = "本地 PostgreSQL（docker-compose）未运行，跳过预约任务仓储集成测试")
class ScheduledMangaPersistenceIntegrationTest {
    @Autowired private EntityManager entityManager;
    @Autowired private ScheduledMangaGenerationRepository tasks;
    @Autowired private ScheduledMangaWorkerSlotService workerSlots;
    @Autowired private PlatformTransactionManager transactionManager;

    static boolean localPostgresAvailable() {
        try {
            return DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/manga_novel?connectTimeout=2", "postgres", "postgres") != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void persistedTaskCanOnlyBeClaimedByOneOwnerUntilItsLeaseExpires() throws Exception {
        UUID id=UUID.randomUUID();
        String key="scheduled-it:"+id;
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        Long[] fixtureIds=tx.execute(status->{
            User user=new User();
            user.setUsername("scheduled_it_"+UUID.randomUUID().toString().replace("-",""));
            user.setEmail(user.getUsername()+"@example.test");
            user.setPasswordHash("integration-test-only");
            user.setRole(Role.USER);
            entityManager.persist(user);
            Story story=new Story(); story.setTitle("scheduled integration story"); story.setUser(user);
            entityManager.persist(story);
            Chapter chapter=new Chapter(); chapter.setStory(story); chapter.setChapterNumber(1); chapter.setImageCount(4);
            entityManager.persist(chapter);
            entityManager.flush();
            return new Long[]{user.getId(),story.getId(),chapter.getId(),chapter.getVersion()};
        });
        Long userId=fixtureIds[0],storyId=fixtureIds[1],chapterId=fixtureIds[2],chapterVersion=fixtureIds[3];
        try {
            OffsetDateTime due=OffsetDateTime.now().minusSeconds(1);
            assertThat(tasks.insertIfAbsent(id,userId,chapterId,due,"UTC","SCHEDULED",chapterVersion,
                    null,"test-model","[]","{}","0".repeat(64),"test-prompt-v1",key)).isEqualTo(1);

            OffsetDateTime firstLeaseEnd=OffsetDateTime.now().plusMinutes(1);
            OffsetDateTime claimTime=OffsetDateTime.now();
            var ready=new CountDownLatch(2);
            var start=new CountDownLatch(1);
            try(var callers=Executors.newVirtualThreadPerTaskExecutor()) {
                var claimA=callers.submit(()->claimAt(id,"worker-a",firstLeaseEnd,claimTime,ready,start));
                var claimB=callers.submit(()->claimAt(id,"worker-b",firstLeaseEnd,claimTime,ready,start));
                ready.await(); start.countDown();
                assertThat(claimA.get()+claimB.get()).isEqualTo(1);
            }
            assertThat(tasks.claim(id,"worker-c",OffsetDateTime.now().plusMinutes(1),firstLeaseEnd.plusSeconds(1))).isEqualTo(1);

            ScheduledMangaGeneration claimed=tasks.findById(id).orElseThrow();
            assertThat(claimed.getState()).isEqualTo(ScheduledMangaGeneration.State.RUNNING);
            assertThat(claimed.getLeaseOwner()).isEqualTo("worker-c");
            assertThat(claimed.getFencingToken()).isEqualTo(2L);
        } finally {
            tx.executeWithoutResult(status->{
                entityManager.createQuery("delete from ScheduledMangaGeneration t where t.id=:id").setParameter("id",id).executeUpdate();
                entityManager.remove(entityManager.find(Chapter.class,chapterId));
                entityManager.remove(entityManager.find(Story.class,storyId));
                entityManager.remove(entityManager.find(User.class,userId));
            });
        }
    }

    private int claimAt(UUID taskId,String owner,OffsetDateTime leaseEnd,OffsetDateTime now,
                        CountDownLatch ready,CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return tasks.claim(taskId,owner,leaseEnd,now);
    }

    @Test
    void sharedWorkerSlotsAndScopedPermitsEnforceDatabaseConcurrencyLimits() {
        String owner1="slot-it:"+UUID.randomUUID();
        String owner2="slot-it:"+UUID.randomUUID();
        String owner3="slot-it:"+UUID.randomUUID();
        Integer slot1=null,slot2=null;
        try {
            slot1=workerSlots.acquire(owner1).orElseThrow();
            slot2=workerSlots.acquire(owner2).orElseThrow();
            assertThat(workerSlots.acquire(owner3)).isEmpty();
        } finally {
            if(slot1!=null)workerSlots.release(slot1,owner1);
            if(slot2!=null)workerSlots.release(slot2,owner2);
        }

        String permit="permit-it:"+UUID.randomUUID();
        try {
            assertThat(workerSlots.acquireScoped(permit,owner1)).isTrue();
            assertThat(workerSlots.acquireScoped(permit,owner2)).isFalse();
            workerSlots.releaseScoped(permit,owner1);
            assertThat(workerSlots.acquireScoped(permit,owner2)).isTrue();
        } finally {
            workerSlots.releaseScoped(permit,owner1);
            workerSlots.releaseScoped(permit,owner2);
        }
    }
}
