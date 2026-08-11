package com.buyeoon.member.entity;

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
@Table(name = "card_characters")
public class CardCharacterEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "image_key", nullable = false, columnDefinition = "text")
	private String imageKey;

	public static CardCharacterEntity create(String name, String imageKey) {
		CardCharacterEntity character = new CardCharacterEntity();
		character.name = name;
		character.imageKey = imageKey;
		return character;
	}
}
