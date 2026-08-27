package com.buyeoon.place.api;

public record PlaceAdminUpdateRequest(String category, String name, String summary, String description,
		String address, String imageKey, Double latitude, Double longitude, String operatingHoursRaw,
		Boolean alwaysOpen, String opensAt, String closesAt, Integer admissionFee) {
}
