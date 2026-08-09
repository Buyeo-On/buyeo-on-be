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
import org.hibernate.annotations.SourceType;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mission_photos")
public class MissionPhotoEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@Column(name = "mission_id", nullable = false)
	private UUID missionId;

	@Column(name = "object_key", nullable = false, unique = true, columnDefinition = "text")
	private String objectKey;

	@Column(name = "content_type", nullable = false, columnDefinition = "text")
	private String contentType;

	@Column(name = "file_size_bytes", nullable = false)
	private long fileSizeBytes;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "uploaded_at", nullable = false, updatable = false)
	private Instant uploadedAt;

	public static MissionPhotoEntity create(
			UUID memberId,
			UUID tripId,
			UUID missionId,
			String objectKey,
			String contentType,
			long fileSizeBytes) {
		MissionPhotoEntity photo = new MissionPhotoEntity();
		photo.memberId = memberId;
		photo.tripId = tripId;
		photo.missionId = missionId;
		photo.objectKey = objectKey;
		photo.contentType = contentType;
		photo.fileSizeBytes = fileSizeBytes;
		return photo;
	}
}
