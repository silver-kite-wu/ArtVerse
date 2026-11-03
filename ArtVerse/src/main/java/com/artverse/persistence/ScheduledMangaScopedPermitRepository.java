package com.artverse.persistence;

import com.artverse.domain.ScheduledMangaScopedPermit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ScheduledMangaScopedPermitRepository extends JpaRepository<ScheduledMangaScopedPermit,String> {
    @Transactional @Modifying
    @Query(value="INSERT INTO scheduled_manga_scoped_permit(permit_key,owner,lease_until) VALUES (:key,:owner,:until) ON CONFLICT (permit_key) DO UPDATE SET owner=EXCLUDED.owner,lease_until=EXCLUDED.lease_until WHERE scheduled_manga_scoped_permit.lease_until IS NULL OR scheduled_manga_scoped_permit.lease_until<:now",nativeQuery=true)
    int acquire(@Param("key")String key,@Param("owner")String owner,@Param("until")java.time.OffsetDateTime until,@Param("now")java.time.OffsetDateTime now);
    @Transactional @Modifying
    @Query(value="UPDATE scheduled_manga_scoped_permit SET owner=NULL,lease_until=NULL WHERE permit_key=:key AND owner=:owner",nativeQuery=true)
    int release(@Param("key")String key,@Param("owner")String owner);
}
