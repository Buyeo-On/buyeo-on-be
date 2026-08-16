package com.buyeoon.trip;

import com.buyeoon.member.entity.TermEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 여행 시작 서비스가 필수 약관 동의 여부를 확인하기 위한 trip 도메인 소유의 읽기 전용 리포지토리다. */
public interface TermRepository extends JpaRepository<TermEntity, UUID> {

	@Query("""
			SELECT count(currentTerm) = 0
			FROM TermEntity currentTerm
			WHERE currentTerm.required = true
			  AND currentTerm.effectiveAt = (
			      SELECT max(latest.effectiveAt) FROM TermEntity latest
			      WHERE latest.type = currentTerm.type AND latest.required = true
			        AND latest.effectiveAt <= function('clock_timestamp')
			  )
			  AND currentTerm.id NOT IN (
			      SELECT consent.id.termId FROM TermConsentEntity consent
			      WHERE consent.id.memberId = :memberId AND consent.agreed = true
			  )
			""")
	boolean hasAgreedToCurrentRequiredTerms(@Param("memberId") UUID memberId);
}
