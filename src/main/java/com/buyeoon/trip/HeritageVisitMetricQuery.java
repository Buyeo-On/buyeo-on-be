package com.buyeoon.trip;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code HERITAGE_VISITED_COUNT}를 판정할 때 사용하는 trip 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class HeritageVisitMetricQuery {

	private final VisitRecordRepository visitRecords;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public HeritageVisitMetricQuery(VisitRecordRepository visitRecords) {
		this.visitRecords = visitRecords;
	}

	/** 회원이 전체 여행에서 방문한 고유 문화재 수를 센다. 같은 문화재를 다른 여행에서 다시 방문해도 다시 세지 않는다. */
	public long countUniquePlacesVisitedByMemberId(UUID memberId) {
		return visitRecords.countDistinctPlaceIdByMemberId(memberId);
	}
}
