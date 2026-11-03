package com.artverse.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity
@Table(name="scheduled_manga_worker_slot")
@Getter @Setter
public class ScheduledMangaWorkerSlot {
    @Id @Column(name="slot_id") private Integer slotId;
    private String owner;
    @Column(name="lease_until") private OffsetDateTime leaseUntil;
}
