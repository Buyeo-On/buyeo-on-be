package com.buyeoon.mission.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "mission_choices")
public class MissionChoiceEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "mission_id", nullable = false)
	private UUID missionId;

	@Column(name = "label", nullable = false, columnDefinition = "text")
	private String label;

	@Column(name = "correct", nullable = false)
	private boolean correct;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	public static MissionChoiceEntity create(
			UUID missionId, String label, boolean correct, int sortOrder) {
		MissionChoiceEntity choice = new MissionChoiceEntity();
		choice.missionId = missionId;
		choice.label = label;
		choice.correct = correct;
		choice.sortOrder = sortOrder;
		return choice;
	}
}
