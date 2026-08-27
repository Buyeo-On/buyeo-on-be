package com.buyeoon.badge.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "badges")
public class BadgeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name = "category", nullable = false, columnDefinition = "badge_category")
	private BadgeCategory category;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "description", nullable = false, columnDefinition = "text")
	private String description;

	@Column(name = "image_key", columnDefinition = "text")
	private String imageKey;

	@Column(name = "condition_text", nullable = false, columnDefinition = "text")
	private String conditionText;

	@Column(name = "retired_at")
	private Instant retiredAt;

	public static BadgeEntity create(BadgeCategory category, String name, String description, String imageKey,
			String conditionText) {
		BadgeEntity badge = new BadgeEntity();
		badge.category = category;
		badge.name = name;
		badge.description = description;
		badge.imageKey = imageKey;
		badge.conditionText = conditionText;
		return badge;
	}

	public void updateMetadata(BadgeCategory category, String name, String description, String imageKey,
			String conditionText) {
		this.category = category;
		this.name = name;
		this.description = description;
		this.imageKey = imageKey;
		this.conditionText = conditionText;
	}

	public void retire() {
		this.retiredAt = Instant.now();
	}

	public void activate() {
		this.retiredAt = null;
	}
}
