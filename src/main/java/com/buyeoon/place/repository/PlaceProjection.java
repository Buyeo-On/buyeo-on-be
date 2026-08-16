package com.buyeoon.place.repository;

import com.buyeoon.place.entity.PlaceEntity;

public record PlaceProjection(PlaceEntity place, double distanceMeters) {
}
