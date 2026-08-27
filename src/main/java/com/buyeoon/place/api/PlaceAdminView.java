package com.buyeoon.place.api;

import com.buyeoon.place.entity.PlaceCategory;
import java.time.LocalTime;
import java.util.UUID;

public record PlaceAdminView(UUID placeId, PlaceCategory category, String name, String summary, String description,
		String address, String imageKey, double latitude, double longitude, String operatingHoursRaw,
		boolean alwaysOpen, LocalTime opensAt, LocalTime closesAt, Integer admissionFee, boolean deleted) {
}
