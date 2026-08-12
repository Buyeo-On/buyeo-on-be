package com.buyeoon.member.application;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.common.storage.PublicImageUrlService;
import com.buyeoon.member.api.InvalidCitizenCardRequestException;
import com.buyeoon.member.application.CitizenCardQueryService.CardOptionView;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;

@Service
public final class CitizenCardCreationService implements CitizenCardCreator {

	private static final String OPERATION = "CREATE_CITIZEN_CARD";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final BuyeoBoundary boundary;
	private final PublicImageUrlService imageUrls;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	public CitizenCardCreationService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			BuyeoBoundary boundary, PublicImageUrlService imageUrls, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.boundary = boundary;
		this.imageUrls = imageUrls;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	@Override
	public CitizenCardView create(UUID memberId, String idempotencyKey, CitizenCardCommand command) {
		validateIdempotencyKey(idempotencyKey);
		if (!boundary.covers(command.location().latitude(), command.location().longitude())) {
			throw new OutsideBuyeoException();
		}
		String requestHash = hash(command);
		return Objects.requireNonNull(
				transactions.execute(status -> createInTransaction(memberId, idempotencyKey, requestHash, command)),
				"군민증 생성 트랜잭션 결과가 없습니다.");
	}

	private CitizenCardView createInTransaction(UUID memberId, String idempotencyKey, String requestHash,
			CitizenCardCommand command) {
		lockMember(memberId);
		Instant issuedAt = Objects
				.requireNonNull(jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant();
		IdempotencyState existingRequest = findIdempotencyRequest(memberId, idempotencyKey);
		if (existingRequest != null) {
			if (!existingRequest.expiresAt().isAfter(issuedAt)) {
				deleteIdempotencyRequest(memberId, idempotencyKey);
			} else {
				return replay(existingRequest, requestHash);
			}
		}

		if (!hasAgreedToCurrentRequiredTerms(memberId)) {
			throw new RequiredTermsNotAgreedException();
		}
		if (hasProfileOrCitizenCard(memberId)) {
			throw new InvalidStateTransitionException();
		}

		CatalogOption character = findCharacter(command.characterId());
		CatalogOption theme = findTheme(command.themeId());
		if (character == null || theme == null) {
			throw new InvalidCitizenCardRequestException();
		}

		UUID cardId = UUID.randomUUID();
		String barcodeValue = UUID.randomUUID().toString();
		Timestamp timestamp = Timestamp.from(issuedAt);
		jdbcOperations.update("""
				INSERT INTO member_profiles (member_id, display_name, character_id, updated_at)
				VALUES (?, ?, ?, ?)
				""", memberId, command.displayName(), command.characterId(), timestamp);
		jdbcOperations.update("""
				INSERT INTO citizen_cards (id, member_id, theme_id, barcode_value, issued_at)
				VALUES (?, ?, ?, ?, ?)
				""", cardId, memberId, command.themeId(), barcodeValue, timestamp);

		CitizenCardView result = new CitizenCardView(cardId, command.displayName(), toView(character), toView(theme),
				issuedAt.atZone(ASIA_SEOUL));
		String responseBody = writeResponse(result);
		jdbcOperations.update("""
				INSERT INTO idempotency_requests
				    (member_id, idempotency_key, operation, request_hash, response_status, response_body,
				     created_at, expires_at)
				VALUES (?, ?, ?, ?, 201, ?::jsonb, ?, ?)
				""", memberId, idempotencyKey, OPERATION, requestHash, responseBody, timestamp,
				Timestamp.from(issuedAt.plus(RETENTION)));
		return result;
	}

	private CitizenCardView replay(IdempotencyState existingRequest, String requestHash) {
		if (!OPERATION.equals(existingRequest.operation()) || !requestHash.equals(existingRequest.requestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		if (!Integer.valueOf(201).equals(existingRequest.responseStatus()) || existingRequest.responseBody() == null) {
			throw new IllegalStateException("완료되지 않은 멱등성 요청이 남아 있습니다.");
		}
		return readResponse(existingRequest.responseBody());
	}

	private void lockMember(UUID memberId) {
		jdbcOperations.queryForObject("SELECT id FROM members WHERE id = ? AND status = 'ACTIVE' FOR UPDATE",
				UUID.class, memberId);
	}

	private boolean hasAgreedToCurrentRequiredTerms(UUID memberId) {
		Boolean agreed = jdbcOperations.queryForObject("""
				WITH current_required_terms AS (
				    SELECT DISTINCT ON (term.type) term.id
				    FROM terms term
				    WHERE term.required = true
				      AND term.effective_at <= clock_timestamp()
				    ORDER BY term.type, term.effective_at DESC
				)
				SELECT EXISTS (SELECT 1 FROM current_required_terms)
				   AND NOT EXISTS (
				       SELECT 1
				       FROM current_required_terms current_term
				       LEFT JOIN term_consents consent
				         ON consent.term_id = current_term.id
				        AND consent.member_id = ?
				       WHERE COALESCE(consent.agreed, false) = false
				   )
				""", Boolean.class, memberId);
		return Boolean.TRUE.equals(agreed);
	}

	private boolean hasProfileOrCitizenCard(UUID memberId) {
		Boolean exists = jdbcOperations.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM member_profiles WHERE member_id = ?)
				    OR EXISTS (SELECT 1 FROM citizen_cards WHERE member_id = ?)
				""", Boolean.class, memberId, memberId);
		return Boolean.TRUE.equals(exists);
	}

	private CatalogOption findCharacter(UUID id) {
		return jdbcOperations.query("SELECT id, name, image_key FROM card_characters WHERE id = ?", this::mapOption, id)
				.stream().findFirst().orElse(null);
	}

	private CatalogOption findTheme(UUID id) {
		return jdbcOperations.query("SELECT id, name, image_key FROM card_themes WHERE id = ?", this::mapOption, id)
				.stream().findFirst().orElse(null);
	}

	private CatalogOption mapOption(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CatalogOption(resultSet.getObject("id", UUID.class), resultSet.getString("name"),
				resultSet.getString("image_key"));
	}

	private CardOptionView toView(CatalogOption option) {
		return new CardOptionView(option.id(), option.name(), imageUrls.create(option.imageKey()));
	}

	private IdempotencyState findIdempotencyRequest(UUID memberId, String idempotencyKey) {
		return jdbcOperations.query("""
				SELECT operation, request_hash, response_status, response_body::text, expires_at
				FROM idempotency_requests
				WHERE member_id = ? AND idempotency_key = ?
				FOR UPDATE
				""", this::mapIdempotencyState, memberId, idempotencyKey).stream().findFirst().orElse(null);
	}

	private IdempotencyState mapIdempotencyState(ResultSet resultSet, int rowNumber) throws SQLException {
		return new IdempotencyState(resultSet.getString("operation"), resultSet.getString("request_hash"),
				resultSet.getObject("response_status", Integer.class), resultSet.getString("response_body"),
				resultSet.getTimestamp("expires_at").toInstant());
	}

	private void deleteIdempotencyRequest(UUID memberId, String idempotencyKey) {
		jdbcOperations.update("DELETE FROM idempotency_requests WHERE member_id = ? AND idempotency_key = ?", memberId,
				idempotencyKey);
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new InvalidCitizenCardRequestException();
		}
	}

	private String hash(CitizenCardCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, command.displayName());
			update(digest, command.characterId().toString());
			update(digest, command.themeId().toString());
			update(digest, Double.toHexString(command.location().latitude()));
			update(digest, Double.toHexString(command.location().longitude()));
			update(digest,
					command.location().accuracyMeters() == null
							? "null"
							: Double.toHexString(command.location().accuracyMeters()));
			update(digest, command.location().capturedAt().toInstant().toString());
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	private void update(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private String writeResponse(CitizenCardView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("군민증 응답을 저장할 수 없습니다.", exception);
		}
	}

	private CitizenCardView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new CitizenCardView(UUID.fromString(requiredText(data, "cardId")), requiredText(data, "displayName"),
					readOption(required(data, "character")), readOption(required(data, "theme")),
					ZonedDateTime.parse(requiredText(data, "issuedAt")));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 군민증 응답을 읽을 수 없습니다.", exception);
		}
	}

	private CardOptionView readOption(JsonNode option) {
		return new CardOptionView(UUID.fromString(requiredText(option, "id")), requiredText(option, "name"),
				requiredText(option, "imageUrl"));
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 군민증 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 군민증 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}

	public record CitizenCardCommand(String displayName, UUID characterId, UUID themeId, LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	public record CitizenCardView(UUID cardId, String displayName, CardOptionView character, CardOptionView theme,
			ZonedDateTime issuedAt) {
	}

	private record CatalogOption(UUID id, String name, String imageKey) {
	}

	private record IdempotencyState(String operation, String requestHash, Integer responseStatus, String responseBody,
			Instant expiresAt) {
	}
}
