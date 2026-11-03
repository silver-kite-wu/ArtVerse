package com.artverse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;

@Entity @Table(name="scheduled_manga_scoped_permit")
@Getter @Setter
public class ScheduledMangaScopedPermit {
    @Id @Column(name="permit_key",length=240) private String permitKey;
    private String owner;
    @Column(name="lease_until") private OffsetDateTime leaseUntil;
}
