package com.buyeoon.mission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "missions")
public class MissionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "place_id", nullable = false)
	private UUID placeId;

	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(name = "location", nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "type", nullable = false, columnDefinition = "mission_type")
	private MissionType type;

	@Column(name = "title", nullable = false, columnDefinition = "text")
	private String title;

	@Column(name = "description", nullable = false, columnDefinition = "text")
	private String description;

	@Column(name = "reward_points", nullable = false)
	private int rewardPoints;

	@Column(name = "max_attempts")
	private Integer maxAttempts;

	@Column(name = "ox_correct_answer")
	private Boolean oxCorrectAnswer;

	public static MissionEntity multipleChoice(UUID placeId, Point location, String title, String description,
			int rewardPoints, Integer maxAttempts) {
		return create(placeId, location, MissionType.MULTIPLE_CHOICE, title, description, rewardPoints, maxAttempts,
				null);
	}

	public static MissionEntity ox(UUID placeId, Point location, String title, String description, int rewardPoints,
			Integer maxAttempts, boolean correctAnswer) {
		return create(placeId, location, MissionType.OX, title, description, rewardPoints, maxAttempts,
				correctAnswer);
	}

	public static MissionEntity photo(UUID placeId, Point location, String title, String description,
			int rewardPoints) {
		return create(placeId, location, MissionType.PHOTO, title, description, rewardPoints, null, null);
	}

	private static MissionEntity create(UUID placeId, Point location, MissionType type, String title,
			String description, int rewardPoints, Integer maxAttempts, Boolean oxCorrectAnswer) {
		if (maxAttempts != null && maxAttempts <= 0) {
			throw new IllegalArgumentException("Maximum attempts must be positive");
		}
		location.setSRID(4326);
		MissionEntity mission = new MissionEntity();
		mission.placeId = placeId;
		mission.location = location;
		mission.type = type;
		mission.title = title;
		mission.description = description;
		mission.rewardPoints = rewardPoints;
		mission.maxAttempts = maxAttempts;
		mission.oxCorrectAnswer = oxCorrectAnswer;
		return mission;
	}
}
