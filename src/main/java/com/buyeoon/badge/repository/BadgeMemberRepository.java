package com.buyeoon.badge.repository;

import com.buyeoon.member.entity.MemberEntity;
import com.buyeoon.member.entity.MemberStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reconciliation이 회원 행을 잠그고 대상 회원을 열거하기 위한 badge 도메인 소유의 리포지토리다. */
public interface BadgeMemberRepository extends JpaRepository<MemberEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<MemberEntity> findByIdAndStatus(UUID id, MemberStatus status);

	/** Reconciliation 대상 회원을 ID 오름차순으로 페이지 단위로 열거한다. */
	@Query("SELECT m.id FROM MemberEntity m WHERE m.status = :status ORDER BY m.id ASC")
	List<UUID> findIdsByStatus(@Param("status") MemberStatus status, Pageable pageable);
}
