package com.buyeoon.member.entity;

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
@Table(name = "citizen_cards")
public class CitizenCardEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "member_id", nullable = false, unique = true)
	private UUID memberId;

	@Column(name = "theme_id", nullable = false)
	private UUID themeId;

	@Column(name = "barcode_value", nullable = false, unique = true, columnDefinition = "text")
	private String barcodeValue;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	public static CitizenCardEntity create(UUID memberId, UUID themeId, String barcodeValue) {
		CitizenCardEntity citizenCard = new CitizenCardEntity();
		citizenCard.memberId = memberId;
		citizenCard.themeId = themeId;
		citizenCard.barcodeValue = barcodeValue;
		return citizenCard;
	}
}
