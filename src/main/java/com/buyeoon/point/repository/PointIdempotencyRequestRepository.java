package com.buyeoon.point.repository;

import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PointIdempotencyRequestRepository
		extends
			JpaRepository<IdempotencyRequestEntity, IdempotencyRequestId> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<IdempotencyRequestEntity> findById(IdempotencyRequestId id);
}
