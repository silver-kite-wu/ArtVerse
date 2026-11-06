package com.artverse.application;

import com.artverse.common.BusinessException;
import com.artverse.domain.MangaAgentRun;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
public class AgentRunLeaseService {

    private static final String INSTANCE_ID = "artverse-" + UUID.randomUUID();
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    public AgentRunLeaseService(JdbcTemplate jdbcTemplate, EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    public MangaAgentRun claim(MangaAgentRun run) {
        int updated = jdbcTemplate.update("""
                UPDATE manga_agent_runs
                SET owner_instance_id = ?, lease_until = clock_timestamp() + interval '90 seconds',
                    fencing_token = fencing_token + 1, updated_at = clock_timestamp()
                WHERE id = ?
                  AND status IN ('RUNNING', 'WAITING_USER')
                  AND (owner_instance_id IS NULL OR lease_until IS NULL OR lease_until <= clock_timestamp())
                """, INSTANCE_ID, run.getId());
        if (updated == 0) {
            throw new BusinessException(409, "Agent run is active on another worker");
        }
        entityManager.refresh(run);
        return run;
    }

    @Scheduled(fixedDelay = 30_000)
    public void renewOwnedRuns() {
        int renewed = jdbcTemplate.update("""
                UPDATE manga_agent_runs
                SET lease_until = clock_timestamp() + interval '90 seconds'
                WHERE owner_instance_id = ? AND status = 'RUNNING' AND lease_until > clock_timestamp()
                """, INSTANCE_ID);
        if (renewed > 0) log.debug("Renewed {} agent run leases", renewed);
    }

    public boolean owns(MangaAgentRun run, Long fencingToken) {
        return run != null && run.getId() != null
                && INSTANCE_ID.equals(run.getOwnerInstanceId())
                && fencingToken != null
                && fencingToken.equals(run.getFencingToken())
                && run.getLeaseUntil() != null
                && Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT owner_instance_id = ? AND fencing_token = ? AND lease_until > clock_timestamp()
                    FROM manga_agent_runs WHERE id = ?
                    """, Boolean.class, INSTANCE_ID, fencingToken, run.getId()));
    }

    public boolean hasActiveLease(MangaAgentRun run) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT owner_instance_id IS NOT NULL AND lease_until > clock_timestamp()
                FROM manga_agent_runs WHERE id = ?
                """, Boolean.class, run.getId()));
    }
}
