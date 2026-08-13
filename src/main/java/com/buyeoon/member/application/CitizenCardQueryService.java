package com.buyeoon.member.application;

import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.member.application.CitizenCardCreationService.CitizenCardView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;

@Service
public class CitizenCardQueryService {
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");
	private static final String SIMULATION_NOTICE = "실제 상점에서의 사용은 제한됩니다.";

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

	public CitizenCardView getMyCard(UUID memberId) {
		StoredCitizenCard card = jdbcOperations.query("""
				SELECT card.id AS card_id,
				       profile.display_name,
				       character.id AS character_id,
				       character.name AS character_name,
				       character.image_key AS character_image_key,
				       theme.id AS theme_id,
				       theme.name AS theme_name,
				       theme.image_key AS theme_image_key,
				       card.issued_at
				FROM citizen_cards card
				JOIN member_profiles profile ON profile.member_id = card.member_id
				JOIN card_characters character ON character.id = profile.character_id
				JOIN card_themes theme ON theme.id = card.theme_id
				WHERE card.member_id = ?
				""", this::mapCitizenCard, memberId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
		return new CitizenCardView(card.cardId(), card.displayName(),
				new CardOptionView(card.characterId(), card.characterName(),
						imageUrls.create(card.characterImageKey())),
				new CardOptionView(card.themeId(), card.themeName(), imageUrls.create(card.themeImageKey())),
				card.issuedAt());
	}

	public BarcodeView getMyBarcode(UUID memberId) {
		CitizenCardView citizenCard = getMyCard(memberId);
		BarcodeState barcode = jdbcOperations.query("""
				SELECT card.barcode_value,
				       COALESCE(SUM(point.amount), 0) AS point_balance
				FROM citizen_cards card
				LEFT JOIN point_transactions point ON point.member_id = card.member_id
				WHERE card.member_id = ?
				GROUP BY card.barcode_value
				""", this::mapBarcode, memberId).stream().findFirst().orElseThrow(ResourceNotFoundException::new);
		return new BarcodeView(citizenCard, barcode.barcodeValue(), barcode.pointBalance(), true, SIMULATION_NOTICE);
	}

	private CardOptionView mapOption(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CardOptionView(resultSet.getObject("id", UUID.class), resultSet.getString("name"),
				imageUrls.create(resultSet.getString("image_key")));
	}

	private StoredCitizenCard mapCitizenCard(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StoredCitizenCard(resultSet.getObject("card_id", UUID.class), resultSet.getString("display_name"),
				resultSet.getObject("character_id", UUID.class), resultSet.getString("character_name"),
				resultSet.getString("character_image_key"), resultSet.getObject("theme_id", UUID.class),
				resultSet.getString("theme_name"), resultSet.getString("theme_image_key"),
				resultSet.getTimestamp("issued_at").toInstant().atZone(ASIA_SEOUL));
	}

	private BarcodeState mapBarcode(ResultSet resultSet, int rowNumber) throws SQLException {
		return new BarcodeState(resultSet.getString("barcode_value"), resultSet.getLong("point_balance"));
	}

	public record CitizenCardOptionsView(List<CardOptionView> characters, List<CardOptionView> themes) {
		public CitizenCardOptionsView {
			characters = List.copyOf(characters);
			themes = List.copyOf(themes);
		}
	}

	public record CardOptionView(UUID id, String name, String imageUrl) {
	}

	public record BarcodeView(CitizenCardView citizenCard, String barcodeValue, long pointBalance,
			boolean simulationOnly, String notice) {
	}

	private record StoredCitizenCard(UUID cardId, String displayName, UUID characterId, String characterName,
			String characterImageKey, UUID themeId, String themeName, String themeImageKey,
			java.time.ZonedDateTime issuedAt) {
	}

	private record BarcodeState(String barcodeValue, long pointBalance) {
	}
}
