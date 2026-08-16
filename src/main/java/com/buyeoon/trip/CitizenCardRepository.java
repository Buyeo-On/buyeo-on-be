package com.buyeoon.trip;

import com.buyeoon.member.entity.CitizenCardEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 여행 시작 서비스가 군민증 발급 여부를 확인하기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface CitizenCardRepository extends JpaRepository<CitizenCardEntity, UUID> {

	boolean existsByMemberId(UUID memberId);
}
