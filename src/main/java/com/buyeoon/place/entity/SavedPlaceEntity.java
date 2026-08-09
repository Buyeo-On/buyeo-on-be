package com.buyeoon.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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
@Table(name = "saved_places")
public class SavedPlaceEntity {

	@EmbeddedId
	private SavedPlaceId id;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "saved_at", nullable = false, updatable = false)
	private Instant savedAt;

	public static SavedPlaceEntity create(UUID memberId, UUID placeId) {
		SavedPlaceEntity savedPlace = new SavedPlaceEntity();
		savedPlace.id = new SavedPlaceId(memberId, placeId);
		return savedPlace;
	}
}
