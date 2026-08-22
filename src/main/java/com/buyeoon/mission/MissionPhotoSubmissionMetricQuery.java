package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionPhotoSubmissionProjection;
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code PHOTO_SUBMISSION_COUNT}를 판정할 때 사용하는 mission 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class MissionPhotoSubmissionMetricQuery {

	private final MissionSubmissionRepository missionSubmissions;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring 싱글턴 빈을 그대로 주입받아 저장한다.")
	public MissionPhotoSubmissionMetricQuery(MissionSubmissionRepository missionSubmissions) {
		this.missionSubmissions = missionSubmissions;
	}

	/**
	 * 회원이 전체 여행에서 제출한 인증 사진 수와 가장 최근 제출 건의 여행·시각을 계산한다. 제출 건이 없으면 여행·시각은
	 * {@code null}이다.
	 */
	public MissionPhotoSubmissionMetricSnapshot snapshot(UUID memberId) {
		long count = missionSubmissions.countByMemberIdAndType(memberId, MissionType.PHOTO);
		if (count == 0) {
			return new MissionPhotoSubmissionMetricSnapshot(0, null, null);
		}
		List<MissionPhotoSubmissionProjection> latest = missionSubmissions
				.findByMemberIdAndTypeOrderBySubmittedAtDescTripIdAsc(memberId, MissionType.PHOTO,
						PageRequest.of(0, 1));
		MissionPhotoSubmissionProjection mostRecent = latest.getFirst();
		return new MissionPhotoSubmissionMetricSnapshot(count, mostRecent.tripId(), mostRecent.submittedAt());
	}
}
