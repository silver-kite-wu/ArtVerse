package com.artverse.persistence;

import com.artverse.domain.ScheduledMangaWorkerSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.OffsetDateTime;
import java.util.List;

public interface ScheduledMangaWorkerSlotRepository extends JpaRepository<ScheduledMangaWorkerSlot,Integer> {
    @Query(value="select slot_id from scheduled_manga_worker_slot where lease_until is null or lease_until < :now order by slot_id limit 1 for update skip locked",nativeQuery=true)
    List<Integer> lockAvailable(OffsetDateTime now);
}
