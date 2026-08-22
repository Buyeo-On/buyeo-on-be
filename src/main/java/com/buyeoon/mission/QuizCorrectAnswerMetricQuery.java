package com.buyeoon.mission;

import com.buyeoon.mission.entity.MissionType;
import com.buyeoon.mission.repository.MissionSubmissionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * badge 도메인이 {@code QUIZ_CORRECT_COUNT}를 판정할 때 사용하는 mission 도메인의 공개 query
 * seam이다(ADR-003).
 */
@Service
@Transactional(readOnly = true)
public class QuizCorrectAnswerMetricQuery {

	private static final List<MissionType> QUIZ_TYPES = List.of(MissionType.MULTIPLE_CHOICE, MissionType.OX);

	private final MissionSubmissionRepository missionSubmissions;

	public QuizCorrectAnswerMetricQuery(MissionSubmissionRepository missionSubmissions) {
		this.missionSubmissions = missionSubmissions;
	}

	/**
	 * 회원이 전체 여행에서 정답으로 제출한 객관식·OX 퀴즈 수와 가장 최근 정답 제출의 여행·시각을 계산한다. 사진 인증 제출은 포함하지
	 * 않는다. 정답 제출이 없으면 여행·시각은 {@code null}이다.
	 */
	public QuizCorrectAnswerMetricSnapshot snapshot(UUID memberId) {
		long count = missionSubmissions.countByMemberIdAndTypeInAndCorrectTrue(memberId, QUIZ_TYPES);
		if (count == 0) {
			return new QuizCorrectAnswerMetricSnapshot(0, null, null);
		}
		List<Object[]> rows = missionSubmissions.findLatestCorrectQuizContributionRows(memberId, QUIZ_TYPES,
				PageRequest.of(0, 1));
		Object[] row = rows.getFirst();
		return new QuizCorrectAnswerMetricSnapshot(count, (UUID) row[0], (Instant) row[1]);
	}
}
