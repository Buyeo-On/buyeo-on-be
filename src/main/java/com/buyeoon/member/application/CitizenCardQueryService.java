package com.buyeoon.member.application;

import com.buyeoon.common.storage.PublicImageUrlService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public class CitizenCardQueryService {

	private final JdbcOperations jdbcOperations;
	private final PublicImageUrlService imageUrls;

	public CitizenCardQueryService(JdbcOperations jdbcOperations, PublicImageUrlService imageUrls) {
		this.jdbcOperations = jdbcOperations;
		this.imageUrls = imageUrls;
	}

	public CitizenCardOptionsView getOptions() {
		List<CardOptionView> characters = jdbcOperations
				.query("SELECT id, name, image_key FROM card_characters ORDER BY sort_order", this::mapOption);
		List<CardOptionView> themes = jdbcOperations
				.query("SELECT id, name, image_key FROM card_themes ORDER BY sort_order", this::mapOption);
		return new CitizenCardOptionsView(characters, themes);
	}

	private CardOptionView mapOption(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CardOptionView(resultSet.getObject("id", UUID.class), resultSet.getString("name"),
				imageUrls.create(resultSet.getString("image_key")));
	}

	public record CitizenCardOptionsView(List<CardOptionView> characters, List<CardOptionView> themes) {
		public CitizenCardOptionsView {
			characters = List.copyOf(characters);
			themes = List.copyOf(themes);
		}
	}

	public record CardOptionView(UUID id, String name, String imageUrl) {
	}
}
