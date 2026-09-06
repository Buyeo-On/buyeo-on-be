package com.buyeoon.mission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mission_photo_upload_reservations")
public class MissionPhotoUploadReservationEntity {

	@Id
	@Column(name = "photo_id", nullable = false)
	private UUID photoId;

	@Column(name = "member_id")
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

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "presigned_expires_at", nullable = false, updatable = false)
	private Instant presignedExpiresAt;

	@Column(name = "cleanup_due_at", nullable = false, updatable = false)
	private Instant cleanupDueAt;
}
