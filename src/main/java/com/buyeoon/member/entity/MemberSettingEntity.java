package com.buyeoon.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "member_settings")
public class MemberSettingEntity {

	@Id
	@Column(name = "member_id", nullable = false)
	private UUID memberId;

	@Column(name = "nearby_quiz_notification_enabled", nullable = false)
	private boolean nearbyQuizNotificationEnabled;

	@Column(name = "dark_mode_enabled", nullable = false)
	private boolean darkModeEnabled;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	public static MemberSettingEntity create(UUID memberId) {
		MemberSettingEntity setting = new MemberSettingEntity();
		setting.memberId = memberId;
		return setting;
	}

	public void update(boolean nearbyQuizNotificationEnabled, boolean darkModeEnabled) {
		this.nearbyQuizNotificationEnabled = nearbyQuizNotificationEnabled;
		this.darkModeEnabled = darkModeEnabled;
	}
}
