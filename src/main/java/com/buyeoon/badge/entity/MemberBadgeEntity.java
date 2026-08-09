package com.buyeoon.badge.entity;

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
@Table(name = "member_badges")
public class MemberBadgeEntity {

	@EmbeddedId
	private MemberBadgeId id;

	@Column(name = "trip_id", nullable = false)
	private UUID tripId;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "earned_at", nullable = false, updatable = false)
	private Instant earnedAt;

	public static MemberBadgeEntity create(UUID memberId, UUID badgeId, UUID tripId) {
		MemberBadgeEntity memberBadge = new MemberBadgeEntity();
		memberBadge.id = new MemberBadgeId(memberId, badgeId);
		memberBadge.tripId = tripId;
		return memberBadge;
	}
}
