package com.buyeoon.trip;

import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<TripEntity, UUID> {

	Optional<TripEntity> findByMemberIdAndStatus(UUID memberId, TripStatus status);

	/** 회원이 진행 중이거나 종료 후 미정산인 여행을 조회한다. 이 두 상태는 동시에 최대 하나만 존재한다. */
	Optional<TripEntity> findByMemberIdAndStatusIn(UUID memberId, Collection<TripStatus> statuses);

	/** 회원이 여행 시작을 막는 상태의 여행을 소유하는지 확인한다. */
	boolean existsByMemberIdAndStatusIn(UUID memberId, Collection<TripStatus> statuses);

	Optional<TripEntity> findByIdAndMemberId(UUID id, UUID memberId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM TripEntity t WHERE t.id = :id AND t.memberId = :memberId")
	Optional<TripEntity> lockByIdAndMemberId(@Param("id") UUID id, @Param("memberId") UUID memberId);
}
