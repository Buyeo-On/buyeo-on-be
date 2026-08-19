package com.buyeoon.trip;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
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

	/**
	 * 회원이 전체 여행에서 방문한 고유 문화재 수와, 고유 문화재 수에 기여한 각 문화재의 최초 방문 중 가장 최근 방문의 여행·시각을
	 * 계산한다. 같은 문화재를 다른 여행에서 다시 방문해도 다시 세지 않는다. 방문 기록이 없으면 여행·시각은 {@code null}이다.
	 */
	public HeritageVisitMetricSnapshot snapshot(UUID memberId) {
		long count = visitRecords.countDistinctPlaceIdByMemberId(memberId);
		if (count == 0) {
			return new HeritageVisitMetricSnapshot(0, null, null);
		}
		List<Object[]> rows = visitRecords.findLatestContributingFirstVisitRows(memberId);
		Object[] row = rows.getFirst();
		return new HeritageVisitMetricSnapshot(count, (UUID) row[0], (Instant) row[1]);
	}
}
