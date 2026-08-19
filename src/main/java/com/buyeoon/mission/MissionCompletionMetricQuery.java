package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionStatus;
import com.buyeoon.mission.repository.MissionParticipationRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.UUID;
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
	 * 회원이 전체 여행에서 완료한 mission participation 수를 센다. 같은 mission을 다른 여행에서 다시 완료하면 다시
	 * 센다.
	 */
	public long countCompletedByMemberId(UUID memberId) {
		return missionParticipations.countByMemberIdAndStatus(memberId, MissionStatus.COMPLETED);
	}
}
