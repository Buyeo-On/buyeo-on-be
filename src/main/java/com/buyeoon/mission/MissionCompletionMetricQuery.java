package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionParticipationEntity;
import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.repository.MissionParticipationRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code MISSION_COMPLETED_COUNT}를 판정할 때 사용하는 mission 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class MissionCompletionMetricQuery {

	private final MissionParticipationRepository missionParticipations;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionCompletionMetricQuery(MissionParticipationRepository missionParticipations) {
		this.missionParticipations = missionParticipations;
	}

	/**
	 * 회원이 전체 여행에서 완료한 mission participation 수와 가장 최근 완료 건의 여행·시각을 계산한다. 같은 mission을
	 * 다른 여행에서 다시 완료하면 다시 센다. 완료 건이 없으면 여행·시각은 {@code null}이다.
	 */
	public MissionCompletionMetricSnapshot snapshot(UUID memberId) {
		long count = missionParticipations.countByMemberIdAndStatus(memberId, MissionStatus.COMPLETED);
		if (count == 0) {
			return new MissionCompletionMetricSnapshot(0, null, null);
		}
		List<MissionParticipationEntity> latest = missionParticipations
				.findByMemberIdAndStatusOrderByCompletedAtDescTripIdAsc(memberId, MissionStatus.COMPLETED,
						PageRequest.of(0, 1));
		MissionParticipationEntity mostRecent = latest.getFirst();
		return new MissionCompletionMetricSnapshot(count, mostRecent.getTripId(), mostRecent.getCompletedAt());
	}
}
