package com.buyeoon.point.repository;

import com.buyeoon.member.entity.MemberEntity;
import com.buyeoon.member.entity.MemberStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/** 이월 포인트 만료 처리가 회원 행을 잠그기 위한 point 도메인 소유의 읽기 전용 리포지토리다. */
public interface PointMemberRepository extends JpaRepository<MemberEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<MemberEntity> findByIdAndStatus(UUID id, MemberStatus status);
}
