package com.buyeoon.place.repository;

import com.buyeoon.place.entity.PlaceEntity;
import java.time.Instant;

public record SavedPlaceProjection(PlaceEntity place, Instant savedAt) {
}
