package com.buyeoon.trip;

import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<TripEntity, UUID> {

	Optional<TripEntity> findByMemberIdAndStatus(UUID memberId, TripStatus status);
}
