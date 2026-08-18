package com.buyeoon.place.repository;

import com.buyeoon.place.entity.PlaceEntity;
import java.time.Instant;

public record SavedPlaceProjection(PlaceEntity place, Instant savedAt, Double distanceMeters) {

	public SavedPlaceProjection(PlaceEntity place, Instant savedAt) {
		this(place, savedAt, null);
	}
}
