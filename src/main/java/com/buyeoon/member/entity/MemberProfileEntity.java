package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "member_profiles")
public class MemberProfileEntity {

	@Id
	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "display_name", nullable = false, length = 8)
	private String displayName;

	@Column(name = "character_id", nullable = false)
	private UUID characterId;

	@UpdateTimestamp(source = SourceType.DB)
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public static MemberProfileEntity create(UUID memberId, String displayName, UUID characterId) {
		MemberProfileEntity profile = new MemberProfileEntity();
		profile.memberId = memberId;
		profile.displayName = displayName;
		profile.characterId = characterId;
		return profile;
	}
}
