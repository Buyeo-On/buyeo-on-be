package com.buyeoon.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "location_usage_records")
public class LocationUsageRecordEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, length = 64)
	private LocationUsagePurpose purpose;

	@Column(name = "used_at", nullable = false)
	private Instant usedAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
}
