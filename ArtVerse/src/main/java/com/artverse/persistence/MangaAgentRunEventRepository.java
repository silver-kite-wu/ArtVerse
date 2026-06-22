package com.artverse.persistence;

import com.artverse.domain.MangaAgentRunEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MangaAgentRunEventRepository extends JpaRepository<MangaAgentRunEventRecord, Long> {

    List<MangaAgentRunEventRecord> findByRunIdOrderByIdAsc(Long runId);
}
