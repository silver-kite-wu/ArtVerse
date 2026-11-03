package com.artverse.application;

import com.artverse.domain.ScheduledMangaWorkerSlot;
import com.artverse.persistence.ScheduledMangaWorkerSlotRepository;
import com.artverse.persistence.ScheduledMangaScopedPermitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduledMangaWorkerSlotService {
    private final ScheduledMangaWorkerSlotRepository slots;
    private final ScheduledMangaScopedPermitRepository scopedPermits;

    @Transactional
    public Optional<Integer> acquire(String owner) {
        return slots.lockAvailable(OffsetDateTime.now()).stream().findFirst().map(id -> {
            ScheduledMangaWorkerSlot slot=slots.findById(id).orElseThrow();
            slot.setOwner(owner); slot.setLeaseUntil(OffsetDateTime.now().plusMinutes(15));
            slots.save(slot);
            return id;
        });
    }

    @Transactional
    public void release(Integer slotId,String owner) {
        slots.findById(slotId).filter(slot->owner.equals(slot.getOwner())).ifPresent(slot->{slot.setOwner(null);slot.setLeaseUntil(null);slots.save(slot);});
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean acquireScoped(String key,String owner){
        var now=OffsetDateTime.now();
        return scopedPermits.acquire(key,owner,now.plusMinutes(15),now)==1;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void releaseScoped(String key,String owner){scopedPermits.release(key,owner);}

    public static String newOwner(UUID taskId) { return taskId + ":" + UUID.randomUUID(); }
}
