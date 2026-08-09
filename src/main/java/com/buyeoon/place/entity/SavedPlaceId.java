package com.buyeoon.place.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

@Embeddable
public record SavedPlaceId(
		@Column(name = "member_id", nullable = false) UUID memberId,
		@Column(name = "place_id", nullable = false) UUID placeId) {}
