package com.buyeoon.trip;

import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<TripEntity, UUID> {

	Optional<TripEntity> findByMemberIdAndStatus(UUID memberId, TripStatus status);

	Optional<TripEntity> findByIdAndMemberId(UUID id, UUID memberId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM TripEntity t WHERE t.memberId = :memberId AND t.status = :status")
	Optional<TripEntity> lockByMemberIdAndStatus(@Param("memberId") UUID memberId, @Param("status") TripStatus status);
}
