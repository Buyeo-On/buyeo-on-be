package com.buyeoon.mission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mission_submissions")
public class MissionSubmissionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "participation_id", nullable = false)
	private UUID participationId;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "type", nullable = false, columnDefinition = "mission_type")
	private MissionType type;

	@Column(name = "choice_id")
	private UUID choiceId;

	@Column(name = "ox_answer")
	private Boolean oxAnswer;

	@Column(name = "photo_id")
	private UUID photoId;

	@Column(name = "correct", nullable = false)
	private boolean correct;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "submitted_at", nullable = false, updatable = false)
	private Instant submittedAt;

	public static MissionSubmissionEntity multipleChoice(
			UUID participationId, UUID choiceId, boolean correct) {
		MissionSubmissionEntity submission = base(participationId, MissionType.MULTIPLE_CHOICE, correct);
		submission.choiceId = choiceId;
		return submission;
	}

	public static MissionSubmissionEntity ox(
			UUID participationId, boolean oxAnswer, boolean correct) {
		MissionSubmissionEntity submission = base(participationId, MissionType.OX, correct);
		submission.oxAnswer = oxAnswer;
		return submission;
	}

	public static MissionSubmissionEntity photo(UUID participationId, UUID photoId) {
		MissionSubmissionEntity submission = base(participationId, MissionType.PHOTO, true);
		submission.photoId = photoId;
		return submission;
	}

	private static MissionSubmissionEntity base(
			UUID participationId, MissionType type, boolean correct) {
		MissionSubmissionEntity submission = new MissionSubmissionEntity();
		submission.participationId = participationId;
		submission.type = type;
		submission.correct = correct;
		return submission;
	}
}
