package com.buyeoon.trip;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 다른 도메인이 미션 완료 시 문화재 방문 기록을 남길 때 사용하는 trip 도메인의 공개 seam이다. */
@Service
public class VisitRecordService {

	private final VisitRecordRepository visitRecords;
	private final ApplicationEventPublisher events;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public VisitRecordService(VisitRecordRepository visitRecords, ApplicationEventPublisher events) {
		this.visitRecords = visitRecords;
		this.events = events;
	}

	/** 같은 여행에 같은 문화재의 방문 기록이 없을 때만 새로 생성하고 {@link VisitRecorded}를 발행한다. */
	public VisitRecordResult recordIfAbsent(UUID memberId, UUID tripId, UUID missionId, UUID placeId,
			Instant occurredAt) {
		Optional<UUID> visitId = visitRecords.insertIfAbsent(tripId, missionId, placeId);
		visitId.ifPresent(id -> events.publishEvent(new VisitRecorded(memberId, tripId, id, occurredAt)));
		return new VisitRecordResult(visitId.isPresent(), visitId.orElse(null));
	}

	public record VisitRecordResult(boolean recorded, UUID visitId) {
	}
}
