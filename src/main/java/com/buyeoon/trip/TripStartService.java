package com.buyeoon.trip;

import com.buyeoon.common.api.SuccessResponse;
import com.buyeoon.common.entity.IdempotencyRequestEntity;
import com.buyeoon.common.entity.IdempotencyRequestId;
import com.buyeoon.common.location.BuyeoBoundary;
import com.buyeoon.member.application.IdempotencyKeyReusedException;
import com.buyeoon.member.application.InvalidStateTransitionException;
import com.buyeoon.member.application.OutsideBuyeoException;
import com.buyeoon.member.application.RequiredTermsNotAgreedException;
import com.buyeoon.member.entity.MemberStatus;
import com.buyeoon.trip.entity.TripEntity;
import com.buyeoon.trip.entity.TripStatus;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
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

/** UC-05 여행 시작 커맨드 서비스다. */
@Service
public class TripStartService implements TripStarter {

	private static final String OPERATION = "START_TRIP";
	private static final Duration RETENTION = Duration.ofHours(24);
	private static final ZoneId ASIA_SEOUL = ZoneId.of("Asia/Seoul");

	private final JdbcOperations jdbcOperations;
	private final TransactionTemplate transactions;
	private final MemberRepository members;
	private final TripRepository trips;
	private final TermRepository terms;
	private final CitizenCardRepository citizenCards;
	private final IdempotencyRequestRepository idempotencyRequests;
	private final BuyeoBoundary boundary;
	private final ObjectReader objectReader;
	private final ObjectWriter objectWriter;

	public TripStartService(JdbcOperations jdbcOperations, PlatformTransactionManager transactionManager,
			MemberRepository members, TripRepository trips, TermRepository terms, CitizenCardRepository citizenCards,
			IdempotencyRequestRepository idempotencyRequests, BuyeoBoundary boundary, ObjectMapper objectMapper) {
		this.jdbcOperations = jdbcOperations;
		this.transactions = new TransactionTemplate(transactionManager);
		this.members = members;
		this.trips = trips;
		this.terms = terms;
		this.citizenCards = citizenCards;
		this.idempotencyRequests = idempotencyRequests;
		this.boundary = boundary;
		this.objectReader = objectMapper.reader();
		this.objectWriter = objectMapper.writer();
	}

	@Override
	public TripView start(UUID memberId, String idempotencyKey, TripStartCommand command) {
		validateIdempotencyKey(idempotencyKey);
		if (!boundary.covers(command.location().latitude(), command.location().longitude())) {
			throw new OutsideBuyeoException();
		}
		String requestHash = hash(command);
		return Objects.requireNonNull(
				transactions.execute(status -> startInTransaction(memberId, idempotencyKey, requestHash)),
				"여행 시작 트랜잭션 결과가 없습니다.");
	}

	private TripView startInTransaction(UUID memberId, String idempotencyKey, String requestHash) {
		lockMember(memberId);
		Instant startedAt = clockTimestamp();

		IdempotencyRequestId idempotencyId = new IdempotencyRequestId(memberId, idempotencyKey);
		Optional<IdempotencyRequestEntity> existingRequest = idempotencyRequests.findById(idempotencyId);
		if (existingRequest.isPresent()) {
			IdempotencyRequestEntity request = existingRequest.get();
			if (!request.getExpiresAt().isAfter(startedAt)) {
				idempotencyRequests.delete(request);
			} else {
				return replay(request, requestHash);
			}
		}

		if (!terms.hasAgreedToCurrentRequiredTerms(memberId)) {
			throw new RequiredTermsNotAgreedException();
		}
		if (!citizenCards.existsByMemberId(memberId)) {
			throw new CitizenCardNotIssuedException();
		}
		if (trips.lockByMemberIdAndStatus(memberId, TripStatus.IN_PROGRESS).isPresent()) {
			throw new InvalidStateTransitionException();
		}

		TripEntity trip = trips.saveAndFlush(TripEntity.start(memberId));

		TripView result = new TripView(trip.getId(), trip.getStatus(), trip.getStartedAt().atZone(ASIA_SEOUL), null,
				null);
		String responseBody = writeResponse(result);
		IdempotencyRequestEntity idempotencyRequest = IdempotencyRequestEntity.create(memberId, idempotencyKey,
				OPERATION, requestHash, startedAt.plus(RETENTION));
		idempotencyRequest.complete(201, responseBody);
		idempotencyRequests.save(idempotencyRequest);
		return result;
	}

	private TripView replay(IdempotencyRequestEntity request, String requestHash) {
		if (!OPERATION.equals(request.getOperation()) || !requestHash.equals(request.getRequestHash())) {
			throw new IdempotencyKeyReusedException();
		}
		if (!Integer.valueOf(201).equals(request.getResponseStatus()) || request.getResponseBody() == null) {
			throw new IllegalStateException("완료되지 않은 멱등성 요청이 남아 있습니다.");
		}
		return readResponse(request.getResponseBody());
	}

	private void lockMember(UUID memberId) {
		members.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
				.orElseThrow(() -> new IllegalStateException("인증된 활성 회원을 찾을 수 없습니다."));
	}

	private Instant clockTimestamp() {
		return Objects.requireNonNull(jdbcOperations.queryForObject("SELECT clock_timestamp()", Timestamp.class))
				.toInstant();
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
			throw new InvalidTripStartRequestException();
		}
	}

	private String hash(TripStartCommand command) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

	private String writeResponse(TripView result) {
		try {
			return objectWriter.writeValueAsString(SuccessResponse.of(result));
		} catch (JacksonException exception) {
			throw new IllegalStateException("여행 시작 응답을 저장할 수 없습니다.", exception);
		}
	}

	private TripView readResponse(String responseBody) {
		try {
			JsonNode data = required(objectReader.readTree(responseBody), "data");
			return new TripView(UUID.fromString(requiredText(data, "tripId")),
					TripStatus.valueOf(requiredText(data, "status")),
					ZonedDateTime.parse(requiredText(data, "startedAt")), nullableDateTime(data, "endedAt"),
					nullableDateTime(data, "settledAt"));
		} catch (JacksonException | IllegalArgumentException exception) {
			throw new IllegalStateException("저장된 여행 시작 응답을 읽을 수 없습니다.", exception);
		}
	}

	private ZonedDateTime nullableDateTime(JsonNode data, String field) {
		JsonNode value = data.get(field);
		return value == null || value.isNull() ? null : ZonedDateTime.parse(value.stringValue());
	}

	private JsonNode required(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null) {
			throw new IllegalStateException("저장된 여행 시작 응답 필드가 누락되었습니다: " + field);
		}
		return value;
	}

	private String requiredText(JsonNode node, String field) {
		JsonNode value = required(node, field);
		if (!value.isString()) {
			throw new IllegalStateException("저장된 여행 시작 응답 필드가 문자열이 아닙니다: " + field);
		}
		return value.stringValue();
	}

	public record TripStartCommand(LocationCommand location) {
	}

	public record LocationCommand(double latitude, double longitude, Double accuracyMeters, OffsetDateTime capturedAt) {
	}

	public record TripView(UUID tripId, TripStatus status, ZonedDateTime startedAt, ZonedDateTime endedAt,
			ZonedDateTime settledAt) {
	}
}
